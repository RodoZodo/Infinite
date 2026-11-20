package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.ReadbackBuffer;
import rodozodo.infinite.client.core.vk.VBarriers;
import rodozodo.infinite.client.core.vk.VBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexedIndirect;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;


abstract class DrawCountStrategy {
    static final int STRIDE = 5 * 4;

    record DrawList(String name, long commandOffset, long countOffset, int capacity) {}

    static DrawCountStrategy create(boolean cpuCountFallback, List<DrawList> lists, ReadbackBuffer readback) {
        return cpuCountFallback ? new CpuCount(lists, readback) : new GpuCount(lists);
    }

    abstract void prepare(VkCommandBuffer cb, VBuffer commands, int list, int maxDraws);

    abstract void afterGeneration(VkCommandBuffer cb, VBuffer counts);

    abstract void draw(VkCommandBuffer cb, VBuffer commands, VBuffer counts, int list, int maxDraws);

    abstract void addDebug(List<String> lines);

    static final class GpuCount extends DrawCountStrategy {
        private final List<DrawList> lists;

        GpuCount(List<DrawList> lists) {
            this.lists = lists;
        }

        @Override
        void prepare(VkCommandBuffer cb, VBuffer commands, int list, int maxDraws) {}

        @Override
        void afterGeneration(VkCommandBuffer cb, VBuffer counts) {}

        @Override
        void draw(VkCommandBuffer cb, VBuffer commands, VBuffer counts, int list, int maxDraws) {
            var l = this.lists.get(list);
            if (maxDraws > 0) {
                vkCmdDrawIndexedIndirectCount(cb, commands.handle, l.commandOffset(), counts.handle, l.countOffset(), maxDraws, STRIDE);
            }
        }

        @Override
        void addDebug(List<String> lines) {
            lines.add("Vk draws: GPU count");
        }
    }

    static final class CpuCount extends DrawCountStrategy {
        private static final float SCALE = Float.parseFloat(System.getProperty("infinite.vulkan.fallbackDrawScale", "1.25"));
        private static final int MIN_HEADROOM = Integer.getInteger("infinite.vulkan.fallbackDrawHeadroom", 256);
        private static final boolean GROW_FROM_COUNT = !"false".equals(System.getProperty("infinite.vulkan.fallbackDrawGrow"));

        private final List<DrawList> lists;
        private final ReadbackBuffer readback;
        private final int[] lastReadCount;
        private final int[] preparedCount;
        private final int[] lastIssued;
        private final boolean[] forceMax;
        private long undershoots;

        CpuCount(List<DrawList> lists, ReadbackBuffer readback) {
            this.lists = lists;
            this.readback = readback;
            this.lastReadCount = new int[lists.size()];
            this.preparedCount = new int[lists.size()];
            this.lastIssued = new int[lists.size()];
            this.forceMax = new boolean[lists.size()];
        }

        @Override
        void prepare(VkCommandBuffer cb, VBuffer commands, int list, int maxDraws) {
            int last = this.lastReadCount[list];
            long headroom = Math.max((long) (last * SCALE) + MIN_HEADROOM, (long) last + 4L * MIN_HEADROOM);
            if (this.forceMax[list]) headroom = GROW_FROM_COUNT ? Math.max(2L * last, (long) last + 4L * MIN_HEADROOM) : maxDraws;
            int count = (int) Math.min(maxDraws, headroom);
            this.forceMax[list] = false;

            // Zero slots up to max(count, previous preparedCount) so unwritten indirect slots remain 0 instance no-ops
            int clear = Math.max(count, this.preparedCount[list]);
            if (clear > 0) {
                vkCmdFillBuffer(cb, commands.handle, this.lists.get(list).commandOffset(), (long) clear * STRIDE, 0);
            }
            this.preparedCount[list] = count;
        }

        @Override
        void afterGeneration(VkCommandBuffer cb, VBuffer counts) {
            int[] issued = this.preparedCount.clone();
            long first = Long.MAX_VALUE, last = 0;
            for (var l : this.lists) {
                first = Math.min(first, l.countOffset());
                last = Math.max(last, l.countOffset() + 4);
            }
            long base = first;
            VBarriers.computeToTransfer(cb);
            this.readback.request(counts, base, last - base, (ptr, size) -> {
                for (int i = 0; i < this.lists.size(); i++) {
                    int real = MemoryUtil.memGetInt(ptr + (this.lists.get(i).countOffset() - base));
                    this.lastReadCount[i] = real;
                    if (real > issued[i]) {
                        this.forceMax[i] = true;
                        this.undershoots++;
                    }
                }
            });
        }

        @Override
        void draw(VkCommandBuffer cb, VBuffer commands, VBuffer counts, int list, int maxDraws) {
            int count = Math.min(this.preparedCount[list], maxDraws);
            this.lastIssued[list] = count;
            if (count > 0) {
                vkCmdDrawIndexedIndirect(cb, commands.handle, this.lists.get(list).commandOffset(), count, STRIDE);
            }
        }

        @Override
        void addDebug(List<String> lines) {
            StringBuilder sb = new StringBuilder("Vk draws issued/real (CPU count fallback):");
            for (int i = 0; i < this.lists.size(); i++) {
                sb.append(' ').append(this.lists.get(i).name()).append(' ').append(this.lastIssued[i]).append('/').append(this.lastReadCount[i]);
            }
            sb.append(", undershoots ").append(this.undershoots);
            lines.add(sb.toString());
        }
    }
}
