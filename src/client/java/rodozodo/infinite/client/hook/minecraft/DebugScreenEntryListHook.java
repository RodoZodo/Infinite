package rodozodo.infinite.client.hook.minecraft;

import rodozodo.infinite.client.DebugScreenLines;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// Shows Infinites F3 line whenever the debug overlay is open, without the player enabling it first

@Mixin(DebugScreenEntryList.class)
public abstract class DebugScreenEntryListHook {
    @Shadow @Final private List<Identifier> currentlyEnabled;

    @Shadow public abstract boolean isOverlayVisible();

    @Inject(method = "rebuildCurrentList", at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V"))
    private void infinite$addEntry(CallbackInfo ci) {
        if (this.isOverlayVisible() && !this.currentlyEnabled.contains(DebugScreenLines.VERSION)) this.currentlyEnabled.add(DebugScreenLines.VERSION);
    }
}