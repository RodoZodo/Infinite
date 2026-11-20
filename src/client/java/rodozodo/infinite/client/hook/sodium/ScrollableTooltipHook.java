package rodozodo.infinite.client.hook.sodium;

import net.caffeinemc.mods.sodium.client.gui.widgets.ScrollableTooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;


// Suppresses Sodium's empty tooltip box and arrow when an option supplies an empty tooltip component
@Mixin(value = ScrollableTooltip.class, remap = false)
public class ScrollableTooltipHook {
    @Shadow
    @Final
    private List<FormattedCharSequence> content;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void infinite$hideEmptyTooltip(GuiGraphicsExtractor gui, CallbackInfo ci) {
        if (this.content.isEmpty()) ci.cancel();
    }
}
