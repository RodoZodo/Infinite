package rodozodo.infinite.client.hook.sodium;

import rodozodo.infinite.client.ingest.ClientIngest;
import rodozodo.infinite.client.render.LodRendererHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import rodozodo.infinite.client.render.VisibleVanillaSections;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Follows Sodiums chunk lifecycle:
// Reports chunks as they become ready or stop being ready, reports each section whose mesh was just built,
// and clears Infinites record of visible vanilla sections whenever Sodium starts a new render list.

@Mixin(value = RenderSectionManager.class, remap = false)
public class RenderSectionManagerHook {
    @Shadow @Final private ClientLevel level;

    // Gives the render list filter (VisibleChunkCollectorHook) this frames camera before Sodium builds its list
    @Inject(method = "finalizeRenderLists", at = @At("HEAD"))
    private void infinite$listCamera(Camera camera, Viewport viewport, FogParameters fog, boolean updateImmediately, CallbackInfo ci) {
        VisibleVanillaSections.listCamera(LodRendererHolder.isActive() ? viewport.getTransform() : null);
    }

    @Inject(method = "renderOutOfGraph", at = @At("HEAD"))
    private void infinite$outOfGraphList(Viewport viewport, FogParameters fog, CallbackInfo ci) {
        infinite$newRenderList();
    }

    @Inject(method = "readRenderListFromTree", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/VisibleChunkCollector;<init>(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;I)V"))
    private void infinite$treeList(Viewport viewport, FogParameters fog, CallbackInfo ci) {
        infinite$newRenderList();
    }

    @Unique
    private static void infinite$newRenderList() {
        var sections = LodRendererHolder.visibleSections();
        if (sections != null) sections.clear();
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void infinite$chunkReady(int x, int z, CallbackInfo ci) {
        ClientIngest.chunkReady(this.level, x, z);
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void infinite$chunkNoLongerReady(int x, int z, CallbackInfo ci) {
        ClientIngest.chunkNoLongerReady(this.level, x, z);
    }

    // Must inject at HEAD before Sodium updates section flags, so isBuilt() tells whether it had a prior mesh
    @Inject(method = "updateWithResult", at = @At("HEAD"))
    private void infinite$sectionBuilt(Viewport viewport, RenderSection section, ChunkBuildOutput output,
                                       List<RenderSection> updated, CallbackInfoReturnable<Integer> cir) {
        ClientIngest.sectionBuilt(this.level, section.getChunkX(), section.getChunkY(), section.getChunkZ(),
                section.isBuilt());
    }
}
