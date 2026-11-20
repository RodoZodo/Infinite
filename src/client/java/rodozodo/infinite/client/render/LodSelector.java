package rodozodo.infinite.client.render;

import rodozodo.infinite.Log;
import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.core.vk.*;
import rodozodo.infinite.client.core.vk.DepthConvention;
import rodozodo.infinite.client.lod.mesh.MeshBuilder;
import rodozodo.infinite.client.lod.tree.LodTreeWorker;
import rodozodo.infinite.world.LodPos;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntCollection;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

final class LodSelector {
    static final int MAX_SELECTED = 200_000;
    private static final int MAX_REQUESTS = 400;
    private static final int MIN_REQUESTS = 50;
    private static final double REQUESTS_PER_SECOND = 12_000;
    private static final int REQUEST_BACKLOG_LIMIT = 12000;
    private static final long PREFETCH_INTERVAL_NANOS = 1_000_000_000L;
    static final boolean AROUND = !"false".equalsIgnoreCase(System.getProperty("infinite.refineAround"));
    static final int AROUND_AGE = 3600;
    static final int AROUND_EVERY = 4;
    private static final double AROUND_FREE_SHARE = 0.25;
    private static final int FADE_MILLIS = 600;
    private static final double WAVE_DOUBLING_SECONDS = 0.18, WAVE_HELD_DOUBLING_SECONDS = 2.0;
    private static final double WAVE_BLOCKS_PER_SECOND = 600, WAVE_HELD_BLOCKS_PER_SECOND = 60;
    private static final double WAVE_LEAD = 1.5, WAVE_SLACK = 128;
    private static final int DROP_MILLIS = 4000, MAX_DROPS = 256;
    private static final long MESH_USE_HEADER = 8L + MAX_DROPS * 8L;
    private static final long FADE_CLOCK_START = System.nanoTime();
    private static final int LEVELS = LodPos.MAX_LEVEL + 1;
    private static final int GROUP_BITS = 5;
    private static final int UNIFORM_BYTES = 1024;

    private static final int T_PYRAMID = 0, U_FRAME = 0, L_LEVEL = 0;
    private static final int S_NODES = 0, S_QUEUES = 1, S_QUEUE_IN = 2, S_QUEUE_OUT = 3, S_SELECTED = 4, S_REQUESTS = 5, S_LAST_SEEN = 6,
            S_SELECTED_FADES = 7, S_MESH_USE = 8, S_SECTIONS = 9, S_VIEW_WANTED = 10, S_FADE_STATES = 11;
    private static final int S_MARK_STATES = 1, S_MARK_CHANGES = 4, L_MARK_VALUE = 0, L_MARK_COUNT = 1;
    private static final int MARK_GROUP = 128;

    private final LodTreeWorker tree;
    private final MeshBuilder meshes;
    private final GeometryEvictor evictor;
    private final StagingBuffer staging;
    private final ReadbackBuffer readback;
    private final VCompute traverse, markFade;
    private final VBuffer fadeStates;
    private double waveRadius;
    private long waveAdvanced = System.nanoTime();
    private float frontier = Float.POSITIVE_INFINITY;
    private final VCompute around;
    private final VBuffer aroundUniform, aroundSizes, aroundRequests;
    private final VBuffer uniform, requests, levelSizes, queueA, queueB;
    final VBuffer selected;
    final VBuffer selectedFades;
    private final VBuffer viewWanted;
    private final VBuffer meshUse;
    private static final int AROUND_BACKLOG = 500;
    final TopNodeList topNodes;
    private boolean animationsDrawn;
    private long lastRequestBudget = System.nanoTime();
    private int aroundBudget;
    private long nextPrefetch;
    private boolean prefetchDropped = true;
    private final Matrix4f aroundViewProjection = new Matrix4f();
    private final float[] aroundClipPlanes = new float[24];
    private final ReadbackBuffer.Callback onRequests = this::submitRequests;
    private final ReadbackBuffer.Callback onAroundRequests = this::submitAroundRequests;
    private final ReadbackBuffer.Callback onDrops = this::submitDrops;
    private final ReadbackBuffer.Callback onAroundQueues = this::checkAroundQueues;
    private final ReadbackBuffer.Callback onWave = this::readWave;

