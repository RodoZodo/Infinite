package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.*;
import rodozodo.infinite.client.lod.tree.LodTreeWorker;
import rodozodo.infinite.world.LodPos;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntCollection;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;


final class GeometryEvictor {
    private static final long LOW_SPACE_BYTES = 256_000_000L;
    private static final int HISTOGRAM_NODES_PER_GROUP = 256 * 8;
    private static final int GROUP = 256;
    private static final int MARK_GROUP = 128;
    private static final int AGE_BUCKETS = 1024;
    private static final int NEVER = -1;
    static final int FOLD_MILLIS = 30_000;
    private static final boolean FOLD = LodSelector.AROUND;
    private static final long FOLD_INTERVAL_NANOS = 100_000_000L;
    private static final int FOLD_BATCH = LodTreeWorker.EVICTION_BATCH;
    private static final int EVICT_NODES_PER_SECOND = 61_440;
    private static final long EVICT_INTERVAL_NANOS = 16_666_667L;
    private static final int MOST_BATCH = 4096;
    private long evictedAt;
    private static final long FOLD_SAMPLE_NANOS = 1_000_000_000L;

    private final LodTreeWorker tree;
    private final StagingBuffer staging;
    private final ReadbackBuffer readback;
    private static final int S_NODES = 0, S_LAST_SEEN = 1, S_SELECTION = 2, S_EVICTED = 3, S_CHANGES = 4;
    private static final int L_FRAME = 0, L_COUNT = 1, L_SEEN_BEFORE = 2, L_BATCH = 3;

    private final VCompute histogram, pickThreshold, collect, mark;
    final VBuffer lastSeen;
    private final VBuffer selection;
    private final VBuffer evicted;
    private int frame;
    private final ArrayDeque<long[]> frameTimes = new ArrayDeque<>();
    private long foldedAt, foldsHeldUntil;

    int nodeCapacity() {
        return (int) (this.lastSeen.size / 4);
    }

    void growNodes(VkCommandBuffer cb, int nodes) {
        this.lastSeen.grow(cb, nodes * 4L, NEVER);
    }

    GeometryEvictor(SetupCommands setup, LodTreeWorker tree, StagingBuffer staging, ReadbackBuffer readback) {
        this.tree = tree;
        this.staging = staging;
        this.readback = readback;
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        this.lastSeen = VBuffer.growable("node_last_seen", NodeSync.LEAST_NODES * 4L, storage);
        this.selection = VBuffer.create("eviction_selection", 4L * (4 + AGE_BUCKETS), storage, VBuffer.Access.DEVICE_LOCAL);
        this.evicted = VBuffer.create("evicted_nodes", MOST_BATCH * 8L, storage, VBuffer.Access.DEVICE_LOCAL);
        setup.fill(this.lastSeen, NEVER);
        setup.fill(this.selection, 0);
        setup.fill(this.evicted, NEVER);

        var defines = new VDefines()
                .define("MOST_BATCH", MOST_BATCH + "u")
                .define("TOP_LEVEL", LodPos.MAX_LEVEL + "u");
        this.histogram = VCompute.asset("infinite:evict/age_histogram.comp", defines);
        this.pickThreshold = VCompute.asset("infinite:evict/pick_threshold.comp", defines.copy().defineIf("SUBGROUP_SCAN", SectionDrawer.subgroupScan()));
        this.collect = VCompute.asset("infinite:evict/collect.comp", defines);
        this.mark = VCompute.asset("infinite:evict/mark_seen.comp", new VDefines());
        for (var pass : new VCompute[]{this.histogram, this.pickThreshold, this.collect}) {
            pass.program.ssbo(S_LAST_SEEN, this.lastSeen).ssbo(S_SELECTION, this.selection);
        }
        this.collect.program.ssbo(S_EVICTED, this.evicted);
        this.mark.program.ssbo(S_LAST_SEEN, this.lastSeen);
    }

    int frame() {
        return this.frame;
    }

    void holdFolds() {
        this.foldsHeldUntil = System.nanoTime() + FOLD_SAMPLE_NANOS;
    }

    private int frameBefore(long now) {
        if (this.frameTimes.isEmpty() || now - this.frameTimes.peekLast()[0] >= FOLD_SAMPLE_NANOS) {
            this.frameTimes.add(new long[]{now, this.frame});
        }
        long old = now - FOLD_MILLIS * 1_000_000L;
        while (this.frameTimes.size() > 1 && second(this.frameTimes)[0] <= old) this.frameTimes.poll();
        long[] first = this.frameTimes.peek();
        return first[0] <= old ? (int) Math.max(0, first[1] - LodSelector.AROUND_AGE - LodSelector.AROUND_EVERY) : 0;
    }

