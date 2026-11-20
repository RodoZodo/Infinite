package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.StagingBuffer;
import rodozodo.infinite.client.core.vk.VBarriers;
import rodozodo.infinite.client.core.vk.VBuffer;
import rodozodo.infinite.client.core.vk.VImage;
import rodozodo.infinite.client.core.vk.VkContext;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;

final class SetupCommands {
    private final List<Consumer<VkCommandBuffer>> commands = new ArrayList<>();

    void fill(VBuffer buffer, int value) {
        this.commands.add(cb -> vkCmdFillBuffer(cb, buffer.handle, 0, VK_WHOLE_SIZE, value));
    }

    void initialize(VImage image) {
        this.commands.add(image::initialize);
    }

    void submit(VkContext ctx, StagingBuffer staging) {
        var cb = ctx.beginFrameCommands();
        for (var command : this.commands) command.accept(cb);
        this.commands.clear();
        VBarriers.full(cb);
        staging.recordCopies(cb);
        ctx.executeInFrame(cb);
        staging.endFrame();
    }
}
