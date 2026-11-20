package rodozodo.infinite.storage;

import rodozodo.infinite.world.LodSection;
import rodozodo.infinite.world.PackedCells;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.LongConsumer;

public interface LodStorage extends MappingStore {
    int LOADED = 0, MISSING = 1, CORRUPT = -1;
    int NOT_PACKED = 2;

    int load(LodSection into);

    default int loadPacked(LodSection into, PackedCells packed) {
        return NOT_PACKED;
    }

    void save(LodSection section);

    long NO_STAMP = 0, UNKNOWN_STAMP = -1;

    default long stamp(long key) {
        return UNKNOWN_STAMP;
    }

    void forEachSection(int level, LongConsumer action);

    default @Nullable Path directory() {
        return null;
    }
}
