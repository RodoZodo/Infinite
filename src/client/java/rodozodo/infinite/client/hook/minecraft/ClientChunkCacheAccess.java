package rodozodo.infinite.client.hook.minecraft;

import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


// Exposes the client chunk cache backing storage so Infinite can read chunks just as they unload

@Mixin(ClientChunkCache.class)
public interface ClientChunkCacheAccess {
    @Accessor("storage")
    ClientChunkCache.Storage infinite$storage();
}