    LodSelector(SetupCommands setup, DepthConvention depth, LodTreeWorker tree, MeshBuilder meshes,
                GeometryEvictor evictor, VBuffer nodes, VBuffer sections, StagingBuffer staging, ReadbackBuffer readback) {
        this.tree = tree;
        this.meshes = meshes;
        this.evictor = evictor;
        this.staging = staging;
        this.readback = readback;
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        this.uniform = VBuffer.create("lod_select_uniform", UNIFORM_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VBuffer.Access.DEVICE_LOCAL);
        this.requests = VBuffer.create("lod_mesh_requests", 8L + MAX_REQUESTS * 8L, storage, VBuffer.Access.DEVICE_LOCAL);
        this.viewWanted = VBuffer.create("lod_view_wanted", 12, storage, VBuffer.Access.DEVICE_LOCAL);
        setup.fill(this.viewWanted, 0);
        this.meshUse = VBuffer.growable("lod_mesh_use", MESH_USE_HEADER + NodeSync.LEAST_NODES * 4L, storage);
        setup.fill(this.meshUse, 0);
        this.fadeStates = VBuffer.growable("lod_fade_states", NodeSync.LEAST_NODES * 4L, storage);
        setup.fill(this.fadeStates, -1);
        this.levelSizes = VBuffer.create("lod_level_sizes", 16L * LEVELS, storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, VBuffer.Access.DEVICE_LOCAL);
        this.queueA = VBuffer.create("lod_level_queue_a", MAX_SELECTED * 4L, storage, VBuffer.Access.DEVICE_LOCAL);
        this.queueB = VBuffer.create("lod_level_queue_b", MAX_SELECTED * 4L, storage, VBuffer.Access.DEVICE_LOCAL);
        this.selected = VBuffer.create("lod_selected", 4L + MAX_SELECTED * 4L, storage, VBuffer.Access.DEVICE_LOCAL);
        this.selectedFades = VBuffer.create("lod_selected_fades", MAX_SELECTED * 4L, storage, VBuffer.Access.DEVICE_LOCAL);
        var top = VBuffer.create("lod_top_nodes", MAX_SELECTED * 4L, storage, VBuffer.Access.DEVICE_LOCAL);
        for (var buffer : new VBuffer[]{this.requests, this.levelSizes, this.queueA, this.queueB, this.selected, this.selectedFades, top}) {
            setup.fill(buffer, 0);
        }
        this.topNodes = new TopNodeList(top, staging);

        this.traverse = VCompute.asset("infinite:select/select.comp", VDefines.of(depth)
                .define("TOP_LEVEL", LodPos.MAX_LEVEL)
                .define("GROUP_SIZE", 1 << GROUP_BITS)
                .define("QUEUE_CAPACITY", MAX_SELECTED)
                .define("MAX_REQUESTS", MAX_REQUESTS)
                .define("MAX_DROPS", MAX_DROPS)
                .define("DROP_MILLIS", DROP_MILLIS + "u")
                .define("FADE_MILLIS", FADE_MILLIS + ".0"));
        this.traverse.program
                .ubo(U_FRAME, this.uniform)
                .ssbo(S_NODES, nodes)
                .ssbo(S_QUEUES, this.levelSizes)
                .ssbo(S_SELECTED, this.selected)
                .ssbo(S_REQUESTS, this.requests)
                .ssbo(S_LAST_SEEN, evictor.lastSeen)
                .ssbo(S_SECTIONS, sections)
                .ssbo(S_VIEW_WANTED, this.viewWanted)
                .ssbo(S_FADE_STATES, this.fadeStates)
                .ssbo(S_MESH_USE, this.meshUse)
                .ssbo(S_SELECTED_FADES, this.selectedFades);
        this.markFade = VCompute.asset("infinite:evict/mark_seen.comp", new VDefines());
        this.markFade.program.ssbo(S_MARK_STATES, this.fadeStates);
        if (AROUND) {
            this.aroundUniform = VBuffer.create("lod_around_uniform", UNIFORM_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VBuffer.Access.DEVICE_LOCAL);
            this.aroundSizes = VBuffer.create("lod_around_level_sizes", 16L * LEVELS, storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, VBuffer.Access.DEVICE_LOCAL);
            this.aroundRequests = VBuffer.create("lod_around_requests", 8L + MAX_REQUESTS * 8L, storage, VBuffer.Access.DEVICE_LOCAL);
            setup.fill(this.aroundSizes, 0);
            setup.fill(this.aroundRequests, 0);
            this.around = VCompute.asset("infinite:select/select.comp", VDefines.of(depth)
                    .define("TOP_LEVEL", LodPos.MAX_LEVEL)
                    .define("GROUP_SIZE", 1 << GROUP_BITS)
                    .define("QUEUE_CAPACITY", MAX_SELECTED)
                    .define("MAX_REQUESTS", MAX_REQUESTS)
                    .define("MAX_DROPS", MAX_DROPS)
                    .define("DROP_MILLIS", DROP_MILLIS + "u")
                    .define("AROUND", 1));
            this.around.program
                    .ubo(U_FRAME, this.aroundUniform)
                    .ssbo(S_NODES, nodes)
                    .ssbo(S_QUEUES, this.aroundSizes)
                    .ssbo(S_SELECTED, this.selected)
                    .ssbo(S_REQUESTS, this.aroundRequests)
                    .ssbo(S_LAST_SEEN, evictor.lastSeen)
                    .ssbo(S_SECTIONS, sections)
                    .ssbo(S_VIEW_WANTED, this.viewWanted)
                    .ssbo(S_MESH_USE, this.meshUse)
                    .ssbo(S_FADE_STATES, this.fadeStates);
        } else {
            this.around = null;
            this.aroundUniform = this.aroundSizes = this.aroundRequests = null;
        }
    }

