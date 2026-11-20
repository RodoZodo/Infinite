package rodozodo.infinite.client.compat;

import rodozodo.infinite.server.ServerGenerate;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

// Shows a singleplayer {@code /infinite pregenerate} run on the boss bar and posts a short completion line in chat

public final class GenerateProgressBar {
    private static final int EVERY_TICKS = 3;
    private static final int IDLE_TICKS = 20;

    private static final HudProgressBar BAR = new HudProgressBar();
    private static final ProgressMeter METER = new ProgressMeter();
    private static int ticks;
    private static boolean shown;

    private GenerateProgressBar() {}

    public static void tick(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        var level = minecraft.level;
        if (server == null || level == null) {
            hidden();
            return;
        }
        if (++ticks < (shown ? EVERY_TICKS : IDLE_TICKS)) return;
        ticks = 0;
        var dimension = level.dimension();
        server.execute(() -> {
            var progress = ServerGenerate.progress(dimension);
            var completed = progress == null ? ServerGenerate.takeCompleted(dimension) : null;
            minecraft.execute(() -> show(progress, completed));
        });
    }

    private static void show(@Nullable ServerGenerate.Progress progress, @Nullable ServerGenerate.Completed completed) {
        if (completed != null) {
            shown = false;
            METER.clear();
            long seconds = Math.max(1, completed.millis() / 1000);
            BAR.end(Component.literal(String.format(Locale.ROOT, "Pregenerate %s finished (%,d chunks in %s).",
                    completed.where().getString(), completed.total(), ProgressMeter.duration(seconds))));
            return;
        }
        if (progress == null) {
            hidden();
            return;
        }
        shown = true;
        METER.sample(progress.reached());
        long left = METER.secondsLeft(progress.reached(), progress.total());
        var label = progress.label();
        if (left > 0) label = label.copy().append(Component.translatable("infinite.progress.left", ProgressMeter.duration(left)));
        BAR.show(label, progress.fraction());
    }

    private static void hidden() {
        if (!shown) return;
        shown = false;
        METER.clear();
        BAR.hide();
    }
}
