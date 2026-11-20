package rodozodo.infinite.client.render;

import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

final class UniformWriter {
    private final long base;
    private final long capacity;
    private long offset;

    UniformWriter(long address, long capacity) {
        this.base = address;
        this.capacity = capacity;
    }

    private long slot(long align, long size) {
        this.offset = (this.offset + align - 1) & -align;
        if (this.offset + size > this.capacity) {
            throw new IndexOutOfBoundsException("Uniform block overflows " + this.capacity + " bytes");
        }
        long at = this.base + this.offset;
        this.offset += size;
        return at;
    }

    UniformWriter mat4(Matrix4fc matrix) {
        matrix.getToAddress(this.slot(16, 64));
        return this;
    }

    UniformWriter ivec3(int x, int y, int z) {
        long at = this.slot(16, 12);
        MemoryUtil.memPutInt(at, x);
        MemoryUtil.memPutInt(at + 4, y);
        MemoryUtil.memPutInt(at + 8, z);
        return this;
    }

    UniformWriter vec3(float x, float y, float z) {
        long at = this.slot(16, 12);
        MemoryUtil.memPutFloat(at, x);
        MemoryUtil.memPutFloat(at + 4, y);
        MemoryUtil.memPutFloat(at + 8, z);
        return this;
    }

    UniformWriter vec2(float x, float y) {
        long at = this.slot(8, 8);
        MemoryUtil.memPutFloat(at, x);
        MemoryUtil.memPutFloat(at + 4, y);
        return this;
    }

    UniformWriter vec4(float x, float y, float z, float w) {
        long at = this.slot(16, 16);
        MemoryUtil.memPutFloat(at, x);
        MemoryUtil.memPutFloat(at + 4, y);
        MemoryUtil.memPutFloat(at + 8, z);
        MemoryUtil.memPutFloat(at + 12, w);
        return this;
    }

    UniformWriter vec4Array(float[] values) {
        for (int i = 0; i < values.length; i += 4) {
            this.vec4(values[i], values[i + 1], values[i + 2], values[i + 3]);
        }
        return this;
    }

    UniformWriter uint(int value) {
        MemoryUtil.memPutInt(this.slot(4, 4), value);
        return this;
    }

    UniformWriter f32(float value) {
        MemoryUtil.memPutFloat(this.slot(4, 4), value);
        return this;
    }
}
