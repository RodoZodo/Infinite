package rodozodo.infinite.client.core.vk;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;


// Host visible staging ring for CPU to GPU buffer and image uploads, batching copies per destination. Render thread only

public final class StagingBuffer {
    public static final int ALIGNMENT = 256;
    private static final int COPIES_PER_COMMAND = 512;

    private record ImageCopy(VImage image, long srcOffset, int level, int x, int y, int width, int height) {}

    private final VBuffer buffer;
    private final long mapped;
    private final FrameRing<Void> ring;

    // Per destination, (srcOffset, dstOffset, size) triples
    private final Map<VBuffer, LongArrayList> bufferCopies = new IdentityHashMap<>();
    private final ArrayDeque<LongArrayList> copyListPool = new ArrayDeque<>();
    private final List<ImageCopy> imageCopies = new ArrayList<>();
    private final VkBufferCopy.Buffer copyRegions = VkBufferCopy.calloc(COPIES_PER_COMMAND);
    private final VkBufferImageCopy.Buffer imageRegion = VkBufferImageCopy.calloc(1);

    public StagingBuffer(long size) {
        this.buffer = VBuffer.create("staging", size,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VBuffer.Access.HOST_UPLOAD);
        this.mapped = this.buffer.mappedAddress();
        this.ring = new FrameRing<>(size, ALIGNMENT, ignored -> {});
    }

    public VBuffer buffer() {
        return this.buffer;
    }

    public long reserve(long size) {
        return this.ring.allocate(size);
    }

    public long address(long offset) {
        return this.mapped + offset;
    }

    public long write(VBuffer target, long targetOffset, long size) {
        long offset = this.reserve(size);
        this.copy(offset, target, targetOffset, size);
        return this.address(offset);
    }

    public void copy(long stagingOffset, VBuffer target, long targetOffset, long size) {
        if (targetOffset < 0 || targetOffset + size > target.size) {
            throw new IllegalArgumentException("Copy of " + size + " bytes at " + targetOffset + " exceeds a buffer of " + target.size);
        }
        LongArrayList copies = this.bufferCopies.get(target);
        if (copies == null) {
            copies = this.copyListPool.pollFirst();
            if (copies == null) copies = new LongArrayList();
            this.bufferCopies.put(target, copies);
        }
        copies.add(stagingOffset);
        copies.add(targetOffset);
        copies.add(size);
    }

    public long writeImage(VImage image, int level, int x, int y, int width, int height) {
        long offset = this.reserve((long) width * height * 4);
        this.imageCopies.add(new ImageCopy(image, offset, level, x, y, width, height));
        return this.address(offset);
    }

    public void recordCopies(VkCommandBuffer cb) {
        this.flushWrites();
        if (this.bufferCopies.isEmpty() && this.imageCopies.isEmpty()) return;
        for (var entry : this.bufferCopies.entrySet()) {
            var triples = entry.getValue();
            int count = triples.size() / 3;
            for (int first = 0; first < count; first += COPIES_PER_COMMAND) {
                int batch = Math.min(COPIES_PER_COMMAND, count - first);
                this.copyRegions.position(0).limit(batch);
                for (int i = 0; i < batch; i++) {
                    int at = (first + i) * 3;
                    this.copyRegions.get(i)
                            .srcOffset(triples.getLong(at))
                            .dstOffset(triples.getLong(at + 1))
                            .size(triples.getLong(at + 2));
                }
                vkCmdCopyBuffer(cb, this.buffer.handle, entry.getKey().handle, this.copyRegions);
            }
            triples.clear();
            if (this.copyListPool.size() < 64) this.copyListPool.addFirst(triples);
        }
        if (!this.imageCopies.isEmpty()) {
            var region = this.imageRegion.get(0);
            for (int i = 0, n = this.imageCopies.size(); i < n; i++) {
                var copy = this.imageCopies.get(i);
                region.bufferOffset(copy.srcOffset)
                        .bufferRowLength(copy.width)
                        .bufferImageHeight(copy.height);
                region.imageSubresource().set(copy.image.aspectMask, copy.level, 0, 1);
                region.imageOffset().set(copy.x, copy.y, 0);
                region.imageExtent().set(copy.width, copy.height, 1);
                vkCmdCopyBufferToImage(cb, this.buffer.handle, copy.image.image, VK_IMAGE_LAYOUT_GENERAL, this.imageRegion);
            }
            this.imageCopies.clear();
        }
        this.bufferCopies.clear();
        VBarriers.memory(cb,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
    }

    private void flushWrites() {
        if (!this.buffer.hostCoherent) this.ring.flushOpenRanges(this.buffer);
    }

    public void endFrame() {
        this.endFrame(null, FrameFence.nextPollEpoch());
    }

    void endFrame(FrameFence fence, long pollEpoch) {
        this.flushWrites();
        this.ring.endFrame(null, fence);
        this.ring.retireCompleted(pollEpoch);
    }

    public static void endFrames(StagingBuffer staging, ReadbackBuffer readback) {
        long pollEpoch = FrameFence.nextPollEpoch();
        if (staging.ring.hasOpenFrame() || readback.hasOpenFrame()) {
            var fence = new FrameFence(VkContext.get().createFrameFence());
            try {
                staging.endFrame(fence, pollEpoch);
                readback.endFrame(fence, pollEpoch);
            } finally {
                fence.release();
            }
        } else {
            staging.endFrame(null, pollEpoch);
            readback.endFrame(null, pollEpoch);
        }
    }

    public long size() {
        return this.buffer.size;
    }

    public void free() {
        this.ring.retireAll();
        this.copyRegions.free();
        this.imageRegion.free();
        this.buffer.free();
    }
}
