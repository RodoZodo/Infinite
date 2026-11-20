package rodozodo.infinite.world;

import rodozodo.infinite.Log;
import rodozodo.infinite.thread.JobQueue;
import rodozodo.infinite.thread.WorkerPool;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public final class SaveQueue {
    private static final int MAX_QUEUED = 4096;
    private static final long DELAY_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final int MAX_WAITING = (int) Math.clamp(Runtime.getRuntime().maxMemory() / 16
            / (LodSection.VOLUME * 5L), 64, 1024);

    private final JobQueue jobs;
    private final DelayQueue<Waiting> waiting = new DelayQueue<>();
    private final ConcurrentLinkedQueue<LodSection> queue = new ConcurrentLinkedQueue<>();
    private final Thread timer;

    private record Waiting(LodSection section, long due) implements Delayed {
        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(this.due - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(this.due, ((Waiting) other).due);
        }
    }

    public SaveQueue(WorkerPool workers) {
        this.jobs = workers.newQueue("LOD saving", 100, this::saveNext);
        this.timer = new Thread(this::passDue, "Infinite save timer");
        this.timer.setDaemon(true);
        this.timer.start();
    }

    void save(LodSection section) {
        if (!this.jobs.isLive() || this.jobs.pending() >= MAX_QUEUED) {
            saveNow(section);
            return;
        }
        var entry = new Waiting(section, System.nanoTime() + DELAY_NANOS);
        this.waiting.add(entry);
        while (this.waiting.size() > MAX_WAITING) {
            var oldest = this.waiting.peek();
            if (oldest != null && this.waiting.remove(oldest)) this.submit(oldest.section);
        }
        // Save directly if shutdown raced after the live check above
        if (!this.jobs.isLive() && this.waiting.remove(entry)) saveNow(section);
    }

    private void passDue() {
        try {
            while (true) this.submit(this.waiting.take().section);
        } catch (InterruptedException stopping) {
        }
    }

    private void submit(LodSection section) {
        this.queue.add(section);
        if (!this.jobs.submit()) this.drain();
    }

    private void saveNext() {
        var section = this.queue.poll();
        if (section != null) saveNow(section);
    }

    private static void saveNow(LodSection section) {
        do {
            // Clear dirty before writing so concurrent edits schedule another save
            section.saving = true;
            section.dirty = false;
            // Snapshot edit counts after clearing dirty so later edits belong to the next save
            int[] edits = section.edits();
            boolean saved = false;
            try {
                section.cache.storage().save(section);
                saved = true;
            } catch (Exception e) {
                Log.error("Could not save LoD section " + LodPos.describe(section.key), e);
            } finally {
                section.saving = false;
            }
            if (saved) section.cache.world().saved(section, edits);
        } while (section.cache.finishSave(section));
    }

    private void drain() {
        for (LodSection section; (section = this.queue.poll()) != null; ) saveNow(section);
    }

    public int pending() {
        return this.jobs.pending();
    }

    public void shutdown() {
        this.jobs.shutdown();
        this.timer.interrupt();
        try {
            this.timer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var left = new ArrayList<Waiting>();
        this.waiting.drainTo(left, Integer.MAX_VALUE);
        // drainTo() only takes expired entries; collect remaining ones too
        left.addAll(this.waiting);
        this.waiting.clear();
        for (var entry : left) this.queue.add(entry.section);
        this.drain();
    }
}