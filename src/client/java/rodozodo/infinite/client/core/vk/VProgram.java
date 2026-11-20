package rodozodo.infinite.client.core.vk;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import rodozodo.infinite.Log;
import org.joml.Matrix4fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.*;
import java.util.regex.Pattern;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;


// Compiles GL style shaders with relaxed Vulkan rules, maps resource bindings onto push descriptor set 0 and manages loose uniform updates
public final class VProgram implements Destroyable {
    public enum Stage {
        VERTEX(shaderc_glsl_vertex_shader, VK_SHADER_STAGE_VERTEX_BIT, 0),
        FRAGMENT(shaderc_glsl_fragment_shader, VK_SHADER_STAGE_FRAGMENT_BIT, 1),
        COMPUTE(shaderc_glsl_compute_shader, VK_SHADER_STAGE_COMPUTE_BIT, 0);

        final int shadercKind;
        public final int vkStage;
        final int baseGroup;

        Stage(int shadercKind, int vkStage, int baseGroup) {
            this.shadercKind = shadercKind;
            this.vkStage = vkStage;
            this.baseGroup = baseGroup;
        }
    }

    public enum Kind {TEXTURE, IMAGE, UBO, SSBO}

    private static final int KIND_RANGE = 12;
    private static final int GROUP_RANGE = KIND_RANGE * 4;

    public static int bindingBase(Stage stage, Kind kind) {

        // glslang never shifts combined image samplers. Textures share binding base 0 across stages.
        if (kind == Kind.TEXTURE) return 0;
        return stage.baseGroup * GROUP_RANGE + kind.ordinal() * KIND_RANGE;
    }

    public static int externalSsboBinding(int glBinding) {
        return bindingBase(Stage.VERTEX, Kind.SSBO) + glBinding;
    }

