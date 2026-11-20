package rodozodo.infinite.util;

import rodozodo.infinite.Log;
import oshi.SystemInfo;

public final class CpuInfo {
    private static volatile int physicalCores;

    private CpuInfo() {}

    public static int physicalCores() {
        int cores = physicalCores;
        if (cores == 0) {
            int logical = Runtime.getRuntime().availableProcessors();
            try {
                cores = new SystemInfo().getHardware().getProcessor().getPhysicalProcessorCount();
            } catch (Throwable e) {
                Log.warn("Could not read the processor layout, counting logical processors", e);
            }
            cores = cores <= 0 ? logical : Math.min(cores, logical);
            physicalCores = cores;
        }
        return cores;
    }
}
