package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.StagingBuffer;
import rodozodo.infinite.client.core.vk.VBuffer;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

final class IndexBuffers {
    static final int QUADS = 16380;
    static final int BOX_INDICES = 36;
    static final int BOXES_PER_BATCH = 32;

    private static final int[] QUAD = {1, 2, 0, 1, 3, 2};
    // Corner bit encoding: x=1, z=2, y=4; winding must match box-culling face orientation.
    private static final int[] BOX = {
            0, 1, 2, 3, 2, 1,
            6, 5, 4, 5, 6, 7,
            0, 4, 1, 5, 1, 4,
            3, 6, 2, 6, 3, 7,
            2, 4, 0, 4, 2, 6,
            1, 5, 3, 7, 3, 5,
    };

    final VBuffer quads;
    final VBuffer box;
    final VBuffer boxBatch;

    IndexBuffers(StagingBuffer staging) {
        this.quads = upload(staging, "quad_indices", repeat(QUAD, QUADS, 4));
        this.box = upload(staging, "box_indices", BOX);
        this.boxBatch = upload(staging, "box_batch_indices", repeat(BOX, BOXES_PER_BATCH, 8));
    }

    private static int[] repeat(int[] pattern, int times, int stride) {
        int[] out = new int[pattern.length * times];
        for (int copy = 0; copy < times; copy++) {
            for (int i = 0; i < pattern.length; i++) {
                out[copy * pattern.length + i] = pattern[i] + copy * stride;
            }
        }
        return out;
    }

    private static VBuffer upload(StagingBuffer staging, String name, int[] indices) {
        long bytes = indices.length * 2L;
        var buffer = VBuffer.create(name, bytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VBuffer.Access.DEVICE_LOCAL);
        long dst = staging.write(buffer, 0, bytes);
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] > 0xFFFF) throw new IllegalStateException("Index " + indices[i] + " does not fit 16 bits");
            MemoryUtil.memPutShort(dst + i * 2L, (short) indices[i]);
        }
        return buffer;
    }

    void free() {
        this.quads.free();
        this.box.free();
        this.boxBatch.free();
    }
}
