package rodozodo.infinite.client.core.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;

// Clip-space depth convention (0..1 vs -1..1 and reverse-Z) shared by all LoD passes to match Minecrafts depth buffer

public record DepthConvention(boolean zeroToOne, boolean reverseZ) {
    public static DepthConvention current() {
        boolean reverse = DepthStencilState.DEFAULT.depthTest().equals(CompareOp.GREATER_THAN_OR_EQUAL);
        return new DepthConvention(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(), reverse);
    }

    public float far() {
        return this.reverseZ ? 0 : 1;
    }

    public float near() {
        return this.reverseZ ? 1 : 0;
    }
}
