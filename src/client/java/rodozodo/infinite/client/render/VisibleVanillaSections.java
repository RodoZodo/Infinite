package rodozodo.infinite.client.render;

import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public final class VisibleVanillaSections {
    private long[] positions = new long[4096];
    private int count;
    private boolean changed;

    public void clear() {
        this.count = 0;
        this.changed = true;
    }

    private static @Nullable CameraTransform listCamera;
    private static float listDistance;

    public static void listCamera(@Nullable CameraTransform camera) {
        listCamera = camera;
        listDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }

    // Same test as mask/vanilla_mask.vert. Sodium's cull tree is rebuilt asynchronously, so for a frame after the camera
    // moves it still holds sections that are only in range of the previous position. Sodium would draw them while the
    // mask leaves them out, so they are dropped from the render list instead.
    public static boolean inRenderDistance(int x, int y, int z) {
        var camera = listCamera;
        if (camera == null) return true;
        float nx = Math.clamp(0, (x << 4) - camera.intX - 1, (x << 4) - camera.intX + 17) - camera.fracX;
        float ny = Math.clamp(0, (y << 4) - camera.intY - 1, (y << 4) - camera.intY + 17) - camera.fracY;
        float nz = Math.clamp(0, (z << 4) - camera.intZ - 1, (z << 4) - camera.intZ + 17) - camera.fracZ;
        return nx * nx + nz * nz < listDistance * listDistance && Math.abs(ny) < listDistance;
    }

    public static void visit(@Nullable RenderRegion region, int x, int y, int z) {
        if (region == null) return;
        var sections = LodRendererHolder.visibleSections();
        if (sections != null && masks(region.getSectionFlags(LocalSectionIndex.pack(x, y, z)))) {
            sections.add(SectionPos.asLong(x, y, z));
        }
    }

    static boolean masks(int sectionFlags) {
        int needed = RenderSectionFlags.MASK_IS_BUILT | RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY;
        return (sectionFlags & needed) == needed;
    }

    public void add(long sectionPos) {
        if (this.count == this.positions.length) {
            this.positions = Arrays.copyOf(this.positions, this.count * 2);
        }
        this.positions[this.count++] = sectionPos;
    }

    public int count() {
        return this.count;
    }

    long[] positions() {
        return this.positions;
    }

    boolean takeChanged() {
        boolean was = this.changed;
        this.changed = false;
        return was;
    }
}
