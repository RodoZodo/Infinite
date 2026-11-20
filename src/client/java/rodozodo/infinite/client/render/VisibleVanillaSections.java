package rodozodo.infinite.client.render;

import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
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