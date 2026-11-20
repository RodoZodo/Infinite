package rodozodo.infinite.client;

import rodozodo.infinite.Infinite;
import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.render.LodRendererHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.jetbrains.annotations.Nullable;

public final class LodSession {
    private static boolean inWorld;
    private static @Nullable ServerData server;

    private LodSession() {}

    public static void joined(@Nullable ServerData joined) {
        if (inWorld) return;
        inWorld = true;
        server = joined;
        if (ClientSettings.get().enabled) Infinite.start();
    }

    public static void left() {
        if (!inWorld) return;
        inWorld = false;
        server = null;
        stop();
    }

    public static boolean inWorld() {
        return inWorld;
    }

    static @Nullable ServerData server() {
        return server;
    }

    public static void start() {
        if (!inWorld || Infinite.engine() != null || !Infinite.start()) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null) minecraft.levelExtractor.allChanged();
    }

    public static void stop() {
        var holder = LodRendererHolder.get();
        if (holder != null) holder.infinite$shutdownRenderer();
        Infinite.stop();
    }

    public static void restart() {
        stop();
        start();
    }
}