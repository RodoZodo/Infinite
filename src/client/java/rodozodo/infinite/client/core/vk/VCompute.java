package rodozodo.infinite.client.core.vk;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import rodozodo.infinite.Log;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPipelineExecutableProperties;
import org.lwjgl.vulkan.VkPipelineExecutableInfoKHR;
import org.lwjgl.vulkan.VkPipelineExecutablePropertiesKHR;
import org.lwjgl.vulkan.VkPipelineExecutableStatisticKHR;
import org.lwjgl.vulkan.VkPipelineInfoKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;

import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;


// A compute pipeline over a GL compat {@link VProgram} (bindings by GL binding number and uniforms by GL location)

public final class VCompute implements Destroyable {
    public final VProgram program;
    private final long pipeline;

    private VCompute(VProgram program) {
        this.program = program;
        try (var stack = MemoryStack.stackPush()) {
            var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(program.module(VProgram.Stage.COMPUTE)).pName(stack.UTF8("main"));
            var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().stage(stage).layout(program.pipelineLayout);
            boolean stats = VkContext.get().hasFeature(InfiniteVkFeatures.PIPELINE_EXECUTABLE_INFO);
            if (stats) info.flags(KHRPipelineExecutableProperties.VK_PIPELINE_CREATE_CAPTURE_STATISTICS_BIT_KHR);
            var p = stack.mallocLong(1);
            VkUtil.check(vkCreateComputePipelines(VkContext.get().vkDevice, VkContext.get().pipelineCache, info, null, p), "vkCreateComputePipelines(" + program.name + ")");
            this.pipeline = p.get(0);
            if (stats) logStatistics(this.pipeline, program.name);
        }
    }

    private static void logStatistics(long pipeline, String name) {
        var device = VkContext.get().vkDevice;
        try (var stack = MemoryStack.stackPush()) {
            var pipelineInfo = VkPipelineInfoKHR.calloc(stack).sType$Default().pipeline(pipeline);
            var count = stack.mallocInt(1);
            KHRPipelineExecutableProperties.vkGetPipelineExecutablePropertiesKHR(device, pipelineInfo, count, null);
            var executables = VkPipelineExecutablePropertiesKHR.calloc(count.get(0), stack);
            for (var executable : executables) executable.sType$Default();
            KHRPipelineExecutableProperties.vkGetPipelineExecutablePropertiesKHR(device, pipelineInfo, count, executables);
            for (int i = 0; i < executables.capacity(); i++) {
                var executableInfo = VkPipelineExecutableInfoKHR.calloc(stack).sType$Default().pipeline(pipeline).executableIndex(i);
                var statCount = stack.mallocInt(1);
                KHRPipelineExecutableProperties.vkGetPipelineExecutableStatisticsKHR(device, executableInfo, statCount, null);
                var statistics = VkPipelineExecutableStatisticKHR.calloc(statCount.get(0), stack);
                for (var statistic : statistics) statistic.sType$Default();
                KHRPipelineExecutableProperties.vkGetPipelineExecutableStatisticsKHR(device, executableInfo, statCount, statistics);
                var line = new StringBuilder("Pipeline statistics: ").append(name).append(" [")
                        .append(executables.get(i).nameString()).append(", subgroup ").append(executables.get(i).subgroupSize()).append("]");
                for (var statistic : statistics) {
                    var value = statistic.value();
                    line.append(" · ").append(statistic.nameString()).append(' ').append(switch (statistic.format()) {
                        case KHRPipelineExecutableProperties.VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_BOOL32_KHR -> String.valueOf(value.b32());
                        case KHRPipelineExecutableProperties.VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_INT64_KHR -> String.valueOf(value.i64());
                        case KHRPipelineExecutableProperties.VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_UINT64_KHR -> Long.toUnsignedString(value.u64());
                        default -> String.valueOf(value.f64());
                    });
                }
                Log.info(line.toString());
            }
        }
    }

    public static VCompute create(String name, String source, Map<String, String> defines) {
        return new VCompute(VProgram.compute(name, source, defines));
    }

    public static VCompute create(String name, String source, Map<String, String> defines, long externalSetLayout) {
        return new VCompute(VProgram.compute(name, source, defines, externalSetLayout));
    }

    public static VCompute asset(String id, Map<String, String> defines) {
        return create(id, VShaderCompiler.load(id), defines);
    }

    public VCompute bind(VkCommandBuffer cb, StagingBuffer staging) {
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
        this.program.flush(cb, VK_PIPELINE_BIND_POINT_COMPUTE, staging);
        return this;
    }

    public VCompute push(VkCommandBuffer cb, StagingBuffer staging) {
        this.program.flush(cb, VK_PIPELINE_BIND_POINT_COMPUTE, staging);
        return this;
    }

    public void dispatch(VkCommandBuffer cb, int x, int y, int z) {
        if (x != 0 && y != 0 && z != 0) vkCmdDispatch(cb, x, y, z);
    }

    public void dispatchIndirect(VkCommandBuffer cb, VBuffer buffer, long offset) {
        vkCmdDispatchIndirect(cb, buffer.handle, offset);
    }

    public void free() {
        this.program.free();
        VkContext.get().destroyDeferred(this);
    }

    @Override
    public void destroy() {
        vkDestroyPipeline(VkContext.get().vkDevice, this.pipeline, null);
    }
}
