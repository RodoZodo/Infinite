package rodozodo.infinite.world;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;


public final class PackedCells {
    public static final int VOLUME = LodSection.VOLUME;
    // Little-endian layouts shared with storage and the mesher
    public static final ValueLayout.OfChar WIDE = ValueLayout.JAVA_CHAR.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
    public static final ValueLayout.OfLong EIGHT = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
    public static final int WIDE_PAGE_CELLS = CellPages.PAGE_BYTES / 2;
    private static final int WIDE_SHIFT = Integer.numberOfTrailingZeros(WIDE_PAGE_CELLS), WIDE_MASK = WIDE_PAGE_CELLS - 1;

    private int[] paletteStates = new int[256];
    private byte[] paletteLight = new byte[256];
    private int paletteSize;
    private int indexBytes;
    private MemorySegment narrow = CellPages.take();
    private MemorySegment wideLow, wideHigh;

    public void reset(int size, int indexBytes) {
        if (size > this.paletteStates.length) {
            int capacity = Integer.highestOneBit(size - 1) << 1;
            this.paletteStates = new int[capacity];
            this.paletteLight = new byte[capacity];
        }
        if (indexBytes == 2 && this.wideLow == null) {
            this.wideLow = CellPages.take();
            this.wideHigh = CellPages.take();
        }
        this.paletteSize = size;
        this.indexBytes = indexBytes;
    }

    void free() {
        CellPages.give(this.narrow);
        this.narrow = null;
        if (this.wideLow != null) {
            CellPages.give(this.wideLow);
            CellPages.give(this.wideHigh);
            this.wideLow = this.wideHigh = null;
        }
    }

    public int[] paletteStates() {
        return this.paletteStates;
    }

    public byte[] paletteLight() {
        return this.paletteLight;
    }

    public MemorySegment narrowIndices() {
        return this.narrow;
    }

    public MemorySegment widePage(int page) {
        return page == 0 ? this.wideLow : this.wideHigh;
    }

    public int paletteSize() {
        return this.paletteSize;
    }

    public int indexBytes() {
        return this.indexBytes;
    }

    public int index(int cell) {
        return switch (this.indexBytes) {
            case 0 -> 0;
            case 1 -> this.narrow.get(ValueLayout.JAVA_BYTE, cell) & 0xFF;
            default -> this.widePage(cell >>> WIDE_SHIFT).getAtIndex(WIDE, cell & WIDE_MASK);
        };
    }

    public int state(int cell) {
        return this.paletteStates[this.index(cell)];
    }

    public int light(int cell) {
        return this.paletteLight[this.index(cell)] & 0xFF;
    }

    public int bytes() {
        return VOLUME + (this.wideLow == null ? 0 : VOLUME * 2) + this.paletteStates.length * 5;
    }

    public int solidCount() {
        int[] paletteStates = this.paletteStates;
        if (this.indexBytes == 0) return Voxel.isAir(paletteStates[0]) ? 0 : VOLUME;
        int[] uses = new int[this.paletteSize];
        if (this.indexBytes == 1) {
            var narrow = this.narrow;
            for (int i = 0; i < VOLUME; i++) uses[narrow.get(ValueLayout.JAVA_BYTE, i) & 0xFF]++;
        } else {
            for (int page = 0; page < 2; page++) {
                var wide = this.widePage(page);
                for (int i = 0; i < WIDE_PAGE_CELLS; i++) uses[wide.getAtIndex(WIDE, i)]++;
            }
        }
        int solid = 0;
        for (int i = 0; i < uses.length; i++) if (!Voxel.isAir(paletteStates[i])) solid += uses[i];
        return solid;
    }

    public void expandInto(SectionCells cells) {
        int[] paletteStates = this.paletteStates;
        byte[] paletteLight = this.paletteLight;
        if (this.indexBytes == 0) {
            cells.fill(paletteStates[0], paletteLight[0] & 0xFF);
            return;
        }
        var light = cells.light();
        var narrow = this.narrow;
        boolean wide = this.indexBytes == 2;
        for (int page = 0; page < SectionCells.STATE_PAGES; page++) {
            var states = cells.statePage(page);
            int first = page * SectionCells.PAGE_CELLS;
            for (int i = 0; i < SectionCells.PAGE_CELLS; i++) {
                int cell = first + i;
                int index = wide ? this.widePage(cell >>> WIDE_SHIFT).getAtIndex(WIDE, cell & WIDE_MASK)
                        : narrow.get(ValueLayout.JAVA_BYTE, cell) & 0xFF;
                states.setAtIndex(SectionCells.STATE, i, paletteStates[index]);
                light.set(SectionCells.LIGHT, cell, paletteLight[index]);
            }
        }
    }
}
