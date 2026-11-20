package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.StagingBuffer;
import rodozodo.infinite.client.core.vk.VBuffer;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.lwjgl.system.MemoryUtil;

final class TopNodeList {
    private final VBuffer gpu;
    private final StagingBuffer staging;
    private final int capacity;
    private final IntArrayList ids = new IntArrayList();
    private final Int2IntOpenHashMap slotOf = new Int2IntOpenHashMap();

    TopNodeList(VBuffer gpu, StagingBuffer staging) {
        this.gpu = gpu;
        this.staging = staging;
        this.capacity = (int) (gpu.size / 4);
        this.slotOf.defaultReturnValue(-1);
    }

    void add(int id) {
        if (this.ids.size() == this.capacity) {
            throw new IllegalStateException("More than " + this.capacity + " top level LOD nodes");
        }
        if (this.slotOf.putIfAbsent(id, this.ids.size()) != -1) {
            throw new IllegalStateException("Top level node " + id + " added twice");
        }
        this.ids.add(id);
        this.write(this.ids.size() - 1, id);
    }

    void remove(int id) {
        int slot = this.slotOf.remove(id);
        if (slot == -1) throw new IllegalStateException("Top level node " + id + " is not in the list");
        int last = this.ids.removeInt(this.ids.size() - 1);
        if (slot != this.ids.size()) {
            this.ids.set(slot, last);
            this.slotOf.put(last, slot);
            this.write(slot, last);
        }
    }

    private void write(int slot, int id) {
        MemoryUtil.memPutInt(this.staging.write(this.gpu, slot * 4L, 4), id);
    }

    int size() {
        return this.ids.size();
    }

    VBuffer buffer() {
        return this.gpu;
    }
}
