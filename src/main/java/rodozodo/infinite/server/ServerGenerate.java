package rodozodo.infinite.server;

import rodozodo.infinite.Infinite;
import rodozodo.infinite.LodWorldKey;
import rodozodo.infinite.Log;
import rodozodo.infinite.importers.LodImport;
import rodozodo.infinite.importers.RegionImport;
import rodozodo.infinite.util.CpuInfo;
import rodozodo.infinite.world.voxelize.IngestQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ServerGenerate {
    public static final int MIN_RADIUS = 16, MAX_RADIUS = 2048;
    private static final int DEFAULT_RADIUS = 256;

    public enum Outcome { CONFIGURED, STARTED, PAUSED, STOPPED, ALREADY_RUNNING, NOT_RUNNING, NO_ENGINE }

    public record Result(Outcome outcome, String detail) {}

    public record Progress(Component where, long reached, long total, long generated) {
        public float fraction() {
            return this.total <= 0 ? 0f : (float) Math.min(1, (double) this.reached / this.total);
        }

        public Component label() {
            return Component.translatable("infinite.progress.pregenerate", this.where,
                    count(this.reached), count(this.total));
        }

        private static String count(long value) {
            return String.format(Locale.ROOT, "%,d", value);
        }
    }

    public record Completed(Component where, long total, long millis) {}

    static final int MAX_GENERATING = 64;
    private static final int MAX_HOLD_TICKS = 40;
    private static final long WALK_NANOS = 2_000_000L;
    private static final int MAX_PASSED_PER_TICK = 1 << 18;
    private static final long BUSY_TICK_NANOS = 45_000_000L;

    private static final Map<ResourceKey<Level>, Run> RUNS = new HashMap<>();
    private static @Nullable TicketType ticket;
    private static final ConcurrentLinkedQueue<Generated> FINISHED = new ConcurrentLinkedQueue<>();
    private static final ArrayDeque<Held> HELD = new ArrayDeque<>();
    private static int generating;

    private record Generated(Run run, ServerLevel level, ChunkPos pos, @Nullable Throwable error) {}

    private record Held(ServerLevel level, ChunkPos pos, int until) {}

    private ServerGenerate() {}

    public static Result configure(ServerLevel level, @Nullable Integer radius, @Nullable Integer centerX,
                                   @Nullable Integer centerZ, int fallbackX, int fallbackZ) {
        if (Infinite.engine() == null) return new Result(Outcome.NO_ENGINE, "");
        var previous = RUNS.get(level.dimension());
        boolean keepExisting = previous != null;
        int newRadius = radius != null ? radius : keepExisting ? previous.radius : DEFAULT_RADIUS;
        int newX = centerX != null ? centerX : keepExisting ? previous.centerX : fallbackX;
        int newZ = centerZ != null ? centerZ : keepExisting ? previous.centerZ : fallbackZ;
        if (keepExisting && previous.radius == newRadius && previous.centerX == newX && previous.centerZ == newZ) {
            return new Result(Outcome.CONFIGURED, "");
        }
        boolean stoppedRunning = previous != null && previous.running();
        if (previous != null) {
            previous.paused = true;
            previous.endDisk(level);
        }
        var run = new Run(level.dimension(), newRadius, newX, newZ);
        run.paused = true;
        RUNS.put(level.dimension(), run);
        return new Result(Outcome.CONFIGURED, stoppedRunning ? "paused" : "");
    }

    public static Result start(ServerLevel level, int radius, int centerX, int centerZ) {
        if (Infinite.engine() == null) return new Result(Outcome.NO_ENGINE, "");
        var previous = RUNS.get(level.dimension());
        if (previous != null) {
            previous.paused = true;
            previous.endDisk(level);
        }
        var run = new Run(level.dimension(), radius, centerX, centerZ);
        RUNS.put(level.dimension(), run);
        beginDisk(level, run);
        return new Result(Outcome.STARTED, run.where());
    }

    public static Result start(ServerLevel level, int centerX, int centerZ) {
        if (Infinite.engine() == null) return new Result(Outcome.NO_ENGINE, "");
        var run = RUNS.get(level.dimension());
        if (run == null) {
            run = new Run(level.dimension(), DEFAULT_RADIUS, centerX, centerZ);
            RUNS.put(level.dimension(), run);
            beginDisk(level, run);
            return new Result(Outcome.STARTED, run.where());
        }
        if (run.isRunning()) return new Result(Outcome.ALREADY_RUNNING, "");
        if (run.finished) run.restart();
        run.paused = false;
        if (!run.diskDone) beginDisk(level, run);
        return new Result(Outcome.STARTED, run.where());
    }

    public static Result pause(ServerLevel level) {
        var run = RUNS.get(level.dimension());
        if (run == null || !run.isRunning()) return new Result(Outcome.NOT_RUNNING, "");
        run.paused = true;
        run.endDisk(level);
        return new Result(Outcome.PAUSED, "");
    }

    public static Result stop(ServerLevel level) {
        var run = RUNS.remove(level.dimension());
        if (run == null) return new Result(Outcome.NOT_RUNNING, "");
        run.paused = true;
        run.endDisk(level);
        return new Result(Outcome.STOPPED, "");
    }

    public static void registerTicket() {
        if (ticket != null) return;
        ticket = Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath(Infinite.MOD_ID, "generate"),
                new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));
    }

    public static void clear() {
        RUNS.clear();
        FINISHED.clear();
        HELD.clear();
        generating = 0;
        LoadedChunks.clear();
    }

    static int generateParallel(int cores) {
        return Math.max(1, Math.min(cores / 2, cores - 2));
    }

    private static void beginDisk(ServerLevel level, Run run) {
        run.diskDone = false;
        var engine = Infinite.engine();
        var world = engine == null ? null : Infinite.world(level);
        if (engine == null || world == null) {
            run.diskDone = true;
            return;
        }
        var folder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
        if (!Files.isDirectory(folder)) {
            run.diskDone = true;
            return;
        }
        var chunks = LoadedChunks.of(level.dimension());
        try {

            // Name prefix is matched by the client progress bar.
            boolean started = engine.imports().start(world, () -> new RegionImport(
                    "Pregenerate " + LodWorldKey.dimensionName(level.dimension()).getString(),
                    RegionImport.folder(folder), world, level, run.centerX >> 4, run.centerZ >> 4,
                    engine.workers(), engine::saveQueueHasRoom)
                    .within(run::within)
                    .skipping(position -> chunks.contains(position) || run.hasStored(position))
                    .imported(run::stored)
                    .observer(run));
            run.diskRunning = started;
            run.diskDone = !started;
            if (!started) {
                Log.info("Pregenerate: " + level.dimension().identifier()
                        + " is already loading from disk; generating all chunks instead");
            }
        } catch (IOException | RuntimeException e) {
            Log.error("Pregenerate: could not read saved chunks of " + level.dimension().identifier(), e);
            run.diskDone = true;
        }
    }

    public static void tick(MinecraftServer server) {
        settle(server.getTickCount());
        if (RUNS.isEmpty()) return;
        var engine = Infinite.engine();
        if (engine == null || ticket == null) {
            RUNS.clear();
            return;
        }
        if (server.getAverageTickTimeNanos() > BUSY_TICK_NANOS || !engine.saveQueueHasRoom()
                || engine.ingestQueue().pending() > IngestQueue.DEFAULT_MAX_QUEUED / 2) return;
        int parallel = Math.min(MAX_GENERATING, generateParallel(CpuInfo.physicalCores()));
        int budget = parallel;
        long deadline = System.nanoTime() + WALK_NANOS;
        for (var entry : RUNS.entrySet()) {
            var run = entry.getValue();
            if (!run.isRunning() || !run.diskDone) continue;
            var level = server.getLevel(entry.getKey());
            if (level == null) {
                run.paused = true;
                continue;
            }
            budget -= walk(level, run, budget, parallel, deadline);
            if (budget <= 0 || generating >= parallel) break;
        }
    }

    private static int walk(ServerLevel level, Run run, int budget, int parallel, long deadline) {
        var loaded = LoadedChunks.of(level.dimension());
        var chunks = level.getChunkSource();
        int started = 0, passed = 0;
        while (started < budget && generating < parallel) {
            if (!run.disc.next(run.next)) {
                run.walked = true;
                run.finishIfDone(level);
                break;
            }
            run.advanced++;
            int chunkX = run.next[0], chunkZ = run.next[1];
            long packed = ChunkPos.pack(chunkX, chunkZ);
            if (run.hasStored(packed) || loaded.contains(packed)
                    || chunks.getChunkNow(chunkX, chunkZ) != null) {
                if (++passed >= MAX_PASSED_PER_TICK || (passed & 1023) == 0 && System.nanoTime() > deadline) break;
                continue;
            }
            generate(level, run, new ChunkPos(chunkX, chunkZ));
            started++;
        }
        return started;
    }

    private static void generate(ServerLevel level, Run run, ChunkPos pos) {
        generating++;
        run.inFlight++;

        // Record before requesting because chunk load may fire synchronously
        run.requested.add(pos.pack());
        try {
            level.getChunkSource().addTicketAndLoadWithRadius(ticket, pos, 0)
                    .whenComplete((ignored, error) -> FINISHED.add(new Generated(run, level, pos, error)));
        } catch (RuntimeException e) {
            FINISHED.add(new Generated(run, level, pos, e));
        }
    }

    private static void settle(int tick) {
        for (Generated done; (done = FINISHED.poll()) != null; ) {
            generating--;
            done.run.inFlight--;
            if (done.error != null) {
                if (done.run.failed++ == 0) {
                    Log.error("Could not generate chunk " + done.pos.x() + ", " + done.pos.z(), done.error);
                }
                release(done.level, done.pos);
            } else {
                done.run.generated++;
                if (ServerIngest.isQueued(done.level.dimension(), done.pos.pack())
                        || done.level.getChunkSource().getChunkNow(done.pos.x(), done.pos.z()) == null) {
                    release(done.level, done.pos);
                } else {
                    HELD.add(new Held(done.level, done.pos, tick + MAX_HOLD_TICKS));
                }
            }
            done.run.finishIfDone(done.level);
        }
        for (var it = HELD.iterator(); it.hasNext(); ) {
            var held = it.next();
            boolean read = ServerIngest.isQueued(held.level.dimension(), held.pos.pack());
            if (!read && held.until - tick > 0
                    && held.level.getChunkSource().getChunkNow(held.pos.x(), held.pos.z()) != null) {
                continue;
            }
            it.remove();
            release(held.level, held.pos);
        }
    }

    private static void release(ServerLevel level, ChunkPos pos) {
        if (ticket == null) return;
        try {
            level.getChunkSource().removeTicketWithRadius(ticket, pos, 0);
        } catch (RuntimeException e) {
            Log.warn("Could not release chunk " + pos.x() + ", " + pos.z() + ": " + e.getMessage());
        }
    }

    public static void describe(List<String> lines) {
        for (var run : RUNS.values()) lines.add(run.describe());
    }

    public static @Nullable Progress progress(ResourceKey<Level> dimension) {
        var run = RUNS.get(dimension);
        return run != null && run.diskDone && run.isRunning() ? run.progress() : null;
    }

    public static @Nullable Completed takeCompleted(ResourceKey<Level> dimension) {
        var run = RUNS.get(dimension);
        if (run == null || !run.notifyClient) return null;
        run.notifyClient = false;
        return new Completed(LodWorldKey.dimensionName(run.dimension), run.total,
                Math.max(0L, (System.nanoTime() - run.startNanos) / 1_000_000L));
    }

    public static boolean running() {
        for (var run : RUNS.values()) {
            if (run.isRunning()) return true;
        }
        return false;
    }

    public static boolean running(ResourceKey<Level> dimension) {
        var run = RUNS.get(dimension);
        return run != null && run.isRunning();
    }

    public static boolean finished(ResourceKey<Level> dimension) {
        var run = RUNS.get(dimension);
        return run != null && run.finished;
    }

    public static boolean tookRequested(ResourceKey<Level> dimension, long packed) {
        var run = RUNS.get(dimension);
        return run != null && run.requested.remove(packed);
    }

    private static final class Run implements LodImport.Listener {
        final ResourceKey<Level> dimension;
        final int radius, centerX, centerZ;
        final long total;
        final int[] next = new int[2];
        ChunkDisc disc;
        final DiscCoverage stored;
        long advanced, generated, failed;
        int inFlight;
        boolean walked, paused, finished, notifyClient;
        long startNanos = System.nanoTime();
        final LongOpenHashSet requested = new LongOpenHashSet();
        volatile boolean diskRunning, diskDone;
        volatile long diskProgress, diskExpected;

        Run(ResourceKey<Level> dimension, int radius, int centerX, int centerZ) {
            this.dimension = dimension;
            this.radius = radius;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.stored = new DiscCoverage(centerX >> 4, centerZ >> 4, radius);
            this.disc = disc();
            this.total = this.disc.count();
        }

        private ChunkDisc disc() {
            return new ChunkDisc(this.centerX >> 4, this.centerZ >> 4, this.radius);
        }

        void restart() {
            this.disc = disc();
            this.requested.clear();
            this.advanced = 0;
            this.generated = 0;
            this.failed = 0;
            this.walked = false;
            this.finished = false;
            this.notifyClient = false;
            this.startNanos = System.nanoTime();
        }

        void finishIfDone(ServerLevel level) {
            if (this.finished || !this.walked || this.inFlight > 0) return;
            this.finished = true;
            this.notifyClient = true;
            Log.info("Finished pregenerating " + this.advanced + " chunks in " + level.dimension().identifier()
                    + " (" + this.generated + " generated, " + storedCount() + " from disk"
                    + (this.failed > 0 ? ", " + this.failed + " failed" : "") + ")");
        }

        boolean isRunning() {
            return !this.paused && !this.finished;
        }

        boolean within(long packed) {
            return this.stored.within(packed);
        }

        void stored(long packed) {
            this.stored.add(packed);
        }

        boolean hasStored(long packed) {
            return this.stored.has(packed);
        }

        int storedCount() {
            return this.stored.count();
        }

        void endDisk(ServerLevel level) {
            if (!this.diskRunning) return;
            this.diskRunning = false;
            var engine = Infinite.engine();
            var world = engine == null ? null : Infinite.openWorld(level);
            if (engine == null || world == null) return;
            try {
                engine.imports().cancel(world);
            } catch (RuntimeException e) {
                Log.warn("Pregenerate: could not stop reading saved chunks cleanly: " + e.getMessage());
            }
        }

        String where() {
            return this.radius + " chunks at " + this.centerX + ", " + this.centerZ;
        }

        Progress progress() {
            return new Progress(LodWorldKey.dimensionName(this.dimension), this.advanced, this.total, this.generated);
        }

        String describe() {
            String name = LodWorldKey.dimensionName(this.dimension).getString();
            String state = this.running() ? "" : this.finished ? " (done)" : " (paused)";
            if (!this.diskDone) {
                long expected = Math.max(this.diskProgress, this.diskExpected);
                return String.format(Locale.ROOT, "Pregenerate %s: reading %,d / %,d chunks%s",
                        name, this.diskProgress, expected, state);
            }
            return String.format(Locale.ROOT, "Pregenerate %s: %,d / %,d chunks%s",
                    name, this.advanced, this.total, state);
        }

        private boolean running() {
            return this.diskRunning || (this.diskDone && isRunning());
        }

        @Override
        public void progress(long done, long expected) {
            this.diskProgress = done;
            this.diskExpected = expected;
        }

        @Override
        public void finished(LodImport.Result result) {
            this.diskRunning = false;
            this.diskDone = !result.cancelled();
            this.diskExpected = Math.max(this.diskExpected, this.diskProgress);
        }
    }
}