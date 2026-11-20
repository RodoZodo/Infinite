package rodozodo.infinite.util;

import rodozodo.infinite.Log;
import org.jetbrains.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public final class NativeBuffer {
    private static final boolean TRACK = Boolean.getBoolean("infinite.trackBuffers");
    private static final @Nullable Cleaner LEAK_CHECKER = TRACK ? Cleaner.create() : null;
    private static final AtomicLong LIVE_BYTES = new AtomicLong();
    private static final VarHandle FREED;
    private static final MethodHandle NMEM_ALLOC, NMEM_FREE;

    static {
        MethodHandle alloc = null, free = null;
        try {
            FREED = MethodHandles.lookup().findVarHandle(NativeBuffer.class, "freed", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        try {
            var util = Class.forName("org.lwjgl.system.MemoryUtil");
            var lookup = MethodHandles.publicLookup();
            alloc = lookup.findStatic(util, "nmemAlloc", MethodType.methodType(long.class, long.class));
            free = lookup.findStatic(util, "nmemFree", MethodType.methodType(void.class, long.class));
        } catch (Throwable ignored) {
        }
        NMEM_ALLOC = alloc;
        NMEM_FREE = free;
    }

    private final @Nullable Arena arena;
    private final MemorySegment segment;
    public final long address;
    public final long size;
    private volatile boolean freed;
    private final @Nullable LeakReport leakReport;

    public NativeBuffer(long size) {
        if (size <= 0) throw new IllegalArgumentException("Buffer size must be positive, got " + size);
        if (NMEM_ALLOC != null) {
            long nativeAddress;
            try {
                nativeAddress = (long) NMEM_ALLOC.invokeExact(size);
            } catch (Throwable e) {
                throw new OutOfMemoryError("Could not allocate " + size + " bytes: " + e);
            }
            if (nativeAddress == 0) throw new OutOfMemoryError("Could not allocate " + size + " bytes");
            this.arena = null;
            this.address = nativeAddress;
            // nmemAlloc returns uninitialized memory, unlike Arena.allocate
            this.segment = MemorySegment.ofAddress(nativeAddress).reinterpret(size);
            this.segment.fill((byte) 0);
        } else {
            this.arena = Arena.ofShared();
            this.segment = this.arena.allocate(size);
            this.address = this.segment.address();
        }
        this.size = size;
        LIVE_BYTES.addAndGet(size);
        if (LEAK_CHECKER != null) {
            this.leakReport = new LeakReport(size, new Throwable("Allocated here"));
            LEAK_CHECKER.register(this, this.leakReport);
        } else {
            this.leakReport = null;
        }
    }

    public static NativeBuffer zeroed(long size) {
        return new NativeBuffer(size);
    }

    public void free() {
        if (!FREED.compareAndSet(this, false, true)) throw new IllegalStateException("Native buffer freed twice");
        if (this.arena != null) {
            this.arena.close();
        } else {
            try {
                NMEM_FREE.invokeExact(this.address);
            } catch (Throwable e) {
                throw new IllegalStateException("Could not free a native buffer of " + this.size + " bytes", e);
            }
        }
        LIVE_BYTES.addAndGet(-this.size);
        if (this.leakReport != null) this.leakReport.freed = true;
    }

    private void checkRange(long offset, long length) {
        if (this.freed) throw new IllegalStateException("Native buffer used after free");
        if (offset < 0 || length < 0 || offset + length > this.size) {
            throw new IndexOutOfBoundsException("Range " + offset + "+" + length + " outside a buffer of " + this.size + " bytes");
        }
    }

    public void copyTo(long destination) {
        this.checkRange(0, this.size);
        MemorySegment.copy(this.segment, 0, MemorySegment.ofAddress(destination).reinterpret(this.size), 0, this.size);
    }

    public NativeBuffer copyFrom(long source) {
        this.checkRange(0, this.size);
        MemorySegment.copy(MemorySegment.ofAddress(source).reinterpret(this.size), 0, this.segment, 0, this.size);
        return this;
    }

    public void read(long offset, byte[] destination, int destinationOffset, int length) {
        this.checkRange(offset, length);
        MemorySegment.copy(this.segment, ValueLayout.JAVA_BYTE, offset, destination, destinationOffset, length);
    }

    public ByteBuffer asByteBuffer() {
        if (this.size > Integer.MAX_VALUE) throw new IllegalStateException("Buffer too large for a ByteBuffer");
        this.checkRange(0, this.size);
        return this.segment.asByteBuffer();
    }

    public static long liveBytes() {
        return LIVE_BYTES.get();
    }

    private static final class LeakReport implements Runnable {
        private final long size;
        private final Throwable allocation;
        volatile boolean freed;

        LeakReport(long size, Throwable allocation) {
            this.size = size;
            this.allocation = allocation;
        }

        @Override
        public void run() {
            if (!this.freed) Log.error("A native buffer of " + this.size + " bytes was never freed", this.allocation);
        }
    }
}