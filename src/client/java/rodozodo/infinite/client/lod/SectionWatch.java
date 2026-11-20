package rodozodo.infinite.client.lod;

import rodozodo.infinite.world.LodPos;
import rodozodo.infinite.world.LodSection;
import rodozodo.infinite.world.LodWorld;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import java.util.function.Consumer;
import java.util.function.LongConsumer;


// Tracks which LoD sections the tree is watching for block or child-mask changes and routes world updates to mesh rebuilds and child updates

public final class SectionWatch {
    public static final int BLOCKS = LodWorld.BLOCKS_CHANGED;
    public static final int CHILDREN = LodWorld.CHILDREN_CHANGED;
    public static final int ALL = BLOCKS | CHILDREN;
    private static final int STRIPES = 32;

    private final Long2ByteOpenHashMap[] stripes = new Long2ByteOpenHashMap[STRIPES];
    private final LongConsumer rebuildMesh;
    private final Consumer<LodSection> childrenChanged;

    public SectionWatch(LongConsumer rebuildMesh, Consumer<LodSection> childrenChanged) {
        this.rebuildMesh = rebuildMesh;
        this.childrenChanged = childrenChanged;
        for (int i = 0; i < STRIPES; i++) {
            this.stripes[i] = new Long2ByteOpenHashMap();
        }
    }

    private Long2ByteOpenHashMap stripe(long position) {
        long mixed = position * 0x9E3779B97F4A7C15L;
        return this.stripes[(int) (mixed >>> 59)];
    }

    public int watch(long position, int kinds) {
        var map = this.stripe(position);
        int added;
        synchronized (map) {
            int current = map.get(position);
            added = kinds & ~current;
            if (added != 0) map.put(position, (byte) (current | kinds));
        }
        if ((added & BLOCKS) != 0) {
            this.rebuildMesh.accept(position);
        }
        return added;
    }

    public int unwatch(long position, int kinds) {
        var map = this.stripe(position);
        synchronized (map) {
            int current = map.get(position);
            int removed = current & kinds;
            if (removed != 0) {
                int left = current & ~kinds;
                if (left == 0) map.remove(position);
                else map.put(position, (byte) left);
            }
            return removed;
        }
    }

    public int get(long position) {
        var map = this.stripe(position);
        synchronized (map) {
            return map.get(position);
        }
    }

    public void sectionUpdated(LodSection section, int kinds, int neighbours) {
        int followed = this.get(section.key) & kinds;
        if ((followed & CHILDREN) != 0) {
            section.acquire();
            this.childrenChanged.accept(section);
        }
        if ((followed & BLOCKS) != 0) {
            this.rebuildMesh.accept(section.key);
        }
        for (int side = 0; side < 6; side++) {
            if ((neighbours >>> side & 1) == 0) continue;
            int offset = (side & 1) * 2 - 1;
            long neighbour = LodPos.pack(section.level,
                    section.x + (side >> 1 == 1 ? offset : 0),
                    section.y + (side >> 1 == 0 ? offset : 0),
                    section.z + (side >> 1 == 2 ? offset : 0));
            if ((this.get(neighbour) & BLOCKS) != 0) {
                this.rebuildMesh.accept(neighbour);
            }
        }
    }
}
