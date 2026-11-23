package rodozodo.infinite.client.hook.sodium;

import rodozodo.infinite.client.render.VisibleVanillaSections;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Reports each section of Sodiums occlusion culled render list to the vanilla mask

@Mixin(value = VisibleChunkCollector.class, remap = false)
public class VisibleChunkCollectorHook {
    @Redirect(method = "visit", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;getForChunk(III)Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"))
    private RenderRegion infinite$report(RenderRegionManager regions, int x, int y, int z) {
        var region = regions.getForChunk(x, y, z);
        if (region != null && !VisibleVanillaSections.inRenderDistance(x, y, z)) return null;
        VisibleVanillaSections.visit(region, x, y, z);
        return region;
    }
}
