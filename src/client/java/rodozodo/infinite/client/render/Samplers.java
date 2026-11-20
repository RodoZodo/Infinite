package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.VkContext;
import rodozodo.infinite.client.core.vk.VkUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.vulkan.VK10.*;


final class Samplers {
    final long point;
    final long bilinear;
    final long pointMipmapped;
    final long blockAtlas;
    final long skin;

    Samplers() {
        this.point = create(VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST, 0);
        this.bilinear = create(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, 0);
        this.pointMipmapped = create(VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_LOD_CLAMP_NONE);
        this.blockAtlas = create(VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_LINEAR, VK_LOD_CLAMP_NONE);
        this.skin = create(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_LINEAR, VK_LOD_CLAMP_NONE);
    }

    private static long create(int filter, int mipmapMode, float maxLod) {
        try (var stack = MemoryStack.stackPush()) {
            var info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter).mipmapMode(mipmapMode)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0).maxLod(maxLod);
            var handle = stack.mallocLong(1);
            VkUtil.check(vkCreateSampler(VkContext.get().vkDevice, info, null, handle), "vkCreateSampler");
            return handle.get(0);
        }
    }

    void free() {
        long[] all = {this.point, this.bilinear, this.pointMipmapped, this.blockAtlas, this.skin};
        VkContext.get().destroyDeferred(() -> {
            for (long sampler : all) vkDestroySampler(VkContext.get().vkDevice, sampler, null);
        });
    }
}
