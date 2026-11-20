package rodozodo.infinite.importers;

import rodozodo.infinite.world.LodWorld;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class Imports {
    @FunctionalInterface
    public interface Factory {
        LodImport<?, ?> create() throws IOException;
    }

    private final Map<LodWorld, LodImport<?, ?>> running = new HashMap<>();
    private final Function<LodImport<?, ?>, LodImport.Listener> listeners;

    public Imports(Function<LodImport<?, ?>, LodImport.Listener> listeners) {
        this.listeners = listeners;
    }

    public boolean start(LodWorld world, Factory factory) throws IOException {
        LodImport<?, ?> job;
        synchronized (this) {
            var current = this.running.get(world);
            if (current != null && !current.isFinished()) return false;
            job = factory.create();
            if (job.world() != world) throw new IllegalArgumentException("Import targets another world");
            this.running.put(world, job);
        }
        var report = this.listeners.apply(job);
        job.start(new LodImport.Listener() {
            @Override
            public void progress(long done, long expected) {
                report.progress(done, expected);
            }

            @Override
            public void finished(LodImport.Result result) {
                synchronized (Imports.this) {
                    Imports.this.running.remove(world, job);
                }
                report.finished(result);
            }
        });
        return true;
    }

    public boolean cancel(LodWorld world) {
        LodImport<?, ?> job;
        synchronized (this) {
            job = this.running.get(world);
        }
        if (job == null) return false;
        job.cancel();
        return true;
    }

    public synchronized boolean isRunning(LodWorld world) {
        var current = this.running.get(world);
        return current != null && !current.isFinished();
    }

    public void cancelAll() {
        ArrayList<LodImport<?, ?>> jobs;
        synchronized (this) {
            jobs = new ArrayList<>(this.running.values());
        }
        for (var job : jobs) job.cancel();
    }
}
