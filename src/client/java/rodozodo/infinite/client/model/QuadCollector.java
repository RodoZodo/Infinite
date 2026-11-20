package rodozodo.infinite.client.model;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;

import java.util.Arrays;


final class QuadCollector implements VertexConsumer {
    static final int DISCARD = 1;
    static final int TINTED = 2;
    static final int ANIMATED = 4;

    // Floats per vertex: x, y, z, u, v.
    static final int STRIDE = 5;

    float[] vertices = new float[64 * 4 * STRIDE];
    int[] flags = new int[64];
    TextureAtlasSprite[] sprites = new TextureAtlasSprite[64];
    private int vertexCount;
    int streamFlags;

    void reset() {
        this.vertexCount = 0;
        this.streamFlags = 0;
    }

    int quadCount() {
        return this.vertexCount >> 2;
    }

    boolean isEmpty() {
        return this.vertexCount < 4;
    }

    void add(BakedQuad quad, int flags, float offsetX, float offsetY, float offsetZ) {
        for (int i = 0; i < 4; i++) {
            var position = quad.position(i);
            long uv = quad.packedUV(i);
            this.vertex(position.x() + offsetX, position.y() + offsetY, position.z() + offsetZ);
            this.uv(UVPair.unpackU(uv), UVPair.unpackV(uv));
        }
        this.flags[this.quadCount() - 1] = flags;
        this.sprites[this.quadCount() - 1] = quad.materialInfo().sprite();
    }

    private void vertex(float x, float y, float z) {
        int at = this.vertexCount * STRIDE;
        if (at + STRIDE > this.vertices.length) {
            this.vertices = Arrays.copyOf(this.vertices, this.vertices.length * 2);
            this.flags = Arrays.copyOf(this.flags, this.flags.length * 2);
            this.sprites = Arrays.copyOf(this.sprites, this.sprites.length * 2);
        }
        this.vertices[at] = x;
        this.vertices[at + 1] = y;
        this.vertices[at + 2] = z;
        this.vertexCount++;
        if ((this.vertexCount & 3) == 0) {
            this.flags[(this.vertexCount >> 2) - 1] = this.streamFlags;
            this.sprites[(this.vertexCount >> 2) - 1] = null;
        }
    }

    private void uv(float u, float v) {
        int at = (this.vertexCount - 1) * STRIDE;
        this.vertices[at + 3] = u;
        this.vertices[at + 4] = v;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        this.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }
}
