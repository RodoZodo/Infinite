package rodozodo.infinite.server;

final class ChunkDisc {
    private final int centerX, centerZ, radius;
    private int ring, step;
    private boolean done;

    ChunkDisc(int centerX, int centerZ, int radius) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = Math.max(0, radius);
    }

    int radius() {
        return this.radius;
    }

    long count() {
        long total = 0, radiusSquared = (long) this.radius * this.radius;
        for (int dz = -this.radius; dz <= this.radius; dz++) {
            for (int dx = -this.radius; dx <= this.radius; dx++) {
                if ((long) dx * dx + (long) dz * dz <= radiusSquared) total++;
            }
        }
        return total;
    }

    boolean next(int[] out) {
        if (this.done) return false;
        if (this.ring == 0) {
            this.done = this.radius == 0;
            this.ring = 1;
            out[0] = this.centerX;
            out[1] = this.centerZ;
            return true;
        }
        while (true) {
            int edge = 8 * this.ring;
            while (this.step < edge) {
                int i = this.step++;
                int dx, dz;
                if (i < 2 * this.ring + 1) {
                    dz = -this.ring;
                    dx = i - this.ring;
                } else if (i < 4 * this.ring + 1) {
                    dx = this.ring;
                    dz = i - 3 * this.ring;
                } else if (i < 6 * this.ring + 1) {
                    dz = this.ring;
                    dx = 5 * this.ring - i;
                } else {
                    dx = -this.ring;
                    dz = 7 * this.ring - i;
                }
                if ((long) dx * dx + (long) dz * dz > (long) this.radius * this.radius) continue;
                out[0] = this.centerX + dx;
                out[1] = this.centerZ + dz;
                return true;
            }
            this.step = 0;
            if (++this.ring > this.radius) {
                this.done = true;
                return false;
            }
        }
    }
}
