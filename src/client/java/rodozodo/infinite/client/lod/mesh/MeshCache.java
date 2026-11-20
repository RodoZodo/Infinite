package rodozodo.infinite.client.lod.mesh;

import rodozodo.infinite.Log;
import rodozodo.infinite.client.model.BlockModels;
import rodozodo.infinite.client.model.ModelInfo;
import rodozodo.infinite.client.render.ViewMotion;
import rodozodo.infinite.storage.LodStorage;
import rodozodo.infinite.storage.LogLodStorage;
import rodozodo.infinite.storage.SectionCompression;
import rodozodo.infinite.util.NativeBuffer;
import rodozodo.infinite.world.LodPos;
import rodozodo.infinite.world.LodSection;
import rodozodo.infinite.world.LodWorld;
import rodozodo.infinite.world.Voxel;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;


// Disk cache for meshed LoD sections using byte shuffled LZ4 records keyed by section and neighbor storage stamps.
// Handles portable model / tint palettes, background compression, and provisional filing for unsaved sections.

final class MeshCache {
    static final boolean ENABLED = !"false".equals(System.getProperty("infinite.meshCache"));
    private static final int CODEC = SectionCompression.LZ4;
    private static final int ZSTD_LEVEL = 3;

    // Bump FORMAT whenever the on disk record layout changes.
    private static final int FORMAT = 1;
    private static final long CODE = codeHash(SectionMesher.class, AmbientOcclusion.class, ModelInfo.class, Voxel.class,
            LodMesh.class, MeshCache.class);

    static final LodMesh WAITING = LodMesh.empty(0, (byte) 0);

    private static final LongAdder HITS = new LongAdder(), ABSENT = new LongAdder(), STALE = new LongAdder(),
            CHANGED = new LongAdder(), REBAKED = new LongAdder(), TURNING = new LongAdder(),
            WAITED = new LongAdder(), FILED = new LongAdder(), UNSETTLED = new LongAdder(),
            LOAD_NANOS = new LongAdder(), FILE_NANOS = new LongAdder(), FILED_BYTES = new LongAdder(), DROPPED = new LongAdder(),
            STORED_BYTES = new LongAdder(), WRITE_NANOS = new LongAdder(), WRITTEN = new LongAdder(),
            EARLY = new LongAdder(), SETTLED = new LongAdder();
    private static final long MOST_QUEUED_BYTES = 64L << 20;

    private final LodWorld world;
    private final BlockModels models;
    private final Path directory;
    private final long settings;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile @Nullable LogLodStorage store;
    private volatile boolean unavailable;
    private volatile boolean closed;
    private volatile boolean stopping;
    private record Filing(long position, long key, byte childMask, int bounds, int discardGroups, int aoTables,
                          int @Nullable [] groupSizes, long @Nullable [] quads, long early) {}

    // Guarded by itself
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Early> early = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final int MOST_EARLY = 200_000;
    private static final AtomicLong EARLY_KEYS = new AtomicLong(java.util.concurrent.ThreadLocalRandom.current().nextLong());
    private final LodWorld.SaveListener saveListener = this::sectionSaved;

    private static final class Early {
        final long provisional;
        final long[] inputs;
        int waiting;

        Early(long provisional, long[] inputs) {
            this.provisional = provisional;
            this.inputs = inputs;
            this.waiting = (int) inputs[INPUTS];
        }
    }

    private static final int INPUTS = 7;

    // Guarded by filings.
    private final ArrayDeque<Filing> filings = new java.util.ArrayDeque<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private @Nullable Thread writer;

    private MeshCache(LodWorld world, BlockModels models, Path directory, boolean cullCaves) {
        this.world = world;
        this.models = models;
        this.directory = directory;
        this.settings = cullCaves ? 0x5DEECE66DL : 0;
    }

    static @Nullable MeshCache open(LodWorld world, BlockModels models, boolean cullCaves) {
        if (!ENABLED) return null;
        var directory = world.storage.directory();
        if (directory == null) return null;
        var cache = new MeshCache(world, models, directory.resolve("meshes"), cullCaves);
        world.setSaveListener(cache.saveListener);
        return cache;
    }

