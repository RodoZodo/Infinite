package rodozodo.infinite.client.hook.minecraft;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


// Reads the concrete GPU backend behind the frontend device, to tell Vulkan from OpenGL

@Mixin(FrontendGpuDevice.class)
public interface FrontendGpuDeviceAccess {
    @Accessor("backend")
    GpuDeviceBackend infinite$getBackend();
}
