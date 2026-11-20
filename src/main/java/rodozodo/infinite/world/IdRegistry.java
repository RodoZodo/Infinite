package rodozodo.infinite.world;

import rodozodo.infinite.Log;
import rodozodo.infinite.storage.MappingStore;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


public final class IdRegistry {
    // 2-bit entry type packed into bits 30-31 of storage mapping keys
    private static final int BLOCK_TYPE = 3, BIOME_TYPE = 1;
    private static final int ID_BITS = 30;
    private static final BlockState MISSING_STATE = Blocks.STONE.defaultBlockState();

    public record KnownBiome(int id, String name) {}

    private final MappingStore store;
    private final ConcurrentHashMap<BlockState, Integer> blockIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> biomeIds = new ConcurrentHashMap<>();
    private final Object blockLock = new Object(), biomeLock = new Object();

    // Guarded by blockLock; arrays are swapped with larger copies so lock-free reads stay valid
    private volatile BlockState[] blocks = new BlockState[1024];
    private volatile byte[] opacities = new byte[1024];
    private volatile int[] covers = new int[1024], grounds = new int[1024];
    private volatile boolean[] covering = new boolean[1024];
    private volatile boolean[] fluid = new boolean[1024];
    private final ConcurrentHashMap<Long, Integer> coveredIds = new ConcurrentHashMap<>();
    private volatile int blockCount;
    private final List<String> biomes = new ArrayList<>();
    private final List<Consumer<KnownBiome>> biomeListeners = new ArrayList<>();

    public IdRegistry(MappingStore store) {
        this.store = store;
        this.blocks[0] = Blocks.AIR.defaultBlockState();
        this.blockIds.put(this.blocks[0], 0);
        this.blockCount = 1;
        this.load();
    }

    public int blockId(BlockState state) {
        if (state.isAir()) return 0;
        Integer id = this.blockIds.get(state);
        return id != null ? id : this.addBlock(state);
    }

    public BlockState blockState(int id) {
        return this.blocks[id];
    }

    public int opacity(int id) {
        return this.opacities[id];
    }

    public int blockCount() {
        return this.blockCount;
    }

    public boolean covering(int id) {
        return this.covering[id];
    }

    public int coverOf(int id) {
        return this.covers[id];
    }

    public boolean fluid(int id) {
        return this.fluid[id];
    }

    public int groundOf(int id) {
        return this.covers[id] != 0 ? this.grounds[id] : id;
    }

    public int coveredId(int ground, int cover) {
        if (this.covers[ground] != 0) ground = this.grounds[ground];
        long key = (long) ground << 32 | cover;
        Integer id = this.coveredIds.get(key);
        if (id != null) return id;
        BlockState state;
        synchronized (this.blockLock) {
            id = this.coveredIds.get(key);
            if (id != null) return id;
            id = this.blockCount;
            state = this.blocks[ground];
            this.putBlock(id, state, ground, cover);
            this.coveredIds.put(key, id);
        }
        var tag = blockTag(id, state);
        tag.putInt("ground", ground);
        tag.putInt("cover", cover);
        this.write(BLOCK_TYPE, id, tag);
        return id;
    }

    private int addBlock(BlockState state) {
        int id;
        synchronized (this.blockLock) {
            Integer existing = this.blockIds.get(state);
            if (existing != null) return existing;
            id = this.blockCount;
            this.putBlock(id, state, 0, 0);
            this.blockIds.put(state, id);
        }
        this.writeBlock(id, state);
        return id;
    }

    // Caller must hold blockLock (or be in load())
    private void putBlock(int id, BlockState state, int ground, int cover) {
        var blocks = this.blocks;
        var opacities = this.opacities;
        var covers = this.covers;
        var grounds = this.grounds;
        var covering = this.covering;
        var fluid = this.fluid;
        if (id >= blocks.length) {
            int size = Math.max(blocks.length * 2, id + 1);
            blocks = Arrays.copyOf(blocks, size);
            opacities = Arrays.copyOf(opacities, size);
            covers = Arrays.copyOf(covers, size);
            grounds = Arrays.copyOf(grounds, size);
            covering = Arrays.copyOf(covering, size);
            fluid = Arrays.copyOf(fluid, size);
        }
        blocks[id] = state;
        opacities[id] = (byte) (state.getBlock() instanceof LeavesBlock ? 15 : state.getLightDampening());
        covers[id] = cover;
        grounds[id] = ground;
        covering[id] = cover == 0 && state.getBlock() instanceof VegetationBlock && state.getLightDampening() == 0;
        fluid[id] = state.getBlock() instanceof LiquidBlock;
        this.covers = covers;
        this.grounds = grounds;
        this.covering = covering;
        this.fluid = fluid;
        this.opacities = opacities;
        this.blocks = blocks;
        this.blockCount = Math.max(this.blockCount, id + 1);
    }

