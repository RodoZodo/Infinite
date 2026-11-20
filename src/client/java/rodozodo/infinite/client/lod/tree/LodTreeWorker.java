package rodozodo.infinite.client.lod.tree;

import rodozodo.infinite.Log;
import rodozodo.infinite.client.lod.SectionWatch;
import rodozodo.infinite.client.lod.mesh.LodMesh;
import rodozodo.infinite.client.lod.mesh.MeshBuilder;
import rodozodo.infinite.client.render.SodiumSight;
import rodozodo.infinite.util.NativeBuffer;
import rodozodo.infinite.world.LodPos;
import rodozodo.infinite.world.LodSection;
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;


// Owns the {@link LodTree} on a dedicated worker thread, applying world updates, GPU requests, evictions, and finished meshes
// and batching the resulting GPU changes for the render thread to consume each frame.

public final class LodTreeWorker {
    public static final int EVICTION_BATCH = 256;
    private static final boolean VERIFY = Boolean.getBoolean("infinite.verifyTree");
    private static final long MESH_RESERVE_BYTES = 48L << 20;
    private static final long ROUND_UPLOAD_BYTES = 1L << 20;
    private static final float PREFETCH_NEAR_SPLIT = 0.18f;
    private static final float PREFETCH_ZOOM = Float.parseFloat(System.getProperty("infinite.prefetchZoom", "4"));
    private static final long COMPACT_QUADS = (8L << 20) / 8;
    private static final boolean COMPACT = !"false".equals(System.getProperty("infinite.compactPages"));
    private static final int PREFETCH_SENT_LIMIT = 1 << 16;

    private record TopLevelChange(long position, boolean added) {}

    public record View(Matrix4fc viewProjection, int sectionX, int sectionY, int sectionZ,
                       float subX, float subY, float subZ, float splitArea) {}

    public final int maxNodes;
    private final long geometryCapacity;
    private final GeometryAllocator geometry;
    private final LodTree tree;
    private final SectionWatch watch;
    private final MeshBuilder meshBuilder;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile Throwable failure;

    private volatile View view;
    private final AtomicBoolean signalled = new AtomicBoolean();
    private final ConcurrentLinkedQueue<NativeBuffer> retiredUploads = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TopLevelChange> topLevelChanges = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<LodMesh> meshes = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<LodSection> childChanges = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<long[]> requests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<long[]> aroundRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<long[]> evictions = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<long[]> unused = new ConcurrentLinkedQueue<>();
    private volatile boolean prefetchWanted;
    private final AtomicBoolean prefetchDropped = new AtomicBoolean();

    private final ArrayDeque<LodMesh> deferred = new ArrayDeque<>();
    private final IntArrayList topNodeChanges = new IntArrayList();
    private final Int2BooleanOpenHashMap seenChanges = new Int2BooleanOpenHashMap();
    private final Int2BooleanOpenHashMap fadeChanges = new Int2BooleanOpenHashMap();
    private final LongOpenHashSet prefetchSent = new LongOpenHashSet();
    private final LongArrayList prefetchLeaves = new LongArrayList();
    private final FloatArrayList prefetchAreas = new FloatArrayList();
    private final IntArrayList prefetchMasks = new IntArrayList();
    private final LongArrayList zoomLeaves = new LongArrayList();
    private final FloatArrayList zoomAreas = new FloatArrayList();
    private final IntArrayList zoomMasks = new IntArrayList();
    private final float[] corners = new float[16];

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition taken = this.lock.newCondition();
    private PendingGpuWork filling = new PendingGpuWork();
    private PendingGpuWork spare = new PendingGpuWork();
    private final IntArrayList seenBatch = new IntArrayList();
    private final IntArrayList fadeBatch = new IntArrayList();
    private volatile long batchesTaken;
    private long movesPublishedAt;
    private volatile boolean movesHeld;
    private final long[] pagesInUse, pagesPublished;

    private volatile int slotLimit;
    private volatile long usedGeometryBytes;
    private volatile int sectionCount;
    private volatile String treeSummary = "";
    private volatile String spaceSummary = "";
    private volatile boolean spaceWanted;

