package rodozodo.infinite.client.compat;

import rodozodo.infinite.importers.LodImport;
import net.minecraft.network.chat.Component;

import java.util.Locale;

// Displays an import job's progress and estimated time remaining on a boss bar

public final class ImportProgressBar implements LodImport.Listener {
    private final String name;
    private final boolean quietEnd;
    private final HudProgressBar bar = new HudProgressBar();
    private final long startNanos = System.nanoTime();

    public ImportProgressBar(LodImport<?, ?> job) {
        this(job, false);
    }

    public ImportProgressBar(LodImport<?, ?> job, boolean quietEnd) {
        this.name = job.name();
        this.quietEnd = quietEnd;
        this.bar.show(Component.literal(this.name + ": preparing..."), 0);
    }

    @Override
    public void progress(long done, long expected) {
        double seconds = (System.nanoTime() - this.startNanos) / 1e9;
        double rate = done / Math.max(seconds, 1e-3);
        var text = new StringBuilder(this.name).append(": ")
                .append(String.format(Locale.ROOT, "%,d / %,d chunks", done, expected));
        if (done > 0 && expected > done && seconds > 5) {
            text.append(", ").append(ProgressMeter.duration((long) ((expected - done) / rate))).append(" left");
        }
        float fraction = expected <= 0 ? 0 : (float) Math.min(1, (double) done / expected);
        this.bar.show(Component.literal(text.toString()), fraction);
    }

    @Override
    public void finished(LodImport.Result result) {
        if (result.cancelled() || this.quietEnd) {
            this.bar.hide();
            return;
        }
        long seconds = Math.max(1, result.millis() / 1000);
        var message = new StringBuilder(this.name)
                .append(String.format(Locale.ROOT, " finished (%,d chunks in %s",
                        result.imported(), ProgressMeter.duration(seconds)));
        if (result.failed() != 0) message.append(String.format(Locale.ROOT, ", %,d failed", result.failed()));
        message.append(").");
        this.bar.end(Component.literal(message.toString()));
    }
}
