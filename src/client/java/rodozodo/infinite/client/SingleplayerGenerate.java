package rodozodo.infinite.client;

import rodozodo.infinite.client.compat.GenerateProgressBar;
import rodozodo.infinite.server.ServerGenerate;
import rodozodo.infinite.server.ServerIngest;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;


public final class SingleplayerGenerate {
    // Server thread only.
    private static final Map<ResourceKey<Level>, LongOpenHashSet> UNSENT = new HashMap<>();

    private SingleplayerGenerate() {}

    public static void register() {
        ServerGenerate.registerTicket();
        ServerIngest.filter(SingleplayerGenerate::unsent);
        ServerIngest.register();
        ServerTickEvents.END_SERVER_TICK.register(ServerGenerate::tick);
        ClientTickEvents.END_CLIENT_TICK.register(GenerateProgressBar::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(SingleplayerGenerate::stopping);
    }

    private static boolean unsent(ServerLevel level, ChunkPos pos, boolean unloading) {
        long chunk = pos.pack();
        if (ServerGenerate.tookRequested(level.dimension(), chunk)) return true;
        var unsent = UNSENT.computeIfAbsent(level.dimension(), ignored -> new LongOpenHashSet());
        if (unloading) return unsent.remove(chunk);
        for (ServerPlayer player : level.players()) {
            if (readByClient(player, pos)) return false;
        }
        unsent.add(chunk);
        return true;
    }

    private static boolean readByClient(ServerPlayer player, ChunkPos pos) {
        // Client ingest requires all 8 neighbors for Sodium meshing, so the outermost tracked ring is left to the server.
        var view = player.getChunkTrackingView();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!view.contains(pos.x() + dx, pos.z() + dz)) return false;
            }
        }
        return true;
    }

    private static void stopping(MinecraftServer server) {
        UNSENT.clear();
        ServerGenerate.clear();
        ServerIngest.clear();
    }
}