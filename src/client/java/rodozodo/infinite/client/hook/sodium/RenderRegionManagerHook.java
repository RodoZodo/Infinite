package rodozodo.infinite.client.hook.sodium;

import rodozodo.infinite.client.render.LodRendererHolder;
import net.caffeinemc.mods.sodium.client.gpu.arena.PendingUpload;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Turns off Sodiums fade-in of newly built chunks while LODs are drawn:
// The LODs already show the terrain there, so a fade would only flash the sky through it.

@Mixin(value = RenderRegionManager.class, remap = false)
public class RenderRegionManagerHook {
    @Unique private static final int LONG_AGO = -1_048_576;

    @ModifyArg(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager$PendingSectionMeshUpload;<init>(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;ILnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionMeshParts;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/gpu/arena/PendingUpload;)V"),
            index = 1)
    private int infinite$skipFade(RenderSection section, int relativeBuiltTime, BuiltSectionMeshParts parts, TerrainRenderPass pass, PendingUpload upload) {

        // Sodium uses -1 to mean no fade
        if (relativeBuiltTime == -1) return relativeBuiltTime;
        if (!LodRendererHolder.isActive()) return relativeBuiltTime;
        return LONG_AGO;
    }
}