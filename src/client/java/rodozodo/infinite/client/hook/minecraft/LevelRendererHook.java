package rodozodo.infinite.client.hook.minecraft;

import rodozodo.infinite.Infinite;
import rodozodo.infinite.LodWorldKey;
import rodozodo.infinite.Log;
import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.core.vk.VkContext;
import rodozodo.infinite.client.render.LodRenderer;
import rodozodo.infinite.client.render.LodRendererHolder;
import rodozodo.infinite.client.render.RendererShell;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

// Manages the LOD renderer lifecycle on {@link LevelRenderer} and draws LoDs after the opaque pass while deferring classic transparency until after Lods.
@Mixin(LevelRenderer.class)
public abstract class LevelRendererHook implements LodRendererHolder {
    @Unique private @Nullable LodWorldKey infinite$world;
    @Unique private @Nullable LodRenderer infinite$renderer;
    @Unique private @Nullable RendererShell infinite$keptShell;
    @Unique private @Nullable String infinite$skipReason;
    @Unique private @Nullable ChunkSectionsToRender infinite$heldSections;
    @Unique private FeatureRenderDispatcher.@Nullable PreparedFrame infinite$heldFeatures;
    @Unique private @Nullable RenderTarget infinite$heldTarget;

    @Shadow
    private void executeClassicTransparency(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame features, RenderPass pass) {
        throw new AssertionError();
    }

    @Override
    public @Nullable LodRenderer infinite$renderer() {
        return this.infinite$renderer;
    }

    @Override
    public void infinite$setWorld(@Nullable Level level) {
        var world = level == null ? null : LodWorldKey.of(level);
        if (Objects.equals(this.infinite$world, world)) return;
        var renderer = this.infinite$renderer;
        if (world != null && renderer != null) {
            this.infinite$renderer = null;
            this.infinite$keptShell = renderer.shutdownKeepingShell();
        } else {
            this.infinite$shutdownRenderer();
        }
        this.infinite$world = world;
    }

    @Override
    public void infinite$reloadRenderer() {
        this.infinite$shutdownRenderer(false);
        this.infinite$createRenderer();
    }

    @Override
    public void infinite$createRenderer() {
        if (this.infinite$renderer != null) throw new IllegalStateException("A LOD renderer is already running");
        var kept = this.infinite$keptShell;
        this.infinite$keptShell = null;
        String skip = this.infinite$whyNotRender();
        if (skip != null) {
            if (!skip.equals(this.infinite$skipReason)) Log.info("Not drawing LoD's: " + skip);
            this.infinite$skipReason = skip;
            if (kept != null) kept.free();
            return;
        }
        var world = Infinite.world(this.infinite$world);
        if (world == null || world.engine == null) {
            Log.warn("Not drawing LoD's: could not open LoD world");
            if (kept != null) kept.free();
            return;
        }
        try {
            this.infinite$renderer = new LodRenderer(world, world.engine.workers(), kept);
        } catch (RuntimeException e) {
            Log.error("Could not start LoD renderer; continuing without LoD's", e);
            this.infinite$skipReason = "the renderer failed to start";
            return;
        }
        this.infinite$skipReason = null;
        world.engine.updateDedicatedThreads();
    }

    @Unique
    private @Nullable String infinite$whyNotRender() {
        if (!ClientSettings.get().enabled) return "the mod is turned off";
        if (!ClientSettings.get().isRenderingEnabled()) return "rendering is turned off";
        if (this.infinite$world == null) return "no world";
        if (Infinite.engine() == null) return "no LoD engine";
        if (!VkContext.isCreated()) return "Vulkan is not available";
        return null;
    }

    @Override
    public void infinite$shutdownRenderer() {
        this.infinite$shutdownRenderer(true);
    }

    @Unique
    private void infinite$shutdownRenderer(boolean dropKept) {
        var kept = this.infinite$keptShell;
        if (dropKept && kept != null) {
            this.infinite$keptShell = null;
            kept.free();
        }
        var renderer = this.infinite$renderer;
        if (renderer != null) {
            this.infinite$renderer = null;
            renderer.shutdown();
        }

        // Wait for background renderer shutdowns before closing the world
        if (dropKept) LodRenderer.awaitShutdowns();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void infinite$close(CallbackInfo ci) {
        this.infinite$shutdownRenderer();
    }

    @WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void infinite$holdTransparency(LevelRenderer self, ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame features,
                                          RenderPass pass, Operation<Void> original, @Local(type = RenderTarget.class) RenderTarget target) {
        if (this.infinite$renderer == null) {
            original.call(self, sections, features, pass);
            return;
        }
        this.infinite$heldSections = sections;
        this.infinite$heldFeatures = features;
        this.infinite$heldTarget = target;
    }

    // Injects right after the opaque render pass closes, outside any active pass.
    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;close()V", ordinal = 0, shift = At.Shift.AFTER))
    private void infinite$drawLods(CallbackInfo ci, @Local(type = RenderTarget.class) RenderTarget target) {
        var renderer = this.infinite$renderer;
        try {
            if (renderer != null) renderer.renderFrame();
        } finally {
            this.infinite$drawHeldTransparency();
        }
    }

    @Unique
    private void infinite$drawHeldTransparency() {
        var sections = this.infinite$heldSections;
        var features = this.infinite$heldFeatures;
        var target = this.infinite$heldTarget;
        this.infinite$heldSections = null;
        this.infinite$heldFeatures = null;
        this.infinite$heldTarget = null;
        if (sections == null || features == null || target == null) return;
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Infinite transparency after LODs",
                target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            this.executeClassicTransparency(sections, features, pass);
        }
    }
}