package rodozodo.infinite.client;

import rodozodo.infinite.Infinite;
import rodozodo.infinite.client.backend.GraphicsBackendGate;
import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.render.LodRendererHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;

public final class DebugScreenLines {
    public static final Identifier VERSION = id("version");
    public static final Identifier DETAILS = id("debug");
    private static final Identifier INSTANCE_GROUP = id("instance_debug");
    private static final Identifier RENDER_GROUP = id("render_debug");

    private DebugScreenLines() {}

    static void register() {
        DebugScreenEntries.register(VERSION, (DisplayOnly) lines -> lines.addLine(versionLine()));
        DebugScreenEntries.register(DETAILS, (DisplayOnly) DebugScreenLines::details);
    }

    private static String versionLine() {
        String name = Infinite.VERSION;
        if (!Infinite.available()) return ChatFormatting.RED + "Infinite " + name + " · off (" + GraphicsBackendGate.status().shortReason + ")";
        var settings = ClientSettings.get();
        if (!settings.enabled) return ChatFormatting.YELLOW + "Infinite " + name + " · off";
        if (Infinite.engine() == null) return ChatFormatting.YELLOW + "Infinite " + name + " · starting";
        String quality = Component.translatable("infinite.quality." + settings.quality.key).getString();
        var line = new StringBuilder((LodRendererHolder.isActive() ? ChatFormatting.GREEN : ChatFormatting.DARK_GREEN).toString())
                .append("Infinite ").append(name)
                .append(ChatFormatting.GRAY).append(" · ").append(quality)
                .append(" · ").append(settings.lodDistanceChunks()).append(" chunks");
        if (!settings.rendering) line.append(" · not drawn");
        return line.toString();
    }

    private static void details(DebugScreenDisplayer lines) {
        var engine = Infinite.engine();
        if (engine == null) return;
        var renderer = LodRendererHolder.active();
        if (renderer != null) {
            var renderLines = new ArrayList<String>();
            renderer.addDebugInfo(renderLines);
            lines.addToGroup(RENDER_GROUP, renderLines);
        }
        var instanceLines = new ArrayList<String>();
        engine.addDebug(instanceLines);
        lines.addToGroup(INSTANCE_GROUP, instanceLines);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Infinite.MOD_ID, path);
    }

    @FunctionalInterface
    private interface DisplayOnly extends DebugScreenEntry {
        void display(DebugScreenDisplayer lines);

        @Override
        default void display(DebugScreenDisplayer lines, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
            this.display(lines);
        }
    }
}
