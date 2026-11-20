package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.VBuffer;
import rodozodo.infinite.client.core.vk.VkContext;
import rodozodo.infinite.client.core.vk.VkUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;

final class GeometryPages {
    static final int METADATA_BYTES = 48;
    private static final long ELEMENT_SIZE = 8;
    static final int PAGE_BINDING = rodozodo.infinite.client.core.vk.VProgram.externalSsboBinding(1);
    private static final long MIN_PAGE = 16L << 20;
    private static final long PREFERRED_PAGE = 128L << 20, MOST_PAGE = 1L << 30;
    private static final int OTHER_BUFFERS_PER_STAGE = 12, OTHER_BUFFERS_PER_SET = 24, OTHER_RESOURCES_PER_STAGE = 48;

    private static final long RELEASE_NANOS = 5_000_000_000L;
    private static final boolean RELEASE = !"false".equals(System.getProperty("infinite.compactPages"));

    private final long pageBytes;
    private final int pageShift;
    private final int pageCount;
    private final long capacityBytes;
    private final int maxSectionCount;
    private final VBuffer[] pages;
    private final long[] unusedSince;
    private final VBuffer dummyPage;
    private final VBuffer metadataBuffer;
    private final long setLayout;
    private long descriptorPool;
    private long descriptorSet;
    private boolean descriptorDirty = true;
    private int sectionCount;

