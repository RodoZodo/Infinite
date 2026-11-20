package rodozodo.infinite.client.model;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;


// Single-block {@link BlockAndTintGetter} used to query Minecraft block tints and render isolated fluid faces.
// Positions beyond the {@link #open} direction report air so only that side's fluid face is tesselated.

final class OneBlockLevel implements BlockAndTintGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    BlockState state = AIR;
    Biome biome;
    @Nullable Direction open;
    boolean askedForBiome;

    OneBlockLevel(Biome biome) {
        this.biome = biome;
    }

    OneBlockLevel reset(BlockState state, Biome biome, @Nullable Direction open) {
        this.state = state;
        this.biome = biome;
        this.open = open;
        this.askedForBiome = false;
        return this;
    }

    private boolean isAir(BlockPos pos) {
        if (this.open == null) return false;
        var normal = this.open.getUnitVec3i();
        return normal.getX() * pos.getX() + normal.getY() * pos.getY() + normal.getZ() * pos.getZ() > 0;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        this.askedForBiome = true;
        return resolver.getColor(this.biome, 0, 0);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.isAir(pos) ? AIR : this.state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return CardinalLighting.DEFAULT;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return LevelLightEngine.EMPTY;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return 0;
    }

    @Override
    public int getHeight() {
        return 0;
    }

    @Override
    public int getMinY() {
        return 0;
    }
}
