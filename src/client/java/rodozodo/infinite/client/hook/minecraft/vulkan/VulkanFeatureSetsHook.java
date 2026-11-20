package rodozodo.infinite.client.hook.minecraft.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import rodozodo.infinite.client.core.vk.InfiniteVkFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;


// Adds Infinites optional Vulkan features to the set the game asks the driver for at device creation

@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsHook {
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"))
    private static void infinite$addInfiniteFeatureSets(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        for (var feature : InfiniteVkFeatures.ALL) {
            cir.getReturnValue().add(feature.set());
        }
    }
}
