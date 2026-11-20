package rodozodo.infinite.world.voxelize;

import rodozodo.infinite.world.IdRegistry;
import rodozodo.infinite.world.Voxel;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.WeakHashMap;


// Converts a Minecraft chunk section's block states, biomes, and light layers into level 0 cells of a {@link VoxelChunk}

public final class ChunkVoxelizer {
    private static final class Scratch {
        final int[] paletteIndices = new int[VoxelChunk.VOLUME];
        final int[] biomes = new int[64];
        final Holder<Biome>[] biomeHolders = new Holder[64];
        int[] blockIds = new int[256];
        final WeakHashMap<IdRegistry, Reference2IntOpenHashMap<Object>> known = new WeakHashMap<>();

        Reference2IntOpenHashMap<Object> knownFor(IdRegistry ids) {
            return this.known.computeIfAbsent(ids, registry -> {
                var map = new Reference2IntOpenHashMap<>();
                map.defaultReturnValue(-1);
                return map;
            });
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private ChunkVoxelizer() {}

    public static VoxelChunk voxelize(VoxelChunk out, IdRegistry ids, PalettedContainer<BlockState> blocks,
                                      PalettedContainerRO<Holder<Biome>> biomes,
                                      @Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {

        // 4x4x4 biome grid indexed (y, z, x)
        var holders = SCRATCH.get().biomeHolders;
        for (int i = 0; i < 64; i++) {
            holders[i] = biomes.get(i & 3, i >> 4, i >> 2 & 3);
        }
        return voxelize(out, ids, blocks.data.storage, blocks.data.palette, holders, blockLight, skyLight);
    }

    public static VoxelChunk voxelize(VoxelChunk out, IdRegistry ids, @Nullable BitStorage storage,
                                      Palette<BlockState> palette, Holder<Biome>[] biomes,
                                      @Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {
        var scratch = SCRATCH.get();
        var known = scratch.knownFor(ids);

        for (int i = 0; i < 64; i++) {
            scratch.biomes[i] = biomeId(ids, known, biomes[i]);
        }

        int[] indices = scratch.paletteIndices;
        if (storage == null) Arrays.fill(indices, 0);
        else storage.unpack(indices);
        boolean global = palette instanceof GlobalPalette<BlockState>;
        int[] blockIds = null;
        int size = palette.getSize();
        if (!global) {
            if (scratch.blockIds.length < size) scratch.blockIds = new int[size];
            blockIds = scratch.blockIds;
            for (int i = 0; i < size; i++) blockIds[i] = blockId(ids, known, palette.valueFor(i));
        }

        boolean lit = blockLight != null && !blockLight.isEmpty() || skyLight != null && !skyLight.isEmpty();
        int[] states = out.blocks();
        byte[] lightPlane = out.blockLight();
        int solid = 0;
        for (int i = 0; i < VoxelChunk.VOLUME; i++) {
            int x = i & 15, z = i >> 4 & 15, y = i >> 8;
            int index = indices[i];
            int block = global ? blockId(ids, known, palette.valueFor(index)) : index < size ? blockIds[index] : 0;
            int light = lit ? Voxel.lightOf(level(skyLight, x, y, z), level(blockLight, x, y, z)) : 0;
            if (block != 0) solid++;
            int biome = scratch.biomes[(y >> 2) << 4 | (z >> 2) << 2 | x >> 2];
            states[i] = Voxel.state(block, biome);
            lightPlane[i] = (byte) light;
        }
        out.setSolidCount(solid);
        return out;
    }

    private static int level(@Nullable DataLayer layer, int x, int y, int z) {
        return layer == null ? 0 : layer.get(x, y, z);
    }

    private static int blockId(IdRegistry ids, Reference2IntOpenHashMap<Object> known, @Nullable BlockState state) {
        if (state == null) return 0;
        int id = known.getInt(state);
        if (id < 0) {
            id = ids.blockId(state);
            known.put(state, id);
        }
        return id;
    }

    private static int biomeId(IdRegistry ids, Reference2IntOpenHashMap<Object> known, Holder<Biome> biome) {
        int id = known.getInt(biome);
        if (id < 0) {
            id = ids.biomeId(biome);
            known.put(biome, id);
        }
        return id;
    }
}