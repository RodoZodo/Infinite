package rodozodo.infinite.client;

import rodozodo.infinite.LodEngine;
import rodozodo.infinite.LodWorldKey;
import rodozodo.infinite.Log;
import rodozodo.infinite.client.compat.ImportProgressBar;
import rodozodo.infinite.client.compat.SodiumBackpressure;
import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.render.ViewMotion;
import rodozodo.infinite.importers.LodImport;
import rodozodo.infinite.importers.RegionImport;
import rodozodo.infinite.storage.LodStorage;
import rodozodo.infinite.storage.LogLodStorage;
import rodozodo.infinite.storage.StorageSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public final class ClientLodEngine extends LodEngine {
    private final Path saveFolder;
    private final StorageSettings storage;

    private ClientLodEngine(Path saveFolder, StorageSettings storage) {
        super(SodiumBackpressure::ingestMayRun);
        this.saveFolder = saveFolder;
        this.storage = storage;
        this.ingestQueue.setThreadShare(Math.max(1, Runtime.getRuntime().availableProcessors() / 3));
        this.workers().setDedicatedGate(ViewMotion::still);
        this.updateDedicatedThreads();
    }

    public static @Nullable ClientLodEngine create(@Nullable ServerData server) {
        var folder = saveFolderFor(Minecraft.getInstance(), server).toAbsolutePath().normalize();
        var storage = StorageSettings.load(folder);
        if (storage.disabled) {
            Log.info("LoD's disabled for " + folder);
            return null;
        }
        return new ClientLodEngine(folder, storage);
    }

    public Path saveFolder() {
        return this.saveFolder;
    }

    public StorageSettings storage() {
        return this.storage;
    }

    @Override
    protected LodStorage openStorage(LodWorldKey id) {
        var directory = StorageSettings.worldDirectory(this.saveFolder, id.storageName());
        return new LogLodStorage(directory.resolve(StorageSettings.LOG), this.storage.compressionLevel, this.storage.codec());
    }

    @Override
    protected int dedicatedThreadTarget() {
        Integer dedicated = Integer.getInteger("infinite.dedicatedThreads");
        if (dedicated != null) return dedicated;
        return ClientSettings.defaultRefinementThreads();
    }

    @Override
    public boolean ingestEnabled() {
        return ClientSettings.get().ingest;
    }

    @Override
    protected LodImport.Listener importListener(LodImport<?, ?> job) {
        var observer = job instanceof RegionImport region ? region.observer() : null;
        var bar = new ImportProgressBar(job, observer != null);
        if (observer == null) return bar;
        return new LodImport.Listener() {
            @Override
            public void progress(long done, long expected) {
                bar.progress(done, expected);
                observer.progress(done, expected);
            }

            @Override
            public void finished(LodImport.Result result) {
                bar.finished(result);
                observer.finished(result);
            }
        };
    }

    private static Path saveFolderFor(Minecraft minecraft, @Nullable ServerData server) {
        var singleplayer = minecraft.getSingleplayerServer();
        if (singleplayer != null) return singleplayer.getWorldPath(LevelResource.ROOT).resolve("infinite");
        var servers = minecraft.gameDirectory.toPath().resolve(".infinite").resolve("saves");
        if (server == null) {
            return servers.resolve("UNKNOWN");
        }
        if (server.isRealm()) return servers.resolve("realms");
        return servers.resolve(server.ip.replaceAll("[<>:\"/\\\\|?*]", "_"));
    }
}
