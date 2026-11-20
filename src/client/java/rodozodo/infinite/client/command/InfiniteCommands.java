package rodozodo.infinite.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import rodozodo.infinite.FillCommandTree;
import rodozodo.infinite.Infinite;
import rodozodo.infinite.LodEngine;
import rodozodo.infinite.LodWorldKey;
import rodozodo.infinite.client.LodSession;
import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.importers.RegionImport;
import rodozodo.infinite.server.ServerGenerate;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

// Registers and executes {@code /infinite} client commands for loading saved terrain into LODs.

public final class InfiniteCommands {
    private static final int FAILED = 0;

    private InfiniteCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var root = literal(Infinite.MOD_ID)
                .then(literal("refresh").executes(InfiniteCommands::refresh))
                .then(progress());
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            root.then(load());
            root.then(pregenerate());
        }
        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> pregenerate() {
        return FillCommandTree.<FabricClientCommandSource>build("pregenerate", InfiniteCommands::runPregenerate)
                .then(literal("status").executes(InfiniteCommands::pregenerateStatus));
    }

    private static int runPregenerate(CommandContext<FabricClientCommandSource> ctx, @Nullable Integer radius,
                                      @Nullable Integer x, @Nullable Integer z, FillCommandTree.Action action) {
        if (radius != null && (radius < ServerGenerate.MIN_RADIUS || radius > ServerGenerate.MAX_RADIUS)) {
            ctx.getSource().sendError(Component.translatable("infinite.command.bad_radius",
                    ServerGenerate.MIN_RADIUS, ServerGenerate.MAX_RADIUS));
            return FAILED;
        }
        var source = ctx.getSource();
        var level = serverLevel(ctx);
        if (level == null) return FAILED;
        var player = source.getPlayer();
        int fallbackX = player == null ? 0 : player.getBlockX();
        int fallbackZ = player == null ? 0 : player.getBlockZ();
        boolean hasArgs = radius != null || x != null;

        server(source).execute(() -> {
            ServerGenerate.Result result;
            switch (action) {
                case NONE -> result = ServerGenerate.configure(level, radius, x, z, fallbackX, fallbackZ);
                case START -> {
                    if (hasArgs) ServerGenerate.configure(level, radius, x, z, fallbackX, fallbackZ);
                    result = ServerGenerate.start(level, fallbackX, fallbackZ);
                }
                case PAUSE -> {
                    if (hasArgs) {
                        result = ServerGenerate.configure(level, radius, x, z, fallbackX, fallbackZ);
                    } else {
                        result = ServerGenerate.pause(level);
                    }
                }
                case STOP -> {
                    result = ServerGenerate.stop(level);
                    if (hasArgs) ServerGenerate.configure(level, radius, x, z, fallbackX, fallbackZ);
                }
                default -> throw new IllegalStateException();
            }
            source.getClient().execute(() -> pregenerateResult(ctx, result, radius, x, z, action, hasArgs));
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int pregenerateStatus(CommandContext<FabricClientCommandSource> ctx) {
        var source = ctx.getSource();
        var level = serverLevel(ctx);
        if (level == null) return FAILED;
        server(source).execute(() -> {
            List<String> lines = new ArrayList<>();
            ServerGenerate.describe(lines);
            source.getClient().execute(() -> {
                if (lines.isEmpty()) {
                    source.sendError(Component.translatable("infinite.command.pregenerate.none_started"));
                } else {
                    for (var line : lines) source.sendFeedback(Component.literal(line));
                }
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private static MinecraftServer server(FabricClientCommandSource source) {
        var server = source.getClient().getSingleplayerServer();
        if (server == null) throw new IllegalStateException("A command ran without the server that was found for it");
        return server;
    }

    private static @Nullable ServerLevel serverLevel(CommandContext<FabricClientCommandSource> ctx) {
        var source = ctx.getSource();
        var server = source.getClient().getSingleplayerServer();
        var level = server == null ? null : server.getLevel(source.getLevel().dimension());
        if (level == null) source.sendError(Component.translatable("infinite.command.not_running"));
        return level;
    }

    private static void pregenerateResult(CommandContext<FabricClientCommandSource> ctx, ServerGenerate.Result result,
                                          @Nullable Integer radius, @Nullable Integer x, @Nullable Integer z,
                                          FillCommandTree.Action action, boolean hasArgs) {
        var source = ctx.getSource();
        if (action == FillCommandTree.Action.STOP && hasArgs
                && (result.outcome() == ServerGenerate.Outcome.NOT_RUNNING || result.outcome() == ServerGenerate.Outcome.STOPPED)) {
            if (result.outcome() == ServerGenerate.Outcome.STOPPED) {
                source.sendFeedback(Component.translatable("infinite.command.pregenerate.stopped"));
            }
            sendAreaMessage(source, "pregenerate", radius, x, z, false);
            return;
        }
        if (result.outcome() == ServerGenerate.Outcome.CONFIGURED) {
            sendAreaMessage(source, "pregenerate", radius, x, z, "paused".equals(result.detail()));
            return;
        }
        var message = switch (result.outcome()) {
            case STARTED -> Component.translatable("infinite.command.pregenerate.started");
            case PAUSED -> Component.translatable("infinite.command.pregenerate.paused");
            case STOPPED -> Component.translatable("infinite.command.pregenerate.stopped");
            case ALREADY_RUNNING -> Component.translatable("infinite.command.pregenerate.already_running");
            case NOT_RUNNING -> Component.translatable("infinite.command.pregenerate.none_running");
            case NO_ENGINE -> Component.translatable("infinite.command.not_running");
            case CONFIGURED -> throw new IllegalStateException();
        };
        boolean ok = result.outcome() == ServerGenerate.Outcome.STARTED
                || result.outcome() == ServerGenerate.Outcome.PAUSED
                || result.outcome() == ServerGenerate.Outcome.STOPPED;
        if (ok) source.sendFeedback(message);
        else source.sendError(message);
    }

    private static void sendAreaMessage(FabricClientCommandSource source, String kind,
                                        @Nullable Integer radius, @Nullable Integer x, @Nullable Integer z,
                                        boolean movedRunning) {
        if (radius != null && x != null && z != null) {
            source.sendFeedback(Component.translatable("infinite.command." + kind + ".set_area", radius, x, z));
        } else if (radius != null) {
            source.sendFeedback(Component.translatable("infinite.command." + kind + ".set_radius", radius));
        } else if (x != null && z != null) {
            source.sendFeedback(Component.translatable("infinite.command." + kind + ".set_position", x, z));
        }
        if (movedRunning) {
            source.sendFeedback(Component.translatable("infinite.command." + kind + ".area_moved"));
        }
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> progress() {
        return literal("progress")
                .executes(ctx -> toggleProgress(ctx))
                .then(literal("on").executes(ctx -> setProgress(ctx, true)))
                .then(literal("off").executes(ctx -> setProgress(ctx, false)));
    }

    private static int toggleProgress(CommandContext<FabricClientCommandSource> ctx) {
        return setProgress(ctx, !ClientSettings.get().importProgress);
    }

    private static int setProgress(CommandContext<FabricClientCommandSource> ctx, boolean shown) {
        var settings = ClientSettings.get();
        if (settings.importProgress == shown) {
            ctx.getSource().sendError(Component.translatable(shown
                    ? "infinite.command.progress.already_shown" : "infinite.command.progress.already_hidden"));
            return FAILED;
        }
        settings.importProgress = shown;
        settings.save();
        ctx.getSource().sendFeedback(Component.translatable(shown
                ? "infinite.command.progress.shown" : "infinite.command.progress.hidden"));
        return Command.SINGLE_SUCCESS;
    }

    private static int refresh(CommandContext<FabricClientCommandSource> ctx) {
        if (running(ctx) == null) return FAILED;
        LodSession.restart();
        ctx.getSource().sendFeedback(Component.translatable(Infinite.engine() != null
                ? "infinite.command.refreshed" : "infinite.command.refresh_failed"));
        return Infinite.engine() != null ? Command.SINGLE_SUCCESS : FAILED;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> load() {
        return literal("load")
                .then(literal("overworld").executes(ctx -> loadDimension(ctx, Level.OVERWORLD)))
                .then(literal("nether").executes(ctx -> loadDimension(ctx, Level.NETHER)))
                .then(literal("end").executes(ctx -> loadDimension(ctx, Level.END)))
                .then(literal("all").executes(InfiniteCommands::loadAll))
                .then(literal("stop")
                        .executes(ctx -> stopDimension(ctx, ctx.getSource().getLevel().dimension()))
                        .then(literal("overworld").executes(ctx -> stopDimension(ctx, Level.OVERWORLD)))
                        .then(literal("nether").executes(ctx -> stopDimension(ctx, Level.NETHER)))
                        .then(literal("end").executes(ctx -> stopDimension(ctx, Level.END)))
                        .then(literal("all").executes(InfiniteCommands::stopAllLoads)));
    }

    private static int loadDimension(CommandContext<FabricClientCommandSource> ctx, ResourceKey<Level> dimension) {
        var source = ctx.getSource();
        var server = source.getClient().getSingleplayerServer();
        if (server == null) {
            source.sendError(Component.translatable("infinite.command.load.singleplayer_only"));
            return FAILED;
        }
        var engine = running(ctx);
        if (engine == null) return FAILED;
        String name = LodWorldKey.dimensionName(dimension).getString();
        var serverLevel = server.getLevel(dimension);
        var folder = serverLevel == null ? null
                : RegionImport.regionsAt(server.getWorldPath(LevelResource.ROOT), dimension);
        if (serverLevel == null || folder == null || !RegionImport.hasRegions(folder)) {
            source.sendError(Component.translatable("infinite.command.load.no_region_folder", name));
            return FAILED;
        }
        var world = Infinite.world(serverLevel);
        if (world == null) {
            source.sendError(Component.translatable("infinite.command.no_world"));
            return FAILED;
        }
        int[] center = loadCenter(source, serverLevel);
        try {
            if (!engine.imports().start(world, () -> new RegionImport("Loading " + name,
                    RegionImport.folder(folder), world, serverLevel, center[0], center[1],
                    engine.workers(), engine::saveQueueHasRoom))) {
                source.sendError(Component.translatable("infinite.command.load.already_running"));
                return FAILED;
            }
        } catch (IOException e) {
            source.sendError(Component.translatable("infinite.command.load.unreadable", name, String.valueOf(e.getMessage())));
            return FAILED;
        }
        source.sendFeedback(Component.translatable("infinite.command.load.started", name));
        return Command.SINGLE_SUCCESS;
    }

    private static int loadAll(CommandContext<FabricClientCommandSource> ctx) {
        var source = ctx.getSource();
        var server = source.getClient().getSingleplayerServer();
        if (server == null) {
            source.sendError(Component.translatable("infinite.command.load.singleplayer_only"));
            return FAILED;
        }
        var engine = running(ctx);
        if (engine == null) return FAILED;
        List<String> started = new ArrayList<>();
        int alreadyRunning = 0;
        String firstError = null;
        for (var serverLevel : server.getAllLevels()) {
            var dimension = serverLevel.dimension();
            String name = LodWorldKey.dimensionName(dimension).getString();
            var folder = RegionImport.regionsAt(server.getWorldPath(LevelResource.ROOT), dimension);
            if (folder == null || !RegionImport.hasRegions(folder)) continue;
            var world = Infinite.world(serverLevel);
            if (world == null) continue;
            int[] center = loadCenter(source, serverLevel);
            try {
                if (engine.imports().start(world, () -> new RegionImport("Loading " + name,
                        RegionImport.folder(folder), world, serverLevel, center[0], center[1],
                        engine.workers(), engine::saveQueueHasRoom))) {
                    started.add(name);
                } else {
                    alreadyRunning++;
                }
            } catch (IOException e) {
                if (firstError == null) firstError = String.valueOf(e.getMessage());
            }
        }
        if (!started.isEmpty()) {
            source.sendFeedback(Component.translatable("infinite.command.load.started", String.join(", ", started)));
            return Command.SINGLE_SUCCESS;
        }
        if (alreadyRunning > 0) {
            source.sendError(Component.translatable("infinite.command.load.already_running"));
            return FAILED;
        }
        if (firstError != null) {
            source.sendError(Component.translatable("infinite.command.load.unreadable", "world", firstError));
            return FAILED;
        }
        source.sendError(Component.translatable("infinite.command.load.none_found"));
        return FAILED;
    }

    private static int stopDimension(CommandContext<FabricClientCommandSource> ctx, ResourceKey<Level> dimension) {
        var source = ctx.getSource();
        var server = source.getClient().getSingleplayerServer();
        if (server == null) {
            source.sendError(Component.translatable("infinite.command.load.singleplayer_only"));
            return FAILED;
        }
        var engine = running(ctx);
        if (engine == null) return FAILED;
        var serverLevel = server.getLevel(dimension);
        var world = serverLevel == null ? null : Infinite.openWorld(serverLevel);
        if (world == null || !engine.imports().cancel(world)) {
            source.sendError(Component.translatable("infinite.command.load.none_running"));
            return FAILED;
        }
        source.sendFeedback(Component.translatable("infinite.command.load.cancelled"));
        return Command.SINGLE_SUCCESS;
    }

    private static int stopAllLoads(CommandContext<FabricClientCommandSource> ctx) {
        var source = ctx.getSource();
        var server = source.getClient().getSingleplayerServer();
        if (server == null) {
            source.sendError(Component.translatable("infinite.command.load.singleplayer_only"));
            return FAILED;
        }
        var engine = running(ctx);
        if (engine == null) return FAILED;
        int stopped = 0;
        for (var serverLevel : server.getAllLevels()) {
            var world = Infinite.openWorld(serverLevel);
            if (world != null && engine.imports().cancel(world)) stopped++;
        }
        if (stopped == 0) {
            source.sendError(Component.translatable("infinite.command.load.none_running"));
            return FAILED;
        }
        source.sendFeedback(Component.translatable("infinite.command.load.cancelled"));
        return Command.SINGLE_SUCCESS;
    }

    private static int[] loadCenter(FabricClientCommandSource source, ServerLevel level) {
        if (source.getLevel().dimension().equals(level.dimension()) && source.getPlayer() != null) {
            var player = source.getPlayer();
            return new int[]{player.getBlockX() >> 4, player.getBlockZ() >> 4};
        }
        var spawn = level.getRespawnData();
        boolean here = spawn.dimension() == level.dimension();
        return new int[]{here ? spawn.pos().getX() >> 4 : 0, here ? spawn.pos().getZ() >> 4 : 0};
    }

    private static @Nullable LodEngine running(CommandContext<FabricClientCommandSource> ctx) {
        var engine = Infinite.engine();
        if (engine == null) ctx.getSource().sendError(Component.translatable("infinite.command.not_running"));
        return engine;
    }
}
