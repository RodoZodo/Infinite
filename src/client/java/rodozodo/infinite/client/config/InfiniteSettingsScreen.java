package rodozodo.infinite.client.config;

import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import rodozodo.infinite.Infinite;
import rodozodo.infinite.LodWorldKey;
import rodozodo.infinite.client.ClientLodEngine;
import rodozodo.infinite.client.LodSession;
import rodozodo.infinite.client.backend.GraphicsBackendGate;
import rodozodo.infinite.client.core.vk.VkContext;
import rodozodo.infinite.client.render.LodRendererHolder;
import rodozodo.infinite.importers.RegionImport;
import rodozodo.infinite.server.ServerGenerate;
import rodozodo.infinite.storage.SectionCompression;
import rodozodo.infinite.storage.StorageSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class InfiniteSettingsScreen extends Screen {
    private static final int CONTENT_WIDTH = 308;
    private static final int HALF_WIDTH = 150;
    private static final int QUARTER_WIDTH = 73;
    private static final int GAP = 8;
    private static final int SMALL_GAP = 4;
    private static final int[] ZSTD_LEVELS = {1, 3, 6, 10, 15, 19};

    private final Screen parent;

    private @Nullable Path saveFolder;
    private @Nullable StorageSettings storage;
    private volatile long diskBytes = -1;
    private boolean pregenRunning;
    private @Nullable Component statusNotice;
    private int statusNoticeColor = 0xFFAAAAAA;

    private Button enabledButton;
    private Button renderingButton;
    private Button ingestButton;
    private Button progressButton;
    private @Nullable Button worldEnabledButton;
    private @Nullable Button codecButton;
    private @Nullable Button levelButton;
    private @Nullable Button refreshButton;
    private @Nullable Button folderButton;
    private @Nullable Button loadButton;
    private @Nullable Button pregenButton;

    public InfiniteSettingsScreen(Screen parent) {
        super(Component.translatable("infinite.options.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        var settings = ClientSettings.get();
        int left = (this.width - CONTENT_WIDTH) / 2;
        int right = left + HALF_WIDTH + GAP;
        int y = 84;

        this.saveFolder = ClientLodEngine.currentSaveFolder();
        this.storage = loadCurrentStorage(this.saveFolder);
        if (this.saveFolder != null && this.diskBytes < 0) {
            Path folderToScan = this.saveFolder;
            CompletableFuture.supplyAsync(() -> directorySize(folderToScan))
                    .thenAccept(size -> this.diskBytes = size);
        }

        // Core toggles (2x2 grid)
        this.enabledButton = this.addRenderableWidget(Button.builder(
                        toggleLabel("infinite.options.enabled", settings.enabled),
                        b -> toggleEnabled())
                .bounds(left, y, HALF_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("infinite.hub.enabled.tooltip")))
                .build());

        this.renderingButton = this.addRenderableWidget(Button.builder(
                        toggleLabel("infinite.options.rendering", settings.rendering),
                        b -> toggleRendering())
                .bounds(right, y, HALF_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("infinite.options.rendering.tooltip")))
                .build());

        y += 24;
        this.ingestButton = this.addRenderableWidget(Button.builder(
                        toggleLabel("infinite.options.ingest", settings.ingest),
                        b -> {
                            settings.ingest = !settings.ingest;
                            settings.save();
                            refreshButtons();
                        })
                .bounds(left, y, HALF_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("infinite.options.ingest.tooltip")))
                .build());

        this.progressButton = this.addRenderableWidget(Button.builder(
                        toggleLabel("infinite.hub.progress", settings.importProgress),
                        b -> {
                            settings.importProgress = !settings.importProgress;
                            settings.save();
                            refreshButtons();
                        })
                .bounds(right, y, HALF_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("infinite.hub.progress.tooltip")))
                .build());

        // Video Settings shortcut (always enabled so users on OpenGL can jump straight to Graphics API)
        y += 28;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("infinite.hub.video_settings"),
                        b -> openVideoSettings())
                .bounds(left, y, CONTENT_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("infinite.hub.video_settings.tooltip")))
                .build());

        // Per world storage & tools (only when in a world/server)
        this.worldEnabledButton = null;
        this.codecButton = null;
        this.levelButton = null;
        this.refreshButton = null;
        this.folderButton = null;
        this.loadButton = null;
        this.pregenButton = null;

        if (this.saveFolder != null && this.storage != null) {
            y += 34;
            this.worldEnabledButton = this.addRenderableWidget(Button.builder(
                            Component.empty(), b -> toggleWorldStorage())
                    .bounds(left, y, HALF_WIDTH, 20)
                    .tooltip(Tooltip.create(Component.translatable("infinite.hub.world_enabled.tooltip")))
                    .build());

            this.codecButton = this.addRenderableWidget(Button.builder(
                            Component.empty(), b -> cycleCodec())
                    .bounds(right, y, QUARTER_WIDTH, 20)
                    .tooltip(Tooltip.create(Component.translatable("infinite.hub.codec.tooltip")))
                    .build());

            this.levelButton = this.addRenderableWidget(Button.builder(
                            Component.empty(), b -> cycleCompressionLevel())
                    .bounds(right + QUARTER_WIDTH + SMALL_GAP, y, QUARTER_WIDTH, 20)
                    .tooltip(Tooltip.create(Component.translatable("infinite.hub.level.tooltip")))
                    .build());

            y += 24;
            this.refreshButton = this.addRenderableWidget(Button.builder(
                            Component.translatable("infinite.hub.refresh"),
                            b -> refreshLods())
                    .bounds(left, y, HALF_WIDTH, 20)
                    .tooltip(Tooltip.create(Component.translatable("infinite.hub.refresh.tooltip")))
                    .build());

            this.folderButton = this.addRenderableWidget(Button.builder(
                            Component.translatable("infinite.hub.open_folder"),
                            b -> openSaveFolder())
                    .bounds(right, y, HALF_WIDTH, 20)
                    .tooltip(Tooltip.create(Component.translatable("infinite.hub.open_folder.tooltip")))
                    .build());

            if (this.minecraft.getSingleplayerServer() != null) {
                y += 24;
                this.loadButton = this.addRenderableWidget(Button.builder(
                                Component.empty(), b -> toggleLoadCurrentDimension())
                        .bounds(left, y, HALF_WIDTH, 20)
                        .tooltip(Tooltip.create(Component.translatable("infinite.hub.load.tooltip")))
                        .build());

                this.pregenButton = this.addRenderableWidget(Button.builder(
                                Component.empty(), b -> togglePregenerate())
                        .bounds(right, y, HALF_WIDTH, 20)
                        .tooltip(Tooltip.create(Component.translatable("infinite.hub.pregen.tooltip")))
                        .build());

                queryPregenState();
            }
        }

        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), b -> this.onClose())
                .bounds((this.width - 200) / 2, this.height - 26, 200, 20)
                .build());

        refreshButtons();
    }

    private void toggleEnabled() {
        var settings = ClientSettings.get();
        settings.enabled = !settings.enabled;
        settings.save();
        if (settings.enabled) {
            LodSession.start();
        } else {
            LodSession.stop();
        }
        refreshButtons();
    }

    private void toggleRendering() {
        var settings = ClientSettings.get();
        settings.rendering = !settings.rendering;
        settings.save();
        var holder = LodRendererHolder.get();
        if (holder != null) {
            holder.infinite$shutdownRenderer();
            holder.infinite$createRenderer();
        }
        refreshButtons();
    }

    private void toggleWorldStorage() {
        if (this.saveFolder == null || this.storage == null) return;
        this.storage.disabled = !this.storage.disabled;
        this.storage.write(this.saveFolder);
        if (ClientSettings.get().enabled) {
            LodSession.restart();
        }
        setNotice(Component.translatable(this.storage.disabled
                ? "infinite.hub.notice.world_disabled"
                : "infinite.hub.notice.world_enabled"), 0xFF55FF55);
        refreshButtons();
    }

    private void cycleCodec() {
        if (this.saveFolder == null || this.storage == null) return;
        boolean isZstd = this.storage.codec() == SectionCompression.ZSTD;
        this.storage.codec = isZstd ? SectionCompression.LZ4_NAME : SectionCompression.DEFAULT;
        this.storage.write(this.saveFolder);
        setNotice(Component.translatable("infinite.hub.notice.storage_needs_refresh"), 0xFFFFAA00);
        refreshButtons();
    }

    private void cycleCompressionLevel() {
        if (this.saveFolder == null || this.storage == null) return;
        int current = this.storage.compressionLevel;
        int next = ZSTD_LEVELS[0];
        for (int level : ZSTD_LEVELS) {
            if (level > current) {
                next = level;
                break;
            }
        }
        this.storage.compressionLevel = next;
        this.storage.write(this.saveFolder);
        setNotice(Component.translatable("infinite.hub.notice.storage_needs_refresh"), 0xFFFFAA00);
        refreshButtons();
    }

    private void refreshLods() {
        LodSession.restart();
        if (this.saveFolder != null) {
            this.storage = loadCurrentStorage(this.saveFolder);
        }
        boolean running = Infinite.engine() != null;
        setNotice(Component.translatable(running
                ? "infinite.command.refreshed"
                : "infinite.command.refresh_failed"), running ? 0xFF55FF55 : 0xFFFF5555);
        refreshButtons();
    }

    private void openSaveFolder() {
        if (this.saveFolder == null) return;
        try {
            Files.createDirectories(this.saveFolder);
            Blaze3D.openPath(this.saveFolder);
        } catch (Exception e) {
            setNotice(Component.literal(String.valueOf(e.getMessage())), 0xFFFF5555);
        }
    }

    private void toggleLoadCurrentDimension() {
        var server = this.minecraft.getSingleplayerServer();
        var engine = Infinite.engine();
        var clientLevel = this.minecraft.level;
        if (server == null || engine == null || clientLevel == null) return;

        var dimension = clientLevel.dimension();
        ServerLevel serverLevel = server.getLevel(dimension);
        if (serverLevel == null) return;

        var openWorld = Infinite.openWorld(serverLevel);
        if (openWorld != null && engine.imports().isRunning(openWorld)) {
            engine.imports().cancel(openWorld);
            setNotice(Component.translatable("infinite.command.load.cancelled"), 0xFFFFAA00);
            refreshButtons();
            return;
        }

        String name = LodWorldKey.dimensionName(dimension).getString();
        var folder = RegionImport.regionsAt(server.getWorldPath(LevelResource.ROOT), dimension);
        if (folder == null || !RegionImport.hasRegions(folder)) {
            setNotice(Component.translatable("infinite.command.load.no_region_folder", name), 0xFFFF5555);
            return;
        }
        var world = Infinite.world(serverLevel);
        if (world == null) {
            setNotice(Component.translatable("infinite.command.no_world"), 0xFFFF5555);
            return;
        }

        var player = this.minecraft.player;
        int centerX = player == null ? 0 : player.getBlockX() >> 4;
        int centerZ = player == null ? 0 : player.getBlockZ() >> 4;
        try {
            boolean started = engine.imports().start(world, () -> new RegionImport("Loading " + name,
                    RegionImport.folder(folder), world, serverLevel, centerX, centerZ,
                    engine.workers(), engine::saveQueueHasRoom));
            if (started) {
                setNotice(Component.translatable("infinite.command.load.started", name), 0xFF55FF55);
            } else {
                setNotice(Component.translatable("infinite.command.load.already_running"), 0xFFFFAA00);
            }
        } catch (IOException e) {
            setNotice(Component.translatable("infinite.command.load.unreadable", name, String.valueOf(e.getMessage())), 0xFFFF5555);
        }
        refreshButtons();
    }

    private void togglePregenerate() {
        MinecraftServer server = this.minecraft.getSingleplayerServer();
        var clientLevel = this.minecraft.level;
        if (server == null || clientLevel == null) return;

        var dimension = clientLevel.dimension();
        var player = this.minecraft.player;
        int fallbackX = player == null ? 0 : player.getBlockX();
        int fallbackZ = player == null ? 0 : player.getBlockZ();

        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) return;
            boolean running = ServerGenerate.isRunning(dimension);
            ServerGenerate.Result result = running
                    ? ServerGenerate.pause(level)
                    : ServerGenerate.start(level, fallbackX, fallbackZ);
            boolean nowRunning = ServerGenerate.isRunning(dimension);

            this.minecraft.execute(() -> {
                this.pregenRunning = nowRunning;
                switch (result.outcome()) {
                    case STARTED -> setNotice(Component.translatable("infinite.command.pregenerate.started"), 0xFF55FF55);
                    case PAUSED -> setNotice(Component.translatable("infinite.command.pregenerate.paused"), 0xFFFFAA00);
                    case NO_ENGINE -> setNotice(Component.translatable("infinite.command.not_running"), 0xFFFF5555);
                    default -> {}
                }
                refreshButtons();
            });
        });
    }

    private void queryPregenState() {
        var server = this.minecraft.getSingleplayerServer();
        var clientLevel = this.minecraft.level;
        if (server == null || clientLevel == null) return;
        var dimension = clientLevel.dimension();
        server.execute(() -> {
            boolean running = ServerGenerate.isRunning(dimension);
            this.minecraft.execute(() -> {
                this.pregenRunning = running;
                refreshButtons();
            });
        });
    }

    private void openVideoSettings() {
        if (FabricLoader.getInstance().isModLoaded("sodium")) {
            this.minecraft.gui.setScreen(VideoSettingsScreen.createScreen(this));
        } else {
            this.minecraft.gui.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(
                    this, this.minecraft, this.minecraft.options));
        }
    }

    private void refreshButtons() {
        boolean available = Infinite.available();
        var settings = ClientSettings.get();
        boolean active = available && settings.enabled;
        boolean engineRunning = Infinite.engine() != null;

        this.enabledButton.setMessage(toggleLabel("infinite.options.enabled", settings.enabled));
        this.enabledButton.active = available;

        this.renderingButton.setMessage(toggleLabel("infinite.options.rendering", settings.rendering));
        this.renderingButton.active = active;

        this.ingestButton.setMessage(toggleLabel("infinite.options.ingest", settings.ingest));
        this.ingestButton.active = active;

        this.progressButton.setMessage(toggleLabel("infinite.hub.progress", settings.importProgress));
        this.progressButton.active = available;

        if (this.storage != null && this.worldEnabledButton != null && this.codecButton != null && this.levelButton != null) {
            boolean worldOn = !this.storage.disabled;
            boolean zstd = this.storage.codec() == SectionCompression.ZSTD;
            this.worldEnabledButton.setMessage(toggleLabel("infinite.hub.world_enabled", worldOn));
            this.worldEnabledButton.active = available;

            this.codecButton.setMessage(Component.translatable("infinite.hub.codec",
                    zstd ? "Zstd" : "LZ4"));
            this.codecButton.active = available && worldOn;

            this.levelButton.setMessage(Component.translatable("infinite.hub.level",
                    zstd ? String.valueOf(this.storage.compressionLevel) : "-"));
            this.levelButton.active = available && worldOn && zstd;
        }

        if (this.refreshButton != null) {
            this.refreshButton.active = active && (this.storage == null || !this.storage.disabled);
        }
        if (this.folderButton != null) {
            this.folderButton.active = this.saveFolder != null;
        }
        if (this.loadButton != null) {
            boolean loading = isCurrentDimensionLoading();
            this.loadButton.setMessage(Component.translatable(loading
                    ? "infinite.hub.load.stop" : "infinite.hub.load.start"));
            this.loadButton.active = engineRunning;
        }
        if (this.pregenButton != null) {
            this.pregenButton.setMessage(Component.translatable(this.pregenRunning
                    ? "infinite.hub.pregen.pause" : "infinite.hub.pregen.start"));
            this.pregenButton.active = engineRunning;
        }
    }

    private boolean isCurrentDimensionLoading() {
        var server = this.minecraft.getSingleplayerServer();
        var engine = Infinite.engine();
        var clientLevel = this.minecraft.level;
        if (server == null || engine == null || clientLevel == null) return false;
        var serverLevel = server.getLevel(clientLevel.dimension());
        var world = serverLevel == null ? null : Infinite.openWorld(serverLevel);
        return world != null && engine.imports().isRunning(world);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int left = (this.width - CONTENT_WIDTH) / 2;

        graphics.centeredText(this.font,
                Component.literal("Infinite " + Infinite.VERSION).withStyle(ChatFormatting.BOLD),
                centerX, 10, 0xFFD394E4);

        drawStatusCard(graphics, left, 24, CONTENT_WIDTH, 52);

        if (this.saveFolder != null) {
            graphics.text(this.font,
                    Component.translatable("infinite.hub.section.world"),
                    left, 158, 0xFFB8C4D4, true);
        }

        if (this.statusNotice != null) {
            int noticeY = this.height - 42;
            graphics.centeredText(this.font, this.statusNotice, centerX, noticeY, this.statusNoticeColor);
        }
    }

    private void drawStatusCard(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        boolean available = Infinite.available();
        var settings = ClientSettings.get();
        var gate = GraphicsBackendGate.status();

        int accent;
        String headline;
        String line2;
        String line3;

        if (!available) {
            accent = 0xFFFF5555;
            if (!FabricLoader.getInstance().isModLoaded("sodium")) {
                headline = "Unavailable — Sodium is required";
                line2 = "Install Sodium to enable Infinite.";
                line3 = "";
            } else {
                headline = "Unavailable — " + gate.shortReason;
                String detail = GraphicsBackendGate.detail();
                line2 = detail.isEmpty()
                        ? "Set Video Settings > Graphics API to \"Prefer Vulkan\" and restart."
                        : detail;
                line3 = "Current backend: " + GraphicsBackendGate.backend().name();
            }
        } else {
            boolean running = Infinite.engine() != null;
            accent = !settings.enabled ? 0xFFFFAA00 : (running ? 0xFF55FF55 : 0xFF55AAFF);
            String state = !settings.enabled ? "Off"
                    : (this.storage != null && this.storage.disabled) ? "Disabled for this world"
                    : running ? "Active · Vulkan" : "Ready · Vulkan";
            String gpu = VkContext.isCreated() ? VkContext.get().deviceName : "Vulkan";
            headline = state + "  (" + gpu + ")";

            String quality = Component.translatable("infinite.quality." + settings.quality.key).getString();
            line2 = quality + " · " + settings.lodDistanceChunks() + " chunks · "
                    + Math.round(settings.detailPixels) + " px detail";
            line3 = formatLiveStats();
        }

        // Card background + left accent bar
        graphics.fill(x, y, x + width, y + height, 0xB010141A);
        graphics.fill(x, y, x + 3, y + height, accent);

        int textX = x + 9;
        int maxTextWidth = width - 16;
        graphics.text(this.font, trimToWidth(headline, maxTextWidth), textX, y + 6, accent, true);
        graphics.text(this.font, trimToWidth(line2, maxTextWidth), textX, y + 20, 0xFFE0E6EE, false);
        if (!line3.isEmpty()) {
            graphics.text(this.font, trimToWidth(line3, maxTextWidth), textX, y + 34, 0xFF9AA6B8, false);
        }
    }

    private String formatLiveStats() {
        var engine = Infinite.engine();
        var renderer = LodRendererHolder.active();
        var sb = new StringBuilder();
        if (engine != null) {
            var s = engine.stats();
            sb.append(s.activeSections()).append(" active / ").append(s.cachedSections()).append(" cached");
            sb.append(" · ").append(s.nativeBytes() >> 20).append(" MiB RAM");
            if (renderer != null) {
                sb.append(" · ").append(renderer.stats().gpuBytes() >> 20).append(" MiB GPU");
            }
            if (s.building() > 0 || s.saving() > 0) {
                sb.append(" · Q:").append(s.building()).append('/').append(s.saving());
            }
        }
        if (this.diskBytes >= 0) {
            if (!sb.isEmpty()) sb.append(" · ");
            sb.append("Disk: ").append(formatBytes(this.diskBytes));
        }
        return sb.toString();
    }

    private String trimToWidth(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width("..."))) + "...";
    }

    private void setNotice(Component text, int color) {
        this.statusNotice = text;
        this.statusNoticeColor = color;
    }

    private static @Nullable StorageSettings loadCurrentStorage(@Nullable Path folder) {
        if (folder == null) return null;
        if (Infinite.engine() instanceof ClientLodEngine clientEngine) {
            return clientEngine.storage();
        }
        return StorageSettings.load(folder);
    }

    private static long directorySize(Path root) {
        if (!Files.isDirectory(root)) return 0L;
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return -1L;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static Component toggleLabel(String key, boolean on) {
        return Component.translatable(key).append(Component.literal(": "))
                .append(Component.translatable(on ? "options.on" : "options.off"));
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}