package rodozodo.infinite.client.core.vk;


import java.util.LinkedHashMap;

// Shader macro definitions, mirroring the GL Shader.Builder define helpers

public final class VDefines extends LinkedHashMap<String, String> {
    public static VDefines of(DepthConvention properties) {
        return new VDefines()
                .defineIf("USE_ZERO_ONE_DEPTH", properties.zeroToOne())
                .defineIf("USE_REVERSE_Z", properties.reverseZ());
    }

    public VDefines define(String name) {
        this.put(name, "");
        return this;
    }

    public VDefines define(String name, int value) {
        this.put(name, Integer.toString(value));
        return this;
    }

    public VDefines define(String name, String value) {
        this.put(name, value);
        return this;
    }

    public VDefines defineIf(String name, boolean condition) {
        if (condition) this.put(name, "");
        return this;
    }

    public VDefines defineIf(String name, boolean condition, int value) {
        if (condition) this.put(name, Integer.toString(value));
        return this;
    }

    public VDefines copy() {
        var copy = new VDefines();
        copy.putAll(this);
        return copy;
    }
}
