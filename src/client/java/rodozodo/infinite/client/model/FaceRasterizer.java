package rodozodo.infinite.client.model;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ARGB;

import java.util.Arrays;

// Software rasteriser that renders block and fluid quads from each of the six orthographic face directions into 16x16 per face buffers
// (color in 0xAABBGGRR, depth, hit counts, and tint flags).

final class FaceRasterizer {
    static final int SIZE = 16;
    static final int PIXELS = SIZE * SIZE;
    private static final float EMPTY_DEPTH = Float.POSITIVE_INFINITY;

    final int[] colour = new int[6 * PIXELS];
    final float[] depth = new float[6 * PIXELS];
    final byte[] hits = new byte[6 * PIXELS];
    final boolean[] tinted = new boolean[6 * PIXELS];

    private final int[] atlas;
    private final int atlasWidth, atlasHeight;
    private final float[] image = new float[2];
    private int sampledX, sampledY;
    private TextureAtlasSprite[] unsprited = new TextureAtlasSprite[0];

    private final float[] px = new float[3], py = new float[3], pz = new float[3], tu = new float[3], tv = new float[3];

    FaceRasterizer(int[] atlas, int atlasWidth, int atlasHeight) {
        this.atlas = atlas;
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
    }

    void clear() {
        Arrays.fill(this.colour, 0);
        Arrays.fill(this.depth, EMPTY_DEPTH);
        Arrays.fill(this.hits, (byte) 0);
        Arrays.fill(this.tinted, false);
    }

    // Fallback sprites used to resolve frame 0 for VertexConsumer quads that lack a sprite reference
    void unspritedFrom(TextureAtlasSprite[] sprites) {
        this.unsprited = sprites;
    }

    boolean drawn(int pixel) {
        return this.depth[pixel] != EMPTY_DEPTH;
    }

    void draw(int face, QuadCollector quads, boolean blend) {
        float[] v = quads.vertices;
        for (int quad = 0, count = quads.quadCount(); quad < count; quad++) {
            int first = quad * 4 * QuadCollector.STRIDE;
            int flags = quads.flags[quad];
            var sprite = quads.sprites[quad];
            this.triangle(face, v, first, first + QuadCollector.STRIDE, first + 2 * QuadCollector.STRIDE, sprite, flags, blend);
            this.triangle(face, v, first, first + 2 * QuadCollector.STRIDE, first + 3 * QuadCollector.STRIDE, sprite, flags, blend);
        }
    }

