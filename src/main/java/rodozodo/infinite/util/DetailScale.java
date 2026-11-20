package rodozodo.infinite.util;

public final class DetailScale {
    public static final float LARGEST_PIXELS = 256;
    public static final float SMALLEST_PIXELS = 27;
    public static final int LAST_STEP = 13;

    private DetailScale() {}

    public static float leastPixels() {
        return SMALLEST_PIXELS;
    }

    public static int finestStep() {
        return LAST_STEP;
    }

    public static float pixelsAt(int step) {
        return (float) (LARGEST_PIXELS / Math.pow(2, step / 4.0));
    }

    public static int stepFor(float pixels) {
        long step = Math.round(4 * Math.log(LARGEST_PIXELS / pixels) / Math.log(2));
        return (int) Math.clamp(step, 0, LAST_STEP);
    }
}
