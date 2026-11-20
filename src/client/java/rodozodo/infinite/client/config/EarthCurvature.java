package rodozodo.infinite.client.config;

import com.google.gson.JsonElement;

import java.util.Locale;

/**
 * Converts the Earth Curvature percentage setting into the shader's quadratic vertical drop
 * per block of distance squared.
 */

public final class EarthCurvature {
    public static final int MAX_PERCENT = 100;
    public static final double EARTH_RADIUS_BLOCKS = 6_371_000.0;

    private EarthCurvature() {}

    public static float dropPerBlockSquared(int percent) {
        if (percent <= 0) return 0;
        return (float) (percent * (0.5 / EARTH_RADIUS_BLOCKS));
    }

    static int parseSetting(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return switch (element.getAsString().toLowerCase(Locale.ROOT)) {
                case "off" -> 0;
                case "earth" -> 1;
                case "small" -> 10;
                case "tiny" -> 100;
                default -> throw new IllegalArgumentException("Unknown earth_curvature key: " + element);
            };
        }
        return element.getAsInt();
    }
}
