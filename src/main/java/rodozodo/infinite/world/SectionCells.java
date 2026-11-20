package rodozodo.infinite.world;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;


public final class SectionCells {
    public static final int VOLUME = LodSection.VOLUME;
    public static final ValueLayout.OfInt STATE = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfByte LIGHT = ValueLayout.JAVA_BYTE;
    public static final int PAGE_CELLS = CellPages.PAGE_BYTES / 4;
    private static final int PAGE_SHIFT = Integer.numberOfTrailingZeros(PAGE_CELLS), PAGE_MASK = PAGE_CELLS - 1;
    public static final int STATE_PAGES = VOLUME / PAGE_CELLS;

    private final MemorySegment[] states = new MemorySegment[STATE_PAGES];
    private MemorySegment light;

    public SectionCells() {
        for (int i = 0; i < STATE_PAGES; i++) this.states[i] = CellPages.take();
        this.light = CellPages.take();
    }

    void free() {
        for (int i = 0; i < STATE_PAGES; i++) {
            CellPages.give(this.states[i]);
            this.states[i] = null;
        }
        CellPages.give(this.light);
        this.light = null;
    }

    public MemorySegment statePage(int page) {
        return this.states[page];
    }

    public MemorySegment light() {
        return this.light;
    }

    public int state(int index) {
        return this.states[index >>> PAGE_SHIFT].getAtIndex(STATE, index & PAGE_MASK);
    }

    public int light(int index) {
        return this.light.get(LIGHT, index) & 0xFF;
    }

    public void set(int index, int state, int light) {
        this.states[index >>> PAGE_SHIFT].setAtIndex(STATE, index & PAGE_MASK, state);
        this.light.set(LIGHT, index, (byte) light);
    }

    public void fill(int state, int light) {
        for (var page : this.states) {
            if (state == 0) {
                page.fill((byte) 0);
            } else {
                for (int i = 0; i < PAGE_CELLS; i++) page.setAtIndex(STATE, i, state);
            }
        }
        this.light.fill((byte) light);
    }

    public void copyStates(int from, int[] into, int at, int count) {
        while (count > 0) {
            int offset = from & PAGE_MASK, run = Math.min(count, PAGE_CELLS - offset);
            MemorySegment.copy(this.states[from >>> PAGE_SHIFT], STATE, offset * 4L, into, at, run);
            from += run;
            at += run;
            count -= run;
        }
    }

    public void copyLight(int from, byte[] into, int at, int count) {
        MemorySegment.copy(this.light, LIGHT, from, into, at, count);
    }

    public void writeStates(int[] from, int at, int to, int count) {
        while (count > 0) {
            int offset = to & PAGE_MASK, run = Math.min(count, PAGE_CELLS - offset);
            MemorySegment.copy(from, at, this.states[to >>> PAGE_SHIFT], STATE, offset * 4L, run);
            to += run;
            at += run;
            count -= run;
        }
    }

    public void writeLight(byte[] from, int at, int to, int count) {
        MemorySegment.copy(from, at, this.light, LIGHT, to, count);
    }

    public boolean statesEqual(MemorySegment from, int at, int to, int count) {
        int offset = to & PAGE_MASK;
        return MemorySegment.mismatch(from, at * 4L, (at + count) * 4L,
                this.states[to >>> PAGE_SHIFT], offset * 4L, (offset + count) * 4L) < 0;
    }

    public void copyFrom(SectionCells other) {
        for (int i = 0; i < STATE_PAGES; i++) MemorySegment.copy(other.states[i], 0, this.states[i], 0, CellPages.PAGE_BYTES);
        MemorySegment.copy(other.light, 0, this.light, 0, VOLUME);
    }
}
