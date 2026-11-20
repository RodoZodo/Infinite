package rodozodo.infinite.storage;

import rodozodo.infinite.Log;
import rodozodo.infinite.world.LodPos;
import rodozodo.infinite.world.LodSection;
import rodozodo.infinite.world.PackedCells;
import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

public final class LogLodStorage implements LodStorage {
    private static final String SEGMENT_PREFIX = "sections-";
    private static final String SEGMENT_SUFFIX = ".log";
    private static final String INDEX_FILE = "index.idx";
    private static final String INDEX_TEMP = "index.idx.new";
    private static final String MAPPINGS_FILE = "mappings.log";

    // Record header (16B big-endian): [codec:8 | length:24] (4B), key (8B), CRC32C (4B). Length=0 is tombstone
    private static final int HEADER_BYTES = 16;
    public static final int MAX_BYTES = SectionCodec.MAX_BYTES;
    private static final ValueLayout.OfInt HEADER_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final int LENGTH_MASK = 0x00FFFFFF;
    private static final int CODEC_SHIFT = 24;

    // Codec IDs in SectionCompression must remain stable across versions.
    private static final int UNKNOWN_CODEC = -2;

    // Mapping record header (12B big-endian): length (4B), id (4B), CRC32C (4B).
    private static final int MAPPING_HEADER_BYTES = 12;
    private static final int INDEX_MAGIC = 0x494E4458;
    private static final int INDEX_VERSION = 1;
    private static final long SEGMENT_BYTES = 256L << 20;
    private static final int WRITE_BUFFER_BYTES = 1 << 20;

    // Packed position layout: [segmentId:24 | offset:40].
    private static final int OFFSET_BITS = 40;
    private static final long OFFSET_MASK = (1L << OFFSET_BITS) - 1;
    private static final int STRIPES = 64;
    private static final long NO_POSITION = Long.MIN_VALUE;
    private static final int RETRY = 2;
    private static final int COMPACT_ONE_IN = 2;

    private static final ThreadLocal<CRC32C> CRC = ThreadLocal.withInitial(CRC32C::new);

    private static final boolean TIMING = Boolean.getBoolean("infinite.cacheTiming");
    private static final LongAdder FILE_LOADS = new LongAdder(), READ_NANOS = new LongAdder(), UNPACK_NANOS = new LongAdder();

    private final Path directory;
    private final int writeCodec;
    private final SectionBlob.Pool blobs;
    // Guarded by appendLock.
    private final ByteBuffer pending = ByteBuffer.allocateDirect(WRITE_BUFFER_BYTES).order(ByteOrder.BIG_ENDIAN);
    private long pendingAt;
    private final Stripe[] index = new Stripe[STRIPES];
    private final ConcurrentSkipListMap<Integer, Segment> segments = new ConcurrentSkipListMap<>();
    private final Set<Integer> compacting = ConcurrentHashMap.newKeySet();
    private final Set<Integer> reportedCodecs = ConcurrentHashMap.newKeySet();
    private final ExecutorService compactor;
    private final AtomicInteger nextSegment = new AtomicInteger();

    private final Object appendLock = new Object();
    private volatile Segment active;
    private volatile boolean closed;

    private final Int2ObjectOpenHashMap<byte[]> mappings = new Int2ObjectOpenHashMap<>();
    private final Object mappingsLock = new Object();
    private FileChannel mappingsChannel;
    private long mappingsLength;

    private static final class Segment {
        final int id;
        final Path file;
        final FileChannel channel;

        // Read lock for reading records
        // Write lock for mapping, closing, or deleting segment.
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        long length;
        long records;
        long live;
        volatile boolean dead;

        // Per-thread read channels avoid Windows FileChannel positioned-read lock contention.
        private final ConcurrentLinkedQueue<FileChannel> readers = new ConcurrentLinkedQueue<>();
        private final List<FileChannel> opened = new ArrayList<>();

        // Read-only mmap of initial bytes; replaced or closed only under Segment.lock write lock.
        @Nullable MemorySegment mapped;
        private @Nullable Arena mapping;

        Segment(int id, Path file, FileChannel channel) {
            this.id = id;
            this.file = file;
            this.channel = channel;
        }

        // Must hold lock for reading.
        FileChannel takeReader() throws IOException {
            var reader = this.readers.poll();
            if (reader != null) return reader;
            reader = FileChannel.open(this.file, StandardOpenOption.READ);
            synchronized (this.opened) {
                this.opened.add(reader);
            }
            return reader;
        }