    public LodTreeWorker(int maxNodes, int maxSections, long geometryBytes, long pageBytes, MeshBuilder meshBuilder) {
        this.maxNodes = maxNodes;
        this.geometryCapacity = geometryBytes;
        this.geometry = new GeometryAllocator(maxSections, geometryBytes, pageBytes);
        this.pagesInUse = new long[(this.geometry.pages() + 63) >> 6];
        this.pagesPublished = new long[this.pagesInUse.length];
        this.meshBuilder = meshBuilder;
        this.watch = new SectionWatch(meshBuilder::request, section -> {
            this.childChanges.add(section);
            this.signal();
        });
        this.tree = new LodTree(maxNodes, this.geometry, this.watch, new LodTree.Listener() {
            @Override
            public void topNodeAdded(int node) {
                LodTreeWorker.this.topNodeChanges.add(node);
            }

            @Override
            public void topNodeRemoved(int node) {
                LodTreeWorker.this.topNodeChanges.add(node | 0x80000000);
            }

            @Override
            public void slotFilled(int node) {
                LodTreeWorker.this.seenChanges.put(node, true);
                LodTreeWorker.this.fadeChanges.put(node, false);
            }

            @Override
            public void slotFreed(int node) {
                LodTreeWorker.this.seenChanges.put(node, false);
                LodTreeWorker.this.fadeChanges.put(node, false);
            }

            @Override
            public void childrenRevealed(int node) {
                LodTreeWorker.this.fadeChanges.put(node, true);
            }
        });
        meshBuilder.setOutput(this::meshBuilt);
        this.thread = new Thread(this::run, "LOD tree");
        this.thread.setDaemon(true);
    }

    public void start() {
        this.thread.start();
    }

    private void signal() {
        if (!this.signalled.getAndSet(true)) LockSupport.unpark(this.thread);
    }

    public void addTopLevel(long position) {
        this.topLevelChanges.add(new TopLevelChange(position, true));
        this.signal();
    }

    public void removeTopLevel(long position) {
        this.topLevelChanges.add(new TopLevelChange(position, false));
        this.signal();
    }

    private void meshBuilt(LodMesh mesh) {
        this.meshes.add(mesh);
        if (this.running) {
            this.signal();
        } else {
            freeAll(this.meshes);
        }
    }

    public void setView(View view) {
        this.view = view;
    }

    public void requested(long[] positions) {
        if (positions.length == 0) return;
        this.requests.add(positions);
        this.signal();
    }

    public void requestedAround(long[] positions) {
        if (positions.length == 0) return;
        this.aroundRequests.add(positions);
        this.signal();
    }

    public void prefetch() {
        this.prefetchWanted = true;
        this.signal();
    }

    public void dropPrefetch() {
        this.prefetchDropped.set(true);
        this.signal();
    }

    public void evict(long[] positions) {
        this.evictions.add(positions);
        this.signal();
    }

    public void dropUnused(long[] positions) {
        this.unused.add(positions);
        this.signal();
    }

    public void worldEvent(LodSection section, int kinds, int neighbours) {
        this.watch.sectionUpdated(section, kinds, neighbours);
    }

    private void run() {
        try {
            while (this.running) {
                this.signalled.set(false);
                if (this.round()) {
                    this.publish();
                } else if (!this.hasInput() && !this.signalled.get()) {

                    // Check signalled after round() because locks taken inside round() may consume an unpark permit
                    LockSupport.park(this);
                }
            }
        } catch (Throwable e) {
            this.failure = e;
            this.running = false;
            Log.error("LoD tree thread failed; updates stopped", e);
        }
    }

    private boolean hasInput() {
        return !this.topLevelChanges.isEmpty() || !this.childChanges.isEmpty()
                || !this.requests.isEmpty() || !this.aroundRequests.isEmpty() || !this.evictions.isEmpty() || !this.unused.isEmpty()
                || this.prefetchWanted || this.prefetchDropped.get()
                || (!this.meshes.isEmpty() && this.hasMeshSpace());
    }

    private boolean hasMeshSpace() {
        return this.geometryCapacity - this.geometry.usedBytes() > MESH_RESERVE_BYTES;
    }

