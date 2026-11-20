package rodozodo.infinite.world;

import rodozodo.infinite.LodEngine;
import rodozodo.infinite.Log;
import rodozodo.infinite.storage.LodStorage;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

public final class LodWorld {
    public static final int BLOCKS_CHANGED = 1, CHILDREN_CHANGED = 2;
    private static final long IDLE_AFTER_MILLIS = 10_000;
    private static final int RESTING_SHARE = 8;

    public interface UpdateListener {
        void sectionUpdated(LodSection section, int kinds, int neighbours);
    }

    public final LodStorage storage;
    public final @Nullable LodEngine engine;
    private final IdRegistry ids;
    private final SectionCache sections;
    private final long idleLimit;
    private final AtomicInteger refs = new AtomicInteger();
    private volatile @Nullable UpdateListener listener;
    private volatile @Nullable SaveListener saveListener;
    private volatile boolean live = true;
    private volatile long lastActive = System.currentTimeMillis();

    public LodWorld(LodStorage storage, SaveQueue saver, @Nullable LodEngine engine) {
        this(storage, saver, engine, 0);
    }

    public LodWorld(LodStorage storage, SaveQueue saver, @Nullable LodEngine engine, long idleBytes) {
        this.storage = storage;
        this.engine = engine;
        this.ids = new IdRegistry(storage);
        long expanded = SectionCache.EXPANDED_BYTES;
        long idle;
        long overrideMiB = Long.getLong("infinite.idleCacheMiB", 0);
        if (overrideMiB > 0) {
            idle = Math.clamp(overrideMiB << 20, 16 * expanded, 65536 * expanded);
        } else if (idleBytes > 0) {
            idle = Math.clamp(idleBytes, 16 * expanded, 65536 * expanded);
        } else {
            idle = Math.clamp(Runtime.getRuntime().maxMemory() / 8, 256 * expanded, 4096 * expanded);
        }
        this.sections = new SectionCache(this, storage, saver, idle);
        this.idleLimit = idle;
    }

    public IdRegistry ids() {
        return this.ids;
    }

    public boolean isLive() {
        return this.live;
    }

    void checkLive() {
        if (!this.live) throw new IllegalStateException("LOD world is closed");
    }

    public void setUpdateListener(@Nullable UpdateListener listener) {
        this.listener = listener;
    }

    public LodSection acquire(long pos) {
        this.checkLive();
        this.lastActive = System.currentTimeMillis();
        return this.sections.acquire(pos, false, false);
    }

    public LodSection acquire(int level, int x, int y, int z) {
        return this.acquire(LodPos.pack(level, x, y, z));
    }

    public @Nullable LodSection acquireIfStored(long pos) {
        this.checkLive();
        this.lastActive = System.currentTimeMillis();
        return this.sections.acquire(pos, true, false);
    }

    public @Nullable LodSection acquireIfStored(int level, int x, int y, int z) {
        return this.acquireIfStored(LodPos.pack(level, x, y, z));
    }

    public @Nullable LodSection acquireIfStoredForReading(long pos) {
        this.checkLive();
        this.lastActive = System.currentTimeMillis();
        return this.sections.acquire(pos, true, true);
    }

    public boolean hasUnsavedChanges(long pos) {
        return this.sections.unsaved(pos);
    }

    public int unsavedEdits(long pos, int slot) {
        return this.sections.unsavedEdits(pos, slot);
    }

    public interface SaveListener {
        void sectionSaved(long pos, int[] edits);
    }

    public void setSaveListener(@Nullable SaveListener listener) {
        this.saveListener = listener;
    }

    public void removeSaveListener(SaveListener listener) {
        if (this.saveListener == listener) this.saveListener = null;
    }

    void saved(LodSection section, int[] edits) {
        var listener = this.saveListener;
        if (listener != null) listener.sectionSaved(section.key, edits);
    }

    public void markChanged(LodSection section, int kinds, int neighbours) {
        this.checkLive();
        if (section.cache != this.sections) throw new IllegalArgumentException(section + " is from another world");
        section.hasData = true;
        section.countEdit(neighbours);
        section.dirty = true;
        var listener = this.listener;
        if (listener != null) listener.sectionUpdated(section, kinds, neighbours);
    }

    public int sectionsInUse() {
        return this.sections.inUseCount();
    }

    public int idleSections() {
        return this.sections.idleCount();
    }

    public void restIdle(boolean resting) {
        this.sections.limitIdle(resting ? this.idleLimit / RESTING_SHARE : this.idleLimit);
    }

    public String idleMemory() {
        return String.format("idle %d of %d MiB", this.sections.idleBytes() >> 20, this.sections.idleLimit() >> 20);
    }

    public @Nullable String cacheTiming() {
        return SectionCache.describeTiming();
    }

    public void acquireRef() {
        this.checkLive();
        this.refs.incrementAndGet();
        this.lastActive = System.currentTimeMillis();
    }

    public void releaseRef() {
        this.checkLive();
        if (this.refs.decrementAndGet() < 0) throw new IllegalStateException("LOD world released more often than acquired");
        this.lastActive = System.currentTimeMillis();
    }

    public void markActive() {
        this.checkLive();
        this.lastActive = System.currentTimeMillis();
    }

    public boolean isInUse() {
        this.checkLive();
        return this.refs.get() != 0 || this.sections.inUseCount() != 0;
    }

    public boolean isIdle() {
        if (this.isInUse()) {
            this.lastActive = System.currentTimeMillis();
            return false;
        }
        return System.currentTimeMillis() - this.lastActive > IDLE_AFTER_MILLIS;
    }

    public boolean beginClose() {
        if (!this.live) return false;
        if (this.refs.get() != 0 || this.sections.inUseCount() != 0) return false;
        this.live = false;
        return true;
    }

    public void finishClose() {
        if (this.live) throw new IllegalStateException("LOD world is still open");
        this.sections.clearIdle();
        try {
            this.storage.flush();
        } catch (Exception e) {
            Log.error("Could not flush LoD storage", e);
        }
        try {
            this.storage.close();
        } catch (Exception e) {
            Log.error("Could not close LoD storage", e);
        }
    }

    public void close() {
        if (!this.beginClose()) {
            throw new IllegalStateException("Closing a LOD world that is in use or already closed");
        }
        this.finishClose();
    }
}
