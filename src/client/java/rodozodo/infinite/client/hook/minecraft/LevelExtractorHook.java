package rodozodo.infinite.client.hook.minecraft;

import rodozodo.infinite.client.render.LodRendererHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


// Follows the level renderers world changes and full reloads (F3+A, render distance, resource packs)

@Mixin(LevelExtractor.class)
public class LevelExtractorHook {
    @Shadow @Final private LevelRenderer levelRenderer;

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void infinite$levelChanged(ClientLevel level, CallbackInfo ci) {
        ((LodRendererHolder) this.levelRenderer).infinite$setWorld(level);
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void infinite$reload(CallbackInfo ci) {
        ((LodRendererHolder) this.levelRenderer).infinite$reloadRenderer();
    }
}
