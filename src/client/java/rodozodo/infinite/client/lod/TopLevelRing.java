package rodozodo.infinite.client.lod;

import rodozodo.infinite.client.render.SodiumSight;
import rodozodo.infinite.world.LodPos;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.function.LongConsumer;


// Maintains the circle of top-level LOD columns around the camera, incrementally adding and removing columns each frame and prioritizing columns visible to Sodium.

public final class TopLevelRing {
    private static final double RECHECK_DISTANCE = 64;
    private static final int TOP_LEVEL = LodPos.MAX_LEVEL;
    private static final int COLUMN_SHIFT = 5 + TOP_LEVEL;
    private static final int COLUMN_BLOCKS = 1 << COLUMN_SHIFT;

    private final LongConsumer addNode;
    private final LongConsumer removeNode;
    private final int columnsPerFrame;
    private final int minY, maxY;

    private final LongOpenHashSet loaded = new LongOpenHashSet();
    private final LongArrayList removals = new LongArrayList();
    private final LongArrayList additions = new LongArrayList();
    private long[] columns = new long[0];
    private long[] keys = new long[0];
    private int radius = -1;
    private int centreX, centreZ;
    private double checkedX = Double.NaN, checkedZ = Double.NaN;
    private boolean stale = true;

    public TopLevelRing(int columnsPerFrame, int minY, int maxY, LongConsumer addNode, LongConsumer removeNode) {
        this.columnsPerFrame = columnsPerFrame;
        this.minY = minY;
        this.maxY = maxY;
        this.addNode = addNode;
        this.removeNode = removeNode;
    }

    public void setRadius(int radius) {
        if (radius != this.radius) {
            this.radius = radius;
            this.stale = true;
        }
    }

    public boolean update(double x, double z) {
        double dx = x - this.checkedX, dz = z - this.checkedZ;
        if (!(dx * dx + dz * dz < RECHECK_DISTANCE * RECHECK_DISTANCE)) {
            this.checkedX = x;
            this.checkedZ = z;
            int column = (int) Math.floor(x) >> COLUMN_SHIFT, row = (int) Math.floor(z) >> COLUMN_SHIFT;
            if (column != this.centreX || row != this.centreZ) {
                this.centreX = column;
                this.centreZ = row;
                this.stale = true;
            }
        }
        if (this.stale && this.radius >= 0) {
            this.plan();
        }

        int budget = this.columnsPerFrame;
        for (; budget > 0 && !this.removals.isEmpty(); budget--) {
            long column = this.removals.popLong();
            this.loaded.remove(column);
            this.forEachNode(column, this.removeNode);
        }
        for (; budget > 0 && !this.additions.isEmpty(); budget--) {
            long column = this.additions.popLong();
            this.loaded.add(column);
            this.forEachNode(column, this.addNode);
        }
        return budget != this.columnsPerFrame;
    }

    private boolean inRange(int x, int z) {
        long dx = x - this.centreX, dz = z - this.centreZ;
        return dx * dx + dz * dz <= (long) this.radius * this.radius;
    }

    private void plan() {
        this.stale = false;
        this.removals.clear();
        this.additions.clear();
        for (var it = this.loaded.iterator(); it.hasNext(); ) {
            long column = it.nextLong();
            if (!this.inRange(columnX(column), columnZ(column))) this.removals.add(column);
        }
        int side = 2 * this.radius + 1, size = side * side;
        if (this.columns.length < size) {
            this.columns = new long[size];
            this.keys = new long[size];
        }
        int count = 0;
        for (int x = this.centreX - this.radius; x <= this.centreX + this.radius; x++) {
            for (int z = this.centreZ - this.radius; z <= this.centreZ + this.radius; z++) {
                if (!this.inRange(x, z)) continue;
                long column = column(x, z);
                if (this.loaded.contains(column)) continue;
                this.columns[count] = column;
                this.keys[count] = this.key(column, count);
                count++;
            }
        }
        Arrays.sort(this.keys, 0, count);
        for (int i = count - 1; i >= 0; i--) this.additions.add(this.columns[(int) (this.keys[i] & 0xFFFFFF)]);
    }

    private long key(long column, int index) {
        int centre = COLUMN_BLOCKS >> 1;
        boolean visible = SodiumSight.sees((double) columnX(column) * COLUMN_BLOCKS + centre,
                (double) columnZ(column) * COLUMN_BLOCKS + centre);
        return (visible ? 0L : 1L) << 62 | (long) this.distanceSquared(column) << 24 | index;
    }

    private long distanceSquared(long column) {
        long dx = columnX(column) - this.centreX, dz = columnZ(column) - this.centreZ;
        return dx * dx + dz * dz;
    }

    private void forEachNode(long column, LongConsumer action) {
        int x = columnX(column), z = columnZ(column);
        for (int y = this.minY; y <= this.maxY; y++) {
            action.accept(LodPos.pack(TOP_LEVEL, x, y, z));
        }
    }

    private static long column(int x, int z) {
        return (long) x << 32 | Integer.toUnsignedLong(z);
    }

    private static int columnX(long column) {
        return (int) (column >> 32);
    }

    private static int columnZ(long column) {
        return (int) column;
    }
}
