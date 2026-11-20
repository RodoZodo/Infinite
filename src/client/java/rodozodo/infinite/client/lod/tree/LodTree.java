package rodozodo.infinite.client.lod.tree;

import rodozodo.infinite.Log;
import rodozodo.infinite.client.lod.SectionWatch;
import rodozodo.infinite.client.lod.mesh.LodMesh;
import rodozodo.infinite.world.LodPos;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.lwjgl.system.MemoryUtil;

// Maintains the LoD octree on the tree worker thread handling node expansion, child updates, eviction folding, and serialization of changed nodes into the GPU node buffer

final class LodTree {
    static final int NONE = -1;
    static final int EMPTY = -2;
    private static final int NO_SPACE = -3;
    private static final int GPU_NONE = 0xFFFFFF, GPU_EMPTY = 0xFFFFFE, GPU_REQUESTED = 1 << 24;
    static final int MAX_DEPTH = 3;
    private static final int DEEP_PENDING_LIMIT = 512;

    private static final byte WATCHING_BLOCKS = 1;
    private static final byte MESH_PENDING = 2;
    private static final byte EXPANDING = 4;

    interface Listener {
        void topNodeAdded(int node);

        void topNodeRemoved(int node);

        void slotFilled(int node);

        void slotFreed(int node);

        // Not called when a node joins the tree with children already materialised
        default void childrenRevealed(int node) {}
    }

    private static final class Pending {
        final long position;
        final Expansion expansion;
        final int depth;
        Expansion children;
        boolean childrenComplete;
        int mesh = NONE;
        boolean meshReady;
        byte childMask;
        boolean childMaskKnown;

        Pending(long position, Expansion expansion) {
            this(position, expansion, 0);
        }

        Pending(long position, Expansion expansion, int depth) {
            this.position = position;
            this.expansion = expansion;
            this.depth = depth;
        }
    }

    private static final class Expansion {
        final long parent;
        final Pending[] children = new Pending[8];
        int waiting;

        Expansion(long parent) {
            this.parent = parent;
        }
    }

    private final NodeTable nodes;
    private final GeometryAllocator geometry;
    private final SectionWatch watch;
    private final Listener listener;
    private final Long2IntOpenHashMap ids = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<Pending> pending = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Expansion> expansions = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet topLevel = new LongOpenHashSet();
    private final IntOpenHashSet changed = new IntOpenHashSet();

    LodTree(int maxNodes, GeometryAllocator geometry, SectionWatch watch, Listener listener) {
        this.nodes = new NodeTable(maxNodes);
        this.geometry = geometry;
        this.watch = watch;
        this.listener = listener;
        this.ids.defaultReturnValue(-1);
    }

    void addTopLevel(long position) {
        if (!this.topLevel.add(position)) return;
        if (this.ids.containsKey(position) || this.pending.containsKey(position)) {
            Log.error("Top-level section " + LodPos.describe(position) + " already in tree");
            return;
        }
        this.pending.put(position, new Pending(position, null));
        this.watch.watch(position, SectionWatch.ALL);
    }

    void removeTopLevel(long position) {
        if (!this.topLevel.remove(position)) return;
        var waiting = this.pending.remove(position);
        if (waiting != null) {
            this.drop(waiting);
            return;
        }
        int node = this.ids.get(position);
        if (node == -1) return;
        this.listener.topNodeRemoved(node);
        this.release(node);
        this.freeSlots(node, 1);
    }

    boolean meshBuilt(LodMesh mesh) {
        long position = mesh.position;
        var waiting = this.pending.get(position);
        if (waiting != null) {
            int stored = this.store(mesh);
            if (stored == NO_SPACE) return false;
            this.dropMesh(waiting.mesh);
            waiting.mesh = stored;
            waiting.meshReady = true;
            if (!waiting.childMaskKnown) {
                waiting.childMask = mesh.childMask;
                waiting.childMaskKnown = true;
            }
            if (waiting.depth > 0 && waiting.children == null) this.expandDeeper(waiting);
            var expansion = waiting.expansion;
            if (expansion == null) {
                this.createTopNode(waiting);
            } else {
                expansion.waiting &= ~(1 << childIndex(position));
                if (expansion.waiting == 0) this.finishExpansion(expansion);
            }
            return true;
        }

        int node = this.ids.get(position);
        if (node == -1 || (this.nodes.flags[node] & WATCHING_BLOCKS) == 0) {
            mesh.free();
            return true;
        }
        byte childMask = mesh.childMask;
        int stored = this.store(mesh);
        if (stored == NO_SPACE) return false;
        this.dropMesh(this.nodes.mesh[node]);
        this.nodes.mesh[node] = stored;
        this.nodes.flags[node] &= ~MESH_PENDING;
        this.changed.add(node);
        if (childMask != this.nodes.childMask[node]) this.childrenChanged(position, childMask);
        return true;
    }

