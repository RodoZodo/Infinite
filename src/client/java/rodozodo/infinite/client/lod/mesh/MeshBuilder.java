package rodozodo.infinite.client.lod.mesh;

import rodozodo.infinite.client.compat.SodiumBackpressure;
import rodozodo.infinite.client.model.BlockModels;
import rodozodo.infinite.client.render.SodiumSight;
import rodozodo.infinite.thread.JobQueue;
import rodozodo.infinite.thread.WorkerPool;
import rodozodo.infinite.world.LodPos;
import rodozodo.infinite.world.LodSection;
import rodozodo.infinite.world.LodWorld;
import rodozodo.infinite.Log;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;


// Builds LoD section meshes asynchronously on shared worker threads

public final class MeshBuilder {
    private static final long RETRY_DELAY_NANOS = 25_000_000L;
    private static final long REBUILD_NANOS = 400_000_000L;
    private static final int STARTED_PRUNE_AT = 1 << 16;
    private static final int MAX_RETRY_STEPS = 8;
    private static final int MAX_PREEMPTIONS = 3;
    private static final long REORDER_NANOS = 500_000_000L;
    private static final int PREFETCH_QUEUE = 4096;

    private final LodWorld world;
    private final BlockModels models;
    private final boolean cullCaves;
    private final @Nullable MeshCache cache;
    private static final Map<@Nullable Path, Thread> STOPPING = new HashMap<>();
    private final JobQueue workQueue;
    private volatile Consumer<LodMesh> output = LodMesh::free;

    private final ReentrantLock lock = new ReentrantLock();
    private final Long2ObjectOpenHashMap<Job> jobs = new Long2ObjectOpenHashMap<>();
    private PriorityQueue<Job> queue = new PriorityQueue<>();
    private final PriorityQueue<Job> waiting = new PriorityQueue<>(Comparator.comparingLong(job -> job.retryAt));
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap started = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    {
        this.started.defaultReturnValue(Long.MIN_VALUE / 2);
    }
    private long nextSequence;
    private volatile int queued, retrying, cooling;
    private final LongArrayFIFOQueue prefetch = new LongArrayFIFOQueue();
    private volatile int prefetchQueued;
    private final LongOpenHashSet prefetchDeep = new LongOpenHashSet();
    private final LongAdder busyNanos = new LongAdder();
    private volatile boolean reorderWanted;
    private volatile int seenSight = SodiumSight.generation();
    private volatile long nextReorder;

    private static final class Job implements Comparable<Job> {
        final long position;
        final int level;
        int attempts;
        int preemptions;
        boolean inSight;
        double groupDistanceSq;
        double distanceSq;
        long sequence;
        long retryAt;
        boolean cooling;

        Job(long position, long sequence) {
            this.position = position;
            this.level = LodPos.level(position);
            this.sequence = sequence;
            followView(this);
        }

        @Override
        public int compareTo(Job other) {
            if (this.inSight != other.inSight) return this.inSight ? -1 : 1;
            if (this.groupDistanceSq != other.groupDistanceSq) return Double.compare(this.groupDistanceSq, other.groupDistanceSq);
            if (this.level != other.level) return Integer.compare(this.level, other.level);
            if (this.distanceSq != other.distanceSq) return Double.compare(this.distanceSq, other.distanceSq);
            int attempts = Math.min(this.attempts, 3), otherAttempts = Math.min(other.attempts, 3);
            if (attempts != otherAttempts) return Integer.compare(attempts, otherAttempts);
            return Long.compare(this.sequence, other.sequence);
        }
    }

    private static void followView(Job job) {
        long group = job.level < LodPos.MAX_LEVEL ? LodPos.parent(job.position) : job.position;
        job.inSight = SodiumSight.seesLod(group);
        job.groupDistanceSq = SodiumSight.nearestDistanceSq(group);
        job.distanceSq = SodiumSight.distanceSq(job.position);
    }

    public MeshBuilder(LodWorld world, BlockModels models, WorkerPool workers, boolean cullCaves) {
        this.world = world;
        this.models = models;
        this.cullCaves = cullCaves;
        this.cache = MeshCache.open(world, models, cullCaves);
        this.workQueue = workers.newQueue("LOD section meshing", 10, SodiumBackpressure::meshMayRun, () -> {
            var mesher = new SectionMesher(world, models, cullCaves);
            return () -> this.buildNext(mesher);
        });
    }

    public void setOutput(Consumer<LodMesh> output) {
        this.output = output;
    }

    public void request(long position) {
        if (!this.workQueue.isLive()) return;
        this.lock.lock();
        try {
            if (this.jobs.containsKey(position)) return;
            var job = new Job(position, this.nextSequence++);
            this.jobs.put(position, job);
            long again = this.started.get(position) + REBUILD_NANOS;
            if (again - System.nanoTime() > 0) {
                job.retryAt = again;
                job.cooling = true;
                this.cooling++;
                this.waiting.add(job);
                this.updateCounts();
                return;
            }
            this.queue.add(job);
            this.updateCounts();
        } finally {
            this.lock.unlock();
        }
        this.workQueue.submit();
    }

