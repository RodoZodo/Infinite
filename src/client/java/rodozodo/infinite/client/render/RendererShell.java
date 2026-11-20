package rodozodo.infinite.client.render;

import rodozodo.infinite.Log;
import rodozodo.infinite.client.core.vk.DepthConvention;
import rodozodo.infinite.client.core.vk.ReadbackBuffer;
import rodozodo.infinite.client.core.vk.StagingBuffer;
import rodozodo.infinite.client.core.vk.VBuffer;

import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;


public final class RendererShell {
    final DepthConvention depth;
    final StagingBuffer staging;
    final ReadbackBuffer readback;
    final Samplers samplers;
    final IndexBuffers indices;
    final GeometryPages geometry;
    final VBuffer nodes;
    final DepthPyramid pyramid;
    final VanillaMask vanillaMask;
    final LodTargets targets;
    final Compositor compositor;
    final LodCrystals crystals;
    final LodAmbientOcclusion ambientOcclusion;
    final LodTaa taa;
    final GpuTimings gpuTimings;

    RendererShell(int maxSections, long nodeBytes, long stagingBytes, long readbackBytes) {
        var undo = new ArrayDeque<Runnable>();
        try {
            this.depth = DepthConvention.current();
            this.staging = new StagingBuffer(stagingBytes);
            undo.push(this.staging::free);
            this.readback = new ReadbackBuffer(readbackBytes);
            undo.push(this.readback::free);
            this.samplers = new Samplers();
            undo.push(this.samplers::free);
            this.indices = new IndexBuffers(this.staging);
            undo.push(this.indices::free);
            this.geometry = new GeometryPages(maxSections);
            undo.push(this.geometry::free);
            this.nodes = VBuffer.growable("lod_nodes", nodeBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            undo.push(this.nodes::free);
            this.pyramid = new DepthPyramid(this.depth, this.staging, this.samplers);
            undo.push(this.pyramid::free);
            this.vanillaMask = new VanillaMask(this.depth, this.staging, this.indices);
            undo.push(this.vanillaMask::free);
            this.targets = new LodTargets(this.depth, this.staging, this.indices, this.samplers);
            undo.push(this.targets::free);
            this.compositor = new Compositor(this.depth, this.staging, this.indices, this.samplers);
            undo.push(this.compositor::free);
            this.crystals = new LodCrystals(this.staging, this.samplers, this.depth, this.targets.depthStencilFormat);
            undo.push(this.crystals::free);
            this.ambientOcclusion = new LodAmbientOcclusion(this.depth, this.staging, this.indices, this.samplers);
            undo.push(this.ambientOcclusion::free);
            this.taa = new LodTaa(this.depth, this.staging, this.indices, this.samplers);
            undo.push(this.taa::free);
            this.gpuTimings = new GpuTimings();
        } catch (RuntimeException e) {
            while (!undo.isEmpty()) {
                try {
                    undo.pop().run();
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
    }

    public void free() {
        try {
            this.crystals.free();
            this.ambientOcclusion.free();
            this.taa.free();
            this.gpuTimings.free();
            this.compositor.free();
            this.vanillaMask.free();
            this.targets.free();
            this.pyramid.free();
            this.indices.free();
            this.geometry.free();
            this.nodes.free();
            this.samplers.free();
            this.staging.free();
            this.readback.free();
        } catch (Exception e) {
            Log.error("Error freeing LoD renderer buffers", e);
        }
    }
}
