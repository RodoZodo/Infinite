package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.StagingBuffer;
import rodozodo.infinite.client.core.vk.VBarriers;
import rodozodo.infinite.client.core.vk.VBuffer;
import rodozodo.infinite.client.core.vk.VComputePipeline;
import rodozodo.infinite.client.lod.tree.GpuSink;
import rodozodo.infinite.client.lod.tree.LodTreeWorker;
import rodozodo.infinite.util.NativeBuffer;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK13.*;


final class NodeSync implements GpuSink {
    private static final int GROUP_SIZE = 64;
    private static final int MOVES_PER_COMMAND = 256;
    static final int LEAST_NODES = 1 << 18, LEAST_SECTIONS = 1 << 19;

    private final GeometryPages geometry;
    private final VBuffer nodes;
    private final StagingBuffer staging;
    private final GeometryEvictor evictor;
    private final TopNodeList topNodes;
    private final LodSelector selector;
    private final SectionDrawer drawer;
    private final int maxNodes;
    private final VComputePipeline scatter;
    private VkCommandBuffer cb;
    private final Long2ObjectOpenHashMap<LongArrayList> moves = new Long2ObjectOpenHashMap<>();

    NodeSync(GeometryPages geometry, VBuffer nodes, StagingBuffer staging, GeometryEvictor evictor, LodSelector selector,
             SectionDrawer drawer, int maxNodes) {
        this.drawer = drawer;
        this.maxNodes = maxNodes;
        this.geometry = geometry;
        this.nodes = nodes;
        this.staging = staging;
        this.evictor = evictor;
        this.topNodes = selector.topNodes;
        this.selector = selector;
        this.scatter = VComputePipeline.builder("node_scatter")
                .asset("infinite:sync/node_scatter.comp")
                .binding(0, VComputePipeline.BindingType.STORAGE_BUFFER)
                .binding(1, VComputePipeline.BindingType.STORAGE_BUFFER)
                .binding(2, VComputePipeline.BindingType.STORAGE_BUFFER)
                .pushConstants(4)
                .build();
    }

    void apply(VkCommandBuffer commands, LodTreeWorker tree) {
        this.cb = commands;
        try {
            tree.applyUpdates(this);
            this.geometry.setSectionCount(tree.sectionCount());
            this.geometry.releaseUnused();
        } finally {
            this.cb = null;
        }
    }

    @Override
    public void topNodeAdded(int node) {
        this.topNodes.add(node);
    }

    @Override
    public void topNodeRemoved(int node) {
        this.topNodes.remove(node);
    }

    @Override
    public void reserveSlots(int nodeLimit, int sectionLimit) {
        int nodes = (int) capacity(nodeLimit, this.nodes.size / LodRenderer.NODE_BYTES, this.maxNodes);
        this.nodes.grow(this.cb, (long) nodes * LodRenderer.NODE_BYTES, -1);
        this.evictor.growNodes(this.cb, (int) capacity(nodeLimit, this.evictor.nodeCapacity(), this.maxNodes));
        this.selector.growNodes(this.cb, (int) capacity(nodeLimit, this.selector.nodeCapacity(), this.maxNodes));
        int most = this.geometry.getMaxSectionCount();
        this.geometry.growSections(this.cb, (int) capacity(sectionLimit, this.geometry.sectionCapacity(), most));
        this.drawer.growSections(this.cb, (int) capacity(sectionLimit, this.drawer.sectionCapacity(), most));
    }

    private static long capacity(long needed, long current, long most) {
        if (needed <= current) return current;
        return Math.min(most, (needed + needed / 2 + 4095) & -4096L);
    }

    @Override
    public void pagesInUse(long[] pages) {
        this.geometry.setPagesInUse(pages);
    }

    @Override
    public void moveQuads(long fromQuad, long toQuad, int quads) {
        long perPage = this.geometry.getPageElements();
        long from = fromQuad / perPage, to = toQuad / perPage;
        if (from == to) throw new IllegalStateException("Move of " + quads + " quads within geometry page " + from);
        this.geometry.ensurePage((int) to);
        var copies = this.moves.computeIfAbsent(from << 32 | to, k -> new LongArrayList());
        copies.add(fromQuad % perPage * 8);
        copies.add(toQuad % perPage * 8);
        copies.add(quads * 8L);
    }

    // Must run after staging copies (a moved run may be uploaded in the same batch) and before passes read updated metadata
    void recordMoves(VkCommandBuffer cb) {
        if (this.moves.isEmpty()) return;
        for (var entry : this.moves.long2ObjectEntrySet()) {
            var from = this.geometry.page((int) (entry.getLongKey() >>> 32));
            var to = this.geometry.page((int) entry.getLongKey());
            var triples = entry.getValue();
            int count = triples.size() / 3;
            for (int first = 0; first < count; first += MOVES_PER_COMMAND) {
                int batch = Math.min(MOVES_PER_COMMAND, count - first);
                try (var stack = MemoryStack.stackPush()) {
                    var regions = VkBufferCopy.malloc(batch, stack);
                    for (int i = 0; i < batch; i++) {
                        int at = (first + i) * 3;
                        regions.get(i).srcOffset(triples.getLong(at)).dstOffset(triples.getLong(at + 1)).size(triples.getLong(at + 2));
                    }
                    vkCmdCopyBuffer(cb, from.handle, to.handle, regions);
                }
            }
        }
        this.moves.clear();
        VBarriers.memory(cb,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
    }

    @Override
    public void uploadQuads(long firstQuad, NativeBuffer quads) {
        long perPage = this.geometry.getPageElements();
        int page = (int) (firstQuad / perPage);
        long inPage = firstQuad % perPage;
        this.geometry.ensurePage(page);
        if (inPage * 8 + quads.size > perPage * 8) {
            throw new IllegalStateException("Upload of " + (quads.size >> 3) + " quads at " + firstQuad + " crosses a geometry page");
        }
        long staged = this.staging.reserve(quads.size);
        quads.copyTo(this.staging.address(staged));
        this.staging.copy(staged, this.geometry.page(page), inPage * 8, quads.size);
    }

    @Override
    public void scatter(long records, long bytes, int count) {
        long staged = this.staging.reserve(bytes);
        MemoryUtil.memCopy(records, this.staging.address(staged), bytes);
        this.scatter.bind(this.cb);
        this.scatter.stageBuffer(0, 0, this.staging.buffer(), staged, bytes);
        this.scatter.stageBuffer(1, 1, this.nodes, 0, VK_WHOLE_SIZE);
        this.scatter.stageBuffer(2, 2, this.geometry.getMetadataBuffer(), 0, VK_WHOLE_SIZE);
        this.scatter.flushBuffers(this.cb, 3);
        this.scatter.pushConstantInt(this.cb, count);
        this.scatter.dispatch(this.cb, (count + GROUP_SIZE - 1) / GROUP_SIZE, 1, 1);
        // Make scattered node and section metadata visible to selection, eviction, and draw passes.
        VBarriers.memory(this.cb,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    @Override
    public void markSeen(IntCollection changes) {
        this.evictor.markSeen(this.cb, changes);
    }

    @Override
    public void markFades(IntCollection changes) {
        this.selector.markFades(this.cb, changes);
    }

    void free() {
        this.scatter.free();
    }
}
