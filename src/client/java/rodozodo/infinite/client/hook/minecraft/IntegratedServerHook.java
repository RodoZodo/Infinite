package rodozodo.infinite.client.hook.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import rodozodo.infinite.server.ServerGenerate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Keeps the integrated server ticking while paused during a {@code /infinite generate} run, freezing world ticks so only chunk generation proceeds

@Mixin(IntegratedServer.class)
public class IntegratedServerHook {

    // Server thread only.
    private static boolean frozenByUs;

    @WrapOperation(method = "tickServer(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isPaused()Z"))
    private boolean infinite$keepGeneratingWhenPaused(Minecraft minecraft, Operation<Boolean> original) {
        boolean paused = original.call(minecraft);
        if (!paused || !ServerGenerate.running()) {
            thaw(minecraft);
            return paused;
        }
        freeze(minecraft);
        return false;
    }

    private static void freeze(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        if (server == null) return;
        var ticks = server.tickRateManager();
        if (ticks.isFrozen()) return;
        ticks.setFrozen(true);
        frozenByUs = true;
    }

    private static void thaw(Minecraft minecraft) {
        if (!frozenByUs) return;
        frozenByUs = false;
        var server = minecraft.getSingleplayerServer();
        if (server != null && server.tickRateManager().isFrozen()) server.tickRateManager().setFrozen(false);
    }
}