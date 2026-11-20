package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.*;
import rodozodo.infinite.client.core.vk.DepthConvention;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;

import static org.lwjgl.vulkan.VK10.*;

// Manages the offscreen LOD color and border fade render targets and seeds them from Minecraft depth buffer
final class LodTargets {
    static final int COLOUR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private static final int BORDER_FORMAT = VK_FORMAT_R32_SFLOAT;

    final int depthStencilFormat;
    private final DepthConvention depth;
    private final StagingBuffer staging;
    private final IndexBuffers indices;
    private final long sampler;
    private final VGraphicsPipeline seed, border;
    private final VRendering.Clear seedClear, passClear;
    private final Matrix4f vanillaToCamera = new Matrix4f();
    private VImage colour, borderImage;
    private VImage depthStencil;
    private long depthOnlyView;
    private int width, height;

    LodTargets(DepthConvention depth, StagingBuffer staging, IndexBuffers indices, Samplers samplers) {
        this.depth = depth;
        this.staging = staging;
        this.indices = indices;
        this.sampler = samplers.point;
        this.depthStencilFormat = pickDepthStencilFormat();
        String vertex = VShaderCompiler.load("infinite:frame/fullscreen.vert");
        String fragment = VShaderCompiler.load("infinite:frame/seed_depth.frag");
        var program = VProgram.graphics("seed_depth_stencil", vertex, fragment, VDefines.of(depth), 0);
        this.seed = VGraphicsPipeline.builder(program)
                .depth(true, true, VK_COMPARE_OP_ALWAYS)
                .stencil(VK_COMPARE_OP_ALWAYS, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_REPLACE, VK_STENCIL_OP_KEEP, 0xFF, 0xFF)
                .colorFormats(BORDER_FORMAT)
                .colorWriteMask(0)
                .depthFormat(this.depthStencilFormat, true)
                .build();
        var borderProgram = VProgram.graphics("seed_border", vertex, fragment, VDefines.of(depth).define("BORDER"), 0);
        this.border = VGraphicsPipeline.builder(borderProgram)
                .colorFormats(BORDER_FORMAT)
                .depthFormat(this.depthStencilFormat, true)
                .build();
        this.seedClear = new VRendering.Clear(new float[]{0, 0, 0, 0}, this.depth.far(), 1);
        this.passClear = new VRendering.Clear(new float[]{0, 0, 0, 0}, null, null);
    }

    private static int pickDepthStencilFormat() {
        int needed = VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties.calloc(stack);
            for (int format : new int[]{VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT}) {
                vkGetPhysicalDeviceFormatProperties(VkContext.get().vkDevice.getPhysicalDevice(), format, properties);
                if ((properties.optimalTilingFeatures() & needed) == needed) return format;
            }
        }
        throw new IllegalStateException("The GPU has no depth/stencil format Infinite can render to and sample");
    }

    void prepare(VkCommandBuffer cb, CameraState camera, long minecraftDepth, int minecraftWidth, int minecraftHeight,
                 float cloudBase, float cloudTop, float borderStart, float borderEnd) {
        if (this.colour == null || this.width != camera.width || this.height != camera.height) {
            this.resize(cb, camera.width, camera.height);
        }
        VRendering.begin(cb, this.width, this.height, this.borderImage.view, this.depthStencil.view, true, this.seedClear);
        vkCmdBindIndexBuffer(cb, this.indices.quads.handle, 0, VK_INDEX_TYPE_UINT16);
        this.vanillaToCamera.set(camera.vanillaProjection).mul(camera.modelView).invert();
        float widthScale = (float) this.width / minecraftWidth, heightScale = (float) this.height / minecraftHeight;
        float relCloudBase = (float) (cloudBase - camera.y), relCloudTop = (float) (cloudTop - camera.y);
        this.seed.bind(cb);
        this.seed.program.texture(0, minecraftDepth, this.sampler)
                .uniform2f(0, widthScale, heightScale)
                .uniformMat4(1, this.vanillaToCamera)
                .uniform2f(2, relCloudBase, relCloudTop)
                .uniform2f(3, borderStart, borderEnd);
        this.seed.flush(cb, this.staging);
        vkCmdSetStencilReference(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 0);
        vkCmdDrawIndexed(cb, 6, 1, 0, 0, 0);
        if (borderStart < borderEnd) {
            this.border.bind(cb);
            this.border.program.texture(0, minecraftDepth, this.sampler)
                    .uniform2f(0, widthScale, heightScale)
                    .uniformMat4(1, this.vanillaToCamera)
                    .uniform2f(2, relCloudBase, relCloudTop)
                    .uniform2f(3, borderStart, borderEnd);
            this.border.flush(cb, this.staging);
            vkCmdDrawIndexed(cb, 6, 1, 0, 0, 0);
        }
        VRendering.end(cb);
        VBarriers.attachmentWrites(cb);
    }

    private void resize(VkCommandBuffer cb, int w, int h) {
        if (this.colour != null) {
            this.colour.free();
            this.depthStencil.free();
            this.borderImage.free();
        }
        this.width = w;
        this.height = h;
        this.colour = VImage.create(cb, "lod_colour", COLOUR_FORMAT, w, h, 1,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        this.depthStencil = VImage.create(cb, "lod_depth_stencil", this.depthStencilFormat, w, h, 1,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT);
        this.depthOnlyView = this.depthStencil.createView(0, 1, VK_IMAGE_ASPECT_DEPTH_BIT);
        this.borderImage = VImage.create(cb, "lod_border", BORDER_FORMAT, w, h, 1,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
    }

    void beginPass(VkCommandBuffer cb, boolean clearColour) {
        VRendering.begin(cb, this.width, this.height, this.colour.view, this.depthStencil.view, true,
                clearColour ? this.passClear : VRendering.Clear.NONE);
    }

    long colourView() {
        return this.colour.view;
    }

    long depthStencilView() {
        return this.depthStencil.view;
    }

    long depthView() {
        return this.depthOnlyView;
    }

    long borderView() {
        return this.borderImage.view;
    }

    int width() {
        return this.width;
    }

    int height() {
        return this.height;
    }

    void free() {
        if (this.colour != null) {
            this.colour.free();
            this.depthStencil.free();
            this.borderImage.free();
        }
        this.seed.program.free();
        this.seed.free();
        this.border.program.free();
        this.border.free();
    }
}
