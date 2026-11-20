package rodozodo.infinite.client.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.textures.GpuTexture;
import rodozodo.infinite.Log;
import rodozodo.infinite.util.NativeBuffer;
import rodozodo.infinite.world.IdRegistry;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;


// Manages baked block state LoD models, biome tint tables and GPU uploads.
// Models are baked on a background thread and published to meshing threads only after being written to the GPU.

public final class BlockModels {

    // 192-byte GPU model entry layout (terrain/quad.glsl).
    // Fluid UVs start at 8 byte aligned offset 40.
    public static final int MODEL_BYTES = 192;
    private static final int FLUID_UV_OFFSET = 40;
    public static final int MAX_MODELS = 1 << 16;

    // Biome ids are 9 bits in a quad. Top BLEND_SLOTS entries per fluid row hold blended tints.
    public static final int BIOME_STRIDE = 512;
    public static final int MAX_BIOME_MODELS = 1024;
    public static final int MAX_TINTS = BIOME_STRIDE * MAX_BIOME_MODELS;
    public static final int BLEND_SLOTS = 128;
    public static final int BIOME_LIMIT = BIOME_STRIDE - BLEND_SLOTS;
    public static final int MAX_BLOCKS = 1 << 20;
    private static final long UPLOAD_BUDGET = 4L << 20;
    private static final VarHandle INTS = MethodHandles.arrayElementVarHandle(int[].class);

    private final IdRegistry ids;
    private final ModelSink sink;

    private final int[] blockModels = new int[MAX_BLOCKS];
    private final long[] info = new long[MAX_MODELS];
    private final int[] fluidModels = new int[MAX_MODELS];
    private final int[] flowLevels = new int[MAX_MODELS];
    private final int[] fluidKinds = new int[MAX_MODELS];

    // Written before the model mapping is release published.
    private final int[] modelBlocks = new int[MAX_MODELS];
    private final long[] portableHashes = new long[MAX_MODELS];
    private final AtomicLongArray requested = new AtomicLongArray(MAX_BLOCKS / 64);
    private final AtomicInteger waiting = new AtomicInteger();
    private volatile int modelCount = 1;
    private Ready held;
    private volatile TextureAtlasSprite[] animatedSprites = new TextureAtlasSprite[0];

    private final LinkedBlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean running = true;

    private final ModelBaker baker;
    private final Int2IntOpenHashMap assigned = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap plantColours = new Int2IntOpenHashMap();
    private final Object2IntOpenHashMap<ModelBaker.ModelKey> byKey = new Object2IntOpenHashMap<>();
    private final Registry<Biome> biomeRegistry;
    private final Biome defaultBiome;
    private final List<Biome> biomes = new ArrayList<>();
    private final List<ModelBaker.Tint> biomeModels = new ArrayList<>();

    // Rows are copy on write and never mutated in place because meshing threads read them lock free
    private volatile int[][] biomeRows = new int[0][];
    private final int[] blendRows = new int[MAX_MODELS];
    private final Int2IntOpenHashMap[] blendSlots = new Int2IntOpenHashMap[MAX_MODELS];
    private final int[] blendCounts = new int[MAX_MODELS];
    private final int[][] blendColours = new int[MAX_MODELS][];
    private volatile boolean blendsUnavailable;
    private final OneBlockLevel tintLevel;
    private final Set<TextureAtlasSprite> animatedSeen = new HashSet<>();
    private boolean warnedModels, warnedTints;

    private sealed interface Ready {}
    private record NewModel(int model, NativeBuffer entry, NativeBuffer texture) implements Ready {}
    private record Tints(int first, int[] colours) implements Ready {}
    private record Mapping(int block, int model) implements Ready {}

    public BlockModels(IdRegistry ids, ModelSink sink) {
        this.ids = ids;
        this.sink = sink;
        Arrays.fill(this.blockModels, -1);
        Arrays.fill(this.blendRows, -1);
        Arrays.fill(this.flowLevels, -1);
        this.blockModels[0] = 0;
        this.assigned.defaultReturnValue(-1);
        this.plantColours.defaultReturnValue(-1);
        this.assigned.put(0, 0);
        this.byKey.defaultReturnValue(-1);

        this.biomeRegistry = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME);
        this.defaultBiome = this.biomeRegistry.getValue(Biomes.PLAINS);
        this.tintLevel = new OneBlockLevel(this.defaultBiome);
        var samples = new ArrayList<Biome>();
        for (var key : List.<ResourceKey<Biome>>of(Biomes.PLAINS, Biomes.SWAMP, Biomes.BADLANDS, Biomes.SNOWY_PLAINS, Biomes.JUNGLE)) {
            var biome = this.biomeRegistry.getValue(key);
            if (biome != null) samples.add(biome);
        }

