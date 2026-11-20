package rodozodo.infinite.client.config;

// Anti-aliasing modes for distant LOD terrain

public enum AntiAliasing {
    OFF("off"),
    TAA("taa"),
    TAA_HIGH("taa_high"),
    TAA_ULTRA("taa_ultra");

    public final String key;

    AntiAliasing(String key) {
        this.key = key;
    }

    public static AntiAliasing byKey(String key) {
        for (AntiAliasing antiAliasing : values()) {
            if (antiAliasing.key.equals(key)) return antiAliasing;
        }
        return TAA;
    }
}
