package rodozodo.infinite.client.render;

import rodozodo.infinite.Log;
import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.core.vk.*;
import rodozodo.infinite.client.core.vk.DepthConvention;
import rodozodo.infinite.client.lod.mesh.AmbientOcclusion;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_ARITHMETIC_BIT;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;
import static org.lwjgl.vulkan.VK13.*;

final class SectionDrawer {
    static final int TRANSLUCENT_CAPACITY = 100_000;
    static final int SKIRT_CAPACITY = 16_384;
    private final int blockQuads = resolveBlockQuads();
    private static final int DEFAULT_BLOCK_QUADS = 16;

    // generate.comp packs (quadCount - 1) into 10 bits of the blocks second word.
    private static final int MOST_BLOCK_QUADS = 1024;

    private static int resolveBlockQuads() {
        String property = System.getProperty("infinite.blockQuads");
        if (property == null) return DEFAULT_BLOCK_QUADS;
        try {
            return Math.max(1, Math.min(MOST_BLOCK_QUADS, Integer.parseInt(property.trim())));
        } catch (NumberFormatException e) {
            Log.warn("Ignoring infinite.blockQuads=" + property + ": not a whole number");
            return DEFAULT_BLOCK_QUADS;
        }
    }
    private static final int DISTANCE_BUCKETS = 1024;
    private static final int GENERATE_GROUP = 128;
    private static final int UNIFORM_BYTES = 1024;
    private static final long COUNT_TRANSLUCENT = 12, OCCLUSION_DRAW = 20, NEWLY_SOLID_DRAW = 60, NEWLY_MASKED_DRAW = 80,
            COUNT_BLOCKS = 100, SOLID_DRAW = 116, MASKED_DRAW = 136, COUNT_QUADS = 156, COUNT_SKIRTS = 172;
    private static final long ONE = 4;

    private static final int U_FRAME = 0;
    private static final int S_CONTROL = 0, S_SELECTED = 1, S_COMMANDS = 2, S_BLOCKS = 2, S_SECTIONS = 3, S_VISIBLE_NOW = 4,
            S_VISIBLE_BEFORE = 5, S_DRAW_POSITIONS = 6, S_TRANSLUCENCY = 7, S_SELECTED_FADES = 8, S_DRAW_CORNERS = 10,
            S_DRAW_AO = 11, S_BUCKETS = 0;
    private static final int L_SOLID_CAPACITY = 0, L_MASKED_CAPACITY = 1, L_NEWLY_VISIBLE_CAPACITY = 2;
    private static final int S_BOX_SELECTED = 0, S_BOX_SECTIONS = 1, S_BOX_VISIBLE = 2;
    private static final int S_TERRAIN_MODELS = 0, S_TERRAIN_TINTS = 1, S_TERRAIN_DRAW_POSITIONS = 2,
            S_TERRAIN_BLOCKS = 4, S_TERRAIN_DRAW_CORNERS = 5, S_TERRAIN_DRAW_AO = 6;
    private static final int T_ATLAS = 0, T_LIGHTMAP = 1, T_VANILLA_MASK = 2, T_BLOCK_ATLAS = 3, T_BORDER = 4;
    private static final int I_WATER_CUT = 0;

    private static final int LIST_TRANSLUCENT = 0, LIST_SKIRTS = 1;
    private static final List<DrawCountStrategy.DrawList> LISTS = List.of(
            new DrawCountStrategy.DrawList("translucent", 0, COUNT_TRANSLUCENT, TRANSLUCENT_CAPACITY),
            new DrawCountStrategy.DrawList("skirts", (long) DrawCountStrategy.STRIDE * TRANSLUCENT_CAPACITY, COUNT_SKIRTS, SKIRT_CAPACITY));

    private final ModelGpuStore models;
    private final GeometryPages geometry;
    private final StagingBuffer staging;
    private final IndexBuffers indices;
    private final Samplers samplers;
    private final DrawCountStrategy drawCounts;
    private final boolean gpuDrawCounts;
    private final VGraphicsPipeline opaque, opaqueSolid, translucent, translucentSkirts, cullBoxes;
    private final VCompute prepare, generate, bucketOffsets, sortTranslucent;
    private final SoftwareRaster software;
    private final VBuffer uniform, control, commands, drawPositions, translucency;
    private final VBuffer drawCorners;
    private final VBuffer drawAo;
    private VBuffer blocks;
    private int solidBlockCapacity, maskedBlockCapacity, newlyVisibleBlockCapacity;
    private static final boolean BY_USE = !"false".equalsIgnoreCase(System.getProperty("infinite.drawBlocksByUse"));
    private static final int LEAST_USE_BLOCKS = 1 << 19;
    private volatile int wantedSolid, wantedMasked, wantedNewly;
    private int overflowFrames;
    private final VBuffer selectedFades;
    private final VBuffer[] visibility = new VBuffer[2];
    private final org.joml.Matrix4f sectionViewProjScratch = new org.joml.Matrix4f();
    private int frameId;