        var atlas = Minecraft.getInstance().getTextureManager().getTexture(Identifier.withDefaultNamespace("textures/atlas/blocks.png")).getTexture();
        if (atlas.getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException("Expected an RGBA8 block atlas, got " + atlas.getFormat());
        }
        int width = atlas.getWidth(0), height = atlas.getHeight(0);
        this.baker = new ModelBaker(readAtlas(atlas, width, height), width, height, this.defaultBiome, samples.toArray(Biome[]::new));

        this.thread = new Thread(this::runBakes, "Infinite model baking");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private static int[] readAtlas(GpuTexture atlas, int width, int height) {
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        int[] pixels = new int[width * height];
        try (GpuBuffer buffer = device.createBuffer(() -> "Infinite block atlas copy", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, pixels.length * 4L)) {
            encoder.copyTextureToBuffer(atlas, buffer, 0, () -> {}, 0);
            try (var fence = encoder.createFence()) {
                encoder.submit();
                if (!fence.awaitCompletion(GpuFence.NO_TIMEOUT)) {
                    throw new IllegalStateException("The block atlas copy never finished");
                }
            }
            try (var mapped = buffer.map(true, false)) {
                mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer().get(pixels);
            }
        }
        return pixels;
    }

    public int modelOf(int block) {
        return (int) INTS.getAcquire(this.blockModels, block);
    }

    public long info(int model) {
        return this.info[model];
    }

    public int fluidModel(int model) {
        return this.fluidModels[model];
    }

    public int flowLevel(int model) {
        return this.flowLevels[model];
    }

    public int fluidKind(int model) {
        return this.fluidKinds[model];
    }

    public int @Nullable [] blendRow(int model) {
        if (this.blendsUnavailable) return null;
        int row = this.blendRows[model];
        int[][] rows = this.biomeRows;
        return row < 0 || row >= rows.length ? null : rows[row];
    }

    public synchronized int blendSlot(int model, int argb) {
        var slots = this.blendSlots[model];
        if (slots == null) {
            slots = new Int2IntOpenHashMap();
            slots.defaultReturnValue(-1);
            this.blendSlots[model] = slots;
        }
        int slot = slots.get(argb);
        if (slot < 0) {
            int count = this.blendCounts[model];
            if (count < BLEND_SLOTS) {
                slot = count;
                this.blendCounts[model] = count + 1;
            } else {
                slot = closestSlot(slots, argb);
            }
            slots.put(argb, slot);
            if (this.blendColours[model] == null) this.blendColours[model] = new int[BLEND_SLOTS];
            this.blendColours[model][slot] = argb;
            this.ready.add(new Tints(this.blendRows[model] * BIOME_STRIDE + BIOME_LIMIT + slot, new int[]{argb}));
        }
        return BIOME_LIMIT + slot;
    }

