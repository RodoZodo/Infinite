package rodozodo.infinite.client.render;

import org.joml.Matrix4fc;

public final class ViewMotion {
    private static final long SETTLE_NANOS = 250_000_000L;
    private static final double TURN_SPEED = Double.parseDouble(System.getProperty("infinite.turnDegreesPerSecond", "30"));
    private static final double TURN_ALLOWANCE = 1;

    private static float m00, m01, m02, m10, m11, m12;
    private static volatile long lastTurned = System.nanoTime();
    private static long lastFrame = System.nanoTime();
    private static double excess;

    private ViewMotion() {}

    static void frame(Matrix4fc modelView) {
        float a = modelView.m00(), b = modelView.m01(), c = modelView.m02();
        float d = modelView.m10(), e = modelView.m11(), f = modelView.m12();
        long now = System.nanoTime();
        double seconds = (now - lastFrame) / 1e9;
        lastFrame = now;
        double da = a - m00, db = b - m01, dc = c - m02, dd = d - m10, de = e - m11, df = f - m12;
        double degrees = Math.toDegrees(Math.sqrt(da * da + db * db + dc * dc + dd * dd + de * de + df * df));
        excess = Math.max(0, excess + degrees - TURN_SPEED * seconds);
        if (excess >= TURN_ALLOWANCE) {
            lastTurned = now;
            excess = TURN_ALLOWANCE;
        }
        m00 = a;
        m01 = b;
        m02 = c;
        m10 = d;
        m11 = e;
        m12 = f;
    }

    public static boolean still() {
        return System.nanoTime() - lastTurned > SETTLE_NANOS;
    }
}
