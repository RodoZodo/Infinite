package rodozodo.infinite.client.model;

import org.lwjgl.system.MemoryUtil;


// Packs a baked models six face textures into a 3x2 tile atlas cell (tile column f/2, row f%2 for terrain.vert) and generates its mipmap chain in linear light

public final class ModelTextures {
    public static final int TILE = FaceRasterizer.SIZE;
    public static final int CELL_WIDTH = 3 * TILE, CELL_HEIGHT = 2 * TILE;
    public static final int LEVELS = Integer.numberOfTrailingZeros(TILE);
    public static final int BYTES = cellBytes();

    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    private final int[] cell = new int[CELL_WIDTH * CELL_HEIGHT];
    private final int[] nearest = new int[FaceRasterizer.PIXELS];
    private final int[] queue = new int[FaceRasterizer.PIXELS];

    private static int cellBytes() {
        int bytes = 0;
        for (int level = 0; level < LEVELS; level++) {
            bytes += (CELL_WIDTH >> level) * (CELL_HEIGHT >> level) * 4;
        }
        return bytes;
    }

    public static int levelOffset(int level) {
        int offset = 0;
        for (int i = 0; i < level; i++) {
            offset += (CELL_WIDTH >> i) * (CELL_HEIGHT >> i) * 4;
        }
        return offset;
    }

    void write(FaceRasterizer faces, boolean darkCutout, long out) {
        for (int face = 0; face < 6; face++) {
            int base = face * FaceRasterizer.PIXELS;
            int originX = (face >> 1) * TILE, originY = (face & 1) * TILE;
            for (int y = 0; y < TILE; y++) {
                System.arraycopy(faces.colour, base + y * TILE, this.cell, (originY + y) * CELL_WIDTH + originX, TILE);
            }
            if (!darkCutout) this.bleedIntoTransparent(originX, originY);
        }

        int width = CELL_WIDTH, height = CELL_HEIGHT;
        MemoryUtil.memIntBuffer(out, width * height).put(this.cell);
        long previous = out;
        long at = out + (long) width * height * 4;
        for (int level = 1; level < LEVELS; level++) {
            int sourceWidth = width;
            width >>= 1;
            height >>= 1;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    long row = previous + ((long) (2 * y) * sourceWidth + 2 * x) * 4;
                    long nextRow = row + sourceWidth * 4L;
                    MemoryUtil.memPutInt(at + ((long) y * width + x) * 4, average(darkCutout,
                            MemoryUtil.memGetInt(row), MemoryUtil.memGetInt(row + 4),
                            MemoryUtil.memGetInt(nextRow), MemoryUtil.memGetInt(nextRow + 4)));
                }
            }
            previous = at;
            at += (long) width * height * 4;
        }
    }

    static int opaqueMip(long cell, int face) {
        long at = cell;
        for (int level = 0; level < LEVELS; level++) {
            int tile = TILE >> level;
            int width = CELL_WIDTH >> level;
            int originX = (face >> 1) * tile, originY = (face & 1) * tile;
            if (opaqueTile(at, width, originX, originY, tile)) return level;
            at += (long) width * (CELL_HEIGHT >> level) * 4;
        }
        return LEVELS;
    }

    private static boolean opaqueTile(long level, int width, int originX, int originY, int tile) {
        for (int y = 0; y < tile; y++) {
            long row = level + ((long) (originY + y) * width + originX) * 4;
            for (int x = 0; x < tile; x++) {
                if (MemoryUtil.memGetInt(row + x * 4L) >>> 24 <= 25) return false;
            }
        }
        return true;
    }

    private void bleedIntoTransparent(int originX, int originY) {
        int head = 0, tail = 0;
        for (int i = 0; i < FaceRasterizer.PIXELS; i++) {
            int colour = this.cell[(originY + (i >> 4)) * CELL_WIDTH + originX + (i & 15)];
            if (colour >>> 24 != 0) {
                this.nearest[i] = i;
                this.queue[tail++] = i;
            } else {
                this.nearest[i] = -1;
            }
        }
        if (tail == 0 || tail == FaceRasterizer.PIXELS) return;
        while (head < tail) {
            int i = this.queue[head++];
            int x = i & 15, y = i >> 4;
            if (x > 0) tail = this.reach(i - 1, i, tail);
            if (x < 15) tail = this.reach(i + 1, i, tail);
            if (y > 0) tail = this.reach(i - 16, i, tail);
            if (y < 15) tail = this.reach(i + 16, i, tail);
        }
        for (int i = 0; i < FaceRasterizer.PIXELS; i++) {
            int source = this.nearest[i];
            if (source == i) continue;
            int sourceColour = this.cell[(originY + (source >> 4)) * CELL_WIDTH + originX + (source & 15)];
            this.cell[(originY + (i >> 4)) * CELL_WIDTH + originX + (i & 15)] = sourceColour & 0x00FFFFFF;
        }
    }

    private int reach(int target, int from, int tail) {
        if (this.nearest[target] != -1) return tail;
        this.nearest[target] = this.nearest[from];
        this.queue[tail] = target;
        return tail + 1;
    }

    private static int average(boolean darkCutout, int a, int b, int c, int d) {
        float r = 0, g = 0, bl = 0;
        int alpha = 0;
        for (int texel : new int[]{a, b, c, d}) {
            alpha += texel >>> 24;
            if (darkCutout && texel >>> 24 == 0) continue;
            r += SRGB_TO_LINEAR[texel & 0xFF];
            g += SRGB_TO_LINEAR[texel >>> 8 & 0xFF];
            bl += SRGB_TO_LINEAR[texel >>> 16 & 0xFF];
        }
        return toSrgb(r / 4) | toSrgb(g / 4) << 8 | toSrgb(bl / 4) << 16 | (alpha + 2) / 4 << 24;
    }

    private static int toSrgb(float linear) {
        double c = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return (int) Math.clamp(Math.round(c * 255), 0, 255);
    }
}