    void childrenChanged(long position, byte mask) {
        var waiting = this.pending.get(position);
        if (waiting != null) {
            boolean changed = waiting.childMaskKnown && waiting.childMask != mask;
            waiting.childMask = mask;
            waiting.childMaskKnown = true;
            if (changed && waiting.children != null) {
                this.cancelExpansion(position);
                waiting.children = null;
                waiting.childrenComplete = false;
                if (waiting.meshReady) this.expandDeeper(waiting);
            }
            return;
        }
        int node = this.ids.get(position);
        if (node == -1 || LodPos.level(position) == 0) return;
        int old = this.nodes.childMask[node] & 0xFF;
        int now = mask & 0xFF;
        this.nodes.childMask[node] = mask;
        var expansion = this.expansions.get(position);
        if (old == now || (this.nodes.firstChild[node] == NONE && expansion == null)) return;

        int removed = old & ~now;
        if (expansion != null) {
            for (int index = 0; index < 8; index++) {
                if ((removed >>> index & 1) != 0 && expansion.children[index] != null) {
                    this.cancelChild(expansion, index);
                    removed &= ~(1 << index);
                }
            }
        }
        if (removed != 0) {
            node = this.removeChildren(node, removed);
        }
        int added = now & ~old;
        if (added != 0) {
            if (expansion == null) expansion = this.startExpansion(node, position);
            for (int index = 0; index < 8; index++) {
                if ((added >>> index & 1) != 0) this.addChild(expansion, index, 0);
            }
        }
        if (expansion != null && expansion.waiting == 0) {
            this.finishExpansion(expansion);
        }
    }

    void requested(long position, int depth) {
        int node = this.ids.get(position);
        if (node == -1) return;

        // Force rewrite to clear the GPU-set requested bit
        this.changed.add(node);
        if (this.nodes.firstChild[node] == NONE && LodPos.level(position) != 0) {
            if (this.expansions.containsKey(position)) return;
            var expansion = this.startExpansion(node, position);
            int mask = this.nodes.childMask[node] & 0xFF;
            int below = this.depthBelow(depth);
            for (int index = 0; index < 8; index++) {
                if ((mask >>> index & 1) != 0) this.addChild(expansion, index, below);
            }
            if (expansion.waiting == 0) this.finishExpansion(expansion);
        } else if (this.nodes.mesh[node] == NONE) {
            this.wantMesh(node);
        }
    }

    private int depthBelow(int depth) {
        if (depth <= 1 || this.pending.size() >= DEEP_PENDING_LIMIT) return 0;
        return Math.min(depth, MAX_DEPTH) - 1;
    }

    void evict(long position) {
        int node = this.ids.get(position);
        if (node == -1 || this.topLevel.contains(position)) return;
        if (this.nodes.firstChild[node] != NONE) {
            this.dropNodeMesh(node);
            return;
        }
        long parentPosition = parent(position);
        int parent = this.ids.get(parentPosition);
        if (parent == -1) return;
        if (this.nodes.mesh[parent] == NONE) {
            this.wantMesh(parent);
            this.changed.add(parent);
        } else {
            this.collapse(parent);
        }
    }

    void dropUnused(long position) {
        int node = this.ids.get(position);
        if (node == -1 || this.topLevel.contains(position) || this.nodes.firstChild[node] < 0) return;
        this.dropNodeMesh(node);
    }

    interface LeafSink {
        void leaf(long position, int childMask);
    }

