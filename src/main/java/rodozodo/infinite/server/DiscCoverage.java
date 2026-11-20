package rodozodo.infinite.server;

import net.minecraft.world.level.ChunkPos;

import java.util.BitSet;

final class DiscCoverage {
    private final int centerChunkX, centerChunkZ, radius, side;
    private final BitSet stored;

    DiscCoverage(int centerChunkX, int centerChunkZ, int radius) {
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.radius = Math.max(0, radius);
        this.side = this.radius * 2 + 1;
        this.stored = new BitSet(this.side * this.side);
    }

    boolean within(long packed) {
        int dx = ChunkPos.getX(packed) - this.centerChunkX, dz = ChunkPos.getZ(packed) - this.centerChunkZ;
        return (long) dx * dx + (long) dz * dz <= (long) this.radius * this.radius;
    }

    void add(long packed) {
        if (!within(packed)) return;
        int index = index(packed);
        if (index < 0) return;
        synchronized (this.stored) {
            this.stored.set(index);
        }
    }

    boolean has(long packed) {
        int index = index(packed);
        if (index < 0) return false;
        synchronized (this.stored) {
            return this.stored.get(index);
        }
    }

    int count() {
        synchronized (this.stored) {
            return this.stored.cardinality();
        }
    }

    private int index(long packed) {
        int dx = ChunkPos.getX(packed) - this.centerChunkX, dz = ChunkPos.getZ(packed) - this.centerChunkZ;
        if (dx < -this.radius || dx > this.radius || dz < -this.radius || dz > this.radius) return -1;
        return (dx + this.radius) * this.side + (dz + this.radius);
    }
}
