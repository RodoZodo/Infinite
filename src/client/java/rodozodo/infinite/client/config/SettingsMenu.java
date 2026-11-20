package rodozodo.infinite.client.config;

import rodozodo.infinite.Infinite;
import rodozodo.infinite.client.LodSession;
import rodozodo.infinite.client.render.LodRendererHolder;
import rodozodo.infinite.util.DetailScale;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.StatefulOptionBuilder;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Collection;


// Registers Infinite's configuration pages in Sodium's video settings


public final class SettingsMenu implements ConfigEntryPoint {
    public static final String MOD_ID = "infinite";

    private static final Identifier ENABLED = id("enabled");
    private static final Identifier RENDERING = id("rendering");
    private static final Identifier QUALITY = id("quality");
    private static final Identifier START_OR_STOP = id("start_or_stop");
    private static final Identifier SWITCH_RENDERER = id("switch_renderer");
    private static final Identifier MOVE_DISTANCE = id("move_distance");
    private static final Identifier CHANGE_DETAIL = id("change_detail");
    private static final Identifier SODIUM_RELOAD = OptionFlag.REQUIRES_RENDERER_RELOAD.getId();

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        if (!Infinite.available()) return;
        var settings = ClientSettings.get();
        var defaults = ClientSettings.defaults();

        var mod = builder.registerModOptions(MOD_ID, "Infinite", Infinite.VERSION)
                .setIcon(Identifier.parse("infinite:icon.png"));

