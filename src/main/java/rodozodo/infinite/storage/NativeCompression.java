package rodozodo.infinite.storage;

import java.nio.ByteBuffer;

import static org.lwjgl.util.lz4.LZ4.LZ4_compress_default;
import static org.lwjgl.util.lz4.LZ4.LZ4_decompress_safe;
import static org.lwjgl.util.zstd.Zstd.*;

final class NativeCompression {
    private final long zstdCompressor;
    private final long zstdDecompressor;

    NativeCompression(int compressionLevel) {
        this.zstdCompressor = ZSTD_createCCtx();
        this.zstdDecompressor = ZSTD_createDCtx();
        check(ZSTD_CCtx_setParameter(this.zstdCompressor, ZSTD_c_compressionLevel, compressionLevel));
        // Omit zstd checksum and content size; LogLodStorage stores CRC32C and bounded section size.
        check(ZSTD_CCtx_setParameter(this.zstdCompressor, ZSTD_c_checksumFlag, 0));
        check(ZSTD_CCtx_setParameter(this.zstdCompressor, ZSTD_c_contentSizeFlag, 0));
    }

    private static long check(long zstdResult) {
        if (ZSTD_isError(zstdResult)) throw new IllegalStateException("ZSTD: " + ZSTD_getErrorName(zstdResult));
        return zstdResult;
    }

    int compress(int codec, ByteBuffer packed, ByteBuffer raw, int size, int offset) {
        raw.clear().limit(size);
        packed.clear().position(offset);
        try {
            if (codec == SectionCompression.LZ4) {
                int length = LZ4_compress_default(raw, packed);
                if (length <= 0) throw new IllegalStateException("LZ4 could not compress a " + size + " byte section");
                return length;
            }
            return (int) check(ZSTD_compress2(this.zstdCompressor, packed, raw));
        } finally {
            raw.clear();
            packed.clear();
        }
    }

    int decompress(int codec, ByteBuffer packed, ByteBuffer raw, int offset, int length) {
        raw.clear();
        packed.clear().position(offset).limit(offset + length);
        try {
            if (codec == SectionCompression.LZ4) {
                int size = LZ4_decompress_safe(packed, raw);
                return size >= 0 ? size : -1;
            }
            long size = ZSTD_decompressDCtx(this.zstdDecompressor, raw, packed);
            return ZSTD_isError(size) ? -1 : (int) size;
        } finally {
            raw.clear();
            packed.clear();
        }
    }

    void free() {
        ZSTD_freeCCtx(this.zstdCompressor);
        ZSTD_freeDCtx(this.zstdDecompressor);
    }
}
