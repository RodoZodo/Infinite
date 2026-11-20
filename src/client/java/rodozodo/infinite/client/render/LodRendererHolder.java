package rodozodo.infinite.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public interface LodRendererHolder {
    @Nullable LodRenderer infinite$renderer();

    void infinite$createRenderer();

    void infinite$shutdownRenderer();

    void infinite$reloadRenderer();

    void infinite$setWorld(@Nullable Level level);

    static @Nullable LodRendererHolder get() {
        return (LodRendererHolder) Minecraft.getInstance().levelRenderer;
    }

    static @Nullable LodRenderer active() {
        var holder = get();
        return holder == null ? null : holder.infinite$renderer();
    }

    static boolean isActive() {
        return active() != null;
    }

    static @Nullable VisibleVanillaSections visibleSections() {
        var renderer = active();
        return renderer == null ? null : renderer.visibleSections;
    }
}
