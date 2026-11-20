package rodozodo.infinite.client.render;

import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.core.vk.*;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;


final class SoftwareRaster {
    static final int FALLBACK_CAPACITY = 1 << 20;
    private static final int MOST_PIXELS = Integer.getInteger("infinite.swRasterMostPixels", 32);
    private static final int RASTER_GROUP = Integer.getInteger("infinite.swRasterGroup", 64);
    private static final int SOLID_GROUP = Integer.getInteger("infinite.swRasterSolidGroup", 128);
    private static final int FALLBACK_DRAW_BYTES = 32;
    private static final boolean STATS = Boolean.getBoolean("infinite.swRasterStats");
    private static final long STATS_OFFSET = 128;
    private static final int STATS_STRIPES = 8, STATS_BYTES = 2 * STATS_STRIPES * 48;

    private static final int S_ARGS_CONTROL = 0, S_ARGS_SELECTED = 1, S_ARGS_OUT = 2, S_ARGS_FALLBACK = 3, S_ARGS_WINNERS = 4;
    private static final int S_VISIBILITY = 7, S_RASTER_ARGS = 8, S_FALLBACK_DRAWS = 9, S_FALLBACK_BLOCKS = 10, S_WINNERS = 11;
    private static final int S_CLAIM_RASTER_ARGS = 0, S_CLAIM_WINNERS = 1, S_NEW_TILES = 3;
    private static final int NEW_TILE_SIZE = 16, NEW_TILE_LIST = 8;
    private static final int WINNER_CAPACITY = Integer.getInteger("infinite.swRasterWinnerCapacity", 1 << 23);
    private static final int WINNER_HEADER_WORDS = 8;
    private static final long CLAIM_LIST_DISPATCH = 0, CLAIM_EVERY_DISPATCH = 16;
    private static final int CLAIM_GROUP = Integer.getInteger("infinite.swRasterClaimGroup", 64);
    private static final boolean RESOLVE_ONE_TRIANGLE = !"false".equals(System.getProperty("infinite.swResolveOneTriangle"));
    private static final int L_ARGS_PASS = 0, L_SCREEN = 0, L_RASTER_PASS = 1, L_FIRST_PAYLOAD = 1;
    private static final boolean NEW_TILES = !"false".equals(System.getProperty("infinite.swNewTiles"));

    private static VCompute claimProgram(String name, VDefines claimDefines, boolean list, long geometryLayout) {
        var defines = list ? claimDefines.copy().define("CLAIM_LIST", 1).define("RASTER_GROUP", CLAIM_GROUP + "u") : claimDefines;
        return VCompute.create("infinite:terrain/raster.comp (" + name + ")", VShaderCompiler.load("infinite:terrain/raster.comp"),
                defines, geometryLayout);
    }

    private static VDefines resolveDefines(VDefines defines) {
        var resolve = defines.copy();
        if (RESOLVE_ONE_TRIANGLE) resolve.define("RESOLVE_ONE_TRIANGLE", 1);
        return resolve;
    }

    private final boolean splitVisibility;
    private final boolean blockStats;
    private final StagingBuffer staging;
    private final IndexBuffers indices;
    private final VCompute args;
    private final VCompute claim;
    private final VCompute claimArgs, claimEvery;
    private final VCompute claimNew, claimEveryNew;
    private final VCompute rasterSolid, rasterMasked;
    private final VGraphicsPipeline resolve;
    private final VGraphicsPipeline resolveTiles;
    private final VBuffer rasterArgs, fallbackDraws, fallbackBlocks;
    private final VBuffer winners;
    private long statWinnersFirst, statWinnersNew;
    private long statWalked, statHidden, statWalkedFirst, statHiddenFirst, statWalkedNew, statHiddenNew,
            statBlocksWalked, statBlocksHidden;
    private long statDepthQuads, statOtherQuads, statEmptyQuads, statDepthBlocks, statDepthEmptyBlocks, statOtherBlocks,
            statEmptyBlocks;
    private boolean statValid;
    private VBuffer visibility;
    private VBuffer newTiles;
    private int width, height;

