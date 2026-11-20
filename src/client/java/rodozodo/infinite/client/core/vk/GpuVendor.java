package rodozodo.infinite.client.core.vk;

// Identifies the GPU vendor from its PCI vendor ID.

public enum GpuVendor {
    APPLE("Apple", 0x106B),
    NVIDIA("NVIDIA", 0x10DE),
    AMD("AMD", 0x1002),
    INTEL("Intel", 0x8086),
    MICROSOFT("Microsoft", 0x1414),
    QUALCOMM("Qualcomm", 0x5143),
    ARM("ARM", 0x13B5),
    IMAGINATION("Imagination", 0x1010),
    OTHER("unknown", 0);

    public final String displayName;
    public final int vendorId;

    GpuVendor(String displayName, int vendorId) {
        this.displayName = displayName;
        this.vendorId = vendorId;
    }

    public static GpuVendor of(int vendorId) {
        for (var vendor : values()) {
            if (vendor.vendorId == vendorId) return vendor;
        }
        return OTHER;
    }
}