    private static long[] second(ArrayDeque<long[]> queue) {
        var it = queue.iterator();
        it.next();
        return it.next();
    }

    void update(VkCommandBuffer cb, VBuffer nodes) {
        this.frame++;
        long now = System.nanoTime();
        int seenBefore = FOLD ? this.frameBefore(now) : 0;
        long free = this.tree.geometryCapacity() - this.tree.usedGeometryBytes();
        int batch;
        if (free < LOW_SPACE_BYTES) {
            long since = now - this.evictedAt;
            if (since < EVICT_INTERVAL_NANOS) return;
            //After a gap longer than slow frames make, space was not short in between and nothing is owed.
            // Sizing by the whole gap made every first pass the largest, evicting more than a batch every frame had.
            long owed = since <= 4 * EVICT_INTERVAL_NANOS ? since : EVICT_INTERVAL_NANOS;
            batch = (int) Math.min(MOST_BATCH, EVICT_NODES_PER_SECOND * owed / 1_000_000_000L);
            this.evictedAt = now;
            seenBefore = NEVER;
        } else {
            if (seenBefore == 0 || now - this.foldedAt < FOLD_INTERVAL_NANOS || now < this.foldsHeldUntil) return;
            this.foldedAt = now;
            batch = FOLD_BATCH;
        }

        //The trer slot limit can run ahead of the slots the GPU has been given room for (NodeSync.reserveSlots)
        int nodeCount = (int) Math.min(this.tree.slotLimit(), Math.min(this.nodeCapacity(), nodes.size / LodRenderer.NODE_BYTES));
        vkCmdFillBuffer(cb, this.selection.handle, 0, VK_WHOLE_SIZE, 0);
        vkCmdFillBuffer(cb, this.evicted.handle, 0, batch * 8L, NEVER);
        VBarriers.transferToCompute(cb);
        for (var pass : new VCompute[]{this.histogram, this.collect}) {
            pass.program.ssbo(S_NODES, nodes).uniform1ui(L_FRAME, this.frame).uniform1ui(L_COUNT, nodeCount).uniform1ui(L_SEEN_BEFORE, seenBefore);
        }
        this.collect.program.uniform1ui(L_BATCH, batch);
        this.histogram.bind(cb, this.staging).dispatch(cb, Math.max(1, (nodeCount + HISTOGRAM_NODES_PER_GROUP - 1) / HISTOGRAM_NODES_PER_GROUP), 1, 1);
        VBarriers.computeToCompute(cb);
        this.pickThreshold.program.ssbo(S_NODES, nodes).uniform1ui(L_BATCH, batch);
        this.pickThreshold.bind(cb, this.staging).dispatch(cb, 1, 1, 1);
        VBarriers.computeToCompute(cb);
        this.collect.bind(cb, this.staging).dispatch(cb, Math.max(1, (nodeCount + GROUP - 1) / GROUP), 1, 1);
        VBarriers.computeToCompute(cb);

        this.readback.request(this.evicted, 0, batch * 8L,
                (address, size) -> this.tree.evict(LodTreeWorker.readPositions(address, (int) (size / 8))));
    }

    void markSeen(VkCommandBuffer cb, IntCollection changedNodes) {
        int count = changedNodes.size();
        if (count == 0) return;
        long list = this.staging.reserve(count * 4L);
        long at = this.staging.address(list);
        if (changedNodes instanceof IntArrayList elements) {
            int[] raw = elements.elements();
            for (int i = 0; i < count; i++, at += 4) MemoryUtil.memPutInt(at, raw[i]);
        } else {
            for (var it = changedNodes.iterator(); it.hasNext(); at += 4) {
                MemoryUtil.memPutInt(at, it.nextInt());
            }
        }
        this.mark.program
                .ssbo(S_CHANGES, this.staging.buffer(), list, count * 4L)
                .uniform1ui(L_FRAME, this.frame)
                .uniform1ui(L_COUNT, count);
        this.mark.bind(cb, this.staging).dispatch(cb, (count + MARK_GROUP - 1) / MARK_GROUP, 1, 1);
        VBarriers.computeToCompute(cb);
    }

    void free() {
        for (var pass : new VCompute[]{this.histogram, this.pickThreshold, this.collect, this.mark}) {
            pass.free();
        }
        this.lastSeen.free();
        this.selection.free();
        this.evicted.free();
    }
}
