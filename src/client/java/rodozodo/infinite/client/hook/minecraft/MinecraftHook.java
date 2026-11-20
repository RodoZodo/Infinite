package rodozodo.infinite.client.hook.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import rodozodo.infinite.client.LodSession;
import rodozodo.infinite.client.backend.GraphicsBackendGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.Comparator;

// Hooks the game's startup and teardown.
// Prefers Vulkan when the graphics API is left on "Default"
// Gives the render thread priority over LOD workers, and ends the session when the player leaves the world.

@Mixin(Minecraft.class)
public class MinecraftHook {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/PreferredGraphicsApi;getBackendsToTry()[Lcom/mojang/renderpearl/api/device/GpuBackend;"))
    private GpuBackend[] infinite$preferVulkan(PreferredGraphicsApi preferred, Operation<GpuBackend[]> original) {
        GraphicsBackendGate.recordPreference(preferred);
        GpuBackend[] backends = original.call(preferred);
        if (preferred != PreferredGraphicsApi.DEFAULT || "false".equalsIgnoreCase(System.getProperty("infinite.vulkanFirstForDefault"))) {
            return backends;
        }
        return Arrays.stream(backends)
                .sorted(Comparator.comparingInt(backend -> backend instanceof VulkanBackend ? 0 : 1))
                .toArray(GpuBackend[]::new);
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;save()V", ordinal = 0))
    private void infinite$raiseRenderThread(GameConfig config, CallbackInfo ci) {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("TAIL"))
    private void infinite$leftWorld(CallbackInfo ci) {
        LodSession.left();
    }
}
