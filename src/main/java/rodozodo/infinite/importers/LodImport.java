package rodozodo.infinite.importers;

import rodozodo.infinite.Log;
import rodozodo.infinite.thread.JobQueue;
import rodozodo.infinite.thread.Worker;
import rodozodo.infinite.thread.WorkerPool;
import rodozodo.infinite.world.LodWorld;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public abstract class LodImport<T, C> {
    private static final int MAX_QUEUED = 2048;
    private static final long REPORT_EVERY_NANOS = 100_000_000;

    public record Result(String name, long imported, long skipped, long failed, boolean cancelled, long millis) {}

    public interface Listener {
        void progress(long done, long expected);

        void finished(Result result);
    }

    protected final LodWorld world;
    private final String name;
    private final WorkerPool pool;
    private final BooleanSupplier canRun;
    private final ConcurrentLinkedQueue<T> tasks = new ConcurrentLinkedQueue<>();
    private final Semaphore room = new Semaphore(MAX_QUEUED);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong imported = new AtomicLong(), skipped = new AtomicLong(), failed = new AtomicLong();
    private final AtomicLong lastReport = new AtomicLong();
    private volatile long expected;
    private volatile boolean cancelled;
    private @Nullable JobQueue queue;
    private @Nullable Thread producer;
    private @Nullable Listener listener;

    protected LodImport(String name, LodWorld world, WorkerPool pool, BooleanSupplier canRun) {
        this.name = name;
        this.world = world;
        this.pool = pool;
        this.canRun = canRun;
    }

    public final String name() {
        return this.name;
    }

    public final LodWorld world() {
        return this.world;
    }

    protected abstract void findWork() throws Exception;

    protected abstract int chunksIn(T task);

    protected abstract int importTask(T task, C context) throws Exception;

    protected abstract C newContext() throws Exception;

    protected void closeContext(C context) throws Exception {}

    protected int weight() {
        return 3;
    }

    public final void start(Listener listener) {
        if (!this.started.compareAndSet(false, true)) throw new IllegalStateException("Import already started");
        this.listener = listener;
        this.world.acquireRef();
        this.queue = this.pool.newQueue(this.name, this.weight(), this.canRun, () -> new Worker() {
            private final @Nullable C context = LodImport.this.openContext();

            @Override
            public boolean runJob() {
                LodImport.this.run(LodImport.this.tasks.poll(), this.context);
                return true;
            }

            @Override
            public void close() {
                if (this.context == null) return;
                try {
                    LodImport.this.closeContext(this.context);
                } catch (Exception e) {
                    Log.error(LodImport.this.name + ": could not close worker", e);
                }
            }
        });
        var thread = new Thread(this::produce, this.name);
        thread.setDaemon(true);
        this.producer = thread;
        thread.start();
    }

    public final void cancel() {
        this.cancelled = true;
        var thread = this.producer;
        if (thread == null || thread == Thread.currentThread()) return;
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected final void setExpected(long chunks) {
        this.expected = chunks;
        this.report(true);
    }

    protected final void offer(T task) throws InterruptedException {
        boolean tookRoom = false, submitted = false;
        try {
            this.room.acquire();
            tookRoom = true;
            if (this.cancelled) throw new InterruptedException("Import cancelled");
            this.tasks.add(task);
            if (!this.queue.submit()) {
                this.tasks.remove(task);
                throw new InterruptedException("Import queue closed");
            }
            submitted = true;
        } finally {
            // Submitted tasks release their permit in run(); unsubmitted ones release it here.
            if (!submitted && tookRoom) this.room.release();
        }
    }

    private @Nullable C openContext() {
        try {
            return this.newContext();
        } catch (Exception e) {
            Log.error(this.name + ": could not start worker", e);
            return null;
        }
    }

    private void run(@Nullable T task, @Nullable C context) {
        if (task == null) return;
        try {
            if (this.cancelled) return;
            int chunks = this.chunksIn(task);
            if (context == null) {
                this.failed.addAndGet(chunks);
                return;
            }
            try {
                int done = this.importTask(task, context);
                this.imported.addAndGet(done);
                this.skipped.addAndGet(chunks - done);
            } catch (Exception | LinkageError e) {
                this.failed.addAndGet(chunks);
                Log.error(this.name + ": failed to import " + task, e);
            }
            this.report(false);
        } finally {
            this.room.release();
        }
    }

    private void report(boolean force) {
        var listener = this.listener;
        if (listener == null) return;
        long now = System.nanoTime(), last = this.lastReport.get();
        if (!force && now - last < REPORT_EVERY_NANOS) return;
        if (!this.lastReport.compareAndSet(last, now) && !force) return;
        listener.progress(this.imported.get() + this.skipped.get() + this.failed.get(), this.expected);
    }

    private void produce() {
        long start = System.nanoTime();
        try {
            this.findWork();
            // Acquiring all permits waits until every submitted task has finished.
            this.room.acquire(MAX_QUEUED);
            this.room.release(MAX_QUEUED);
        } catch (InterruptedException e) {
        } catch (Exception | LinkageError e) {
            Log.error(this.name + " stopped: could not read source", e);
            this.cancelled = true;
        }
        this.queue.shutdown();
        this.tasks.clear();
        this.world.releaseRef();
        var result = new Result(this.name, this.imported.get(), this.skipped.get(), this.failed.get(), this.cancelled,
                (System.nanoTime() - start) / 1_000_000);
        try {
            this.listener.finished(result);
        } catch (RuntimeException e) {
            Log.error(this.name + ": import listener failed", e);
        }
    }

    public final boolean isFinished() {
        var thread = this.producer;
        return thread != null && !thread.isAlive();
    }
}