    public void prefetch(long[] positions, int count, boolean deep) {
        if (this.cache == null || !this.workQueue.isLive()) return;
        int added = 0;
        this.lock.lock();
        try {
            for (int i = 0; i < count && this.prefetch.size() < PREFETCH_QUEUE; i++, added++) {
                this.prefetch.enqueue(positions[i]);
                if (deep) this.prefetchDeep.add(positions[i]);
            }
            this.prefetchQueued = this.prefetch.size();
        } finally {
            this.lock.unlock();
        }
        for (int i = 0; i < added; i++) {
            if (!this.workQueue.submit()) break;
        }
    }

    private void prefetchChildren(long position, int childMask) {
        int level = LodPos.level(position);
        if (level == 0 || childMask == 0 || !this.workQueue.isLive()) return;
        int added = 0;
        this.lock.lock();
        try {
            for (int index = 0; index < 8; index++) {
                if ((childMask >>> index & 1) == 0) continue;
                // Bit order matches LodTree.child: bit 0 = x, bit 1 = z, bit 2 = y.
                this.prefetch.enqueue(LodPos.pack(level - 1, LodPos.x(position) << 1 | index & 1,
                        LodPos.y(position) << 1 | index >> 2 & 1, LodPos.z(position) << 1 | index >> 1 & 1));
                added++;
            }
            this.prefetchQueued = this.prefetch.size();
        } finally {
            this.lock.unlock();
        }
        for (int i = 0; i < added; i++) {
            if (!this.workQueue.submit()) break;
        }
    }

    public int prefetchRoom() {
        return this.cache == null ? 0 : PREFETCH_QUEUE - this.prefetchQueued;
    }

    public void dropPrefetch() {
        if (this.prefetchQueued == 0) return;
        this.lock.lock();
        try {
            this.prefetch.clear();
            this.prefetchDeep.clear();
            this.prefetchQueued = 0;
        } finally {
            this.lock.unlock();
        }
    }

    public void tick() {
        if (!this.reorderWanted && SodiumSight.generation() != this.seenSight && System.nanoTime() >= this.nextReorder) {
            this.reorderWanted = true;
            this.workQueue.submit();
        }
        if (this.retrying == 0 && this.cooling == 0) return;
        int ready = 0;
        long now = System.nanoTime();
        this.lock.lock();
        try {
            while (!this.waiting.isEmpty() && this.waiting.peek().retryAt <= now) {
                var job = this.waiting.poll();
                if (job.cooling) {
                    job.cooling = false;
                    this.cooling--;
                }
                job.sequence = this.nextSequence++;
                followView(job);
                this.queue.add(job);
                ready++;
            }
            if (ready != 0) this.updateCounts();
        } finally {
            this.lock.unlock();
        }
        for (int i = 0; i < ready; i++) {
            if (!this.workQueue.submit()) break;
        }
    }

    private void reorderForSight() {
        int generation = SodiumSight.generation();
        long now = System.nanoTime();
        this.lock.lock();
        try {
            if (generation == this.seenSight) {
                this.reorderWanted = false;
                return;
            }
            if (now < this.nextReorder) return;
            this.reorderWanted = false;
            this.seenSight = generation;
            this.nextReorder = now + REORDER_NANOS;
            if (this.queue.isEmpty()) return;
            var jobs = this.queue.toArray(new Job[0]);
            for (var job : jobs) followView(job);
            this.queue = new PriorityQueue<>(List.of(jobs));
        } finally {
            this.lock.unlock();
        }
    }

    private boolean buildNext(SectionMesher mesher) {
        long started = System.nanoTime();
        try {
            return this.build(mesher);
        } finally {
            this.busyNanos.add(System.nanoTime() - started);
        }
    }

    private boolean build(SectionMesher mesher) {
        if (this.reorderWanted) this.reorderForSight();
        Job job;
        long prefetched;
        boolean deep = false;
        this.lock.lock();
        try {
            job = this.queue.poll();
            if (job == null) {
                if (this.prefetch.isEmpty()) return true;
                prefetched = this.prefetch.dequeueLong();
                deep = this.prefetchDeep.remove(prefetched);
                this.prefetchQueued = this.prefetch.size();
            } else {
                prefetched = 0;
                this.jobs.remove(job.position);
                this.noteStarted(job.position);
                this.updateCounts();
            }
        } finally {
            this.lock.unlock();
        }
        if (job == null) {
            this.prefetchOne(prefetched, mesher, deep);
            return true;
        }

        var cache = this.cache;
        long key = cache == null ? 0 : cache.key(job.position);
        if (key != 0) {
            var cached = cache.load(job.position, key);
            if (cached == MeshCache.WAITING) {
                this.retryLater(job);
                return true;
            }
            if (cached != null) {
                this.output.accept(cached);
                return true;
            }
        }

        var section = this.world.acquireIfStoredForReading(job.position);
        if (section == null) {
            this.output.accept(LodMesh.empty(job.position, (byte) 0));
            return true;
        }
        LodMesh mesh;
        long[] early = key == 0 && cache != null ? cache.inputs(job.position) : null;
        try {
            mesh = mesher.mesh(section, job.preemptions < MAX_PREEMPTIONS);
        } finally {
            section.release();
        }
        if (mesh != null) {
            if (key != 0) {
                long now = cache.key(job.position);
                if (now == key) cache.save(mesh, key);
                else if (now != 0) MeshCache.unsettled();
            } else if (early != null) {
                cache.fileEarly(mesh, early);
            }
            this.output.accept(mesh);
            return true;
        }
        if (mesher.abandoned()) {
            return !this.preempted(job);
        }
        for (int block : mesher.missingBlocks()) {
            this.models.request(block);
        }
        this.retryLater(job);
        return true;
    }

