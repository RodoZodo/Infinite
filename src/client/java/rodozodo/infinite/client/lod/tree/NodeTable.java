package rodozodo.infinite.client.lod.tree;

import rodozodo.infinite.util.IdBitSet;

import java.util.Arrays;

// Stores LOD tree node fields in parallel arrays indexed by node slot, matching the GPU node buffer.
// Sibling nodes are allocated in contiguous runs.

final class NodeTable {
    private static final int GROWTH = 1 << 16;

    private final IdBitSet used;
    private final int capacity;
    long[] position = new long[0];
    int[] mesh = new int[0];
    int[] firstChild = new int[0];
    byte[] childCount = new byte[0];
    byte[] childMask = new byte[0];
    byte[] flags = new byte[0];

    NodeTable(int capacity) {
        this.capacity = capacity;
        this.used = new IdBitSet(capacity);
        this.grow(Math.min(GROWTH, capacity));
    }

    int allocate(int count) {
        int first = this.used.allocateRun(count);
        if (first < 0) {
            throw new IllegalStateException("Out of LOD node slots (" + this.capacity + ")");
        }
        if (first + count > this.position.length) {
            this.grow(Math.min(this.capacity, Math.max(first + count, this.position.length + GROWTH)));
        }
        return first;
    }

    void free(int first, int count) {
        for (int id = first; id < first + count; id++) {
            if (!this.used.free(id)) throw new IllegalStateException("Node slot " + id + " was not in use");
        }
    }

    boolean isUsed(int id) {
        return this.used.isUsed(id);
    }

    void copy(int from, int to) {
        this.position[to] = this.position[from];
        this.mesh[to] = this.mesh[from];
        this.firstChild[to] = this.firstChild[from];
        this.childCount[to] = this.childCount[from];
        this.childMask[to] = this.childMask[from];
        this.flags[to] = this.flags[from];
    }

    int slotLimit() {
        return this.used.highest() + 1;
    }

    int count() {
        return this.used.count();
    }

    int freeSlots() {
        return this.capacity - this.used.count();
    }

    private void grow(int size) {
        this.position = Arrays.copyOf(this.position, size);
        this.mesh = Arrays.copyOf(this.mesh, size);
        this.firstChild = Arrays.copyOf(this.firstChild, size);
        this.childCount = Arrays.copyOf(this.childCount, size);
        this.childMask = Arrays.copyOf(this.childMask, size);
        this.flags = Arrays.copyOf(this.flags, size);
    }
}
