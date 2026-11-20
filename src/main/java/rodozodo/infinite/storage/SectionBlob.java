package rodozodo.infinite.storage;

import rodozodo.infinite.world.LodSection;
import rodozodo.infinite.world.PackedCells;
import org.jetbrains.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

final class SectionBlob {
    static final int HEADROOM = 32;
    static final int CAPACITY = SectionCodec.MAX_BYTES + SectionCodec.MAX_BYTES / 8 + 1024;

    private final ByteBuffer packed = ByteBuffer.allocateDirect(HEADROOM + CAPACITY);
    private final MemorySegment packedSegment = MemorySegment.ofBuffer(this.packed);
    private final ByteBuffer raw = ByteBuffer.allocateDirect(SectionCodec.MAX_BYTES);
    private final SectionCodec codec = new SectionCodec();
    private final int writeCodec;
    private final NativeCompression nativeCodecs;

    SectionBlob(int compressionLevel, int writeCodec) {
        this.writeCodec = writeCodec;
        this.nativeCodecs = new NativeCompression(compressionLevel);
    }

    ByteBuffer buffer() {
        return this.packed;
    }

    MemorySegment bufferSegment() {
        return this.packedSegment;
    }

    int writeCodec() {
        return this.writeCodec;
    }

    boolean canRead(int codec) {
        return SectionCompression.available(codec);
    }

    int pack(LodSection section, int offset) {
        int size = this.codec.encode(section, this.raw);
        return this.nativeCodecs.compress(this.writeCodec, this.packed, this.raw, size, offset);
    }

    private static final boolean TIMING = Boolean.getBoolean("infinite.cacheTiming");
    private static final LongAdder UNPACKS = new LongAdder(), DECOMPRESS_NANOS = new LongAdder(), DECODE_NANOS = new LongAdder();

    static @Nullable String describeTiming() {
        long unpacks = UNPACKS.sum();
        if (!TIMING || unpacks == 0) return null;
        return String.format(java.util.Locale.ROOT, "unpack us/section: decompress %.1f, decode %.1f (%d)",
                DECOMPRESS_NANOS.sum() / (double) unpacks / 1e3, DECODE_NANOS.sum() / (double) unpacks / 1e3, unpacks);
    }

    boolean unpack(LodSection section, @Nullable PackedCells packed, int offset, int length, int codec) {
        if (length <= 0 || length > CAPACITY || offset < 0 || offset + length > this.packed.capacity()) return false;
        if (!this.canRead(codec)) return false;
        long started = TIMING ? System.nanoTime() : 0;
        int size = this.nativeCodecs.decompress(codec, this.packed, this.raw, offset, length);
        long decompressed = TIMING ? System.nanoTime() : 0;
        boolean decoded = size > 0 && (packed == null ? this.codec.decode(section, this.raw, size)
                : this.codec.decodePacked(section, packed, this.raw, size));
        if (TIMING) {
            UNPACKS.increment();
            DECOMPRESS_NANOS.add(decompressed - started);
            DECODE_NANOS.add(System.nanoTime() - decompressed);
        }
        return decoded;
    }

    int packBytes(ByteBuffer from, int size, int offset) {
        if (size > SectionCodec.MAX_BYTES) throw new IllegalArgumentException(size + " bytes is more than a frame holds");
        return this.nativeCodecs.compress(this.writeCodec, this.packed, from, size, offset);
    }

    boolean unpackBytes(ByteBuffer into, int offset, int length, int codec) {
        if (length <= 0 || length > CAPACITY || offset < 0 || offset + length > this.packed.capacity()) return false;
        if (!this.canRead(codec)) return false;
        int size = this.nativeCodecs.decompress(codec, this.packed, into, offset, length);
        if (size <= 0) return false;
        into.clear().limit(size);
        return true;
    }

    private void free() {
        this.nativeCodecs.free();
    }

    static final class Pool {
        private final ConcurrentLinkedQueue<SectionBlob> free = new ConcurrentLinkedQueue<>();
        private final int compressionLevel;
        private final int codec;
        private volatile boolean closed;

        Pool(int compressionLevel, int codec) {
            this.compressionLevel = compressionLevel;
            this.codec = codec;
        }

        SectionBlob take() {
            if (this.closed) throw new IllegalStateException("LOD storage is closed");
            var blob = this.free.poll();
            return blob != null ? blob : new SectionBlob(this.compressionLevel, this.codec);
        }

        void giveBack(SectionBlob blob) {
            if (this.closed) {
                blob.free();
                return;
            }
            this.free.add(blob);
        }

        void close() {
            this.closed = true;
            for (SectionBlob blob; (blob = this.free.poll()) != null; ) blob.free();
        }
    }
}