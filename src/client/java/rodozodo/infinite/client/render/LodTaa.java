package rodozodo.infinite.client.render;

import rodozodo.infinite.client.config.AntiAliasing;
import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.core.vk.*;
import rodozodo.infinite.client.core.vk.DepthConvention;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.EnumMap;

import static org.lwjgl.vulkan.VK10.*;


final class LodTaa {
    private static final int FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int DISTANCE_FORMAT = VK_FORMAT_R16_SFLOAT;
    private static final boolean GATHER = !"false".equals(System.getProperty("infinite.taaGather"));
    private static final double TELEPORT_BLOCKS = 64;

    private static final int T_DEPTH = 0, T_COLOUR = 1, T_HISTORY = 2, T_BORDER = 3, T_HISTORY_DISTANCE = 4, L_REPROJECTION = 0, L_BLEND = 1,
            L_TO_CAMERA = 2, L_CAMERA_MOVED = 3;

    private record Tier(boolean high, int samples, float still, float moving, float sharpness) {}

    private static Tier tier(AntiAliasing antiAliasing) {
        return switch (antiAliasing) {
            case OFF -> new Tier(false, 1, 1, 1, 0);
            case TAA, TAA_HIGH -> new Tier(antiAliasing == AntiAliasing.TAA_HIGH, 8, 0.1f, 0.45f, 1);
            case TAA_ULTRA -> new Tier(true, 16, 0.05f, 0.35f, 1);
        };
    }

    private final DepthConvention depth;
    private final StagingBuffer staging;
    private final IndexBuffers indices;
    private final Samplers samplers;
    private final String vertex;
    private final EnumMap<AntiAliasing, VGraphicsPipeline> pipelines = new EnumMap<>(AntiAliasing.class);
    private final VImage[] history = new VImage[2], distance = new VImage[2];
    private int width, height;
    private int written;
    private int frames;
    private boolean historyValid;
    private int lastFrameId = -1;
    private AntiAliasing lastQuality;
    private final Matrix4d lastViewProjection = new Matrix4d();
    private double lastX, lastY, lastZ;
    private final Matrix4d reprojection = new Matrix4d(), current = new Matrix4d();
    private final Matrix4f reprojectionFloat = new Matrix4f(), toCameraFloat = new Matrix4f();
    private final float[] jitterScratch = new float[2];

    LodTaa(DepthConvention depth, StagingBuffer staging, IndexBuffers indices, Samplers samplers) {
        this.depth = depth;
        this.staging = staging;
        this.indices = indices;
        this.samplers = samplers;
        this.vertex = VShaderCompiler.load("infinite:frame/fullscreen.vert");
        if (this.active()) this.pipeline(ClientSettings.get().antiAliasing, this.tier());
    }

    private Tier tier() {
        return tier(ClientSettings.get().antiAliasing);
    }

    boolean active() {
        return ClientSettings.get().antiAliasing != AntiAliasing.OFF;
    }

    float[] jitter(int width, int height) {
        if (!this.active()) {
            this.jitterScratch[0] = 0;
            this.jitterScratch[1] = 0;
            return this.jitterScratch;
        }
        int index = this.frames % this.tier().samples() + 1;
        this.jitterScratch[0] = (halton(index, 2) - 0.5f) * 2 / width;
        this.jitterScratch[1] = (halton(index, 3) - 0.5f) * 2 / height;
        return this.jitterScratch;
    }

    private static float halton(int index, int base) {
        float result = 0, fraction = 1;
        for (int i = index; i > 0; i /= base) {
            fraction /= base;
            result += fraction * (i % base);
        }
        return result;
    }

    float sharpness() {
        return this.tier().sharpness();
    }

    void forgetHistory() {
        this.historyValid = false;
    }