    private @Nullable LogLodStorage store() {
        var store = this.store;
        if (store != null || this.unavailable) return store;
        synchronized (this) {
            if (this.store == null && !this.unavailable) {
                try {
                    // Wait for a previous sessions background shutdown on this directory, unless this instance is stopping
                    if (!this.stopping) MeshBuilder.awaitStopping(this.directory);
                    this.store = new LogLodStorage(this.directory, ZSTD_LEVEL, CODEC);
                } catch (RuntimeException e) {
                    Log.warn("Could not open mesh cache at " + this.directory + "; caching disabled", e);
                    this.unavailable = true;
                }
            }
            return this.store;
        }
    }

    long key(long position) {
        if (this.unavailable || CODE == 0) return 0;
        var storage = this.world.storage;
        long key = CODE ^ this.settings;
        for (int face = -1; face < 6; face++) {
            long at = input(position, face, 1);
            if (this.world.hasUnsavedChanges(at)) {
                UNSETTLED.increment();
                return 0;
            }
            long stamp = storage.stamp(at);
            if (stamp == LodStorage.UNKNOWN_STAMP || face < 0 && stamp == LodStorage.NO_STAMP) return 0;
            key = mix(key ^ stamp) + face;
        }
        return key == 0 ? 1 : key;
    }

    private static long input(long position, int face, int sign) {
        if (face < 0) return position;
        return LodPos.pack(LodPos.level(position),
                LodPos.x(position) + sign * (face == 4 ? -1 : face == 5 ? 1 : 0),
                LodPos.y(position) + sign * (face == 0 ? -1 : face == 1 ? 1 : 0),
                LodPos.z(position) + sign * (face == 2 ? -1 : face == 3 ? 1 : 0));
    }

    private static int slot(int face) {
        return switch (face) {
            case 0 -> 1;
            case 1 -> 0;
            case 2 -> 5;
            case 3 -> 4;
            case 4 -> 3;
            case 5 -> 2;
            default -> LodSection.ALL_EDITS;
        };
    }

    long @Nullable [] inputs(long position) {
        if (this.unavailable || CODE == 0) return null;
        var storage = this.world.storage;
        long[] inputs = new long[INPUTS + 1];
        for (int face = -1; face < 6; face++) {
            long at = input(position, face, 1);
            int edits = this.world.unsavedEdits(at, slot(face));
            if (edits >= 0) {
                inputs[face + 1] = edits;
                inputs[INPUTS] |= 1 << face + 1;
                continue;
            }
            long stamp = storage.stamp(at);
            if (stamp == LodStorage.UNKNOWN_STAMP || face < 0 && stamp == LodStorage.NO_STAMP) return null;
            inputs[face + 1] = stamp;
        }
        return inputs[INPUTS] == 0 ? null : inputs;
    }

    void fileEarly(LodMesh mesh, long[] before) {
        if (!java.util.Arrays.equals(before, this.inputs(mesh.position))) return;
        long provisional = EARLY_KEYS.incrementAndGet();
        if (provisional == 0) return;
        var entry = new Early(provisional, before);
        synchronized (this.early) {
            if (this.early.size() >= MOST_EARLY) return;
            this.early.put(mesh.position, entry);
        }
        // Recheck unsaved edits after inserting into early so concurrent saves cannot be missed
        boolean unheard = false;
        for (int face = -1; face < 6; face++) {
            if ((before[INPUTS] & 1 << face + 1) != 0 && this.world.unsavedEdits(input(mesh.position, face, 1), slot(face)) != before[face + 1]) {
                unheard = true;
            }
        }
        if (unheard || !this.save(mesh, provisional)) {
            synchronized (this.early) {
                this.early.remove(mesh.position, entry);
            }
            return;
        }
        EARLY.increment();
    }

