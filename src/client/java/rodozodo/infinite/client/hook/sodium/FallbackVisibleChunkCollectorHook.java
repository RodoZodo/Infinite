package rodozodo.infinite.client.hook.sodium;

import rodozodo.infinite.client.render.VisibleVanillaSections;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.FallbackVisibleChunkCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Reports each section of Sodiums out of graph render list (camera outside the world, spectator) to the vanilla mask

@Mixin(value = FallbackVisibleChunkCollector.class, remap = false)
public class FallbackVisibleChunkCollectorHook {
    @Redirect(method = "visit", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/storage/SectionStorage;getCurrent(III)Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;"))
    private RenderSection infinite$report(SectionStorage storage, int x, int y, int z) {
        var section = storage.getCurrent(x, y, z);
        if (section != null) VisibleVanillaSections.visit(section.getRegion(), x, y, z);
        return section;
    }
}
