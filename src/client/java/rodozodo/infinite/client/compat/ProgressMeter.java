package rodozodo.infinite.client.compat;

import java.util.ArrayDeque;

// Tracks recent progress samples over a sliding window to estimate remaining time

final class ProgressMeter {
    private static final long WINDOW_NANOS = 15_000_000_000L;
    private static final long LEAST_NANOS = 5_000_000_000L;

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    private record Sample(long nanos, long done) {}

    void sample(long done) {
        long now = System.nanoTime();
        var last = this.samples.peekLast();
        if (last != null && now - last.nanos() > WINDOW_NANOS) this.samples.clear();
        this.samples.addLast(new Sample(now, done));
        while (this.samples.size() > 2 && now - this.samples.peekFirst().nanos() > WINDOW_NANOS) {
            this.samples.removeFirst();
        }
    }

    long secondsLeft(long done, long total) {
        var first = this.samples.peekFirst();
        var last = this.samples.peekLast();
        if (first == null || last == null || total <= done) return -1;
        long nanos = last.nanos() - first.nanos();
        long moved = last.done() - first.done();
        if (nanos < LEAST_NANOS || moved <= 0) return -1;
        double perSecond = moved * 1e9 / nanos;
        return Math.max(1, (long) Math.ceil((total - done) / perSecond));
    }

    void clear() {
        this.samples.clear();
    }

    static String duration(long seconds) {
        if (seconds < 60) return seconds + " s";
        if (seconds < 3600) return seconds / 60 + " min " + seconds % 60 + " s";
        return seconds / 3600 + " h " + seconds / 60 % 60 + " min";
    }
}