    long resolve(VkCommandBuffer cb, CameraState camera, LodTargets targets) {
        var quality = ClientSettings.get().antiAliasing;
        var tier = tier(quality);
        if (this.history[0] == null || this.width != targets.width() || this.height != targets.height()) {
            this.resize(cb, targets.width(), targets.height());
        }
        double moved = Math.max(Math.abs(camera.x - this.lastX), Math.max(Math.abs(camera.y - this.lastY), Math.abs(camera.z - this.lastZ)));
        if (camera.frameId != this.lastFrameId + 1 || quality != this.lastQuality || moved > TELEPORT_BLOCKS) this.historyValid = false;

        // Reproject in double precision so a stationary camera maps back to the exact same pixel.
        this.current.set(camera.steadyViewProjection).invert();
        this.reprojection.set(this.lastViewProjection)
                .translate(camera.x - this.lastX, camera.y - this.lastY, camera.z - this.lastZ)
                .mul(this.current);
        this.reprojectionFloat.set(this.reprojection);
        this.toCameraFloat.set(this.current);

        int read = this.written;
        this.written ^= 1;
        var pipeline = this.pipeline(quality, tier);
        VRendering.begin(cb, this.width, this.height, this.history[this.written].view, this.distance[this.written].view, VK_NULL_HANDLE, false,
                VRendering.Clear.NONE);
        pipeline.bind(cb);
        pipeline.program.texture(T_DEPTH, targets.depthView(), this.samplers.point)
                .texture(T_COLOUR, targets.colourView(), this.samplers.point)
                .texture(T_HISTORY, this.history[read].view, this.samplers.bilinear)
                .texture(T_BORDER, targets.borderView(), this.samplers.point)
                .texture(T_HISTORY_DISTANCE, this.distance[read].view, this.samplers.point)
                .uniformMat4(L_REPROJECTION, this.reprojectionFloat)
                .uniformMat4(L_TO_CAMERA, this.toCameraFloat)
                .uniform3f(L_CAMERA_MOVED, (float) (camera.x - this.lastX), (float) (camera.y - this.lastY), (float) (camera.z - this.lastZ))
                .uniform3f(L_BLEND, tier.still(), tier.moving(), this.historyValid ? 1 : 0);
        pipeline.flush(cb, this.staging);
        vkCmdBindIndexBuffer(cb, this.indices.quads.handle, 0, VK_INDEX_TYPE_UINT16);
        vkCmdDrawIndexed(cb, 6, 1, 0, 0, 0);
        VRendering.end(cb);
        VBarriers.attachmentWrites(cb);

        this.lastViewProjection.set(camera.steadyViewProjection);
        this.lastX = camera.x;
        this.lastY = camera.y;
        this.lastZ = camera.z;
        this.lastFrameId = camera.frameId;
        this.lastQuality = quality;
        this.historyValid = true;
        this.frames++;
        return this.history[this.written].view;
    }

    private VGraphicsPipeline pipeline(AntiAliasing quality, Tier tier) {
        var pipeline = this.pipelines.get(quality);
        if (pipeline == null) {
            var program = VProgram.graphics("lod_taa_" + quality.key, this.vertex, VShaderCompiler.load("infinite:frame/taa.frag"),
                    VDefines.of(this.depth).defineIf("QUALITY_HIGH", tier.high()).defineIf("TAA_GATHER", GATHER), 0);
            pipeline = VGraphicsPipeline.builder(program).colorFormats(FORMAT, DISTANCE_FORMAT).build();
            this.pipelines.put(quality, pipeline);
        }
        return pipeline;
    }

    private void resize(VkCommandBuffer cb, int w, int h) {
        for (int i = 0; i < 2; i++) {
            if (this.history[i] != null) this.history[i].free();
            if (this.distance[i] != null) this.distance[i].free();
            this.history[i] = VImage.create(cb, "lod_taa_history_" + i, FORMAT, w, h, 1,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            this.distance[i] = VImage.create(cb, "lod_taa_distance_" + i, DISTANCE_FORMAT, w, h, 1,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        }
        this.width = w;
        this.height = h;
        this.historyValid = false;
    }

    void free() {
        for (int i = 0; i < 2; i++) {
            if (this.history[i] != null) this.history[i].free();
            if (this.distance[i] != null) this.distance[i].free();
        }
        for (var pipeline : this.pipelines.values()) {
            pipeline.program.free();
            pipeline.free();
        }
    }
}
