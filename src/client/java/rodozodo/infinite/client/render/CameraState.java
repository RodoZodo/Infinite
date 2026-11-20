package rodozodo.infinite.client.render;

import rodozodo.infinite.client.core.vk.DepthConvention;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;


final class CameraState {
    static final float FAR_PLANE = 3000 * 16;

    final int width, height;
    final int frameId;
    final double x, y, z;
    final int sectionX, sectionY, sectionZ;
    final float subX, subY, subZ;
    final Matrix4f vanillaProjection;
    final Matrix4f modelView;
    final Matrix4f projection;
    final Matrix4f viewProjection;
    final Matrix4f steadyViewProjection;
    final float[] clipPlanes;
    final FogParameters fog;
    final float curveDrop, curveStart;
    final float vanillaDistance;
    final float jitterX, jitterY;

    CameraState(DepthConvention depth, int frameId, int width, int height, Matrix4fc vanillaProjection, Matrix4fc rawProjection,
                Matrix4fc modelView, FogParameters fog, double x, double y, double z, float nearPlane, float curveDrop,
                float curveStart, float vanillaDistance, float jitterX, float jitterY) {
        this.width = width;
        this.height = height;
        this.frameId = frameId & 0x7fffffff;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sectionX = Mth.floor(x) >> 5;
        this.sectionY = Mth.floor(y) >> 5;
        this.sectionZ = Mth.floor(z) >> 5;
        this.subX = (float) (x - (this.sectionX << 5));
        this.subY = (float) (y - (this.sectionY << 5));
        this.subZ = (float) (z - (this.sectionZ << 5));
        this.vanillaProjection = new Matrix4f(vanillaProjection);
        this.modelView = new Matrix4f(modelView);
        this.fog = fog;
        this.curveDrop = curveDrop;
        this.curveStart = curveStart;
        this.vanillaDistance = vanillaDistance;
        this.jitterX = jitterX;
        this.jitterY = jitterY;
        var steady = lodProjection(depth, vanillaProjection, rawProjection, nearPlane, FAR_PLANE);
        this.steadyViewProjection = new Matrix4f(steady).mul(this.modelView);
        this.projection = new Matrix4f().translation(jitterX, jitterY, 0).mul(steady);
        this.viewProjection = new Matrix4f(this.projection).mul(this.modelView);
        this.clipPlanes = clipPlanes(this.viewProjection);
    }

    private static Matrix4f lodProjection(DepthConvention depth, Matrix4fc frameProjection, Matrix4fc cameraProjection, float near, float far) {
        var extra = new Matrix4f(cameraProjection).invert().mul(frameProjection);
        float n = depth.reverseZ() ? far : near;
        float f = depth.reverseZ() ? near : far;
        var remapped = new Matrix4f(cameraProjection);
        if (depth.zeroToOne()) {
            remapped.m22(f / (n - f)).m32(f * n / (n - f));
        } else {
            remapped.m22((f + n) / (n - f)).m32(2 * f * n / (n - f));
        }
        return remapped.mul(extra);
    }

    static float[] clipPlanes(Matrix4fc m) {
        float[] planes = new float[24];
        clipPlanes(m, planes);
        return planes;
    }

    static void clipPlanes(Matrix4fc m, float[] planes) {
        float w0 = m.m03(), w1 = m.m13(), w2 = m.m23(), w3 = m.m33();
        float x0 = m.m00(), x1 = m.m10(), x2 = m.m20(), x3 = m.m30();
        float y0 = m.m01(), y1 = m.m11(), y2 = m.m21(), y3 = m.m31();
        float z0 = m.m02(), z1 = m.m12(), z2 = m.m22(), z3 = m.m32();
        planes[0] = w0 + x0; planes[1] = w1 + x1; planes[2] = w2 + x2; planes[3] = w3 + x3;
        planes[4] = w0 - x0; planes[5] = w1 - x1; planes[6] = w2 - x2; planes[7] = w3 - x3;
        planes[8] = w0 + y0; planes[9] = w1 + y1; planes[10] = w2 + y2; planes[11] = w3 + y3;
        planes[12] = w0 - y0; planes[13] = w1 - y1; planes[14] = w2 - y2; planes[15] = w3 - y3;
        planes[16] = w0 + z0; planes[17] = w1 + z1; planes[18] = w2 + z2; planes[19] = w3 + z3;
        planes[20] = w0 - z0; planes[21] = w1 - z1; planes[22] = w2 - z2; planes[23] = w3 - z3;
    }

    Matrix4f sectionViewProjection() {
        return sectionViewProjection(new Matrix4f());
    }

    Matrix4f sectionViewProjection(Matrix4f dst) {
        return dst.set(this.viewProjection).translate(-this.subX, -this.subY, -this.subZ);
    }
}