    private int requestBudget() {
        long now = System.nanoTime();
        double earned = REQUESTS_PER_SECOND * (now - this.lastRequestBudget) / 1e9;
        this.lastRequestBudget = now;
        double budget = Math.max(MIN_REQUESTS, Math.min(MAX_REQUESTS, earned));
        double idle = Math.max(0, 1 - (double) this.meshes.backlog() / REQUEST_BACKLOG_LIMIT);
        return (int) Math.ceil(budget * idle * idle);
    }

    private void prefetch() {
        if (!ViewMotion.still()) {
            if (!this.prefetchDropped) {
                this.prefetchDropped = true;
                this.tree.dropPrefetch();
            }
            return;
        }
        long now = System.nanoTime();
        if (now < this.nextPrefetch || this.meshes.backlog() != 0) return;
        this.nextPrefetch = now + PREFETCH_INTERVAL_NANOS;
        this.prefetchDropped = false;
        this.tree.prefetch();
    }

    void markFades(VkCommandBuffer cb, IntCollection changes) {
        int count = changes.size();
        if (count == 0) return;
        long list = this.staging.reserve(count * 4L);
        long at = this.staging.address(list);
        if (changes instanceof IntArrayList elements) {
            int[] raw = elements.elements();
            for (int i = 0; i < count; i++, at += 4) MemoryUtil.memPutInt(at, raw[i]);
        } else {
            for (var it = changes.iterator(); it.hasNext(); at += 4) {
                MemoryUtil.memPutInt(at, it.nextInt());
            }
        }
        // select.comp interprets 0 as FADE_PENDING and -1 as FADE_NONE.
        this.markFade.program
                .ssbo(S_MARK_CHANGES, this.staging.buffer(), list, count * 4L)
                .uniform1ui(L_MARK_VALUE, Double.isInfinite(this.waveRadius) ? -1 : 0)
                .uniform1ui(L_MARK_COUNT, count);
        this.markFade.bind(cb, this.staging).dispatch(cb, (count + MARK_GROUP - 1) / MARK_GROUP, 1, 1);
        VBarriers.computeToCompute(cb);
    }