    private void sectionSaved(long saved, int[] edits) {
        synchronized (this.early) {
            if (this.early.isEmpty()) return;
        }
        long stamp = this.world.storage.stamp(saved);
        for (int face = -1; face < 6; face++) {
            long position = input(saved, face, -1);
            int bit = 1 << face + 1;
            Early settled;
            synchronized (this.early) {
                var entry = this.early.get(position);
                if (entry == null) continue;
                if ((entry.waiting & bit) == 0 || entry.inputs[face + 1] != edits[slot(face)] || stamp == LodStorage.UNKNOWN_STAMP) {
                    this.early.remove(position);
                    continue;
                }
                entry.inputs[face + 1] = stamp;
                entry.waiting &= ~bit;
                if (entry.waiting != 0) continue;
                this.early.remove(position);
                settled = entry;
            }
            long key = this.key(position);
            if (key == 0) continue;
            boolean same = true;
            for (int input = -1; input < 6 && same; input++) {
                same = this.world.storage.stamp(input(position, input, 1)) == settled.inputs[input + 1];
            }
            if (same) this.rekey(position, settled.provisional, key);
        }
    }

    private void rekey(long position, long provisional, long key) {
        synchronized (this.filings) {
            if (this.closed) return;
            this.queuedBytes.addAndGet(64);
            this.file(new Filing(position, key, (byte) 0, 0, 0, 0, null, null, provisional));
        }
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Nullable LodMesh load(long position, long key) {
        if (key == 0) return null;
        if (!ViewMotion.still()) {
            TURNING.increment();
            return null;
        }
        long started = System.nanoTime();
        var scratch = Scratch.get();
        var bytes = scratch.bytes;
        this.lock.readLock().lock();
        try {
            if (this.closed) return null;
            var store = this.store();
            if (store == null || store.loadBytes(position, bytes) != LodStorage.LOADED) {
                ABSENT.increment();
                return null;
            }
        } finally {
            this.lock.readLock().unlock();
        }
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        var mesh = this.decode(position, key, bytes, scratch);
        if (mesh == null) {
            STALE.increment();
        } else if (mesh == WAITING) {
            WAITED.increment();
        } else {
            HITS.increment();
            LOAD_NANOS.add(System.nanoTime() - started);
        }
        return mesh;
    }

    boolean has(long position, long key) {
        return this.filedChildMask(position, key) >= 0;
    }

    int filedChildMask(long position, long key) {
        if (key == 0) return -1;
        var bytes = Scratch.get().bytes;
        this.lock.readLock().lock();
        try {
            if (this.closed) return -1;
            var store = this.store();
            if (store == null || store.loadBytes(position, bytes) != LodStorage.LOADED) return -1;
        } finally {
            this.lock.readLock().unlock();
        }
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.remaining() < 13 || bytes.getInt() != FORMAT || bytes.getLong() != key) return -1;
        return bytes.get() & 0xFF;
    }

