package rodozodo.infinite.client.core.vk;

import com.mojang.renderpearl.api.commands.GpuFence;


// Reference-counted frame-end {@link GpuFence} shared across rings closing at the same frame boundary

final class FrameFence {
    private static long pollEpoch;

    private final GpuFence fence;
    private int refs = 1;
    private boolean signalled;
    private long lastPollEpoch = -1;

    FrameFence(GpuFence fence) {
        this.fence = fence;
    }

    static long nextPollEpoch() {
        return ++pollEpoch;
    }

    void retain() {
        this.refs++;
    }

    boolean poll(long epoch) {
        if (this.signalled) return true;
        if (this.lastPollEpoch == epoch) return false;
        this.lastPollEpoch = epoch;
        if (this.fence.awaitCompletion(0)) {
            this.signalled = true;
            return true;
        }
        return false;
    }

    boolean await() {
        if (this.signalled) return true;
        if (this.fence.awaitCompletion(GpuFence.NO_TIMEOUT)) {
            this.signalled = true;
            return true;
        }
        return false;
    }

    void release() {
        if (--this.refs == 0) this.fence.close();
    }
}
