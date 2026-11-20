package rodozodo.infinite.world;

import rodozodo.infinite.Log;
import rodozodo.infinite.storage.LodStorage;
import rodozodo.infinite.storage.LogLodStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

final class SectionCache {
    private static final int STRIPES = 64;
    private static final int SPARE_ARRAYS = 64;
    private static final ConcurrentLinkedQueue<SectionCells> SPARES = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SPARE_COUNT = new AtomicInteger();
    private static final ConcurrentLinkedQueue<PackedCells> SPARE_PACKED = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SPARE_PACKED_COUNT = new AtomicInteger();
    private static final int EMPTY_LIGHT = Voxel.lightOf(15, 0);

    private static final boolean TIMING = Boolean.getBoolean("infinite.cacheTiming");
    private static final LongAdder ACQUIRED = new LongAdder(), LOADED = new LongAdder(), WAITED = new LongAdder(),
            LOCK_NANOS = new LongAdder(), LOAD_NANOS = new LongAdder(), WAIT_NANOS = new LongAdder(), RELOADED = new LongAdder();
    private static final java.util.Set<Long> LOADED_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static final int EXPANDED_BYTES = LodSection.VOLUME * 5;

    private static final class Stripe {
        final Long2ObjectOpenHashMap<LodSection> inUse = new Long2ObjectOpenHashMap<>();
        final Long2ObjectLinkedOpenHashMap<LodSection> idle = new Long2ObjectLinkedOpenHashMap<>();
        long idleBytes;
    }

    private final Stripe[] stripes = new Stripe[STRIPES];
    private volatile long idleBytesPerStripe;
    private final LodWorld world;
    private final LodStorage storage;
    private final SaveQueue saver;
    private final AtomicInteger inUse = new AtomicInteger();
    private final AtomicInteger idle = new AtomicInteger();

    SectionCache(LodWorld world, LodStorage storage, SaveQueue saver, long idleBytes) {
        this.world = world;
        this.storage = storage;
        this.saver = saver;
        this.idleBytesPerStripe = Math.max(EXPANDED_BYTES, idleBytes / STRIPES);
        for (int i = 0; i < STRIPES; i++) this.stripes[i] = new Stripe();
    }

    private Stripe stripe(long key) {
        long mixed = key * 0x9E3779B97F4A7C15L;
        return this.stripes[(int) (mixed >>> 58)];
    }

    LodSection acquire(long key, boolean onlyIfStored, boolean packed) {
        var stripe = this.stripe(key);
        LodSection section;
        boolean load = false;
        long started = TIMING ? System.nanoTime() : 0;
        synchronized (stripe) {
            section = stripe.inUse.get(key);
            if (section == null) {
                section = stripe.idle.remove(key);
                if (section != null) {
                    stripe.idleBytes -= section.idleBytes;
                    this.idle.decrementAndGet();
                } else {
                    section = new LodSection(key, this, packed ? null : takeCells());
                    load = true;
                }
                stripe.inUse.put(key, section);
                this.inUse.incrementAndGet();
            }
            section.refs++;
        }
        long locked = TIMING ? System.nanoTime() : 0;

        if (load) {
            this.load(section);
        } else {
            section.awaitLoaded();
        }
        if (TIMING) {
            long done = System.nanoTime();
            ACQUIRED.increment();
            LOCK_NANOS.add(locked - started);
            if (load) {
                LOADED.increment();
                if (!LOADED_KEYS.add(key)) RELOADED.increment();
                LOAD_NANOS.add(done - locked);
            } else if (done - locked > 1_000) {
                WAITED.increment();
                WAIT_NANOS.add(done - locked);
            }
        }

        if (onlyIfStored && !section.hasData) {
            this.release(section);
            return null;
        }
        return section;
    }

    private void load(LodSection section) {
        int result;
        try {
            if (section.cells == null) {
                var packed = takePacked();
                result = this.storage.loadPacked(section, packed);
                if (result == LodStorage.LOADED) {
                    section.packed = packed;
                    if (section.level == 0) section.setSolidCount(LodSection.UNCOUNTED);
                    section.hasData = true;
                    section.markLoaded();
                    return;
                }
                givePacked(packed);
                section.cells = takeCells();
                if (result == LodStorage.NOT_PACKED) result = this.storage.load(section);
            } else {
                result = this.storage.load(section);
            }
        } catch (Throwable e) {
            Log.error("Could not load LoD section " + LodPos.describe(section.key), e);
            result = LodStorage.CORRUPT;
            if (section.cells == null) section.cells = takeCells();
        }
        if (result == LodStorage.LOADED) {
            section.hasData = true;
        } else {
            section.cells.fill(0, EMPTY_LIGHT);
            section.setChildMask((byte) 0);
            section.setSolidCount(0);
        }
        section.markLoaded();
    }

    boolean unsaved(long key) {
        var stripe = this.stripe(key);
        synchronized (stripe) {
            var section = stripe.inUse.get(key);
            return section != null && (section.dirty || section.saving);
        }
    }

    int unsavedEdits(long key, int slot) {
        var stripe = this.stripe(key);
        synchronized (stripe) {
            var section = stripe.inUse.get(key);
            return section != null && (section.dirty || section.saving) ? section.edits(slot) : -1;
        }
    }

    void retain(LodSection section) {
        var stripe = this.stripe(section.key);
        synchronized (stripe) {
            if (section.refs <= 0) throw new IllegalStateException("Acquired " + section + " which nobody holds");
            section.refs++;
        }
    }