    private float advanceWave() {
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - this.waveAdvanced) / 1e9);
        this.waveAdvanced = now;
        if (Double.isInfinite(this.waveRadius) || this.topNodes.size() == 0) return (float) this.waveRadius;
        boolean held = this.waveRadius > this.frontier * WAVE_LEAD + WAVE_SLACK;
        this.waveRadius = this.waveRadius * Math.pow(2, seconds / (held ? WAVE_HELD_DOUBLING_SECONDS : WAVE_DOUBLING_SECONDS))
                + (held ? WAVE_HELD_BLOCKS_PER_SECOND : WAVE_BLOCKS_PER_SECOND) * seconds;
        if (this.waveRadius > ClientSettings.get().renderDistanceBlocks() * 1.5 + 1024) this.waveRadius = Double.POSITIVE_INFINITY;
        return (float) this.waveRadius;
    }

    private void readWave(long address, long size) {
        // Shader stores atomicMax(0x7F800000 - floatBits) for non-negative floats; 0 means none waiting.
        int nearest = MemoryUtil.memGetInt(address);
        this.frontier = nearest == 0 ? Float.POSITIVE_INFINITY : Float.intBitsToFloat(0x7F800000 - nearest);
    }

    private void writeUniform(VBuffer target, CameraState camera, Matrix4fc viewProjection, float[] clipPlanes, int stamp, int budget,
                              DepthPyramid pyramid, float splitArea, float aroundEdge, float waveRadius) {
        float lodDistance = ClientSettings.get().renderDistanceBlocks();
        new UniformWriter(this.staging.write(target, 0, UNIFORM_BYTES), UNIFORM_BYTES)
                .mat4(viewProjection)
                .ivec3(camera.sectionX, camera.sectionY, camera.sectionZ)
                .uint(pyramid.packedSize())
                .vec3(camera.subX, camera.subY, camera.subZ)
                .f32(splitArea)
                .vec4Array(clipPlanes)
                .uint(stamp)
                .uint(budget)
                .f32(lodDistance * lodDistance)
                .uint(fadeClock())
                .f32(camera.curveDrop)
                .f32(camera.curveStart)
                .f32(camera.width * (float) camera.height)
                .vec4(aroundEdge != 0 ? cameraYaw(camera) : 0, aroundEdge,
                        Float.intBitsToFloat(this.evictor.frame()), 0)
                .f32(waveRadius);
    }

    private static int fadeClock() {
        return (int) ((System.nanoTime() - FADE_CLOCK_START) / 1_000_000L) + 1;
    }

    private static float unzoom(CameraState camera) {
        double half = Math.toRadians(Minecraft.getInstance().options.fov().get()) / 2;
        return (float) Math.min(1, 1 / (Math.tan(half) * camera.projection.m11()));
    }

    private static float cameraYaw(CameraState camera) {
        var view = camera.modelView;
        return (float) Math.atan2(-view.m02(), -view.m22());
    }

    int nodeCapacity() {
        return (int) (this.fadeStates.size / 4);
    }

    void growNodes(VkCommandBuffer cb, int nodes) {
        this.fadeStates.grow(cb, nodes * 4L, -1);
        this.meshUse.grow(cb, MESH_USE_HEADER + nodes * 4L, 0);
    }

    void select(VkCommandBuffer cb, CameraState camera, DepthPyramid pyramid) {
        float splitArea = ClientSettings.get().detailPixels * ClientSettings.get().detailPixels / (camera.width * camera.height);
        this.tree.setView(new LodTreeWorker.View(camera.viewProjection, camera.sectionX, camera.sectionY, camera.sectionZ,
                camera.subX, camera.subY, camera.subZ, splitArea));
        this.prefetch();
        int budget = this.requestBudget();
        float waveRadius = this.advanceWave();
        this.writeUniform(this.uniform, camera, camera.viewProjection, camera.clipPlanes, this.evictor.frame(), budget, pyramid,
                splitArea, 0, waveRadius);
        int topCount = this.topNodes.size();
        this.writeLevelSizes(this.levelSizes, topCount);
        boolean walkAround = this.around != null && this.evictor.frame() % AROUND_EVERY == 0;
        this.aroundBudget = Math.min(MAX_REQUESTS, this.aroundBudget + budget);
        if (walkAround) {
            long free = this.tree.geometryCapacity() - this.tree.usedGeometryBytes();
            boolean ask = ViewMotion.still() && this.meshes.backlog() < AROUND_BACKLOG && free > this.tree.geometryCapacity() * AROUND_FREE_SHARE;
            int aroundBudget = Math.min(MAX_REQUESTS, this.aroundBudget);
            this.aroundBudget = 0;
            float unzoom = unzoom(camera);
            this.aroundViewProjection.scaling(unzoom, unzoom, 1).mul(camera.viewProjection);
            CameraState.clipPlanes(this.aroundViewProjection, this.aroundClipPlanes);
            float edge = (float) Math.atan(1 / (unzoom * camera.projection.m00()));
            this.writeUniform(this.aroundUniform, camera, this.aroundViewProjection, this.aroundClipPlanes,
                    Math.max(0, this.evictor.frame() - AROUND_AGE), ask ? aroundBudget : 0, pyramid, splitArea, edge, 0);
            this.writeLevelSizes(this.aroundSizes, topCount);
        }
        vkCmdFillBuffer(cb, this.selected.handle, 0, 4, 0);
        this.staging.recordCopies(cb);

        this.traverse.program.texture(T_PYRAMID, pyramid.view(), pyramid.sampler());
        for (int level = 0; level < LEVELS; level++) {
            VBuffer in = level == 0 ? this.topNodes.buffer() : (level & 1) == 0 ? this.queueA : this.queueB;
            VBuffer out = (level & 1) == 0 ? this.queueB : this.queueA;
            this.traverse.program.uniform1ui(L_LEVEL, level).ssbo(S_QUEUE_IN, in).ssbo(S_QUEUE_OUT, out);
            if (level == 0) {
                this.traverse.bind(cb, this.staging);
                this.traverse.dispatch(cb, (topCount + (1 << GROUP_BITS) - 1) >> GROUP_BITS, 1, 1);
            } else {
                this.traverse.push(cb, this.staging);
                this.traverse.dispatchIndirect(cb, this.levelSizes, level * 16L);
            }
            VBarriers.computeToIndirect(cb);
        }
        if (walkAround) {
            VBarriers.computeToCompute(cb);
            this.around.program.texture(T_PYRAMID, pyramid.view(), pyramid.sampler());
            for (int level = 0; level < LEVELS; level++) {
                VBuffer in = level == 0 ? this.topNodes.buffer() : (level & 1) == 0 ? this.queueA : this.queueB;
                VBuffer out = (level & 1) == 0 ? this.queueB : this.queueA;
                this.around.program.uniform1ui(L_LEVEL, level).ssbo(S_QUEUE_IN, in).ssbo(S_QUEUE_OUT, out);
                if (level == 0) {
                    this.around.bind(cb, this.staging);
                    this.around.dispatch(cb, (topCount + (1 << GROUP_BITS) - 1) >> GROUP_BITS, 1, 1);
                } else {
                    this.around.push(cb, this.staging);
                    this.around.dispatchIndirect(cb, this.aroundSizes, level * 16L);
                }
                VBarriers.computeToIndirect(cb);
                VBarriers.computeToCompute(cb);
            }
        }
        VBarriers.computeToTransfer(cb);

        this.readback.request(this.requests, 0, this.requests.size, this.onRequests);
        if (walkAround) {
            this.readback.request(this.aroundRequests, 0, this.aroundRequests.size, this.onAroundRequests);
            this.readback.request(this.meshUse, 0, MESH_USE_HEADER, this.onDrops);
            this.readback.request(this.aroundSizes, 0, 16L * LEVELS, this.onAroundQueues);
        }
        this.readback.request(this.viewWanted, 4, 8, this.onWave);
        this.readback.recordCopies(cb);
        // Ensure readback copies finish before resetting request and wave counters.
        VBarriers.memory(cb, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
        vkCmdFillBuffer(cb, this.requests.handle, 0, 8, 0);
        if (walkAround) {
            vkCmdFillBuffer(cb, this.aroundRequests.handle, 0, 8, 0);
            vkCmdFillBuffer(cb, this.meshUse.handle, 0, 8, 0);
        }
        vkCmdFillBuffer(cb, this.viewWanted.handle, 4, 8, 0);
        VBarriers.full(cb);
    }

    private void submitRequests(long address, long size) {
        this.animationsDrawn = MemoryUtil.memGetInt(address + 4) != 0;
        int count = MemoryUtil.memGetInt(address);

        if (count < 0 || count > 50_000) {
            Log.error(new IllegalStateException("Implausible LoD mesh request count " + count + ", ignoring this batch"));
            return;
        }
        count = Math.min(count, MAX_REQUESTS);
        if (count == 0) return;
        this.tree.requested(LodTreeWorker.readPositions(address + 8, count));
    }

    private void submitAroundRequests(long address, long size) {
        int count = Math.min(MemoryUtil.memGetInt(address), MAX_REQUESTS);
        if (count <= 0) return;
        this.tree.requestedAround(LodTreeWorker.readPositions(address + 8, count));
    }

    private void checkAroundQueues(long address, long size) {
        for (int level = 0; level < LEVELS; level++) {
            if (MemoryUtil.memGetInt(address + level * 16L + 12) > MAX_SELECTED) {
                this.evictor.holdFolds();
                return;
            }
        }
    }

    private void submitDrops(long address, long size) {
        int count = Math.min(MemoryUtil.memGetInt(address), MAX_DROPS);
        if (count <= 0) return;
        this.tree.dropUnused(LodTreeWorker.readPositions(address + 8, count));
    }

    private void writeLevelSizes(VBuffer target, int topCount) {
        long sizes = this.staging.write(target, 0, 16L * LEVELS);
        for (int level = 0; level < LEVELS; level++) {
            // Indirect dispatch (x, y, z) followed by queued node count for the level.
            int groups = level == 0 ? (topCount + (1 << GROUP_BITS) - 1) >> GROUP_BITS : 0;
            long at = sizes + level * 16L;
            MemoryUtil.memPutInt(at, groups);
            MemoryUtil.memPutInt(at + 4, 1);
            MemoryUtil.memPutInt(at + 8, 1);
            MemoryUtil.memPutInt(at + 12, level == 0 ? topCount : 0);
        }
    }

    boolean animationsDrawn() {
        return this.animationsDrawn;
    }

    int topNodeCount() {
        return this.topNodes.size();
    }

    void free() {
        this.traverse.free();
        this.markFade.free();
        this.viewWanted.free();
        this.meshUse.free();
        this.fadeStates.free();
        if (this.around != null) {
            this.around.free();
            this.aroundUniform.free();
            this.aroundSizes.free();
            this.aroundRequests.free();
        }
        for (var buffer : new VBuffer[]{this.uniform, this.requests, this.levelSizes, this.queueA, this.queueB, this.selected,
                this.selectedFades, this.topNodes.buffer()}) {
            buffer.free();
        }
    }
}
