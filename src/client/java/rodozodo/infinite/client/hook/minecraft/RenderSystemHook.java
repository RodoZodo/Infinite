package rodozodo.infinite.client.hook.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import rodozodo.infinite.client.InfiniteClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


// Checks the finished GPU device once, before anything draws, to decide whether Infinite can run on it

@Mixin(RenderSystem.class)
public class RenderSystemHook {
    @Inject(method = "initRenderer", order = 900, remap = false, at = @At("RETURN"))
    private static void infinite$deviceReady(GpuDevice device, CallbackInfo ci) {
        InfiniteClient.deviceReady(device);
    }
}