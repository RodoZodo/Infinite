package rodozodo.infinite.client.render;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import rodozodo.infinite.client.core.vk.StagingBuffer;
import rodozodo.infinite.client.core.vk.VBarriers;
import rodozodo.infinite.client.core.vk.VBuffer;
import rodozodo.infinite.client.core.vk.VImage;
import rodozodo.infinite.client.core.vk.VkContext;
import rodozodo.infinite.client.core.vk.VkUtil;
import rodozodo.infinite.client.model.BlockModels;
import rodozodo.infinite.client.model.ModelSink;
import rodozodo.infinite.client.model.ModelTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

//Manages GPU buffers and the dynamically grown mipmapped texture atlas for baked block models and biome tints.

final class ModelGpuStore implements ModelSink {
    private static final int CELLS_PER_ROW = 256;
    private static final int MOST_ROWS = BlockModels.MAX_MODELS / CELLS_PER_ROW;
    private static final int FIRST_ROWS = 8;

    final VBuffer models;
    final VBuffer colours;
    VImage atlas;
    private int rows;
    final long atlasSampler;
    private final StagingBuffer staging;

    ModelGpuStore(SetupCommands setup, StagingBuffer staging) {
        this.staging = staging;
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        this.models = VBuffer.create("models", (long) BlockModels.MODEL_BYTES * BlockModels.MAX_MODELS, usage, VBuffer.Access.DEVICE_LOCAL);
        this.colours = VBuffer.create("biome_tints", 4L * BlockModels.MAX_TINTS, usage, VBuffer.Access.DEVICE_LOCAL);
        setup.fill(this.models, 0);
        setup.fill(this.colours, -1);

        this.rows = FIRST_ROWS;
        this.atlas = allocateAtlas(this.rows);
        setup.initialize(this.atlas);
        this.atlasSampler = createAtlasSampler(blockAtlasMipLevels());
    }

    private static VImage allocateAtlas(int rows) {
        return VImage.allocate("model_atlas", VK_FORMAT_R8G8B8A8_UNORM, ModelTextures.CELL_WIDTH * CELLS_PER_ROW,
                ModelTextures.CELL_HEIGHT * rows, ModelTextures.LEVELS,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
    }

    // Must run before any frame writes into the atlas so the copy does not overwrite them.
    int reserveModels(VkCommandBuffer cb, int count) {
        int needed = Math.min(MOST_ROWS, (count + CELLS_PER_ROW - 1) / CELLS_PER_ROW);
        if (needed > this.rows) {
            int rows = this.rows;
            while (rows < needed) rows *= 2;
            var grown = allocateAtlas(rows);
            grown.initialize(cb);
            VBarriers.full(cb);
            try (var stack = MemoryStack.stackPush()) {
                var regions = VkImageCopy.calloc(ModelTextures.LEVELS, stack);
                for (int level = 0; level < ModelTextures.LEVELS; level++) {
                    var region = regions.get(level);
                    region.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, level, 0, 1);
                    region.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, level, 0, 1);
                    region.extent().set(this.atlas.width >> level, this.atlas.height >> level, 1);
                }
                vkCmdCopyImage(cb, this.atlas.image, VK_IMAGE_LAYOUT_GENERAL, grown.image, VK_IMAGE_LAYOUT_GENERAL, regions);
            }
            VBarriers.full(cb);
            this.atlas.free();
            this.atlas = grown;
            this.rows = rows;
        }
        return this.rows * CELLS_PER_ROW;
    }

    float atlasScaleV() {
        return (float) MOST_ROWS / this.rows;
    }

    private static int blockAtlasMipLevels() {
        var blocks = Minecraft.getInstance().getTextureManager().getTexture(Identifier.withDefaultNamespace("textures/atlas/blocks.png"));
        return ((TextureAtlas) blocks).maxMipLevel;
    }

    // Looked up every frame because resource reloads replace Minecraft's block atlas view.
    static long blockAtlasView() {
        var blocks = Minecraft.getInstance().getTextureManager().getTexture(Identifier.withDefaultNamespace("textures/atlas/blocks.png"));
        var view = blocks == null ? null : blocks.getTextureView();
        return view == null ? 0 : ((VulkanGpuTextureView) view).vkImageView();
    }

    private static long createAtlasSampler(int maxMip) {
        try (var stack = MemoryStack.stackPush()) {
            var info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .minLod(0).maxLod(maxMip);
            var handle = stack.mallocLong(1);
            VkUtil.check(vkCreateSampler(VkContext.get().vkDevice, info, null, handle), "vkCreateSampler(model atlas)");
            return handle.get(0);
        }
    }

    @Override
    public long modelEntry(int model) {
        return this.staging.write(this.models, (long) model * BlockModels.MODEL_BYTES, BlockModels.MODEL_BYTES);
    }

    @Override
    public long tints(int first, int count) {
        return this.staging.write(this.colours, first * 4L, count * 4L);
    }

    @Override
    public long atlasLevel(int model, int level) {
        int width = ModelTextures.CELL_WIDTH >> level, height = ModelTextures.CELL_HEIGHT >> level;
        return this.staging.writeImage(this.atlas, level, (model % CELLS_PER_ROW) * width, (model / CELLS_PER_ROW) * height, width, height);
    }

    void free() {
        this.models.free();
        this.colours.free();
        this.atlas.free();
        long sampler = this.atlasSampler;
        VkContext.get().destroyDeferred(() -> vkDestroySampler(VkContext.get().vkDevice, sampler, null));
    }
}