        // Must hold lock for writing (or call during open) because Windows cannot delete mapped files.
        void map(long length) throws IOException {
            this.unmap();
            // Clamp to actual file size in case the index header was stale.
            length = Math.min(length, this.channel.size());
            if (length <= 0) return;
            var arena = Arena.ofShared();
            try {
                this.mapped = this.channel.map(FileChannel.MapMode.READ_ONLY, 0, length, arena);
                this.mapping = arena;
            } catch (IOException | RuntimeException e) {
                arena.close();
                throw e;
            }
        }

        private void unmap() {
            this.mapped = null;
            if (this.mapping != null) {
                this.mapping.close();
                this.mapping = null;
            }
        }

        void giveBack(FileChannel reader) {
            if (reader.isOpen()) this.readers.add(reader);
        }

        // Must hold lock for writing or be closing the store.
        void close() throws IOException {
            this.unmap();
            synchronized (this.opened) {
                for (var reader : this.opened) reader.close();
                this.opened.clear();
            }
            this.readers.clear();
            this.channel.close();
        }
    }

    private static final class Stripe {
        private final Long2LongOpenHashMap map = new Long2LongOpenHashMap();

        Stripe() {
            this.map.defaultReturnValue(NO_POSITION);
        }

        synchronized long get(long key) {
            return this.map.get(key);
        }

        synchronized long put(long key, long position) {
            return this.map.put(key, position);
        }

        synchronized long remove(long key) {
            return this.map.remove(key);
        }

        synchronized long size() {
            return this.map.size();
        }

        synchronized void reset() {
            this.map.clear();
        }

        synchronized long[] snapshot() {
            long[] keys = new long[this.map.size()];
            int at = 0;
            for (var iterator = this.map.keySet().iterator(); iterator.hasNext(); ) keys[at++] = iterator.nextLong();
            return keys;
        }

        synchronized void writeEntries(ByteBuffer into) {
            for (var entry : this.map.long2LongEntrySet()) {
                into.putLong(entry.getLongKey());
                into.putLong(entry.getLongValue());
            }
        }
    }

