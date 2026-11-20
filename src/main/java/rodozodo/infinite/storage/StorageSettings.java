package rodozodo.infinite.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import rodozodo.infinite.Log;

import java.nio.file.Files;
import java.nio.file.Path;

//Per-save storage configuration persisted in {@code storage.json}
public final class StorageSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "storage.json";

    public static final String LOG = "log";

    public boolean disabled = false;
    public int compressionLevel = 1;
    public String backend = null;
    public String codec = null;

    public static StorageSettings load(Path saveDirectory) {
        var file = saveDirectory.resolve(FILE);
        boolean existed = Files.exists(file);
        StorageSettings settings = null;
        try {
            Files.createDirectories(saveDirectory);
            if (existed) {
                settings = GSON.fromJson(Files.readString(file), StorageSettings.class);
            }
        } catch (Exception e) {
            Log.error("Could not read " + file + ", using defaults", e);
        }
        if (settings == null) settings = new StorageSettings();
        settings.compressionLevel = Math.clamp(settings.compressionLevel, 1, 19);
        if (settings.backend == null) {
            settings.backend = LOG;
        } else if (!LOG.equals(settings.backend)) {
            Log.warn("Unknown storage backend \"" + settings.backend + "\"; using " + LOG);
            settings.backend = LOG;
        }
        if (settings.codec == null) {
            settings.codec = SectionCompression.defaultName();
        } else {
            int codec = SectionCompression.parse(settings.codec);
            if (codec < 0) {
                Log.warn("Unknown LoD codec \"" + settings.codec + "\"; using " + SectionCompression.defaultName());
                settings.codec = SectionCompression.defaultName();
            } else if (!SectionCompression.available(codec)) {
                Log.warn("LoD codec \"" + settings.codec + "\" unavailable; using " + SectionCompression.defaultName());
                settings.codec = SectionCompression.defaultName();
            }
        }
        settings.write(saveDirectory);
        return settings;
    }

    public void write(Path saveDirectory) {
        try {
            Files.createDirectories(saveDirectory);
            Files.writeString(saveDirectory.resolve(FILE), GSON.toJson(this));
        } catch (Exception e) {
            Log.error("Could not write " + saveDirectory.resolve(FILE), e);
        }
    }

    public int codec() {
        int parsed = SectionCompression.parse(this.codec);
        if (parsed >= 0 && SectionCompression.available(parsed)) return parsed;
        return SectionCompression.parse(SectionCompression.defaultName());
    }

    public static Path worldDirectory(Path saveDirectory, String worldId) {
        return saveDirectory.resolve(worldId).resolve("lods");
    }
}
