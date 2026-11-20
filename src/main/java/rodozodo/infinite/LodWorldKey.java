package rodozodo.infinite;

import rodozodo.infinite.storage.WorldFolderName;
import rodozodo.infinite.world.LodWorld;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;

public final class LodWorldKey {
    private static final Identifier NO_TYPE = Identifier.fromNamespaceAndPath(Infinite.MOD_ID, "null_dimension_id");

    public final ResourceKey<Level> dimension;
    public final Identifier dimensionType;
    public final long biomeSeed;
    private final int hash;
    private volatile @Nullable WeakReference<LodWorld> resolved;

    public LodWorldKey(ResourceKey<Level> dimension, long biomeSeed, @Nullable ResourceKey<DimensionType> dimensionType) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.dimensionType = dimensionType == null ? NO_TYPE : dimensionType.identifier();
        this.biomeSeed = biomeSeed;
        this.hash = (dimension.identifier().hashCode() * 31 + this.dimensionType.hashCode()) * 31 + Long.hashCode(biomeSeed);
    }

    public static @Nullable LodWorldKey of(@Nullable Level level) {
        return level == null ? null : ((LodKeyHolder) level).infinite$lodKey();
    }

    public static Component dimensionName(ResourceKey<Level> dimension) {
        var id = dimension.identifier();
        return Component.translatableWithFallback(id.toLanguageKey("dimension"), id.getPath().replace('_', ' '));
    }

    public String storageName() {
        return WorldFolderName.of(this.biomeSeed, this.dimension.identifier().toString(), this.dimensionType.toString());
    }

    @Nullable LodWorld resolvedIn(LodEngine engine) {
        var ref = this.resolved;
        var world = ref == null ? null : ref.get();
        return world != null && world.engine == engine && world.isLive() ? world : null;
    }

    void resolvedTo(LodWorld world) {
        this.resolved = new WeakReference<>(world);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LodWorldKey id && id.hash == this.hash && id.biomeSeed == this.biomeSeed
                && id.dimension.identifier().equals(this.dimension.identifier()) && id.dimensionType.equals(this.dimensionType);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }

    @Override
    public String toString() {
        return this.dimension.identifier() + " (" + this.dimensionType + ", seed " + this.biomeSeed + ")";
    }
}
