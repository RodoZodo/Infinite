package rodozodo.infinite.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


public final class WorldFolderName {
    private static final String DOMAIN = "infinite/lod-world/v1";
    private static final char SEPARATOR = '\0';
    private static final int NAME_BYTES = 16;

    private WorldFolderName() {}

    public static String of(long biomeSeed, String dimension, String dimensionType) {
        String input = DOMAIN + SEPARATOR + biomeSeed + SEPARATOR + dimension + SEPARATOR + dimensionType;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, NAME_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java has no SHA-256", e);
        }
    }
}