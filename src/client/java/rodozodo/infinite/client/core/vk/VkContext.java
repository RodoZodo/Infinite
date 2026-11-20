package rodozodo.infinite.client.core.vk;

import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import rodozodo.infinite.Log;
import rodozodo.infinite.client.hook.minecraft.FrontendGpuDeviceAccess;
import rodozodo.infinite.client.hook.minecraft.vulkan.VulkanDeviceAccess;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMaintenance3Properties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDevicePushDescriptorPropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;


// Single point of contact with Minecraft's Vulkan backend internals, exposing the device, VMA allocator, pipeline cache, and command submission helpers.
// Render thread only.

public final class VkContext {
    private static VkContext INSTANCE;

    public final GpuDevice gpuDevice;
    public final VulkanDevice device;
    public final VkDevice vkDevice;
    public final long vma;
    public final FeatureSet enabledFeatures;
    public final Limits limits;
    public final GpuVendor vendor;
    public final String deviceName;
    public final long pipelineCache;
    // Cache header: magic, format, vendorID, deviceID, driverVersion, apiVersion, length, CRC32.
    private final int[] cacheKey;
    private static final int CACHE_MAGIC = 0x494E4643;
    private static final int CACHE_FORMAT = 2;
    private static final int CACHE_HEADER_INTS = 8;
    private static final int CACHE_MOST_BYTES = 64 << 20;
    private static final String CACHE_PATH = "infinite/pipeline_cache.bin";
    private static final boolean PERSIST_PIPELINE_CACHE = !"false".equalsIgnoreCase(System.getProperty("infinite.pipelineCache"));
    public final boolean drawCountFallback;
    private final FrontendCommandEncoder frontendEncoder;
    private final VulkanCommandEncoder encoder;

    public record Limits(long maxStorageBufferRange, long minStorageBufferOffsetAlignment, int maxComputeWorkGroupInvocations,
                         int subgroupSize, int subgroupSupportedStages, int subgroupSupportedOperations, float timestampPeriod,
                         long maxMemoryAllocationSize, long largestDeviceLocalHeap, int maxPushDescriptors, int maxPushConstantsSize,
                         long maxPerStageDescriptorStorageBuffers, long maxDescriptorSetStorageBuffers, long maxPerStageResources) {}

