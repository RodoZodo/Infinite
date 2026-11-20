package rodozodo.infinite.world;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

final class CellPages {
    static final int PAGE_BYTES = 32768;
    private static final int KEEP_AFTER_CLOSE = 1024;
    private static final ConcurrentLinkedQueue<MemorySegment> SPARE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SPARE_COUNT = new AtomicInteger();

    private CellPages() {}

    static MemorySegment take() {
        var page = SPARE.poll();
        if (page == null) return Arena.ofAuto().allocate(PAGE_BYTES, 64);
        SPARE_COUNT.decrementAndGet();
        return page;
    }

    static void give(MemorySegment page) {
        SPARE.add(page);
        SPARE_COUNT.incrementAndGet();
    }

    static void trim() {
        while (SPARE_COUNT.get() > KEEP_AFTER_CLOSE && SPARE.poll() != null) SPARE_COUNT.decrementAndGet();
    }
}