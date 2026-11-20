package rodozodo.infinite.world.voxelize;

import rodozodo.infinite.Infinite;
import rodozodo.infinite.LodWorldKey;
import rodozodo.infinite.Log;
import rodozodo.infinite.thread.JobQueue;
import rodozodo.infinite.thread.WorkerPool;
import rodozodo.infinite.world.IdRegistry;
import rodozodo.infinite.world.LodWorld;
import rodozodo.infinite.world.SectionWriter;
import rodozodo.infinite.world.Voxel;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;


public final class IngestQueue {
    public static final int DEFAULT_MAX_QUEUED = 2048;
    private static final int MAX_POOLED = 512;
    private static final ThreadLocal<VoxelChunk> CHUNKS = ThreadLocal.withInitial(VoxelChunk::new);
    private static final int EMPTY_LIGHT = Voxel.lightOf(15, 0);
    private static final int LIGHT_BYTES = 16 * 16 * 16 / 2;
    private static final long[] NO_PACKED = new long[0];

    static final class Copy {
        Palette<BlockState> palette;
        long[] packed = NO_PACKED;
        int bits;
        final Holder<Biome>[] biomes = new Holder[64];
        final DataLayer blockLight = new DataLayer(new byte[LIGHT_BYTES]);
        final DataLayer skyLight = new DataLayer(new byte[LIGHT_BYTES]);

        void fill(BitStorage storage, Palette<BlockState> palette,
                  @Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {
            this.palette = palette;
            this.bits = storage.getBits();
            int packed = packedLength(this.bits);
            if (this.packed.length != packed) this.packed = packed == 0 ? NO_PACKED : new long[packed];
            if (packed != 0) System.arraycopy(storage.getRaw(), 0, this.packed, 0, packed);
            lightInto(this.blockLight, blockLight);
            lightInto(this.skyLight, skyLight);
        }

        void fillBiomes(PalettedContainerRO<Holder<Biome>> biomes) {
            for (int i = 0; i < 64; i++) this.biomes[i] = biomes.get(i & 3, i >> 4, i >> 2 & 3);
        }

        VoxelChunk voxelize(VoxelChunk out, IdRegistry ids) {
            var storage = this.bits == 0 ? null : new SimpleBitStorage(this.bits, VoxelChunk.VOLUME, this.packed);
            return ChunkVoxelizer.voxelize(out, ids, storage, this.palette, this.biomes, this.blockLight, this.skyLight);
        }
    }

    private record Job(LodWorld world, int x, int y, int z, @Nullable Copy copy) {}

    private final JobQueue jobs;
    private final ConcurrentLinkedQueue<Job> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Copy> free = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pooled = new AtomicInteger();
    private final int maxQueued;

    public IngestQueue(WorkerPool workers, BooleanSupplier mayRun) {
        this(workers, mayRun, DEFAULT_MAX_QUEUED);
    }

    public IngestQueue(WorkerPool workers, BooleanSupplier mayRun, int maxQueued) {
        this.jobs = workers.newQueue("LOD ingest", 5000, mayRun, () -> this::runNext);
        this.maxQueued = Math.max(1, maxQueued);
    }

    public void setThreadShare(int threads) {
        this.jobs.setThreadShare(threads);
    }

    private boolean runNext() {
        var job = this.queue.poll();
        if (job == null) return true;
        var copy = job.copy;
        try {
            var chunk = CHUNKS.get().moveTo(job.x, job.y, job.z);
            if (copy == null) {
                chunk.fillAir(EMPTY_LIGHT);
            } else {
                copy.voxelize(chunk, job.world.ids());
                chunk.downsample(job.world.ids());
            }
            SectionWriter.write(job.world, chunk);
        } finally {
            recycle(copy);
            job.world.releaseRef();
        }
        return true;
    }