    private @Nullable LodMesh decode(long position, long key, ByteBuffer bytes, Scratch scratch) {
        if (bytes.remaining() < 14 || bytes.getInt() != FORMAT || bytes.getLong() != key) {
            CHANGED.increment();
            return null;
        }
        byte childMask = bytes.get();
        if (bytes.get() == 0) return LodMesh.empty(position, childMask);
        int bounds = bytes.getInt(), discardGroups = bytes.getInt(), aoTables = bytes.getInt();
        int[] groupSizes = new int[LodMesh.GROUPS];
        int quads = 0;
        for (int group = 0; group < LodMesh.GROUPS; group++) {
            groupSizes[group] = bytes.getInt();
            quads += groupSizes[group];
        }
        int slots = bytes.getInt();
        int paletteSize = bytes.getShort() & 0xFFFF;
        if (slots < quads || slots <= 0) return null;

        int[] models = scratch.models(paletteSize);
        boolean waiting = false;
        for (int i = 0; i < paletteSize; i++) {
            int block = bytes.getInt();
            long hash = bytes.getLong();
            int model = this.models.modelOf(block);
            if (model < 0) {
                this.models.request(block);
                waiting = true;
                continue;
            }
            if (this.models.portableHash(model) != hash) {
                REBAKED.increment();
                return null;
            }
            models[i] = model;
        }
        if (waiting) return WAITING;

        int blends = bytes.getShort() & 0xFFFF;
        var blendSlots = scratch.blendSlots;
        blendSlots.clear();
        if (blends > 0 && this.models.blendsUnavailable()) return null;
        for (int i = 0; i < blends; i++) {
            int index = bytes.getShort() & 0xFFFF, slot = bytes.get() & 0xFF, argb = bytes.getInt();
            if (index >= paletteSize || this.models.blendRow(models[index]) == null) return null;
            blendSlots.put(index << 8 | slot, this.models.blendSlot(models[index], argb));
        }
        if (bytes.remaining() != slots * 8) return null;
        long[] filed = scratch.unshuffle(bytes, slots);

        var buffer = new NativeBuffer(slots * 8L);
        long address = buffer.address;
        for (int i = 0; i < quads; i++) {
            long quad = filed[i];
            int high = (int) (quad >>> 32);
            int index = high & 0xFFFF;
            int biome = high >>> 16 & 0x1FF;
            if (index >= paletteSize) {
                buffer.free();
                return null;
            }
            if (biome >= BlockModels.BIOME_LIMIT) {
                biome = blendSlots.get(index << 8 | biome - BlockModels.BIOME_LIMIT);
                if (biome < 0) {
                    buffer.free();
                    return null;
                }
            }
            high = high & ~0x1FFFFFF | biome << 16 | models[index];
            MemoryUtil.memPutLong(address + i * 8L, quad & 0xFFFFFFFFL | (long) high << 32);
        }
        for (int i = quads; i < slots; i++) MemoryUtil.memPutLong(address + i * 8L, filed[i]);
        return new LodMesh(position, childMask, bounds, discardGroups, buffer, groupSizes, aoTables);
    }

    boolean save(LodMesh mesh, long key) {
        if (key == 0 || this.unavailable) return false;
        long started = System.nanoTime();
        long[] quads = null;
        int[] groupSizes = null;
        if (!mesh.isEmpty()) {
            quads = new long[mesh.quadCount()];
            MemoryUtil.memLongBuffer(mesh.quads().address, quads.length).get(quads);
            groupSizes = new int[LodMesh.GROUPS];
            for (int group = 0; group < LodMesh.GROUPS; group++) groupSizes[group] = mesh.groupSize(group);
        }
        long bytes = quads == null ? 64 : quads.length * 8L + 64;
        if (this.queuedBytes.addAndGet(bytes) > MOST_QUEUED_BYTES) {
            this.queuedBytes.addAndGet(-bytes);
            DROPPED.increment();
            return false;
        }
        synchronized (this.filings) {
            if (this.closed) {
                this.queuedBytes.addAndGet(-bytes);
                return false;
            }
            this.file(new Filing(mesh.position, key, mesh.childMask, mesh.bounds, mesh.discardGroups, mesh.aoTables,
                    groupSizes, quads, 0));
        }
        FILED.increment();
        FILE_NANOS.add(System.nanoTime() - started);
        return true;
    }

    // Must hold filings lock.
    private void file(Filing filing) {
        this.filings.add(filing);
        if (this.writer == null) this.writer = Thread.ofPlatform().daemon().name("Infinite LOD mesh filing")
                .priority(Thread.MIN_PRIORITY).start(this::writeFilings);
        this.filings.notify();
    }