    private VkContext(GpuDevice gpuDevice, VulkanDevice device) {
        this.gpuDevice = gpuDevice;
        this.device = device;
        this.vkDevice = device.vkDevice();
        this.vma = device.vma();
        this.enabledFeatures = ((VulkanDeviceAccess) device).infinite$getEnabledFeatures();
        this.frontendEncoder = (FrontendCommandEncoder) gpuDevice.createCommandEncoder();
        this.encoder = (VulkanCommandEncoder) this.frontendEncoder.backend();
        this.limits = queryLimits(this.vkDevice);
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(this.vkDevice.getPhysicalDevice(), properties);
            this.vendor = GpuVendor.of(properties.vendorID());
            this.deviceName = properties.deviceNameString();
            this.cacheKey = new int[]{CACHE_MAGIC, CACHE_FORMAT, properties.vendorID(), properties.deviceID(),
                    properties.driverVersion(), properties.apiVersion()};
        }
        this.pipelineCache = createPipelineCache(this.vkDevice, this.cacheKey);
        this.drawCountFallback = InfiniteVkFeatures.usesDrawCountFallback(this.enabledFeatures);
    }

    public static VkContext create(GpuDevice gpuDevice) {
        if (INSTANCE != null) throw new IllegalStateException("VkContext already created");
        if (!(((FrontendGpuDeviceAccess) gpuDevice).infinite$getBackend() instanceof VulkanDevice vulkanDevice)) {
            return null;
        }
        INSTANCE = new VkContext(gpuDevice, vulkanDevice);
        return INSTANCE;
    }

    public static VkContext get() {
        if (INSTANCE == null) throw new IllegalStateException("VkContext not created");
        return INSTANCE;
    }

    public static boolean isCreated() {
        return INSTANCE != null;
    }

    private static Path cacheFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(CACHE_PATH);
    }

    private static long createPipelineCache(VkDevice device, int[] key) {
        ByteBuffer initial = PERSIST_PIPELINE_CACHE ? readCacheFile(key) : null;
        try (var stack = MemoryStack.stackPush()) {
            var info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            var p = stack.mallocLong(1);
            int result = VK_SUCCESS;
            if (initial != null) {
                info.pInitialData(initial);
                result = vkCreatePipelineCache(device, info, null, p);
                if (result != VK_SUCCESS) {
                    Log.warn("Could not load pipeline cache (VkResult " + result + "); rebuilding");
                }
            }
            if (initial == null || result != VK_SUCCESS) {
                info.pInitialData((ByteBuffer) null);
                VkUtil.check(vkCreatePipelineCache(device, info, null, p), "vkCreatePipelineCache");
            }
            return p.get(0);
        } finally {
            if (initial != null) MemoryUtil.memFree(initial);
        }
    }

    public synchronized void savePipelineCache() {
        if (this.pipelineCache == 0 || !PERSIST_PIPELINE_CACHE) return;
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocPointer(1);
            vkGetPipelineCacheData(this.vkDevice, this.pipelineCache, size, null);
            long bytes = size.get(0);
            if (bytes <= 0 || bytes > Integer.MAX_VALUE) return;
            var data = MemoryUtil.memAlloc((int) bytes);
            try {

                // Concurrent pipeline creation can grow the cache and return VK_INCOMPLETE
                int result = vkGetPipelineCacheData(this.vkDevice, this.pipelineCache, size, data);
                if (result != VK_SUCCESS) {
                    Log.warn("Could not save pipeline cache (VkResult " + result + ")");
                    return;
                }
                writeCacheFile(data, (int) Math.min(bytes, size.get(0)));
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }

    private void writeCacheFile(ByteBuffer data, int length) {
        try {
            var view = data.duplicate();
            view.limit(length);
            var checksum = new CRC32();
            checksum.update(view);
            var out = ByteBuffer.allocate(CACHE_HEADER_INTS * 4 + length);
            for (int value : this.cacheKey) out.putInt(value);
            out.putInt(length).putInt((int) checksum.getValue());
            out.put(view.position(0).limit(length));
            Path file = cacheFile();
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, out.array(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.warn("Could not save pipeline cache: " + e);
        }
    }

    @Nullable
    private static ByteBuffer readCacheFile(int[] key) {
        Path file = cacheFile();
        if (!Files.isRegularFile(file)) return null;
        try {
            if (Files.size(file) > CACHE_HEADER_INTS * 4L + CACHE_MOST_BYTES) return null;
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < CACHE_HEADER_INTS * 4) return null;
            var header = ByteBuffer.wrap(bytes);
            for (int expected : key) {
                if (header.getInt() != expected) return null;
            }
            int length = header.getInt(), checksum = header.getInt();
            if (length <= 0 || length != bytes.length - CACHE_HEADER_INTS * 4) return null;
            int offset = CACHE_HEADER_INTS * 4;
            var actual = new CRC32();
            actual.update(bytes, offset, length);
            if ((int) actual.getValue() != checksum) return null;

            // Native buffer required. driver reads pInitialData during vkCreatePipelineCache.
            var data = MemoryUtil.memAlloc(length);
            data.put(bytes, offset, length).flip();

            // Validate Vulkan pipeline cache header (size, version=1, vendorID, deviceID).
            if (data.getInt(0) < 32 || data.getInt(4) != 1 || data.getInt(8) != key[2] || data.getInt(12) != key[3]) {
                MemoryUtil.memFree(data);
                return null;
            }
            return data;
        } catch (IOException e) {
            Log.warn("Could not read pipeline cache: " + e);
            return null;
        }
    }

    private static Limits queryLimits(VkDevice device) {
        try (var stack = MemoryStack.stackPush()) {
            var pushDescriptor = VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack).sType$Default();
            var maintenance3 = VkPhysicalDeviceMaintenance3Properties.calloc(stack).sType$Default().pNext(pushDescriptor.address());
            var subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default().pNext(maintenance3.address());
            var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(subgroup.address());
            vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props);
            var l = props.properties().limits();

            var memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memory);
            long largestDeviceLocal = 0;
            for (int i = 0; i < memory.memoryHeapCount(); i++) {
                var heap = memory.memoryHeaps(i);
                if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    largestDeviceLocal = Math.max(largestDeviceLocal, deviceSize(heap.size()));
                }
            }
            return new Limits(
                    Integer.toUnsignedLong(l.maxStorageBufferRange()),
                    l.minStorageBufferOffsetAlignment(),
                    l.maxComputeWorkGroupInvocations(),
                    subgroup.subgroupSize(),
                    subgroup.supportedStages(),
                    subgroup.supportedOperations(),
                    l.timestampPeriod(),
                    deviceSize(maintenance3.maxMemoryAllocationSize()),
                    largestDeviceLocal,
                    pushDescriptor.maxPushDescriptors(),
                    l.maxPushConstantsSize(),
                    Integer.toUnsignedLong(l.maxPerStageDescriptorStorageBuffers()),
                    Integer.toUnsignedLong(l.maxDescriptorSetStorageBuffers()),
                    Integer.toUnsignedLong(l.maxPerStageResources()));
        }
    }

    // VkDeviceSize is unsigned 64-bit. drivers report -1 (all ones) for unlimited.
    private static long deviceSize(long size) {
        return size < 0 ? Long.MAX_VALUE : size;
    }

    public boolean hasFeature(InfiniteVkFeatures.Feature feature) {
        return this.enabledFeatures.contains(feature.set());
    }

    public VkCommandBuffer beginFrameCommands() {
        return this.encoder.allocateAndBeginTransientCommandBuffer();
    }

    // Must not be called while a Minecraft RenderPass is open.
    public void executeInFrame(VkCommandBuffer commandBuffer) {
        VkUtil.check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
        this.encoder.execute(commandBuffer);
    }

    public GpuFence createFrameFence() {
        return this.frontendEncoder.createFence();
    }

    public void flushSubmit() {
        this.frontendEncoder.submit();
    }

    public void destroyDeferred(Destroyable destroyable) {
        this.encoder.queueForDestroy(destroyable);
    }
}
