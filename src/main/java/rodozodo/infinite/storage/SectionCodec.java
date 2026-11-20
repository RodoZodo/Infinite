package rodozodo.infinite.storage;

import rodozodo.infinite.world.LodSection;
import rodozodo.infinite.world.PackedCells;
import rodozodo.infinite.world.SectionCells;
import rodozodo.infinite.world.Voxel;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class SectionCodec {
    static final int VERSION = 3;
    private static final int HEADER_BYTES = 16;
    private static final int ENTRY_BYTES = 5;
    private static final int VOLUME = LodSection.VOLUME;
    static final int MAX_BYTES = HEADER_BYTES + VOLUME * ENTRY_BYTES + VOLUME * 2;

    private static final int INDEX_RANGE = 1 << 16;

    private final Long2IntOpenHashMap paletteIndex = new Long2IntOpenHashMap(256);
    private final int[] paletteStates = new int[INDEX_RANGE];
    private final byte[] paletteLight = new byte[INDEX_RANGE];
    private final byte[] paletteSolid = new byte[INDEX_RANGE];
    private final short[] indices = new short[VOLUME];
    private final byte[] byteIndices = new byte[VOLUME];

    SectionCodec() {
        this.paletteIndex.defaultReturnValue(-1);
    }

    private static long paletteKey(int state, byte light) {
        return (long) (light & 0xFF) << 32 | (state & 0xFFFFFFFFL);
    }

    int encode(LodSection section, ByteBuffer out) {
        var cells = section.cells();
        this.paletteIndex.clear();
        int size = 0;
        long previous = 0;
        int previousIndex = -1;
        for (int i = 0; i < VOLUME; i++) {
            int state = cells.state(i);
            byte lightValue = (byte) cells.light(i);
            long value = paletteKey(state, lightValue);
            if (value != previous || previousIndex < 0) {
                int index = this.paletteIndex.putIfAbsent(value, size);
                if (index == -1) {
                    index = size;
                    this.paletteStates[size] = state;
                    this.paletteLight[size++] = lightValue;
                }
                previous = value;
                previousIndex = index;
            }
            this.indices[i] = (short) previousIndex;
        }

        int indexBytes = size == 1 ? 0 : size <= 256 ? 1 : 2;
        var buf = out.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) VERSION);
        buf.put(1, (byte) indexBytes);
        buf.put(2, section.childMask());
        buf.put(3, (byte) 0);
        buf.putInt(4, size);
        buf.putLong(8, section.key);
        int at = HEADER_BYTES;
        for (int i = 0; i < size; i++) {
            buf.putInt(at, this.paletteStates[i]);
            buf.put(at + 4, this.paletteLight[i]);
            at += ENTRY_BYTES;
        }
        if (indexBytes == 1) {
            for (int i = 0; i < VOLUME; i++) buf.put(at + i, (byte) this.indices[i]);
            at += VOLUME;
        } else if (indexBytes == 2) {
            for (int i = 0; i < VOLUME; i++) buf.putShort(at + i * 2, this.indices[i]);
            at += VOLUME * 2;
        }
        return at;
    }

    boolean decode(LodSection section, ByteBuffer in, int size) {
        var buf = in.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (!validHeader(section, buf, size)) return false;
        int indexBytes = buf.get(1);
        byte children = buf.get(2);
        int paletteSize = buf.getInt(4);

        var cells = section.cells();
        int[] paletteStates = this.paletteStates;
        byte[] paletteLight = this.paletteLight;
        byte[] paletteSolid = this.paletteSolid;
        int at = HEADER_BYTES;
        for (int i = 0; i < paletteSize; i++) {
            int state = buf.getInt(at);
            paletteStates[i] = state;
            paletteLight[i] = buf.get(at + 4);
            paletteSolid[i] = (byte) (Voxel.isAir(state) ? 0 : 1);
            at += ENTRY_BYTES;
        }
        // Palette arrays are sized to 65536; out-of-range indices are caught by largest >= paletteSize below.
        int largest = 0, solid = 0;
        switch (indexBytes) {
            case 0 -> {
                cells.fill(paletteStates[0], paletteLight[0] & 0xFF);
                solid = paletteSolid[0] * VOLUME;
            }
            case 1 -> {
                byte[] indices = this.byteIndices;
                buf.get(at, indices, 0, VOLUME);
                for (int i = 0; i < VOLUME; i++) {
                    int index = indices[i] & 0xFF;
                    largest = Math.max(largest, index);
                    cells.set(i, paletteStates[index], paletteLight[index]);
                    solid += paletteSolid[index];
                }
            }
            default -> {
                short[] indices = this.indices;
                buf.slice(at, VOLUME * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(indices);
                for (int i = 0; i < VOLUME; i++) {
                    int index = indices[i] & 0xFFFF;
                    largest = Math.max(largest, index);
                    cells.set(i, paletteStates[index], paletteLight[index]);
                    solid += paletteSolid[index];
                }
            }
        }
        if (largest >= paletteSize) return false;

        section.setChildMask(children);
        if (section.level == 0) section.setSolidCount(solid);
        return true;
    }

    boolean decodePacked(LodSection section, PackedCells into, ByteBuffer in, int size) {
        var buf = in.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (!validHeader(section, buf, size)) return false;
        int indexBytes = buf.get(1);
        byte children = buf.get(2);
        int paletteSize = buf.getInt(4);

        into.reset(paletteSize, indexBytes);
        int[] paletteStates = into.paletteStates();
        byte[] paletteLight = into.paletteLight();
        int at = HEADER_BYTES;
        for (int i = 0; i < paletteSize; i++) {
            paletteStates[i] = buf.getInt(at);
            paletteLight[i] = buf.get(at + 4);
            at += ENTRY_BYTES;
        }
        int largest = 0;
        switch (indexBytes) {
            case 0 -> {}
            case 1 -> {
                var indices = into.narrowIndices();
                MemorySegment.copy(MemorySegment.ofBuffer(buf.slice(at, VOLUME)), 0, indices, 0, VOLUME);
                if (paletteSize <= 128) {
                    if (anyByteAbove(indices, paletteSize - 1)) return false;
                } else if (paletteSize < 256) {
                    for (int i = 0; i < VOLUME; i++) largest = Math.max(largest, indices.get(ValueLayout.JAVA_BYTE, i) & 0xFF);
                }
            }
            default -> {
                for (int page = 0; page < 2; page++) {
                    var indices = into.widePage(page);
                    int bytes = PackedCells.WIDE_PAGE_CELLS * 2;
                    MemorySegment.copy(MemorySegment.ofBuffer(buf.slice(at + page * bytes, bytes)), 0, indices, 0, bytes);
                    for (int i = 0; i < PackedCells.WIDE_PAGE_CELLS; i++) {
                        largest = Math.max(largest, indices.getAtIndex(PackedCells.WIDE, i));
                    }
                }
            }
        }
        if (largest >= paletteSize) return false;

        section.setChildMask(children);
        return true;
    }

    private static final long ONES = 0x0101_0101_0101_0101L, HIGH_BITS = 0x8080_8080_8080_8080L;

    // SWAR check for any byte > most (most <= 127): adding (127 - most) sets bit 7 on overflow.
    static boolean anyByteAbove(MemorySegment indices, int most) {
        long add = ONES * (127 - most), over = 0;
        for (int i = 0; i < VOLUME; i += 8) {
            long bytes = indices.get(PackedCells.EIGHT, i);
            over |= (bytes + add) | bytes;
        }
        return (over & HIGH_BITS) != 0;
    }

    private static boolean validHeader(LodSection section, ByteBuffer buf, int size) {
        if (size < HEADER_BYTES || buf.get(0) != VERSION) return false;
        int indexBytes = buf.get(1);
        int paletteSize = buf.getInt(4);
        if (buf.getLong(8) != section.key) return false;
        int expectedIndexBytes = paletteSize == 1 ? 0 : paletteSize <= 256 ? 1 : 2;
        return paletteSize >= 1 && paletteSize <= VOLUME && indexBytes == expectedIndexBytes
                && size == HEADER_BYTES + paletteSize * ENTRY_BYTES + VOLUME * indexBytes;
    }
}
