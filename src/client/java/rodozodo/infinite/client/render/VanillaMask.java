package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.*;
import rodozodo.infinite.client.core.vk.DepthConvention;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

final class VanillaMask {
    private static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;
    static final int SHIFT = 0;
    static final int SCALE = 1 << SHIFT;

    private final DepthConvention depth;
    private final StagingBuffer staging;
    private final IndexBuffers indices;
    private final VGraphicsPipeline boxes;
    private final VBuffer uniform;
    private final VRendering.Clear clear;
    private final org.joml.Matrix4f steadyMatrix = new org.joml.Matrix4f();
    private VBuffer sectionPositions;
    private VImage target;

    VanillaMask(DepthConvention depth, StagingBuffer staging, IndexBuffers indices) {
        this.depth = depth;
        this.staging = staging;
        this.indices = indices;
        this.clear = new VRendering.Clear(null, depth.near(), null);
        this.uniform = VBuffer.create("vanilla_mask_uniform", 128, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VBuffer.Access.DEVICE_LOCAL);
        var program = VProgram.graphics("vanilla_mask", VShaderCompiler.load("infinite:mask/vanilla_mask.vert"),
                VShaderCompiler.load("infinite:mask/vanilla_mask.frag"), VDefines.of(depth), 0);

        // Cull near faces so only each box's farthest depth per pixel is recorded.
        this.boxes = VGraphicsPipeline.builder(program)
                .cull(VK_CULL_MODE_BACK_BIT).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .depth(true, true, depth.reverseZ() ? VK_COMPARE_OP_LESS : VK_COMPARE_OP_GREATER)
                .depthFormat(DEPTH_FORMAT, false)
                .build();
    }

    long view() {
        return this.target.view;
    }

    void render(VkCommandBuffer cb, CameraState camera, VisibleVanillaSections sections) {
        int width = maskSize(camera.width), height = maskSize(camera.height);
        if (this.target == null || this.target.width != width || this.target.height != height) {
            if (this.target != null) this.target.free();
            this.target = VImage.create(cb, "vanilla_mask", DEPTH_FORMAT, width, height, 1,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
        }
        int count = sections.count();
        if (count > 0) {
            this.uploadSections(sections, count);
            this.uploadUniform(camera, width, height);
            this.staging.recordCopies(cb);
        }

        VRendering.begin(cb, width, height, VK_NULL_HANDLE, this.target.view, false, this.clear);
        if (count > 0) {
            this.boxes.bind(cb);
            this.boxes.program.ubo(0, this.uniform).ssbo(0, this.sectionPositions);
            this.boxes.flush(cb, this.staging);
            vkCmdBindIndexBuffer(cb, this.indices.boxBatch.handle, 0, VK_INDEX_TYPE_UINT16);
            int batch = IndexBuffers.BOXES_PER_BATCH;
            int fullBatches = count / batch, rest = count % batch;
            if (fullBatches > 0) {
                vkCmdDrawIndexed(cb, IndexBuffers.BOX_INDICES * batch, fullBatches, 0, 0, 0);
            }
            if (rest > 0) {
                vkCmdDrawIndexed(cb, IndexBuffers.BOX_INDICES * rest, 1, 0, 0, fullBatches);
            }
        }
        VRendering.end(cb);
    }

    private void uploadSections(VisibleVanillaSections sections, int count) {
        boolean resized = false;
        long bytes = count * 8L;
        if (this.sectionPositions == null || this.sectionPositions.size < bytes) {
            if (this.sectionPositions != null) this.sectionPositions.free();
            this.sectionPositions = VBuffer.create("vanilla_mask_sections", bytes + (bytes >> 2) + 64,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VBuffer.Access.DEVICE_LOCAL);
            resized = true;
        }
        if (sections.takeChanged() || resized) {
            long dst = this.staging.write(this.sectionPositions, 0, bytes);
            MemoryUtil.memLongBuffer(dst, count).put(sections.positions(), 0, count);
        }
    }

    private void uploadUniform(CameraState camera, int width, int height) {
        int bx = (int) Math.floor(camera.x), by = (int) Math.floor(camera.y), bz = (int) Math.floor(camera.z);
        float ox = (float) (camera.x - bx), oy = (float) (camera.y - by), oz = (float) (camera.z - bz);
        new UniformWriter(this.staging.write(this.uniform, 0, 128), 128)
                // Use unjittered view-projection to match Minecraft's pixel centers tested in terrain.frag.
                .mat4(camera.steadyViewProjection.translate(-ox, -oy, -oz, this.steadyMatrix))
                .ivec3(bx, by, bz)
                .vec4(ox, oy, oz, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16)
                .vec2(SHIFT == 0 ? 0f : 1f / width, SHIFT == 0 ? 0f : 1f / height);
    }

    private static int maskSize(int pixels) {
        return Math.max(1, (pixels + SCALE - 1) / SCALE);
    }

    void free() {
        this.boxes.program.free();
        this.boxes.free();
        this.uniform.free();
        if (this.sectionPositions != null) this.sectionPositions.free();
        if (this.target != null) this.target.free();
    }
}
