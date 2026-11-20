package rodozodo.infinite.thread;

import rodozodo.infinite.Log;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;


public final class JobQueue {
    final WorkerPool pool;
    final String name;
    final long weight;
    final @Nullable BooleanSupplier canRun;

    private final Supplier<? extends Worker> workers;
    private final ThreadLocal<Worker> threadWorker = new ThreadLocal<>();
    private final List<Worker> allWorkers = new ArrayList<>();
    final Condition idle;

    volatile int pending;
    int running;
    volatile boolean live = true;
    int threadShare = Integer.MAX_VALUE;

    JobQueue(WorkerPool pool, String name, long weight, @Nullable BooleanSupplier canRun, Supplier<? extends Worker> workers) {
        if (weight <= 0) throw new IllegalArgumentException("Weight must be positive");
        this.pool = pool;
        this.name = name;
        this.weight = weight;
        this.canRun = canRun;
        this.workers = workers;
        this.idle = pool.lock.newCondition();
    }

    public boolean submit() {
        return this.pool.submit(this);
    }

    public boolean isLive() {
        return this.live;
    }

    public void setThreadShare(int threads) {
        if (threads < 1) throw new IllegalArgumentException("Thread share < 1");
        this.pool.lock.lock();
        try {
            this.threadShare = threads;
        } finally {
            this.pool.lock.unlock();
        }
    }

    public int pending() {
        return this.pending;
    }

    public int shutdown() {
        this.checkNotOwnJob("shut down");
        int dropped = this.pool.retire(this);
        List<Worker> workers;
        synchronized (this.allWorkers) {
            workers = new ArrayList<>(this.allWorkers);
            this.allWorkers.clear();
        }
        for (var worker : workers) {
            try {
                worker.close();
            } catch (Exception e) {
                Log.error("Could not close a '" + this.name + "' worker", e);
            }
        }
        return dropped;
    }

    private void checkNotOwnJob(String action) {
        if (WorkerPool.currentQueue() == this) {
            throw new IllegalStateException("Can't " + action + " '" + this.name + "' from one of its own jobs");
        }
    }

    boolean runClaimed() {
        try {
            var worker = this.threadWorker.get();
            if (worker == null) {
                worker = this.workers.get();
                this.threadWorker.set(worker);
                synchronized (this.allWorkers) {
                    this.allWorkers.add(worker);
                }
            }
            return worker.runJob();
        } catch (Exception e) {
            Log.error("'" + this.name + "' job failed on " + Thread.currentThread().getName(), e);
            return true;
        }
    }

    @Override
    public String toString() {
        return this.name;
    }
}
