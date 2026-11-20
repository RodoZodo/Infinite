package rodozodo.infinite.client.backend;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import rodozodo.infinite.Log;
import rodozodo.infinite.client.core.vk.InfiniteVkFeatures;
import rodozodo.infinite.client.core.vk.VkContext;
import rodozodo.infinite.client.core.vk.VkSelfTest;
import rodozodo.infinite.client.hook.minecraft.FrontendGpuDeviceAccess;
import net.minecraft.client.PreferredGraphicsApi;
import org.jetbrains.annotations.Nullable;

// Decides once per launch whether Infinite runs, disabling it when Minecraft is on OpenGL

public final class GraphicsBackendGate {
    public enum Backend {VULKAN, OPENGL, UNKNOWN}

    public enum Status {
        UNDECIDED(false, "not initialised", "graphics backend not initialised yet"),
        ENABLED(true, "Vulkan", "running on Vulkan"),
        DISABLED_OPENGL_BACKEND(false, "needs Vulkan, running OpenGL", "the game is running on the OpenGL graphics backend; Infinite requires Vulkan"),
        DISABLED_OPENGL_PREFERRED(false, "needs Vulkan, Prefer OpenGL set", "the Graphics API option is set to \"Prefer OpenGL\"; Infinite requires Vulkan"),
        DISABLED_UNKNOWN_BACKEND(false, "unknown backend", "the active graphics backend is not recognised; Infinite requires Vulkan"),
        DISABLED_VULKAN_INIT_FAILED(false, "Vulkan init failed", "Infinite could not access Minecraft's Vulkan device"),
        DISABLED_MISSING_FEATURES(false, "GPU lacks required Vulkan features", "the GPU/driver does not support Vulkan features Infinite requires"),
        DISABLED_VULKAN_SELFTEST_FAILED(false, "Vulkan self-test failed", "Infinite's Vulkan compute self-test failed on this GPU/driver");

        public final boolean enabled;
        public final String shortReason;
        public final String reason;

        Status(boolean enabled, String shortReason, String reason) {
            this.enabled = enabled;
            this.shortReason = shortReason;
            this.reason = reason;
        }
    }

    private static @Nullable PreferredGraphicsApi effectivePreference;
    private static Backend backend = Backend.UNKNOWN;
    private static Status status = Status.UNDECIDED;
    private static String detail = "";

    private GraphicsBackendGate() {}

    public static void recordPreference(PreferredGraphicsApi preference) {
        effectivePreference = preference;
    }

    public static Status decide(GpuDevice device) {
        if (status != Status.UNDECIDED) {
            return status;
        }
        backend = detectBackend(device);
        Status backendStatus = checkBackend(backend, effectivePreference);
        status = backendStatus != null ? backendStatus : checkVulkan(device);

        if (!status.enabled) {
            boolean backendProblem = backendStatus != null;
            Log.warn("Infinite disabled: " + status.reason + (detail.isEmpty() ? "" : " (" + detail + ")")
                    + (backendProblem ? " - set Video Settings > Graphics API to \"Prefer Vulkan\" or \"Default\" and restart." : ""));
        }
        return status;
    }

    static @Nullable Status checkBackend(Backend backend, @Nullable PreferredGraphicsApi preference) {
        if (backend == Backend.OPENGL) {
            return Status.DISABLED_OPENGL_BACKEND;
        }
        if (preference == PreferredGraphicsApi.OPENGL) {
            return Status.DISABLED_OPENGL_PREFERRED;
        }
        if (backend != Backend.VULKAN) {
            return Status.DISABLED_UNKNOWN_BACKEND;
        }
        return null;
    }

    private static Status checkVulkan(GpuDevice device) {
        VkContext ctx;
        try {
            ctx = VkContext.create(device);
        } catch (Throwable t) {
            Log.error("Could not access Vulkan device", t);
            detail = t.toString();
            return Status.DISABLED_VULKAN_INIT_FAILED;
        }
        if (ctx == null) {
            return Status.DISABLED_UNKNOWN_BACKEND;
        }

        VkSelfTest.Result selfTest = null;
        if (!"false".equalsIgnoreCase(System.getProperty("infinite.vulkan.selfTest"))) {
            selfTest = VkSelfTest.run(ctx);
            if (!selfTest.passed()) {
                Log.error("Vulkan self-test failed: " + selfTest.message());
            }
        }

        var missing = InfiniteVkFeatures.missingRequired(ctx.enabledFeatures);
        if (!missing.isEmpty()) {
            StringBuilder names = new StringBuilder("missing: ");
            for (int i = 0; i < missing.size(); i++) {
                names.append(i == 0 ? "" : ", ").append(missing.get(i).name());
            }
            detail = names.toString();
            return Status.DISABLED_MISSING_FEATURES;
        }
        if (selfTest != null && !selfTest.passed()) {
            detail = selfTest.message();
            return Status.DISABLED_VULKAN_SELFTEST_FAILED;
        }
        return Status.ENABLED;
    }

    private static Backend detectBackend(GpuDevice device) {
        if (device instanceof FrontendGpuDevice) {
            GpuDeviceBackend impl = ((FrontendGpuDeviceAccess) device).infinite$getBackend();
            if (impl instanceof VulkanDevice) return Backend.VULKAN;
        }
        String name = device.getDeviceInfo().backendName();
        if ("Vulkan".equalsIgnoreCase(name)) return Backend.VULKAN;
        if ("OpenGL".equalsIgnoreCase(name)) return Backend.OPENGL;
        return Backend.UNKNOWN;
    }

    public static Status status() {
        return status;
    }

    public static String detail() {
        return detail;
    }

    public static Backend backend() {
        return backend;
    }
}