    void forEachExpandable(LeafSink sink) {
        for (var it = this.ids.long2IntEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            int node = entry.getIntValue();
            if (this.nodes.firstChild[node] != NONE || (this.nodes.flags[node] & (EXPANDING | MESH_PENDING)) != 0) continue;
            int mask = this.nodes.childMask[node] & 0xFF;
            if (mask == 0 || LodPos.level(entry.getLongKey()) == 0) continue;
            sink.leaf(entry.getLongKey(), mask);
        }
    }

    private int store(LodMesh mesh) {
        if (mesh.isEmpty()) {
            mesh.free();
            return EMPTY;
        }
        int id = this.geometry.add(mesh);
        return id < 0 ? NO_SPACE : id;
    }

    private void dropMesh(int mesh) {
        if (mesh >= 0) this.geometry.remove(mesh);
    }

    private void wantMesh(int node) {
        if ((this.nodes.flags[node] & WATCHING_BLOCKS) != 0) return;
        this.nodes.flags[node] |= WATCHING_BLOCKS | MESH_PENDING;
        this.watch.watch(this.nodes.position[node], SectionWatch.BLOCKS);
    }

    private void dropNodeMesh(int node) {
        if (this.nodes.mesh[node] == NONE) return;
        this.dropMesh(this.nodes.mesh[node]);
        this.nodes.mesh[node] = NONE;
        this.nodes.flags[node] &= ~(WATCHING_BLOCKS | MESH_PENDING);
        this.watch.unwatch(this.nodes.position[node], SectionWatch.BLOCKS);
        this.changed.add(node);
    }

    private void createTopNode(Pending waiting) {
        this.pending.remove(waiting.position);
        int node = this.nodes.allocate(1);
        this.fill(node, waiting);
        this.listener.topNodeAdded(node);
    }

    private void fill(int slot, Pending from) {
        this.pending.remove(from.position);
        this.nodes.position[slot] = from.position;
        this.nodes.mesh[slot] = from.mesh;
        this.nodes.firstChild[slot] = NONE;
        this.nodes.childCount[slot] = 0;
        this.nodes.childMask[slot] = from.childMask;
        this.nodes.flags[slot] = WATCHING_BLOCKS;
        this.ids.put(from.position, slot);
        this.listener.slotFilled(slot);
        this.changed.add(slot);
        if (from.childrenComplete) {
            this.materialiseChildren(slot, from.children);
        } else if (from.children != null) {
            this.nodes.flags[slot] |= EXPANDING;
        }
    }

    private void materialiseChildren(int slot, Expansion expansion) {
        this.expansions.remove(expansion.parent);
        int mask = 0;
        for (int index = 0; index < 8; index++) {
            if (expansion.children[index] != null) mask |= 1 << index;
        }
        int count = Integer.bitCount(mask);
        if (this.nodes.freeSlots() < this.subtreeSlots(expansion)) {
            for (var waiting : expansion.children) {
                if (waiting == null) continue;
                this.cancelExpansion(waiting.position);
                waiting.children = null;
                waiting.childrenComplete = false;
            }
        }
        if (this.nodes.freeSlots() < count) {
            this.cancelExpansion(expansion.parent);
            return;
        }
        int first = count == 0 ? EMPTY : this.nodes.allocate(count);
        int at = first;
        for (int index = 0; index < 8; index++) {
            var waiting = expansion.children[index];
            if (waiting == null) continue;
            this.fill(at++, waiting);
        }
        this.nodes.firstChild[slot] = first;
        this.nodes.childCount[slot] = (byte) count;
        this.changed.add(slot);
    }

    private int subtreeSlots(Expansion expansion) {
        int slots = 0;
        for (var waiting : expansion.children) {
            if (waiting == null) continue;
            slots++;
            if (waiting.childrenComplete) slots += this.subtreeSlots(waiting.children);
        }
        return slots;
    }

    private Expansion startExpansion(int node, long position) {
        var expansion = new Expansion(position);
        this.expansions.put(position, expansion);
        this.nodes.flags[node] |= EXPANDING;
        this.changed.add(node);
        return expansion;
    }

