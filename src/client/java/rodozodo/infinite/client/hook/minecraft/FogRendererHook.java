package rodozodo.infinite.client.hook.minecraft;

import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.render.LodRendererHolder;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Pushes vanilla distance fog out while LoD's are active and applies custom Nether distance fog without suppressing thick hazard fog

@Mixin(value = FogRenderer.class, priority = 900) // Must run before Sodium reads the fog result
public class FogRendererHook {
    @Unique private static final float NO_FOG = 1.0e9f;
    @Unique private static final float THICK_FOG_END = 10;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void infinite$pushFogOut(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        var settings = ClientSettings.get();
        if (!settings.isRenderingEnabled() || !LodRendererHolder.isActive()) return;
        var fog = cir.getReturnValue();
        fog.renderDistanceStart = NO_FOG;
        fog.renderDistanceEnd = NO_FOG;
        if (fog.environmentalEnd < THICK_FOG_END) return;
        if (settings.netherFogIn(level)) {
            fog.environmentalStart = settings.netherFogStartChunks * 16f;
            fog.environmentalEnd = fog.environmentalStart + ClientSettings.NETHER_FOG_RAMP_CHUNKS * 16f;
            fog.color.w *= settings.netherFog / 100f;
        } else if (ClientSettings.FOG.clearsVanillaFog()) {
            fog.environmentalStart = NO_FOG;
            fog.environmentalEnd = NO_FOG;
        }
    }
}