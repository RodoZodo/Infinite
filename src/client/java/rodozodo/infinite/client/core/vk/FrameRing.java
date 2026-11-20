package rodozodo.infinite.client.core.vk;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.function.Consumer;


/**
 * Ring allocator for per-frame transient GPU memory, retiring each frame's allocations once its
 * GPU fence signals. Render thread only.
 */

final class FrameRing<T> {
    private record PendingFrame<T>(FrameFence fence, long end, @Nullable T data) {}

    private final long capacity;
    private final long alignment;
    private final Consumer<T> onRetire;
    private final ArrayDeque<PendingFrame<T>> inFlight = new ArrayDeque<>();
    private long head;
    private long tail;
    private long frameStart;

    FrameRing(long capacity, long alignment, Consumer<T> onRetire) {
        if (Long.bitCount(alignment) != 1) throw new IllegalArgumentException("Alignment must be a power of two");
        this.capacity = capacity;
        this.alignment = alignment;
        this.onRetire = onRetire;
    }

    long allocate(long size) {
        long aligned = (size + this.alignment - 1) & -this.alignment;
        if (size < 0 || aligned > this.capacity) {
            throw new IllegalArgumentException("Allocation of " + size + " bytes does not fit a ring of " + this.capacity);
        }
        long start = this.head;
        long offset = start % this.capacity;
        if (offset + aligned > this.capacity) {
            start += this.capacity - offset;
        }
        while (start + aligned - this.tail > this.capacity) {
            if (this.inFlight.isEmpty()) {
                throw new IllegalStateException("Ring of " + this.capacity + " bytes exhausted within a single frame");
            }
            this.retireOldest(true);
        }
        this.head = start + aligned;
        return start % this.capacity;
    }

    boolean hasOpenFrame() {
        return this.head != this.frameStart;
    }

    void flushOpenRanges(VBuffer buffer) {
        long size = this.head - this.frameStart;
        if (size == 0) return;
        long start = this.frameStart % this.capacity;
        if (start + size <= this.capacity) {
            buffer.flush(start, size);
        } else {
            long first = this.capacity - start;
            buffer.flush(start, first);
            buffer.flush(0, size - first);
        }
    }

    void endFrame(@Nullable T frameData, @Nullable FrameFence shared) {
        if (this.head != this.frameStart || frameData != null) {
            FrameFence fence;
            if (shared != null) {
                shared.retain();
                fence = shared;
            } else {
                fence = new FrameFence(VkContext.get().createFrameFence());
            }
            this.inFlight.add(new PendingFrame<>(fence, this.head, frameData));
        }
        this.frameStart = this.head;
    }

    void retireCompleted(long pollEpoch) {
        while (!this.inFlight.isEmpty() && this.inFlight.peek().fence.poll(pollEpoch)) {
            this.retireOldest(false);
        }
    }

    void retireAll() {
        while (!this.inFlight.isEmpty()) {
            this.retireOldest(true);
        }
    }

    private void retireOldest(boolean wait) {
        var frame = this.inFlight.peek();
        if (wait) {
            boolean done = frame.fence.await();
            if (!done) throw new IllegalStateException("Timed out waiting for a frame to finish on the GPU");
        }
        this.inFlight.pop();
        try {
            if (frame.data != null) this.onRetire.accept(frame.data);
        } finally {
            frame.fence.release();
            this.tail = frame.end;
        }
    }
}