    private void expandDeeper(Pending waiting) {
        int mask = waiting.childMask & 0xFF;
        if (mask == 0 || LodPos.level(waiting.position) == 0 || this.pending.size() >= DEEP_PENDING_LIMIT) return;
        var nested = new Expansion(waiting.position);
        waiting.children = nested;
        this.expansions.put(waiting.position, nested);
        for (int index = 0; index < 8; index++) {
            if ((mask >>> index & 1) != 0) this.addChild(nested, index, waiting.depth - 1);
        }
        if (nested.waiting == 0) this.finishExpansion(nested);
    }

    private void addChild(Expansion expansion, int index, int depth) {
        long position = child(expansion.parent, index);
        if (this.ids.containsKey(position) || this.pending.containsKey(position)) {
            throw new IllegalStateException("LOD section " + LodPos.describe(position) + " is already in the tree");
        }
        var waiting = new Pending(position, expansion, depth);
        expansion.children[index] = waiting;
        expansion.waiting |= 1 << index;
        this.pending.put(position, waiting);
        this.watch.watch(position, SectionWatch.ALL);
    }

    private void cancelChild(Expansion expansion, int index) {
        var waiting = expansion.children[index];
        expansion.children[index] = null;
        expansion.waiting &= ~(1 << index);
        this.pending.remove(waiting.position);
        this.drop(waiting);
    }

    private void drop(Pending waiting) {
        this.cancelExpansion(waiting.position);
        waiting.children = null;
        waiting.childrenComplete = false;
        this.dropMesh(waiting.mesh);
        this.watch.unwatch(waiting.position, SectionWatch.ALL);
    }

    private void cancelExpansion(long position) {
        var expansion = this.expansions.remove(position);
        if (expansion == null) return;
        for (var waiting : expansion.children) {
            if (waiting == null) continue;
            this.pending.remove(waiting.position);
            this.drop(waiting);
        }
        int node = this.ids.get(position);
        if (node != -1) this.nodes.flags[node] &= ~EXPANDING;
    }

    private void finishExpansion(Expansion expansion) {
        int parent = this.ids.get(expansion.parent);
        if (parent == -1) {
            var waiting = this.pending.get(expansion.parent);
            if (waiting != null) waiting.childrenComplete = true;
            return;
        }
        this.expansions.remove(expansion.parent);
        this.nodes.flags[parent] &= ~EXPANDING;
        int arriving = 0;
        for (int index = 0; index < 8; index++) {
            if (expansion.children[index] != null) arriving |= 1 << index;
        }
        this.rebuildChildren(parent, this.liveChildren(parent) | arriving, expansion);
    }

    private int removeChildren(int parent, int mask) {
        int live = this.liveChildren(parent);
        mask &= live;
        if (mask == 0) return parent;
        int first = this.nodes.firstChild[parent];
        for (int i = 0; i < this.nodes.childCount[parent]; i++) {
            int child = first + i;
            if ((mask >>> childIndex(this.nodes.position[child]) & 1) != 0) this.release(child);
        }
        this.rebuildChildren(parent, live & ~mask, null);
        return parent;
    }

    private void rebuildChildren(int parent, int mask, Expansion arriving) {
        long parentPosition = this.nodes.position[parent];
        int oldFirst = this.nodes.firstChild[parent];
        int oldCount = oldFirst >= 0 ? this.nodes.childCount[parent] : 0;
        int count = Integer.bitCount(mask);
        int first = count == 0 ? EMPTY : this.nodes.allocate(count);
        int slot = first;
        for (int index = 0; index < 8; index++) {
            if ((mask >>> index & 1) == 0) continue;
            var waiting = arriving == null ? null : arriving.children[index];
            if (waiting != null) {
                this.pending.remove(waiting.position);
                this.fill(slot, waiting);
            } else {
                long position = child(parentPosition, index);
                int from = this.ids.get(position);
                this.nodes.copy(from, slot);
                this.ids.put(position, slot);
                this.listener.slotFilled(slot);
                this.changed.add(slot);
            }
            slot++;
        }
        if (oldCount != 0) this.freeSlots(oldFirst, oldCount);
        this.nodes.firstChild[parent] = first;
        this.nodes.childCount[parent] = (byte) count;
        this.changed.add(parent);
        if (oldFirst == NONE && count != 0) this.listener.childrenRevealed(parent);
    }

