package rodozodo.infinite.world.voxelize;

import rodozodo.infinite.world.IdRegistry;
import rodozodo.infinite.world.LodPos;
import rodozodo.infinite.world.Voxel;

import java.util.Arrays;

// Reusable 16x16x16 chunk section buffer holding LOD cell states and light across levels 0 to 4, indexed y, z, x.
// Not thread-safe.
public final class VoxelChunk {
    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    private final int[][] states = new int[LodPos.MAX_LEVEL + 1][];
    private final byte[][] light = new byte[LodPos.MAX_LEVEL + 1][];
    private final int[] childStates = new int[8];
    private final byte[] childLight = new byte[8];
    private final boolean[] densePlants = new boolean[(SIZE / 2) * (SIZE / 2) * (SIZE / 2)];
    private int x, y, z;
    private int solidCount;

    public VoxelChunk() {
        for (int level = 0; level <= LodPos.MAX_LEVEL; level++) {
            int side = SIZE >> level;
            this.states[level] = new int[side * side * side];
            this.light[level] = new byte[side * side * side];
        }
    }

    public VoxelChunk moveTo(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public int x() {
        return this.x;
    }

    public int y() {
        return this.y;
    }

    public int z() {
        return this.z;
    }

    public static int index(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }

    public int[] blocks() {
        return this.states[0];
    }

    public byte[] blockLight() {
        return this.light[0];
    }

    public int[] states(int level) {
        return this.states[level];
    }

    public byte[] light(int level) {
        return this.light[level];
    }

    public int solidCount() {
        return this.solidCount;
    }

    public void setSolidCount(int count) {
        this.solidCount = count;
    }

    public void countSolid() {
        int count = 0;
        for (int state : this.states[0]) {
            if (!Voxel.isAir(state)) count++;
        }
        this.solidCount = count;
    }

    public VoxelChunk fillAir(int light) {
        for (int level = 0; level <= LodPos.MAX_LEVEL; level++) {
            Arrays.fill(this.states[level], 0);
            Arrays.fill(this.light[level], (byte) light);
        }
        this.solidCount = 0;
        return this;
    }

    public void downsample(IdRegistry ids) {
        for (int level = 1; level <= LodPos.MAX_LEVEL; level++) {
            int[] fine = this.states[level - 1], coarse = this.states[level];
            byte[] fineLight = this.light[level - 1], coarseLight = this.light[level];
            int side = SIZE >> level, fineSide = side * 2;
            int i = 0;
            for (int cy = 0; cy < side; cy++) {
                for (int cz = 0; cz < side; cz++) {
                    for (int cx = 0; cx < side; cx++) {
                        int base = ((cy * 2) * fineSide + cz * 2) * fineSide + cx * 2;
                        for (int child = 0; child < 8; child++) {
                            int dx = child & 1, dz = child >> 1 & 1, dy = child >> 2;
                            int at = base + (dy * fineSide + dz) * fineSide + dx;
                            this.childStates[child] = fine[at];
                            this.childLight[child] = fineLight[at];
                        }
                        int first = this.childStates[0];
                        boolean same = true;
                        for (int child = 1; child < 8 && same; child++) same = this.childStates[child] == first;
                        int best = same && !Voxel.isAir(first) ? 7 : bestChild(this.childStates, ids);
                        this.densePlants[i] = best >= 0 && ids.covering(Voxel.block(this.childStates[best]))
                                && coverColumns(this.childStates, ids) >= 2;
                        if (same && best == 7) {
                            coarse[i] = first;
                            coarseLight[i] = this.childLight[7];
                        } else if (best >= 0) {
                            coarse[i] = covered(this.childStates, best, ids);
                            coarseLight[i] = this.childLight[best];
                        } else {
                            int sky = 0, block = 0;
                            for (byte child : this.childLight) {
                                sky += Voxel.skyLight(child);
                                block += Voxel.blockLight(child);
                            }
                            coarse[i] = 0;
                            coarseLight[i] = (byte) Voxel.lightOf((sky + 7) / 8, (block + 4) / 8);
                        }
                        i++;
                    }
                }
            }
            // Promote dense plant layers onto the solid ground cell below
            int layer = side * side;
            for (int at = layer; at < coarse.length; at++) {
                int plant = Voxel.block(coarse[at]);
                if (plant == 0 || !ids.covering(plant) || !this.densePlants[at]) continue;
                int ground = coarse[at - layer];
                if (Voxel.isAir(ground) || ids.covering(Voxel.block(ground)) || ids.coverOf(Voxel.block(ground)) != 0) continue;
                coarse[at - layer] = Voxel.state(ids.coveredId(Voxel.block(ground), plant), Voxel.biome(ground));
            }
        }
    }

    private static int covered(int[] children, int best, IdRegistry ids) {
        int state = children[best];
        int block = Voxel.block(state);
        if (ids.covering(block)) return state;
        int plant = 0, columns = 0;
        for (int column = 3; column >= 0; column--) {
            int cover = columnCover(children, column, ids);
            if (cover == 0) continue;
            columns++;
            if (plant == 0) plant = cover;
        }
        int ground = ids.groundOf(block);
        if (columns < 2) return ground == block ? state : Voxel.state(ground, Voxel.biome(state));
        return Voxel.state(ids.coveredId(ground, plant), Voxel.biome(state));
    }

    private static int coverColumns(int[] children, IdRegistry ids) {
        int columns = 0;
        for (int column = 0; column < 4; column++) {
            if (columnCover(children, column, ids) != 0) columns++;
        }
        return columns;
    }

    private static int columnCover(int[] children, int column, IdRegistry ids) {
        int top = Voxel.isAir(children[column | 4]) ? children[column] : children[column | 4];
        if (Voxel.isAir(top)) return 0;
        int block = Voxel.block(top);
        return ids.covering(block) ? block : ids.coverOf(block);
    }

    private static int bestChild(int[] children, IdRegistry ids) {
        // Fluids at the top of a cell win outright so distant water surfaces stay intact
        int highest = -1;
        for (int child = 4; child < 8 && highest < 0; child++) {
            if (!Voxel.isAir(children[child])) highest = child;
        }
        for (int child = 0; child < 4 && highest < 0; child++) {
            if (!Voxel.isAir(children[child])) highest = child;
        }
        if (highest >= 0 && ids.fluid(Voxel.block(children[highest]))) return highest;

        int best = -1;
        int bestRank = -1;
        for (int child = 0; child < 8; child++) {
            if (Voxel.isAir(children[child])) continue;
            int block = Voxel.block(children[child]);
            int rank = ids.opacity(block) << 4 | (child & 4) << 1 | (ids.coverOf(block) != 0 ? 4 : 0) | child & 3;
            if (rank > bestRank) {
                bestRank = rank;
                best = child;
            }
        }
        return best;
    }
}
