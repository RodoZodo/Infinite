package rodozodo.infinite.storage;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.nio.ByteBuffer;

public interface MappingStore {
    void writeMapping(int id, ByteBuffer data);

    Int2ObjectOpenHashMap<byte[]> readMappings();

    void flush();

    void close();
}
