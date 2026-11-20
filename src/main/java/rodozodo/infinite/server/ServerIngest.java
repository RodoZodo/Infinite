package rodozodo.infinite.server;

import rodozodo.infinite.Infinite;
import rodozodo.infinite.Log;
import rodozodo.infinite.world.voxelize.IngestQueue;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class ServerIngest {
    private static final int RETRY_TICKS = 5;
    private static final int MAX_TRIES = 8;

    private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> PENDING = new HashMap<>();
    private static final Map<ResourceKey<Level>, LongOpenHashSet> QUEUED = new HashMap<>();
    private static int ticksUntilRetry;

    @FunctionalInterface
    public interface Filter {
        boolean ingest(ServerLevel level, ChunkPos pos, boolean unloading);
    }

    private static volatile @Nullable Filter filter;

    private ServerIngest() {}

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register(ServerIngest::chunkLoaded);
        ServerChunkEvents.CHUNK_UNLOAD.register(ServerIngest::chunkUnloaded);
        ServerTickEvents.END_SERVER_TICK.register(ServerIngest::tick);
    }

    public static void filter(@Nullable Filter chosen) {
        filter = chosen;
    }

    private static void chunkLoaded(ServerLevel level, LevelChunk chunk, boolean generated) {
        LoadedChunks.of(level.dimension()).loaded(chunk.getPos().pack());
        if (Infinite.engine() == null) return;
        var chosen = filter;
        if (chosen != null && !chosen.ingest(level, chunk.getPos(), false)) return;
        try {
            if (!IngestQueue.lightReady(chunk) || !IngestQueue.ingestChunk(chunk)) {
                pending(level, chunk.getPos().pack());
            } else {
                markQueued(level, chunk.getPos().pack());
            }
        } catch (RuntimeException | LinkageError e) {
            Log.error("Could not read loaded chunk into LoD's", e);
        }
    }

    private static void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        forget(level, chunk.getPos().pack());
        forgetQueued(level.dimension(), chunk.getPos().pack());
        LoadedChunks.of(level.dimension()).unloaded(chunk.getPos().pack());
        if (Infinite.engine() == null) return;
        var chosen = filter;
        if (chosen != null && !chosen.ingest(level, chunk.getPos(), true)) return;
        try {
            IngestQueue.ingestChunk(chunk);
        } catch (RuntimeException | LinkageError e) {
            Log.error("Could not read unloaded chunk into LoD's", e);
        }
    }

    private static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        if (--ticksUntilRetry > 0) return;
        ticksUntilRetry = RETRY_TICKS;
        for (var entry : PENDING.entrySet()) {
            var level = server.getLevel(entry.getKey());
            if (level == null) {
                entry.getValue().clear();
                continue;
            }
            var chunks = entry.getValue();
            for (var it = chunks.long2IntEntrySet().iterator(); it.hasNext(); ) {
                var waiting = it.next();
                long key = waiting.getLongKey();
                var chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (chunk == null) {
                    it.remove();
                    continue;
                }
                int tries = waiting.getIntValue();
                if (!IngestQueue.lightReady(chunk) && tries > 0) {
                    waiting.setValue(tries - 1);
                    continue;
                }
                if (!IngestQueue.ingestChunk(chunk)) return;
                markQueued(level, key);
                it.remove();
            }
        }
    }

    private static void pending(ServerLevel level, long key) {
        PENDING.computeIfAbsent(level.dimension(), dimension -> new Long2IntOpenHashMap()).put(key, MAX_TRIES);
        if (ticksUntilRetry <= 0) ticksUntilRetry = RETRY_TICKS;
    }

    private static void forget(ServerLevel level, long key) {
        Long2IntMap waiting = PENDING.get(level.dimension());
        if (waiting != null) waiting.remove(key);
    }

    private static void markQueued(ServerLevel level, long key) {
        QUEUED.computeIfAbsent(level.dimension(), dimension -> new LongOpenHashSet()).add(key);
    }

    private static void forgetQueued(ResourceKey<Level> dimension, long key) {
        LongOpenHashSet chunks = QUEUED.get(dimension);
        if (chunks != null) chunks.remove(key);
    }

    static boolean isQueued(ResourceKey<Level> dimension, long key) {
        LongOpenHashSet chunks = QUEUED.get(dimension);
        return chunks != null && chunks.contains(key);
    }

    public static void clear() {
        PENDING.clear();
        QUEUED.clear();
        ticksUntilRetry = 0;
    }
}