    // Repaints face pixels from sprite using terrain / quad.glsl quarter turn orientation
    void paint(int face, TextureAtlasSprite sprite, int turn) {
        int base = face * PIXELS;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = (x + 0.5f) / SIZE, v = (y + 0.5f) / SIZE;
                float spriteU, spriteV;
                switch (turn) {
                    case 1 -> { spriteU = u; spriteV = 1 - v; }
                    case 2 -> { spriteU = 1 - v; spriteV = 1 - u; }
                    case 3 -> { spriteU = 1 - u; spriteV = v; }
                    default -> { spriteU = v; spriteV = u; }
                }
                this.colour[base + y * SIZE + x] = this.frameZero(this.texel(sprite.getU(spriteU), sprite.getV(spriteV)), sprite);
            }
        }
    }

    private void triangle(int face, float[] v, int a, int b, int c, TextureAtlasSprite sprite, int flags, boolean blend) {
        float abx = v[b] - v[a], aby = v[b + 1] - v[a + 1], abz = v[b + 2] - v[a + 2];
        float acx = v[c] - v[a], acy = v[c + 1] - v[a + 1], acz = v[c + 2] - v[a + 2];
        float nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
        float outward = switch (face) {
            case 0 -> -ny;
            case 1 -> ny;
            case 2 -> -nz;
            case 3 -> nz;
            case 4 -> -nx;
            default -> nx;
        };
        if (outward <= 1e-9f) return;

        this.project(face, v, a, 0);
        this.project(face, v, b, 1);
        this.project(face, v, c, 2);
        float area = edge(0, 1, this.px[2], this.py[2]);
        if (Math.abs(area) < 1e-6f) return;
        int i1 = area > 0 ? 1 : 2, i2 = area > 0 ? 2 : 1;
        area = Math.abs(area);

        float minX = Math.min(this.px[0], Math.min(this.px[1], this.px[2]));
        float maxX = Math.max(this.px[0], Math.max(this.px[1], this.px[2]));
        float minY = Math.min(this.py[0], Math.min(this.py[1], this.py[2]));
        float maxY = Math.max(this.py[0], Math.max(this.py[1], this.py[2]));
        int x0 = Math.max(0, (int) Math.ceil(minX - 0.5f)), x1 = Math.min(SIZE - 1, (int) Math.floor(maxX - 0.5f));
        int y0 = Math.max(0, (int) Math.ceil(minY - 0.5f)), y1 = Math.min(SIZE - 1, (int) Math.floor(maxY - 0.5f));

        // Top left edge rule so shared triangle edges are rasterised once.
        boolean own0 = owns(i1, i2), own1 = owns(i2, 0), own2 = owns(0, i1);

        for (int y = y0; y <= y1; y++) {
            float cy = y + 0.5f;
            for (int x = x0; x <= x1; x++) {
                float cx = x + 0.5f;
                float w0 = this.edge(i1, i2, cx, cy), w1 = this.edge(i2, 0, cx, cy), w2 = this.edge(0, i1, cx, cy);
                if (!inside(w0, own0) || !inside(w1, own1) || !inside(w2, own2)) continue;
                float l0 = w0 / area, l1 = w1 / area, l2 = w2 / area;
                float z = l0 * this.pz[0] + l1 * this.pz[i1] + l2 * this.pz[i2];
                if (z < -1e-5f || z > 1 + 1e-5f) continue;
                float u = l0 * this.tu[0] + l1 * this.tu[i1] + l2 * this.tu[i2];
                float t = l0 * this.tv[0] + l1 * this.tv[i1] + l2 * this.tv[i2];
                this.fragment(face * PIXELS + y * SIZE + x, Math.clamp(z, 0, 1), this.texel(u, t), sprite, flags, blend);
            }
        }
    }

    private void fragment(int pixel, float z, int texel, TextureAtlasSprite sprite, int flags, boolean blend) {
        texel = frameZero(texel, sprite);
        if ((flags & QuadCollector.DISCARD) != 0 && texel >>> 24 == 0) return;
        if (this.hits[pixel] != -1) this.hits[pixel]++;

        // LEQUAL depth test so coplanar overlays (e.g. grass block sides) overwrite base quads
        if (z > this.depth[pixel]) return;
        this.depth[pixel] = z;
        this.tinted[pixel] = (flags & QuadCollector.TINTED) != 0;
        this.colour[pixel] = blend ? over(texel, this.colour[pixel]) : texel;
    }

    static void imageCoords(int face, float x, float y, float z, float[] out) {
        float u, w;
        switch (face) {
            case 0, 1 -> { u = x; w = z; }
            case 2, 3 -> { u = x; w = y; }
            default -> { u = y; w = z; }
        }
        out[0] = u * SIZE;
        out[1] = w * SIZE;
    }

    private void project(int face, float[] v, int at, int corner) {
        float x = v[at], y = v[at + 1], z = v[at + 2];
        imageCoords(face, x, y, z, this.image);
        this.px[corner] = this.image[0];
        this.py[corner] = this.image[1];
        this.pz[corner] = switch (face) {
            case 0 -> y;
            case 1 -> 1 - y;
            case 2 -> z;
            case 3 -> 1 - z;
            case 4 -> x;
            default -> 1 - x;
        };
        this.tu[corner] = v[at + 3];
        this.tv[corner] = v[at + 4];
    }

    private float edge(int from, int to, float x, float y) {
        return (this.px[to] - this.px[from]) * (y - this.py[from]) - (this.py[to] - this.py[from]) * (x - this.px[from]);
    }

    private boolean owns(int from, int to) {
        float dy = this.py[to] - this.py[from];
        return dy < 0 || (dy == 0 && this.px[to] - this.px[from] > 0);
    }

    private static boolean inside(float w, boolean ownsEdge) {
        return w > 0 || (w == 0 && ownsEdge);
    }

    // Sample animated sprites at frame 0 so baked textures and portable hashes are deterministic across sessions.
    private int frameZero(int texel, TextureAtlasSprite sprite) {
        if (sprite == null) {
            for (var candidate : this.unsprited) {
                if (this.inFrame(candidate)) return this.frameZeroTexel(candidate);
            }
            return texel;
        }
        if (!sprite.contents().isAnimated() || !this.inFrame(sprite)) return texel;
        return this.frameZeroTexel(sprite);
    }

    private boolean inFrame(TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        int x = this.sampledX - this.frameX(sprite), y = this.sampledY - this.frameY(sprite);
        return x >= 0 && y >= 0 && x < contents.width() && y < contents.height();
    }

    private int frameZeroTexel(TextureAtlasSprite sprite) {
        int x = this.sampledX - this.frameX(sprite), y = this.sampledY - this.frameY(sprite);
        // Atlas pixels are 0xAABBGGRR while originalImage is ARGB.
        return ARGB.toABGR(sprite.contents().originalImage.getPixel(x, y));
    }

    private int frameX(TextureAtlasSprite sprite) {
        return Math.round(sprite.getU(0f) * this.atlasWidth);
    }

    private int frameY(TextureAtlasSprite sprite) {
        return Math.round(sprite.getV(0f) * this.atlasHeight);
    }

    private int texel(float u, float v) {
        int x = Math.clamp((int) Math.floor(u * this.atlasWidth), 0, this.atlasWidth - 1);
        int y = Math.clamp((int) Math.floor(v * this.atlasHeight), 0, this.atlasHeight - 1);
        this.sampledX = x;
        this.sampledY = y;
        return this.atlas[y * this.atlasWidth + x];
    }

    static int over(int top, int under) {
        int topAlpha = top >>> 24;
        if (topAlpha == 255) return top;
        if (topAlpha == 0) return under;
        int underAlpha = under >>> 24;
        int underWeight = underAlpha * (255 - topAlpha);
        int alpha = topAlpha * 255 + underWeight;
        int result = 0;
        for (int shift = 0; shift < 24; shift += 8) {
            int channel = ((top >>> shift & 0xFF) * topAlpha * 255 + (under >>> shift & 0xFF) * underWeight + alpha / 2) / alpha;
            result |= channel << shift;
        }
        return result | ((alpha + 127) / 255) << 24;
    }
}
