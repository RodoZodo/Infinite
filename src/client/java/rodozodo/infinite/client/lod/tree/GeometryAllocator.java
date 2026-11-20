package rodozodo.infinite.client.lod.tree;

import rodozodo.infinite.client.lod.mesh.LodMesh;
import rodozodo.infinite.util.IdBitSet;
import rodozodo.infinite.util.NativeBuffer;
import rodozodo.infinite.util.PagedRangeAllocator;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.function.LongConsumer;

// Manages GPU geometry store allocations, section metadata, and page compaction on the tree worker thread.
// Allocates section IDs and quad runs without touching the GPU directly, staging uploads, copies, and metadata updates.

final class GeometryAllocator {

    // 48 byte section metadata layout (common / section.glsl): posHi, posLo, bounds, firstQuad, 4x packed group sizes, discardGroups, aoTables, 2x0
    static final int METADATA_BYTES = 48;
    private static final int METADATA_INTS = METADATA_BYTES / 4;
    private static final int RESERVE_STEP = 16;

    private final IdBitSet ids;
    private final PagedRangeAllocator space;
    private final long capacityQuads, pageQuads;
    private int[] metadata = new int[METADATA_INTS * 4096];
    private long reservedQuads, storedQuads;
    private int[] quadCounts = new int[4096];
    private long didNotFit;

    private final Int2ObjectOpenHashMap<NativeBuffer> uploads = new Int2ObjectOpenHashMap<>();
    private final IntOpenHashSet changedIds = new IntOpenHashSet();
    private final LongArrayList freedRuns = new LongArrayList();

    private int victim = -1;
    private final IntArrayList victimIds = new IntArrayList();
    private int victimNext;
    private long abandonedAt = -1;

    // Key: destination firstQuad; value: (source firstQuad << 32) | quadCount
    private final Long2LongOpenHashMap moves = new Long2LongOpenHashMap();

    // Source runs kept reserved until the GPU copy batch has been submitted
    private final LongArrayList movedFrom = new LongArrayList();
    private long movedQuads;
    private int pagesEmptied;

    GeometryAllocator(int maxSections, long capacityBytes, long pageBytes) {
        this.ids = new IdBitSet(maxSections);
        this.capacityQuads = capacityBytes / 8;
        this.pageQuads = pageBytes / 8;
        this.space = new PagedRangeAllocator(this.capacityQuads, pageBytes / 8);
    }

    int add(LodMesh mesh) {
        int quads = mesh.quadCount();
        int reserved = (quads + RESERVE_STEP - 1) / RESERVE_STEP * RESERVE_STEP;
        long first = this.space.allocate(reserved, this.victim, false);
        if (first == PagedRangeAllocator.NONE && this.victim >= 0) {
            this.victim = -1;
            first = this.space.allocate(reserved);
        }
        if (first == PagedRangeAllocator.NONE) {
            if (this.capacityQuads - this.reservedQuads >= reserved) this.didNotFit++;
            return -1;
        }
        int id = this.ids.allocate();
        if (id < 0) {
            this.space.free(first);
            return -1;
        }
        this.reservedQuads += reserved;
        this.storedQuads += quads;
        if (id >= this.quadCounts.length) this.quadCounts = Arrays.copyOf(this.quadCounts, Math.max(this.quadCounts.length * 2, id + 1));
        this.quadCounts[id] = quads;
        if ((id + 1) * METADATA_INTS > this.metadata.length) {
            this.metadata = Arrays.copyOf(this.metadata, Math.max(this.metadata.length * 2, (id + 1) * METADATA_INTS));
        }
        int at = id * METADATA_INTS;
        this.metadata[at] = (int) (mesh.position >> 32);
        this.metadata[at + 1] = (int) mesh.position;
        this.metadata[at + 2] = mesh.bounds;
        this.metadata[at + 3] = (int) first;
        for (int pair = 0; pair < 4; pair++) {
            this.metadata[at + 4 + pair] = mesh.groupSize(pair * 2) | mesh.groupSize(pair * 2 + 1) << 16;
        }
        this.metadata[at + 8] = mesh.discardGroups;
        this.metadata[at + 9] = mesh.aoTables;
        this.uploads.put((int) first, mesh.quads());
        this.changedIds.add(id);
        return id;
    }

    void remove(int id) {
        int at = id * METADATA_INTS;
        int first = this.metadata[at + 3];
        this.reservedQuads -= this.space.free(Integer.toUnsignedLong(first));
        this.storedQuads -= this.quadCounts[id];
        this.quadCounts[id] = 0;
        var upload = this.uploads.remove(first);
        if (upload != null) upload.free();
        else this.freedRuns.add(Integer.toUnsignedLong(first));
        this.moves.remove(Integer.toUnsignedLong(first));
        Arrays.fill(this.metadata, at, at + METADATA_INTS, 0);
        if (!this.ids.free(id)) throw new IllegalStateException("Section " + id + " was not stored");
        this.changedIds.add(id);
    }

    void takeFreedRuns(LongConsumer consumer) {
        this.freedRuns.forEach(consumer);
        this.freedRuns.clear();
    }