    SectionDrawer(SetupCommands setup, DepthConvention depth, ModelGpuStore models, GeometryPages geometry, StagingBuffer staging,
                  ReadbackBuffer readback, IndexBuffers indices, Samplers samplers, int depthStencilFormat, VBuffer selectedFades) {
        var ctx = VkContext.get();
        this.models = models;
        this.selectedFades = selectedFades;
        this.geometry = geometry;
        this.staging = staging;
        this.indices = indices;
        this.samplers = samplers;
        this.gpuDrawCounts = !ctx.drawCountFallback;
        this.drawCounts = DrawCountStrategy.create(ctx.drawCountFallback, LISTS, readback);

        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        int indirect = storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        this.uniform = VBuffer.create("lod_draw_uniform", UNIFORM_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VBuffer.Access.DEVICE_LOCAL);
        this.control = VBuffer.create("lod_draw_control", 1024, indirect, VBuffer.Access.DEVICE_LOCAL);
        this.commands = VBuffer.create("lod_draw_commands", (long) DrawCountStrategy.STRIDE * (TRANSLUCENT_CAPACITY + SKIRT_CAPACITY), indirect, VBuffer.Access.DEVICE_LOCAL);
        this.ensureBlockCapacity();
        this.drawPositions = VBuffer.create("lod_draw_positions", 8L * LodSelector.MAX_SELECTED, storage, VBuffer.Access.DEVICE_LOCAL);
        this.drawCorners = VBuffer.create("lod_draw_corners", 16L * LodSelector.MAX_SELECTED, storage, VBuffer.Access.DEVICE_LOCAL);
        this.drawAo = VBuffer.create("lod_draw_ao", 4L * LodSelector.MAX_SELECTED, storage, VBuffer.Access.DEVICE_LOCAL);
        this.translucency = VBuffer.create("lod_translucency", 4L * (DISTANCE_BUCKETS + TRANSLUCENT_CAPACITY), storage, VBuffer.Access.DEVICE_LOCAL);
        for (int i = 0; i < 2; i++) {
            this.visibility[i] = VBuffer.growable("lod_section_visibility_" + i, NodeSync.LEAST_SECTIONS * 4L, storage);
        }
        for (var buffer : new VBuffer[]{this.control, this.commands, this.drawPositions, this.drawCorners,
                this.drawAo, this.translucency, this.visibility[0], this.visibility[1]}) {
            setup.fill(buffer, 0);
        }

        var listDefines = new VDefines()
                .define("SELECTED_CAPACITY", LodSelector.MAX_SELECTED)
                .define("TRANSLUCENT_CAPACITY", TRANSLUCENT_CAPACITY)
                .define("SKIRT_CAPACITY", SKIRT_CAPACITY)
                .define("DISTANCE_BUCKETS", DISTANCE_BUCKETS)
                .define("MAX_QUADS_PER_DRAW", IndexBuffers.QUADS + "u")
                .define("BLOCK_QUADS", this.blockQuads + "u")
                .define("GENERATE_GROUP", GENERATE_GROUP + "u");
        this.prepare = VCompute.asset("infinite:draws/prepare.comp", listDefines);
        this.generate = VCompute.asset("infinite:draws/generate.comp", listDefines);
        this.sortTranslucent = VCompute.asset("infinite:draws/sort_translucent.comp", listDefines);
        boolean subgroupScan = subgroupScan();
        this.bucketOffsets = VCompute.asset("infinite:draws/bucket_offsets.comp", new VDefines().defineIf("SUBGROUP_SCAN", subgroupScan));

        var terrainDefines = VDefines.of(depth)
                .define("GEOMETRY_PAGE_COUNT", geometry.pageCount())
                .define("GEOMETRY_PAGE_SHIFT", geometry.pageShift())
                .define("VANILLA_MASK_SHIFT", VanillaMask.SHIFT)
                .define("AO_CORNER_WORDS", AmbientOcclusion.glslWords())
                .define("AO_CORNER_TABLE", AmbientOcclusion.glslTable());
        String vertex = VShaderCompiler.load("infinite:terrain/terrain.vert");
        String fragment = VShaderCompiler.load("infinite:terrain/terrain.frag");
        int nearer = nearerOrEqual(depth);
        this.opaque = VGraphicsPipeline.builder(VProgram.graphics("lod_opaque", vertex, fragment, terrainDefines, geometry.descriptorSetLayout()))
                .depth(true, true, nearer)
                .stencil(VK_COMPARE_OP_EQUAL, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, 0xFF, 0x00)
                .colorFormats(LodTargets.COLOUR_FORMAT).depthFormat(depthStencilFormat, true)
                .build();
        this.opaqueSolid = VGraphicsPipeline.builder(VProgram.graphics("lod_opaque_solid", vertex, fragment,
                        terrainDefines.copy().define("SOLID"), geometry.descriptorSetLayout()))
                .depth(true, true, nearer)
                .stencil(VK_COMPARE_OP_EQUAL, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, 0xFF, 0x00)
                .colorFormats(LodTargets.COLOUR_FORMAT).depthFormat(depthStencilFormat, true)
                .build();
        this.translucent = VGraphicsPipeline.builder(VProgram.graphics("lod_translucent", vertex, fragment,
                        terrainDefines.copy().define("TRANSLUCENT"), geometry.descriptorSetLayout()))
                .depth(true, true, nearer)
                .stencil(VK_COMPARE_OP_EQUAL, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, 0xFF, 0x00)
                .blend(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .colorFormats(LodTargets.COLOUR_FORMAT).depthFormat(depthStencilFormat, true)
                .build();
        this.translucentSkirts = VGraphicsPipeline.builder(VProgram.graphics("lod_translucent_skirts", vertex, fragment,
                        terrainDefines.copy().define("TRANSLUCENT").define("SKIRTS"), geometry.descriptorSetLayout()))
                .depth(true, true, nearer)
                .stencil(VK_COMPARE_OP_EQUAL, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, 0xFF, 0x00)
                .blend(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .colorFormats(LodTargets.COLOUR_FORMAT).depthFormat(depthStencilFormat, true)
                .build();
        this.cullBoxes = VGraphicsPipeline.builder(VProgram.graphics("lod_occlusion_boxes", VShaderCompiler.load("infinite:draws/occlusion.vert"),
                        VShaderCompiler.load("infinite:draws/occlusion.frag"), VDefines.of(depth), 0))
                .depth(true, false, nearer)
                .stencil(VK_COMPARE_OP_EQUAL, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, 0xFF, 0x00)
                .depthFormat(depthStencilFormat, true)
                .build();
        this.software = SoftwareRaster.wanted() ? new SoftwareRaster(staging, indices, listDefines, terrainDefines,
                geometry.descriptorSetLayout(), nearer, depthStencilFormat, this.blockQuads) : null;
    }

    static boolean subgroupScan() {
        var limits = VkContext.get().limits;
        return (limits.subgroupSupportedOperations() & VK_SUBGROUP_FEATURE_ARITHMETIC_BIT) != 0
                && (limits.subgroupSupportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0 && limits.subgroupSize() >= 16;
    }

    static int nearerOrEqual(DepthConvention depth) {
        return depth.reverseZ() ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL;
    }


    private int limit(int capacity) {
        return Math.min(this.geometry.getSectionCount(), capacity);
    }

    private void ensureBlockCapacity() {
        long stored = this.geometry.allocatedBytes() / this.geometry.pageBytes() * this.geometry.getPageElements();
        long needed = stored / this.blockQuads + 7L * LodSelector.MAX_SELECTED;
        if (BY_USE) {
            this.ensureBlocksByUse(needed);
            return;
        }
        if (this.blocks != null && needed <= this.solidBlockCapacity) return;
        if (this.blocks != null) this.blocks.free();
        this.solidBlockCapacity = Math.toIntExact(needed);
        this.maskedBlockCapacity = Math.toIntExact(needed);
        this.newlyVisibleBlockCapacity = Math.max(LodSelector.MAX_SELECTED, this.solidBlockCapacity / 4);
        long blocks = (long) this.solidBlockCapacity + this.maskedBlockCapacity + 2L * this.newlyVisibleBlockCapacity;
        this.blocks = VBuffer.create("lod_draw_blocks", 8L * blocks,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VBuffer.Access.DEVICE_LOCAL);
    }

    private void ensureBlocksByUse(long bound) {
        int solid = this.wantedSolid, masked = this.wantedMasked, newly = this.wantedNewly;
        if (this.blocks != null && solid * 2L <= this.solidBlockCapacity && masked * 2L <= this.maskedBlockCapacity
                && newly * 2L <= this.newlyVisibleBlockCapacity) return;
        if (this.blocks != null) this.blocks.free();
        this.solidBlockCapacity = (int) Math.min(bound, Math.max(LEAST_USE_BLOCKS, solid * 4L));
        this.maskedBlockCapacity = (int) Math.min(bound, Math.max(LEAST_USE_BLOCKS, masked * 4L));
        this.newlyVisibleBlockCapacity = (int) Math.min(bound, Math.max(LEAST_USE_BLOCKS, newly * 4L));
        long blocks = (long) this.solidBlockCapacity + this.maskedBlockCapacity + 2L * this.newlyVisibleBlockCapacity;
        this.blocks = VBuffer.create("lod_draw_blocks", 8L * blocks, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VBuffer.Access.DEVICE_LOCAL);
    }

    int sectionCapacity() {
        return (int) (this.visibility[0].size / 4);
    }

    void growSections(VkCommandBuffer cb, int sections) {
        for (var buffer : this.visibility) buffer.grow(cb, sections * 4L, 0);
    }

    void requestBlockCounts(ReadbackBuffer readback) {
        if (!BY_USE) return;
        int solidCapacity = this.solidBlockCapacity, maskedCapacity = this.maskedBlockCapacity, newlyCapacity = this.newlyVisibleBlockCapacity;
        readback.request(this.control, COUNT_BLOCKS, 16, (address, size) -> {
            int solid = MemoryUtil.memGetInt(address), masked = MemoryUtil.memGetInt(address + 4);
            int newly = Math.max(MemoryUtil.memGetInt(address + 8), MemoryUtil.memGetInt(address + 12));
            if (solid > solidCapacity || masked > maskedCapacity || newly > newlyCapacity) this.overflowFrames++;
            this.wantedSolid = Math.max(this.wantedSolid, solid);
            this.wantedMasked = Math.max(this.wantedMasked, masked);
            this.wantedNewly = Math.max(this.wantedNewly, newly);
        });
    }

    private void drawFromControl(VkCommandBuffer cb, long offset) {
        if (this.gpuDrawCounts) {

            // Use indirect-count with count=1: lavapipe leaks earlier indirect-count state onto plain indirect draws.
            vkCmdDrawIndexedIndirectCount(cb, this.control.handle, offset, this.control.handle, ONE, 1, DrawCountStrategy.STRIDE);
        } else {
            vkCmdDrawIndexedIndirect(cb, this.control.handle, offset, 1, DrawCountStrategy.STRIDE);
        }
    }

    void beginFrame(CameraState camera) {
        this.frameId = camera.frameId;
        var light = Minecraft.getInstance().level.cardinalLighting();
        var viewProjection = camera.sectionViewProjection(this.sectionViewProjScratch);
        new UniformWriter(this.staging.write(this.uniform, 0, UNIFORM_BYTES), UNIFORM_BYTES)
                .mat4(viewProjection)
                .ivec3(camera.sectionX, camera.sectionY, camera.sectionZ)
                .uint(camera.frameId)
                .vec3(camera.subX, camera.subY, camera.subZ)
                .uint(ClientSettings.get().ambientOcclusion && Minecraft.getInstance().options.ambientOcclusion().get() ? 1 : 0)
                .f32(camera.curveDrop)
                .f32(camera.curveStart)
                .f32(camera.vanillaDistance)
                .vec2(camera.jitterX * camera.width * 0.5f, camera.jitterY * camera.height * 0.5f)
                .vec4(light.up(), light.down(), light.north(), light.east())
                .mat4(viewProjection.invert())
                .f32(this.models.atlasScaleV());
    }

    private void bindTerrain(VkCommandBuffer cb, VGraphicsPipeline pipeline, long lightmap, long vanillaMask, long border) {
        this.bindTerrain(cb, pipeline, lightmap, vanillaMask, border, this.blocks);
    }

    private void bindTerrain(VkCommandBuffer cb, VGraphicsPipeline pipeline, long lightmap, long vanillaMask, long border, VBuffer blocks) {
        pipeline.bind(cb);
        this.terrainResources(pipeline.program, lightmap, vanillaMask, border, blocks);
        pipeline.flush(cb, this.staging);
        vkCmdSetStencilReference(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 1);
        vkCmdBindIndexBuffer(cb, this.indices.quads.handle, 0, VK_INDEX_TYPE_UINT16);
    }

    private void terrainResources(VProgram program, long lightmap, long vanillaMask, long border, VBuffer blocks) {
        program
                .ubo(U_FRAME, this.uniform)
                .ssbo(S_TERRAIN_MODELS, this.models.models)
                .ssbo(S_TERRAIN_TINTS, this.models.colours)
                .ssbo(S_TERRAIN_DRAW_POSITIONS, this.drawPositions)
                .ssbo(S_TERRAIN_DRAW_CORNERS, this.drawCorners)
                .ssbo(S_TERRAIN_DRAW_AO, this.drawAo)
                .ssbo(S_TERRAIN_BLOCKS, blocks)
                .texture(T_ATLAS, this.models.atlas.view, this.models.atlasSampler)
                .texture(T_LIGHTMAP, lightmap, this.samplers.bilinear)
                .texture(T_VANILLA_MASK, vanillaMask, this.samplers.point)
                .texture(T_BORDER, border, this.samplers.point)
                .image(I_WATER_CUT, border)
                .texture(T_BLOCK_ATLAS, ModelGpuStore.blockAtlasView(), this.samplers.blockAtlas)
                .setExternalSet(this.geometry.descriptorSet());
    }

    void drawOpaque(VkCommandBuffer cb, long lightmap, long vanillaMask, long border) {
        if (this.geometry.getSectionCount() == 0) return;
        this.drawSolid(cb, lightmap, vanillaMask, border);
        this.drawMasked(cb, lightmap, vanillaMask, border);
    }

    void drawSolid(VkCommandBuffer cb, long lightmap, long vanillaMask, long border) {
        if (this.geometry.getSectionCount() == 0) return;
        this.bindTerrain(cb, this.opaqueSolid, lightmap, vanillaMask, border);
        this.drawFromControl(cb, SOLID_DRAW);
    }

    void drawMasked(VkCommandBuffer cb, long lightmap, long vanillaMask, long border) {
        if (this.geometry.getSectionCount() == 0) return;
        this.bindTerrain(cb, this.opaque, lightmap, vanillaMask, border);
        this.drawFromControl(cb, MASKED_DRAW);
    }

    void drawNewlyVisible(VkCommandBuffer cb, long lightmap, long vanillaMask, long border) {
        if (this.geometry.getSectionCount() == 0) return;
        this.bindTerrain(cb, this.opaqueSolid, lightmap, vanillaMask, border);
        this.drawFromControl(cb, NEWLY_SOLID_DRAW);
        this.bindTerrain(cb, this.opaque, lightmap, vanillaMask, border);
        this.drawFromControl(cb, NEWLY_MASKED_DRAW);
    }

    boolean softwareRaster() {
        return this.software != null;
    }

    void rasterSoftware(VkCommandBuffer cb, LodTargets targets, long lightmap, long vanillaMask, long border, int pass,
                        Runnable keysKept) {
        if (this.geometry.getSectionCount() == 0) return;
        this.software.raster(cb, pass, targets.width(), targets.height(), this.control,
                program -> this.terrainResources(program, lightmap, vanillaMask, border, this.blocks), keysKept);
    }

    void resolveSoftware(VkCommandBuffer cb, long lightmap, long vanillaMask, long border, int pass) {
        if (this.geometry.getSectionCount() == 0) return;

        // Pass 1 newly-visible payloads start after pass 0's solid and masked block regions
        int firstPayload = pass == 0 ? 1 : (this.solidBlockCapacity + this.maskedBlockCapacity) * this.blockQuads + 1;
        this.software.resolve(cb, pass, firstPayload, program -> this.terrainResources(program, lightmap, vanillaMask, border, this.blocks));
    }

    void drawSoftwareFallback(VkCommandBuffer cb, long lightmap, long vanillaMask, long border, int pass) {
        if (this.geometry.getSectionCount() == 0) return;
        this.bindTerrain(cb, this.opaque, lightmap, vanillaMask, border, this.software.fallbackBlocks());
        long offset = SoftwareRaster.fallbackDrawOffset(pass);
        if (this.gpuDrawCounts) {
            vkCmdDrawIndexedIndirectCount(cb, this.software.fallbackDraws().handle, offset, this.control.handle, ONE, 1, DrawCountStrategy.STRIDE);
        } else {
            vkCmdDrawIndexedIndirect(cb, this.software.fallbackDraws().handle, offset, 1, DrawCountStrategy.STRIDE);
        }
    }

    void drawTranslucent(VkCommandBuffer cb, long lightmap, long vanillaMask, long border) {
        if (this.geometry.getSectionCount() == 0) return;
        this.bindTerrain(cb, this.translucent, lightmap, vanillaMask, border);
        this.drawCounts.draw(cb, this.commands, this.control, LIST_TRANSLUCENT, this.limit(TRANSLUCENT_CAPACITY));
        this.bindTerrain(cb, this.translucentSkirts, lightmap, vanillaMask, border);
        this.drawCounts.draw(cb, this.commands, this.control, LIST_SKIRTS, this.limit(SKIRT_CAPACITY));
    }

    void buildDraws(VkCommandBuffer cb, VBuffer selected, LodTargets targets) {
        if (this.geometry.getSectionCount() == 0) return;

        var visibleNow = this.visibility[this.frameId & 1];
        var visibleBefore = this.visibility[(this.frameId + 1) & 1];
        var sections = this.geometry.getMetadataBuffer();

        this.ensureBlockCapacity();
        this.prepare.program.ubo(U_FRAME, this.uniform).ssbo(S_CONTROL, this.control).ssbo(S_SELECTED, selected)
                .uniform1ui(L_SOLID_CAPACITY, this.solidBlockCapacity)
                .uniform1ui(L_MASKED_CAPACITY, this.maskedBlockCapacity)
                .uniform1ui(L_NEWLY_VISIBLE_CAPACITY, this.newlyVisibleBlockCapacity);
        this.prepare.bind(cb, this.staging).dispatch(cb, 1, 1, 1);
        VBarriers.computeToIndirect(cb);

        VRendering.begin(cb, targets.width(), targets.height(), VK_NULL_HANDLE, targets.depthStencilView(), true, VRendering.Clear.NONE);
        this.cullBoxes.bind(cb);
        this.cullBoxes.program
                .ubo(U_FRAME, this.uniform)
                .ssbo(S_BOX_SELECTED, selected)
                .ssbo(S_BOX_SECTIONS, sections)
                .ssbo(S_BOX_VISIBLE, visibleNow);
        this.cullBoxes.flush(cb, this.staging);
        vkCmdSetStencilReference(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 1);
        vkCmdBindIndexBuffer(cb, this.indices.box.handle, 0, VK_INDEX_TYPE_UINT16);
        this.drawFromControl(cb, OCCLUSION_DRAW);
        VRendering.end(cb);
        VBarriers.memory(cb,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT);

        this.drawCounts.prepare(cb, this.commands, LIST_TRANSLUCENT, this.limit(TRANSLUCENT_CAPACITY));
        this.drawCounts.prepare(cb, this.commands, LIST_SKIRTS, this.limit(SKIRT_CAPACITY));
        vkCmdFillBuffer(cb, this.translucency.handle, 0, 4L * DISTANCE_BUCKETS, 0);
        VBarriers.transferToCompute(cb);
        this.generate.program
                .ubo(U_FRAME, this.uniform)
                .ssbo(S_CONTROL, this.control)
                .ssbo(S_SELECTED, selected)
                .ssbo(S_BLOCKS, this.blocks)
                .ssbo(S_SECTIONS, sections)
                .ssbo(S_VISIBLE_NOW, visibleNow)
                .ssbo(S_VISIBLE_BEFORE, visibleBefore)
                .ssbo(S_DRAW_POSITIONS, this.drawPositions)
                .ssbo(S_TRANSLUCENCY, this.translucency)
                .ssbo(S_SELECTED_FADES, this.selectedFades)
                .ssbo(S_DRAW_CORNERS, this.drawCorners)
                .ssbo(S_DRAW_AO, this.drawAo);
        this.generate.bind(cb, this.staging).dispatchIndirect(cb, this.control, 0);
        VBarriers.computeToCompute(cb);

        this.bucketOffsets.program.ssbo(S_BUCKETS, this.translucency, 0, 4L * DISTANCE_BUCKETS);
        this.bucketOffsets.bind(cb, this.staging).dispatch(cb, 1, 1, 1);
        VBarriers.computeToCompute(cb);
        this.sortTranslucent.program
                .ubo(U_FRAME, this.uniform)
                .ssbo(S_CONTROL, this.control)
                .ssbo(S_SELECTED, selected)
                .ssbo(S_COMMANDS, this.commands)
                .ssbo(S_SECTIONS, sections)
                .ssbo(S_TRANSLUCENCY, this.translucency);
        this.sortTranslucent.bind(cb, this.staging).dispatchIndirect(cb, this.control, 0);
        VBarriers.computeToIndirect(cb);

        this.drawCounts.afterGeneration(cb, this.control);
    }

    private int devSolid = -1, devMasked = -1, devNewlySolid = -1, devNewlyMasked = -1, devTranslucent = -1;
    private int devSolidQuads = -1, devMaskedQuads = -1, devNewlySolidQuads = -1, devNewlyMaskedQuads = -1;

    void requestDevStats(ReadbackBuffer readback) {
        readback.request(this.control, COUNT_TRANSLUCENT, 4, (address, size) -> this.devTranslucent = MemoryUtil.memGetInt(address));
        readback.request(this.control, COUNT_BLOCKS, 16, (address, size) -> {
            this.devSolid = MemoryUtil.memGetInt(address);
            this.devMasked = MemoryUtil.memGetInt(address + 4);
            this.devNewlySolid = MemoryUtil.memGetInt(address + 8);
            this.devNewlyMasked = MemoryUtil.memGetInt(address + 12);
        });
        readback.request(this.control, COUNT_QUADS, 16, (address, size) -> {
            this.devSolidQuads = MemoryUtil.memGetInt(address);
            this.devMaskedQuads = MemoryUtil.memGetInt(address + 4);
            this.devNewlySolidQuads = MemoryUtil.memGetInt(address + 8);
            this.devNewlyMaskedQuads = MemoryUtil.memGetInt(address + 12);
        });
        if (this.software != null) this.software.requestStats(readback);
    }

    void addDebug(List<String> lines) {
        this.drawCounts.addDebug(lines);
        if (this.devSolid >= 0) {
            int blocks = this.devSolid + this.devMasked + this.devNewlySolid + this.devNewlyMasked;
            long slots = (long) blocks * this.blockQuads;
            long quads = this.devSolidQuads < 0 ? -1 : (long) this.devSolidQuads + this.devMaskedQuads
                    + this.devNewlySolidQuads + this.devNewlyMaskedQuads;
            lines.add("  LoD draws " + quadsLine(slots) + " slots"
                    + (quads < 0 ? "" : " for " + quadsLine(quads) + " quads ("
                            + Math.round(100.0 - 100.0 * quads / Math.max(1L, slots)) + "% padding)")
                    + " in " + blocks + " blocks of " + this.blockQuads + " (" + this.devSolid + " solid,"
                    + this.devMasked + " masked, " + this.devNewlySolid + " + " + this.devNewlyMasked
                    + " newly visible) · translucent " + this.devTranslucent + " draws");
        }
        if (BY_USE && this.overflowFrames > 0) {
            lines.add("  Blocks    by use: " + this.solidBlockCapacity + " solid, " + this.maskedBlockCapacity + " masked, "
                    + this.newlyVisibleBlockCapacity + " newly visible · " + this.overflowFrames + " frames overflowed");
        }
        if (this.software != null) {
            String raster = this.software.statsLine();
            if (raster != null) lines.add(raster);
        }
    }

    private static String quadsLine(long quads) {
        if (quads >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.2fM", quads / 1e6);
        return (quads / 1000) + "k";
    }

    void free() {
        for (var pipeline : new VGraphicsPipeline[]{this.opaque, this.opaqueSolid, this.translucent, this.translucentSkirts,
                this.cullBoxes}) {
            pipeline.program.free();
            pipeline.free();
        }
        if (this.software != null) this.software.free();
        for (var compute : new VCompute[]{this.prepare, this.generate, this.bucketOffsets, this.sortTranslucent}) {
            compute.free();
        }
        for (var buffer : new VBuffer[]{this.uniform, this.control, this.commands, this.blocks, this.drawPositions, this.drawCorners, this.drawAo, this.translucency, this.visibility[0], this.visibility[1]}) {
            buffer.free();
        }
    }
}
