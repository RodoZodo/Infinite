package rodozodo.infinite.client.lod.mesh;

import java.util.Arrays;


// Precomputes the 112 distinct smooth lighting ambient occlusion patterns from 8 neighbor face masks and exports them as a packed lookup table for the terrain vertex shader

public final class AmbientOcclusion {
    static final int DISTINCT = 112;

    private static final byte[] CODES = new byte[256];

    // 2 bits per corner: (0,0), (1,0), (0,1), (1,1) in (u,v). Shader reads 4 codes per uint from glslTable()
    private static final int[] CORNERS = new int[DISTINCT];

    static {
        int[] codeOf = new int[256];
        Arrays.fill(codeOf, -1);
        int count = 0;
        for (int mask = 0; mask < 256; mask++) {
            int levels = cornerLevels(mask);
            if (codeOf[levels] < 0) {
                if (count == DISTINCT) throw new IllegalStateException("More ambient occlusion patterns than codes");
                codeOf[levels] = count;
                CORNERS[count++] = levels;
            }
            CODES[mask] = (byte) codeOf[levels];
        }
        if (count != DISTINCT) throw new IllegalStateException("Expected " + DISTINCT + " ambient occlusion patterns, found " + count);
    }

    private AmbientOcclusion() {}

    public static int code(int mask) {
        return CODES[mask];
    }

    private static int cornerLevels(int mask) {
        return corner(mask & 1, mask >> 2 & 1, mask >> 4 & 1)
                | corner(mask >> 1 & 1, mask >> 2 & 1, mask >> 5 & 1) << 2
                | corner(mask & 1, mask >> 3 & 1, mask >> 6 & 1) << 4
                | corner(mask >> 1 & 1, mask >> 3 & 1, mask >> 7 & 1) << 6;
    }

    private static int corner(int side, int otherSide, int diagonal) {
        return side + otherSide == 2 ? 3 : side + otherSide + diagonal;
    }

    public static int glslWords() {
        return (DISTINCT + 3) / 4;
    }

    public static String glslTable() {
        var table = new StringBuilder();
        for (int word = 0; word < glslWords(); word++) {
            int packed = 0;
            for (int i = 0; i < 4 && word * 4 + i < DISTINCT; i++) {
                packed |= CORNERS[word * 4 + i] << (i * 8);
            }
            if (word > 0) table.append(',');
            table.append("0x").append(Integer.toHexString(packed)).append('u');
        }
        return table.toString();
    }
}
