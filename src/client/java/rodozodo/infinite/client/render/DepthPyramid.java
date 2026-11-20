package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.*;
import rodozodo.infinite.client.core.vk.DepthConvention;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

//Builds a mip chain of farthest depth texels from the LOD depth buffer for GPU occlusion culling
final class DepthPyramid {
    private static final int GROUP = 8;

    private final VCompute reduce;
    private final StagingBuffer staging;
    private final long sampler;
    private VImage image;
    private long[] levelViews = new long[0];
    private int width, height;

    DepthPyramid(DepthConvention depth, StagingBuffer staging, Samplers samplers) {
        this.staging = staging;
        this.sampler = samplers.pointMipmapped;
        this.reduce = VCompute.asset("infinite:frame/depth_pyramid.comp", VDefines.of(depth));
    }

    void build(VkCommandBuffer cb, long depthView, int depthWidth, int depthHeight) {
        int w = Integer.highestOneBit(depthWidth), h = Integer.highestOneBit(depthHeight);
        if (this.image == null || w != this.width || h != this.height) {
            this.resize(cb, w, h);
        }
        // Synchronize prior LOD depth writes and previous frames pyramid reads before reducing level 0
        VBarriers.memory(cb,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        long source = depthView;
        int sw = depthWidth, sh = depthHeight;
        int lw = w, lh = h;
        boolean first = true;
        for (long target : this.levelViews) {
            this.reduce.program
                    .texture(0, source, this.sampler)
                    .image(1, target)
                    .uniform2i(0, lw, lh)
                    .uniform2i(1, sw, sh);
            if (first) {
                this.reduce.bind(cb, this.staging);
                first = false;
            } else {
                this.reduce.push(cb, this.staging);
            }
            this.reduce.dispatch(cb, (lw + GROUP - 1) / GROUP, (lh + GROUP - 1) / GROUP, 1);
            VBarriers.computeWriteToSampled(cb);
            source = target;
            sw = lw;
            sh = lh;
            lw = Math.max(1, lw >> 1);
            lh = Math.max(1, lh >> 1);
        }
    }

    private void resize(VkCommandBuffer cb, int w, int h) {
        if (this.image != null) this.image.free();
        int levels = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(w, h) - 1));
        this.image = VImage.create(cb, "depth_pyramid", VK_FORMAT_R32_SFLOAT, w, h, levels,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        this.levelViews = new long[levels];
        for (int level = 0; level < levels; level++) {
            this.levelViews[level] = this.image.createView(level, 1, VK_IMAGE_ASPECT_COLOR_BIT);
        }
        this.width = w;
        this.height = h;
    }

    long view() {
        return this.image.view;
    }

    long sampler() {
        return this.sampler;
    }

    int packedSize() {
        return (this.width << 16) | this.height;
    }

    void free() {
        if (this.image != null) this.image.free();
        this.reduce.free();
    }
}
