package rodozodo.infinite.client.lod.tree;

import rodozodo.infinite.util.NativeBuffer;
import it.unimi.dsi.fastutil.ints.IntCollection;


// Receiver for LOD tree GPU updates applied on the render thread.
// Methods are invoked in the order declared during each published update batch.

public interface GpuSink {
    void topNodeAdded(int node);

    void topNodeRemoved(int node);

    void pagesInUse(long[] pages);

    void reserveSlots(int nodeLimit, int sectionLimit);

    void uploadQuads(long firstQuad, NativeBuffer quads);

    void moveQuads(long fromQuad, long toQuad, int quads);

    // 16 byte writes packed as sync / node_scatter.comp expects
    void scatter(long records, long bytes, int count);

    void markSeen(IntCollection changes);

    void markFades(IntCollection changes);
}