        var enabled = builder.createBooleanOption(ENABLED)
                .setBinding(v -> settings.enabled = v, () -> settings.enabled)
                .setDefaultValue(defaults.enabled)
                .setTooltip(v -> Component.empty())
                .setFlags(START_OR_STOP);
        var quality = builder.createEnumOption(QUALITY, Quality.class)
                .setBinding(v -> settings.quality = v, () -> settings.quality)
                .setDefaultValue(defaults.quality)
                .setElementNameProvider(v -> Component.translatable("infinite.quality." + v.key))
                .setTooltip(v -> Component.translatable("infinite.quality." + v.key + ".tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setFlags(QUALITY);
        var rendering = builder.createBooleanOption(RENDERING)
                .setBinding(v -> settings.rendering = v, () -> settings.rendering)
                .setDefaultValue(defaults.rendering)
                .setFlags(SWITCH_RENDERER);

        var distance = builder.createIntegerOption(id("render_distance"))
                .setBinding(v -> settings.renderDistanceChunks = v, () -> settings.renderDistanceChunks)
                .setDefaultValue(defaults.renderDistanceChunks)
                .setRange(ClientSettings.MIN_DISTANCE_CHUNKS, ClientSettings.MAX_DISTANCE_CHUNKS, ClientSettings.DISTANCE_STEP_CHUNKS)
                .setValueFormatter(v -> Component.translatable("infinite.options.chunks", v))
                .setTooltip(v -> Component.empty())
                .setFlags(MOVE_DISTANCE);
        var detail = builder.createIntegerOption(id("detail"))
                .setBinding(v -> settings.detailPixels = Math.max(DetailScale.pixelsAt(v), DetailScale.leastPixels()),
                        () -> DetailScale.stepFor(settings.detailPixels))
                .setDefaultValue(DetailScale.stepFor(defaults.detailPixels))
                .setRange(0, DetailScale.finestStep(), 1)
                .setValueFormatter(v -> Component.translatable("infinite.options.detail.value", Math.round(DetailScale.pixelsAt(v))))
                .setImpact(OptionImpact.HIGH)
                .setFlags(CHANGE_DETAIL);
        var ingest = builder.createBooleanOption(id("ingest"))
                .setBinding(v -> settings.ingest = v, () -> settings.ingest)
                .setDefaultValue(defaults.ingest);
        var ambientOcclusion = builder.createBooleanOption(id("ambient_occlusion"))
                .setBinding(v -> settings.ambientOcclusion = v, () -> settings.ambientOcclusion)
                .setDefaultValue(defaults.ambientOcclusion);
        var antiAliasing = builder.createEnumOption(id("anti_aliasing"), AntiAliasing.class)
                .setBinding(v -> settings.antiAliasing = v, () -> settings.antiAliasing)
                .setDefaultValue(defaults.antiAliasing)
                .setElementNameProvider(v -> Component.translatable("infinite.anti_aliasing." + v.key))
                .setImpact(OptionImpact.LOW);
        var smoothBorder = builder.createBooleanOption(id("smooth_border"))
                .setBinding(v -> settings.smoothBorder = v, () -> settings.smoothBorder)
                .setDefaultValue(defaults.smoothBorder);
        var caveCulling = builder.createBooleanOption(id("cave_culling"))
                .setBinding(v -> settings.caveCulling = v, () -> settings.caveCulling)
                .setDefaultValue(defaults.caveCulling)
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(SWITCH_RENDERER);
        var plantCutouts = builder.createBooleanOption(id("plant_cutouts"))
                .setBinding(v -> settings.plantCutouts = v, () -> settings.plantCutouts)
                .setDefaultValue(defaults.plantCutouts)
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(SWITCH_RENDERER);
        var curvature = builder.createIntegerOption(id("earth_curvature"))
                .setBinding(v -> settings.earthCurvature = v, () -> settings.earthCurvature)
                .setDefaultValue(defaults.earthCurvature)
                .setRange(0, EarthCurvature.MAX_PERCENT, 1)
                .setValueFormatter(v -> v == 0 ? Component.translatable("options.off")
                        : Component.translatable("infinite.options.percent", v));
        var netherFog = builder.createIntegerOption(id("nether_fog"))
                .setBinding(v -> settings.netherFog = v, () -> settings.netherFog)
                .setDefaultValue(defaults.netherFog)
                .setRange(0, 100, 5)
                .setValueFormatter(v -> v == 0 ? Component.translatable("options.off")
                        : Component.translatable("infinite.options.percent", v));
        var netherFogStart = builder.createIntegerOption(id("nether_fog_start"))
                .setBinding(v -> settings.netherFogStartChunks = v, () -> settings.netherFogStartChunks)
                .setDefaultValue(defaults.netherFogStartChunks)
                .setRange(0, ClientSettings.MAX_NETHER_FOG_START_CHUNKS, 1)
                .setValueFormatter(v -> Component.translatable("infinite.options.chunks", v));

        for (var option : new StatefulOptionBuilder<?>[]{enabled, quality, rendering, ingest, distance, detail,
                ambientOcclusion, antiAliasing, smoothBorder, caveCulling,
                plantCutouts, curvature, netherFog, netherFogStart}) {
            option.setStorageHandler(settings::save);
        }
        quality.setEnabledProvider(s -> s.readBooleanOption(ENABLED) && s.readBooleanOption(RENDERING), ENABLED, RENDERING);
        ingest.setEnabledProvider(s -> s.readBooleanOption(ENABLED), ENABLED);
        distance.setEnabledProvider(s -> s.readBooleanOption(ENABLED) && s.readBooleanOption(RENDERING),
                ENABLED, RENDERING);
        detail.setEnabledProvider(s -> custom(s) && s.readBooleanOption(ENABLED) && s.readBooleanOption(RENDERING),
                QUALITY, ENABLED, RENDERING);
        for (var option : new StatefulOptionBuilder<?>[]{ambientOcclusion, antiAliasing, smoothBorder,
                caveCulling, plantCutouts, curvature, netherFog}) {
            option.setEnabledProvider(s -> s.readBooleanOption(ENABLED) && s.readBooleanOption(RENDERING), ENABLED, RENDERING);
        }
        netherFogStart.setEnabledProvider(s -> s.readBooleanOption(ENABLED) && s.readBooleanOption(RENDERING)
                && s.readIntOption(id("nether_fog")) > 0, ENABLED, RENDERING, id("nether_fog"));
        named(quality, "quality");
        enabled.setName(Component.translatable("infinite.options.enabled"));
        named(rendering, "rendering");
        named(ingest, "ingest");
        distance.setName(Component.translatable("infinite.options.render_distance"));
        named(detail, "detail");
        named(ambientOcclusion, "ambient_occlusion");
        named(antiAliasing, "anti_aliasing");
        named(smoothBorder, "smooth_border");
        named(caveCulling, "cave_culling");
        named(plantCutouts, "plant_cutouts");
        named(curvature, "earth_curvature");
        named(netherFog, "nether_fog");
        named(netherFogStart, "nether_fog_start");

        mod.addPage(builder.createOptionPage()
                .setName(Component.translatable("infinite.options.page.view"))
                .addOptionGroup(builder.createOptionGroup().addOption(enabled))
                .addOptionGroup(builder.createOptionGroup().addOption(quality).addOption(distance).addOption(detail))
                .addOptionGroup(builder.createOptionGroup().addOption(ingest).addOption(rendering)));
        mod.addPage(builder.createOptionPage()
                .setName(Component.translatable("infinite.options.page.advanced"))
                .addOptionGroup(builder.createOptionGroup().addOption(ambientOcclusion)
                        .addOption(antiAliasing).addOption(smoothBorder)
                        .addOption(caveCulling).addOption(plantCutouts).addOption(curvature))
                .addOptionGroup(builder.createOptionGroup().addOption(netherFog).addOption(netherFogStart)));
        mod.registerFlagHook(SettingsMenu::apply, START_OR_STOP, SWITCH_RENDERER, MOVE_DISTANCE,
                CHANGE_DETAIL, QUALITY);
    }

    private static boolean custom(ConfigState state) {
        return state.readEnumOption(QUALITY, Quality.class) == Quality.CUSTOM;
    }

    private static void named(StatefulOptionBuilder<?> option, String key) {
        option.setName(Component.translatable("infinite.options." + key));
        option.setTooltip(Component.translatable("infinite.options." + key + ".tooltip"));
    }

    private static void apply(Collection<Identifier> flags, ConfigState state) {
        var settings = ClientSettings.get();
        boolean plantCutouts = settings.plantCutouts;
        if (flags.contains(QUALITY) && settings.quality.isPreset()) {
            settings.choosePreset(settings.quality);
            settings.save();
            refresh(state);
        } else if (flags.contains(MOVE_DISTANCE) || flags.contains(CHANGE_DETAIL)) {
            settings.refreshQuality();
            settings.save();
            refresh(state);
        }

        var holder = LodRendererHolder.get();
        if (flags.contains(START_OR_STOP)) {
            if (settings.enabled) {
                LodSession.start();
            } else {
                LodSession.stop();
            }
            return;
        }
        if (holder != null && !flags.contains(SODIUM_RELOAD)) {
            if (flags.contains(SWITCH_RENDERER) || settings.plantCutouts != plantCutouts) {
                holder.infinite$shutdownRenderer();
                holder.infinite$createRenderer();
            } else if (flags.contains(MOVE_DISTANCE) || flags.contains(QUALITY)) {
                var renderer = holder.infinite$renderer();
                if (renderer != null) renderer.setRenderDistance(settings.lodDistanceChunks());
            }
        }
    }

    private static void refresh(ConfigState state) {
        if (state instanceof Config config) config.resetAllOptionsFromBindings();
    }
}
