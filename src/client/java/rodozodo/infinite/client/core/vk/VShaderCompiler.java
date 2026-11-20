package rodozodo.infinite.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;


// Compiles Infinites GLSL (with its {@code #import} lines expanded by {@link ShaderSources}) to SPIR-V for Vulkan 1.2 using the shaderc library Minecraft already ships
public final class VShaderCompiler {
    public enum Stage {
        VERTEX(shaderc_glsl_vertex_shader),
        FRAGMENT(shaderc_glsl_fragment_shader),
        COMPUTE(shaderc_glsl_compute_shader);

        final int shadercKind;

        Stage(int shadercKind) {
            this.shadercKind = shadercKind;
        }
    }

    private static long compiler;

    private VShaderCompiler() {}

    private static synchronized long compiler() {
        if (compiler == 0) {
            compiler = shaderc_compiler_initialize();
            if (compiler == 0) throw new IllegalStateException("Failed to initialise shaderc");
        }
        return compiler;
    }

    public static String load(String id) {
        return ShaderSources.load(id);
    }

    private static final Map<String, byte[]> COMPILED = new ConcurrentHashMap<>();

    // Caller must free the returned buffer with MemoryUtil.memFree
    public static ByteBuffer compileToSpirv(String name, String source, Stage stage, Map<String, String> defines) {
        byte[] spirv = COMPILED.computeIfAbsent(stage + "\n" + new TreeMap<>(defines) + "\n" + source, key -> {
            ByteBuffer compiled = compileNow(name, source, stage, defines);
            try {
                byte[] bytes = new byte[compiled.remaining()];
                compiled.get(compiled.position(), bytes);
                return bytes;
            } finally {
                MemoryUtil.memFree(compiled);
            }
        });
        ByteBuffer copy = MemoryUtil.memAlloc(spirv.length);
        copy.put(0, spirv);
        return copy;
    }

    // Passes source in heap memory because LWJGL's 64 KiB thread stack overflows on large shaders.
    static long compileIntoSpv(long compiler, String source, int kind, String name, long options) {
        ByteBuffer text = MemoryUtil.memUTF8(source, false);
        try (var stack = MemoryStack.stackPush()) {
            return shaderc_compile_into_spv(compiler, text, kind, stack.UTF8(name), stack.UTF8("main"), options);
        } finally {
            MemoryUtil.memFree(text);
        }
    }

    private static ByteBuffer compileNow(String name, String source, Stage stage, Map<String, String> defines) {
        long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            for (var define : defines.entrySet()) {
                shaderc_compile_options_add_macro_definition(options, define.getKey(), define.getValue());
            }

            long result = compileIntoSpv(compiler(), source, stage.shadercKind, name, options);
            if (result == 0) throw new IllegalStateException("shaderc returned no result for " + name);
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw new IllegalStateException("Failed to compile " + stage + " shader " + name + ":\n" + shaderc_result_get_error_message(result));
                }
                ByteBuffer spirv = shaderc_result_get_bytes(result);
                if (spirv == null) throw new IllegalStateException("shaderc produced no SPIR-V for " + name);
                ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
                MemoryUtil.memCopy(spirv, copy);
                return copy;
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
        }
    }

    public static long compileToModule(String name, String source, Stage stage, Map<String, String> defines) {
        ByteBuffer spirv = compileToSpirv(name, source, stage, defines);
        try (var stack = MemoryStack.stackPush()) {
            var info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            var pModule = stack.mallocLong(1);
            VkUtil.check(vkCreateShaderModule(VkContext.get().vkDevice, info, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }
}
