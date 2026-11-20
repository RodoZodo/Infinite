package rodozodo.infinite;

import rodozodo.infinite.world.LodWorld;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public final class Infinite {
    public static final String MOD_ID = "infinite";
    public static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    private static @Nullable Supplier<@Nullable LodEngine> factory;
    private static volatile @Nullable LodEngine running;

    private Infinite() {}

    public static synchronized void enable(Supplier<@Nullable LodEngine> engines) {
        if (factory != null) throw new IllegalStateException("Infinite is already enabled");
        factory = engines;
    }

    public static synchronized boolean available() {
        return factory != null;
    }

    public static @Nullable LodEngine engine() {
        return running;
    }

    public static synchronized boolean start() {
        if (running != null) return true;
        if (factory == null) return false;
        running = factory.get();
        return running != null;
    }

    public static synchronized void stop() {
        var engine = running;
        if (engine == null) return;
        running = null;
        try {
            engine.shutdown();
        } catch (RuntimeException e) {
            Log.error("Infinite did not stop cleanly", e);
        }
    }

    public static @Nullable LodWorld world(@Nullable Level level) {
        return world(LodWorldKey.of(level));
    }

    public static @Nullable LodWorld world(@Nullable LodWorldKey id) {
        var engine = running;
        return engine == null || id == null ? null : engine.world(id);
    }

    public static @Nullable LodWorld openWorld(@Nullable Level level) {
        var engine = running;
        var id = LodWorldKey.of(level);
        return engine == null || id == null ? null : engine.openWorld(id);
    }
}
