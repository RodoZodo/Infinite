package rodozodo.infinite.client.core.vk;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

// VMA backed {@code VkBuffer} wrapper supporting arbitrary usage flags and deferred destruction

public final class VBuffer implements Destroyable {
    private static final LongAdder LIVE_BUFFERS = new LongAdder();
    private static final LongAdder LIVE_BYTES = new LongAdder();
    private static final Map<String, LongAdder> BYTES_BY_NAME = new ConcurrentHashMap<>();

    public record Usage(long buffers, long bytes) {}

    public enum Access {
        DEVICE_LOCAL,
        HOST_UPLOAD,
        HOST_READBACK
    }


    // Mutated only by grow() on the render thread

    public long handle, size;
    private long allocation;
    private final long mappedAddress;
    public final boolean hostCoherent;
    private final String name, kind;
    private final int vkUsage;
    private boolean destroyed;

    private VBuffer(String name, long handle, long allocation, long size, long mappedAddress, boolean hostCoherent, int vkUsage) {
        this.handle = handle;
        this.allocation = allocation;
        this.size = size;
        this.mappedAddress = mappedAddress;
        this.hostCoherent = hostCoherent;
        this.name = name;
        this.kind = name.replaceAll("_?\\d+$", "");
        this.vkUsage = vkUsage;
        LIVE_BUFFERS.increment();
        LIVE_BYTES.add(size);
        BYTES_BY_NAME.computeIfAbsent(this.kind, k -> new LongAdder()).add(size);
    }

    public static VBuffer create(String name, long size, int vkUsage, Access access) {
        long[] made = allocate(name, size, vkUsage, access);
        return new VBuffer(name, made[0], made[1], size, made[2], made[3] != 0, vkUsage);
    }

    public static VBuffer growable(String name, long size, int vkUsage) {
        return create(name, size, vkUsage | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, Access.DEVICE_LOCAL);
    }

    public void grow(VkCommandBuffer cb, long newSize, int fill) {
        if (newSize <= this.size) return;
        if (this.mappedAddress != 0 || (this.vkUsage & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) == 0) {
            throw new IllegalStateException(this.name + " was not made to grow");
        }
        long[] made = allocate(this.name, newSize, this.vkUsage, Access.DEVICE_LOCAL);

        // Earlier commands may still be writing the old buffer.
        VBarriers.memory(cb, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT);
        try (var stack = MemoryStack.stackPush()) {
            vkCmdCopyBuffer(cb, this.handle, made[0], VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(this.size));
        }
        vkCmdFillBuffer(cb, made[0], this.size, newSize - this.size, fill);
        VBarriers.memory(cb, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
        long oldHandle = this.handle, oldAllocation = this.allocation, oldSize = this.size;
        VkContext.get().destroyDeferred(() -> {
            vmaDestroyBuffer(VkContext.get().vma, oldHandle, oldAllocation);
            LIVE_BYTES.add(-oldSize);
            BYTES_BY_NAME.get(this.kind).add(-oldSize);
        });
        this.handle = made[0];
        this.allocation = made[1];
        this.size = newSize;
        LIVE_BYTES.add(newSize);
        BYTES_BY_NAME.get(this.kind).add(newSize);
    }

    private static long[] allocate(String name, long size, int vkUsage, Access access) {
        var ctx = VkContext.get();
        try (var stack = MemoryStack.stackPush()) {
            var bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size)
                    .usage(vkUsage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var allocInfo = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO);
            switch (access) {
                case DEVICE_LOCAL -> allocInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                case HOST_UPLOAD -> allocInfo.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
                case HOST_READBACK -> allocInfo.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }
            var pBuffer = stack.mallocLong(1);
            var pAllocation = stack.mallocPointer(1);
            var resultInfo = VmaAllocationInfo.calloc(stack);
            VkUtil.check(vmaCreateBuffer(ctx.vma, bufferInfo, allocInfo, pBuffer, pAllocation, resultInfo), "vmaCreateBuffer(" + name + ", " + size + ")");
            long allocation = pAllocation.get(0);
            vmaSetAllocationName(ctx.vma, allocation, stack.UTF8("infinite:" + name));
            boolean coherent = false;
            if (access != Access.DEVICE_LOCAL) {
                var pFlags = stack.mallocInt(1);
                vmaGetAllocationMemoryProperties(ctx.vma, allocation, pFlags);
                coherent = (pFlags.get(0) & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            }
            return new long[]{pBuffer.get(0), allocation, access == Access.DEVICE_LOCAL ? 0 : resultInfo.pMappedData(), coherent ? 1 : 0};
        }
    }

    public long mappedAddress() {
        if (this.mappedAddress == 0) throw new IllegalStateException("Buffer is not host mapped");
        return this.mappedAddress;
    }

    public void flush(long offset, long size) {
        if (!this.hostCoherent) vmaFlushAllocation(VkContext.get().vma, this.allocation, offset, size);
    }

    public void invalidate(long offset, long size) {
        if (!this.hostCoherent) vmaInvalidateAllocation(VkContext.get().vma, this.allocation, offset, size);
    }

    public void free() {
        VkContext.get().destroyDeferred(this);
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        vmaDestroyBuffer(VkContext.get().vma, this.handle, this.allocation);
        LIVE_BUFFERS.decrement();
        LIVE_BYTES.add(-this.size);
        BYTES_BY_NAME.get(this.kind).add(-this.size);
    }

    public static String largest(int count) {
        var line = new StringBuilder();
        BYTES_BY_NAME.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(count)
                .forEach(e -> line.append(line.isEmpty() ? "" : " · ").append(e.getKey()).append(' ').append(e.getValue() >> 20));
        return line.toString();
    }

    public static Usage usage() {
        return new Usage(LIVE_BUFFERS.sum(), LIVE_BYTES.sum());
    }
}
