package rodozodo.infinite.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import rodozodo.infinite.Infinite;
import rodozodo.infinite.Log;
import rodozodo.infinite.client.compat.SodiumBackpressure;
import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.config.EarthCurvature;
import rodozodo.infinite.client.core.vk.*;
import rodozodo.infinite.client.core.vk.DepthConvention;
import rodozodo.infinite.client.lod.TopLevelRing;
import rodozodo.infinite.client.lod.mesh.MeshBuilder;
import rodozodo.infinite.client.lod.mesh.SectionMesher;
import rodozodo.infinite.client.lod.tree.LodTreeWorker;
import rodozodo.infinite.client.model.BlockModels;
import rodozodo.infinite.thread.WorkerPool;
import rodozodo.infinite.util.Platform;
import rodozodo.infinite.world.IdRegistry;
import rodozodo.infinite.world.LodPos;
import rodozodo.infinite.world.LodWorld;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK13.*;


// Drives Infinites two-pass occlusion-culled LOD rendering pipeline on Minecraft's Vulkan backend for a single world.

public final class LodRenderer {
    private static final int MAX_SECTIONS = 1 << 20;
    private static final int MAX_NODES = 1 << 21;
    static final int NODE_BYTES = 16;
    private static final long STAGING_BYTES = 64L << 20;
    private static final long READBACK_BYTES = 32L << 20;
    private static final float BORDER_SHARE = 0.25f, BORDER_LEAST = 16, BORDER_MOST = 64;
    private static final float CLOUD_THICKNESS = 4;
    private static final boolean FULL = Boolean.getBoolean("infinite.debug.full");
    private static final boolean FALLBACK_OWN_PASS = System.getProperty("infinite.swFallbackPass") == null
            ? Platform.isAppleSilicon() : Boolean.getBoolean("infinite.swFallbackPass");

    public final VisibleVanillaSections visibleSections = new VisibleVanillaSections();

    private final LodWorld world;
    private final VkContext ctx = VkContext.get();
    private final RendererShell shell;
    private final DepthConvention depth;
    private final StagingBuffer staging;
    private final ReadbackBuffer readback;
    private final Samplers samplers;
    private final IndexBuffers indices;
    private final ModelGpuStore models;
    private final BlockModels blockModels;
    private final Consumer<IdRegistry.KnownBiome> biomeListener;
    private final MeshBuilder meshes;
    private final GeometryPages geometry;
    private final VBuffer nodes;
    private final LodTreeWorker tree;
    private final GeometryEvictor evictor;
    private final NodeSync nodeSync;
    private final LodSelector selector;
    private final DepthPyramid pyramid;
    private final VanillaMask vanillaMask;
    private final LodTargets targets;
    private final SectionDrawer drawer;
    private final Compositor compositor;
    private final LodCrystals crystals;
    private final LodAmbientOcclusion ambientOcclusion;
    private final LodTaa taa;
    private final GpuTimings gpuTimings;
    private final TopLevelRing ring;
    private final FrameTimings timings = new FrameTimings();
    private int distanceChunks;

    private record CapturedFrame(Matrix4f projection, Matrix4f modelView, FogParameters fog, RenderTarget target, double x, double y, double z) {}
    private @Nullable CapturedFrame captured;
    private int cameraFrames;
    private long frames;
    private static final long REST_AFTER_NANOS = Long.getLong("infinite.idleRestSeconds", 10) * 1_000_000_000L;
    private static final double QUIET_SHARE = 0.02;
    private static final int WAKE_BACKLOG = 64;
    private long meshedBusy = -1, meshedAt;
    private boolean sectionsResting;
    private int devSelected = -1;
    private boolean worldReleased;