    private int liveChildren(int node) {
        int first = this.nodes.firstChild[node];
        if (first < 0) return 0;
        int mask = 0;
        for (int i = 0; i < this.nodes.childCount[node]; i++) {
            mask |= 1 << childIndex(this.nodes.position[first + i]);
        }
        return mask;
    }

    private void collapse(int node) {
        long position = this.nodes.position[node];
        this.cancelExpansion(position);
        int first = this.nodes.firstChild[node];
        if (first >= 0) {
            int count = this.nodes.childCount[node];
            for (int i = 0; i < count; i++) this.release(first + i);
            this.freeSlots(first, count);
        }
        this.nodes.firstChild[node] = NONE;
        this.nodes.childCount[node] = 0;
        this.changed.add(node);
    }

    private void release(int node) {
        long position = this.nodes.position[node];
        this.cancelExpansion(position);
        int first = this.nodes.firstChild[node];
        if (first >= 0) {
            int count = this.nodes.childCount[node];
            for (int i = 0; i < count; i++) this.release(first + i);
            this.freeSlots(first, count);
        }
        this.dropMesh(this.nodes.mesh[node]);
        this.watch.unwatch(position, SectionWatch.ALL);
        this.ids.remove(position);
    }

    private void freeSlots(int first, int count) {
        this.nodes.free(first, count);
        for (int slot = first; slot < first + count; slot++) {
            this.listener.slotFreed(slot);
            this.changed.add(slot);
        }
    }

    IntOpenHashSet changedNodes() {
        return this.changed;
    }

    void writeNode(int node, long address) {
        if (!this.nodes.isUsed(node)) {
            MemoryUtil.memSet(address, 0xFF, 16);
            return;
        }
        long position = this.nodes.position[node];
        int mesh = this.nodes.mesh[node];
        int first = this.nodes.firstChild[node];
        int meshField = mesh >= 0 ? mesh : mesh == EMPTY ? GPU_EMPTY : GPU_NONE;
        if ((this.nodes.flags[node] & (EXPANDING | MESH_PENDING)) != 0) meshField |= GPU_REQUESTED;
        if (first >= 0) meshField |= (this.nodes.childCount[node] - 1) << 26;
        MemoryUtil.memPutInt(address, (int) (position >> 32));
        MemoryUtil.memPutInt(address + 4, (int) position);
        MemoryUtil.memPutInt(address + 8, meshField);
        MemoryUtil.memPutInt(address + 12, first >= 0 ? first : first == EMPTY ? GPU_EMPTY : GPU_NONE);
    }

    int slotLimit() {
        return this.nodes.slotLimit();
    }

    String describe() {
        return this.nodes.count() + " nodes, " + this.pending.size() + " being meshed, " + this.expansions.size() + " expanding";
    }

    private static int childIndex(long position) {
        return LodPos.x(position) & 1 | (LodPos.z(position) & 1) << 1 | (LodPos.y(position) & 1) << 2;
    }

    static long child(long position, int index) {
        return LodPos.pack(LodPos.level(position) - 1,
                LodPos.x(position) << 1 | index & 1,
                LodPos.y(position) << 1 | index >> 2 & 1,
                LodPos.z(position) << 1 | index >> 1 & 1);
    }

    private static long parent(long position) {
        return LodPos.pack(LodPos.level(position) + 1,
                LodPos.x(position) >> 1, LodPos.y(position) >> 1, LodPos.z(position) >> 1);
    }

    void verify() {
        var seenPositions = new LongOpenHashSet();
        int[] seenNodes = {0};
        for (long position : this.topLevel) {
            int node = this.ids.get(position);
            if (node == -1) {
                check(this.pending.containsKey(position) && this.pending.get(position).expansion == null, "top level neither node nor pending", position);
                check(this.watch.get(position) == SectionWatch.ALL, "pending top level not watched", position);
                continue;
            }
            this.verifyNode(node, position, seenPositions, seenNodes);
        }
        check(seenPositions.size() == this.ids.size(), "nodes not reachable from the top", 0);
        check(seenNodes[0] == this.nodes.count(), "slots in use not reachable from the top", 0);
        long pendingChildren = this.pending.values().stream().filter(p -> p.expansion != null).count();
        long expansionChildren = this.expansions.values().stream()
                .mapToLong(e -> java.util.Arrays.stream(e.children).filter(java.util.Objects::nonNull).count()).sum();
        check(pendingChildren == expansionChildren, "pending children do not match expansions", 0);
        for (var waiting : this.pending.values()) {
            this.verifyPending(waiting);
        }
    }

