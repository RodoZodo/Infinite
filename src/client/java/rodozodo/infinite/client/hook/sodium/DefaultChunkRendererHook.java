package rodozodo.infinite.client.hook.sodium;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import rodozodo.infinite.client.render.LodRendererHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


// Hands the LoD renderer this frames camera matrices, fog and target, read from Sodiums cutout pass (the last opaque one)
// That pass always runs while Lods are on, even with nothing to draw, so the frame is never missed.

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class DefaultChunkRendererHook extends ShaderChunkRenderer {
    @Shadow @Final private boolean[] shouldDraw;

    public DefaultChunkRendererHook(ChunkVertexType vertexType) {
        super(vertexType);
    }

    @Inject(method = "prepare", at = @At("TAIL"))
    private void infinite$alwaysRunCutout(ChunkRenderListIterable renderLists, CameraTransform camera, boolean indexedRendering, CallbackInfo ci) {
        if (LodRendererHolder.isActive()) {
            this.shouldDraw[DefaultTerrainRenderPasses.getPassIndex(DefaultTerrainRenderPasses.CUTOUT)] = true;
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
    private void infinite$captureFrame(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass pass, CameraTransform camera, FogParameters fog, boolean indexedRendering, RenderPass renderPass, GpuSampler terrainSampler, GpuBufferSlice uniforms, GpuBuffer sectionTimes, OitStage oitStage, CallbackInfo ci) {
        if (pass != DefaultTerrainRenderPasses.CUTOUT) return;
        var renderer = LodRendererHolder.active();
        if (renderer != null) {
            renderer.captureFrame(matrices.projection(), matrices.modelView(), fog, pass.getTarget(), camera.x, camera.y, camera.z);
        }
    }
}