    void release(LodSection section) {
        var stripe = this.stripe(section.key);
        LodSection evicted = null;
        synchronized (stripe) {
            if (section.refs <= 0) throw new IllegalStateException("Released " + section + " more often than acquired");
            if (--section.refs > 0) return;
            if (section.dirty) {
                // SaveQueue takes over the last reference until the write finishes
                section.refs = 1;
            } else {
                evicted = this.makeIdle(stripe, section);
                section = null;
            }
        }
        if (section != null) this.saver.save(section);
        giveEvicted(evicted);
    }

    boolean finishSave(LodSection section) {
        var stripe = this.stripe(section.key);
        LodSection evicted = null;
        synchronized (stripe) {
            if (--section.refs > 0) return false;
            if (section.dirty) {
                section.refs = 1;
                return true;
            }
            evicted = this.makeIdle(stripe, section);
        }
        giveEvicted(evicted);
        return false;
    }

    static String describeTiming() {
        long acquired = ACQUIRED.sum();
        if (!TIMING || acquired == 0) return null;
        long loaded = LOADED.sum(), waited = WAITED.sum();
        long loadNanos = LOAD_NANOS.sum(), waitNanos = WAIT_NANOS.sum();
        String files = LogLodStorage.describeTiming();
        return String.format(java.util.Locale.ROOT,
                "Cache us/lookup: %.1f (lock %.1f, load %.1f x%d, wait %.1f x%d); %d lookups, %d loaded (%d again), %d waited",
                (LOCK_NANOS.sum() + loadNanos + waitNanos) / (double) acquired / 1e3,
                LOCK_NANOS.sum() / (double) acquired / 1e3,
                loaded == 0 ? 0 : loadNanos / (double) loaded / 1e3, loaded,
                waited == 0 ? 0 : waitNanos / (double) waited / 1e3, waited, acquired, loaded, RELOADED.sum(), waited)
                + (files == null ? "" : "; " + files);
    }

    private LodSection makeIdle(Stripe stripe, LodSection section) {
        stripe.inUse.remove(section.key);
        this.inUse.decrementAndGet();
        // Unreferenced idle sections cannot expand concurrently while sized here
        var packed = section.packed;
        int bytes = (section.cells != null ? EXPANDED_BYTES : 0) + (packed != null ? packed.bytes() : 0);
        section.idleBytes = bytes;
        stripe.idleBytes += bytes;
        stripe.idle.putAndMoveToLast(section.key, section);
        this.idle.incrementAndGet();
        LodSection evicted = null;
        long limit = this.idleBytesPerStripe;
        while (stripe.idleBytes > limit && stripe.idle.size() > 1) {
            var oldest = stripe.idle.removeFirst();
            this.idle.decrementAndGet();
            stripe.idleBytes -= oldest.idleBytes;
            oldest.nextEvicted = evicted;
            evicted = oldest;
        }
        return evicted;
    }

    private static void giveEvicted(LodSection evicted) {
        while (evicted != null) {
            var next = evicted.nextEvicted;
            evicted.nextEvicted = null;
            giveCells(evicted);
            evicted = next;
        }
    }

    int inUseCount() {
        return this.inUse.get();
    }

    int idleCount() {
        return this.idle.get();
    }

    void limitIdle(long idleBytes) {
        long perStripe = Math.max(EXPANDED_BYTES, idleBytes / STRIPES);
        boolean shrinks = perStripe < this.idleBytesPerStripe;
        this.idleBytesPerStripe = perStripe;
        if (!shrinks) return;
        for (var stripe : this.stripes) {
            LodSection evicted = null;
            synchronized (stripe) {
                while (stripe.idleBytes > perStripe && !stripe.idle.isEmpty()) {
                    var oldest = stripe.idle.removeFirst();
                    this.idle.decrementAndGet();
                    stripe.idleBytes -= oldest.idleBytes;
                    oldest.nextEvicted = evicted;
                    evicted = oldest;
                }
            }
            giveEvicted(evicted);
        }
        CellPages.trim();
    }

    long idleBytes() {
        long bytes = 0;
        // Racy read for F3 display only
        for (var stripe : this.stripes) bytes += stripe.idleBytes;
        return bytes;
    }

    long idleLimit() {
        return this.idleBytesPerStripe * STRIPES;
    }

    void clearIdle() {
        for (var stripe : this.stripes) {
            synchronized (stripe) {
                for (var section : stripe.idle.values()) giveCells(section);
                this.idle.addAndGet(-stripe.idle.size());
                stripe.idle.clear();
                stripe.idleBytes = 0;
            }
        }
        CellPages.trim();
    }

    LodWorld world() {
        return this.world;
    }

    LodStorage storage() {
        return this.storage;
    }

    static SectionCells takeCells() {
        SectionCells cells = SPARES.poll();
        if (cells == null) return new SectionCells();
        SPARE_COUNT.decrementAndGet();
        return cells;
    }

    private static PackedCells takePacked() {
        PackedCells packed = SPARE_PACKED.poll();
        if (packed == null) return new PackedCells();
        SPARE_PACKED_COUNT.decrementAndGet();
        return packed;
    }

    private static void givePacked(PackedCells packed) {
        if (SPARE_PACKED_COUNT.incrementAndGet() <= SPARE_ARRAYS) {
            SPARE_PACKED.add(packed);
        } else {
            SPARE_PACKED_COUNT.decrementAndGet();
            packed.free();
        }
    }

    private static void giveCells(LodSection section) {
        SectionCells cells = section.cells;
        PackedCells packed = section.packed;
        section.cells = null;
        section.packed = null;
        if (packed != null) givePacked(packed);
        if (cells == null) return;
        if (SPARE_COUNT.incrementAndGet() <= SPARE_ARRAYS) {
            SPARES.add(cells);
        } else {
            SPARE_COUNT.decrementAndGet();
            cells.free();
        }
    }
}