package rodozodo.infinite.client.render;

import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.config.HorizonFog;
import rodozodo.infinite.client.core.vk.*;
import rodozodo.infinite.client.core.vk.DepthConvention;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

// Composites the finished LOD image over Minecraft frame
final class Compositor {
    private static final float EDGE_FADE = 0.1f;

    private static final int T_DEPTH = 0, T_COLOUR = 1, T_BORDER = 2;
    private static final int L_LOD_TO_CAMERA = 0, L_VANILLA_VIEW_PROJECTION = 1, L_FOG = 2, L_FOG_COLOUR = 3, L_FADE = 4,
            L_SHARPNESS = 5;

    private final DepthConvention depth;
    private final StagingBuffer staging;
    private final IndexBuffers indices;
    private final long sampler;
    private final HorizonFog fogMode;
    private final VProgram resolvedProgram, plainProgram;
    private VProgram program;
    private final Long2ObjectOpenHashMap<VGraphicsPipeline> pipelines = new Long2ObjectOpenHashMap<>();
    private final Matrix4f lodToCamera = new Matrix4f(), vanillaViewProjection = new Matrix4f();

    Compositor(DepthConvention depth, StagingBuffer staging, IndexBuffers indices, Samplers samplers) {
        this.depth = depth;
        this.staging = staging;
        this.indices = indices;
        this.sampler = samplers.point;
        this.fogMode = ClientSettings.FOG;
        this.resolvedProgram = this.program(true);
        this.plainProgram = this.program(false);
        this.program = this.resolvedProgram;
    }

    private VProgram program(boolean taa) {
        return VProgram.graphics(taa ? "composite_taa" : "composite", VShaderCompiler.load("infinite:frame/fullscreen.vert"),
                VShaderCompiler.load("infinite:frame/composite.frag"), VDefines.of(this.depth)
                        .define("HAS_FOG")
                        .defineIf("HAS_FADE", this.fogMode.edgeFade)
                        .defineIf("TAA", taa), 0);
    }

    boolean draws(CameraState camera, float vanillaDistance) {
        var fog = camera.fog;
        return fog == null || fog.environmentalEnd() >= vanillaDistance || fog.alpha() < 1;
    }

    void record(VkCommandBuffer cb, CameraState camera, LodTargets targets, long colour, boolean resolved, float sharpness,
                int colourFormat, int depthFormat, float vanillaDistance) {
        this.program = resolved ? this.resolvedProgram : this.plainProgram;
        if (resolved) this.program.uniform1f(L_SHARPNESS, sharpness);
        this.setFog(camera, vanillaDistance);
        if (this.fogMode.edgeFade) {
            float lodDistance = ClientSettings.get().renderDistanceBlocks() - 32;
            float end = Math.max(vanillaDistance, lodDistance);
            float start = Math.max(vanillaDistance, lodDistance * (1 - EDGE_FADE));
            float perBlock = 1 / Math.max(end - start, 1);
            this.program.uniform2f(L_FADE, -start * perBlock, perBlock);
        }
        this.lodToCamera.set(camera.viewProjection).invert();
        this.vanillaViewProjection.set(camera.vanillaProjection).mul(camera.modelView);
        this.program.texture(T_DEPTH, targets.depthView(), this.sampler)
                .texture(T_COLOUR, colour, this.sampler)
                .texture(T_BORDER, targets.borderView(), this.sampler)
                .uniformMat4(L_LOD_TO_CAMERA, this.lodToCamera)
                .uniformMat4(L_VANILLA_VIEW_PROJECTION, this.vanillaViewProjection);

        var pipeline = this.pipeline(colourFormat, depthFormat);
        pipeline.bind(cb);
        pipeline.flush(cb, this.staging);
        vkCmdBindIndexBuffer(cb, this.indices.quads.handle, 0, VK_INDEX_TYPE_UINT16);
        vkCmdDrawIndexed(cb, 6, 1, 0, 0, 0);
    }

    private void setFog(CameraState camera, float vanillaDistance) {
        var fog = camera.fog;
        boolean nether = ClientSettings.get().netherFogIn(Minecraft.getInstance().level);
        if (fog == null || (!ClientSettings.FOG.lodFog && !nether)) {
            this.program.uniform3f(L_FOG, 0, 0, 0);
            this.program.uniform4f(L_FOG_COLOUR, 0, 0, 0, 0);
            return;
        }
        float range = fog.environmentalEnd() - fog.environmentalStart();
        if (Math.abs(range) <= 1) {
            this.program.uniform3f(L_FOG, 0, 0, 0);
            this.program.uniform4f(L_FOG_COLOUR, 0, 0, 0, 0);
            return;
        }
        float scale = 1 / range;
        float offset = -fog.environmentalStart() * scale;
        float corner = Math.max(vanillaDistance, 20 * 16) * (float) Math.sqrt(3);
        float cap = nether ? 1 : Math.clamp(corner * scale + offset, 0, 1);
        this.program.uniform3f(L_FOG, scale, offset, cap);
        this.program.uniform4f(L_FOG_COLOUR, fog.red(), fog.green(), fog.blue(), fog.alpha());
    }

    private VGraphicsPipeline pipeline(int colourFormat, int depthFormat) {

        // Bit 63 distinguishes resolvedProgram vs plainProgram. Vulkan format enums fit well below 2^31
        long key = ((long) colourFormat << 32) | (depthFormat & 0xFFFFFFFFL) | (this.program == this.resolvedProgram ? 1L << 63 : 0);
        var pipeline = this.pipelines.get(key);
        if (pipeline == null) {
            pipeline = VGraphicsPipeline.builder(this.program)
                    .depth(true, true, SectionDrawer.nearerOrEqual(this.depth))
                    .blend(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorFormats(colourFormat)
                    .depthFormat(depthFormat, false)
                    .build();
            this.pipelines.put(key, pipeline);
        }
        return pipeline;
    }

    void free() {
        this.pipelines.values().forEach(VGraphicsPipeline::free);
        this.resolvedProgram.free();
        this.plainProgram.free();
    }
}
