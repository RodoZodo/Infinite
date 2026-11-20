package rodozodo.infinite.client.core.vk;

import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK13.*;


public final class ReadbackBuffer {
    @FunctionalInterface
    public interface Callback {

        // address is mapped memory valid only during this call
        void accept(long address, long size);
    }

    private record Request(VBuffer source, long sourceOffset, long ringOffset, long size, Callback callback) {}

    private final VBuffer buffer;
    private final long mapped;
    private final FrameRing<List<Request>> ring;
    private final List<Request> queued = new ArrayList<>();
    private final ArrayDeque<List<Request>> listPool = new ArrayDeque<>();
    private final VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1);
    private List<Request> recorded = new ArrayList<>();

    public ReadbackBuffer(long size) {
        this.buffer = VBuffer.create("readback", size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VBuffer.Access.HOST_READBACK);
        this.mapped = this.buffer.mappedAddress();
        this.ring = new FrameRing<>(size, 16, this::deliver);
    }

    public void request(VBuffer source, long sourceOffset, long size, Callback callback) {
        if (size <= 0 || sourceOffset < 0 || sourceOffset + size > source.size) {
            throw new IllegalArgumentException("Bad readback range " + sourceOffset + "+" + size + " of a " + source.size + " byte buffer");
        }
        this.queued.add(new Request(source, sourceOffset, this.ring.allocate(size), size, callback));
    }

    public void recordCopies(VkCommandBuffer cb) {
        if (this.queued.isEmpty()) return;
        VBarriers.memory(cb,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
        var region = this.copyRegion.get(0);
        for (int i = 0, n = this.queued.size(); i < n; i++) {
            var request = this.queued.get(i);
            region.set(request.sourceOffset, request.ringOffset, request.size);
            vkCmdCopyBuffer(cb, request.source.handle, this.buffer.handle, this.copyRegion);
        }
        VBarriers.transferToHost(cb);
        this.recorded.addAll(this.queued);
        this.queued.clear();
    }

    boolean hasOpenFrame() {
        return !this.recorded.isEmpty();
    }

    public void endFrame() {
        this.endFrame(null, FrameFence.nextPollEpoch());
    }

    void endFrame(FrameFence fence, long pollEpoch) {
        if (!this.queued.isEmpty()) throw new IllegalStateException("Readbacks requested but never recorded this frame");
        List<Request> frame;
        if (this.recorded.isEmpty()) {
            frame = null;
        } else {
            frame = this.recorded;
            var pooled = this.listPool.pollFirst();
            this.recorded = pooled != null ? pooled : new ArrayList<>();
        }
        this.ring.endFrame(frame, fence);
        this.ring.retireCompleted(pollEpoch);
    }

    private void deliver(List<Request> requests) {
        for (int i = 0, n = requests.size(); i < n; i++) {
            var request = requests.get(i);
            if (!this.buffer.hostCoherent) this.buffer.invalidate(request.ringOffset, request.size);
            request.callback.accept(this.mapped + request.ringOffset, request.size);
        }
        requests.clear();
        if (this.listPool.size() < 8) this.listPool.addFirst(requests);
    }

    public void drain() {
        this.ring.retireAll();
    }

    public void free() {
        this.drain();
        this.copyRegion.free();
        this.buffer.free();
    }
}
