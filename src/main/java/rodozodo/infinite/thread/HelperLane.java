package rodozodo.infinite.thread;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;

public final class HelperLane {
    private final WorkerPool pool;
    final Condition wake;

    // Written under pool.lock
    // Read lock-free in release() after incrementing permits
    volatile int sleeping;
    private final AtomicInteger permits = new AtomicInteger();
    private boolean closed;

    HelperLane(WorkerPool pool) {
        this.pool = pool;
        this.wake = pool.lock.newCondition();
    }

    public void release(int permits) {
        if (permits < 0) throw new IllegalArgumentException("Negative permits");
        if (permits == 0) return;
        this.permits.addAndGet(permits);
        if (this.sleeping == 0) return;
        this.pool.lock.lock();
        try {
            if (permits == 1) {
                this.wake.signal();
            } else {
                this.wake.signalAll();
            }
        } finally {
            this.pool.lock.unlock();
        }
    }

    public void acquire() throws InterruptedException {
        var lock = this.pool.lock;
        lock.lockInterruptibly();
        try {
            while (true) {
                if (this.tryTake()) return;
                var queue = this.closed ? null : this.pool.claim();
                if (queue != null) {
                    this.pool.runWithLockHeld(queue, this);
                    if (Thread.interrupted()) throw new InterruptedException();
                    continue;
                }
                this.sleeping++;
                try {
                    // Re-check permits after publishing sleeping++ so no concurrent release() wakeup is missed.
                    if (this.permits.get() != 0) continue;
                    if (this.closed) {
                        this.wake.await();
                    } else {
                        this.pool.sleep(this.wake);
                    }
                } finally {
                    this.sleeping--;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire() {
        return this.tryTake();
    }

    private boolean tryTake() {
        while (true) {
            int free = this.permits.get();
            if (free == 0) return false;
            if (this.permits.compareAndSet(free, free - 1)) return true;
        }
    }

    public int drainPermits() {
        return this.permits.getAndSet(0);
    }

    boolean wanted() {
        return this.permits.get() != 0;
    }

    public int availablePermits() {
        return this.permits.get();
    }

    public void close() {
        this.pool.lock.lock();
        try {
            if (this.closed) return;
            this.closed = true;
        } finally {
            this.pool.lock.unlock();
        }
        this.pool.removeLane(this);
    }
}