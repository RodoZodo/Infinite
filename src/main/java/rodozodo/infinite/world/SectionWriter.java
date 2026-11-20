package rodozodo.infinite.world;

import rodozodo.infinite.world.voxelize.VoxelChunk;

import java.lang.foreign.MemorySegment;

public final class SectionWriter {
    private SectionWriter() {}

    public static void write(LodWorld world, VoxelChunk chunk) {
        write(world, chunk, false);
    }

    public static void write(LodWorld world, VoxelChunk chunk, boolean onlyWhereEmpty) {
        world.checkLive();
        LodSection child = null;
        try {
            for (int level = 0; level <= LodPos.MAX_LEVEL; level++) {
                int shift = level + 1;
                var section = world.acquire(level, chunk.x() >> shift, chunk.y() >> shift, chunk.z() >> shift);
                int kinds = 0;
                boolean flipped = false;
                if (child != null) {
                    int update = section.updateChildBit(child);
                    child.release();
                    child = null;
                    if (update != LodSection.UNCHANGED) kinds |= LodWorld.CHILDREN_CHANGED;
                    flipped = update == LodSection.FLIPPED;
                }

                int span = (1 << shift) - 1;
                int lx = chunk.x() & span, ly = chunk.y() & span, lz = chunk.z() & span;
                if (onlyWhereEmpty && !isEmpty(section.cells(), VoxelChunk.SIZE >> level, lx, ly, lz)) {
                    if (kinds == 0) {
                        section.release();
                        break;
                    }
                    world.markChanged(section, kinds, 0);
                    child = section;
                    continue;
                }
                int solidDelta = copy(chunk, level, section.cells(), VoxelChunk.SIZE >> level, lx, ly, lz, level == 0);
                boolean changed = solidDelta != NO_CHANGE;
                if (changed) {
                    kinds |= LodWorld.BLOCKS_CHANGED;
                    if (level == 0 && solidDelta != 0 && section.addSolid(solidDelta)) {
                        kinds |= LodWorld.CHILDREN_CHANGED;
                        flipped = true;
                    }
                }

                if (kinds != 0) world.markChanged(section, kinds, changed ? touchedSides(lx, ly, lz, span) : 0);
                if (flipped) {

                    // Keep acquired until the parent updates its child mask bit
                    child = section;
                    continue;
                }
                section.release();
                if (!changed) break;
            }
        } finally {
            if (child != null) child.release();
        }
    }

    private static boolean isEmpty(SectionCells cells, int side, int lx, int ly, int lz) {
        for (int y = 0; y < side; y++) {
            for (int z = 0; z < side; z++) {
                int row = LodSection.index(lx * side, ly * side + y, lz * side + z);
                for (int x = 0; x < side; x++) {
                    if (!Voxel.isAir(cells.state(row + x))) return false;
                }
            }
        }
        return true;
    }

    private static final int NO_CHANGE = Integer.MIN_VALUE;

    private static int copy(VoxelChunk chunk, int level, SectionCells cells, int side, int lx, int ly, int lz,
                            boolean countSolid) {
        int[] fromStates = chunk.states(level);
        byte[] fromLight = chunk.light(level);
        var fromStateRows = MemorySegment.ofArray(fromStates);
        var fromLightRows = MemorySegment.ofArray(fromLight);
        var toLight = cells.light();
        boolean changed = false;
        int delta = 0;
        for (int y = 0; y < side; y++) {
            for (int z = 0; z < side; z++) {
                int src = (y * side + z) * side;
                int dst = LodSection.index(lx * side, ly * side + y, lz * side + z);
                if (cells.statesEqual(fromStateRows, src, dst, side)
                        && MemorySegment.mismatch(fromLightRows, src, src + side, toLight, dst, dst + side) < 0) continue;
                changed = true;
                if (countSolid) delta += solidIn(fromStates, src, side) - solidIn(cells, dst, side);
                cells.writeStates(fromStates, src, dst, side);
                cells.writeLight(fromLight, src, dst, side);
            }
        }
        return changed ? delta : NO_CHANGE;
    }

    private static int solidIn(int[] states, int start, int length) {
        int count = 0;
        for (int i = start; i < start + length; i++) {
            if (!Voxel.isAir(states[i])) count++;
        }
        return count;
    }

    private static int solidIn(SectionCells cells, int start, int length) {
        int count = 0;
        for (int i = start; i < start + length; i++) {
            if (!Voxel.isAir(cells.state(i))) count++;
        }
        return count;
    }

    private static int touchedSides(int lx, int ly, int lz, int span) {
        int sides = 0;
        if (ly == 0) sides |= 1;
        if (ly == span) sides |= 1 << 1;
        if (lx == 0) sides |= 1 << 2;
        if (lx == span) sides |= 1 << 3;
        if (lz == 0) sides |= 1 << 4;
        if (lz == span) sides |= 1 << 5;
        return sides;
    }
}