package rodozodo.infinite.client;

import com.mojang.renderpearl.api.device.GpuDevice;
import rodozodo.infinite.Infinite;
import rodozodo.infinite.Log;
import rodozodo.infinite.client.backend.GraphicsBackendGate;
import rodozodo.infinite.client.command.InfiniteCommands;
import rodozodo.infinite.client.ingest.ClientIngest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;


public final class InfiniteClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Log.setSink(InfiniteClient::showLogLine);
        Log.copyTo(FabricLoader.getInstance().getGameDir().resolve("logs").resolve("infinite.log"));
        DebugScreenLines.register();
        ClientTickEvents.END_CLIENT_TICK.register(ClientIngest::tick);
        SingleplayerGenerate.register();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> {
            if (Infinite.available()) InfiniteCommands.register(dispatcher);
        });
    }

    public static void deviceReady(GpuDevice device) {
        if (!FabricLoader.getInstance().isModLoaded("sodium")) {
            Log.warn("Infinite disabled: missing Sodium dependency");
            return;
        }
        if (GraphicsBackendGate.decide(device).enabled) {
            Infinite.enable(() -> ClientLodEngine.create(LodSession.server()));
        }
    }

    private static void showLogLine(String line) {
        var minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        var text = Component.literal(line);
        minecraft.execute(() -> {
            if (minecraft.player != null) minecraft.gui.hud.getChat().addClientSystemMessage(text);
        });
    }
}