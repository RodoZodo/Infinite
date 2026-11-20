package rodozodo.infinite.client.render;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import rodozodo.infinite.Log;
import rodozodo.infinite.client.core.vk.VkContext;
import rodozodo.infinite.client.core.vk.VkUtil;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;


final class GpuTimings implements Destroyable {
    private static final int SLOTS = 6;
    private static final int MAX_MARKS = 24;
    private static final long KEEP_WANTED_FRAMES = 40;
    private static final long SHOW_FRAMES = 360;
    private static final int MEASURE_EVERY = 25;
    private static final double NEW_SAMPLE = 0.2;

    private static final class Span {
        double millis;
        long frame;

        void add(double value, long frame) {
            this.millis = this.millis == 0 ? value : this.millis * (1 - NEW_SAMPLE) + value * NEW_SAMPLE;
            this.frame = frame;
        }
    }

    private final VkDevice device;
    private final boolean enabled;
    private final double nanosPerTick;
    private final long[] pools = new long[SLOTS];
    private final boolean[] used = new boolean[SLOTS];
    private final int[] marked = new int[SLOTS];
    private final String[][] names = new String[SLOTS][MAX_MARKS];
    private final Map<String, Span> spans = new LinkedHashMap<>();

    private long frame;
    private long wanted = -1L << 40;
    private int slot = -1;
    private int marks;
    private long dropped;
    private boolean warned;

    GpuTimings() {
        var ctx = VkContext.get();
        this.device = ctx.vkDevice;
        this.nanosPerTick = ctx.limits.timestampPeriod();
        boolean supported = this.nanosPerTick > 0 && supportsTimestamps(ctx);
        boolean created = false;
        if (supported) {
            try (var stack = MemoryStack.stackPush()) {
                var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(MAX_MARKS);
                for (int i = 0; i < SLOTS; i++) {
                    var pool = stack.mallocLong(1);
                    VkUtil.check(vkCreateQueryPool(this.device, info, null, pool), "vkCreateQueryPool");
                    this.pools[i] = pool.get(0);
                }
                created = true;
            } catch (RuntimeException e) {
                Log.warn("Could not create GPU timing queries; using CPU timings only", e);
                this.destroy();
            }
        }
        this.enabled = created;
    }

    private static boolean supportsTimestamps(VkContext ctx) {
        var physical = ctx.vkDevice.getPhysicalDevice();
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            if (!properties.limits().timestampComputeAndGraphics()) return false;
            var count = stack.callocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            for (int i = 0; i < count.get(0); i++) {
                var family = families.get(i);
                if ((family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0 && family.timestampValidBits() != 0) return true;
            }
            return false;
        }
    }

    void beginFrame(VkCommandBuffer cb) {
        this.frame++;
        this.slot = -1;
        this.marks = 0;
        if (!this.enabled || this.frame - this.wanted > KEEP_WANTED_FRAMES) return;
        if (this.frame % MEASURE_EVERY != 0) return;
        int next = (int) (this.frame % SLOTS);
        if (!this.used[next] || this.read(next)) {
            vkCmdResetQueryPool(cb, this.pools[next], 0, MAX_MARKS);
            this.used[next] = true;
            this.slot = next;
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, this.pools[next], 0);
            this.names[next][0] = null;
            this.marks = 1;
            this.marked[next] = 1;
        }
    }

    void mark(VkCommandBuffer cb, String name) {
        if (this.slot < 0) return;
        if (this.marks == MAX_MARKS) {
            this.dropped++;
            return;
        }
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, this.pools[this.slot], this.marks);
        this.names[this.slot][this.marks] = name;
        this.marks++;
        this.marked[this.slot] = this.marks;
    }

    private boolean read(int slot) {
        int count = this.marked[slot];
        if (count < 2) {
            this.marked[slot] = 0;
            return true;
        }
        long stride = 16;
        try (var stack = MemoryStack.stackPush()) {
            long size = count * stride;
            var data = stack.malloc((int) size);
            int result = vkGetQueryPoolResults(this.device, this.pools[slot], 0, count, data, stride,
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (result == VK_NOT_READY) return false;
            if (result != VK_SUCCESS) {
                if (!this.warned) {
                    this.warned = true;
                    Log.warn("Could not read GPU timings (VkResult " + result + "); using CPU timings only");
                }
                this.marked[slot] = 0;
                return true;
            }
            long base = MemoryUtil.memAddress(data);
            if (MemoryUtil.memGetInt(base + size - 8) == 0) return false;
            long previous = MemoryUtil.memGetLong(base);
            for (int i = 1; i < count; i++) {
                long now = MemoryUtil.memGetLong(base + i * stride);
                double millis = (now - previous) * this.nanosPerTick / 1e6;
                previous = now;
                String name = this.names[slot][i];
                if (name == null || millis < 0) continue;
                this.spans.computeIfAbsent(name, key -> new Span()).add(millis, this.frame);
            }
            for (int i = 0; i < count; i++) this.names[slot][i] = null;
        }
        this.marked[slot] = 0;
        return true;
    }

    boolean measuring() {
        return this.slot >= 0;
    }

    String describe() {
        this.wanted = this.frame;
        if (!this.enabled) return "  Passes: timestamps not supported";
        double total = 0;
        var parts = new StringBuilder();
        for (var entry : this.spans.entrySet()) {
            var span = entry.getValue();
            if (this.frame - span.frame > SHOW_FRAMES) continue;
            total += span.millis;
            if (parts.length() != 0) parts.append(" · ");
            parts.append(entry.getKey()).append(' ').append(String.format("%.2f", span.millis));
        }
        if (parts.length() == 0) return "  Passes: measuring…";
        var text = new StringBuilder("  Passes: ").append(String.format("%.2f", total)).append(" ms · ").append(parts);
        if (this.dropped != 0) text.append(" (").append(this.dropped).append(" dropped)");
        return text.toString();
    }

    void free() {
        if (this.enabled) VkContext.get().destroyDeferred(this);
    }

    @Override
    public void destroy() {
        for (int i = 0; i < SLOTS; i++) {
            if (this.pools[i] != VK_NULL_HANDLE) {
                vkDestroyQueryPool(this.device, this.pools[i], null);
                this.pools[i] = VK_NULL_HANDLE;
            }
        }
    }
}
