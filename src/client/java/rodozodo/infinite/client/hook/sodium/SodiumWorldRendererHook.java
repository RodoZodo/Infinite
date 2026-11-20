package rodozodo.infinite.client.hook.sodium;

import rodozodo.infinite.Infinite;
import rodozodo.infinite.client.render.LodRendererHolder;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;


/**
 * Rebalances Infinites threads after Sodium creates its chunk builders, since they count towards the budget, and adds
 * Infinite lines to Sodium's debug overlay so chunk and lod work can be read side by side
 */

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class SodiumWorldRendererHook {
    @Inject(method = "initRenderer", at = @At("TAIL"))
    private void infinite$buildersChanged(CallbackInfo ci) {
        var engine = Infinite.engine();
        if (engine != null) engine.updateDedicatedThreads();
    }


    // Returned collection may be immutable (e.g. empty), so always copy before adding

    @Inject(method = "getDebugStrings", at = @At("RETURN"), cancellable = true)
    private void infinite$lodDebugLines(boolean reduced, CallbackInfoReturnable<Collection<String>> cir) {
        var renderer = LodRendererHolder.active();
        if (renderer == null) return;
        var lines = new ArrayList<>(cir.getReturnValue());
        renderer.addSodiumDebugInfo(lines);
        cir.setReturnValue(lines);
    }
}