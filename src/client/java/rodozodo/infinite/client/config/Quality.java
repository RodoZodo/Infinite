package rodozodo.infinite.client.config;

import rodozodo.infinite.util.DetailScale;


/**
 * Quality presets pairing an LOD render distance, screen-space detail threshold, and plant cutout
 * setting, plus {@link #CUSTOM} for manual configuration.
 */

public enum Quality {
    LOW("low", 256, 108),
    MEDIUM("medium", 512, 86),
    HIGH("high", 1024, 64),
    ULTRA("ultra", 2048, 52),
    MAC("mac", 256, 108, false),
    CUSTOM("custom", 0, 0);

    public final String key;
    public final int distanceChunks;
    private final int detailPixels;
    public final boolean plantCutouts;

    Quality(String key, int distanceChunks, int detailPixels) {
        this(key, distanceChunks, detailPixels, true);
    }

    Quality(String key, int distanceChunks, int detailPixels, boolean plantCutouts) {
        this.key = key;
        this.distanceChunks = distanceChunks;
        this.detailPixels = detailPixels;
        this.plantCutouts = plantCutouts;
    }

    public boolean isPreset() {
        return this != CUSTOM;
    }

    public float detailPixels() {
        return Math.max(this.detailPixels, DetailScale.leastPixels());
    }

    public static Quality nearest(int distanceChunks, float detailPixels) {
        int step = DetailScale.stepFor(detailPixels);
        for (Quality quality : values()) {
            if (quality.isPreset() && quality.distanceChunks == distanceChunks
                    && DetailScale.stepFor(quality.detailPixels()) == step) return quality;
        }
        return CUSTOM;
    }

    public static Quality byKey(String key) {
        for (Quality quality : values()) {
            if (quality.key.equals(key)) return quality;
        }
        return CUSTOM;
    }
}
