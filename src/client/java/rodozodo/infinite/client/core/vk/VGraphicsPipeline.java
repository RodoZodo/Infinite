package rodozodo.infinite.client.core.vk;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;


// Graphics pipeline for dynamic rendering built from a {@link VProgram}, with dynamic viewport and stencil reference.
// Front faces are clockwise under Minecraft's unflipped Vulkan viewport.

public final class VGraphicsPipeline implements Destroyable {
    public final VProgram program;
    public final long pipeline;

    private VGraphicsPipeline(VProgram program, long pipeline) {
        this.program = program;
        this.pipeline = pipeline;
    }

    public void bind(VkCommandBuffer cb) {
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline);
    }

    public void flush(VkCommandBuffer cb, StagingBuffer staging) {
        this.program.flush(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, staging);
    }

    public void free() {
        VkContext.get().destroyDeferred(this);
    }

    @Override
    public void destroy() {
        vkDestroyPipeline(VkContext.get().vkDevice, this.pipeline, null);
    }

    public static Builder builder(VProgram program) {
        return new Builder(program);
    }

    public record Attribute(int location, int format, int offset) {}

    public static final class Builder {
        private final VProgram program;
        private int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        private int cullMode = VK_CULL_MODE_NONE;
        private int frontFace = VK_FRONT_FACE_CLOCKWISE;
        private int polygonMode = VK_POLYGON_MODE_FILL;
        private boolean depthTest;
        private boolean depthWrite;
        private int depthCompare = VK_COMPARE_OP_ALWAYS;
        private boolean stencilTest;
        private int stencilCompare = VK_COMPARE_OP_ALWAYS, stencilPass = VK_STENCIL_OP_KEEP, stencilFail = VK_STENCIL_OP_KEEP, stencilDepthFail = VK_STENCIL_OP_KEEP;
        private int stencilCompareMask = 0xFF, stencilWriteMask = 0xFF;
        private int[] colorFormats = new int[0];
        private int depthFormat = VK_FORMAT_UNDEFINED;
        private int stencilFormat = VK_FORMAT_UNDEFINED;
        private boolean blend;
        private int srcColor, dstColor, srcAlpha, dstAlpha;
        private int colorWriteMask = 0xF;
        private int vertexStride;
        private Attribute[] vertexAttributes = new Attribute[0];

        private Builder(VProgram program) {
            this.program = program;
        }

        public Builder vertexInput(int stride, Attribute... attributes) {
            this.vertexStride = stride;
            this.vertexAttributes = attributes;
            return this;
        }

        public Builder topology(int topology) { this.topology = topology; return this; }
        public Builder cull(int cullMode) { this.cullMode = cullMode; return this; }
        public Builder frontFace(int frontFace) { this.frontFace = frontFace; return this; }
        public Builder polygonMode(int mode) { this.polygonMode = mode; return this; }
        public Builder depth(boolean test, boolean write, int compare) {
            this.depthTest = test;
            this.depthWrite = write;
            this.depthCompare = compare;
            return this;
        }
        public Builder stencil(int compare, int fail, int pass, int depthFail, int compareMask, int writeMask) {
            this.stencilTest = true;
            this.stencilCompare = compare;
            this.stencilFail = fail;
            this.stencilPass = pass;
            this.stencilDepthFail = depthFail;
            this.stencilCompareMask = compareMask;
            this.stencilWriteMask = writeMask;
            return this;
        }
        public Builder colorFormats(int... formats) { this.colorFormats = formats; return this; }
        public Builder depthFormat(int format, boolean hasStencil) {
            this.depthFormat = format;
            this.stencilFormat = hasStencil ? format : VK_FORMAT_UNDEFINED;
            return this;
        }
        public Builder blend(int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
            this.blend = true;
            this.srcColor = srcColor;
            this.dstColor = dstColor;
            this.srcAlpha = srcAlpha;
            this.dstAlpha = dstAlpha;
            return this;
        }
        public Builder colorWriteMask(int mask) { this.colorWriteMask = mask; return this; }

        public VGraphicsPipeline build() {
            var device = VkContext.get().vkDevice;
            try (var stack = MemoryStack.stackPush()) {
                var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(this.program.module(VProgram.Stage.VERTEX)).pName(stack.UTF8("main"));
                stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(this.program.module(VProgram.Stage.FRAGMENT)).pName(stack.UTF8("main"));

                var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
                if (this.vertexStride > 0) {
                    var binding = VkVertexInputBindingDescription.calloc(1, stack);
                    binding.get(0).binding(0).stride(this.vertexStride).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                    var attributes = VkVertexInputAttributeDescription.calloc(this.vertexAttributes.length, stack);
                    for (int i = 0; i < this.vertexAttributes.length; i++) {
                        var attribute = this.vertexAttributes[i];
                        attributes.get(i).location(attribute.location()).binding(0)
                                .format(attribute.format()).offset(attribute.offset());
                    }
                    vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributes);
                }
                var inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(this.topology);
                var viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
                var raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                        .polygonMode(this.polygonMode)
                        .cullMode(this.cullMode)
                        .frontFace(this.frontFace)
                        .lineWidth(1.0f);
                var multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

                var depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                        .depthTestEnable(this.depthTest)
                        .depthWriteEnable(this.depthWrite)
                        .depthCompareOp(this.depthCompare)
                        .stencilTestEnable(this.stencilTest);
                for (var face : new VkStencilOpState[]{depthStencil.front(), depthStencil.back()}) {
                    face.failOp(this.stencilFail).passOp(this.stencilPass).depthFailOp(this.stencilDepthFail)
                            .compareOp(this.stencilCompare).compareMask(this.stencilCompareMask).writeMask(this.stencilWriteMask);
                }

                var attachments = VkPipelineColorBlendAttachmentState.calloc(Math.max(1, this.colorFormats.length), stack);
                for (int i = 0; i < this.colorFormats.length; i++) {
                    attachments.get(i).colorWriteMask(this.colorWriteMask).blendEnable(this.blend)
                            .srcColorBlendFactor(this.srcColor).dstColorBlendFactor(this.dstColor).colorBlendOp(VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(this.srcAlpha).dstAlphaBlendFactor(this.dstAlpha).alphaBlendOp(VK_BLEND_OP_ADD);
                }
                attachments.limit(this.colorFormats.length);
                var colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(attachments);

                var dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                        .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_STENCIL_REFERENCE));

                var rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                        .colorAttachmentCount(this.colorFormats.length)
                        .pColorAttachmentFormats(this.colorFormats.length == 0 ? null : stack.ints(this.colorFormats))
                        .depthAttachmentFormat(this.depthFormat)
                        .stencilAttachmentFormat(this.stencilFormat);

                var info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                        .pNext(rendering.address())
                        .pStages(stages)
                        .pVertexInputState(vertexInput)
                        .pInputAssemblyState(inputAssembly)
                        .pViewportState(viewport)
                        .pRasterizationState(raster)
                        .pMultisampleState(multisample)
                        .pDepthStencilState(depthStencil)
                        .pColorBlendState(colorBlend)
                        .pDynamicState(dynamic)
                        .layout(this.program.pipelineLayout);
                var p = stack.mallocLong(1);
                VkUtil.check(vkCreateGraphicsPipelines(device, VkContext.get().pipelineCache, info, null, p), "vkCreateGraphicsPipelines(" + this.program.name + ")");
                return new VGraphicsPipeline(this.program, p.get(0));
            }
        }
    }
}
