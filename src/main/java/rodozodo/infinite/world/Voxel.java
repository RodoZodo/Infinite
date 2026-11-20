package rodozodo.infinite.world;


public final class Voxel {
    private static final int BIOME_SHIFT = 20;
    private static final int BLOCK_MASK = (1 << 20) - 1, BIOME_MASK = (1 << 9) - 1;

    private Voxel() {}

    public static int state(int block, int biome) {
        if (block == 0) return 0;
        return block & BLOCK_MASK | (biome & BIOME_MASK) << BIOME_SHIFT;
    }

    public static boolean isAir(int state) {
        return (state & BLOCK_MASK) == 0;
    }

    public static int block(int state) {
        return state & BLOCK_MASK;
    }

    public static int biome(int state) {
        return state >>> BIOME_SHIFT & BIOME_MASK;
    }

    public static int lightOf(int sky, int block) {
        return Math.min(sky, 15) | Math.min(block, 15) << 4;
    }

    public static int skyLight(int light) {
        return light & 0xF;
    }

    public static int blockLight(int light) {
        return light >>> 4 & 0xF;
    }
}