    boolean compact(long budget) {
        if (this.victim < 0 && !this.chooseVictim()) return false;
        long moved = 0;
        while (this.victimNext < this.victimIds.size() && moved < budget) {
            int id = this.victimIds.getInt(this.victimNext);
            int at = id * METADATA_INTS;
            long first = Integer.toUnsignedLong(this.metadata[at + 3]);
            if (this.quadCounts[id] == 0 || first / this.pageQuads != this.victim) {
                this.victimNext++;
                continue;
            }
            int size = this.space.sizeOf(first);
            long to = this.space.allocate(size, this.victim, true);
            if (to == PagedRangeAllocator.NONE) {
                this.victim = -1;
                this.abandonedAt = this.reservedQuads;
                return moved != 0;
            }
            this.victimNext++;
            this.reservedQuads += size;
            this.metadata[at + 3] = (int) to;
            this.changedIds.add(id);
            var upload = this.uploads.remove((int) first);
            if (upload != null) {
                this.uploads.put((int) to, upload);
                this.reservedQuads -= this.space.free(first);
            } else {
                this.moves.put(to, first << 32 | size);
                this.movedFrom.add(first);
            }
            moved += size;
        }
        this.movedQuads += moved;
        if (this.victimNext >= this.victimIds.size()) {
            this.victim = -1;
            this.abandonedAt = -1;
            this.pagesEmptied++;
        }
        return moved != 0;
    }

    private boolean chooseVictim() {
        int inUse = 0, least = -1;
        for (int page = 0; page < this.space.pages(); page++) {
            long used = this.space.usedIn(page);
            if (used == 0) continue;
            inUse++;
            if (least < 0 || used <= this.space.usedIn(least)) least = page;
        }
        if (inUse < 2) return false;
        long free = inUse * this.pageQuads - this.reservedQuads;
        if (free < this.pageQuads + this.pageQuads / 2) return false;
        if (this.abandonedAt >= 0 && this.reservedQuads > this.abandonedAt - this.pageQuads / 8) return false;
        this.victim = least;
        this.victimIds.clear();
        this.victimNext = 0;
        for (int id = 0, limit = this.idLimit(); id < limit; id++) {
            if (this.quadCounts[id] != 0 && Integer.toUnsignedLong(this.metadata[id * METADATA_INTS + 3]) / this.pageQuads == least) {
                this.victimIds.add(id);
            }
        }
        return true;
    }

    boolean holdsMoved() {
        return !this.movedFrom.isEmpty();
    }

    void releaseMoved() {
        for (int i = 0; i < this.movedFrom.size(); i++) this.reservedQuads -= this.space.free(this.movedFrom.getLong(i));
        this.movedFrom.clear();
    }

    void takeMoves(MoveConsumer consumer) {
        for (var entry : this.moves.long2LongEntrySet()) {
            consumer.accept(entry.getLongKey(), entry.getLongValue() >>> 32, (int) entry.getLongValue());
        }
        this.moves.clear();
    }

    interface MoveConsumer {
        void accept(long toQuad, long fromQuad, int quads);
    }

    void pagesInUse(long[] bits) {
        Arrays.fill(bits, 0);
        for (int page = 0; page < this.space.pages(); page++) {
            if (this.space.usedIn(page) != 0) bits[page >> 6] |= 1L << page;
        }
    }

    int pages() {
        return this.space.pages();
    }

    void takeUploads(UploadConsumer consumer) {
        for (var entry : this.uploads.int2ObjectEntrySet()) {
            consumer.accept(Integer.toUnsignedLong(entry.getIntKey()), entry.getValue());
        }
        this.uploads.clear();
    }

    interface UploadConsumer {
        void accept(long firstQuad, NativeBuffer quads);
    }

    IntOpenHashSet changedIds() {
        return this.changedIds;
    }

    void writeMetadata(int id, int half, long address) {
        int at = id * METADATA_INTS + half * 4;
        for (int i = 0; i < 4; i++) {
            MemoryUtil.memPutInt(address + i * 4L, this.metadata[at + i]);
        }
    }

    int idLimit() {
        return this.ids.highest() + 1;
    }

    int sectionCount() {
        return this.ids.count();
    }

    String spaceSummary() {
        long inPages = this.space.pagesInUse() * this.pageQuads;
        return String.format("Space: %d MiB for %d MiB of quads, %d free pieces (largest %.1f MiB), %d MiB free in %d pages in use, %d did not fit, %d MiB moved to empty %d pages",
                this.reservedQuads >> 17, this.storedQuads >> 17, this.space.freeRanges(), this.space.largestFree() / 131072.0,
                (inPages - this.reservedQuads) >> 17, this.space.pagesInUse(), this.didNotFit, this.movedQuads >> 17, this.pagesEmptied);
    }

    long usedBytes() {
        return this.reservedQuads * 8;
    }

    long capacityBytes() {
        return this.capacityQuads * 8;
    }

    void free() {
        this.uploads.values().forEach(NativeBuffer::free);
        this.uploads.clear();
    }
}