    public LogLodStorage(Path directory, int compressionLevel, int codec) {
        if (!SectionCompression.known(codec)) throw new IllegalArgumentException("Unknown LOD codec " + codec);
        this.directory = directory;
        this.writeCodec = codec;
        this.blobs = new SectionBlob.Pool(compressionLevel, codec);
        for (int i = 0; i < STRIPES; i++) this.index[i] = new Stripe();
        this.compactor = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "Infinite LOD compactor");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        try {
            Files.createDirectories(directory);
            this.openSegments();
            this.openMappings();
        } catch (IOException | RuntimeException e) {
            this.compactor.shutdownNow();
            this.blobs.close();
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException("Could not open the LOD storage at " + directory, e);
        }
    }

    private Stripe stripe(long key) {
        return this.index[(int) (key ^ key >>> 32) & STRIPES - 1];
    }

    private long position(long key) {
        return this.stripe(key).get(key);
    }

    private static long packed(int segment, long offset) {
        return (long) segment << OFFSET_BITS | offset & OFFSET_MASK;
    }

    private static int segmentOf(long position) {
        return (int) (position >>> OFFSET_BITS);
    }

    private static long offsetOf(long position) {
        return position & OFFSET_MASK;
    }

    private static int header(int length, int codec) {
        return codec << CODEC_SHIFT | length & LENGTH_MASK;
    }

    private static int lengthOf(int header) {
        return header & LENGTH_MASK;
    }

    private static int codecOf(int header) {
        return header >>> CODEC_SHIFT;
    }

    public static String describeTiming() {
        long loads = FILE_LOADS.sum();
        if (!TIMING || loads == 0) return null;
        String unpack = SectionBlob.describeTiming();
        return String.format(java.util.Locale.ROOT, "file us/load: read %.1f, check and decode %.1f (%d from files)",
                READ_NANOS.sum() / (double) loads / 1e3, UNPACK_NANOS.sum() / (double) loads / 1e3, loads)
                + (unpack == null ? "" : "; " + unpack);
    }

    @Override
    public int load(LodSection into) {
        return this.load(into.key, into, null, null);
    }

    @Override
    public int loadPacked(LodSection into, PackedCells packed) {
        return this.load(into.key, into, packed, null);
    }

    public int loadBytes(long key, ByteBuffer into) {
        if (!into.isDirect() || into.capacity() < MAX_BYTES) throw new IllegalArgumentException("Needs a direct buffer of " + MAX_BYTES + " bytes");
        return this.load(key, null, null, into);
    }

    public int saveBytes(long key, ByteBuffer from, int length) {
        if (length <= 0 || length > MAX_BYTES) throw new IllegalArgumentException("Can't store " + length + " bytes");
        var blob = this.blobs.take();
        try {
            int frame = blob.packBytes(from, length, HEADER_BYTES);
            this.append(key, blob, frame, NO_POSITION, false, blob.writeCodec());
            return HEADER_BYTES + frame;
        } finally {
            this.blobs.giveBack(blob);
        }
    }

    @Override
    public long stamp(long key) {
        for (int attempt = 0; attempt < 64; attempt++) {
            long position = this.position(key);
            if (position == NO_POSITION) return NO_STAMP;
            var segment = this.segments.get(segmentOf(position));
            if (segment == null) continue;
            long offset = offsetOf(position);
            if (segment == this.active) {
                synchronized (this.appendLock) {
                    if (segment == this.active && offset >= this.pendingAt) {
                        int start = (int) (offset - this.pendingAt);
                        if (start + HEADER_BYTES > this.pending.position()) return UNKNOWN_STAMP;
                        return stampOf(this.pending.getInt(start), this.pending.getInt(start + 12));
                    }
                }
            }
            segment.lock.readLock().lock();
            try {
                if (segment.dead) continue;
                var mapped = segment.mapped;
                if (mapped != null && offset + HEADER_BYTES <= mapped.byteSize()) {
                    return stampOf(mapped.get(HEADER_INT, offset), mapped.get(HEADER_INT, offset + 12));
                }
                var header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
                var reader = segment.takeReader();
                try {
                    if (readFully(reader, header, offset) < HEADER_BYTES) return UNKNOWN_STAMP;
                } finally {
                    segment.giveBack(reader);
                }
                return stampOf(header.getInt(0), header.getInt(12));
            } catch (IOException e) {
                return UNKNOWN_STAMP;
            } finally {
                segment.lock.readLock().unlock();
            }
        }
        return UNKNOWN_STAMP;
    }

    // Positive header word guarantees stamp is never 0 (NO_STAMP) or -1 (UNKNOWN_STAMP).
    private static long stampOf(int header, int crc) {
        return (long) header << 32 | crc & 0xFFFFFFFFL;
    }

    private static boolean unpack(SectionBlob blob, @Nullable LodSection into, @Nullable PackedCells packed,
                                  @Nullable ByteBuffer bytes, int length, int codec) {
        if (into != null) return blob.unpack(into, packed, HEADER_BYTES, length, codec);
        return bytes != null && blob.unpackBytes(bytes, HEADER_BYTES, length, codec);
    }

    private int load(long wanted, @Nullable LodSection into, @Nullable PackedCells packed, @Nullable ByteBuffer bytes) {
        var blob = this.blobs.take();
        try {
            for (int attempt = 0; attempt < 64; attempt++) {
                long position = this.position(wanted);
                if (position == NO_POSITION) return MISSING;
                var segment = this.segments.get(segmentOf(position));
                if (segment == null) continue;
                int result = this.read(segment, position, wanted, into, packed, bytes, blob);
                if (result != RETRY) return result;
            }
            throw new IllegalStateException("LOD section " + LodPos.describe(wanted) + " kept moving while being read");
        } finally {
            this.blobs.giveBack(blob);
        }
    }

    private int read(Segment segment, long position, long wanted, @Nullable LodSection into, @Nullable PackedCells packed,
                     @Nullable ByteBuffer bytes, SectionBlob blob) {
        long offset = offsetOf(position);
        if (segment == this.active) {
            int buffered = this.readBuffered(segment, offset, wanted, into, packed, bytes, blob);
            if (buffered != RETRY) return buffered;
        }
        var buffer = blob.buffer().order(ByteOrder.BIG_ENDIAN);
        segment.lock.readLock().lock();
        FileChannel reader = null;
        try {
            if (segment.dead) return RETRY;
            long started = TIMING ? System.nanoTime() : 0;
            int length = -1;
            var mapped = segment.mapped;
            if (mapped != null && offset + HEADER_BYTES <= mapped.byteSize()) {
                int mappedLength = lengthOf(mapped.get(HEADER_INT, offset));
                if (mappedLength > 0 && mappedLength <= SectionBlob.CAPACITY && offset + HEADER_BYTES + mappedLength <= mapped.byteSize()) {
                    buffer.clear();
                    MemorySegment.copy(mapped, offset, blob.bufferSegment(), 0, HEADER_BYTES + mappedLength);
                    length = mappedLength;
                }
            }
            if (length < 0) {
                reader = segment.takeReader();
                buffer.clear().limit(HEADER_BYTES);
                if (readFully(reader, buffer, offset) < HEADER_BYTES) return this.corrupt(wanted, "is shorter than its header");
                length = lengthOf(buffer.getInt(0));
                if (length <= 0 || length > SectionBlob.CAPACITY) return this.corrupt(wanted, "has an impossible length");
                buffer.clear().position(HEADER_BYTES).limit(HEADER_BYTES + length);
                if (readFully(reader, buffer, offset + HEADER_BYTES) < length) return this.corrupt(wanted, "is shorter than its record");
            }
            int codec = codecOf(buffer.getInt(0));
            long key = buffer.getLong(4);
            int storedCrc = buffer.getInt(12);
            if (!SectionCompression.known(codec) || !blob.canRead(codec)) return this.unsupported(wanted, codec);
            if (key != wanted) return this.corrupt(wanted, "is stored under another position");
            long read = TIMING ? System.nanoTime() : 0;
            if (crc(buffer, length) != storedCrc) return this.corrupt(wanted, "does not match its checksum");
            if (!unpack(blob, into, packed, bytes, length, codec)) return this.corrupt(wanted, "is not a valid section");
            if (TIMING) {
                FILE_LOADS.increment();
                READ_NANOS.add(read - started);
                UNPACK_NANOS.add(System.nanoTime() - read);
            }
            return LOADED;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read LOD section " + LodPos.describe(wanted), e);
        } finally {
            if (reader != null) segment.giveBack(reader);
            segment.lock.readLock().unlock();
        }
    }

    private int readBuffered(Segment segment, long offset, long wanted, @Nullable LodSection into, @Nullable PackedCells packed,
                             @Nullable ByteBuffer bytes, SectionBlob blob) {
        int length;
        int storedCrc;
        int codec;
        synchronized (this.appendLock) {
            if (segment != this.active || offset < this.pendingAt) return RETRY;
            int start = (int) (offset - this.pendingAt);
            if (start + HEADER_BYTES > this.pending.position()) return RETRY;
            int header = this.pending.getInt(start);
            length = lengthOf(header);
            long key = this.pending.getLong(start + 4);
            storedCrc = this.pending.getInt(start + 12);
            if (length <= 0 || length > SectionBlob.CAPACITY) return this.corrupt(wanted, "has an impossible length");
            codec = codecOf(header);
            if (!SectionCompression.known(codec) || !blob.canRead(codec)) return this.unsupported(wanted, codec);
            if (key != wanted) return this.corrupt(wanted, "is stored under another position");
            if (start + HEADER_BYTES + length > this.pending.position()) return this.corrupt(wanted, "is not a complete record");
            var buffer = blob.buffer().clear();
            var source = this.pending.duplicate();
            source.limit(start + HEADER_BYTES + length).position(start);
            buffer.put(source);
        }
        var buffer = blob.buffer().order(ByteOrder.BIG_ENDIAN);
        if (crc(buffer, length) != storedCrc) return this.corrupt(wanted, "does not match its checksum");
        if (!unpack(blob, into, packed, bytes, length, codec)) return this.corrupt(wanted, "is not a valid section");
        return LOADED;
    }

    private int corrupt(long wanted, String problem) {
        Log.error("Corrupt LoD section " + LodPos.describe(wanted) + "; deleting");
        this.delete(wanted);
        return CORRUPT;
    }

    // Leave records with unknown codecs intact so newer versions can still read them.
    private int unsupported(long wanted, int codec) {
        if (this.reportedCodecs.add(codec)) {
            Log.warn("LoD section at " + LodPos.describe(wanted) + " uses unsupported compression " + codec + "; rebuilding");
        }
        return MISSING;
    }

    @Override
    public void save(LodSection section) {
        var blob = this.blobs.take();
        try {
            int length = blob.pack(section, HEADER_BYTES);
            this.append(section.key, blob, length, NO_POSITION, false, blob.writeCodec());
        } finally {
            this.blobs.giveBack(blob);
        }
    }

    private void delete(long key) {
        var blob = this.blobs.take();
        try {
            this.append(key, blob, 0, NO_POSITION, false, SectionCompression.ZSTD);
        } finally {
            this.blobs.giveBack(blob);
        }
    }

    private boolean append(long key, SectionBlob blob, int length, long onlyIfAt, boolean conditional, int codec) {
        if (length > LENGTH_MASK) throw new IllegalArgumentException("LOD frame of " + length + " bytes is too large to store");
        var buffer = blob.buffer().order(ByteOrder.BIG_ENDIAN);
        buffer.clear();
        buffer.putInt(0, header(length, codec));
        buffer.putLong(4, key);
        buffer.putInt(12, crc(buffer, length));
        int total = HEADER_BYTES + length;
        synchronized (this.appendLock) {
            if (this.closed) throw new IllegalStateException("LOD storage is closed");
            var stripe = this.stripe(key);
            if (conditional && stripe.get(key) != onlyIfAt) return false;
            var segment = this.active;
            if (segment.length != 0 && segment.length + total > SEGMENT_BYTES) segment = this.roll();
            long offset = segment.length;
            if (this.pending.position() + total > this.pending.capacity()) this.flushPending(segment);
            var view = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
            view.clear().limit(total);
            this.pending.put(view);
            segment.length += total;
            segment.records++;
            long previous = length == 0 ? stripe.remove(key) : stripe.put(key, packed(segment.id, offset));
            if (previous != NO_POSITION) {
                var old = this.segments.get(segmentOf(previous));
                if (old != null) old.live--;
            }
            if (length != 0) segment.live++;
            return true;
        }
    }

    @Override
    public Path directory() {
        return this.directory;
    }

    @Override
    public void forEachSection(int level, LongConsumer action) {
        for (var stripe : this.index) {
            for (long key : stripe.snapshot()) {
                if (LodPos.level(key) == level) action.accept(key);
            }
        }
    }

    @Override
    public void writeMapping(int id, ByteBuffer data) {
        byte[] value = new byte[data.remaining()];
        data.get(data.position(), value);
        synchronized (this.mappingsLock) {
            if (this.closed) throw new IllegalStateException("LOD storage is closed");
            var record = ByteBuffer.allocate(MAPPING_HEADER_BYTES + value.length).order(ByteOrder.BIG_ENDIAN);
            record.putInt(0, value.length);
            record.putInt(4, id);
            record.putInt(8, mappingCrc(record, id, value));
            record.put(MAPPING_HEADER_BYTES, value);
            record.position(0).limit(MAPPING_HEADER_BYTES + value.length);
            try {
                long at = this.mappingsLength;
                while (record.hasRemaining()) at += this.mappingsChannel.write(record, at);
                this.mappingsLength = at;
            } catch (IOException e) {
                throw new IllegalStateException("Could not save id mapping " + id, e);
            }
            this.mappings.put(id, value);
        }
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> readMappings() {
        synchronized (this.mappingsLock) {
            return new Int2ObjectOpenHashMap<>(this.mappings);
        }
    }

    private static int mappingCrc(ByteBuffer record, int id, byte[] value) {
        var crc = CRC.get();
        crc.reset();
        var view = record.duplicate().order(ByteOrder.BIG_ENDIAN);
        view.clear().limit(4);
        crc.update(view);
        view.clear().position(4).limit(8);
        crc.update(view);
        crc.update(value);
        return (int) crc.getValue();
    }

    private void openSegments() throws IOException {
        List<Segment> existing = new ArrayList<>();
        try (Stream<Path> files = Files.list(this.directory)) {
            for (var path : files.toList()) {
                int id = segmentId(path);
                if (id < 0) continue;
                existing.add(new Segment(id, path, FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)));
            }
        }
        existing.sort(Comparator.comparingInt(segment -> segment.id));
        for (var segment : existing) {
            this.segments.put(segment.id, segment);
            this.nextSegment.accumulateAndGet(segment.id + 1, Math::max);
        }

        if (!this.readIndex(existing)) this.recover(existing);

        if (this.segments.isEmpty()) {
            this.active = this.createSegment(0);
        } else {
            this.active = this.segments.lastEntry().getValue();
            if (this.active.length >= SEGMENT_BYTES) this.active = this.createSegment(this.nextSegment.getAndIncrement());
        }
        this.pendingAt = this.active.length;
        for (var segment : this.segments.values()) segment.map(segment.length);
    }

    private static int segmentId(Path path) {
        String name = path.getFileName().toString();
        if (!name.startsWith(SEGMENT_PREFIX) || !name.endsWith(SEGMENT_SUFFIX)) return -1;
        String number = name.substring(SEGMENT_PREFIX.length(), name.length() - SEGMENT_SUFFIX.length());
        if (number.isEmpty() || number.length() > 9) return -1;
        for (int i = 0; i < number.length(); i++) {
            if (!Character.isDigit(number.charAt(i))) return -1;
        }
        return Integer.parseInt(number);
    }

    private void recover(List<Segment> existing) throws IOException {
        for (var stripe : this.index) stripe.reset();
        for (var segment : existing) {
            segment.length = 0;
            segment.records = 0;
            segment.live = 0;
        }
        var buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        for (var segment : existing) {
            long offset = 0;
            long records = 0;
            long size = segment.channel.size();
            while (true) {
                buffer.clear().limit(HEADER_BYTES);
                if (readFully(segment.channel, buffer, offset) < HEADER_BYTES) break;
                int length = lengthOf(buffer.getInt(0));
                long key = buffer.getLong(4);
                if (length == 0) {
                    if (!validKey(key)) break;
                    records++;
                    long previous = this.stripe(key).remove(key);
                    if (previous != NO_POSITION) {
                        var old = this.segments.get(segmentOf(previous));
                        if (old != null) old.live--;
                    }
                    offset += HEADER_BYTES;
                    continue;
                }
                if (length > SectionBlob.CAPACITY || !validKey(key)) break;
                long next = offset + HEADER_BYTES + length;
                if (next > size) break;
                records++;
                long previous = this.stripe(key).put(key, packed(segment.id, offset));
                segment.live++;
                if (previous != NO_POSITION) {
                    var old = this.segments.get(segmentOf(previous));
                    if (old != null) old.live--;
                }
                offset = next;
            }
            segment.records = records;
            segment.length = offset;
            if (offset < size) {
                Log.warn("Truncated torn LoD segment " + segment.file.getFileName() + " at byte " + offset);
                segment.channel.truncate(offset);
            }
        }
    }

    private static boolean validKey(long key) {
        int level = LodPos.level(key);
        return level >= 0 && level <= LodPos.MAX_LEVEL;
    }

    private void writeIndex() {
        List<Segment> ordered = new ArrayList<>(this.segments.values());
        int entries = 0;
        for (var stripe : this.index) entries += (int) stripe.size();
        var buffer = ByteBuffer.allocate(20 + ordered.size() * 28 + entries * 16).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(INDEX_MAGIC).putInt(INDEX_VERSION).putInt(ordered.size());
        for (var segment : ordered) buffer.putInt(segment.id).putLong(segment.length).putLong(segment.records).putLong(segment.live);
        buffer.putInt(entries);
        for (var stripe : this.index) stripe.writeEntries(buffer);
        var view = buffer.duplicate();
        view.flip();
        var crc = new CRC32C();
        crc.update(view);
        buffer.putInt((int) crc.getValue());
        try {
            var temp = this.directory.resolve(INDEX_TEMP);
            var seal = this.directory.resolve(INDEX_FILE);
            Files.write(temp, buffer.array(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, seal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.warn("Could not write LoD index; will replay segments next open", e);
        }
    }

    private boolean readIndex(List<Segment> existing) {
        Path seal = this.directory.resolve(INDEX_FILE);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(seal);
        } catch (NoSuchFileException missing) {
            return false;
        } catch (IOException e) {
            Log.warn("Could not read LoD index " + seal + "; replaying segments", e);
            return false;
        }
        try {
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            if (bytes.length < 16) return false;
            var view = buffer.duplicate();
            view.limit(bytes.length - 4);
            var crc = new CRC32C();
            crc.update(view);
            if (buffer.getInt(bytes.length - 4) != (int) crc.getValue()) return false;
            if (buffer.getInt() != INDEX_MAGIC || buffer.getInt() != INDEX_VERSION) return false;
            int count = buffer.getInt();
            if (count != existing.size()) return false;
            List<long[]> sealed = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                sealed.add(new long[]{buffer.getInt(), buffer.getLong(), buffer.getLong(), buffer.getLong()});
            }
            for (int i = 0; i < count; i++) {
                var segment = existing.get(i);
                var values = sealed.get(i);
                if (values[0] != segment.id || values[1] != segment.channel.size()) return false;
                segment.length = values[1];
                segment.records = values[2];
                segment.live = values[3];
            }
            int entries = buffer.getInt();
            for (int i = 0; i < entries; i++) {
                long key = buffer.getLong();
                long position = buffer.getLong();
                if (!validKey(key) || position == NO_POSITION) return false;
                this.stripe(key).put(key, position);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            Log.warn("Unreadable LoD index " + seal + "; replaying segments", e);
            return false;
        }
    }

    private Segment createSegment(int id) {
        var file = this.directory.resolve(SEGMENT_PREFIX + id + SEGMENT_SUFFIX);
        try {
            var segment = new Segment(id, file, FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE));
            this.segments.put(id, segment);
            this.nextSegment.accumulateAndGet(id + 1, Math::max);
            try {
                Files.deleteIfExists(this.directory.resolve(INDEX_FILE));
            } catch (IOException e) {
                Log.warn("Could not delete stale LoD index", e);
            }
            return segment;
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the LOD segment " + file, e);
        }
    }

    // Must hold appendLock.
    private void scheduleCompaction() {
        if (this.closed) return;
        for (var segment : this.segments.values()) this.compactIfWorthwhile(segment);
    }

    private void compactIfWorthwhile(Segment segment) {
        if (segment == this.active || segment.dead) return;
        if (segment.live * COMPACT_ONE_IN >= segment.records) return;
        if (!this.compacting.add(segment.id)) return;
        this.compactor.execute(() -> this.compact(segment));
    }

    private void compact(Segment segment) {
        try {
            if (this.closed || segment.dead) return;
            var blob = this.blobs.take();
            int[] codec = new int[1];
            try {
                for (var stripe : this.index) {
                    for (long key : stripe.snapshot()) {
                        if (this.closed || segment.dead) return;
                        long position = stripe.get(key);
                        if (position == NO_POSITION || segmentOf(position) != segment.id) continue;
                        int length = this.readFrame(segment, position, key, blob, codec);
                        if (length == UNKNOWN_CODEC) {
                            // Cannot copy or delete records written by an unsupported codec.
                            Log.warn("Skipped compacting " + segment.file.getFileName() + " (unsupported codec)");
                            return;
                        }
                        if (length < 0) {
                            this.delete(key);
                            continue;
                        }
                        this.append(key, blob, length, position, true, codec[0]);
                    }
                }
            } finally {
                this.blobs.giveBack(blob);
            }
            if (segment.live > 0) {
                Log.warn("Kept " + segment.file.getFileName() + " (" + segment.live + " live records remaining)");
                return;
            }
            segment.lock.writeLock().lock();
            try {
                segment.dead = true;
                try {
                    segment.close();
                } catch (IOException ignored) {
                }
                try {
                    Files.deleteIfExists(segment.file);
                } catch (IOException e) {
                    Log.warn("Could not delete compacted segment " + segment.file, e);
                }
                this.segments.remove(segment.id);
            } finally {
                segment.lock.writeLock().unlock();
            }
        } catch (RuntimeException e) {
            Log.error("LoD segment compaction failed", e);
        } finally {
            this.compacting.remove(segment.id);
        }
    }

    // Must hold appendLock.
    private void flushPending(Segment segment) {
        if (this.pending.position() == 0) return;
        this.pending.flip();
        try {
            long at = this.pendingAt;
            while (this.pending.hasRemaining()) {
                int written = segment.channel.write(this.pending, at);
                if (written <= 0) throw new IOException("Segment accepted no bytes at " + at);
                at += written;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not write to the LOD segment " + segment.file, e);
        } finally {
            this.pending.clear();
            this.pendingAt = segment.length;
        }
    }

    private int readFrame(Segment segment, long position, long key, SectionBlob blob, int[] codecOut) {
        var buffer = blob.buffer().order(ByteOrder.BIG_ENDIAN);
        segment.lock.readLock().lock();
        try {
            if (segment.dead) return -1;
            long offset = offsetOf(position);
            buffer.clear().limit(HEADER_BYTES);
            if (readFully(segment.channel, buffer, offset) < HEADER_BYTES) return -1;
            int header = buffer.getInt(0);
            int length = lengthOf(header);
            if (length <= 0 || length > SectionBlob.CAPACITY || buffer.getLong(4) != key) return -1;
            int codec = codecOf(header);
            if (!SectionCompression.known(codec) || !blob.canRead(codec)) return UNKNOWN_CODEC;
            codecOut[0] = codec;
            int storedCrc = buffer.getInt(12);
            buffer.clear().position(HEADER_BYTES).limit(HEADER_BYTES + length);
            if (readFully(segment.channel, buffer, offset + HEADER_BYTES) < length) return -1;
            return crc(buffer, length) == storedCrc ? length : -1;
        } catch (IOException e) {
            return -1;
        } finally {
            segment.lock.readLock().unlock();
        }
    }

    private Segment roll() {
        var sealed = this.active;
        this.flushPending(sealed);
        sealed.lock.writeLock().lock();
        try {
            if (!sealed.dead) sealed.map(sealed.length);
        } catch (IOException e) {
            Log.warn("Could not memory-map " + sealed.file + "; using file reads", e);
        } finally {
            sealed.lock.writeLock().unlock();
        }
        var segment = this.createSegment(this.nextSegment.getAndIncrement());
        this.active = segment;
        this.pendingAt = 0;
        this.scheduleCompaction();
        return segment;
    }

    private void openMappings() throws IOException {
        var file = this.directory.resolve(MAPPINGS_FILE);
        this.mappingsChannel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long offset = 0;
        long size = this.mappingsChannel.size();
        var header = ByteBuffer.allocate(MAPPING_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        while (true) {
            header.clear().limit(MAPPING_HEADER_BYTES);
            if (readFully(this.mappingsChannel, header, offset) < MAPPING_HEADER_BYTES) break;
            int length = header.getInt(0);
            int id = header.getInt(4);
            int storedCrc = header.getInt(8);
            if (length < 0 || length > 1 << 20) break;
            long next = offset + MAPPING_HEADER_BYTES + length;
            if (next > size) break;
            var value = new byte[length];
            if (length != 0) {
                var payload = ByteBuffer.wrap(value);
                if (readFully(this.mappingsChannel, payload, offset + MAPPING_HEADER_BYTES) < length) break;
                if (mappingCrc(header, id, value) != storedCrc) break;
                this.mappings.put(id, value);
            } else {
                this.mappings.remove(id);
            }
            offset = next;
        }
        if (offset < size) {
            Log.warn("Truncated torn LoD mappings at byte " + offset);
            this.mappingsChannel.truncate(offset);
        }
        this.mappingsLength = offset;
    }

    @Override
    public void flush() {
        synchronized (this.appendLock) {
            this.flushPending(this.active);
        }
        try {
            for (var segment : this.segments.values()) {
                if (!segment.dead) segment.channel.force(false);
            }
            synchronized (this.mappingsLock) {
                this.mappingsChannel.force(false);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not flush the LOD storage", e);
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        // Wait for compaction before setting closed so in-flight compactions do not fail.
        this.compactor.shutdown();
        try {
            this.compactor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.closed = true;
        try {
            this.flush();
        } catch (RuntimeException e) {
            Log.error("Could not flush LoD storage on close", e);
        }
        this.writeIndex();
        for (var segment : this.segments.values()) {
            try {
                segment.close();
            } catch (IOException e) {
                Log.error("Could not close LoD segment " + segment.file, e);
            }
        }
        try {
            this.mappingsChannel.close();
        } catch (IOException e) {
            Log.error("Could not close LoD mappings", e);
        }
        this.blobs.close();
    }

    private static int readFully(FileChannel channel, ByteBuffer into, long position) throws IOException {
        int total = 0;
        while (into.hasRemaining()) {
            int read = channel.read(into, position + total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private static int crc(ByteBuffer buffer, int length) {
        var crc = CRC.get();
        crc.reset();
        var view = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        view.clear().limit(HEADER_BYTES - 4);
        crc.update(view);
        view.clear().position(HEADER_BYTES).limit(HEADER_BYTES + length);
        crc.update(view);
        return (int) crc.getValue();
    }
}