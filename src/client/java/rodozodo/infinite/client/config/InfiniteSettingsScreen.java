package rodozodo.infinite.client.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

// Simple ModMenu configuration screen

public final class InfiniteSettingsScreen extends Screen {
    private final Screen parent;

    public InfiniteSettingsScreen(Screen parent) {
        super(Component.translatable("infinite.options.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        var settings = ClientSettings.get();
        int x = this.width / 2, y = this.height / 6;
        this.addRenderableWidget(toggle(x, y, "infinite.options.enabled", () -> settings.enabled, value -> settings.enabled = value));
        this.addRenderableWidget(toggle(x, y + 24, "infinite.options.rendering", () -> settings.rendering, value -> settings.rendering = value));
        this.addRenderableWidget(toggle(x, y + 48, "infinite.options.ingest", () -> settings.ingest, value -> settings.ingest = value));
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(x - 100, this.height - 40, 200, 20).build());
    }

    private Button toggle(int x, int y, String key, Supplier<Boolean> get, Consumer<Boolean> set) {
        return Button.builder(label(key, get.get()), button -> {
            set.accept(!get.get());
            button.setMessage(label(key, get.get()));
            ClientSettings.get().save();
        }).bounds(x - 100, y, 200, 20).build();
    }

    private static Component label(String key, boolean on) {
        return Component.translatable(key).append(Component.literal(": "))
                .append(Component.translatable(on ? "options.on" : "options.off"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 6 - 28, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
