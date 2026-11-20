package rodozodo.infinite.client.core.vk;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;


// A compute pipeline whose descriptor set 0 resources are bound with push descriptors and loose uniforms via push constants

public final class VComputePipeline implements Destroyable {
    public enum BindingType {
        STORAGE_BUFFER(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
        UNIFORM_BUFFER(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER),
        COMBINED_IMAGE_SAMPLER(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        STORAGE_IMAGE(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);

        final int vkType;

        BindingType(int vkType) {
            this.vkType = vkType;
        }
    }

    private record Binding(int binding, BindingType type) {}

    public final String name;
    private final long setLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final int pushConstantSize;
    private final Map<Integer, BindingType> bindings;
    private final VkDescriptorBufferInfo.Buffer bufferInfos;
    private final VkWriteDescriptorSet.Buffer writes;
    private final ByteBuffer pushBuf;

    private VComputePipeline(String name, long setLayout, long pipelineLayout, long pipeline, int pushConstantSize, Map<Integer, BindingType> bindings) {
        this.name = name;
        this.setLayout = setLayout;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.pushConstantSize = pushConstantSize;
        this.bindings = bindings;
        int n = bindings.size();
        this.bufferInfos = n > 0 ? VkDescriptorBufferInfo.calloc(n) : null;
        this.writes = n > 0 ? VkWriteDescriptorSet.calloc(n) : null;
        this.pushBuf = pushConstantSize > 0 ? MemoryUtil.memAlloc(pushConstantSize) : null;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public void bind(VkCommandBuffer cb) {
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
    }

    public void pushBuffers(VkCommandBuffer cb, BufferBinding... buffers) {
        for (int i = 0; i < buffers.length; i++) {
            var b = buffers[i];
            this.stageBuffer(i, b.binding, b.buffer, b.offset, b.range);
        }
        this.flushBuffers(cb, buffers.length);
    }

    public void stageBuffer(int i, int binding, VBuffer buffer, long offset, long range) {
        BindingType type = this.bindings.get(binding);
        if (type != BindingType.STORAGE_BUFFER && type != BindingType.UNIFORM_BUFFER) {
            throw new IllegalArgumentException(this.name + ": binding " + binding + " is not a buffer binding");
        }
        long infoAddr = this.bufferInfos.address() + (long) i * VkDescriptorBufferInfo.SIZEOF;
        MemoryUtil.memPutLong(infoAddr + VkDescriptorBufferInfo.BUFFER, buffer.handle);
        MemoryUtil.memPutLong(infoAddr + VkDescriptorBufferInfo.OFFSET, offset);
        MemoryUtil.memPutLong(infoAddr + VkDescriptorBufferInfo.RANGE, range);
        long writeAddr = this.writes.address() + (long) i * VkWriteDescriptorSet.SIZEOF;
        VkWriteDescriptorSet.nsType(writeAddr, VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
        VkWriteDescriptorSet.ndstBinding(writeAddr, binding);
        VkWriteDescriptorSet.ndstArrayElement(writeAddr, 0);
        VkWriteDescriptorSet.ndescriptorCount(writeAddr, 1);
        VkWriteDescriptorSet.ndescriptorType(writeAddr, type.vkType);
        MemoryUtil.memPutAddress(writeAddr + VkWriteDescriptorSet.PBUFFERINFO, infoAddr);
    }

    public void flushBuffers(VkCommandBuffer cb, int count) {
        this.writes.position(0).limit(count);
        vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, this.writes);
    }

    public void pushConstants(VkCommandBuffer cb, ByteBuffer data) {
        if (data.remaining() > this.pushConstantSize) {
            throw new IllegalArgumentException(this.name + ": push constant data larger than declared range");
        }
        vkCmdPushConstants(cb, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, data);
    }

    public void pushConstantInt(VkCommandBuffer cb, int value) {
        if (4 > this.pushConstantSize) {
            throw new IllegalArgumentException(this.name + ": push constant data larger than declared range");
        }
        MemoryUtil.memPutInt(MemoryUtil.memAddress(this.pushBuf), value);
        this.pushBuf.position(0).limit(4);
        vkCmdPushConstants(cb, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, this.pushBuf);
    }

    public void dispatch(VkCommandBuffer cb, int x, int y, int z) {
        vkCmdDispatch(cb, x, y, z);
    }

    public void dispatchIndirect(VkCommandBuffer cb, VBuffer buffer, long offset) {
        vkCmdDispatchIndirect(cb, buffer.handle, offset);
    }

    public void free() {
        VkContext.get().destroyDeferred(this);
    }

    @Override
    public void destroy() {
        var device = VkContext.get().vkDevice;
        vkDestroyPipeline(device, this.pipeline, null);
        vkDestroyPipelineLayout(device, this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, this.setLayout, null);
        if (this.bufferInfos != null) this.bufferInfos.free();
        if (this.writes != null) this.writes.free();
        if (this.pushBuf != null) MemoryUtil.memFree(this.pushBuf);
    }

    public record BufferBinding(int binding, VBuffer buffer, long offset, long range) {
        public static BufferBinding whole(int binding, VBuffer buffer) {
            return new BufferBinding(binding, buffer, 0, VK_WHOLE_SIZE);
        }
    }

    public static final class Builder {
        private final String name;
        private final List<Binding> bindings = new ArrayList<>();
        private final Map<String, String> defines = new HashMap<>();
        private String source;
        private int pushConstantSize;

        private Builder(String name) {
            this.name = name;
        }

        public Builder source(String glsl) {
            this.source = glsl;
            return this;
        }

        public Builder asset(String id) {
            this.source = VShaderCompiler.load(id);
            return this;
        }

        public Builder define(String name, String value) {
            this.defines.put(name, value);
            return this;
        }

        public Builder define(String name, int value) {
            return this.define(name, Integer.toString(value));
        }

        public Builder binding(int binding, BindingType type) {
            this.bindings.add(new Binding(binding, type));
            return this;
        }

        public Builder pushConstants(int size) {
            this.pushConstantSize = size;
            return this;
        }

        public VComputePipeline build() {
            if (this.source == null) throw new IllegalStateException(this.name + ": no shader source");
            var device = VkContext.get().vkDevice;
            long module = VShaderCompiler.compileToModule(this.name, this.source, VShaderCompiler.Stage.COMPUTE, this.defines);
            try (var stack = MemoryStack.stackPush()) {
                var layoutBindings = VkDescriptorSetLayoutBinding.calloc(this.bindings.size(), stack);
                Map<Integer, BindingType> bindingTypes = new HashMap<>();
                for (int i = 0; i < this.bindings.size(); i++) {
                    var b = this.bindings.get(i);
                    if (bindingTypes.put(b.binding, b.type) != null) {
                        throw new IllegalStateException(this.name + ": duplicate binding " + b.binding);
                    }
                    layoutBindings.get(i)
                            .binding(b.binding)
                            .descriptorType(b.type.vkType)
                            .descriptorCount(1)
                            .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                }
                var setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                        .flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                        .pBindings(layoutBindings);
                var pHandle = stack.mallocLong(1);
                VkUtil.check(vkCreateDescriptorSetLayout(device, setLayoutInfo, null, pHandle), "vkCreateDescriptorSetLayout(" + this.name + ")");
                long setLayout = pHandle.get(0);

                var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout));
                if (this.pushConstantSize > 0) {
                    layoutInfo.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                            .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(this.pushConstantSize));
                }
                VkUtil.check(vkCreatePipelineLayout(device, layoutInfo, null, pHandle), "vkCreatePipelineLayout(" + this.name + ")");
                long pipelineLayout = pHandle.get(0);

                var stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                        .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(module)
                        .pName(stack.UTF8("main"));
                var pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                        .stage(stageInfo)
                        .layout(pipelineLayout);
                VkUtil.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pHandle), "vkCreateComputePipelines(" + this.name + ")");
                return new VComputePipeline(this.name, setLayout, pipelineLayout, pHandle.get(0), this.pushConstantSize, Map.copyOf(bindingTypes));
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
        }
    }
}
