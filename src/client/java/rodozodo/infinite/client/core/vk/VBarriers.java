package rodozodo.infinite.client.core.vk;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK13.*;


// Helpers for recording Vulkan synchronization2 global memory barriers

public final class VBarriers {
    private static final class Scratch {
        final VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1).sType$Default();
        final VkDependencyInfo dependency = VkDependencyInfo.calloc().sType$Default().pMemoryBarriers(this.barrier);
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final VkDependencyInfo FULL = fixed(
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);

    private static final VkDependencyInfo ATTACHMENT_WRITES = fixed(
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                    | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
            VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                    | VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
            VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                    | VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                    | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

    private static final VkDependencyInfo COMPUTE_TO_COMPUTE = fixed(
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

    private static final VkDependencyInfo COMPUTE_WRITE_TO_SAMPLED = fixed(
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

    private static final VkDependencyInfo COMPUTE_TO_INDIRECT = fixed(
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);

    private static final VkDependencyInfo COMPUTE_TO_TRANSFER = fixed(
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);

    private static final VkDependencyInfo TRANSFER_TO_COMPUTE = fixed(
            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

    private static final VkDependencyInfo TRANSFER_TO_HOST = fixed(
            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_READ_BIT);

    private static VkDependencyInfo fixed(long srcStage, long srcAccess, long dstStage, long dstAccess) {
        var barrier = VkMemoryBarrier2.calloc(1).sType$Default()
                .srcStageMask(srcStage)
                .srcAccessMask(srcAccess)
                .dstStageMask(dstStage)
                .dstAccessMask(dstAccess);
        return VkDependencyInfo.calloc().sType$Default().pMemoryBarriers(barrier);
    }

    private VBarriers() {}

    public static void memory(VkCommandBuffer cb, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        Scratch s = SCRATCH.get();
        s.barrier.get(0)
                .srcStageMask(srcStage)
                .srcAccessMask(srcAccess)
                .dstStageMask(dstStage)
                .dstAccessMask(dstAccess);
        vkCmdPipelineBarrier2KHR(cb, s.dependency);
    }

    public static void full(VkCommandBuffer cb) {
        vkCmdPipelineBarrier2KHR(cb, FULL);
    }

    public static void attachmentWrites(VkCommandBuffer cb) {
        vkCmdPipelineBarrier2KHR(cb, ATTACHMENT_WRITES);
    }

    public static void computeToCompute(VkCommandBuffer cb) {
        vkCmdPipelineBarrier2KHR(cb, COMPUTE_TO_COMPUTE);
    }

    public static void computeWriteToSampled(VkCommandBuffer cb) {
        vkCmdPipelineBarrier2KHR(cb, COMPUTE_WRITE_TO_SAMPLED);
    }

    public static void computeToIndirect(VkCommandBuffer cb) {
        vkCmdPipelineBarrier2KHR(cb, COMPUTE_TO_INDIRECT);
    }

    public static void computeToTransfer(VkCommandBuffer cb) {
        vkCmdPipelineBarrier2KHR(cb, COMPUTE_TO_TRANSFER);
    }

    public static void transferToCompute(VkCommandBuffer cb) {
        vkCmdPipelineBarrier2KHR(cb, TRANSFER_TO_COMPUTE);
    }

    public static void transferToHost(VkCommandBuffer cb) {
        vkCmdPipelineBarrier2KHR(cb, TRANSFER_TO_HOST);
    }
}