    public LodRenderer(LodWorld world, WorkerPool workers, @Nullable RendererShell kept) {
        world.acquireRef();
        this.world = world;
        var undo = new ArrayDeque<Runnable>();
        try {
            if (kept != null) {
                this.shell = kept;
            } else {
                this.shell = new RendererShell(MAX_SECTIONS, (long) NodeSync.LEAST_NODES * NODE_BYTES, STAGING_BYTES, READBACK_BYTES);
            }
            undo.push(this.shell::free);
            this.depth = this.shell.depth;
            this.staging = this.shell.staging;
            this.readback = this.shell.readback;
            this.samplers = this.shell.samplers;
            this.indices = this.shell.indices;
            this.geometry = this.shell.geometry;
            this.nodes = this.shell.nodes;
            this.pyramid = this.shell.pyramid;
            this.vanillaMask = this.shell.vanillaMask;
            this.targets = this.shell.targets;
            this.compositor = this.shell.compositor;
            this.crystals = this.shell.crystals;
            this.ambientOcclusion = this.shell.ambientOcclusion;
            this.taa = this.shell.taa;
            this.gpuTimings = this.shell.gpuTimings;
            var setup = new SetupCommands();

            this.models = new ModelGpuStore(setup, this.staging);
            undo.push(this.models::free);
            // Reset persistent buffers when reusing a shell from the previous world.
            setup.fill(this.geometry.getMetadataBuffer(), 0);
            this.geometry.setSectionCount(0);
            setup.fill(this.nodes, -1);
            this.blockModels = new BlockModels(world.ids(), this.models);
            undo.push(this.blockModels::shutdown);
            var level = Minecraft.getInstance().level;
            boolean cullCaves = ClientSettings.get().caveCulling && (level == null || level.dimensionType().hasSkyLight());
            this.meshes = new MeshBuilder(world, this.blockModels, workers, cullCaves);
            undo.push(this.meshes::shutdown);

            this.tree = new LodTreeWorker(MAX_NODES, MAX_SECTIONS, this.geometry.getGeometryCapacityBytes(), this.geometry.pageBytes(), this.meshes);
            this.evictor = new GeometryEvictor(setup, this.tree, this.staging, this.readback);
            undo.push(this.evictor::free);
            this.selector = new LodSelector(setup, this.depth, this.tree, this.meshes, this.evictor, this.nodes,
                    this.geometry.getMetadataBuffer(), this.staging, this.readback);
            undo.push(this.selector::free);
            this.drawer = new SectionDrawer(setup, this.depth, this.models, this.geometry, this.staging, this.readback,
                    this.indices, this.samplers, this.targets.depthStencilFormat, this.selector.selectedFades);
            undo.push(this.drawer::free);
            this.nodeSync = new NodeSync(this.geometry, this.nodes, this.staging, this.evictor, this.selector, this.drawer, MAX_NODES);
            undo.push(this.nodeSync::free);

            setup.submit(this.ctx, this.staging);
            // Flush GPU work submitted above before tearing down anything on subsequent failure.
            undo.push(this.ctx::flushSubmit);

            world.setUpdateListener(this.tree::worldEvent);
            undo.push(() -> world.setUpdateListener(null));
            this.biomeListener = this.blockModels::addBiome;
            world.ids().addBiomeListener(this.biomeListener);
            undo.push(() -> world.ids().removeBiomeListener(this.biomeListener));
            this.tree.start();
            undo.push(this.tree::stop);

            this.ring = createRing(this.tree);
            this.setRenderDistance(ClientSettings.get().lodDistanceChunks());
        } catch (RuntimeException e) {
            while (!undo.isEmpty()) {
                try {
                    undo.pop().run();
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            world.releaseRef();
            throw e;
        }
        this.ctx.savePipelineCache();
    }

    private static TopLevelRing createRing(LodTreeWorker tree) {
        var level = Minecraft.getInstance().level;
        int shift = 5 + LodPos.MAX_LEVEL;
        return new TopLevelRing(40, level.getMinY() >> shift, level.getMaxY() >> shift, tree::addTopLevel, tree::removeTopLevel);
    }

    public void captureFrame(Matrix4fc projection, Matrix4fc modelView, FogParameters fog, RenderTarget target, double x, double y, double z) {
        this.captured = new CapturedFrame(new Matrix4f(projection), new Matrix4f(modelView), fog, target, x, y, z);
    }

    private static float vanillaDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }

    public void renderFrame() {
        long frameStart = System.nanoTime();
        long lodNanos = 0;
        var frame = this.captured;
        this.captured = null;
        var cb = this.ctx.beginFrameCommands();
        VBarriers.full(cb);

        double x, z;
        if (frame != null && frame.target.width > 0 && frame.target.height > 0) {
            long lodStart = System.nanoTime();
            var camera = this.cameraFor(frame);
            this.renderLods(cb, camera, frame.target);
            if (this.selector.animationsDrawn()) this.blockModels.markAnimationsActive();
            lodNanos = System.nanoTime() - lodStart;
            this.timings.lods.add(lodNanos);
            x = frame.x;
            z = frame.z;
        } else {
            this.nodeSync.apply(cb, this.tree);
            var camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
            x = camera.x;
            z = camera.z;
        }

        long upkeepStart = System.nanoTime();
        this.ring.update(x, z);
        this.meshes.tick();
        this.restIdleSections();
        this.blockModels.tick(this.models.reserveModels(cb, this.blockModels.modelCount()));
        this.staging.recordCopies(cb);
        this.nodeSync.recordMoves(cb);
        this.readback.recordCopies(cb);
        this.gpuTimings.mark(cb, "tail");
        VBarriers.full(cb);
        this.ctx.executeInFrame(cb);
        StagingBuffer.endFrames(this.staging, this.readback);
        long end = System.nanoTime();
        this.timings.upkeep.add(end - upkeepStart);
        this.timings.frame.add(end - frameStart);
        this.frames++;
    }

    private CameraState cameraFor(CapturedFrame frame) {
        var cameraProjection = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        float vanilla = vanillaDistance();
        float near = vanilla <= 32 ? 8 : 16;
        float[] jitter = this.taa.jitter(frame.target.width, frame.target.height);
        return new CameraState(this.depth, ++this.cameraFrames, frame.target.width, frame.target.height, frame.projection,
                cameraProjection, frame.modelView, frame.fog, frame.x, frame.y, frame.z, near,
                EarthCurvature.dropPerBlockSquared(ClientSettings.get().earthCurvature), vanilla, vanilla, jitter[0], jitter[1]);
    }

    private void renderLods(VkCommandBuffer cb, CameraState camera, RenderTarget target) {
        var mcDepthFormat = target.getDepthTexture().getFormat();
        if (mcDepthFormat.hasStencilAspect()) {
            throw new IllegalStateException("Infinite expects Minecraft's main depth target without stencil, got " + mcDepthFormat);
        }
        long mcColour = ((VulkanGpuTextureView) target.getColorTextureView()).vkImageView();
        long mcDepth = ((VulkanGpuTextureView) target.getDepthTextureView()).vkImageView();
        long lightmap = ((VulkanGpuTextureView) Minecraft.getInstance().gameRenderer.levelLightmap()).vkImageView();

        this.gpuTimings.beginFrame(cb);
        SodiumSight.update(camera.x, camera.z, this.visibleSections.positions(), this.visibleSections.count());
        ViewMotion.frame(camera.modelView);
        this.vanillaMask.render(cb, camera, this.visibleSections);
        this.gpuTimings.mark(cb, "mask");
        float cloudBase = 0, cloudTop = 0;
        var gameState = Minecraft.getInstance().gameRenderer.gameRenderState();
        if (gameState.optionsRenderState.cloudStatus != CloudStatus.OFF && ARGB.alpha(gameState.levelRenderState.cloudColor) > 0) {
            cloudBase = gameState.levelRenderState.cloudHeight - 1;
            cloudTop = gameState.levelRenderState.cloudHeight + CLOUD_THICKNESS + 1;
        }
        float borderEnd = vanillaDistance(), borderStart = borderEnd;
        if (ClientSettings.get().smoothBorder) borderStart -= Math.clamp(borderEnd * BORDER_SHARE, BORDER_LEAST, BORDER_MOST);
        this.targets.prepare(cb, camera, mcDepth, target.width, target.height, cloudBase, cloudTop, borderStart, borderEnd);
        this.gpuTimings.mark(cb, "seed");

        this.drawer.beginFrame(camera);
        this.staging.recordCopies(cb);
        this.gpuTimings.mark(cb, "upload");
        boolean software = this.drawer.softwareRaster();
        if (software) {
            this.drawer.rasterSoftware(cb, this.targets, lightmap, this.vanillaMask.view(), this.targets.borderView(), 0,
                    () -> this.gpuTimings.mark(cb, "sw keys"));
            this.gpuTimings.mark(cb, "sw raster");
        }
        this.targets.beginPass(cb, true);
        if (software) {
            this.drawer.resolveSoftware(cb, lightmap, this.vanillaMask.view(), this.targets.borderView(), 0);
            if (FALLBACK_OWN_PASS) {
                VRendering.end(cb);
                this.gpuTimings.mark(cb, "sw resolve");
                VBarriers.attachmentWrites(cb);
                this.targets.beginPass(cb, false);
            }
            this.drawer.drawSoftwareFallback(cb, lightmap, this.vanillaMask.view(), this.targets.borderView(), 0);
            VRendering.end(cb);
            this.gpuTimings.mark(cb, FALLBACK_OWN_PASS ? "sw fallback" : "sw resolve");
        } else if (this.gpuTimings.measuring()) {
            this.drawer.drawSolid(cb, lightmap, this.vanillaMask.view(), this.targets.borderView());
            VRendering.end(cb);
            this.gpuTimings.mark(cb, "solid");
            VBarriers.attachmentWrites(cb);
            this.targets.beginPass(cb, false);
            this.drawer.drawMasked(cb, lightmap, this.vanillaMask.view(), this.targets.borderView());
            VRendering.end(cb);
            this.gpuTimings.mark(cb, "masked");
        } else {
            this.drawer.drawOpaque(cb, lightmap, this.vanillaMask.view(), this.targets.borderView());
            VRendering.end(cb);
            this.gpuTimings.mark(cb, "opaque");
        }

        VBarriers.attachmentWrites(cb);
        this.pyramid.build(cb, this.targets.depthView(), this.targets.width(), this.targets.height());
        this.gpuTimings.mark(cb, "pyramid");
        this.nodeSync.apply(cb, this.tree);
        this.staging.recordCopies(cb);
        this.nodeSync.recordMoves(cb);
        this.evictor.update(cb, this.nodes);
        this.selector.select(cb, camera, this.pyramid);
        this.gpuTimings.mark(cb, "select");

        this.drawer.buildDraws(cb, this.selector.selected, this.targets);
        this.gpuTimings.mark(cb, "cull");
        if (software) {
            this.drawer.rasterSoftware(cb, this.targets, lightmap, this.vanillaMask.view(), this.targets.borderView(), 1,
                    () -> this.gpuTimings.mark(cb, "sw keys new"));
            this.gpuTimings.mark(cb, "sw raster new");
        }
        this.targets.beginPass(cb, false);
        if (software) {
            this.drawer.resolveSoftware(cb, lightmap, this.vanillaMask.view(), this.targets.borderView(), 1);
            if (FALLBACK_OWN_PASS) {
                VRendering.end(cb);
                this.gpuTimings.mark(cb, "new resolve");
                VBarriers.attachmentWrites(cb);
                this.targets.beginPass(cb, false);
            }
            this.drawer.drawSoftwareFallback(cb, lightmap, this.vanillaMask.view(), this.targets.borderView(), 1);
        } else {
            this.drawer.drawNewlyVisible(cb, lightmap, this.vanillaMask.view(), this.targets.borderView());
        }
        var shade = this.ambientOcclusion.shadeFor(camera, this.targets, vanillaDistance());
        if (shade != null) {
            VRendering.end(cb);
            this.gpuTimings.mark(cb, "new");
            this.ambientOcclusion.draw(cb, camera, this.targets, shade);
            this.gpuTimings.mark(cb, "ao");
            this.targets.beginPass(cb, false);
        }
        this.drawer.drawTranslucent(cb, lightmap, this.vanillaMask.view(), this.targets.borderView());
        var level = Minecraft.getInstance().level;
        if (level != null) this.crystals.draw(cb, camera, level, lightmap);
        VRendering.end(cb);
        this.gpuTimings.mark(cb, shade != null ? "blend" : "new + blend");
        this.drawer.requestBlockCounts(this.readback);
        if (this.frames % 60 == 0) {
            this.drawer.requestDevStats(this.readback);
            this.readback.request(this.selector.selected, 0, 4, (address, size) -> this.devSelected = MemoryUtil.memGetInt(address));
        }

        int colourFormat = VulkanConst.toVk(target.getColorTexture().getFormat());
        int depthFormat = VulkanConst.toVk(mcDepthFormat);
        if (this.compositor.draws(camera, vanillaDistance())) {
            VBarriers.attachmentWrites(cb);
            long colour = this.targets.colourView();
            boolean resolved = this.taa.active();
            if (resolved) {

                // Ensure border image writes from the blended pass are visible to TAA resolve.
                VBarriers.memory(cb, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                colour = this.taa.resolve(cb, camera, this.targets);
                this.gpuTimings.mark(cb, "taa");
            }
            VRendering.begin(cb, target.width, target.height, mcColour, mcDepth, false, VRendering.Clear.NONE);
            this.compositor.record(cb, camera, this.targets, colour, resolved, this.taa.sharpness(), colourFormat,
                    depthFormat, vanillaDistance());
            VRendering.end(cb);
            this.gpuTimings.mark(cb, "composite");
        }
    }

    public void setRenderDistance(int chunks) {
        this.distanceChunks = chunks;
        this.ring.setRadius(Math.ceilDiv(chunks, 32) + 1);
    }

    public void addSodiumDebugInfo(List<String> lines) {
        lines.add("Infinite: " + this.tree.sectionCount() + " LoD sections, " + this.selector.topNodeCount()
                + " top nodes, geometry " + (this.tree.usedGeometryBytes() >> 20) + "/"
                + (this.tree.geometryCapacity() >> 20) + " MiB");
        lines.add("Infinite: " + this.meshes.backlog() + " LoD meshes pending, horizon " + this.distanceChunks + " chunks");
    }

    private void restIdleSections() {
        if (REST_AFTER_NANOS <= 0) return;
        long now = System.nanoTime(), busy = this.meshes.busyNanos();
        if (this.meshedBusy < 0) {
            this.meshedBusy = busy;
            this.meshedAt = now;
            return;
        }
        if (this.sectionsResting && this.meshes.backlog() >= WAKE_BACKLOG) {
            this.sectionsResting = false;
            this.world.restIdle(false);
        }
        if (now - this.meshedAt < REST_AFTER_NANOS) return;
        boolean quiet = busy - this.meshedBusy < (now - this.meshedAt) * QUIET_SHARE;
        this.meshedBusy = busy;
        this.meshedAt = now;
        if (quiet != this.sectionsResting) {
            this.sectionsResting = quiet;
            this.world.restIdle(quiet);
        }
    }

    public void addDebugInfo(List<String> lines) {
        var settings = ClientSettings.get();
        lines.add("  Horizon: " + this.distanceChunks + " chunks · detail " + Math.round(settings.detailPixels) + " px");
        var buffers = VBuffer.usage();
        lines.add("  GPU: " + (buffers.bytes() >> 20) + " MiB buffers · " + (this.geometry.allocatedBytes() >> 20)
                + " MiB terrain · " + this.geometry.getSectionCount() + " sections");
        lines.add(this.timings.describe());
        lines.add(this.gpuTimings.describe());
        this.blockModels.addDebugInfo(lines);
        this.meshes.addDebugInfo(lines);
        String stages = SectionMesher.describeStages();
        if (stages != null) lines.add("  " + stages);
        String merges = SectionMesher.describeMergeSizes();
        if (merges != null) lines.add("  " + merges);
        String breaks = SectionMesher.describeMergeBreaks();
        if (breaks != null) lines.add("  " + breaks);
        String cache = this.world == null ? null : this.world.cacheTiming();
        if (cache != null) lines.add("  " + cache);
        lines.add(SodiumBackpressure.describe());
        if (FULL) {
            long pageBytes = this.geometry.getPageElements() * 8;
            this.drawer.addDebug(lines);
            lines.add(SodiumSight.describe());
            this.tree.addDebugInfo(lines);
            lines.add("  Pages: " + (this.geometry.allocatedBytes() / Math.max(1, pageBytes)) + "/" + this.geometry.pageCount()
                    + " x " + (pageBytes >> 20) + " MiB · " + buffers.buffers() + " buffers · " + this.frames + " frames"
                    + (this.devSelected >= 0 ? " · selected " + this.devSelected : ""));
            lines.add("  Buffers (MiB): " + VBuffer.largest(10));
            lines.add("  Images (MiB): " + VImage.largest(10));
            lines.add("  Section cache: " + this.world.idleMemory());
        }
    }

    public void shutdown() {
        try {
            this.stopWorld(false);
        } catch (Exception e) {
            Log.error("Error while shutting down the LoD renderer", e);
        }
        this.shell.free();
        this.world.releaseRef();
    }

    public @Nullable RendererShell shutdownKeepingShell() {
        try {
            this.stopWorld(true);
            this.taa.forgetHistory();
            return this.shell;
        } catch (Exception e) {
            Log.error("Error while shutting down the LoD renderer", e);
            this.shell.free();
            if (!this.worldReleased) this.world.releaseRef();
            return null;
        }
    }

    public record Stats(int sections, long gpuBytes, long terrainBytes, int pendingMeshes) {}

    public Stats stats() {
        return new Stats(
                this.geometry.getSectionCount(),
                VBuffer.usage().bytes(),
                this.geometry.allocatedBytes(),
                this.meshes.backlog()
        );
    }

    public static void awaitShutdowns() {
        MeshBuilder.awaitStopping();
    }

    private void stopWorld(boolean keepShell) {
        // Submit pending work before draining readbacks so fences and in-flight buffers stay valid.
        this.ctx.flushSubmit();
        this.readback.drain();
        if (keepShell) {
            // Flush staging ring copies into buffers before freeing them while the staging ring outlives this renderer.
            new SetupCommands().submit(this.ctx, this.staging);
            this.ctx.flushSubmit();
        }
        this.world.setUpdateListener(null);
        this.world.ids().removeBiomeListener(this.biomeListener);
        if (keepShell) {
            var tree = this.tree;
            var world = this.world;
            this.worldReleased = true;
            this.meshes.shutdownInBackground(() -> {
                tree.stop();
                world.releaseRef();
            });
        } else {
            this.meshes.shutdown();
            this.tree.stop();
            if (keepShell) {
                this.worldReleased = true;
                this.world.releaseRef();
            }
        }
        this.blockModels.shutdown();
        this.models.free();
        this.nodeSync.free();
        this.selector.free();
        this.evictor.free();
        this.drawer.free();
    }

    private static final class FrameTimings {
        static final class Average {
            private double millis;

            void add(long nanos) {
                double sample = nanos / 1e6;
                this.millis = Math.max(sample, this.millis * 0.96 + sample * 0.04);
            }
        }

        final Average frame = new Average(), lods = new Average(), upkeep = new Average();

        String describe() {
            return String.format("  CPU: %.2f ms (passes %.2f, upkeep %.2f)", this.frame.millis, this.lods.millis, this.upkeep.millis);
        }
    }
}
