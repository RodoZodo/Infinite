package rodozodo.infinite.world;

public final class LodPos {
    public static final int MAX_LEVEL = 4;

    private LodPos() {}

    public static long pack(int level, int x, int y, int z) {
        return (long) level << 60
                | (long) (y & 0xFF) << 52
                | (long) (z & 0xFFFFFF) << 28
                | (long) (x & 0xFFFFFF) << 4;
    }

    public static int level(long pos) {
        return (int) (pos >>> 60);
    }

    public static int x(long pos) {
        return (int) (pos << 36 >> 40);
    }

    public static int y(long pos) {
        return (int) (pos << 4 >> 56);
    }

    public static int z(long pos) {
        return (int) (pos << 12 >> 40);
    }

    public static long parent(long pos) {
        return pack(level(pos) + 1, x(pos) >> 1, y(pos) >> 1, z(pos) >> 1);
    }

    public static int childIndex(int x, int y, int z) {
        // Bit 0: x, bit 1: z, bit 2: y
        return x & 1 | (z & 1) << 1 | (y & 1) << 2;
    }

    public static String describe(long pos) {
        return "L" + level(pos) + " (" + x(pos) + ", " + y(pos) + ", " + z(pos) + ")";
    }
}
