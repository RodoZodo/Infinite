package rodozodo.infinite.client.ingest;

import rodozodo.infinite.Infinite;
import rodozodo.infinite.LodWorldKey;
import rodozodo.infinite.client.compat.SodiumBackpressure;
import rodozodo.infinite.client.hook.minecraft.ClientChunkCacheAccess;
import rodozodo.infinite.world.voxelize.IngestQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

// Schedules client-side chunk and section ingestion into the LOD engine as Sodium marks chunks
public final class ClientIngest {
    private static final int CHANGED_SECTION_DELAY = 20;
    private static final LongOpenHashSet DEFERRED_CHUNKS = new LongOpenHashSet();
    private static @Nullable ClientLevel deferredLevel;
    private static final int MAX_DEFERRED_CHUNKS = 512;
    private static final int DEFERRED_PER_TICK = 2;
    private static final int DEFERRED_QUEUE_LIMIT = 128;
    private static final int IMMEDIATE_QUEUE_LIMIT = 64;

    private static final LongOpenHashSet CHANGED_SECTIONS = new LongOpenHashSet();
    private static int ticksSinceFlush;
    private static final LongOpenHashSet READY_CHUNKS = new LongOpenHashSet();
    private static @Nullable ClientLevel readyLevel;

    private ClientIngest() {}

    public static void chunkReady(ClientLevel level, int x, int z) {
        if (readyLevel != level) {
            READY_CHUNKS.clear();
            readyLevel = level;
        }
        READY_CHUNKS.add(key(x, z));
        if (!SodiumBackpressure.ingestMayRun()) {
            defer(level, x, z);
            return;
        }
        var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk != null && !IngestQueue.ingestChunk(chunk)) defer(level, x, z);
    }

    public static void chunkNoLongerReady(ClientLevel level, int x, int z) {
        if (readyLevel == level) READY_CHUNKS.remove(key(x, z));
        var chunk = loadedChunk(level, x, z);
        if (chunk != null) IngestQueue.ingestChunk(chunk);
    }

    public static void blockChanged(BlockPos pos) {
        CHANGED_SECTIONS.add(SectionPos.asLong(pos));
    }

    public static void sectionBuilt(ClientLevel level, int x, int y, int z, boolean rebuild) {
        if (!rebuild) return;
        var id = LodWorldKey.of(level);
        if (id == null) return;
        long key = SectionPos.asLong(x, y, z);
        var engine = Infinite.engine();
        if (engine != null && engine.ingestQueue().pending() < IMMEDIATE_QUEUE_LIMIT && ingestNow(level, id, x, y, z)) {
            CHANGED_SECTIONS.remove(key);
        } else {
            CHANGED_SECTIONS.add(key);
        }
    }

    public static void tick(Minecraft minecraft) {
        drainDeferred(minecraft);
        if (CHANGED_SECTIONS.isEmpty() || ++ticksSinceFlush < CHANGED_SECTION_DELAY) return;
        ticksSinceFlush = 0;
        var level = minecraft.level;
        var id = level == null ? null : LodWorldKey.of(level);
        if (id != null) {
            for (var it = CHANGED_SECTIONS.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                ingestNow(level, id, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
            }
        }
        CHANGED_SECTIONS.clear();
    }

    private static boolean ingestNow(ClientLevel level, LodWorldKey id, int x, int y, int z) {
        var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null) return false;

        // Wait until Sodium marks the chunk ready.
        // IngestQueue.lightReady alone can miss pending sky light.
        if (readyLevel != level || !READY_CHUNKS.contains(key(x, z))) return false;
        int index = chunk.getSectionIndexFromSectionY(y);
        if (index < 0 || index >= chunk.getSectionsCount()) return false;
        var pos = SectionPos.of(x, y, z);
        var light = level.getLightEngine();
        return IngestQueue.ingestSection(id, chunk.getSection(index), x, y, z,
                light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos),
                light.getLayerListener(LightLayer.SKY).getDataLayerData(pos));
    }

    private static void drainDeferred(Minecraft minecraft) {
        var level = minecraft.level;
        if (level == null || level != deferredLevel) {
            DEFERRED_CHUNKS.clear();
            deferredLevel = null;
            return;
        }
        if (DEFERRED_CHUNKS.isEmpty() || !SodiumBackpressure.ingestMayRun()) return;
        var engine = Infinite.engine();
        if (engine == null || engine.ingestQueue().pending() >= DEFERRED_QUEUE_LIMIT) return;
        var it = DEFERRED_CHUNKS.iterator();
        for (int read = 0; read < DEFERRED_PER_TICK && it.hasNext(); read++) {
            long key = it.nextLong();
            int x = (int) (key >> 32), z = (int) key;
            var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk != null && !IngestQueue.ingestChunk(chunk)) return;
            it.remove();
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFF_FFFFL);
    }

    private static void defer(ClientLevel level, int x, int z) {
        if (deferredLevel != level) {
            DEFERRED_CHUNKS.clear();
            deferredLevel = level;
        }
        if (DEFERRED_CHUNKS.size() >= MAX_DEFERRED_CHUNKS) return;
        DEFERRED_CHUNKS.add(key(x, z));
    }

    private static @Nullable LevelChunk loadedChunk(ClientLevel level, int x, int z) {
        var storage = ((ClientChunkCacheAccess) level.getChunkSource()).infinite$storage();
        var chunk = storage.getChunk(storage.getIndex(x, z));
        // Storage slots wrap around, so verify the chunk coordinates match.
        return chunk != null && chunk.getPos().x() == x && chunk.getPos().z() == z ? chunk : null;
    }
}