    SoftwareRaster(StagingBuffer staging, IndexBuffers indices, VDefines listDefines, VDefines terrainDefines,
                   long geometryLayout, int nearer, int depthStencilFormat, int blockQuads) {
        this.staging = staging;
        this.indices = indices;
        this.splitVisibility = splitsVisibility();
        this.args = VCompute.asset("infinite:terrain/raster_args.comp", listDefines.copy()
                .define("FALLBACK_CAPACITY", FALLBACK_CAPACITY + "u")
                .define("RASTER_GROUP", RASTER_GROUP + "u")
                .define("SOLID_GROUP", SOLID_GROUP + "u")
                .defineIf("WINNER_LIST", this.splitVisibility));
        this.blockStats = STATS && blockQuads == 16;
        var defines = terrainDefines.copy()
                .define("BLOCK_QUADS", blockQuads + "u")
                .define("MOST_PIXELS", MOST_PIXELS)
                .define("FALLBACK_CAPACITY", FALLBACK_CAPACITY + "u")
                .define("WINNER_CAPACITY", WINNER_CAPACITY + "u");
        if (this.splitVisibility) defines.define("VISIBILITY_SPLIT", 1);
        if (!ClientSettings.get().plantCutouts) defines.define("SOLID_PLANTS", 1);
        if (STATS) {
            defines.define("RASTER_STATS", 1);
            if (this.blockStats) defines.define("STATS_BLOCKS", 1);
        }
        defines.define("RASTER_GROUP", RASTER_GROUP + "u");

        // Split 2x32-bit visibility claims payloads in a second pass because Metal / MoltenVK lacks 64-bit atomics.
        var claimDefines = defines.copy().define("CLAIM_PASS", 1);
        boolean newTiles = this.splitVisibility && NEW_TILES;
        this.claim = this.splitVisibility ? claimProgram("claim", claimDefines, true, geometryLayout) : null;
        this.claimEvery = this.splitVisibility ? claimProgram("claim, every quad", claimDefines, false, geometryLayout) : null;
        var newDefines = claimDefines.copy().define("NEW_TILES", 1);
        this.claimNew = newTiles ? claimProgram("claim, newly visible", newDefines, true, geometryLayout) : null;
        this.claimEveryNew = newTiles ? claimProgram("claim, newly visible, every quad", newDefines, false, geometryLayout) : null;
        this.claimArgs = this.splitVisibility ? VCompute.asset("infinite:terrain/claim_args.comp", new VDefines()
                .define("CLAIM_GROUP", CLAIM_GROUP + "u").define("WINNER_CAPACITY", WINNER_CAPACITY + "u")) : null;
        this.rasterSolid = VCompute.create("infinite:terrain/raster.comp (solid)",
                VShaderCompiler.load("infinite:terrain/raster.comp"), defines.copy().define("SOLID_LIST", 1)
                .define("RASTER_GROUP", SOLID_GROUP + "u"), geometryLayout);
        this.rasterMasked = VCompute.create("infinite:terrain/raster.comp (masked)",
                VShaderCompiler.load("infinite:terrain/raster.comp"), defines.copy().define("MASKED_LIST", 1),
                geometryLayout);
        this.resolve = VGraphicsPipeline.builder(VProgram.graphics("lod_resolve", VShaderCompiler.load("infinite:frame/fullscreen.vert"),
                        VShaderCompiler.load("infinite:terrain/resolve.frag"), resolveDefines(defines), geometryLayout))
                .depth(true, true, nearer)
                .stencil(VK_COMPARE_OP_EQUAL, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, 0xFF, 0x00)
                .colorFormats(LodTargets.COLOUR_FORMAT).depthFormat(depthStencilFormat, true)
                .build();
        this.resolveTiles = newTiles ? VGraphicsPipeline.builder(VProgram.graphics("lod_resolve_tiles",
                        VShaderCompiler.load("infinite:terrain/resolve_tiles.vert"), VShaderCompiler.load("infinite:terrain/resolve.frag"),
                        resolveDefines(defines), geometryLayout))
                .depth(true, true, nearer)
                .stencil(VK_COMPARE_OP_EQUAL, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, 0xFF, 0x00)
                .colorFormats(LodTargets.COLOUR_FORMAT).depthFormat(depthStencilFormat, true)
                .build() : null;
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        this.rasterArgs = VBuffer.create("lod_raster_args", STATS ? STATS_OFFSET + STATS_BYTES : STATS_OFFSET,
                storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | (STATS ? VK_BUFFER_USAGE_TRANSFER_SRC_BIT : 0),
                VBuffer.Access.DEVICE_LOCAL);
        this.fallbackDraws = VBuffer.create("lod_raster_fallback_draws", 2L * FALLBACK_DRAW_BYTES,
                storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, VBuffer.Access.DEVICE_LOCAL);
        this.fallbackBlocks = VBuffer.create("lod_raster_fallback_blocks", 2L * 8 * FALLBACK_CAPACITY, storage, VBuffer.Access.DEVICE_LOCAL);
        this.winners = this.splitVisibility ? VBuffer.create("lod_raster_winners", 4L * (WINNER_HEADER_WORDS + WINNER_CAPACITY),
                storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | (STATS ? VK_BUFFER_USAGE_TRANSFER_SRC_BIT : 0),
                VBuffer.Access.DEVICE_LOCAL) : null;
    }

