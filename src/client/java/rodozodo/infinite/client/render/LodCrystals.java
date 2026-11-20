package rodozodo.infinite.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.crystal.EndCrystalModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import rodozodo.infinite.client.config.ClientSettings;
import rodozodo.infinite.client.core.vk.*;

import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;

// Renders end crystals beyond Minecrafts entity draw range using animated poses of Minecraft's crystal model
final class LodCrystals {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/end_crystal/end_crystal.png");
    private static final int MAX_DRAWN = 256;
    private static final int MAX_VERTICES = 4 * 6 * 6;
    private static final int VERTEX_FLOATS = 8;
    private static final int VERTEX_BYTES = VERTEX_FLOATS * Float.BYTES;
    private static final float LEAST_PIXELS = 3;
    private static final float MOST_GROWTH = 6;
    private static final float HEIGHT = 2;
    private static final Vector3f LIGHT_0 = new Vector3f(0.2f, 1.0f, -0.7f).normalize();
    private static final Vector3f LIGHT_1 = new Vector3f(-0.2f, 1.0f, 0.7f).normalize();
    private static final float LIGHT_POWER = 0.6f, AMBIENT_LIGHT = 0.4f;

    private static final int L_VIEW_PROJECTION = 0, L_CURVATURE = 1;

    private final StagingBuffer staging;
    private final Samplers samplers;
    private final VProgram program;
    private final VGraphicsPipeline pipeline;
    private @Nullable EndCrystalModel model;
    private final EndCrystalRenderState state = new EndCrystalRenderState();
    private final PoseStack poses = new PoseStack();
    private final Vector3f normal = new Vector3f(), corner = new Vector3f();
    private long[] order = new long[0];
    private CrystalMarks.Mark[] drawn = new CrystalMarks.Mark[0];
    private long cursor;
    private int written;

    LodCrystals(StagingBuffer staging, Samplers samplers, DepthConvention depth, int depthStencilFormat) {
        this.staging = staging;
        this.samplers = samplers;
        VProgram program = VProgram.graphics("lod_end_crystals", VShaderCompiler.load("infinite:crystals/crystal.vert"),
                VShaderCompiler.load("infinite:crystals/crystal.frag"), VDefines.of(depth), 0);
        try {
            this.pipeline = VGraphicsPipeline.builder(program)
                    .depth(true, true, SectionDrawer.nearerOrEqual(depth))
                    .stencil(VK_COMPARE_OP_EQUAL, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, 0xFF, 0x00)
                    .blend(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorFormats(LodTargets.COLOUR_FORMAT)
                    .depthFormat(depthStencilFormat, true)
                    .build();
        } catch (RuntimeException e) {
            program.free();
            throw e;
        }
        this.program = program;
    }

    void draw(VkCommandBuffer cb, CameraState camera, ClientLevel level, long lightmap) {
        long now = System.nanoTime();
        var marks = CrystalMarks.of(level, now);
        if (marks.isEmpty()) return;
        double lodDistance = ClientSettings.get().renderDistanceBlocks();
        double lodSq = lodDistance * lodDistance;

        if (this.order.length < marks.size()) {
            this.order = new long[marks.size()];
            this.drawn = new CrystalMarks.Mark[marks.size()];
        }
        int count = 0;
        for (var mark : marks.values()) {
            var entity = mark.entity;
            if (entity != null && !entity.isRemoved() && (entity.isInvisible() || entity.shouldRender(camera.x, camera.y, camera.z))) continue;
            double dx = mark.x - camera.x, dz = mark.z - camera.z;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq > lodSq) continue;
            this.drawn[count] = mark;
            this.order[count] = (long) Float.floatToRawIntBits((float) distanceSq) << 32 | count;
            count++;
        }
        if (count == 0) return;
        var view = texture();
        var model = this.model();
        if (view == null || model == null) return;
        Arrays.sort(this.order, 0, count);
        count = Math.min(count, MAX_DRAWN);

        float blocksPerPixel = 2 / (Math.abs(camera.projection.m11()) * camera.height);
        long bytes = (long) count * MAX_VERTICES * VERTEX_BYTES;
        long offset = this.staging.reserve(bytes);
        this.cursor = this.staging.address(offset);
        this.written = 0;
        for (int i = 0; i < count; i++) {
            var mark = this.drawn[(int) this.order[i]];
            double dx = mark.x - camera.x, dz = mark.z - camera.z;
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            float grow = Math.min(MOST_GROWTH, Math.max(1f, LEAST_PIXELS * distance * blocksPerPixel / HEIGHT));
            this.state.ageInTicks = mark.age(now);
            this.state.showsBottom = mark.showsBottom;
            model.setupAnim(this.state);

            this.poses.pushPose();
            this.poses.translate((float) dx, (float) (mark.y - camera.y), (float) dz);
            this.poses.scale(2 * grow, 2 * grow, 2 * grow);
            this.poses.translate(0, -0.5f, 0);
            float block = mark.blockLight, sky = mark.skyLight;
            ModelPart.Visitor visitor = (pose, path, index, cube) -> this.writeCube(pose, cube, block, sky);

            // Visit base explicitly since outerGlass.visit skips nothing for visibility and setupAnim only toggles base.visible
            model.outerGlass.visit(this.poses, visitor);
            if (mark.showsBottom) model.base.visit(this.poses, visitor);
            this.poses.popPose();
        }
        if (this.written == 0) return;

        this.pipeline.bind(cb);
        vkCmdSetStencilReference(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 1);
        this.program.ssbo(0, this.staging.buffer(), offset, (long) this.written * VERTEX_BYTES)
                .texture(1, lightmap, this.samplers.bilinear)
                .texture(2, view.vkImageView(), this.samplers.skin)
                .uniformMat4(L_VIEW_PROJECTION, camera.viewProjection)
                .uniform2f(L_CURVATURE, camera.curveDrop, camera.curveStart);
        this.pipeline.flush(cb, this.staging);
        vkCmdDraw(cb, this.written, 1, 0, 0);
    }

