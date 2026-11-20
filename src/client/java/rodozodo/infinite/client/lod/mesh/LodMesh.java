package rodozodo.infinite.client.lod.mesh;

import rodozodo.infinite.util.NativeBuffer;
import org.jetbrains.annotations.Nullable;


// Packed quad buffer and metadata for one meshed LOD section before upload to the GPU geometry store
// Quads are grouped into translucent, double-sided, and the six face directions, followed by any per-cell AO/light tables

public final class LodMesh {
    public static final int GROUPS = 8;
    public static final int GROUP_TRANSLUCENT = 0;
    public static final int GROUP_DOUBLE_SIDED = 1;
    public static final int GROUP_FIRST_FACE = 2;

    // Bits 30-31 of bounds (shared with common/section.glsl)
    public static final int ANIMATED = 1 << 30;
    public static final int MAY_DISCARD = 1 << 31;

    public final long position;
    public final byte childMask;

    // Bitmask per group indicating alpha cutout discard (common/section.glsl, draws/generate.comp)
    public final int discardGroups;

    // Packed bounds: min x/y/z (5 bits each), size-1 x/y/z (5 bits each), plus ANIMATED/MAY_DISCARD.
    public final int bounds;

    // Quad-slot offset of per-cell AO / light tables after the quad groups, or 0 if none
    public final int aoTables;
    private final @Nullable NativeBuffer quads;
    private final int[] groupSizes;

    LodMesh(long position, byte childMask, int bounds, int discardGroups, @Nullable NativeBuffer quads, int[] groupSizes) {
        this(position, childMask, bounds, discardGroups, quads, groupSizes, 0);
    }

    LodMesh(long position, byte childMask, int bounds, int discardGroups, @Nullable NativeBuffer quads, int[] groupSizes,
            int aoTables) {
        this.position = position;
        this.childMask = childMask;
        this.bounds = bounds;
        this.discardGroups = discardGroups;
        this.quads = quads;
        this.groupSizes = groupSizes;
        this.aoTables = aoTables;
    }

    public static LodMesh empty(long position, byte childMask) {
        return new LodMesh(position, childMask, 0, 0, null, new int[GROUPS]);
    }

    public boolean isEmpty() {
        return this.quads == null;
    }

    public NativeBuffer quads() {
        if (this.quads == null) throw new IllegalStateException("Empty mesh has no quads");
        return this.quads;
    }

    public int groupSize(int group) {
        return this.groupSizes[group];
    }

    public int quadCount() {
        return this.quads == null ? 0 : (int) (this.quads.size >> 3);
    }

    public void free() {
        if (this.quads != null) this.quads.free();
    }
}
