package rodozodo.infinite.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class CrystalMarks {
    static final class Mark {
        double x, y, z;
        boolean showsBottom;
        int time;
        long seenNanos;
        int blockLight, skyLight;
        int missing;
        @Nullable EndCrystal entity;

        float age(long now) {
            if (this.entity != null && !this.entity.isRemoved()) {
                return this.entity.time + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            }
            return this.time + (now - this.seenNanos) / NANOS_PER_TICK;
        }
    }

    private static final float NANOS_PER_TICK = 50_000_000f;
    private static final long SCAN_NANOS = 500_000_000L;
    private static final int MISSING_SCANS = 3;
    private static final double TRACKED_MARGIN = 24;

    private static final Map<ResourceKey<Level>, Map<UUID, Mark>> BY_LEVEL = new HashMap<>();
    private static final Set<UUID> SEEN = new HashSet<>();
    private static @Nullable Object connection;
    private static @Nullable ClientLevel scanned;
    private static long lastScan;

    private CrystalMarks() {}

    static Map<UUID, Mark> of(ClientLevel level, long now) {
        var minecraft = Minecraft.getInstance();
        var current = minecraft.getConnection();
        if (current != connection) {
            BY_LEVEL.clear();
            connection = current;
            scanned = null;
        }
        var marks = BY_LEVEL.computeIfAbsent(level.dimension(), key -> new HashMap<>());
        if (scanned == level && now - lastScan < SCAN_NANOS) return marks;
        scanned = level;
        lastScan = now;

        SEEN.clear();
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal) || crystal.isRemoved()) continue;
            var mark = marks.computeIfAbsent(crystal.getUUID(), key -> new Mark());
            mark.x = crystal.getX();
            mark.y = crystal.getY();
            mark.z = crystal.getZ();
            mark.showsBottom = crystal.showsBottom();
            mark.time = crystal.time;
            mark.seenNanos = now;
            var at = crystal.blockPosition();
            mark.blockLight = level.getBrightness(LightLayer.BLOCK, at);
            mark.skyLight = level.getBrightness(LightLayer.SKY, at);
            mark.missing = 0;
            mark.entity = crystal;
            SEEN.add(crystal.getUUID());
        }

        var player = minecraft.player;
        int trackedChunks = Math.min(EntityTypes.END_CRYSTAL.clientTrackingRange(), minecraft.options.getEffectiveRenderDistance());
        double tracked = Math.max(trackedChunks * 16 - TRACKED_MARGIN, 0);
        var entries = marks.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (SEEN.contains(entry.getKey())) continue;
            var mark = entry.getValue();
            mark.entity = null;
            if (player == null) continue;
            double dx = mark.x - player.getX(), dz = mark.z - player.getZ();
            boolean shouldBeTracked = dx * dx + dz * dz < tracked * tracked
                    && level.hasChunk((int) Math.floor(mark.x) >> 4, (int) Math.floor(mark.z) >> 4);
            if (!shouldBeTracked) {
                mark.missing = 0;
            } else if (++mark.missing >= MISSING_SCANS) {
                entries.remove();
            }
        }
        return marks;
    }
}