    private boolean round() {
        for (NativeBuffer retired; (retired = this.retiredUploads.poll()) != null; ) retired.free();
        boolean changed = false;
        for (TopLevelChange change; (change = this.topLevelChanges.poll()) != null; changed = true) {
            if (change.added) this.tree.addTopLevel(change.position);
            else this.tree.removeTopLevel(change.position);
        }
        for (LodSection section; (section = this.childChanges.poll()) != null; changed = true) {
            byte mask;
            try {
                mask = section.childMask();
            } finally {
                section.release();
            }
            this.tree.childrenChanged(section.key, mask);
        }
        for (long[] batch; (batch = this.evictions.poll()) != null; changed = true) {
            for (long position : batch) {
                if (position != -1) this.tree.evict(position);
            }
        }
        for (long[] batch; (batch = this.unused.poll()) != null; changed = true) {
            for (long position : batch) this.tree.dropUnused(position);
        }
        for (long[] batch; (batch = this.requests.poll()) != null; changed = true) {
            for (long position : batch) this.tree.requested(position, this.depthFor(position));
        }
        for (long[] batch; (batch = this.aroundRequests.poll()) != null; changed = true) {
            for (long position : batch) this.tree.requested(position, 1);
        }

        long stored = 0;
        if (changed) {
            for (int i = this.deferred.size(); i > 0 && this.hasMeshSpace(); i--) {
                var mesh = this.deferred.poll();
                long bytes = mesh.quadCount() * 8L;
                if (this.tree.meshBuilt(mesh)) {
                    stored += bytes;
                } else {
                    this.deferred.add(mesh);
                }
            }
        }
        while (stored < ROUND_UPLOAD_BYTES && this.hasMeshSpace()) {
            var mesh = this.meshes.poll();
            if (mesh == null) break;
            changed = true;
            long bytes = mesh.quadCount() * 8L;
            if (this.tree.meshBuilt(mesh)) {
                stored += bytes;
            } else {
                this.deferred.add(mesh);
            }
        }

        if (this.prefetchDropped.getAndSet(false)) {
            this.prefetchSent.clear();
            this.meshBuilder.dropPrefetch();
        }
        if (this.prefetchWanted) {
            this.prefetchWanted = false;
            this.prefetchNearSplit();
        }

        if (COMPACT && this.compact()) changed = true;

        if (VERIFY && changed) this.tree.verify();
        return changed;
    }

    private boolean compact() {
        boolean changed = false;
        if (this.geometry.holdsMoved()) {

            // Wait until the render thread consumes the move batch before freeing source quad runs
            if (this.batchesTaken <= this.movesPublishedAt) return false;
            this.geometry.releaseMoved();
            this.movesHeld = false;
            changed = true;
        }
        if (!this.meshes.isEmpty() || !this.deferred.isEmpty() || this.meshBuilder.backlog() != 0) return changed;
        if (this.geometry.compact(COMPACT_QUADS)) {
            changed = true;
            if (this.geometry.holdsMoved()) {
                this.movesPublishedAt = Long.MAX_VALUE;
                this.movesHeld = true;
            }
        }
        return changed;
    }

    private int depthFor(long position) {
        View view = this.view;
        if (view == null) return 1;
        float area = this.areaOf(view, position);
        if (!(area > view.splitArea())) return 1;
        int depth = (int) Math.ceil(Math.log(area / view.splitArea()) / Math.log(4.0));
        return Math.clamp(depth, 1, LodTree.MAX_DEPTH);
    }

    private float areaOf(View view, long position) {
        int level = LodPos.level(position);
        float size = 32f * (1 << level);
        float x = ((LodPos.x(position) << level) - view.sectionX()) * 32f - view.subX();
        float y = ((LodPos.y(position) << level) - view.sectionY()) * 32f - view.subY();
        float z = ((LodPos.z(position) << level) - view.sectionZ()) * 32f - view.subZ();
        return projectedArea(view.viewProjection(), x, y, z, size, this.corners);
    }

