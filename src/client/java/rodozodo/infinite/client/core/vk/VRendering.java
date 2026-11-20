package rodozodo.infinite.client.core.vk;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;

// Dynamic rendering passes on images kept in VK_IMAGE_LAYOUT_GENERAL (Infinite and Minecraft)

public final class VRendering {
    private static final class Scratch {
        final VkRenderingAttachmentInfo.Buffer colours = VkRenderingAttachmentInfo.calloc(4);
        final VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc().sType$Default();
        final VkRenderingAttachmentInfo stencil = VkRenderingAttachmentInfo.calloc().sType$Default();
        final VkRenderingInfo info = VkRenderingInfo.calloc().sType$Default();
        final VkViewport.Buffer viewport = VkViewport.calloc(1).minDepth(0).maxDepth(1);
        final VkRect2D.Buffer scissor = VkRect2D.calloc(1);
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private VRendering() {}

    public record Clear(@Nullable float[] colour, @Nullable Float depth, @Nullable Integer stencil) {
        public static final Clear NONE = new Clear(null, null, null);
    }

    public static void begin(VkCommandBuffer cb, int width, int height, long colourView, long depthView, boolean depthHasStencil, Clear clear) {
        Scratch s = SCRATCH.get();
        s.colours.clear();
        if (colourView != VK_NULL_HANDLE) {
            fillColourAttachment(s.colours.get(0), colourView, clear);
            s.colours.limit(1);
        } else {
            s.colours.limit(0);
        }
        beginWithScratch(cb, width, height, s, s.colours, depthView, depthHasStencil, clear);
    }

    public static void begin(VkCommandBuffer cb, int width, int height, long colourView0, long colourView1, long depthView,
                             boolean depthHasStencil, Clear clear) {
        Scratch s = SCRATCH.get();
        s.colours.clear();
        fillColourAttachment(s.colours.get(0), colourView0, clear);
        fillColourAttachment(s.colours.get(1), colourView1, clear);
        s.colours.limit(2);
        beginWithScratch(cb, width, height, s, s.colours, depthView, depthHasStencil, clear);
    }

    public static void begin(VkCommandBuffer cb, int width, int height, long[] colourViews, long depthView, boolean depthHasStencil, Clear clear) {
        if (colourViews.length == 0) {
            begin(cb, width, height, VK_NULL_HANDLE, depthView, depthHasStencil, clear);
            return;
        }
        if (colourViews.length == 1) {
            begin(cb, width, height, colourViews[0], depthView, depthHasStencil, clear);
            return;
        }
        if (colourViews.length == 2) {
            begin(cb, width, height, colourViews[0], colourViews[1], depthView, depthHasStencil, clear);
            return;
        }
        Scratch s = SCRATCH.get();
        if (colourViews.length <= s.colours.capacity()) {
            s.colours.clear();
            for (int i = 0; i < colourViews.length; i++) fillColourAttachment(s.colours.get(i), colourViews[i], clear);
            s.colours.limit(colourViews.length);
            beginWithScratch(cb, width, height, s, s.colours, depthView, depthHasStencil, clear);
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            var colours = VkRenderingAttachmentInfo.calloc(colourViews.length, stack);
            for (int i = 0; i < colourViews.length; i++) fillColourAttachment(colours.get(i), colourViews[i], clear);
            beginWithScratch(cb, width, height, s, colours, depthView, depthHasStencil, clear);
        }
    }

    private static void fillColourAttachment(VkRenderingAttachmentInfo a, long view, Clear clear) {
        a.sType$Default()
                .imageView(view)
                .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(clear.colour != null ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        if (clear.colour != null) {
            a.clearValue().color().float32(0, clear.colour[0]).float32(1, clear.colour[1]).float32(2, clear.colour[2]).float32(3, clear.colour[3]);
        }
    }

    private static void beginWithScratch(VkCommandBuffer cb, int width, int height, Scratch s,
                                         VkRenderingAttachmentInfo.Buffer colours, long depthView, boolean depthHasStencil, Clear clear) {
        var info = s.info.layerCount(1).pColorAttachments(colours);
        info.renderArea().extent().set(width, height);
        if (depthView != VK_NULL_HANDLE) {
            float d = clear.depth != null ? clear.depth : 0;
            int st = clear.stencil != null ? clear.stencil : 0;
            var depth = s.depth
                    .imageView(depthView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(clear.depth != null ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            depth.clearValue().depthStencil().depth(d).stencil(st);
            info.pDepthAttachment(depth);
            if (depthHasStencil) {
                var stencil = s.stencil
                        .imageView(depthView)
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .loadOp(clear.stencil != null ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                stencil.clearValue().depthStencil().depth(d).stencil(st);
                info.pStencilAttachment(stencil);
            } else {
                info.pStencilAttachment(null);
            }
        } else {
            info.pDepthAttachment(null);
            info.pStencilAttachment(null);
        }
        vkCmdBeginRenderingKHR(cb, info);
        setViewport(cb, 0, 0, width, height);
    }

    public static void setViewport(VkCommandBuffer cb, int x, int y, int width, int height) {
        Scratch s = SCRATCH.get();
        s.viewport.get(0).x(x).y(y).width(width).height(height).minDepth(0).maxDepth(1);
        vkCmdSetViewport(cb, 0, s.viewport);
        s.scissor.get(0).offset().set(x, y);
        s.scissor.get(0).extent().set(width, height);
        vkCmdSetScissor(cb, 0, s.scissor);
    }

    public static void end(VkCommandBuffer cb) {
        vkCmdEndRenderingKHR(cb);
    }
}