    static boolean wanted() {
        if ("false".equals(System.getProperty("infinite.swRaster"))) return false;
        return VkContext.get().hasFeature(InfiniteVkFeatures.SHADER_BUFFER_INT64_ATOMICS) || splitsVisibility();
    }

    private static boolean splitsVisibility() {
        return !VkContext.get().hasFeature(InfiniteVkFeatures.SHADER_BUFFER_INT64_ATOMICS)
                && VkContext.get().vendor == GpuVendor.APPLE;
    }

    void raster(VkCommandBuffer cb, int pass, int width, int height, VBuffer control, Consumer<VProgram> terrain,
                Runnable keysKept) {
        boolean created = this.visibility == null || this.width != width || this.height != height;
        if (created) {
            if (this.visibility != null) this.visibility.free();
            this.width = width;
            this.height = height;
            this.visibility = VBuffer.create("lod_visibility", 8L * width * height,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VBuffer.Access.DEVICE_LOCAL);
            if (this.resolveTiles != null) {
                if (this.newTiles != null) this.newTiles.free();
                long tiles = (long) ((width + NEW_TILE_SIZE - 1) / NEW_TILE_SIZE) * ((height + NEW_TILE_SIZE - 1) / NEW_TILE_SIZE);
                this.newTiles = VBuffer.create("lod_raster_new_tiles", 4L * (NEW_TILE_LIST + tiles + (tiles + 31) / 32),
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VBuffer.Access.DEVICE_LOCAL);
            }
        }
        if (pass == 1 && this.newTiles != null) {
            // Wait for the previous frame's indirect tile draw before resetting newTiles.
            VBarriers.memory(cb, VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT,
                    VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT,
                    VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
            vkCmdFillBuffer(cb, this.newTiles.handle, 0, 4, 6);
            vkCmdFillBuffer(cb, this.newTiles.handle, 4, VK_WHOLE_SIZE, 0);
            VBarriers.transferToCompute(cb);
        }
        if (pass == 0 || created) {
            vkCmdFillBuffer(cb, this.visibility.handle, 0, VK_WHOLE_SIZE, 0);
            VBarriers.transferToCompute(cb);
        } else {
            // Wait for pass 0's resolve reads before pass 1 updates the visibility buffer.
            VBarriers.memory(cb, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        }
        if (STATS && pass == 0) {
            vkCmdFillBuffer(cb, this.rasterArgs.handle, STATS_OFFSET, STATS_BYTES, 0);
            VBarriers.transferToCompute(cb);
        }
        this.args.program.ssbo(S_ARGS_CONTROL, control).ssbo(S_ARGS_SELECTED, control).ssbo(S_ARGS_OUT, this.rasterArgs)
                .ssbo(S_ARGS_FALLBACK, this.fallbackDraws).uniform1ui(L_ARGS_PASS, pass);
        if (this.winners != null) this.args.program.ssbo(S_ARGS_WINNERS, this.winners);
        this.args.bind(cb, this.staging).dispatch(cb, 1, 1, 1);
        VBarriers.memory(cb, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        for (int list = 0; list < 2; list++) {
            var program = list == 0 ? this.rasterSolid : this.rasterMasked;
            terrain.accept(program.program);
            program.program.ssbo(S_VISIBILITY, this.visibility).ssbo(S_RASTER_ARGS, this.rasterArgs)
                    .ssbo(S_FALLBACK_DRAWS, this.fallbackDraws).ssbo(S_FALLBACK_BLOCKS, this.fallbackBlocks)
                    .uniform2i(L_SCREEN, width, height).uniform1ui(L_RASTER_PASS, pass);
            if (this.winners != null) program.program.ssbo(S_WINNERS, this.winners);
            program.bind(cb, this.staging).dispatchIndirect(cb, this.rasterArgs, (4L + 2L * list + pass) * 16L);
        }
        if (this.claim != null) {
            keysKept.run();
            // All depth keys must be written before any thread claims a winning pixel's payload.
            VBarriers.memory(cb, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            this.claimArgs.program.ssbo(S_CLAIM_RASTER_ARGS, this.rasterArgs).ssbo(S_CLAIM_WINNERS, this.winners)
                    .uniform1ui(L_ARGS_PASS, pass);
            this.claimArgs.bind(cb, this.staging).dispatch(cb, 1, 1, 1);
            VBarriers.memory(cb, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            boolean tiles = pass == 1 && this.claimNew != null;
            for (var claim : tiles ? new VCompute[]{this.claimNew, this.claimEveryNew} : new VCompute[]{this.claim, this.claimEvery}) {
                terrain.accept(claim.program);
                claim.program.ssbo(S_VISIBILITY, this.visibility).ssbo(S_RASTER_ARGS, this.rasterArgs)
                        .ssbo(S_FALLBACK_DRAWS, this.fallbackDraws).ssbo(S_FALLBACK_BLOCKS, this.fallbackBlocks)
                        .ssbo(S_WINNERS, this.winners).uniform2i(L_SCREEN, width, height).uniform1ui(L_RASTER_PASS, pass);
                if (tiles) claim.program.ssbo(S_NEW_TILES, this.newTiles);
                claim.bind(cb, this.staging).dispatchIndirect(cb, this.winners,
                        claim == this.claim || claim == this.claimNew ? CLAIM_LIST_DISPATCH : CLAIM_EVERY_DISPATCH);
            }
        }
        VBarriers.memory(cb, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    void resolve(VkCommandBuffer cb, int pass, int firstPayload, Consumer<VProgram> terrain) {
        boolean tiles = pass == 1 && this.newTiles != null;
        var pipeline = tiles ? this.resolveTiles : this.resolve;
        pipeline.bind(cb);
        terrain.accept(pipeline.program);
        pipeline.program.ssbo(S_VISIBILITY, this.visibility).uniform2i(L_SCREEN, this.width, this.height)
                .uniform1ui(L_FIRST_PAYLOAD, firstPayload);
        if (tiles) pipeline.program.ssbo(S_NEW_TILES, this.newTiles);
        pipeline.flush(cb, this.staging);
        vkCmdSetStencilReference(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 1);
        vkCmdBindIndexBuffer(cb, this.indices.quads.handle, 0, VK_INDEX_TYPE_UINT16);
        if (tiles) {
            vkCmdDrawIndexedIndirect(cb, this.newTiles.handle, 0, 1, 20);
        } else {
            vkCmdDrawIndexed(cb, 6, 1, 0, 0, 0);
        }
    }

    VBuffer fallbackBlocks() {
        return this.fallbackBlocks;
    }

    VBuffer fallbackDraws() {
        return this.fallbackDraws;
    }

    static long fallbackDrawOffset(int pass) {
        return (long) pass * FALLBACK_DRAW_BYTES;
    }

    void requestStats(ReadbackBuffer readback) {
        if (!STATS) return;
        readback.request(this.rasterArgs, STATS_OFFSET, STATS_BYTES, (address, size) -> {
            long[] totals = new long[24];
            for (int pass = 0; pass < 2; pass++) {
                for (int stripe = 0; stripe < STATS_STRIPES; stripe++) {
                    long at = address + ((long) pass * STATS_STRIPES + stripe) * 48;
                    for (int counter = 0; counter < 12; counter++) {
                        totals[pass * 12 + counter] += Integer.toUnsignedLong(MemoryUtil.memGetInt(at + counter * 4L));
                    }
                }
            }
            this.statWalked = totals[0] + totals[12];
            this.statHidden = totals[1] + totals[13];
            this.statWalkedFirst = totals[0];
            this.statHiddenFirst = totals[1];
            this.statWalkedNew = totals[12];
            this.statHiddenNew = totals[13];
            this.statBlocksWalked = totals[2] + totals[14];
            this.statBlocksHidden = totals[3] + totals[15];
            this.statDepthQuads = totals[4] + totals[16];
            this.statOtherQuads = totals[5] + totals[17];
            this.statEmptyQuads = totals[6] + totals[18];
            this.statDepthBlocks = totals[7] + totals[19];
            this.statDepthEmptyBlocks = totals[8] + totals[20];
            this.statOtherBlocks = totals[9] + totals[21];
            this.statEmptyBlocks = totals[10] + totals[22];
            this.statValid = true;
        });
        if (this.winners != null) {
            readback.request(this.winners, 0, 4L * WINNER_HEADER_WORDS, (address, size) -> {
                this.statWinnersFirst = Integer.toUnsignedLong(MemoryUtil.memGetInt(address + 7 * 4L));
                this.statWinnersNew = Integer.toUnsignedLong(MemoryUtil.memGetInt(address + 3 * 4L));
            });
        }
    }

    String statsLine() {
        if (!this.statValid) return null;
        var text = new StringBuilder("  Raster    ").append(quadsLine(this.statWalked)).append(" quads walked, ")
                .append(quadsLine(this.statHidden)).append(" kept no pixel (")
                .append(share(this.statHidden, this.statWalked)).append("%): depth ")
                .append(quadsLine(this.statDepthQuads)).append(", mask/cutout ")
                .append(quadsLine(this.statOtherQuads)).append(", covered none ")
                .append(quadsLine(this.statEmptyQuads)).append(" · first pass ")
                .append(share(this.statHiddenFirst, this.statWalkedFirst)).append("% of ")
                .append(quadsLine(this.statWalkedFirst)).append(" · new pass ")
                .append(share(this.statHiddenNew, this.statWalkedNew)).append("% of ")
                .append(quadsLine(this.statWalkedNew));
        if (this.winners != null) {
            text.append(" · won a pixel ").append(quadsLine(this.statWinnersFirst)).append(" + ")
                    .append(quadsLine(this.statWinnersNew)).append(" of ").append(quadsLine(WINNER_CAPACITY)).append(" listed");
        }
        if (this.blockStats) {
            text.append(" · blocks ").append(quadsLine(this.statBlocksWalked)).append(" walked, ")
                    .append(quadsLine(this.statBlocksHidden)).append(" kept no pixel (")
                    .append(share(this.statBlocksHidden, this.statBlocksWalked)).append("%): depth-only ")
                    .append(quadsLine(this.statDepthBlocks)).append(", depth+empty ")
                    .append(quadsLine(this.statDepthEmptyBlocks)).append(", mask/cutout ")
                    .append(quadsLine(this.statOtherBlocks)).append(", empty ")
                    .append(quadsLine(this.statEmptyBlocks));
        }
        return text.toString();
    }

    private static long share(long part, long whole) {
        return whole == 0 ? 0 : Math.round(100.0 * part / whole);
    }

    private static String quadsLine(long quads) {
        if (quads >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.2fM", quads / 1e6);
        return quads >= 1_000 ? (quads / 1000) + "k" : Long.toString(quads);
    }

    void free() {
        this.args.free();
        if (this.claim != null) this.claim.free();
        if (this.claimEvery != null) this.claimEvery.free();
        if (this.claimNew != null) this.claimNew.free();
        if (this.claimEveryNew != null) this.claimEveryNew.free();
        if (this.claimArgs != null) this.claimArgs.free();
        this.rasterSolid.free();
        this.rasterMasked.free();
        this.resolve.program.free();
        this.resolve.free();
        if (this.resolveTiles != null) {
            this.resolveTiles.program.free();
            this.resolveTiles.free();
        }
        for (var buffer : new VBuffer[]{this.rasterArgs, this.fallbackDraws, this.fallbackBlocks, this.winners, this.visibility,
                this.newTiles}) {
            if (buffer != null) buffer.free();
        }
    }
}
