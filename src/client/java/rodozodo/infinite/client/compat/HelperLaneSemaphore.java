package rodozodo.infinite.client.compat;

import rodozodo.infinite.thread.HelperLane;

import java.util.concurrent.Semaphore;

// Stands in for the semaphore of Sodium's chunk job queue
// Sodium's builder threads run LOD jobs while they have no chunks to build.

public final class HelperLaneSemaphore extends Semaphore {
    private final HelperLane lane;

    public HelperLaneSemaphore(HelperLane lane) {
        super(0);
        this.lane = lane;
    }

    @Override
    public void release() {
        this.lane.release(1);
    }

    @Override
    public void release(int permits) {
        this.lane.release(permits);
    }

    @Override
    public void acquire() throws InterruptedException {
        this.lane.acquire();
    }

    @Override
    public void acquireUninterruptibly() {
        boolean interrupted = false;
        while (true) {
            try {
                this.lane.acquire();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public boolean tryAcquire() {
        return this.lane.tryAcquire();
    }

    @Override
    public int drainPermits() {
        return this.lane.drainPermits();
    }

    @Override
    public int availablePermits() {
        return this.lane.availablePermits();
    }
}
