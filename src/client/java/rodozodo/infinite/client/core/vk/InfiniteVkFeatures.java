package rodozodo.infinite.client.core.vk;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.KHRPipelineExecutableProperties;
import org.lwjgl.vulkan.VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


/**
 * Declares Vulkan device features Infinite requests as optional sets at device creation and
 * verifies whether all required features were enabled
 */

public final class InfiniteVkFeatures {
    public record Feature(String name, FeatureSet set, boolean required) {}

    private static Feature feature(VulkanPNextStruct struct, String field, boolean required) {
        return new Feature(field, new FeatureSet("Infinite " + field, Set.of(), Set.of(new VulkanFeature(struct, field))), required);
    }

    public static final Feature DRAW_INDIRECT_COUNT = feature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "drawIndirectCount", false);
    public static final Feature SHADER_INT64 = feature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderInt64", true);
    public static final Feature FRAGMENT_STORES_AND_ATOMICS = feature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "fragmentStoresAndAtomics", true);
    public static final Feature VERTEX_STORES_AND_ATOMICS = feature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "vertexPipelineStoresAndAtomics", false);
    public static final Feature STORAGE_IMAGE_WRITE_WITHOUT_FORMAT = feature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageWriteWithoutFormat", false);
    public static final Feature SAMPLER_FILTER_MINMAX = feature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "samplerFilterMinmax", false);
    public static final Feature SHADER_BUFFER_INT64_ATOMICS = feature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderBufferInt64Atomics", false);

    public static final boolean PIPELINE_STATS_WANTED = Boolean.getBoolean("infinite.pipelineStats");
    public static final Feature PIPELINE_EXECUTABLE_INFO = new Feature("pipelineExecutableInfo",
            new FeatureSet("Infinite pipelineExecutableInfo",
                    Set.of(KHRPipelineExecutableProperties.VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME),
                    Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR.class),
                            "pipelineExecutableInfo"))), false);

    public static final List<Feature> ALL = features();

    private static List<Feature> features() {
        var all = new ArrayList<>(List.of(
                DRAW_INDIRECT_COUNT,
                SHADER_INT64,
                FRAGMENT_STORES_AND_ATOMICS,
                VERTEX_STORES_AND_ATOMICS,
                STORAGE_IMAGE_WRITE_WITHOUT_FORMAT,
                SAMPLER_FILTER_MINMAX,
                SHADER_BUFFER_INT64_ATOMICS));
        if (PIPELINE_STATS_WANTED) all.add(PIPELINE_EXECUTABLE_INFO);
        return List.copyOf(all);
    }

    private InfiniteVkFeatures() {}

    public static boolean usesDrawCountFallback(FeatureSet enabled) {
        return Boolean.getBoolean("infinite.vulkan.forceDrawCountFallback") || !enabled.contains(DRAW_INDIRECT_COUNT.set);
    }

    public static List<Feature> missingRequired(FeatureSet enabled) {
        Set<String> treatAsMissing = Set.of(System.getProperty("infinite.vulkan.debugTreatAsMissing", "").split(","));
        List<Feature> missing = new ArrayList<>();
        for (Feature feature : ALL) {
            if (feature.required && (!enabled.contains(feature.set) || treatAsMissing.contains(feature.name))) {
                missing.add(feature);
            }
        }
        return missing;
    }
}