    private Copy copyOf(LevelChunkSection section, @Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {
        var copy = take();
        var states = section.getStates();
        copy.fill(states.data.storage, states.data.palette, blockLight, skyLight);
        copy.fillBiomes(section.getBiomes());
        return copy;
    }

    private static int packedLength(int bits) {
        if (bits == 0) return 0;
        int perLong = 64 / bits;
        return (VoxelChunk.VOLUME + perLong - 1) / perLong;
    }

    private static void lightInto(DataLayer into, @Nullable DataLayer from) {
        byte[] data = into.getData();
        if (from == null) Arrays.fill(data, (byte) 0);
        else System.arraycopy(from.getData(), 0, data, 0, data.length);
    }

    Copy take() {
        var copy = this.free.poll();
        if (copy == null) return new Copy();
        this.pooled.decrementAndGet();
        return copy;
    }

    void recycle(@Nullable Copy copy) {
        if (copy == null || this.pooled.get() >= MAX_POOLED) return;
        this.pooled.incrementAndGet();
        this.free.add(copy);
    }

    private boolean enqueue(LodWorld world, int x, int y, int z, @Nullable LevelChunkSection section,
                            @Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {
        if (!this.jobs.isLive() || this.jobs.pending() >= this.maxQueued) return false;
        boolean empty = section == null || section.hasOnlyAir() && blockLight == null && skyLight == null;
        world.acquireRef();
        this.queue.add(new Job(world, x, y, z, empty ? null : copyOf(section, blockLight, skyLight)));
        // Release jobs missed by a concurrent shutdown
        if (!this.jobs.submit()) this.dropQueued();
        return true;
    }

    public boolean enqueue(LodWorld world, LevelChunk chunk) {
        if (!this.jobs.isLive()) return true;
        var sections = chunk.getSections();
        int count = 0;
        for (var section : sections) {
            if (section != null) count++;
        }
        if (this.jobs.pending() + count > this.maxQueued) return false;
        world.markActive();
        var light = chunk.getLevel().getLightEngine();
        int bottom = chunk.getMinSectionY();
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();

        boolean anyLit = false, allAir = true;
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) continue;
            allAir &= sections[i].hasOnlyAir();
            var pos = SectionPos.of(cx, bottom + i, cz);
            anyLit |= light.getDebugSectionType(LightLayer.SKY, pos) == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA
                    || light.getDebugSectionType(LightLayer.BLOCK, pos) == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA;
        }

        if (!anyLit) {
            if (allAir) {
                for (int i = 0; i < sections.length; i++) {
                    if (sections[i] != null) this.enqueue(world, cx, bottom + i, cz, null, null, null);
                }
            }
            return true;
        }

        var blockLayers = light.getLayerListener(LightLayer.BLOCK);
        var skyLayers = light.getLayerListener(LightLayer.SKY);
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) continue;
            var pos = SectionPos.of(cx, bottom + i, cz);
            if (!this.enqueue(world, cx, bottom + i, cz, sections[i],
                    blockLayers.getDataLayerData(pos), skyLayers.getDataLayerData(pos))) break;
        }
        return true;
    }

    public static boolean lightReady(LevelChunk chunk) {
        var light = chunk.getLevel().getLightEngine();
        int bottom = chunk.getMinSectionY();
        var sections = chunk.getSections();
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) continue;
            var pos = SectionPos.of(cx, bottom + i, cz);
            if (light.getDebugSectionType(LightLayer.SKY, pos) == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA
                    || light.getDebugSectionType(LightLayer.BLOCK, pos) == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA) {
                return true;
            }
        }
        return false;
    }

    public int pending() {
        return this.jobs.pending();
    }

    public void shutdown() {
        this.jobs.shutdown();
        this.dropQueued();
    }

    private void dropQueued() {
        for (Job job; (job = this.queue.poll()) != null; ) {
            recycle(job.copy);
            job.world.releaseRef();
        }
    }

    public static boolean ingestChunk(LevelChunk chunk) {
        var id = LodWorldKey.of(chunk.getLevel());
        var engine = Infinite.engine();
        if (id == null || engine == null || !engine.ingestEnabled()) return true;
        var world = engine.world(id);
        if (world == null) return true;
        return engine.ingestQueue().enqueue(world, chunk);
    }

    public static boolean ingestSection(@Nullable LodWorldKey id, LevelChunkSection section, int x, int y, int z,
                                        @Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {
        if (id == null) return false;
        var world = Infinite.world(id);
        return world != null && ingestSection(world, section, x, y, z, blockLight, skyLight);
    }

    public static boolean ingestSection(LodWorld world, LevelChunkSection section, int x, int y, int z,
                                        @Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {
        var engine = world.engine;
        if (engine == null || !engine.ingestEnabled()) return false;
        try {
            return engine.ingestQueue().enqueue(world, x, y, z, section, blockLight, skyLight);
        } catch (IllegalStateException e) {
            Log.error("Could not queue LoD section", e);
            return false;
        }
    }
}