    private void prefetchOne(long position, SectionMesher mesher, boolean deep) {
        var cache = this.cache;
        if (cache == null) return;
        this.lock.lock();
        try {
            if (this.jobs.containsKey(position)) return;
        } finally {
            this.lock.unlock();
        }
        long key = cache.key(position);
        if (key == 0) return;
        int filed = cache.filedChildMask(position, key);
        if (filed >= 0) {
            if (deep) this.prefetchChildren(position, filed);
            return;
        }
        var section = this.world.acquireIfStoredForReading(position);
        if (section == null) return;
        LodMesh mesh;
        try {
            mesh = mesher.mesh(section, true);
        } finally {
            section.release();
        }
        if (mesh == null) return;
        if (cache.key(position) == key) cache.save(mesh, key);
        int childMask = mesh.childMask & 0xFF;
        mesh.free();
        if (deep) this.prefetchChildren(position, childMask);
    }

    private boolean preempted(Job job) {
        this.lock.lock();
        try {
            if (this.jobs.containsKey(job.position) || !this.workQueue.isLive()) return false;
            job.preemptions++;
            followView(job);
            this.jobs.put(job.position, job);
            this.queue.add(job);
            this.updateCounts();
            return true;
        } finally {
            this.lock.unlock();
        }
    }

    private void retryLater(Job job) {
        this.lock.lock();
        try {
            if (this.jobs.containsKey(job.position) || !this.workQueue.isLive()) return;
            job.attempts++;
            job.retryAt = System.nanoTime() + RETRY_DELAY_NANOS * Math.min(job.attempts, MAX_RETRY_STEPS);
            this.jobs.put(job.position, job);
            this.waiting.add(job);
            this.updateCounts();
        } finally {
            this.lock.unlock();
        }
    }

    private void noteStarted(long position) {
        long now = System.nanoTime();
        if (this.started.size() >= STARTED_PRUNE_AT) {
            this.started.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() >= REBUILD_NANOS);
        }
        this.started.put(position, now);
    }

    private void updateCounts() {
        this.queued = this.queue.size();
        this.retrying = this.waiting.size() - this.cooling;
    }

    public long busyNanos() {
        return this.busyNanos.sum();
    }

    public int backlog() {
        return this.queued + this.retrying;
    }

    public void addDebugInfo(List<String> lines) {
        lines.add("  Meshing: " + this.queued + " queued"
                + (this.retrying > 0 ? " · " + this.retrying + " waiting for models" : ""));
        String cached = this.cache == null ? null : MeshCache.describe();
        if (cached != null) lines.add(cached);
    }

    public void shutdown() {
        awaitStopping();
        this.stop();
    }

    public void shutdownInBackground(Runnable then) {
        var directory = this.cache == null ? null : this.cache.directory();
        if (this.cache != null) this.cache.stopping();
        Thread thread;
        synchronized (STOPPING) {
            var previous = STOPPING.get(directory);
            thread = Thread.ofPlatform().name("Infinite LOD renderer shutdown").unstarted(() -> {
                try {
                    if (previous != null) join(previous);
                    this.stop();
                    then.run();
                } catch (RuntimeException e) {
                    Log.error("Error shutting down LoD renderer", e);
                } finally {
                    synchronized (STOPPING) {
                        STOPPING.remove(directory, Thread.currentThread());
                    }
                }
            });
            STOPPING.put(directory, thread);
        }
        thread.start();
    }

    public static void awaitStopping() {
        List<Thread> stopping;
        synchronized (STOPPING) {
            stopping = List.copyOf(STOPPING.values());
        }
        // Join outside STOPPING because each shutdown thread acquires STOPPING to unregister itself
        for (var thread : stopping) join(thread);
    }

    static void awaitStopping(Path directory) {
        Thread stopping;
        synchronized (STOPPING) {
            stopping = STOPPING.get(directory);
        }
        if (stopping != null) join(stopping);
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stop() {
        this.workQueue.shutdown();

        // Close cache only after workQueue stops so no worker writes to a closed store.
        if (this.cache != null) this.cache.close();
        this.lock.lock();
        try {
            this.jobs.clear();
            this.queue.clear();
            this.waiting.clear();
            this.prefetch.clear();
            this.prefetchDeep.clear();
            this.prefetchQueued = 0;
            this.cooling = 0;
            this.updateCounts();
        } finally {
            this.lock.unlock();
        }
    }
}
