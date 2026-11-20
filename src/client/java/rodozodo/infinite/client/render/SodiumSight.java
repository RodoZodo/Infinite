package rodozodo.infinite.client.render;

import rodozodo.infinite.world.LodPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;

//Publishes a thread-safe directional visibility mask from Sodium's drawn section list to prioritize LOD mesh work.
public final class SodiumSight {
    private static final int SECTORS = 64;
    private static final double SECTOR_SIZE = Math.PI * 2 / SECTORS;
    private static final double NEAR_BLOCKS = 48;
    private static final int RECHECK_SECTORS = 3;
    private static final double MOVE_BLOCKS = 8;
    private static final double NEAR_SQUARED = NEAR_BLOCKS * NEAR_BLOCKS;

    private static final double[] OCTANT = new double[33];
    static {
        for (int i = 0; i <= 32; i++) OCTANT[i] = Math.atan(i / 32.0);
    }

    private static final LongOpenHashSet COLUMNS = new LongOpenHashSet();

    private static volatile Snapshot snapshot = Snapshot.NONE;
    private static int generation;
    private static long lastSectors;
    private static long lastHash = Long.MIN_VALUE;
    private static double lastX, lastZ;

    private SodiumSight() {}

    public record Snapshot(long sectors, double x, double z, int columns) {
        static final Snapshot NONE = new Snapshot(0, 0, 0, 0);

        public boolean sees(double px, double pz) {
            if (this.sectors == 0) return true;
            double dx = px - this.x, dz = pz - this.z;
            return dx * dx + dz * dz < NEAR_SQUARED || (this.sectors & 1L << sector(dx, dz)) != 0;
        }
    }

    public static void update(double cameraX, double cameraZ, long[] positions, int count) {
        long hash = count;
        for (int i = 0; i < count; i += 8) hash = hash * 0x9E37_79B9_7F4A_7C15L + positions[i];
        if (count > 0 && hash == lastHash
                && Math.abs(cameraX - lastX) < MOVE_BLOCKS && Math.abs(cameraZ - lastZ) < MOVE_BLOCKS) return;
        lastHash = hash;
        lastX = cameraX;
        lastZ = cameraZ;

        var columns = COLUMNS;
        columns.clear();
        long sectors = 0;
        for (int i = 0; i < count; i++) {
            long position = positions[i];
            int x = SectionPos.x(position) << 4, z = SectionPos.z(position) << 4;
            if (!columns.add((long) x << 32 | z & 0xFFFF_FFFFL)) continue;
            double dx = x + 8 - cameraX, dz = z + 8 - cameraZ;
            if (dx * dx + dz * dz < NEAR_SQUARED) continue;
            sectors |= 1L << sector(dx, dz);
        }
        snapshot = new Snapshot(sectors, cameraX, cameraZ, columns.size());
        if (Long.bitCount(sectors ^ lastSectors) >= RECHECK_SECTORS) {
            lastSectors = sectors;
            generation++;
        }
    }

    public static boolean sees(double px, double pz) {
        return snapshot.sees(px, pz);
    }

    public static boolean seesLod(long position) {
        Snapshot snapshot = SodiumSight.snapshot;
        if (snapshot.sectors == 0) return true;
        double size = 32 << LodPos.level(position);
        double centreX = LodPos.x(position) * size + size * 0.5;
        double centreZ = LodPos.z(position) * size + size * 0.5;
        double half = size * 0.5;
        if (Math.abs(centreX - snapshot.x) <= half && Math.abs(centreZ - snapshot.z) <= half) return true;
        if (snapshot.sees(centreX, centreZ)) return true;
        return snapshot.sees(centreX - half, centreZ - half) || snapshot.sees(centreX + half, centreZ - half)
                || snapshot.sees(centreX - half, centreZ + half) || snapshot.sees(centreX + half, centreZ + half);
    }

    public static double distanceSq(long position) {
        Snapshot snapshot = SodiumSight.snapshot;
        double size = 32 << LodPos.level(position);
        double dx = LodPos.x(position) * size + size * 0.5 - snapshot.x;
        double dz = LodPos.z(position) * size + size * 0.5 - snapshot.z;
        return dx * dx + dz * dz;
    }

    public static double nearestDistanceSq(long position) {
        Snapshot snapshot = SodiumSight.snapshot;
        double size = 32 << LodPos.level(position);
        double minX = LodPos.x(position) * size, minZ = LodPos.z(position) * size;
        double dx = Math.max(0, Math.max(minX - snapshot.x, snapshot.x - (minX + size)));
        double dz = Math.max(0, Math.max(minZ - snapshot.z, snapshot.z - (minZ + size)));
        return dx * dx + dz * dz;
    }

    public static int generation() {
        return generation;
    }

    public static String describe() {
        Snapshot snapshot = SodiumSight.snapshot;
        return "  Sight     " + Long.bitCount(snapshot.sectors) + " of " + SECTORS + " directions · "
                + snapshot.columns + " drawn columns";
    }

    static int sector(double dx, double dz) {
        double ax = Math.abs(dx), az = Math.abs(dz);
        boolean alongX = ax >= az;
        double scaled = (alongX ? az / ax : ax / az) * 32.0;
        int step = (int) Math.min(31, scaled); // NaN maps to +x sector 0.
        double octant = OCTANT[step] + (OCTANT[step + 1] - OCTANT[step]) * (scaled - step);
        if (!alongX) octant = Math.PI / 2 - octant;
        double angle = dx < 0 ? Math.PI - octant : octant;
        if (dz < 0) angle = -angle;
        return (int) Math.floor((angle + Math.PI) / SECTOR_SIZE) & (SECTORS - 1);
    }
}
