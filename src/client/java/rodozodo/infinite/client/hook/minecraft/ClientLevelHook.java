package rodozodo.infinite.client.hook.minecraft;

import rodozodo.infinite.client.ingest.ClientIngest;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Feeds every block change the client sees into ingest, so LoDs keep up with edits made after a chunk loaded

@Mixin(ClientLevel.class)
public class ClientLevelHook {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void infinite$blockChanged(BlockPos pos, BlockState before, BlockState after, CallbackInfo ci) {
        if (before != after) ClientIngest.blockChanged(pos);
    }
}