    private static boolean inZoom(View view, long position, float zoom) {
        int level = LodPos.level(position);
        float half = 16f * (1 << level);
        float x = ((LodPos.x(position) << level) - view.sectionX()) * 32f - view.subX() + half;
        float y = ((LodPos.y(position) << level) - view.sectionY()) * 32f - view.subY() + half;
        float z = ((LodPos.z(position) << level) - view.sectionZ()) * 32f - view.subZ() + half;
        var m = view.viewProjection();
        float w = m.m03() * x + m.m13() * y + m.m23() * z + m.m33();
        if (w <= 1e-4f) return false;
        float limit = w / zoom;
        return Math.abs(m.m00() * x + m.m10() * y + m.m20() * z + m.m30()) < limit
                && Math.abs(m.m01() * x + m.m11() * y + m.m21() * z + m.m31()) < limit;
    }

    private static float projectedArea(Matrix4fc m, float ox, float oy, float oz, float size, float[] uv) {
        for (int i = 0; i < 8; i++) {
            float x = ox + ((i & 1) != 0 ? size : 0);
            float y = oy + ((i & 2) != 0 ? size : 0);
            float z = oz + ((i & 4) != 0 ? size : 0);
            float w = m.m03() * x + m.m13() * y + m.m23() * z + m.m33();
            if (w <= 1e-4f) return Float.MAX_VALUE;
            uv[2 * i] = (m.m00() * x + m.m10() * y + m.m20() * z + m.m30()) / w * 0.5f + 0.5f;
            uv[2 * i + 1] = (m.m01() * x + m.m11() * y + m.m21() * z + m.m31()) / w * 0.5f + 0.5f;
        }
        float area = 0;
        for (int pass = 0; pass < 2; pass++) {
            int corner = pass == 0 ? 0 : 7;
            float ax = uv[2 * (corner ^ 1)] - uv[2 * corner], ay = uv[2 * (corner ^ 1) + 1] - uv[2 * corner + 1];
            float bx = uv[2 * (corner ^ 2)] - uv[2 * corner], by = uv[2 * (corner ^ 2) + 1] - uv[2 * corner + 1];
            float cx = uv[2 * (corner ^ 4)] - uv[2 * corner], cy = uv[2 * (corner ^ 4) + 1] - uv[2 * corner + 1];
            area += Math.abs(ax * by - ay * bx) + Math.abs(ax * cy - ay * cx) + Math.abs(bx * cy - by * cx);
        }
        return area * 0.5f;
    }