    private void writeFilings() {
        var scratch = Scratch.get();
        while (true) {
            Filing filing;
            synchronized (this.filings) {
                while (this.filings.isEmpty() && !this.closed) {
                    try {
                        this.filings.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
                filing = this.filings.poll();
                if (filing == null) {
                    this.filings.notifyAll();
                    return;
                }
            }
            this.queuedBytes.addAndGet(filing.quads == null ? -64 : -(filing.quads.length * 8L + 64));
            if (filing.early != 0) {
                this.writeKey(filing, scratch);
                continue;
            }
            try {
                long started = System.nanoTime();
                var bytes = scratch.bytes.order(ByteOrder.LITTLE_ENDIAN).clear();
                bytes.putInt(FORMAT).putLong(filing.key).put(filing.childMask).put((byte) (filing.quads == null ? 0 : 1));
                if (filing.quads != null && !this.encode(filing, bytes, scratch)) continue;
                int length = bytes.position();
                var store = this.store();
                if (store == null) continue;
                FILED_BYTES.add(length);
                STORED_BYTES.add(store.saveBytes(filing.position, bytes, length));
                WRITE_NANOS.add(System.nanoTime() - started);
                WRITTEN.increment();
            } catch (RuntimeException e) {
                Log.warn("Could not cache mesh " + LodPos.describe(filing.position), e);
            }
        }
    }

    private void writeKey(Filing filing, Scratch scratch) {
        try {
            var store = this.store();
            if (store == null) return;
            var bytes = scratch.bytes.order(ByteOrder.LITTLE_ENDIAN).clear();
            if (store.loadBytes(filing.position, bytes) != LodStorage.LOADED) return;
            if (bytes.limit() < 12 || bytes.getInt(0) != FORMAT || bytes.getLong(4) != filing.early) return;
            bytes.putLong(4, filing.key);
            store.saveBytes(filing.position, bytes, bytes.limit());
            SETTLED.increment();
        } catch (RuntimeException e) {
            Log.warn("Could not update mesh key " + LodPos.describe(filing.position), e);
        }
    }

    private boolean encode(Filing filing, ByteBuffer bytes, Scratch scratch) {
        int quads = 0;
        for (int size : filing.groupSizes) quads += size;
        long[] source = filing.quads;
        int slots = source.length;
        int epoch = scratch.nextEpoch();
        var paletteModels = scratch.paletteModels;
        int paletteSize = 0;
        var blends = scratch.blendSlots;
        blends.clear();
        long[] filed = scratch.quads(slots);
        for (int i = 0; i < quads; i++) {
            long quad = source[i];
            int high = (int) (quad >>> 32);
            int model = high & 0xFFFF;
            if (scratch.seen[model] != epoch) {
                if (model != 0 && this.models.blockOf(model) == 0) return false;
                scratch.seen[model] = epoch;
                scratch.index[model] = paletteSize;
                paletteModels[paletteSize++] = model;
            }
            int index = scratch.index[model];
            int biome = high >>> 16 & 0x1FF;
            if (biome >= BlockModels.BIOME_LIMIT) blends.putIfAbsent(index << 8 | biome - BlockModels.BIOME_LIMIT, model);
            filed[i] = quad & 0xFFFFFFFFL | (long) (high & ~0xFFFF | index) << 32;
        }
        System.arraycopy(source, quads, filed, quads, slots - quads);
        int size = bytes.position() + 12 + LodMesh.GROUPS * 4 + 4 + 2 + paletteSize * 12 + 2 + blends.size() * 7 + slots * 8;
        if (size > bytes.capacity()) return false;

        bytes.putInt(filing.bounds).putInt(filing.discardGroups).putInt(filing.aoTables);
        for (int group = 0; group < LodMesh.GROUPS; group++) bytes.putInt(filing.groupSizes[group]);
        bytes.putInt(slots).putShort((short) paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            int model = paletteModels[i];
            bytes.putInt(this.models.blockOf(model)).putLong(this.models.portableHash(model));
        }
        bytes.putShort((short) blends.size());
        for (var entry : blends.int2IntEntrySet()) {
            int indexAndSlot = entry.getIntKey();
            bytes.putShort((short) (indexAndSlot >>> 8)).put((byte) indexAndSlot)
                    .putInt(this.models.blendColour(entry.getIntValue(), indexAndSlot & 0xFF));
        }
        scratch.shuffle(bytes, slots);
        return true;
    }

    static void unsettled() {
        UNSETTLED.increment();
    }

    void close() {
        this.world.removeSaveListener(this.saveListener);
        Thread writer;
        synchronized (this.filings) {
            this.closed = true;
            this.filings.notifyAll();
            writer = this.writer;
        }
        if (writer != null) {
            try {
                writer.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        this.lock.writeLock().lock();
        try {
            this.closed = true;
            var store = this.store;
            if (store != null) store.close();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    Path directory() {
        return this.directory;
    }

    void stopping() {
        this.stopping = true;
    }

    static @Nullable String describe() {
        if (!ENABLED) return null;
        long hits = HITS.sum(), filed = FILED.sum(), written = WRITTEN.sum();
        return String.format(Locale.ROOT,
                "Mesh cache: %d loaded (%.1f us each), %d not there, %d stale (%d sections changed, %d models), %d waited for"
                        + " models, %d built while turning; %d filed (%.1f us laying out"
                        + " %.1f KiB, %.1f us writing %.1f KiB), %d dropped, %d not filed while unsaved (%d of them"
                        + " filed early, %d settled since)",
                hits, hits == 0 ? 0 : LOAD_NANOS.sum() / (double) hits / 1e3, ABSENT.sum(),
                STALE.sum(), CHANGED.sum(), REBAKED.sum(), WAITED.sum(), TURNING.sum(),
                filed, filed == 0 ? 0 : FILE_NANOS.sum() / (double) filed / 1e3,
                filed == 0 ? 0 : FILED_BYTES.sum() / (double) filed / 1024,
                written == 0 ? 0 : WRITE_NANOS.sum() / (double) written / 1e3,
                written == 0 ? 0 : STORED_BYTES.sum() / (double) written / 1024, DROPPED.sum(), UNSETTLED.sum(),
                EARLY.sum(), SETTLED.sum());
    }

    private static long codeHash(Class<?>... classes) {
        long hash = FORMAT;
        for (var type : classes) {
            try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                if (in == null) throw new IOException("no class file");
                for (byte b : in.readAllBytes()) hash = (hash ^ b) * 0x100000001B3L;
            } catch (IOException e) {
                Log.warn("Could not hash " + type.getName() + "; mesh cache disabled", e);
                return 0;
            }
        }
        return mix(hash);
    }

    private static final class Scratch {
        private static final ThreadLocal<Scratch> LOCAL = ThreadLocal.withInitial(Scratch::new);

        final ByteBuffer bytes = ByteBuffer.allocateDirect(LogLodStorage.MAX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final int[] seen = new int[BlockModels.MAX_MODELS], index = new int[BlockModels.MAX_MODELS];
        final int[] paletteModels = new int[BlockModels.MAX_MODELS];
        final Int2IntOpenHashMap blendSlots = new Int2IntOpenHashMap();
        private int[] models = new int[64];
        private long[] quads = new long[4096];
        private byte[] planes = new byte[4096 * 8];
        private int epoch;

        Scratch() {
            this.blendSlots.defaultReturnValue(-1);
        }

        static Scratch get() {
            return LOCAL.get();
        }

        int nextEpoch() {
            return ++this.epoch;
        }

        int[] models(int size) {
            if (this.models.length < size) this.models = new int[size];
            return this.models;
        }

        long[] quads(int size) {
            if (this.quads.length < size) this.quads = new long[size];
            return this.quads;
        }

        private byte[] planes(int size) {
            if (this.planes.length < size) this.planes = new byte[size];
            return this.planes;
        }

        void shuffle(ByteBuffer bytes, int slots) {
            byte[] planes = this.planes(slots * 8);
            long[] quads = this.quads;
            for (int b = 0; b < 8; b++) {
                int base = b * slots, shift = b * 8;
                for (int i = 0; i < slots; i++) planes[base + i] = (byte) (quads[i] >>> shift);
            }
            bytes.put(planes, 0, slots * 8);
        }

        long[] unshuffle(ByteBuffer bytes, int slots) {
            byte[] planes = this.planes(slots * 8);
            bytes.get(planes, 0, slots * 8);
            long[] quads = this.quads(slots);
            for (int i = 0; i < slots; i++) quads[i] = planes[i] & 0xFFL;
            for (int b = 1; b < 8; b++) {
                int base = b * slots, shift = b * 8;
                for (int i = 0; i < slots; i++) quads[i] |= (planes[base + i] & 0xFFL) << shift;
            }
            return quads;
        }

    }
}
