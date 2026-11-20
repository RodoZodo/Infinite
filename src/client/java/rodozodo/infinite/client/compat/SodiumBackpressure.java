package rodozodo.infinite.client.compat;

import rodozodo.infinite.client.hook.sodium.SodiumWorldRendererAccess;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;

import java.util.concurrent.TimeUnit;

// Monitors Sodium's chunk builder queue so LoD ingest and meshing yield worker time when Sodium is busy building visible chunks.

public final class SodiumBackpressure {
    private static final long SAMPLE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private static volatile long nextSample;
    private static volatile int pendingBuilds;
    private static volatile int builderThreads = 1;
    private static volatile boolean holding;

    private SodiumBackpressure() {}

    public static boolean ingestMayRun() {
        sample();
        return !holding;
    }

    public static boolean meshMayRun() {
        sample();
        return pendingBuilds < Math.max(4, builderThreads * 2);
    }

    static boolean nextHolding(boolean holding, int pending, int threads, int busy) {
        threads = Math.max(1, threads);
        boolean saturated = pending >= Math.max(2, threads) || busy >= threads;
        boolean calm = pending <= Math.max(1, threads / 2) && busy == 0;
        return holding ? !calm : saturated;
    }

    public static int pendingBuilds() {
        sample();
        return pendingBuilds;
    }

    public static String describe() {
        sample();
        return "  Sodium: " + pendingBuilds + " chunk builds queued" + (holding ? " · pausing LoD's" : "");
    }

    private static void sample() {
        long now = System.nanoTime();
        if (now < nextSample) return;
        nextSample = now + SAMPLE_NANOS;
        try {
            var renderer = SodiumWorldRenderer.instanceNullable();
            var sections = renderer == null ? null : ((SodiumWorldRendererAccess) renderer).infinite$sectionManager();
            var builder = sections == null ? null : sections.getBuilder();
            if (builder == null) {
                pendingBuilds = 0;
                builderThreads = 1;
                holding = false;
                return;
            }
            int pending = builder.getScheduledJobCount();
            int threads = Math.max(1, builder.getTotalThreadCount());
            pendingBuilds = pending;
            builderThreads = threads;
            holding = nextHolding(holding, pending, threads, builder.getBusyThreadCount());
        } catch (RuntimeException | LinkageError e) {
            pendingBuilds = 0;
            builderThreads = 1;
            holding = false;
        }
    }
}