    private void writeBlock(int id, BlockState state) {
        this.write(BLOCK_TYPE, id, blockTag(id, state));
    }

    private static CompoundTag blockTag(int id, BlockState state) {
        var tag = new CompoundTag();
        tag.putInt("id", id);
        tag.put("block_state", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow());
        return tag;
    }

    public int biomeId(Holder<Biome> biome) {
        return this.biomeId(biome.unwrapKey().orElseThrow().identifier().toString());
    }

    public int biomeId(String name) {
        Integer id = this.biomeIds.get(name);
        return id != null ? id : this.addBiome(name);
    }

    private int addBiome(String name) {
        KnownBiome added;
        List<Consumer<KnownBiome>> listeners;
        synchronized (this.biomeLock) {
            Integer existing = this.biomeIds.get(name);
            if (existing != null) return existing;
            added = new KnownBiome(this.biomes.size(), name);
            this.biomes.add(name);
            this.biomeIds.put(name, added.id());
            listeners = List.copyOf(this.biomeListeners);
        }
        var tag = new CompoundTag();
        tag.putInt("id", added.id());
        tag.putString("biome_id", name);
        this.write(BIOME_TYPE, added.id(), tag);
        for (var listener : listeners) listener.accept(added);
        return added.id();
    }

    public void addBiomeListener(Consumer<KnownBiome> listener) {
        List<KnownBiome> known = new ArrayList<>();
        synchronized (this.biomeLock) {
            for (int i = 0; i < this.biomes.size(); i++) known.add(new KnownBiome(i, this.biomes.get(i)));
            this.biomeListeners.add(listener);
        }
        known.forEach(listener);
    }

    public void removeBiomeListener(Consumer<KnownBiome> listener) {
        synchronized (this.biomeLock) {
            this.biomeListeners.remove(listener);
        }
    }

    private void write(int type, int id, CompoundTag tag) {
        try {
            var bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));
            byte[] data = bytes.toByteArray();
            var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
            this.store.writeMapping(type << ID_BITS | id, buffer);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save LOD id " + id, e);
        }
    }

    private static CompoundTag read(byte[] data) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(data)), NbtAccounter.unlimitedHeap());
    }

    private void load() {
        var saved = this.store.readMappings();
        int maxBiome = -1;
        var biomeNames = new String[saved.size()];
        for (var entry : saved.int2ObjectEntrySet()) {
            int type = entry.getIntKey() >>> ID_BITS;
            int id = entry.getIntKey() & (1 << ID_BITS) - 1;
            CompoundTag tag;
            try {
                tag = read(entry.getValue());
            } catch (IOException e) {
                Log.error("Unreadable saved LoD id " + id, e);
                tag = new CompoundTag();
            }
            if (type == BLOCK_TYPE) {
                if (id == 0) continue;
                var state = this.decodeBlock(id, tag);
                int ground = tag.getIntOr("ground", 0), cover = tag.getIntOr("cover", 0);
                if (cover != 0) {
                    // Covered IDs are keyed by (ground, cover), not by ground BlockState
                    this.putBlock(id, state, ground, cover);
                    this.coveredIds.putIfAbsent((long) ground << 32 | cover, id);
                } else {
                    this.putBlock(id, state, 0, 0);
                    this.blockIds.putIfAbsent(state, id);
                }
            } else if (type == BIOME_TYPE) {
                if (id >= biomeNames.length) biomeNames = Arrays.copyOf(biomeNames, id + 1);
                biomeNames[id] = tag.getStringOr("biome_id", "minecraft:plains");
                maxBiome = Math.max(maxBiome, id);
            } else {
                Log.error("Unknown saved LoD id type " + type + " (" + id + ")");
            }
        }
        for (int i = 1; i < this.blockCount; i++) {
            if (this.blocks[i] == null) {
                Log.error("Missing saved LoD block id " + i + "; using stone");
                this.putBlock(i, MISSING_STATE, 0, 0);
            }
        }
        for (int i = 0; i <= maxBiome; i++) {
            String name = biomeNames[i];
            if (name == null) {
                Log.error("Missing saved LoD biome id " + i + "; using plains");
                name = "minecraft:plains";
            }
            this.biomes.add(name);
            this.biomeIds.putIfAbsent(name, i);
        }
    }

    private BlockState decodeBlock(int id, CompoundTag tag) {
        var encoded = tag.get("block_state");
        if (encoded == null) {
            Log.error("Saved LoD block id " + id + " missing block state; using stone");
            return MISSING_STATE;
        }
        var result = BlockState.CODEC.parse(NbtOps.INSTANCE, encoded);
        var state = result.resultOrPartial().orElse(null);
        if (state == null || state.isAir()) {
            Log.error("Unknown saved LoD block id " + id + " (" + encoded + "); using stone");
            return MISSING_STATE;
        }
        if (result.isError()) {
            Log.warn("Partially decoded LoD block id " + id + "; using " + state);
        }
        return state;
    }
}
