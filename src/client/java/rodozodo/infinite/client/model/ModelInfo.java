package rodozodo.infinite.client.model;

// Packs per-model and per-face metadata flags for the section mesher into a single 64-bit long.
// Faces are ordered by {@link net.minecraft.core.Direction#get3DDataValue()} (down, up, north, south, west, east)

public final class ModelInfo {

    // Bit layout: present[0..5], occludes[6..11], hideable[12..17], selfLit[18..23], flags[24..31], light[32..35],
    // flush[36..41], usesAo[42], plant[43], fourPlanes[44], animated[45], leaf[46], cutout[47..52], stretchedCutout[53..58].
    private static final int PRESENT = 0, OCCLUDES = 6, HIDEABLE = 12, SELF_LIT = 18, FLUSH = 36, CUTOUT = 47;
    static final long OPAQUE_CUBE = 1L << 24;
    static final long TRANSLUCENT = 1L << 25;
    static final long DOUBLE_SIDED = 1L << 26;
    static final long BIOME_TINTED = 1L << 27;
    static final long HAS_FLUID = 1L << 28;
    static final long IS_FLUID = 1L << 29;
    static final long CULLS_SELF = 1L << 30;
    static final long AO_CASTER = 1L << 31;
    private static final int LIGHT = 32;
    static final long USES_AO = 1L << 42;
    static final long PLANT = 1L << 43;
    static final long FOUR_PLANES = 1L << 44;
    static final long ANIMATED = 1L << 45;
    static final long LEAF = 1L << 46;

    private ModelInfo() {}

    static long face(int face, boolean occludes, boolean hideable, boolean selfLit, boolean flush, boolean cutout,
                     boolean stretchedCutout) {
        long info = 1L << (PRESENT + face);
        if (occludes) info |= 1L << (OCCLUDES + face);
        if (hideable) info |= 1L << (HIDEABLE + face);
        if (selfLit) info |= 1L << (SELF_LIT + face);
        if (flush) info |= 1L << (FLUSH + face);
        if (cutout) info |= 1L << (CUTOUT + face);
        if (stretchedCutout) info |= 1L << (CUTOUT + 6 + face);
        return info;
    }

    static long light(int emission) {
        return (long) emission << LIGHT;
    }

    public static boolean hasFace(long info, int face) {
        return (info >>> (PRESENT + face) & 1) != 0;
    }

    public static boolean occludes(long info, int face) {
        return (info >>> (OCCLUDES + face) & 1) != 0;
    }

    public static boolean hideable(long info, int face) {
        return (info >>> (HIDEABLE + face) & 1) != 0;
    }

    public static boolean selfLit(long info, int face) {
        return (info >>> (SELF_LIT + face) & 1) != 0;
    }

    public static boolean opaqueCube(long info) {
        return (info & OPAQUE_CUBE) != 0;
    }

    public static boolean translucent(long info) {
        return (info & TRANSLUCENT) != 0;
    }

    public static boolean doubleSided(long info) {
        return (info & DOUBLE_SIDED) != 0;
    }

    public static boolean biomeTinted(long info) {
        return (info & BIOME_TINTED) != 0;
    }

    public static boolean hasFluid(long info) {
        return (info & HAS_FLUID) != 0;
    }

    public static boolean isFluid(long info) {
        return (info & IS_FLUID) != 0;
    }

    public static boolean cullsSelf(long info) {
        return (info & CULLS_SELF) != 0;
    }

    public static int lightEmission(long info) {
        return (int) (info >>> LIGHT & 0xF);
    }

    public static boolean flush(long info, int face) {
        return (info >>> (FLUSH + face) & 1) != 0;
    }

    public static boolean aoCaster(long info) {
        return (info & AO_CASTER) != 0;
    }

    public static boolean usesAo(long info) {
        return (info & USES_AO) != 0;
    }

    public static boolean plant(long info) {
        return (info & PLANT) != 0;
    }

    public static boolean fourPlanes(long info) {
        return (info & FOUR_PLANES) != 0;
    }

    public static boolean animated(long info) {
        return (info & ANIMATED) != 0;
    }

    public static boolean leaf(long info) {
        return (info & LEAF) != 0;
    }

    public static boolean alphaCutout(long info, int face) {
        return (info >>> (CUTOUT + face) & 1) != 0;
    }

    public static boolean stretchedCutout(long info, int face) {
        return (info >>> (CUTOUT + 6 + face) & 1) != 0;
    }
}
