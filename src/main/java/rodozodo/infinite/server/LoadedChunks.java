package rodozodo.infinite.server;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class LoadedChunks {
    private static final Map<ResourceKey<Level>, LoadedChunks> BY_DIMENSION = new ConcurrentHashMap<>();

    private final LongOpenHashSet loaded = new LongOpenHashSet();
    private final LongOpenHashSet kept = new LongOpenHashSet();
    private int holds;

    static LoadedChunks of(ResourceKey<Level> dimension) {
        return BY_DIMENSION.computeIfAbsent(dimension, ignored -> new LoadedChunks());
    }

    static void clear() {
        BY_DIMENSION.clear();
    }

    synchronized void loaded(long chunk) {
        this.loaded.add(chunk);
    }

    synchronized void unloaded(long chunk) {
        if (this.loaded.remove(chunk) && this.holds > 0) this.kept.add(chunk);
    }

    synchronized boolean contains(long chunk) {
        return this.loaded.contains(chunk) || this.kept.contains(chunk);
    }

    synchronized void hold() {
        this.holds++;
    }

    synchronized void release() {
        if (this.holds > 0 && --this.holds == 0) {
            this.kept.clear();
            this.kept.trim();
        }
    }
}