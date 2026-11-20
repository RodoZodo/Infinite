package rodozodo.infinite.client.core.vk;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

// Small Vulkan helpers shared by the renderer's wrappers

public final class VkUtil {
    private VkUtil() {}

    public static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed with VkResult " + result);
        }
    }
}
