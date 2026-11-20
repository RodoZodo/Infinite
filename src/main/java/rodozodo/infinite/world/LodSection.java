package rodozodo.infinite.world;

import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;


public final class LodSection {
    public static final int SIZE = 32;
    public static final int VOLUME = SIZE * SIZE * SIZE;
    static final int UNCHANGED = 0, CHANGED = 1, FLIPPED = 2;
    static final int UNCOUNTED = -1;

    private static final VarHandle CHILD_MASK, SOLID_COUNT;
    private static final AtomicLong LOAD_IDS = new AtomicLong();
    static {
        try {
            var lookup = MethodHandles.lookup();
            CHILD_MASK = lookup.findVarHandle(LodSection.class, "childMask", byte.class);
            SOLID_COUNT = lookup.findVarHandle(LodSection.class, "solidCount", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public final long key;
    public final long loadId = LOAD_IDS.incrementAndGet();
    public final int level, x, y, z;
    final SectionCache cache;

    volatile SectionCells cells;

    // Retained after expansion until eviction so concurrent packed readers remain valid
    volatile PackedCells packed;

    // Guarded by the cache stripe's lock
    int refs;

    // Guarded by the cache stripe's lock
    int idleBytes;

    LodSection nextEvicted;
    private volatile boolean loaded;
    volatile boolean hasData;
    volatile boolean dirty;
    volatile boolean saving;

    // Slots 0..5 track -y/+y/-x/+x/-z/+z face edits; slot 6 (ALL_EDITS) tracks all edits
    private final java.util.concurrent.atomic.AtomicIntegerArray edits = new java.util.concurrent.atomic.AtomicIntegerArray(7);
    public static final int ALL_EDITS = 6;

    @SuppressWarnings("unused") private volatile byte childMask;
    @SuppressWarnings("unused") private volatile int solidCount;

    LodSection(long key, SectionCache cache, SectionCells cells) {
        this.key = key;
        this.level = LodPos.level(key);
        this.x = LodPos.x(key);
        this.y = LodPos.y(key);
        this.z = LodPos.z(key);
        this.cache = cache;
        this.cells = cells;
    }

    public static int index(int x, int y, int z) {
        return y << 10 | z << 5 | x;
    }

    int edits(int slot) {
        return this.edits.get(slot);
    }

    int[] edits() {
        int[] counts = new int[ALL_EDITS + 1];
        for (int slot = 0; slot <= ALL_EDITS; slot++) counts[slot] = this.edits.get(slot);
        return counts;
    }

    void countEdit(int edges) {
        for (int edge = 0; edge < ALL_EDITS; edge++) {
            if ((edges & 1 << edge) != 0) this.edits.incrementAndGet(edge);
        }
        this.edits.incrementAndGet(ALL_EDITS);
    }

    public void acquire() {
        this.cache.retain(this);
    }

    public void release() {
        this.cache.release(this);
    }

    public SectionCells cells() {
        var cells = this.cells;
        return cells != null ? cells : this.expand();
    }

    private synchronized SectionCells expand() {
        var cells = this.cells;
        if (cells == null) {
            cells = SectionCache.takeCells();
            this.packed.expandInto(cells);
            this.cells = cells;
        }
        return cells;
    }

    public @Nullable PackedCells packedCells() {
        return this.cells == null ? this.packed : null;
    }

    public byte childMask() {
        return (byte) CHILD_MASK.getVolatile(this);
    }

    public void setChildMask(byte mask) {
        CHILD_MASK.setVolatile(this, mask);
    }

    int updateChildBit(LodSection child) {
        byte bit = (byte) (1 << LodPos.childIndex(child.x, child.y, child.z));
        byte before, after;
        do {
            before = this.childMask();
            after = (byte) (child.childMask() != 0 ? before | bit : before & ~bit);
        } while (before != after && !CHILD_MASK.compareAndSet(this, before, after));
        if (before == after) return UNCHANGED;
        return (before == 0) != (after == 0) ? FLIPPED : CHANGED;
    }

    public int solidCount() {
        int count = (int) SOLID_COUNT.getVolatile(this);
        if (count != UNCOUNTED) return count;

        // Derived lazily from immutable packed cells so concurrent addSolid() deltas stay intact
        SOLID_COUNT.compareAndSet(this, UNCOUNTED, this.packed.solidCount());
        return (int) SOLID_COUNT.getVolatile(this);
    }

    public void setSolidCount(int count) {
        SOLID_COUNT.setVolatile(this, count);
    }

    boolean addSolid(int delta) {
        if ((int) SOLID_COUNT.getVolatile(this) == UNCOUNTED) this.solidCount();
        int after = (int) SOLID_COUNT.getAndAdd(this, delta) + delta;
        if (after < 0) throw new IllegalStateException("Negative solid count in " + LodPos.describe(this.key));
        byte mask = (byte) (after == 0 ? 0 : 0xFF);
        return (byte) CHILD_MASK.getAndSet(this, mask) != mask;
    }

    void markLoaded() {
        synchronized (this) {
            this.loaded = true;
            this.notifyAll();
        }
    }

    void awaitLoaded() {
        if (this.loaded) return;
        boolean interrupted = false;
        synchronized (this) {
            while (!this.loaded) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public String toString() {
        return "LodSection " + LodPos.describe(this.key);
    }
}