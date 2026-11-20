package rodozodo.infinite.client.render;

import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.core.vk.*;
import rodozodo.infinite.client.core.vk.DepthConvention;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

final class LodAmbientOcclusion {
    private static final int FORMAT = VK_FORMAT_R16G16_SFLOAT;
    private static final float RADIUS_VOXELS = 6;
    private static final float LEAST_RADIUS = 4;
    private static final float STRENGTH = 0.3f;
    private static final int DOWNSCALE = 2;

    private static final int T_DEPTH = 0, T_OCCLUSION = 1, L_LOD_TO_CAMERA = 0, L_SHAPE = 1, L_FADE_IN = 2;

    record Shade(float start, float end, float radiusPerBlock) {}

    private final StagingBuffer staging;
    private final IndexBuffers indices;
    private final long sampler;
    private final VProgram occlusionProgram, applyProgram;
    private final VGraphicsPipeline occlusion, apply;
    private final Matrix4f lodToCamera = new Matrix4f();
    private VImage image;
    private int width, height;

    LodAmbientOcclusion(DepthConvention depth, StagingBuffer staging, IndexBuffers indices, Samplers samplers) {
        this.staging = staging;
        this.indices = indices;
        this.sampler = samplers.point;
        String vertex = VShaderCompiler.load("infinite:frame/fullscreen.vert");
        this.occlusionProgram = VProgram.graphics("lod_ambient_occlusion", vertex,
                VShaderCompiler.load("infinite:frame/ambient_occlusion.frag"), VDefines.of(depth), 0);
        this.applyProgram = VProgram.graphics("lod_ambient_apply", vertex,
                VShaderCompiler.load("infinite:frame/ambient_apply.frag"), VDefines.of(depth), 0);
        this.occlusion = VGraphicsPipeline.builder(this.occlusionProgram).colorFormats(FORMAT).build();
        this.apply = VGraphicsPipeline.builder(this.applyProgram)
                .blend(VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_SRC_COLOR, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE)
                .colorFormats(LodTargets.COLOUR_FORMAT)
                .build();
    }

    @Nullable Shade shadeFor(CameraState camera, LodTargets targets, float vanillaDistance) {
        float detail = ClientSettings.get().detailPixels;
        float focal = Math.abs(camera.projection.m11()) * targets.height() * 0.5f;
        float twoBlocks = 64 * focal / detail;
        float start = Math.max(twoBlocks, vanillaDistance);
        float end = Math.max(start + 1, twoBlocks * 2);
        if (start >= ClientSettings.get().renderDistanceBlocks()) return null;
        return new Shade(start, end, RADIUS_VOXELS * detail / (32 * focal));
    }

    void draw(VkCommandBuffer cb, CameraState camera, LodTargets targets, Shade shade) {
        int w = (targets.width() + DOWNSCALE - 1) / DOWNSCALE, h = (targets.height() + DOWNSCALE - 1) / DOWNSCALE;
        if (this.image == null || w != this.width || h != this.height) this.resize(cb, w, h);
        this.lodToCamera.set(camera.viewProjection).invert();
        float pixelsPerBlock = Math.abs(camera.projection.m11()) * targets.height() * 0.5f;

        VBarriers.attachmentWrites(cb);
        VRendering.begin(cb, w, h, this.image.view, VK_NULL_HANDLE, false, VRendering.Clear.NONE);
        this.occlusion.bind(cb);
        this.occlusionProgram.texture(T_DEPTH, targets.depthView(), this.sampler)
                .uniformMat4(L_LOD_TO_CAMERA, this.lodToCamera)
                .uniform4f(L_SHAPE, pixelsPerBlock, shade.radiusPerBlock(), LEAST_RADIUS, STRENGTH)
                .uniform2f(L_FADE_IN, shade.start(), shade.end());
        this.occlusion.flush(cb, this.staging);
        vkCmdBindIndexBuffer(cb, this.indices.quads.handle, 0, VK_INDEX_TYPE_UINT16);
        vkCmdDrawIndexed(cb, 6, 1, 0, 0, 0);
        VRendering.end(cb);

        // Ensure occlusion output is visible before sampling, and keep apply separate so depth isn't simultaneously sampled and attached.
        VBarriers.attachmentWrites(cb);
        VRendering.begin(cb, targets.width(), targets.height(), targets.colourView(), VK_NULL_HANDLE, false,
                VRendering.Clear.NONE);
        this.apply.bind(cb);
        this.applyProgram.texture(T_DEPTH, targets.depthView(), this.sampler)
                .texture(T_OCCLUSION, this.image.view, this.sampler)
                .uniformMat4(L_LOD_TO_CAMERA, this.lodToCamera)
                .uniform2f(L_FADE_IN, shade.start(), shade.end());
        this.apply.flush(cb, this.staging);
        vkCmdBindIndexBuffer(cb, this.indices.quads.handle, 0, VK_INDEX_TYPE_UINT16);
        vkCmdDrawIndexed(cb, 6, 1, 0, 0, 0);
        VRendering.end(cb);
        VBarriers.attachmentWrites(cb);
    }

    private void resize(VkCommandBuffer cb, int w, int h) {
        if (this.image != null) this.image.free();
        this.image = VImage.create(cb, "lod_ambient_occlusion", FORMAT, w, h, 1,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        this.width = w;
        this.height = h;
    }

    void free() {
        if (this.image != null) this.image.free();
        this.occlusion.free();
        this.apply.free();
        this.occlusionProgram.free();
        this.applyProgram.free();
    }
}
