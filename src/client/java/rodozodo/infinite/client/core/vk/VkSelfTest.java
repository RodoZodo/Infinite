package rodozodo.infinite.client.core.vk;

import rodozodo.infinite.Log;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;

import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;


// Startup compute self test verifying shader compilation, command submission, readback, and 64-bit integer math

public final class VkSelfTest {
    private static final int COUNT = 4096;
    private static final int MULTIPLIER = 3;
    private static final int WORKGROUP = 64;
    private static final long FENCE_TIMEOUT = 5_000_000_000L;

    private static final String SHADER = """
            #version 460
            #ifdef USE_INT64
            #extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
            #endif
            layout(local_size_x = WORKGROUP) in;
            layout(set = 0, binding = 0, std430) restrict writeonly buffer Output { uint data[]; };
            layout(push_constant) uniform Params { uint count; uint multiplier; } params;
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= params.count) return;
            #ifdef USE_INT64
                // Bit 33 must be shifted out of the 32-bit result; this only holds if 64-bit math really happened
                uint64_t v = uint64_t(i) * uint64_t(params.multiplier) + (uint64_t(1) << 33);
                data[i] = uint(v >> 1);
            #else
                data[i] = (i * params.multiplier) >> 1;
            #endif
            }
            """;

    public record Result(boolean passed, String message) {}

    private VkSelfTest() {}

    public static Result run(VkContext ctx) {
        long start = System.nanoTime();
        boolean int64 = ctx.hasFeature(InfiniteVkFeatures.SHADER_INT64);
        VComputePipeline pipeline = null;
        VBuffer output = null;
        VBuffer readback = null;
        try {
            pipeline = VComputePipeline.builder("selftest")
                    .source(SHADER)
                    .define("WORKGROUP", WORKGROUP)
                    .define(int64 ? "USE_INT64" : "NO_INT64", "1")
                    .binding(0, VComputePipeline.BindingType.STORAGE_BUFFER)
                    .pushConstants(8)
                    .build();
            long bytes = COUNT * 4L;
            output = VBuffer.create("selftest_output", bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VBuffer.Access.DEVICE_LOCAL);
            readback = VBuffer.create("selftest_readback", bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VBuffer.Access.HOST_READBACK);
            MemoryUtil.memSet(readback.mappedAddress(), 0, bytes);
            readback.flush(0, bytes);

            var cb = ctx.beginFrameCommands();
            VBarriers.full(cb);
            pipeline.bind(cb);
            pipeline.pushBuffers(cb, VComputePipeline.BufferBinding.whole(0, output));
            try (var stack = MemoryStack.stackPush()) {
                pipeline.pushConstants(cb, stack.malloc(8).putInt(0, COUNT).putInt(4, MULTIPLIER));
                pipeline.dispatch(cb, (COUNT + WORKGROUP - 1) / WORKGROUP, 1, 1);
                VBarriers.computeToTransfer(cb);
                vkCmdCopyBuffer(cb, output.handle, readback.handle, VkBufferCopy.calloc(1, stack).size(bytes));
            }
            VBarriers.transferToHost(cb);
            VBarriers.full(cb);
            ctx.executeInFrame(cb);

            var fence = ctx.createFrameFence();
            ctx.flushSubmit();
            if (!fence.awaitCompletion(FENCE_TIMEOUT)) {
                return new Result(false, "timed out waiting for the GPU");
            }
            readback.invalidate(0, bytes);

            long base = readback.mappedAddress();
            for (int i = 0; i < COUNT; i++) {
                int expected = (i * MULTIPLIER) >>> 1;
                int actual = MemoryUtil.memGetInt(base + i * 4L);
                if (actual != expected) {
                    return new Result(false, "mismatch at index " + i + ": expected " + expected + ", got " + actual + (int64 ? " (int64 path)" : ""));
                }
            }
            return new Result(true, String.format("%d values verified (%s) in %.1f ms",
                    COUNT, int64 ? "int64 path" : "32-bit path", (System.nanoTime() - start) / 1e6));
        } catch (Throwable t) {
            Log.error("Infinite Vulkan self-test threw", t);
            return new Result(false, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (pipeline != null) pipeline.free();
            if (output != null) output.free();
            if (readback != null) readback.free();
        }
    }
}
