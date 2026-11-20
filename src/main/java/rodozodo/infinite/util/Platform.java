package rodozodo.infinite.util;

import java.util.Locale;

public final class Platform {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final String ARCH = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

    private Platform() {}

    public static boolean isMac() {
        return OS.contains("mac") || OS.contains("darwin");
    }

    public static boolean isAppleSilicon() {
        return isMac() && (ARCH.equals("aarch64") || ARCH.equals("arm64"));
    }

    public static String describe() {
        if (isAppleSilicon()) return "Apple Silicon Mac";
        if (isMac()) return "Mac";
        return System.getProperty("os.name", "unknown platform");
    }
}