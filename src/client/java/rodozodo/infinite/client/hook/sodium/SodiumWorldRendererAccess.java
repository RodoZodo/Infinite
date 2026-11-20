package rodozodo.infinite.client.hook.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reads Sodiums section manager to count the chunk builder threads Infinite may borrow

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface SodiumWorldRendererAccess {
    @Accessor("renderSectionManager")
    RenderSectionManager infinite$sectionManager();
}