    private static final Pattern LOCATION_UNIFORM = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*([A-Za-z0-9_]+)\\s*\\)\\s*uniform\\s+(?:highp\\s+|mediump\\s+|lowp\\s+)?\\w+\\s+(\\w+)\\s*;");
    private static final Pattern DEFINE = Pattern.compile("^\\s*#define\\s+(\\w+)\\s+(\\S+)", Pattern.MULTILINE);

    private record Resource(Kind kind, int set, int binding, int glBinding, int stageFlags) {}
    private record DefaultBlock(Stage stage, int binding, int size, Map<String, Integer> offsets) {}

    public static final class Module {
        final Stage stage;
        long module;
        final List<Resource> resources = new ArrayList<>();
        DefaultBlock defaultBlock;
        final Map<Integer, String> locationNames = new HashMap<>();
        final Map<String, Integer> stageOutputs = new HashMap<>();
        final Map<String, Integer> stageInputs = new HashMap<>();

        Module(Stage stage) {
            this.stage = stage;
        }
    }

    public final String name;
    private final List<Module> modules;
    public final long setLayout;
    public final long pipelineLayout;
    private final boolean usesExternalSet;
    private long externalSetLayout;
    private long externalSet;
    private final LongBuffer externalSetBuf;
    private final long externalSetAddr;

    private final int[][] bufferKeyToSlots = new int[2 * KIND_RANGE][];
    private final int bufferCount;
    private final int[] bufferVkBindings;
    private final int[] bufferVkTypes;
    private final VBuffer[] boundBuffers;
    private final VkDescriptorBufferInfo.Buffer bufferInfos;
    private final long bufferInfoBase;

    private final int[][] imageKeyToSlots = new int[2 * KIND_RANGE][];
    private final int imageCount;
    private final int[] imageVkBindings;
    private final int[] imageVkTypes;
    private final boolean[] imageBound;
    private final VkDescriptorImageInfo.Buffer imageInfos;
    private final long imageInfoBase;

    private final List<ByteBuffer> uniformData = new ArrayList<>();
    private final int blockCount;
    private final int[] blockBindings;
    private final int[] blockSizes;
    private final long[] blockSrcAddrs;
    private final VkDescriptorBufferInfo.Buffer blockInfos;
    private final long blockInfoBase;

    private final VkWriteDescriptorSet.Buffer writes;
    private boolean writesDirty = true;
    private int writeCount;

    private final long[] uniformAddr0;
    private final long[] uniformAddr1;
    private final long[][] uniformExtraAddrs;

    private VProgram(String name, List<Module> modules, long externalSetLayout) {
        this.name = name;
        this.modules = modules;
        this.externalSetLayout = externalSetLayout;
        var device = VkContext.get().vkDevice;

        Map<Integer, Resource> merged = new TreeMap<>();
        boolean external = false;
        int blocks = 0;
        for (var module : modules) {
            for (var r : module.resources) {
                if (r.set == 1) {
                    external = true;
                    continue;
                }
                if (r.set != 0) throw new IllegalStateException(name + ": unsupported descriptor set " + r.set);
                var existing = merged.get(r.binding);
                if (existing != null) {
                    if (existing.kind != r.kind) throw new IllegalStateException(name + ": binding clash at " + r.binding);
                    merged.put(r.binding, new Resource(r.kind, 0, r.binding, r.glBinding, existing.stageFlags | r.stageFlags));
                } else {
                    merged.put(r.binding, r);
                }
            }
            if (module.defaultBlock != null) {
                int b = module.defaultBlock.binding;
                if (merged.containsKey(b) && merged.get(b).glBinding != -1) {
                    throw new IllegalStateException(name + ": default uniform block binding clash at " + b);
                }
                merged.put(b, new Resource(Kind.UBO, 0, b, -1, module.stage.vkStage));
                this.uniformData.add(MemoryUtil.memCalloc(module.defaultBlock.size));
                blocks++;
            } else {
                this.uniformData.add(null);
            }
        }
        this.usesExternalSet = external;
        if (external && externalSetLayout == 0) {
            throw new IllegalStateException(name + ": shader uses set 1 but no external set layout was given");
        }
        if (external) {
            this.externalSetBuf = MemoryUtil.memCallocLong(1);
            this.externalSetAddr = MemoryUtil.memAddress(this.externalSetBuf);
        } else {
            this.externalSetBuf = null;
            this.externalSetAddr = 0;
        }

        List<Resource> bufList = new ArrayList<>();
        List<Resource> imgList = new ArrayList<>();
        for (var r : merged.values()) {
            if (r.glBinding == -1) continue;
            if (r.kind == Kind.UBO || r.kind == Kind.SSBO) {
                bufList.add(r);
            } else {
                imgList.add(r);
            }
        }

        this.bufferCount = bufList.size();
        this.bufferVkBindings = new int[this.bufferCount];
        this.bufferVkTypes = new int[this.bufferCount];
        this.boundBuffers = new VBuffer[this.bufferCount];
        if (this.bufferCount > 0) {
            this.bufferInfos = VkDescriptorBufferInfo.calloc(this.bufferCount);
            this.bufferInfoBase = this.bufferInfos.address();
        } else {
            this.bufferInfos = null;
            this.bufferInfoBase = 0;
        }
        List<List<Integer>> bufByKey = new ArrayList<>(2 * KIND_RANGE);
        for (int i = 0; i < 2 * KIND_RANGE; i++) bufByKey.add(null);
        for (int i = 0; i < this.bufferCount; i++) {
            Resource r = bufList.get(i);
            this.bufferVkBindings[i] = r.binding;
            this.bufferVkTypes[i] = vkType(r.kind);
            int keyIdx = (r.kind == Kind.SSBO ? KIND_RANGE : 0) + r.glBinding;
            var list = bufByKey.get(keyIdx);
            if (list == null) {
                list = new ArrayList<>(2);
                bufByKey.set(keyIdx, list);
            }
            list.add(i);
        }
        for (int k = 0; k < 2 * KIND_RANGE; k++) {
            var list = bufByKey.get(k);
            if (list != null) this.bufferKeyToSlots[k] = list.stream().mapToInt(Integer::intValue).toArray();
        }

        this.imageCount = imgList.size();
        this.imageVkBindings = new int[this.imageCount];
        this.imageVkTypes = new int[this.imageCount];
        this.imageBound = new boolean[this.imageCount];
        if (this.imageCount > 0) {
            this.imageInfos = VkDescriptorImageInfo.calloc(this.imageCount);
            this.imageInfoBase = this.imageInfos.address();
            for (int i = 0; i < this.imageCount; i++) {
                this.imageInfos.get(i).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            }
        } else {
            this.imageInfos = null;
            this.imageInfoBase = 0;
        }
        List<List<Integer>> imgByKey = new ArrayList<>(2 * KIND_RANGE);
        for (int i = 0; i < 2 * KIND_RANGE; i++) imgByKey.add(null);
        for (int i = 0; i < this.imageCount; i++) {
            Resource r = imgList.get(i);
            this.imageVkBindings[i] = r.binding;
            this.imageVkTypes[i] = vkType(r.kind);
            int keyIdx = (r.kind == Kind.IMAGE ? KIND_RANGE : 0) + r.glBinding;
            var list = imgByKey.get(keyIdx);
            if (list == null) {
                list = new ArrayList<>(2);
                imgByKey.set(keyIdx, list);
            }
            list.add(i);
        }
        for (int k = 0; k < 2 * KIND_RANGE; k++) {
            var list = imgByKey.get(k);
            if (list != null) this.imageKeyToSlots[k] = list.stream().mapToInt(Integer::intValue).toArray();
        }

        this.blockCount = blocks;
        this.blockBindings = new int[blocks];
        this.blockSizes = new int[blocks];
        this.blockSrcAddrs = new long[blocks];
        if (blocks > 0) {
            this.blockInfos = VkDescriptorBufferInfo.calloc(blocks);
            this.blockInfoBase = this.blockInfos.address();
        } else {
            this.blockInfos = null;
            this.blockInfoBase = 0;
        }
        int bi = 0;
        for (int i = 0; i < modules.size(); i++) {
            var block = modules.get(i).defaultBlock;
            if (block == null) continue;
            this.blockBindings[bi] = block.binding;
            this.blockSizes[bi] = block.size;
            this.blockSrcAddrs[bi] = MemoryUtil.memAddress(this.uniformData.get(i));
            this.blockInfos.get(bi).range(block.size);
            bi++;
        }

        int maxWrites = this.bufferCount + this.imageCount + this.blockCount;
        this.writes = maxWrites > 0 ? VkWriteDescriptorSet.calloc(maxWrites) : null;

        int maxLoc = -1;
        for (var module : modules) {
            if (module.defaultBlock == null) continue;
            for (var e : module.locationNames.entrySet()) {
                if (module.defaultBlock.offsets.containsKey(e.getValue())) {
                    maxLoc = Math.max(maxLoc, e.getKey());
                }
            }
        }
        this.uniformAddr0 = new long[maxLoc + 1];
        this.uniformAddr1 = new long[maxLoc + 1];
        long[][] extras = null;
        for (int i = 0; i < modules.size(); i++) {
            var module = modules.get(i);
            if (module.defaultBlock == null) continue;
            long base = MemoryUtil.memAddress(this.uniformData.get(i));
            for (var e : module.locationNames.entrySet()) {
                int loc = e.getKey();
                if (loc < 0) continue;
                Integer off = module.defaultBlock.offsets.get(e.getValue());
                if (off == null) continue;
                long addr = base + off;
                if (this.uniformAddr0[loc] == 0) {
                    this.uniformAddr0[loc] = addr;
                } else if (this.uniformAddr1[loc] == 0) {
                    this.uniformAddr1[loc] = addr;
                } else {
                    if (extras == null) extras = new long[maxLoc + 1][];
                    long[] prev = extras[loc];
                    long[] next = prev == null ? new long[1] : Arrays.copyOf(prev, prev.length + 1);
                    next[next.length - 1] = addr;
                    extras[loc] = next;
                }
            }
        }
        this.uniformExtraAddrs = extras;

        try (var stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(merged.size(), stack);
            int i = 0;
            for (var r : merged.values()) {
                bindings.get(i++).binding(r.binding).descriptorType(vkType(r.kind)).descriptorCount(1).stageFlags(r.stageFlags);
            }
            var info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(bindings);
            var p = stack.mallocLong(1);
            VkUtil.check(vkCreateDescriptorSetLayout(device, info, null, p), "vkCreateDescriptorSetLayout(" + name + ")");
            this.setLayout = p.get(0);
            var layouts = external ? stack.longs(this.setLayout, externalSetLayout) : stack.longs(this.setLayout);
            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(layouts);
            VkUtil.check(vkCreatePipelineLayout(device, layoutInfo, null, p), "vkCreatePipelineLayout(" + name + ")");
            this.pipelineLayout = p.get(0);
        }
    }

    private static int vkType(Kind kind) {
        return switch (kind) {
            case TEXTURE -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case UBO -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case SSBO -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        };
    }

    public static VProgram compute(String name, String source, Map<String, String> defines) {
        return compute(name, source, defines, 0);
    }

    public static VProgram compute(String name, String source, Map<String, String> defines, long externalSetLayout) {
        return new VProgram(name, List.of(compileModule(name, Stage.COMPUTE, source, defines)), externalSetLayout);
    }

    public static VProgram graphics(String name, String vertex, String fragment, Map<String, String> defines, long externalSetLayout) {
        var vs = compileModule(name + ".vert", Stage.VERTEX, vertex, defines);
        var fs = compileModule(name + ".frag", Stage.FRAGMENT, fragment, defines);
        for (var in : fs.stageInputs.entrySet()) {
            Integer outLoc = vs.stageOutputs.get(in.getKey());
            if (!in.getKey().isEmpty() && outLoc != null && !outLoc.equals(in.getValue())) {
                throw new IllegalStateException(name + ": varying '" + in.getKey() + "' has location " + outLoc + " in the vertex shader but " + in.getValue() + " in the fragment shader");
            }
        }
        return new VProgram(name, List.of(vs, fs), externalSetLayout);
    }

    public long module(Stage stage) {
        for (var m : this.modules) if (m.stage == stage) return m.module;
        throw new IllegalArgumentException(this.name + ": no " + stage + " stage");
    }

    private static Module compileModule(String name, Stage stage, String source, Map<String, String> defines) {
        ByteBuffer spirv = compileSpirv(name, stage, source, defines);
        try {
            // Reflect before creating VkShaderModule because reflection may rewrite set 1 bindings in SPIR-V
            var result = new Module(stage);
            reflect(name, stage, spirv, result);
            long module;
            try (var stack = MemoryStack.stackPush()) {
                var info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
                var p = stack.mallocLong(1);
                VkUtil.check(vkCreateShaderModule(VkContext.get().vkDevice, info, null, p), "vkCreateShaderModule(" + name + ")");
                module = p.get(0);
            }
            result.module = module;
            mapLocations(source, defines, result);
            return result;
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private static final Map<String, byte[]> COMPILED = new java.util.concurrent.ConcurrentHashMap<>();

    private static ByteBuffer compileSpirv(String name, Stage stage, String source, Map<String, String> defines) {
        String key = stage + "\n" + new TreeMap<>(defines) + "\n" + source;
        byte[] spirv = COMPILED.get(key);
        if (spirv == null) {
            ByteBuffer compiled = compileSpirvNow(name, stage, source, defines);
            spirv = new byte[compiled.remaining()];
            compiled.get(compiled.position(), spirv);
            MemoryUtil.memFree(compiled);
            COMPILED.put(key, spirv);
        }

        // Copy per compile because reflection may rewrite set 1 bindings in place
        ByteBuffer copy = MemoryUtil.memAlloc(spirv.length);
        copy.put(0, spirv);
        return copy;
    }

    private static ByteBuffer compileSpirvNow(String name, Stage stage, String source, Map<String, String> defines) {
        long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            // Keep OpName so SPIRV-Cross reflection can find default uniform block members and varyings.
            shaderc_compile_options_set_generate_debug_info(options);
            shaderc_compile_options_set_vulkan_rules_relaxed(options, true);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            shaderc_compile_options_set_auto_map_locations(options, true);
            shaderc_compile_options_set_binding_base_for_stage(options, stage.shadercKind, shaderc_uniform_kind_texture, bindingBase(stage, Kind.TEXTURE));
            shaderc_compile_options_set_binding_base_for_stage(options, stage.shadercKind, shaderc_uniform_kind_sampler, bindingBase(stage, Kind.TEXTURE));
            shaderc_compile_options_set_binding_base_for_stage(options, stage.shadercKind, shaderc_uniform_kind_image, bindingBase(stage, Kind.IMAGE));
            shaderc_compile_options_set_binding_base_for_stage(options, stage.shadercKind, shaderc_uniform_kind_buffer, bindingBase(stage, Kind.UBO));
            shaderc_compile_options_set_binding_base_for_stage(options, stage.shadercKind, shaderc_uniform_kind_storage_buffer, bindingBase(stage, Kind.SSBO));
            for (var d : defines.entrySet()) {
                shaderc_compile_options_add_macro_definition(options, d.getKey(), d.getValue());
            }
            long result = VShaderCompiler.compileIntoSpv(VShaderCompilerHolder.compiler(), source, stage.shadercKind, name, options);
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw new IllegalStateException("Failed to compile " + stage + " shader " + name + ":\n" + shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
                MemoryUtil.memCopy(bytes, copy);
                dumpShader(name, stage, source, copy);
                return copy;
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
        }
    }

    private static final String DUMP_DIR = System.getProperty("infinite.vulkan.dumpShaders", "");

    private static void dumpShader(String name, Stage stage, String source, ByteBuffer spirv) {
        if (DUMP_DIR.isEmpty()) return;
        try {
            var dir = java.nio.file.Path.of(DUMP_DIR);
            java.nio.file.Files.createDirectories(dir);
            String file = name.replaceAll("[^A-Za-z0-9_.-]", "_") + "." + stage.name().toLowerCase();
            java.nio.file.Files.writeString(dir.resolve(file + ".glsl"), source);
            byte[] bytes = new byte[spirv.remaining()];
            spirv.duplicate().get(bytes);
            java.nio.file.Files.write(dir.resolve(file + ".spv"), bytes);
        } catch (java.io.IOException e) {
            Log.warn("Could not dump shader " + name + ": " + e);
        }
    }

    private static void reflect(String name, Stage stage, ByteBuffer spirv, Module out) {
        try (var stack = MemoryStack.stackPush()) {
            var p = stack.mallocPointer(1);
            check(spvc_context_create(p), 0, "context");
            long ctx = p.get(0);
            try {
                check(spvc_context_parse_spirv(ctx, spirv.asIntBuffer(), spirv.remaining() / 4, p), ctx, "parse");
                long ir = p.get(0);
                check(spvc_context_create_compiler(ctx, SPVC_BACKEND_NONE, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), ctx, "compiler");
                long compiler = p.get(0);
                check(spvc_compiler_create_shader_resources(compiler, p), ctx, "resources");
                long resources = p.get(0);

                forEach(resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, stack, r -> {
                    int set = spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationDescriptorSet);
                    int binding = spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationBinding);
                    if ("gl_DefaultUniformBlock".equals(r.nameString())) {
                        out.defaultBlock = reflectBlock(compiler, stage, r.base_type_id(), binding);
                    } else {
                        out.resources.add(new Resource(Kind.UBO, set, binding, binding - bindingBase(stage, Kind.UBO), stage.vkStage));
                    }
                });
                forEach(resources, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, stack, r -> addResource(compiler, stage, r, Kind.SSBO, spirv, out));
                forEach(resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, stack, r -> addResource(compiler, stage, r, Kind.TEXTURE, spirv, out));
                forEach(resources, SPVC_RESOURCE_TYPE_STORAGE_IMAGE, stack, r -> addResource(compiler, stage, r, Kind.IMAGE, spirv, out));
                forEach(resources, SPVC_RESOURCE_TYPE_STAGE_OUTPUT, stack, r ->
                        out.stageOutputs.put(r.nameString(), spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationLocation)));
                forEach(resources, SPVC_RESOURCE_TYPE_STAGE_INPUT, stack, r ->
                        out.stageInputs.put(r.nameString(), spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationLocation)));
            } finally {
                spvc_context_destroy(ctx);
            }
        }
    }

    private static void addResource(long compiler, Stage stage, SpvcReflectedResource r, Kind kind, ByteBuffer spirv, Module out) {
        int set = spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationDescriptorSet);
        int binding = spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationBinding);
        int glBinding = binding - bindingBase(stage, kind);
        if (glBinding < 0 || glBinding >= KIND_RANGE) {
            throw new IllegalStateException(stage + " " + kind + " '" + r.nameString() + "' landed at binding " + binding + ", outside its GL binding range");
        }
        // Set 1 shares one layout across stages using vertex / compute binding bases
        int external = bindingBase(Stage.VERTEX, kind) + glBinding;
        if (set == 1 && binding != external) {
            try (var stack = MemoryStack.stackPush()) {
                var offset = stack.mallocInt(1);
                if (!spvc_compiler_get_binary_offset_for_decoration(compiler, r.id(), SpvDecorationBinding, offset)) {
                    throw new IllegalStateException(stage + " " + kind + " '" + r.nameString() + "' has no binding decoration to move");
                }
                spirv.putInt(spirv.position() + offset.get(0) * 4, external);
            }
            binding = external;
        }
        out.resources.add(new Resource(kind, set, binding, glBinding, stage.vkStage));
    }

    private static DefaultBlock reflectBlock(long compiler, Stage stage, int baseTypeId, int binding) {
        try (var stack = MemoryStack.stackPush()) {
            long type = spvc_compiler_get_type_handle(compiler, baseTypeId);
            int members = spvc_type_get_num_member_types(type);
            Map<String, Integer> offsets = new HashMap<>();
            var offset = stack.mallocInt(1);
            for (int i = 0; i < members; i++) {
                spvc_compiler_type_struct_member_offset(compiler, type, i, offset);
                offsets.put(spvc_compiler_get_member_name(compiler, baseTypeId, i), offset.get(0));
            }
            var size = stack.mallocPointer(1);
            spvc_compiler_get_declared_struct_size(compiler, type, size);
            return new DefaultBlock(stage, binding, (int) size.get(0), offsets);
        }
    }

    private interface ResourceConsumer {
        void accept(SpvcReflectedResource resource);
    }

    private static void forEach(long resources, int type, MemoryStack stack, ResourceConsumer consumer) {
        PointerBuffer list = stack.mallocPointer(1);
        PointerBuffer count = stack.mallocPointer(1);
        spvc_resources_get_resource_list_for_type(resources, type, list, count);
        var buffer = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
        for (int i = 0; i < buffer.remaining(); i++) consumer.accept(buffer.get(i));
    }

    private static void check(int result, long ctx, String what) {
        if (result != SPVC_SUCCESS) {
            throw new IllegalStateException("SPIRV-Cross " + what + " failed: " + (ctx != 0 ? spvc_context_get_last_error_string(ctx) : result));
        }
    }

    private static void mapLocations(String source, Map<String, String> defines, Module module) {
        Map<String, String> sourceDefines = new HashMap<>();
        var dm = DEFINE.matcher(source);
        while (dm.find()) sourceDefines.putIfAbsent(dm.group(1), dm.group(2));
        var m = LOCATION_UNIFORM.matcher(source);
        while (m.find()) {
            String token = m.group(1);
            String value = defines.getOrDefault(token, sourceDefines.getOrDefault(token, token));
            try {
                module.locationNames.put(Integer.parseInt(value), m.group(2));
            } catch (NumberFormatException e) {
                Log.warn("Could not resolve uniform location '" + token + "' for " + m.group(2));
            }
        }
    }

    public VProgram ssbo(int glBinding, VBuffer buffer) {
        return this.ssbo(glBinding, buffer, 0, VK_WHOLE_SIZE);
    }

    public VProgram ssbo(int glBinding, VBuffer buffer, long offset, long range) {
        return this.buffer(Kind.SSBO, glBinding, buffer, offset, range);
    }

    public VProgram ubo(int glBinding, VBuffer buffer) {
        return this.ubo(glBinding, buffer, 0, VK_WHOLE_SIZE);
    }

    public VProgram ubo(int glBinding, VBuffer buffer, long offset, long range) {
        return this.buffer(Kind.UBO, glBinding, buffer, offset, range);
    }

    private VProgram buffer(Kind kind, int glBinding, VBuffer buffer, long offset, long range) {
        if (glBinding >= 0 && glBinding < KIND_RANGE) {
            int[] slots = this.bufferKeyToSlots[(kind == Kind.SSBO ? KIND_RANGE : 0) + glBinding];
            if (slots != null) {
                for (int slot : slots) {
                    if (this.boundBuffers[slot] == null) this.writesDirty = true;
                    this.boundBuffers[slot] = buffer;
                    long info = this.bufferInfoBase + (long) slot * VkDescriptorBufferInfo.SIZEOF;
                    MemoryUtil.memPutLong(info + VkDescriptorBufferInfo.OFFSET, offset);
                    MemoryUtil.memPutLong(info + VkDescriptorBufferInfo.RANGE, range);
                }
            }
        }
        return this;
    }

    public VProgram texture(int glBinding, long imageView, long sampler) {
        return this.bindImage(0, glBinding, imageView, sampler);
    }

    public VProgram image(int glBinding, long imageView) {
        return this.bindImage(KIND_RANGE, glBinding, imageView, VK_NULL_HANDLE);
    }

    private VProgram bindImage(int keyBase, int glBinding, long imageView, long sampler) {
        if (glBinding >= 0 && glBinding < KIND_RANGE) {
            int[] slots = this.imageKeyToSlots[keyBase + glBinding];
            if (slots != null) {
                for (int slot : slots) {
                    if (!this.imageBound[slot]) {
                        this.imageBound[slot] = true;
                        this.writesDirty = true;
                    }
                    long info = this.imageInfoBase + (long) slot * VkDescriptorImageInfo.SIZEOF;
                    MemoryUtil.memPutLong(info + VkDescriptorImageInfo.SAMPLER, sampler);
                    MemoryUtil.memPutLong(info + VkDescriptorImageInfo.IMAGEVIEW, imageView);
                }
            }
        }
        return this;
    }

    public VProgram setExternalSet(long descriptorSet) {
        this.externalSet = descriptorSet;
        if (this.externalSetAddr != 0) MemoryUtil.memPutLong(this.externalSetAddr, descriptorSet);
        return this;
    }

    public VProgram uniform1ui(int location, int v) {
        if (location >= 0 && location < this.uniformAddr0.length) {
            long a0 = this.uniformAddr0[location];
            if (a0 != 0) MemoryUtil.memPutInt(a0, v);
            long a1 = this.uniformAddr1[location];
            if (a1 != 0) MemoryUtil.memPutInt(a1, v);
            if (this.uniformExtraAddrs != null && this.uniformExtraAddrs[location] != null) {
                for (long a : this.uniformExtraAddrs[location]) MemoryUtil.memPutInt(a, v);
            }
        }
        return this;
    }

    public VProgram uniform2i(int location, int x, int y) {
        if (location >= 0 && location < this.uniformAddr0.length) {
            long a0 = this.uniformAddr0[location];
            if (a0 != 0) {
                MemoryUtil.memPutInt(a0, x);
                MemoryUtil.memPutInt(a0 + 4, y);
            }
            long a1 = this.uniformAddr1[location];
            if (a1 != 0) {
                MemoryUtil.memPutInt(a1, x);
                MemoryUtil.memPutInt(a1 + 4, y);
            }
            if (this.uniformExtraAddrs != null && this.uniformExtraAddrs[location] != null) {
                for (long a : this.uniformExtraAddrs[location]) {
                    MemoryUtil.memPutInt(a, x);
                    MemoryUtil.memPutInt(a + 4, y);
                }
            }
        }
        return this;
    }

    public VProgram uniform1f(int location, float v) {
        if (location >= 0 && location < this.uniformAddr0.length) {
            long a0 = this.uniformAddr0[location];
            if (a0 != 0) MemoryUtil.memPutFloat(a0, v);
            long a1 = this.uniformAddr1[location];
            if (a1 != 0) MemoryUtil.memPutFloat(a1, v);
            if (this.uniformExtraAddrs != null && this.uniformExtraAddrs[location] != null) {
                for (long a : this.uniformExtraAddrs[location]) MemoryUtil.memPutFloat(a, v);
            }
        }
        return this;
    }

    public VProgram uniform2f(int location, float x, float y) {
        if (location >= 0 && location < this.uniformAddr0.length) {
            long a0 = this.uniformAddr0[location];
            if (a0 != 0) {
                MemoryUtil.memPutFloat(a0, x);
                MemoryUtil.memPutFloat(a0 + 4, y);
            }
            long a1 = this.uniformAddr1[location];
            if (a1 != 0) {
                MemoryUtil.memPutFloat(a1, x);
                MemoryUtil.memPutFloat(a1 + 4, y);
            }
            if (this.uniformExtraAddrs != null && this.uniformExtraAddrs[location] != null) {
                for (long a : this.uniformExtraAddrs[location]) {
                    MemoryUtil.memPutFloat(a, x);
                    MemoryUtil.memPutFloat(a + 4, y);
                }
            }
        }
        return this;
    }

    public VProgram uniform3f(int location, float x, float y, float z) {
        if (location >= 0 && location < this.uniformAddr0.length) {
            long a0 = this.uniformAddr0[location];
            if (a0 != 0) {
                MemoryUtil.memPutFloat(a0, x);
                MemoryUtil.memPutFloat(a0 + 4, y);
                MemoryUtil.memPutFloat(a0 + 8, z);
            }
            long a1 = this.uniformAddr1[location];
            if (a1 != 0) {
                MemoryUtil.memPutFloat(a1, x);
                MemoryUtil.memPutFloat(a1 + 4, y);
                MemoryUtil.memPutFloat(a1 + 8, z);
            }
            if (this.uniformExtraAddrs != null && this.uniformExtraAddrs[location] != null) {
                for (long a : this.uniformExtraAddrs[location]) {
                    MemoryUtil.memPutFloat(a, x);
                    MemoryUtil.memPutFloat(a + 4, y);
                    MemoryUtil.memPutFloat(a + 8, z);
                }
            }
        }
        return this;
    }

    public VProgram uniform4f(int location, float x, float y, float z, float w) {
        if (location >= 0 && location < this.uniformAddr0.length) {
            long a0 = this.uniformAddr0[location];
            if (a0 != 0) {
                MemoryUtil.memPutFloat(a0, x);
                MemoryUtil.memPutFloat(a0 + 4, y);
                MemoryUtil.memPutFloat(a0 + 8, z);
                MemoryUtil.memPutFloat(a0 + 12, w);
            }
            long a1 = this.uniformAddr1[location];
            if (a1 != 0) {
                MemoryUtil.memPutFloat(a1, x);
                MemoryUtil.memPutFloat(a1 + 4, y);
                MemoryUtil.memPutFloat(a1 + 8, z);
                MemoryUtil.memPutFloat(a1 + 12, w);
            }
            if (this.uniformExtraAddrs != null && this.uniformExtraAddrs[location] != null) {
                for (long a : this.uniformExtraAddrs[location]) {
                    MemoryUtil.memPutFloat(a, x);
                    MemoryUtil.memPutFloat(a + 4, y);
                    MemoryUtil.memPutFloat(a + 8, z);
                    MemoryUtil.memPutFloat(a + 12, w);
                }
            }
        }
        return this;
    }

    public VProgram uniformMat4(int location, Matrix4fc matrix) {
        if (location >= 0 && location < this.uniformAddr0.length) {
            long a0 = this.uniformAddr0[location];
            if (a0 != 0) matrix.getToAddress(a0);
            long a1 = this.uniformAddr1[location];
            if (a1 != 0) matrix.getToAddress(a1);
            if (this.uniformExtraAddrs != null && this.uniformExtraAddrs[location] != null) {
                for (long a : this.uniformExtraAddrs[location]) matrix.getToAddress(a);
            }
        }
        return this;
    }

    private void rebuildWrites() {
        this.writesDirty = false;
        if (this.writes == null) {
            this.writeCount = 0;
            return;
        }
        this.writes.clear();
        int count = 0;
        for (int i = 0; i < this.bufferCount; i++) {
            if (this.boundBuffers[i] == null) continue;
            var w = this.writes.get(count++).sType$Default().dstSet(0).dstBinding(this.bufferVkBindings[i])
                    .dstArrayElement(0).descriptorCount(1).descriptorType(this.bufferVkTypes[i])
                    .pImageInfo(null).pTexelBufferView(null);
            MemoryUtil.memPutAddress(w.address() + VkWriteDescriptorSet.PBUFFERINFO,
                    this.bufferInfoBase + (long) i * VkDescriptorBufferInfo.SIZEOF);
        }
        for (int i = 0; i < this.imageCount; i++) {
            if (!this.imageBound[i]) continue;
            var w = this.writes.get(count++).sType$Default().dstSet(0).dstBinding(this.imageVkBindings[i])
                    .dstArrayElement(0).descriptorCount(1).descriptorType(this.imageVkTypes[i])
                    .pBufferInfo(null).pTexelBufferView(null);
            MemoryUtil.memPutAddress(w.address() + VkWriteDescriptorSet.PIMAGEINFO,
                    this.imageInfoBase + (long) i * VkDescriptorImageInfo.SIZEOF);
        }
        for (int i = 0; i < this.blockCount; i++) {
            var w = this.writes.get(count++).sType$Default().dstSet(0).dstBinding(this.blockBindings[i])
                    .dstArrayElement(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pImageInfo(null).pTexelBufferView(null);
            MemoryUtil.memPutAddress(w.address() + VkWriteDescriptorSet.PBUFFERINFO,
                    this.blockInfoBase + (long) i * VkDescriptorBufferInfo.SIZEOF);
        }
        this.writeCount = count;
        this.writes.limit(count);
    }

    public void flush(VkCommandBuffer cb, int bindPoint, StagingBuffer staging) {
        for (int i = 0; i < this.bufferCount; i++) {
            VBuffer b = this.boundBuffers[i];
            if (b != null) {
                MemoryUtil.memPutLong(this.bufferInfoBase + (long) i * VkDescriptorBufferInfo.SIZEOF + VkDescriptorBufferInfo.BUFFER, b.handle);
            }
        }
        for (int i = 0; i < this.blockCount; i++) {
            long offset = staging.reserve(this.blockSizes[i]);
            MemoryUtil.memCopy(this.blockSrcAddrs[i], staging.address(offset), this.blockSizes[i]);
            long dst = this.blockInfoBase + (long) i * VkDescriptorBufferInfo.SIZEOF;
            MemoryUtil.memPutLong(dst + VkDescriptorBufferInfo.BUFFER, staging.buffer().handle);
            MemoryUtil.memPutLong(dst + VkDescriptorBufferInfo.OFFSET, offset);
        }
        if (this.writesDirty) this.rebuildWrites();
        if (this.writeCount != 0) {
            vkCmdPushDescriptorSetKHR(cb, bindPoint, this.pipelineLayout, 0, this.writes);
        }
        if (this.usesExternalSet) {
            if (this.externalSet == 0) throw new IllegalStateException(this.name + ": external descriptor set not provided");
            vkCmdBindDescriptorSets(cb, bindPoint, this.pipelineLayout, 1, this.externalSetBuf, null);
        }
    }

    public void free() {
        VkContext.get().destroyDeferred(this);
    }

    @Override
    public void destroy() {
        var device = VkContext.get().vkDevice;
        for (var m : this.modules) vkDestroyShaderModule(device, m.module, null);
        vkDestroyPipelineLayout(device, this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, this.setLayout, null);
        for (var data : this.uniformData) if (data != null) MemoryUtil.memFree(data);
        if (this.bufferInfos != null) this.bufferInfos.free();
        if (this.imageInfos != null) this.imageInfos.free();
        if (this.blockInfos != null) this.blockInfos.free();
        if (this.writes != null) this.writes.free();
        if (this.externalSetBuf != null) MemoryUtil.memFree(this.externalSetBuf);
    }

    static final class VShaderCompilerHolder {
        private static long compiler;

        static synchronized long compiler() {
            if (compiler == 0) compiler = shaderc_compiler_initialize();
            return compiler;
        }
    }
}