    private void verifyPending(Pending waiting) {
        var nested = waiting.children;
        if (nested == null) {
            check(!waiting.childrenComplete, "children complete without an expansion", waiting.position);
            return;
        }
        check(this.expansions.get(waiting.position) == nested, "nested expansion not tracked", waiting.position);
        check(waiting.childrenComplete == (nested.waiting == 0), "nested children readiness mismatch", waiting.position);
        for (int index = 0; index < 8; index++) {
            var nestedChild = nested.children[index];
            if (nestedChild == null) continue;
            check(nestedChild.position == child(waiting.position, index), "nested child mismatch", waiting.position);
            check(this.pending.get(nestedChild.position) == nestedChild, "nested child not pending", nestedChild.position);
            check(this.watch.get(nestedChild.position) == SectionWatch.ALL, "nested child not watched", nestedChild.position);
            this.verifyPending(nestedChild);
        }
    }

    private void verifyNode(int node, long position, LongOpenHashSet seenPositions, int[] seenNodes) {
        check(this.nodes.isUsed(node), "node slot not in use", position);
        check(this.nodes.position[node] == position, "node position mismatch", position);
        check(this.ids.get(position) == node, "id map mismatch", position);
        check(seenPositions.add(position), "node reached twice", position);
        seenNodes[0]++;
        int flags = this.nodes.flags[node];
        int mesh = this.nodes.mesh[node];
        boolean watchingBlocks = (flags & WATCHING_BLOCKS) != 0;
        check((this.watch.get(position) & SectionWatch.CHILDREN) != 0, "node not watched for children", position);
        check(((this.watch.get(position) & SectionWatch.BLOCKS) != 0) == watchingBlocks, "block watch mismatch", position);
        check(mesh == NONE || watchingBlocks, "mesh without block watch", position);
        check(((flags & MESH_PENDING) != 0) == (watchingBlocks && mesh == NONE), "pending mesh flag mismatch", position);
        var expansion = this.expansions.get(position);
        check(((flags & EXPANDING) != 0) == (expansion != null), "expanding flag mismatch", position);
        int first = this.nodes.firstChild[node];
        if (first == NONE) {
            check(mesh != NONE, "leaf without a mesh", position);
        }
        int live = this.liveChildren(node);
        int pendingMask = 0;
        if (expansion != null) {
            check(LodPos.level(position) != 0, "level 0 expanding", position);
            for (int index = 0; index < 8; index++) {
                var waiting = expansion.children[index];
                if (waiting == null) continue;
                pendingMask |= 1 << index;
                check(this.pending.get(waiting.position) == waiting && waiting.position == child(position, index), "pending child mismatch", position);
                check(this.watch.get(waiting.position) == SectionWatch.ALL, "pending child not watched", waiting.position);
                check(waiting.meshReady == ((expansion.waiting >>> index & 1) == 0), "pending child readiness mismatch", waiting.position);
            }
            check(expansion.waiting != 0, "finished expansion left open", position);
        }
        if (first != NONE || expansion != null) {
            check((live & pendingMask) == 0, "child both live and pending", position);
            check((live | pendingMask) == (this.nodes.childMask[node] & 0xFF), "children do not match the world", position);
        }
        if (first >= 0) {
            int count = this.nodes.childCount[node];
            check(count >= 1 && count <= 8 && count == Integer.bitCount(live), "bad child count", position);
            int previous = -1;
            for (int i = 0; i < count; i++) {
                long childPosition = this.nodes.position[first + i];
                check(parent(childPosition) == position, "child of another parent", childPosition);
                check(childIndex(childPosition) > previous, "children out of order", childPosition);
                previous = childIndex(childPosition);
                this.verifyNode(first + i, childPosition, seenPositions, seenNodes);
            }
        }
    }

    private static void check(boolean condition, String what, long position) {
        if (!condition) throw new IllegalStateException("LOD tree check failed: " + what + " at " + LodPos.describe(position));
    }
}
