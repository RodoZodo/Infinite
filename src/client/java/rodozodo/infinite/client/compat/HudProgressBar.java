package rodozodo.infinite.client.compat;

import rodozodo.infinite.client.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

import java.util.UUID;
import java.util.function.Consumer;

// Manages a single boss bar in the HUD that can be updated from any thread

final class HudProgressBar {
    private final LerpingBossEvent bar;

    // Client thread only.
    private boolean inHud;

    HudProgressBar() {
        this.bar = new LerpingBossEvent(UUID.randomUUID(), Component.empty(), 0,
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS, false, false, false);
    }

    void show(Component label, float fraction) {
        if (!ClientSettings.get().importProgress) {
            hide();
            return;
        }
        onClient(minecraft -> {
            setInHud(minecraft, true);
            this.bar.setProgress(fraction);
            this.bar.setName(label);
        });
    }

    void hide() {
        onClient(minecraft -> setInHud(minecraft, false));
    }

    void end(Component message) {
        onClient(minecraft -> {
            setInHud(minecraft, false);
            minecraft.gui.hud.getChat().addClientSystemMessage(message);
        });
    }

    private void setInHud(Minecraft minecraft, boolean want) {
        if (want == this.inHud) return;
        this.inHud = want;
        if (want) {
            minecraft.gui.hud.getBossOverlay().events.put(this.bar.getId(), this.bar);
        } else {
            minecraft.gui.hud.getBossOverlay().events.remove(this.bar.getId());
        }
    }

    private static void onClient(Consumer<Minecraft> action) {
        var minecraft = Minecraft.getInstance();
        minecraft.execute(() -> action.accept(minecraft));
    }
}