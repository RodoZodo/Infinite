package rodozodo.infinite.util;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongRBTreeSet;

// Best-fit range allocator over an address space split into equal-sized pages
// Keeping every allocation within a single page and preferring lower pages.
// Not thread-safe.
public final class PagedRangeAllocator {
    public static final long NONE = -1;
    private final long pageSize;
    private final long capacity;
    private final LongRBTreeSet[] freeBySize;
    private final Long2LongOpenHashMap freeByStart = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap freeByEnd = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap taken = new Long2IntOpenHashMap();
    private final long[] usedByPage;
    private long used;

    public PagedRangeAllocator(long capacity, long pageSize) {
        if (pageSize <= 0 || pageSize > Integer.MAX_VALUE) throw new IllegalArgumentException("Page size must be 1 to 2^31-1");
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.pageSize = pageSize;
        this.capacity = capacity;
        int pages = (int) ((capacity + pageSize - 1) / pageSize);
        this.freeBySize = new LongRBTreeSet[pages];
        this.usedByPage = new long[pages];
        this.freeByStart.defaultReturnValue(-1);
        this.freeByEnd.defaultReturnValue(-1);
        this.taken.defaultReturnValue(-1);
        for (int page = 0; page < pages; page++) {
            this.freeBySize[page] = new LongRBTreeSet();
            long start = page * pageSize;
            this.addFree(start, Math.min(start + pageSize, capacity));
        }
    }

    private static long sizeKey(long size, long offset) {
        return size << 32 | offset;
    }

    private void addFree(long start, long end) {
        int page = (int) (start / this.pageSize);
        this.freeBySize[page].add(sizeKey(end - start, start - page * this.pageSize));
        this.freeByStart.put(start, end);
        this.freeByEnd.put(end, start);
    }

    private void removeFree(long start, long end) {
        int page = (int) (start / this.pageSize);
        this.freeBySize[page].remove(sizeKey(end - start, start - page * this.pageSize));
        this.freeByStart.remove(start);
        this.freeByEnd.remove(end);
    }

    public long allocate(int size) {
        return this.allocate(size, -1, false);
    }

    public long allocate(int size, int avoid, boolean usedOnly) {
        if (size <= 0 || size > this.pageSize) throw new IllegalArgumentException("Range size must be 1 to " + this.pageSize + ", got " + size);
        for (int page = 0; page < this.freeBySize.length; page++) {
            if (page == avoid || (usedOnly && this.usedByPage[page] == 0)) continue;
            var free = this.freeBySize[page];
            if (free.isEmpty() || free.lastLong() >>> 32 < size) continue;
            long fit = free.tailSet(sizeKey(size, 0)).firstLong();
            long start = page * this.pageSize + (fit & 0xFFFFFFFFL);
            long end = start + (fit >>> 32);
            this.removeFree(start, end);
            if (end > start + size) this.addFree(start + size, end);
            this.taken.put(start, size);
            this.used += size;
            this.usedByPage[page] += size;
            return start;
        }
        return NONE;
    }

    public int free(long start) {
        int size = this.taken.remove(start);
        if (size < 0) throw new IllegalArgumentException("No range starts at " + start);
        this.used -= size;
        this.usedByPage[(int) (start / this.pageSize)] -= size;
        long from = start, to = start + size;
        if (from % this.pageSize != 0) {
            long before = this.freeByEnd.get(from);
            if (before >= 0) {
                this.removeFree(before, from);
                from = before;
            }
        }
        if (to % this.pageSize != 0) {
            long after = this.freeByStart.get(to);
            if (after >= 0) {
                this.removeFree(to, after);
                to = after;
            }
        }
        this.addFree(from, to);
        return size;
    }

    public int freeRanges() {
        return this.freeByStart.size();
    }

    public long largestFree() {
        long largest = 0;
        for (var free : this.freeBySize) if (!free.isEmpty()) largest = Math.max(largest, free.lastLong() >>> 32);
        return largest;
    }

    public int pagesInUse() {
        int pages = 0;
        for (long used : this.usedByPage) if (used != 0) pages++;
        return pages;
    }

    public int pages() {
        return this.usedByPage.length;
    }

    public long usedIn(int page) {
        return this.usedByPage[page];
    }

    public int sizeOf(long start) {
        return this.taken.get(start);
    }

    public long used() {
        return this.used;
    }

    public long capacity() {
        return this.capacity;
    }
}