    private void prefetchNearSplit() {
        View view = this.view;
        int room = this.meshBuilder.prefetchRoom();
        if (view == null || room < 8) return;
        if (this.prefetchSent.size() > PREFETCH_SENT_LIMIT) this.prefetchSent.clear();
        float split = view.splitArea(), near = split * PREFETCH_NEAR_SPLIT;
        var leaves = this.prefetchLeaves;
        var areas = this.prefetchAreas;
        var masks = this.prefetchMasks;
        leaves.clear();
        areas.clear();
        masks.clear();
        this.zoomLeaves.clear();
        this.zoomAreas.clear();
        this.zoomMasks.clear();
        float zoom = PREFETCH_ZOOM, zoomed = zoom * zoom;
        this.tree.forEachExpandable((position, mask) -> {
            if (this.prefetchSent.contains(position) || !SodiumSight.seesLod(position)) return;
            float area = this.areaOf(view, position);
            if (area > split) return;
            if (zoom > 1 && area * zoomed > split && inZoom(view, position, zoom)) {
                this.zoomLeaves.add(position);
                this.zoomAreas.add(area * zoomed);
                this.zoomMasks.add(mask);
                return;
            }
            if (!(area > near)) return;
            leaves.add(position);
            areas.add(area);
            masks.add(mask);
        });
        int[] order = new int[leaves.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        IntArrays.quickSort(order, (a, b) -> Float.compare(areas.getFloat(b), areas.getFloat(a)));
        room -= this.prefetchForZoom(room, split);
        long[] batch = new long[room];
        int count = 0;
        for (int i : order) {
            int mask = masks.getInt(i);
            if (count + Integer.bitCount(mask) > room) break;
            long leaf = leaves.getLong(i);
            for (int index = 0; index < 8; index++) {
                if ((mask >>> index & 1) != 0) batch[count++] = LodTree.child(leaf, index);
            }
            this.prefetchSent.add(leaf);
        }
        if (count != 0) this.meshBuilder.prefetch(batch, count, false);
    }

    private int prefetchForZoom(int room, float split) {
        int size = this.zoomLeaves.size();
        if (size == 0) return 0;
        int[] order = new int[size];
        for (int i = 0; i < size; i++) order[i] = i;
        IntArrays.quickSort(order, (a, b) -> Float.compare(this.zoomAreas.getFloat(b), this.zoomAreas.getFloat(a)));
        long[] once = new long[room], twice = new long[room];
        int onceCount = 0, twiceCount = 0;
        for (int i : order) {
            int mask = this.zoomMasks.getInt(i);
            if (onceCount + twiceCount + Integer.bitCount(mask) > room) break;
            long leaf = this.zoomLeaves.getLong(i);
            boolean deep = this.zoomAreas.getFloat(i) > split * 4 && LodPos.level(leaf) > 1;
            for (int index = 0; index < 8; index++) {
                if ((mask >>> index & 1) == 0) continue;
                if (deep) twice[twiceCount++] = LodTree.child(leaf, index);
                else once[onceCount++] = LodTree.child(leaf, index);
            }
            this.prefetchSent.add(leaf);
        }
        if (twiceCount != 0) this.meshBuilder.prefetch(twice, twiceCount, true);
        if (onceCount != 0) this.meshBuilder.prefetch(once, onceCount, false);
        return onceCount + twiceCount;
    }

    private void publish() throws InterruptedException {
        this.lock.lock();
        try {
            var work = this.filling;
            this.geometry.takeFreedRuns(work::dropUpload);
            this.geometry.takeUploads(work::addUpload);
            this.geometry.takeMoves(work::addMove);
            if (this.movesPublishedAt == Long.MAX_VALUE) this.movesPublishedAt = this.batchesTaken;
            this.geometry.pagesInUse(this.pagesInUse);
            if (!java.util.Arrays.equals(this.pagesInUse, this.pagesPublished)) {
                System.arraycopy(this.pagesInUse, 0, this.pagesPublished, 0, this.pagesInUse.length);
                work.usedPages = this.pagesInUse.clone();
            }
            for (var it = this.geometry.changedIds().iterator(); it.hasNext(); ) {
                int id = it.nextInt();
                this.geometry.writeMetadata(id, 0, work.metadataWrite(id, 0));
                this.geometry.writeMetadata(id, 1, work.metadataWrite(id, 1));
                this.geometry.writeMetadata(id, 2, work.metadataWrite(id, 2));
            }
            this.geometry.changedIds().clear();
            for (var it = this.tree.changedNodes().iterator(); it.hasNext(); ) {
                int node = it.nextInt();
                this.tree.writeNode(node, work.nodeWrite(node));
            }
            this.tree.changedNodes().clear();
            for (var it = this.seenChanges.int2BooleanEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                work.lastSeen.put(entry.getIntKey(), entry.getBooleanValue());
            }
            this.seenChanges.clear();

            // Newest state wins if a slot was revealed, freed, and reused within one batch.
            work.fades.putAll(this.fadeChanges);
            this.fadeChanges.clear();
            work.topNodes.addAll(this.topNodeChanges);
            this.topNodeChanges.clear();
            this.slotLimit = this.tree.slotLimit();
            work.nodeLimit = Math.max(work.nodeLimit, this.slotLimit);
            work.sectionLimit = Math.max(work.sectionLimit, this.geometry.idLimit());
            this.usedGeometryBytes = this.geometry.usedBytes();
            this.sectionCount = this.geometry.sectionCount();
            if (this.spaceWanted) this.spaceSummary = this.geometry.spaceSummary();
            this.treeSummary = this.tree.describe() + (this.deferred.isEmpty() ? "" : ", " + this.deferred.size() + " meshes waiting for space");

            while (this.running && this.filling.isFull()) {
                this.taken.await(50, TimeUnit.MILLISECONDS);
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void applyUpdates(GpuSink sink) {
        if (this.failure != null) {
            throw new IllegalStateException("The LOD tree thread failed", this.failure);
        }
        if (!this.lock.tryLock()) return;
        PendingGpuWork work;
        try {
            if (this.filling.isEmpty()) return;
            work = this.filling;
            this.filling = this.spare;
            this.spare = work;
            this.batchesTaken++;
            this.taken.signal();
        } finally {
            this.lock.unlock();
        }

        try {
            sink.reserveSlots(work.nodeLimit, work.sectionLimit);
            for (int i = 0; i < work.topNodes.size(); i++) {
                int change = work.topNodes.getInt(i);
                if (change < 0) sink.topNodeRemoved(change & 0x7FFFFFFF);
                else sink.topNodeAdded(change);
            }
            if (work.usedPages != null) sink.pagesInUse(work.usedPages);
            for (var it = work.uploads.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                sink.uploadQuads(entry.getLongKey(), entry.getValue());
            }
            for (var it = work.moves.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                sink.moveQuads(entry.getLongValue() >>> 32, entry.getLongKey(), (int) entry.getLongValue());
            }
            if (work.writeCount() != 0) {
                sink.scatter(work.records(), work.recordBytes(), work.writeCount());
            }
            if (!work.lastSeen.isEmpty()) {
                this.seenBatch.clear();
                this.seenBatch.ensureCapacity(work.lastSeen.size());
                for (var it = work.lastSeen.int2BooleanEntrySet().fastIterator(); it.hasNext(); ) {
                    var entry = it.next();
                    this.seenBatch.add(entry.getBooleanValue() ? entry.getIntKey() | 0x80000000 : entry.getIntKey());
                }
                sink.markSeen(this.seenBatch);
            }
            if (!work.fades.isEmpty()) {
                this.fadeBatch.clear();
                this.fadeBatch.ensureCapacity(work.fades.size());
                for (var it = work.fades.int2BooleanEntrySet().fastIterator(); it.hasNext(); ) {
                    var entry = it.next();
                    this.fadeBatch.add(entry.getBooleanValue() ? entry.getIntKey() | 0x80000000 : entry.getIntKey());
                }
                sink.markFades(this.fadeBatch);
            }
        } finally {
            work.clear(this.retiredUploads); if (!this.retiredUploads.isEmpty() || this.movesHeld) this.signal();
        }
    }

    public int slotLimit() {
        return this.slotLimit;
    }

    public long usedGeometryBytes() {
        return this.usedGeometryBytes;
    }

    public long geometryCapacity() {
        return this.geometryCapacity;
    }

    public int sectionCount() {
        return this.sectionCount;
    }

    public void addDebugInfo(List<String> lines) {
        lines.add("LoD geometry: " + (this.usedGeometryBytes >> 20) + "/" + (this.geometryCapacity >> 20) + " MiB in "
                + this.sectionCount + " sections, " + this.treeSummary);
        lines.add("  " + this.spaceSummary());
    }

    public String spaceSummary() {
        this.spaceWanted = true;
        return this.spaceSummary;
    }

    public void stop() {
        this.running = false;
        this.lock.lock();
        try {
            this.taken.signalAll();
        } finally {
            this.lock.unlock();
        }
        LockSupport.unpark(this.thread);
        try {
            this.thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (this.thread.isAlive()) {
            Log.error("LoD tree thread did not stop in time");
            return;
        }
        freeAll(this.meshes);
        this.deferred.forEach(LodMesh::free);
        this.deferred.clear();
        for (LodSection section; (section = this.childChanges.poll()) != null; ) section.release();
        this.filling.free();
        this.spare.free();
        for (NativeBuffer retired; (retired = this.retiredUploads.poll()) != null; ) retired.free();
        this.geometry.free();
    }

    private static void freeAll(ConcurrentLinkedQueue<LodMesh> queue) {
        for (LodMesh mesh; (mesh = queue.poll()) != null; ) mesh.free();
    }

    public static long[] readPositions(long address, int count) {
        long[] positions = new long[count];
        for (int i = 0; i < count; i++) {
            long at = address + i * 8L;
            positions[i] = (long) MemoryUtil.memGetInt(at) << 32 | Integer.toUnsignedLong(MemoryUtil.memGetInt(at + 4));
        }
        return positions;
    }
}
