package rodozodo.infinite.client.lod.tree;

import rodozodo.infinite.util.NativeBuffer;
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.ConcurrentLinkedQueue;


// Coalesces pending LOD tree GPU updates published by the tree worker before the render thread consumes them.
// Repeated writes to the same node, metadata half, or quad range overwrite previous entries in the batch.

final class PendingGpuWork {

    // 4 destinations + 4x 16-byte values per scatter record (sync / node_scatter.comp)
    static final int RECORD_BYTES = 80;
    static final int METADATA_DESTINATION = 0x80000000;
    private static final long UPLOAD_LIMIT_BYTES = Long.getLong("infinite.batchUploadMiB", 16) << 20;

    final Long2ObjectOpenHashMap<NativeBuffer> uploads = new Long2ObjectOpenHashMap<>();
    long uploadBytes;

    // Key: destination firstQuad; value: (source firstQuad << 32) | quadCount. Applied after uploads
    final Long2LongOpenHashMap moves = new Long2LongOpenHashMap();
    long[] usedPages;
    int nodeLimit, sectionLimit;

    private NativeBuffer records = new NativeBuffer(RECORD_BYTES * 256L);
    private final Int2IntOpenHashMap writeIndex = new Int2IntOpenHashMap();
    final Int2BooleanOpenHashMap lastSeen = new Int2BooleanOpenHashMap();
    final Int2BooleanOpenHashMap fades = new Int2BooleanOpenHashMap();
    final IntArrayList topNodes = new IntArrayList();

    PendingGpuWork() {
        this.writeIndex.defaultReturnValue(-1);
    }

    void addUpload(long firstQuad, NativeBuffer quads) {
        var replaced = this.uploads.put(firstQuad, quads);
        if (replaced != null) {
            this.uploadBytes -= replaced.size;
            replaced.free();
        }
        this.uploadBytes += quads.size;
    }

    void addMove(long toQuad, long fromQuad, int quads) {
        this.moves.put(toQuad, fromQuad << 32 | quads);
    }

    void dropUpload(long firstQuad) {
        this.moves.remove(firstQuad);
        var dropped = this.uploads.remove(firstQuad);
        if (dropped != null) {
            this.uploadBytes -= dropped.size;
            dropped.free();
        }
    }

    long nodeWrite(int node) {
        return this.write(node);
    }

    long metadataWrite(int id, int half) {
        return this.write((id * 3 + half) | METADATA_DESTINATION);
    }

    private long write(int destination) {
        int index = this.writeIndex.get(destination);
        if (index == -1) {
            index = this.writeIndex.size();
            long needed = (index / 4 + 1L) * RECORD_BYTES;
            if (needed > this.records.size) {
                var bigger = new NativeBuffer(Math.max(needed, this.records.size * 2));
                this.records.copyTo(bigger.address);
                this.records.free();
                this.records = bigger;
            }
            this.writeIndex.put(destination, index);
            MemoryUtil.memPutInt(this.recordAddress(index) + (index & 3) * 4L, destination);
        }
        return this.recordAddress(index) + 16 + (index & 3) * 16L;
    }

    private long recordAddress(int index) {
        return this.records.address + (index >> 2) * (long) RECORD_BYTES;
    }

    int writeCount() {
        return this.writeIndex.size();
    }

    long records() {
        return this.records.address;
    }

    long recordBytes() {
        return (this.writeCount() + 3L) / 4 * RECORD_BYTES;
    }

    boolean isEmpty() {
        return this.uploads.isEmpty() && this.moves.isEmpty() && this.usedPages == null && this.writeIndex.isEmpty() && this.lastSeen.isEmpty() && this.fades.isEmpty() && this.topNodes.isEmpty();
    }

    boolean isFull() {
        return this.uploadBytes > UPLOAD_LIMIT_BYTES || this.writeIndex.size() > 8192 || this.lastSeen.size() > 8192 || this.topNodes.size() > 4096;
    }

    // Retire buffers to the tree thread because uploadQuads already copied them into staging
    void clear(ConcurrentLinkedQueue<NativeBuffer> retired) {
        retired.addAll(this.uploads.values());
        this.uploads.clear();
        this.uploadBytes = 0;
        this.moves.clear();
        this.usedPages = null;
        this.nodeLimit = 0;
        this.sectionLimit = 0;
        this.writeIndex.clear();
        this.lastSeen.clear();
        this.fades.clear();
        this.topNodes.clear();
    }

    void free() {
        this.uploads.values().forEach(NativeBuffer::free);
        this.clear(new ConcurrentLinkedQueue<>());
        this.records.free();
    }
}
