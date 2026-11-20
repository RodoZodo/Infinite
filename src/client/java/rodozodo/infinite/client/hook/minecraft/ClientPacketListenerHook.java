package rodozodo.infinite.client.hook.minecraft;

import rodozodo.infinite.client.LodSession;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


// Starts an Infinite session when the client joins a world, before that worlds first level exists
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerHook {
    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;commonPlayerSpawnInfo()Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;"))
    private void infinite$joinedWorld(ClientboundLoginPacket packet, CallbackInfo ci) {
        LodSession.joined(((ClientPacketListener) (Object) this).getServerData());
    }
}
