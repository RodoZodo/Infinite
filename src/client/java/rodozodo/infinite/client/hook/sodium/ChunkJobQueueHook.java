package rodozodo.infinite.client.hook.sodium;

import rodozodo.infinite.Infinite;
import rodozodo.infinite.client.compat.HelperLaneSemaphore;
import rodozodo.infinite.thread.HelperLane;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Semaphore;

// Lets Sodium chunk builder threads run LoD jobs while their own queue is empty

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobQueue", remap = false)
public class ChunkJobQueueHook {
    @Unique private HelperLane infinite$lane;

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(I)Ljava/util/concurrent/Semaphore;"))
    private Semaphore infinite$shareThreads(int permits) {
        var engine = Infinite.engine();
        if (engine == null || permits != 0) {
            return new Semaphore(permits);
        }
        this.infinite$lane = engine.workers().newHelperLane();
        return new HelperLaneSemaphore(this.infinite$lane);
    }

    @Inject(method = "shutdown", at = @At("RETURN"))
    private void infinite$closeLane(CallbackInfoReturnable<?> ci) {
        if (this.infinite$lane != null) this.infinite$lane.close();
    }
}