    private void writeCube(PoseStack.Pose pose, ModelPart.Cube cube, float block, float sky) {
        var matrix = pose.pose();
        for (var polygon : cube.polygons) {
            var vertices = polygon.vertices();
            if (vertices.length != 4) continue;
            pose.transformNormal(polygon.normal(), this.normal);
            float shade = Math.min(1, (Math.max(0, this.normal.dot(LIGHT_0)) + Math.max(0, this.normal.dot(LIGHT_1)))
                    * LIGHT_POWER + AMBIENT_LIGHT);
            for (int corner : QUAD_TRIANGLES) {
                var vertex = vertices[corner];
                matrix.transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), this.corner);
                long at = this.cursor + (long) this.written * VERTEX_BYTES;
                MemoryUtil.memPutFloat(at, this.corner.x);
                MemoryUtil.memPutFloat(at + 4, this.corner.y);
                MemoryUtil.memPutFloat(at + 8, this.corner.z);
                MemoryUtil.memPutFloat(at + 12, shade);
                MemoryUtil.memPutFloat(at + 16, vertex.u());
                MemoryUtil.memPutFloat(at + 20, vertex.v());
                MemoryUtil.memPutFloat(at + 24, block);
                MemoryUtil.memPutFloat(at + 28, sky);
                this.written++;
            }
        }
    }

    private static final int[] QUAD_TRIANGLES = {0, 1, 2, 0, 2, 3};

    private @Nullable EndCrystalModel model() {
        if (this.model == null) {
            try {
                this.model = new EndCrystalModel(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.END_CRYSTAL));
            } catch (RuntimeException notLoaded) {
                return null;
            }
        }
        return this.model;
    }

    private static @Nullable VulkanGpuTextureView texture() {
        try {
            var texture = Minecraft.getInstance().getTextureManager().getTexture(TEXTURE);
            return texture.getTextureView() instanceof VulkanGpuTextureView view ? view : null;
        } catch (RuntimeException notLoaded) {
            return null;
        }
    }

    void free() {
        this.pipeline.free();
        this.program.free();
    }
}
