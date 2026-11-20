package rodozodo.infinite.thread;

import rodozodo.infinite.Log;
import rodozodo.infinite.util.MacQos;
import rodozodo.infinite.util.Platform;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class WorkerPool {
    private static final long RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final ThreadLocal<JobQueue> CURRENT = new ThreadLocal<>();
    private static final MacQos.Level WORKER_QOS = workerQos();
    private static MacQos.Level workerQos() {
        String chosen = System.getProperty("infinite.workerQos");
        if (chosen == null) return Platform.isAppleSilicon() ? MacQos.Level.USER_INITIATED : null;
        return MacQos.Level.parse(chosen);
    }

    private static final ThreadLocal<HelperLane> CURRENT_LANE = new ThreadLocal<>();

    final ReentrantLock lock = new ReentrantLock();
    private final Condition dedicatedWake = this.lock.newCondition();
    private final String threadName;
    private final List<JobQueue> queues = new ArrayList<>();
    private final List<HelperLane> lanes = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private int totalPending;
    private int dedicatedSleeping;
    private volatile BooleanSupplier dedicatedGate = () -> true;
    private int retiring;
    private int nextThreadId;
    private boolean shutdown;

    // Volatile snapshots for lock-free UI reads
    private volatile int dedicatedCount, laneCount;

    public WorkerPool(String threadName) {
        this.threadName = threadName;
    }

    static @Nullable JobQueue currentQueue() {
        return CURRENT.get();
    }

    public static boolean wantThreadBack() {
        var lane = CURRENT_LANE.get();
        return lane != null && lane.wanted();
    }

    public JobQueue newQueue(String name, long weight, @Nullable BooleanSupplier canRun, Supplier<? extends Worker> workers) {
        var queue = new JobQueue(this, name, weight, canRun, workers);
        this.lock.lock();
        try {
            if (this.shutdown) throw new IllegalStateException("Worker pool is shut down");
            this.queues.add(queue);
        } finally {
            this.lock.unlock();
        }
        return queue;
    }

    public JobQueue newQueue(String name, long weight, Runnable job) {
        return this.newQueue(name, weight, null, () -> () -> {
            job.run();
            return true;
        });
    }

    boolean submit(JobQueue queue) {
        this.lock.lock();
        try {
            if (!queue.live) return false;
            queue.pending++;
            this.totalPending++;
            this.wakeOne();
        } finally {
            this.lock.unlock();
        }
        return true;
    }

    private void wakeOne() {

        // Do not wake dedicated threads while gated off
        if (this.dedicatedSleeping != 0 && this.dedicatedMayRun()) {
            this.dedicatedWake.signal();
            return;
        }
        for (var lane : this.lanes) {
            if (lane.sleeping != 0) {
                lane.wake.signal();
                return;
            }
        }
    }

    int retire(JobQueue queue) {
        this.lock.lock();
        try {
            if (!queue.live) throw new IllegalStateException("'" + queue.name + "' is already shut down");
            queue.live = false;
            int dropped = queue.pending;
            queue.pending = 0;
            this.totalPending -= dropped;
            this.queues.remove(queue);
            queue.idle.signalAll();
            while (queue.running != 0) queue.idle.awaitUninterruptibly();
            return dropped;
        } finally {
            this.lock.unlock();
        }
    }

    // Must hold lock
    @Nullable JobQueue claim() {
        if (this.totalPending == 0) return null;
        long total = 0;
        for (var queue : this.queues) {
            if (queue.pending != 0 && mayRun(queue) && !this.yieldsThreads(queue)) total += queue.pending * queue.weight;
        }
        if (total == 0) return null;
        long pick = ThreadLocalRandom.current().nextLong(total);
        JobQueue chosen = null;
        for (var queue : this.queues) {
            if (queue.pending == 0 || !mayRun(queue) || this.yieldsThreads(queue)) continue;
            chosen = queue;
            pick -= queue.pending * queue.weight;
            if (pick < 0) break;
        }
        if (chosen == null) return null;
        chosen.pending--;
        chosen.running++;
        this.totalPending--;
        return chosen;
    }

    // Must hold lock.
    private boolean yieldsThreads(JobQueue queue) {
        if (queue.running < queue.threadShare) return false;
        for (var other : this.queues) {
            if (other != queue && other.pending != 0 && mayRun(other)) return true;
        }
        return false;
    }

    private static boolean mayRun(JobQueue queue) {
        try {
            return queue.canRun == null || queue.canRun.getAsBoolean();
        } catch (Exception e) {
            Log.error("Run check of '" + queue.name + "' failed", e);
            return true;
        }
    }

    // Must hold lock
    // Unlocks while running the job and re-acquires before returning.
    void runWithLockHeld(JobQueue queue, @Nullable HelperLane lane) {
        if (lane != null && lane.wanted()) {
            // Do not start a job if the helper lane already has a permit waiting.
            requeue(queue);
            finish(queue);
            return;
        }
        this.lock.unlock();
        var previousQueue = CURRENT.get();
        var previousLane = CURRENT_LANE.get();
        MacQos.Level lentFrom = lane != null && WORKER_QOS != null ? MacQos.current() : null;
        if (lentFrom != null) MacQos.set(WORKER_QOS);
        CURRENT.set(queue);
        CURRENT_LANE.set(lane);
        boolean finished = true;
        try {
            finished = queue.runClaimed();
        } finally {
            CURRENT.set(previousQueue);
            CURRENT_LANE.set(previousLane);
            if (lentFrom != null) MacQos.set(lentFrom);
            this.lock.lock();
            if (!finished) requeue(queue);
            finish(queue);
        }
    }

    // Must hold lock.
    private void requeue(JobQueue queue) {
        if (!queue.live) return;
        queue.pending++;
        this.totalPending++;
        this.wakeOne();
    }

    private static void finish(JobQueue queue) {
        queue.running--;
        if (queue.running == 0 && (queue.pending == 0 || !queue.live)) queue.idle.signalAll();
    }

    // Must hold lock.
    void sleep(Condition wake) throws InterruptedException {
        if (this.totalPending != 0) {
            wake.awaitNanos(RECHECK_NANOS);
        } else {
            wake.await();
        }
    }

    public boolean setDedicatedThreads(int count) {
        if (count < 0) throw new IllegalArgumentException("Thread count < 0");
        this.lock.lock();
        try {
            if (this.shutdown) return false;
            int current = this.threads.size() - this.retiring;
            if (count == current) return false;
            if (count < current) {
                this.retiring += current - count;
                this.dedicatedWake.signalAll();
                return true;
            }
            int needed = count - current;
            int kept = Math.min(this.retiring, needed);
            this.retiring -= kept;
            for (int i = kept; i < needed; i++) {
                var thread = new Thread(this::dedicatedLoop, this.threadName + " #" + this.nextThreadId++);
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 2);
                this.threads.add(thread);
                thread.start();
            }
            return true;
        } finally {
            this.dedicatedCount = this.threads.size() - this.retiring;
            this.lock.unlock();
        }
    }

    public void setDedicatedGate(BooleanSupplier mayRun) {
        this.dedicatedGate = mayRun;
    }

    private boolean dedicatedMayRun() {
        try {
            return this.dedicatedGate.getAsBoolean();
        } catch (Exception e) {
            Log.error("Dedicated thread gate failed", e);
            return true;
        }
    }

    private void dedicatedLoop() {
        if (WORKER_QOS != null) MacQos.set(WORKER_QOS);
        this.lock.lock();
        try {
            while (true) {
                if (this.retiring != 0) {
                    this.retiring--;
                    return;
                }
                if (!this.dedicatedMayRun()) {
                    // Do not increment dedicatedSleeping while gated off so wakeOne() wakes helpers instead.
                    try {
                        this.dedicatedWake.awaitNanos(RECHECK_NANOS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                var queue = this.claim();
                if (queue != null) {
                    this.runWithLockHeld(queue, null);
                    continue;
                }
                this.dedicatedSleeping++;
                try {
                    this.sleep(this.dedicatedWake);
                } catch (InterruptedException e) {
                    return;
                } finally {
                    this.dedicatedSleeping--;
                }
            }
        } finally {
            this.threads.remove(Thread.currentThread());
            this.lock.unlock();
        }
    }

    public HelperLane newHelperLane() {
        var lane = new HelperLane(this);
        this.lock.lock();
        try {
            this.lanes.add(lane);
            this.laneCount = this.lanes.size();
        } finally {
            this.lock.unlock();
        }
        return lane;
    }

    void removeLane(HelperLane lane) {
        this.lock.lock();
        try {
            this.lanes.remove(lane);
            this.laneCount = this.lanes.size();
            // Wake remaining threads if pending work was relying on this lane.
            if (this.totalPending != 0) this.wakeOne();
        } finally {
            this.lock.unlock();
        }
    }

    public int dedicatedThreads() {
        return this.dedicatedCount;
    }

    public int helperLanes() {
        return this.laneCount;
    }

    public void shutdown() {
        List<Thread> threads;
        this.lock.lock();
        try {
            if (this.shutdown) throw new IllegalStateException("Worker pool already shut down");
            this.shutdown = true;
            for (var queue : this.queues) Log.warn("Worker queue '" + queue.name + "' still open at shutdown");
            this.retiring = this.threads.size();
            this.dedicatedCount = 0;
            this.dedicatedWake.signalAll();
            threads = new ArrayList<>(this.threads);
        } finally {
            this.lock.unlock();
        }
        for (var thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
