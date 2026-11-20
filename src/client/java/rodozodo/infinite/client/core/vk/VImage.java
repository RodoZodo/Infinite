package rodozodo.infinite.client.core.vk;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;


// A 2D image (with a view over all mips) allocated through Minecrafts VMA allocator
// Like Minecraft's own images it lives permanently in VK_IMAGE_LAYOUT_GENERAL, so it can be sampled, stored to and copied without layout tracking

public final class VImage implements Destroyable {
    private static final LongAdder LIVE_BYTES = new LongAdder();
    private static final Map<String, LongAdder> BYTES_BY_NAME = new ConcurrentHashMap<>();

    public final long image;
    public final long view;
    public final int format;
    public final int width;
    public final int height;
    public final int mipLevels;
    public final int aspectMask;
    private final long allocation;
    private final long bytes;
    private final String kind;
    private boolean destroyed;

    private VImage(String name, long image, long view, long allocation, long bytes, int format, int width, int height, int mipLevels, int aspectMask) {
        this.bytes = bytes;
        this.kind = name.replaceAll("_?\\d+$", "");
        LIVE_BYTES.add(bytes);
        BYTES_BY_NAME.computeIfAbsent(this.kind, k -> new LongAdder()).add(bytes);
        this.image = image;
        this.view = view;
        this.allocation = allocation;
        this.format = format;
        this.width = width;
        this.height = height;
        this.mipLevels = mipLevels;
        this.aspectMask = aspectMask;
    }

    public static VImage create(VkCommandBuffer cb, String name, int format, int width, int height, int mipLevels, int usage, int aspectMask) {
        var image = allocate(name, format, width, height, mipLevels, usage, aspectMask);
        image.initialize(cb);
        return image;
    }

    public static VImage allocate(String name, int format, int width, int height, int mipLevels, int usage, int aspectMask) {
        var ctx = VkContext.get();
        try (var stack = MemoryStack.stackPush()) {
            var info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(mipLevels)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            var allocInfo = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            var pImage = stack.mallocLong(1);
            var pAllocation = stack.mallocPointer(1);
            VkUtil.check(vmaCreateImage(ctx.vma, info, allocInfo, pImage, pAllocation, null), "vmaCreateImage(" + name + ")");
            long image = pImage.get(0);
            long allocation = pAllocation.get(0);
            vmaSetAllocationName(ctx.vma, allocation, stack.UTF8("infinite:" + name));

            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewInfo.subresourceRange().set(aspectMask, 0, mipLevels, 0, 1);
            var pView = stack.mallocLong(1);
            VkUtil.check(vkCreateImageView(ctx.vkDevice, viewInfo, null, pView), "vkCreateImageView(" + name + ")");
            var allocated = VmaAllocationInfo.calloc(stack);
            vmaGetAllocationInfo(ctx.vma, allocation, allocated);

            return new VImage(name, image, pView.get(0), allocation, allocated.size(), format, width, height, mipLevels, aspectMask);
        }
    }

    public void initialize(VkCommandBuffer cb) {
        try (var stack = MemoryStack.stackPush()) {
            var range = VkImageSubresourceRange.calloc(stack).set(this.aspectMask, 0, this.mipLevels, 0, 1);
            var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(this.image)
                    .subresourceRange(range);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
            if ((this.aspectMask & VK_IMAGE_ASPECT_COLOR_BIT) != 0) {
                vkCmdClearColorImage(cb, this.image, VK_IMAGE_LAYOUT_GENERAL, VkClearColorValue.calloc(stack), range);
            }
        }
    }

    private final java.util.List<Long> extraViews = new java.util.ArrayList<>();

    public long createView(int baseMip, int levels, int aspect) {
        try (var stack = MemoryStack.stackPush()) {
            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(this.image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(this.format);
            viewInfo.subresourceRange().set(aspect, baseMip, levels, 0, 1);
            var pView = stack.mallocLong(1);
            VkUtil.check(vkCreateImageView(VkContext.get().vkDevice, viewInfo, null, pView), "vkCreateImageView");
            this.extraViews.add(pView.get(0));
            return pView.get(0);
        }
    }

    public void free() {
        VkContext.get().destroyDeferred(this);
    }

    public static long liveBytes() {
        return LIVE_BYTES.sum();
    }

    public static String largest(int count) {
        var line = new StringBuilder();
        BYTES_BY_NAME.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(count)
                .forEach(e -> line.append(line.isEmpty() ? "" : " · ").append(e.getKey()).append(' ').append(e.getValue() >> 20));
        return line.toString();
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        LIVE_BYTES.add(-this.bytes);
        BYTES_BY_NAME.get(this.kind).add(-this.bytes);
        var ctx = VkContext.get();
        for (long view : this.extraViews) vkDestroyImageView(ctx.vkDevice, view, null);
        vkDestroyImageView(ctx.vkDevice, this.view, null);
        vmaDestroyImage(ctx.vma, this.image, this.allocation);
    }
}
