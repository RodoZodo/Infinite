package rodozodo.infinite;

import rodozodo.infinite.importers.Imports;
import rodozodo.infinite.importers.LodImport;
import rodozodo.infinite.storage.LodStorage;
import rodozodo.infinite.thread.WorkerPool;
import rodozodo.infinite.util.NativeBuffer;
import rodozodo.infinite.world.LodWorld;
import rodozodo.infinite.world.SaveQueue;
import rodozodo.infinite.world.voxelize.IngestQueue;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public abstract class LodEngine {
    private static final int IMPORT_SAVE_BACKLOG = 1200;
    private static final long CLOSE_WAIT_MILLIS = 10_000;

    protected final WorkerPool workers;
    protected final SaveQueue saveQueue;
    protected final IngestQueue ingestQueue;
    protected final Imports imports;
    private final long worldIdleBytes;

    private final Map<LodWorldKey, LodWorld> worlds = new HashMap<>();
    private final Set<LodWorldKey> closingWorlds = new HashSet<>();
    private final Set<LodWorldKey> unopenable = new HashSet<>();
    private final ScheduledExecutorService janitor;
    private volatile boolean running = true;

    protected LodEngine(BooleanSupplier ingestMayRun) {
        this(ingestMayRun, IngestQueue.DEFAULT_MAX_QUEUED, 0);
    }

    protected LodEngine(BooleanSupplier ingestMayRun, int ingestLimit, long worldIdleBytes) {
        Log.info("Starting Infinite " + Infinite.VERSION);
        this.workers = new WorkerPool("Infinite worker");
        this.saveQueue = new SaveQueue(this.workers);
        this.ingestQueue = new IngestQueue(this.workers, ingestMayRun, ingestLimit);
        this.worldIdleBytes = worldIdleBytes;
        this.imports = new Imports(this::importListener);
        this.janitor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "Infinite world closer");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        this.janitor.scheduleWithFixedDelay(this::closeIdleWorlds, 1, 1, TimeUnit.SECONDS);
    }

    protected abstract LodStorage openStorage(LodWorldKey id);

    protected abstract int dedicatedThreadTarget();

    public abstract boolean ingestEnabled();

    protected LodImport.Listener importListener(LodImport<?, ?> job) {
        return new LodImport.Listener() {
            @Override
            public void progress(long done, long expected) {}

            @Override
            public void finished(LodImport.Result result) {}
        };
    }

    public final void updateDedicatedThreads() {
        int threads = Math.max(0, this.dedicatedThreadTarget());
        this.workers.setDedicatedThreads(threads);
    }

    public WorkerPool workers() {
        return this.workers;
    }

    public IngestQueue ingestQueue() {
        return this.ingestQueue;
    }

    public Imports imports() {
        return this.imports;
    }

    public boolean isRunning() {
        return this.running;
    }

    public boolean saveQueueHasRoom() {
        return this.saveQueue.pending() < IMPORT_SAVE_BACKLOG;
    }

    public @Nullable LodWorld world(LodWorldKey id) {
        return this.find(id, true);
    }

    public @Nullable LodWorld openWorld(LodWorldKey id) {
        return this.find(id, false);
    }

    private @Nullable LodWorld find(LodWorldKey id, boolean open) {
        var cached = id.resolvedIn(this);
        if (cached != null) {
            try {
                cached.markActive();
                return cached;
            } catch (IllegalStateException closedMeanwhile) {
            }
        }
        LodWorld world;
        synchronized (this.worlds) {
            while (open && this.running && this.closingWorlds.contains(id)) {
                try {
                    this.worlds.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (!this.running || this.closingWorlds.contains(id)) return null;
            world = this.worlds.get(id);
            if (world == null && open) {
                world = this.open(id);
                if (world != null) this.worlds.put(id, world);
            }
            if (world == null) return null;
            // Mark active under lock so closeIdleWorlds() cannot close it before use
            world.markActive();
        }
        id.resolvedTo(world);
        return world;
    }

    // Caller must hold worlds lock
    private @Nullable LodWorld open(LodWorldKey id) {
        if (this.unopenable.contains(id)) return null;
        try {
            return new LodWorld(this.openStorage(id), this.saveQueue, this, this.worldIdleBytes);
        } catch (RuntimeException e) {
            this.unopenable.add(id);
            Log.error("Could not open LoD world " + id, e);
            return null;
        }
    }

    private void closeIdleWorlds() {
        try {
            List<ClosingWorld> toClose = new ArrayList<>();
            synchronized (this.worlds) {
                for (var it = this.worlds.entrySet().iterator(); it.hasNext(); ) {
                    var entry = it.next();
                    var world = entry.getValue();
                    boolean idle;
                    try {
                        idle = world.isIdle() && world.beginClose();
                    } catch (RuntimeException e) {
                        Log.error("Could not close LoD world " + entry.getKey(), e);
                        continue;
                    }
                    if (!idle) continue;
                    it.remove();
                    this.closingWorlds.add(entry.getKey());
                    toClose.add(new ClosingWorld(entry.getKey(), world));
                }
            }
            for (var close : toClose) {
                try {
                    close.world.finishClose();
                } catch (RuntimeException e) {
                    Log.error("Could not close LoD world " + close.id, e);
                } finally {
                    synchronized (this.worlds) {
                        this.closingWorlds.remove(close.id);
                        this.worlds.notifyAll();
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.error("Could not close idle LoD world", e);
        }
    }

    public void addDebug(List<String> lines) {
        long inUse = 0, idle = 0;
        int worldCount;
        synchronized (this.worlds) {
            worldCount = this.worlds.size();
            for (var world : this.worlds.values()) {
                inUse += world.sectionsInUse();
                idle += world.idleSections();
            }
        }
        lines.add("  Sections: " + inUse + " active · " + idle + " cached · " + (NativeBuffer.liveBytes() >> 20) + " MiB");
        lines.add("  Workers: " + this.ingestQueue.pending() + " building · " + this.saveQueue.pending() + " saving · "
                + (this.workers.dedicatedThreads() + this.workers.helperLanes()) + " threads");
    }

    public void shutdown() {
        this.imports.cancelAll();
        this.running = false;
        this.janitor.shutdownNow();
        try {
            if (!this.janitor.awaitTermination(30, TimeUnit.SECONDS)) Log.warn("Idle world closer did not stop");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stopQuietly("the ingest queue", this.ingestQueue::shutdown);
        stopQuietly("the save queue", this.saveQueue::shutdown);

        List<Map.Entry<LodWorldKey, LodWorld>> open;
        synchronized (this.worlds) {
            open = new ArrayList<>(this.worlds.entrySet());
            this.worlds.clear();
        }
        for (var entry : open) {
            var world = entry.getValue();
            if (!awaitUnused(world)) {
                Log.error("LoD world " + entry.getKey() + " still in use (" + world.sectionsInUse() + " sections); leaving open");
                continue;
            }
            stopQuietly("the LoD world of " + entry.getKey(), world::close);
        }
        stopQuietly("the worker threads", this.workers::shutdown);
        Log.info("Stopped Infinite");
    }

    public record Stats(long activeSections, long cachedSections, long nativeBytes, int building, int saving) {}

    public Stats stats() {
        long inUse = 0, idle = 0;
        synchronized (this.worlds) {
            for (var world : this.worlds.values()) {
                inUse += world.sectionsInUse();
                idle += world.idleSections();
            }
        }
        return new Stats(inUse, idle, NativeBuffer.liveBytes(), this.ingestQueue.pending(), this.saveQueue.pending());
    }
    
    private static boolean awaitUnused(LodWorld world) {
        long deadline = System.currentTimeMillis() + CLOSE_WAIT_MILLIS;
        while (world.isInUse()) {
            if (System.currentTimeMillis() > deadline) return false;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private record ClosingWorld(LodWorldKey id, LodWorld world) {}

    private static void stopQuietly(String what, Runnable stop) {
        try {
            stop.run();
        } catch (RuntimeException e) {
            Log.error("Could not stop " + what, e);
        }
    }
}
