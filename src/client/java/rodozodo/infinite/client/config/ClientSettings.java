package rodozodo.infinite.client.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import rodozodo.infinite.Infinite;
import rodozodo.infinite.Log;
import rodozodo.infinite.util.DetailScale;
import rodozodo.infinite.util.Platform;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Function;


// Client-side Infinite configuration, loaded from and atomically saved to {@code config/infinite-config.json}.
public final class ClientSettings {
    public static final int MIN_DISTANCE_CHUNKS = 16, MAX_DISTANCE_CHUNKS = 2048, DISTANCE_STEP_CHUNKS = 16;
    public static final HorizonFog FOG = HorizonFog.EDGE_ONLY;

    private static final ClientSettings SETTINGS = load();

    public boolean enabled = true;
    public boolean rendering = true;
    public boolean ingest = true;
    public Quality quality;
    public int renderDistanceChunks;
    public float detailPixels;
    public boolean ambientOcclusion = true;
    public AntiAliasing antiAliasing = AntiAliasing.TAA;
    public boolean smoothBorder = true;
    public boolean caveCulling = true;
    public boolean plantCutouts = true;
    public int earthCurvature = 0;
    public boolean importProgress = true;
    public int netherFog = 50;
    public int netherFogStartChunks = 4;

    public static final int MAX_NETHER_FOG_START_CHUNKS = 64;
    public static final int NETHER_FOG_RAMP_CHUNKS = 32;

    private ClientSettings() {
        this.choosePreset(defaultQuality());
    }

    public static Quality defaultQuality() {
        return Platform.isMac() ? Quality.MAC : Quality.MEDIUM;
    }

    public static ClientSettings get() {
        return SETTINGS;
    }

    public static ClientSettings defaults() {
        return new ClientSettings();
    }

    public static int defaultRefinementThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 4);
    }

    public boolean netherFogIn(@Nullable Level level) {
        return this.netherFog > 0 && level != null && level.dimension() == Level.NETHER;
    }

    public boolean isRenderingEnabled() {
        return Infinite.available() && this.enabled && this.rendering;
    }

    public void choosePreset(Quality preset) {
        if (!preset.isPreset()) return;
        this.applyPreset(preset);
        this.plantCutouts = preset.plantCutouts;
    }

    public void applyPreset(Quality preset) {
        if (!preset.isPreset()) return;
        this.quality = preset;
        this.renderDistanceChunks = preset.distanceChunks;
        this.detailPixels = preset.detailPixels();
    }

    public void refreshQuality() {
        this.quality = Quality.nearest(this.renderDistanceChunks, this.detailPixels);
    }

    public int lodDistanceChunks() {
        return this.renderDistanceChunks;
    }

    public float renderDistanceBlocks() {
        return this.lodDistanceChunks() * 16f;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("infinite-config.json");
    }

    private static ClientSettings load() {
        var settings = new ClientSettings();
        if (!Infinite.available()) {
            settings.enabled = false;
            settings.rendering = false;
            return settings;
        }
        var path = path();
        if (Files.exists(path)) {
            try {
                var json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                if (json.isJsonObject()) {
                    settings.read(json.getAsJsonObject());
                } else {
                    Log.warn("Settings file is not a JSON object; using defaults");
                }
            } catch (IOException | RuntimeException e) {
                Log.error("Could not read settings; using defaults", e);
            }
        }
        settings.save();
        return settings;
    }

    private void read(JsonObject json) {
        boolean hadQuality = json.has("quality");
        this.quality = read(json, Quality.class, this.quality, e -> Quality.byKey(e.getAsString().toLowerCase(Locale.ROOT)), "quality");
        this.enabled = read(json, Boolean.class, this.enabled, JsonElement::getAsBoolean, "enabled");
        this.rendering = read(json, Boolean.class, this.rendering, JsonElement::getAsBoolean, "rendering");
        this.ingest = read(json, Boolean.class, this.ingest, JsonElement::getAsBoolean, "ingest");
        int distance = read(json, Integer.class, this.renderDistanceChunks, JsonElement::getAsInt, "render_distance_chunks");
        this.renderDistanceChunks = Math.clamp(Math.round(distance / (float) DISTANCE_STEP_CHUNKS) * DISTANCE_STEP_CHUNKS, MIN_DISTANCE_CHUNKS, MAX_DISTANCE_CHUNKS);
        float detail = read(json, Float.class, this.detailPixels, JsonElement::getAsFloat, "detail_pixels");
        this.detailPixels = Float.isFinite(detail) ? Math.clamp(detail, DetailScale.leastPixels(), DetailScale.LARGEST_PIXELS) : this.detailPixels;
        this.ambientOcclusion = read(json, Boolean.class, this.ambientOcclusion, JsonElement::getAsBoolean, "ambient_occlusion");
        this.antiAliasing = read(json, AntiAliasing.class, this.antiAliasing,
                e -> AntiAliasing.byKey(e.getAsString().toLowerCase(Locale.ROOT)), "anti_aliasing");
        this.smoothBorder = read(json, Boolean.class, this.smoothBorder, JsonElement::getAsBoolean, "smooth_border");
        this.caveCulling = read(json, Boolean.class, this.caveCulling, JsonElement::getAsBoolean, "cave_culling");
        this.plantCutouts = read(json, Boolean.class, this.plantCutouts, JsonElement::getAsBoolean, "plant_cutouts");
        this.earthCurvature = Math.clamp(read(json, Integer.class, this.earthCurvature,
                EarthCurvature::parseSetting, "earth_curvature"), 0, EarthCurvature.MAX_PERCENT);
        this.importProgress = read(json, Boolean.class, this.importProgress, JsonElement::getAsBoolean, "import_progress");
        this.netherFog = Math.clamp(read(json, Integer.class, this.netherFog, JsonElement::getAsInt, "nether_fog"), 0, 100);
        this.netherFogStartChunks = Math.clamp(read(json, Integer.class, this.netherFogStartChunks, JsonElement::getAsInt,
                "nether_fog_start_chunks"), 0, MAX_NETHER_FOG_START_CHUNKS);
        if (!hadQuality) {
            this.refreshQuality();
        } else if (this.quality.isPreset()) {
            this.applyPreset(this.quality);
        }
    }

    private static <T> @Nullable T read(JsonObject json, Class<T> type, @Nullable T fallback, Function<JsonElement, T> parse, String key) {
        var element = json.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return parse.apply(element);
        } catch (RuntimeException e) {
            Log.warn("Ignoring invalid setting " + key + "=" + element);
            return fallback;
        }
    }

    public void save() {
        if (!Infinite.available()) return;
        var json = new JsonObject();
        json.addProperty("quality", this.quality.key);
        json.addProperty("enabled", this.enabled);
        json.addProperty("rendering", this.rendering);
        json.addProperty("ingest", this.ingest);
        json.addProperty("render_distance_chunks", this.renderDistanceChunks);
        json.addProperty("detail_pixels", this.detailPixels);
        json.addProperty("ambient_occlusion", this.ambientOcclusion);
        json.addProperty("anti_aliasing", this.antiAliasing.key);
        json.addProperty("smooth_border", this.smoothBorder);
        json.addProperty("cave_culling", this.caveCulling);
        json.addProperty("plant_cutouts", this.plantCutouts);
        json.addProperty("earth_curvature", this.earthCurvature);
        json.addProperty("import_progress", this.importProgress);
        json.addProperty("nether_fog", this.netherFog);
        json.addProperty("nether_fog_start_chunks", this.netherFogStartChunks);

        var path = path();
        var temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.error("Could not save settings", e);
        }
    }
}
