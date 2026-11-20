package rodozodo.infinite.client.hook.minecraft.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Exposes the feature set the game negotiated with the Vulkan driver, so Infinite can check what it may use

@Mixin(VulkanDevice.class)
public interface VulkanDeviceAccess {
    @Accessor("enabledFeatures")
    FeatureSet infinite$getEnabledFeatures();
}