    GeometryPages(int maxSectionCount) {
        var ctx = VkContext.get();
        this.maxSectionCount = maxSectionCount;
        this.pageBytes = choosePageBytes(ctx.limits);
        this.pageShift = Long.numberOfTrailingZeros(this.pageBytes / ELEMENT_SIZE);
        this.capacityBytes = capacityFor(ctx.limits, this.pageBytes);
        this.pageCount = (int) (this.capacityBytes / this.pageBytes);
        this.pages = new VBuffer[this.pageCount];
        this.unusedSince = new long[this.pageCount];

        this.dummyPage = VBuffer.create("geometry_dummy_page", 256, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VBuffer.Access.DEVICE_LOCAL);
        this.metadataBuffer = VBuffer.growable("section_metadata", (long) NodeSync.LEAST_SECTIONS * METADATA_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);

        try (var stack = MemoryStack.stackPush()) {
            var binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(PAGE_BINDING)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(this.pageCount)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT);
            var info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding);
            var pLayout = stack.mallocLong(1);
            VkUtil.check(vkCreateDescriptorSetLayout(ctx.vkDevice, info, null, pLayout), "vkCreateDescriptorSetLayout(geometry)");
            this.setLayout = pLayout.get(0);
        }
    }

    static long capacityFor(VkContext.Limits limits, long pageBytes) {
        return Math.min(chooseCapacity(limits, pageBytes), mostPages(limits) * pageBytes);
    }

    static long choosePageBytes(VkContext.Limits limits) {
        long limit = Math.min(limits.maxStorageBufferRange(), limits.maxMemoryAllocationSize());
        long largest = Long.highestOneBit(Math.min(limit, MOST_PAGE));
        if (largest < MIN_PAGE) {
            throw new IllegalStateException("Device storage buffer range too small for Infinite geometry pages: " + limit);
        }
        String override = System.getProperty("infinite.vulkan.geometryPageMB", "");
        if (!override.isEmpty()) return Math.max(MIN_PAGE, Math.min(largest, Long.highestOneBit(Long.parseLong(override) << 20)));
        long page = Math.min(largest, PREFERRED_PAGE);
        while (page < largest && chooseCapacity(limits, page) / page > mostPages(limits)) page <<= 1;
        return page;
    }

    static long mostPages(VkContext.Limits limits) {
        long pages = Math.min(limits.maxPerStageDescriptorStorageBuffers() - OTHER_BUFFERS_PER_STAGE,
                limits.maxDescriptorSetStorageBuffers() - OTHER_BUFFERS_PER_SET);
        return Math.max(1, Math.min(pages, limits.maxPerStageResources() - OTHER_RESOURCES_PER_STAGE));
    }

    private static long chooseCapacity(VkContext.Limits limits, long pageBytes) {
        // Signed 32-bit vertexOffset (4 vertices per quad) caps addressable geometry at 2^29 quads (4 GiB).
        long addressLimit = (1L << 32) - pageBytes;
        long capacity = Math.max(512L << 20, limits.largestDeviceLocalHeap() - (1536L << 20));
        capacity = Math.min(capacity, addressLimit);
        String override = System.getProperty("infinite.geometryBufferSizeOverrideMB", "");
        if (!override.isEmpty()) {
            capacity = Math.min(Long.parseLong(override) << 20, addressLimit);
        }
        return Math.max(pageBytes, capacity / pageBytes * pageBytes);
    }

    public void ensurePage(int index) {
        if (this.pages[index] != null) return;
        this.pages[index] = VBuffer.create("geometry_page_" + index, this.pageBytes,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VBuffer.Access.DEVICE_LOCAL);
        this.descriptorDirty = true;
    }

    void setPagesInUse(long[] used) {
        long now = System.nanoTime() | 1;
        for (int i = 0; i < this.pageCount; i++) {
            if ((used[i >> 6] >>> i & 1) != 0) this.unusedSince[i] = 0;
            else if (this.unusedSince[i] == 0) this.unusedSince[i] = now;
        }
    }

    void releaseUnused() {
        if (!RELEASE) return;
        long now = System.nanoTime();
        for (int i = 0; i < this.pageCount; i++) {
            if (this.pages[i] == null || this.unusedSince[i] == 0 || now - this.unusedSince[i] < RELEASE_NANOS) continue;
            this.pages[i].free();
            this.pages[i] = null;
            this.descriptorDirty = true;
        }
    }

    public VBuffer page(int index) {
        var page = this.pages[index];
        if (page == null) throw new IllegalStateException("Geometry page " + index + " not allocated");
        return page;
    }

    public long descriptorSet() {
        if (this.descriptorDirty) {
            this.rebuildDescriptorSet();
        }
        return this.descriptorSet;
    }

    public long descriptorSetLayout() {
        return this.setLayout;
    }

    private void rebuildDescriptorSet() {
        var ctx = VkContext.get();
        if (this.descriptorPool != 0) {
            long oldPool = this.descriptorPool;
            ctx.destroyDeferred(() -> vkDestroyDescriptorPool(VkContext.get().vkDevice, oldPool, null));
        }
        try (var stack = MemoryStack.stackPush()) {
            var poolSize = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(this.pageCount);
            var poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSize);
            var pHandle = stack.mallocLong(1);
            VkUtil.check(vkCreateDescriptorPool(ctx.vkDevice, poolInfo, null, pHandle), "vkCreateDescriptorPool(geometry)");
            this.descriptorPool = pHandle.get(0);
            var allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(stack.longs(this.setLayout));
            VkUtil.check(vkAllocateDescriptorSets(ctx.vkDevice, allocInfo, pHandle), "vkAllocateDescriptorSets(geometry)");
            this.descriptorSet = pHandle.get(0);

            var infos = VkDescriptorBufferInfo.calloc(this.pageCount, stack);
            for (int i = 0; i < this.pageCount; i++) {
                var buffer = this.pages[i] != null ? this.pages[i] : this.dummyPage;
                infos.get(i).buffer(buffer.handle).offset(0).range(VK_WHOLE_SIZE);
            }
            var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                    .dstSet(this.descriptorSet)
                    .dstBinding(PAGE_BINDING)
                    .descriptorCount(this.pageCount)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(infos);
            vkUpdateDescriptorSets(ctx.vkDevice, write, null);
        }
        this.descriptorDirty = false;
    }

    public VBuffer getMetadataBuffer() {
        return this.metadataBuffer;
    }

    public int pageShift() {
        return this.pageShift;
    }

    public int pageCount() {
        return this.pageCount;
    }

    public long allocatedBytes() {
        long total = 0;
        for (var page : this.pages) if (page != null) total += page.size;
        return total;
    }

    public long getPageElements() {
        return this.pageBytes / ELEMENT_SIZE;
    }

    public long pageBytes() {
        return this.pageBytes;
    }

    public int getSectionCount() {
        return this.sectionCount;
    }

    void setSectionCount(int count) {
        this.sectionCount = count;
    }

    int sectionCapacity() {
        return (int) (this.metadataBuffer.size / METADATA_BYTES);
    }

    void growSections(VkCommandBuffer cb, int sections) {
        this.metadataBuffer.grow(cb, (long) sections * METADATA_BYTES, 0);
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public long getGeometryCapacityBytes() {
        return this.capacityBytes;
    }

    public void free() {
        var ctx = VkContext.get();
        for (var page : this.pages) if (page != null) page.free();
        this.dummyPage.free();
        this.metadataBuffer.free();
        long pool = this.descriptorPool;
        long layout = this.setLayout;
        ctx.destroyDeferred(() -> {
            if (pool != 0) vkDestroyDescriptorPool(VkContext.get().vkDevice, pool, null);
            vkDestroyDescriptorSetLayout(VkContext.get().vkDevice, layout, null);
        });
    }
}
