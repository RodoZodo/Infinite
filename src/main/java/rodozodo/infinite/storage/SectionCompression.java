package rodozodo.infinite.storage;

import java.util.Locale;

public final class SectionCompression {
    // Persisted in record headers; never reuse or reassign (ID 1 is retired).
    public static final int ZSTD = 0;
    public static final int LZ4 = 2;

    public static final String DEFAULT = "zstd";
    public static final String LZ4_NAME = "lz4";

    private SectionCompression() {}

    public static boolean known(int codec) {
        return codec == ZSTD || codec == LZ4;
    }

    public static boolean available(int codec) {
        return known(codec);
    }

    public static String defaultName() {
        return DEFAULT;
    }

    public static String name(int codec) {
        return switch (codec) {
            case ZSTD -> DEFAULT;
            case LZ4 -> LZ4_NAME;
            default -> null;
        };
    }

    public static int parse(String name) {
        if (name == null) return -1;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case DEFAULT -> ZSTD;
            case LZ4_NAME -> LZ4;
            default -> -1;
        };
    }
}
