package rodozodo.infinite.hook.minecraft;

import rodozodo.infinite.LodKeyHolder;
import rodozodo.infinite.LodWorldKey;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public class LevelHook implements LodKeyHolder {
    @Unique private @Nullable LodWorldKey infinite$lodKey;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void infinite$identify(WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registries,
                                      Holder<DimensionType> dimensionType, boolean isClientSide, boolean isDebug,
                                      long biomeZoomSeed, int maxChainedNeighborUpdates, CallbackInfo ci) {
        if (dimension == null) return;
        var typeKey = dimensionType == null ? null : dimensionType.unwrapKey().orElse(null);
        this.infinite$lodKey = new LodWorldKey(dimension, biomeZoomSeed, typeKey);
    }

    @Override
    public @Nullable LodWorldKey infinite$lodKey() {
        return this.infinite$lodKey;
    }
}