    private static int closestSlot(Int2IntOpenHashMap slots, int argb) {
        int red = argb >> 16 & 0xFF, green = argb >> 8 & 0xFF, blue = argb & 0xFF;
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (var entry : slots.int2IntEntrySet()) {
            int colour = entry.getIntKey();
            long dr = (colour >> 16 & 0xFF) - red, dg = (colour >> 8 & 0xFF) - green, db = (colour & 0xFF) - blue;
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getIntValue();
            }
        }
        return best;
    }

    public synchronized int blendColour(int model, int slot) {
        int[] colours = this.blendColours[model];
        return colours == null ? 0 : colours[slot];
    }

    public int blockOf(int model) {
        return this.modelBlocks[model];
    }

    public long portableHash(int model) {
        return this.portableHashes[model];
    }

    public boolean blendsUnavailable() {
        return this.blendsUnavailable;
    }

    public void request(int block) {
        if (block <= 0 || block >= this.ids.blockCount()) {
            if (block != 0) Log.error("Unknown block state id " + block + " (max " + this.ids.blockCount() + ")");
            return;
        }
        if (this.markRequested(block)) {
            this.work.add(() -> this.modelFor(block));
        }
    }

    private boolean markRequested(int block) {
        int word = block >>> 6;
        long bit = 1L << block;
        long old;
        do {
            old = this.requested.get(word);
            if ((old & bit) != 0) return false;
        } while (!this.requested.compareAndSet(word, old, old | bit));
        this.waiting.incrementAndGet();
        return true;
    }

    public void addBiome(IdRegistry.KnownBiome entry) {
        this.work.add(() -> this.biomeAdded(entry));
    }

    private void runBakes() {
        while (this.running) {
            Runnable task;
            try {
                task = this.work.take();
            } catch (InterruptedException e) {
                continue;
            }
            try {
                task.run();
            } catch (RuntimeException | LinkageError e) {
                Log.error("Model baking failed", e);
            }
        }
    }

    private int modelFor(int block) {
        int model = this.assigned.get(block);
        if (model != -1) return model;
        this.markRequested(block);
        BlockState state = null;
        try {
            state = this.ids.blockState(block);
            int cover = this.ids.coverOf(block);
            model = this.bake(state, cover == 0 ? 0 : this.plantColour(cover));
        } catch (RuntimeException | LinkageError e) {
            Log.error("Could not bake model for " + state, e);
            model = 0;
        }
        if (model > 0 && this.modelBlocks[model] == 0) this.modelBlocks[model] = block;
        this.assigned.put(block, model);
        this.ready.add(new Mapping(block, model));
        return model;
    }

    private int plantColour(int block) {
        int colour = this.plantColours.get(block);
        if (colour == -1) {
            try {
                colour = this.baker.plantColour(this.ids.blockState(block));
            } catch (RuntimeException | LinkageError e) {
                Log.error("Could not resolve plant colour for " + this.ids.blockState(block), e);
                colour = 0;
            }
            this.plantColours.put(block, colour);
        }
        return colour;
    }

    private int bake(BlockState state, int cover) {
        if (state.getBlock() instanceof StairBlock stairs) {
            state = stairs.baseState.getBlock().withPropertiesOf(state);
        }
        boolean fluid = state.getBlock() instanceof LiquidBlock;
        int fluidModel = -1;
        if (!fluid && !state.getFluidState().isEmpty()) {
            fluidModel = this.modelFor(this.ids.blockId(state.getFluidState().createLegacyBlock()));
            if (fluidModel == 0) fluidModel = -1;
        }

        var baked = this.baker.bake(state, fluidModel, cover);
        if (baked == null) return 0;
        int existing = this.byKey.getInt(baked.key());
        if (existing != -1) {
            baked.texture().free();
            return existing;
        }
        if (this.modelCount == MAX_MODELS) {
            if (!this.warnedModels) {
                this.warnedModels = true;
                Log.warn("Over " + MAX_MODELS + " block models; extra blocks omitted");
            }
            baked.texture().free();
            return 0;
        }

        int model = this.modelCount;
        long info = baked.info();
        int flags = baked.flags();
        var tint = baked.tint();
        int tintWord = tint.colour();
        if (tint.perBiome()) {
            if (this.biomeModels.size() < MAX_BIOME_MODELS) {
                int row = this.biomeModels.size();
                this.biomeModels.add(tint);
                int[] colours = this.tintRow(tint);
                this.addRow(colours);
                tintWord = row * BIOME_STRIDE;
                this.ready.add(new Tints(tintWord, colours));
                if (fluid) this.blendRows[model] = row;
            } else {
                if (!this.warnedTints) {
                    this.warnedTints = true;
                    Log.warn("Over " + MAX_BIOME_MODELS + " biome-tinted models; extra models use fixed colour");
                }
                info &= ~ModelInfo.BIOME_TINTED;
                flags &= ~ModelBaker.FLAG_BIOME_TINT;
            }
        }
        this.info[model] = info;
        this.fluidModels[model] = fluid ? model : fluidModel;
        this.flowLevels[model] = fluid ? state.getOptionalValue(LiquidBlock.LEVEL).orElse(0) : -1;
        this.fluidKinds[model] = fluid ? BuiltInRegistries.BLOCK.getId(state.getBlock()) : -1;
        this.byKey.put(baked.key(), model);
        long portable = baked.key().portable();
        this.portableHashes[model] = fluidModel > 0 ? Long.rotateLeft(portable, 23) ^ this.portableHashes[fluidModel] * 0x9E3779B97F4A7C15L : portable;
        this.modelCount = model + 1;

        var entry = NativeBuffer.zeroed(MODEL_BYTES);
        for (int face = 0; face < 6; face++) {
            MemoryUtil.memPutInt(entry.address + face * 4L, baked.faces()[face]);
        }
        MemoryUtil.memPutInt(entry.address + 24, flags);
        MemoryUtil.memPutInt(entry.address + 28, tintWord);
        for (int i = 0; i < baked.fluidUv().length; i++) {
            MemoryUtil.memPutFloat(entry.address + FLUID_UV_OFFSET + i * 4L, baked.fluidUv()[i]);
        }
        this.ready.add(new NewModel(model, entry, baked.texture()));
        if (this.animatedSeen.addAll(baked.animated())) {
            this.animatedSprites = this.animatedSeen.toArray(TextureAtlasSprite[]::new);
        }
        return model;
    }

    private int[] tintRow(ModelBaker.Tint tint) {
        int[] row = new int[BIOME_STRIDE];
        int fallback = this.tintIn(tint, this.defaultBiome);
        for (int id = 0; id < BIOME_STRIDE; id++) {
            var biome = id < this.biomes.size() ? this.biomes.get(id) : null;
            row[id] = biome == null ? fallback : this.tintIn(tint, biome);
        }
        Arrays.fill(row, BIOME_LIMIT, BIOME_STRIDE, fallback);
        return row;
    }

    private void addRow(int[] colours) {
        int[][] rows = Arrays.copyOf(this.biomeRows, this.biomeRows.length + 1);
        rows[rows.length - 1] = colours;
        this.biomeRows = rows;
    }

    private int tintIn(ModelBaker.Tint tint, Biome biome) {
        this.tintLevel.reset(tint.state(), biome, null);
        return ModelBaker.colourOf(tint.sources(), tint.state(), this.tintLevel);
    }

    private void biomeAdded(IdRegistry.KnownBiome entry) {
        if (entry.id() < 0 || entry.id() >= BIOME_STRIDE) {
            Log.warn("Biome " + entry.name() + " id " + entry.id() + " exceeds tint limit (" + BIOME_STRIDE + ")");
            return;
        }
        if (entry.id() >= BIOME_LIMIT && !this.blendsUnavailable) {
            this.blendsUnavailable = true;
            Log.warn("Biome " + entry.name() + " id " + entry.id() + " disables fluid tint blending");
        }
        Biome biome = null;
        try {
            biome = this.biomeRegistry.getValue(Identifier.parse(entry.name()));
        } catch (RuntimeException ignored) {
        }
        if (biome == null) {
            Log.warn("Unknown biome " + entry.name() + "; using plains");
            biome = this.defaultBiome;
        }
        while (this.biomes.size() <= entry.id()) this.biomes.add(null);
        if (Objects.equals(this.biomes.set(entry.id(), biome), biome)) return;
        int[][] rows = this.biomeRows.clone();
        for (int row = 0; row < this.biomeModels.size(); row++) {
            int colour = this.tintIn(this.biomeModels.get(row), biome);
            this.ready.add(new Tints(row * BIOME_STRIDE + entry.id(), new int[]{colour}));
            int[] updated = rows[row].clone();
            updated[entry.id()] = colour;
            rows[row] = updated;
        }
        this.biomeRows = rows;
    }

    public int modelCount() {
        return this.modelCount;
    }

    public void tick(int modelLimit) {
        long budget = UPLOAD_BUDGET;
        Ready next;
        while (budget > 0 && (next = this.held != null ? this.held : this.ready.poll()) != null) {
            this.held = null;
            if (next instanceof NewModel model && model.model >= modelLimit) {
                this.held = next;
                return;
            }
            switch (next) {
                case NewModel model -> {
                    model.entry.copyTo(this.sink.modelEntry(model.model));
                    for (int level = 0; level < ModelTextures.LEVELS; level++) {
                        int bytes = (ModelTextures.CELL_WIDTH >> level) * (ModelTextures.CELL_HEIGHT >> level) * 4;
                        MemoryUtil.memCopy(model.texture.address + ModelTextures.levelOffset(level), this.sink.atlasLevel(model.model, level), bytes);
                    }
                    model.entry.free();
                    model.texture.free();
                    budget -= MODEL_BYTES + ModelTextures.BYTES;
                }
                case Tints tints -> {
                    MemoryUtil.memIntBuffer(this.sink.tints(tints.first, tints.colours.length), tints.colours.length).put(tints.colours);
                    budget -= tints.colours.length * 4L;
                }
                case Mapping mapping -> {
                    // Release-store after GPU upload so meshing never observes an un-uploaded model.
                    INTS.setRelease(this.blockModels, mapping.block, mapping.model);
                    this.waiting.decrementAndGet();
                }
            }
        }
    }

    // Required so Sodiums "animate only visible textures" advances sprites used by LODs.
    public void markAnimationsActive() {
        for (var sprite : this.animatedSprites) SpriteUtil.INSTANCE.markSpriteActive(sprite);
    }

    public void addDebugInfo(List<String> lines) {
        int baking = this.waiting.get();
        lines.add("  Models: " + this.modelCount + (baking > 0 ? " (" + baking + " baking)" : ""));
    }

    public void shutdown() {
        this.running = false;
        this.work.add(() -> {});
        try {
            this.thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (this.held != null) this.ready.add(this.held);
        this.held = null;
        for (Ready next; (next = this.ready.poll()) != null; ) {
            if (next instanceof NewModel model) {
                model.entry.free();
                model.texture.free();
            }
        }
    }
}
