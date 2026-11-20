// Shades LoD terrain quads with tiled / live atlas sampling, vanilla masking, cutouts, and baked AO

#import <infinite:common/depth.glsl>
#import <infinite:common/frame.glsl>
#import <infinite:terrain/quad.glsl>
#import <infinite:terrain/geometry.glsl>

layout(binding = 0) uniform sampler2D atlas;
layout(binding = 2) uniform sampler2D vanillaMask;
layout(binding = 3) uniform sampler2D blockAtlas;
layout(binding = 4) uniform sampler2D border;
#ifdef TRANSLUCENT

layout(binding = 0, r32f) uniform restrict writeonly image2D waterCut;
#endif

layout(location = 0) in vec2 faceUv;
layout(location = 1) flat in vec2 atlasTile;
layout(location = 2) flat in uvec2 lastTile;
layout(location = 3) flat in vec3 shade;
layout(location = 4) flat in vec3 tint;
layout(location = 5) flat in uint faceFlags;
layout(location = 6) flat in vec2 liveUv;
layout(location = 7) flat in vec2 liveUvU;
layout(location = 8) flat in vec2 liveUvV;
layout(location = 9) flat in uint modelBits;
layout(location = 10) flat in float voxelBlocks;
layout(location = 11) flat in uint nearVanilla;
layout(location = 12) flat in uint aoTable;

layout(location = 0) out vec4 colour;

const vec2 TILE_SIZE = 1.0 / vec2(3.0 * 256.0, 2.0 * 256.0);

#define ATLAS_SCALE vec2(1.0, atlasScaleV)

bool greyscale(vec3 texel) {
    return abs(texel.r - texel.g) < 0.02 && abs(texel.g - texel.b) < 0.02;
}

void main() {
    vec2 uv = clamp(faceUv, vec2(0.0), vec2(lastTile) + 1.0 - 1.0 / 4096.0);
    vec2 voxel = floor(uv);
    vec2 within = uv - voxel;
    bool live = (modelBits & (MODEL_LIVE_UV | MODEL_PLANT)) != 0u;
    vec2 liveFace = (modelBits & MODEL_PLANT) != 0u ? uv : uv * voxelBlocks;
    vec2 unwrapped = uv * TILE_SIZE;
    vec2 liveUnwrapped = liveUv + liveFace.x * liveUvU + liveFace.y * liveUvV;
    // Compute derivatives before discards and helper exit so 2x2 quad mip selection stays valid
    vec2 atlasScale = ATLAS_SCALE;
    vec2 texelDx = dFdx(unwrapped) * atlasScale, texelDy = dFdy(unwrapped) * atlasScale;
    vec2 liveDx = dFdx(liveUnwrapped), liveDy = dFdy(liveUnwrapped);
#ifndef SOLID
    float depthDx = dFdx(gl_FragCoord.z), depthDy = dFdy(gl_FragCoord.z);
#endif
    if (gl_HelperInvocation) return;

#ifndef SOLID
    if ((nearVanilla >> 8) != 0u && fadedOut(nearVanilla >> 8, gl_FragCoord.xy)) discard;
    if ((nearVanilla & 1u) != 0u) {
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        bool dissolved = texelFetch(border, pixel, 0).r > 0.0;
        float centreDepth = gl_FragCoord.z + jitterPixels.x * depthDx + jitterPixels.y * depthDy;
        if (!dissolved && depthNearer(centreDepth, texelFetch(vanillaMask, pixel >> VANILLA_MASK_SHIFT, 0).r)) {
#ifdef TRANSLUCENT
            imageStore(waterCut, pixel, vec4(-1.0));
#endif
            discard;
        }
        if (dissolved && voxelBlocks > 1.0) discard;
        if ((nearVanilla & 2u) != 0u && withinVanillaDistance(gl_FragCoord.xy, vec2(textureSize(border, 0)), gl_FragCoord.z)) discard;
    }
#endif

    vec2 liveWithin = liveFace - floor(liveFace);
    vec2 liveTexel = liveUv + liveWithin.x * liveUvU + liveWithin.y * liveUvV;
    vec2 texel = (atlasTile + within * TILE_SIZE) * atlasScale;
    colour = live ? textureGrad(blockAtlas, liveTexel, liveDx, liveDy) : textureGrad(atlas, texel, texelDx, texelDy);

    uint tintMode = (faceFlags >> 1) & 3u;
#ifdef TRANSLUCENT
    bool fluid = (modelBits & MODEL_FLUID) != 0u;
    vec4 exact = colour;
    if (!fluid || tintMode == TINT_GREYSCALE) exact = live ? textureLod(blockAtlas, liveTexel, 0.0) : textureLod(atlas, texel, 0.0);
    if (exact.a == 0.0) discard;
    if (tintMode == TINT_ALWAYS || (tintMode == TINT_GREYSCALE && greyscale(exact.rgb))) colour.rgb *= tint;
#else
#ifndef SOLID
    bool leaf = (modelBits & MODEL_LEAF) != 0u;
    bool cutout = (faceFlags & 1u) != 0u && !(leaf && voxelBlocks >= 4.0);
    bool plant = (modelBits & MODEL_PLANT) != 0u;
    if (cutout && (plant ? textureLod(blockAtlas, liveTexel, 0.0).a : colour.a) <= 0.1) discard;
#endif
    if (tintMode == TINT_ALWAYS) {
        colour.rgb *= tint;
    } else if (tintMode == TINT_GREYSCALE) {
        vec4 exact = live ? textureLod(blockAtlas, liveTexel, 0.0) : textureLod(atlas, texel, 0.0);
        if (greyscale(exact.rgb)) colour.rgb *= tint;
    }
    colour.a = 1.0;
#endif

    colour.rgb *= shade;

    uint corners = faceFlags >> 8;
    if (aoTable != 0u) {
        uint entry = tableEntry(aoTable, uint(voxel.y) * (lastTile.x + 1u) + uint(voxel.x));
        colour.rgb *= lightColour(tableLight(entry));
        if (ambientOcclusion != 0u) corners = aoCorners(tableCode(entry));
    }
    if (corners != 0u) {
        vec4 brightness = 1.0 - 0.2 * vec4((uvec4(corners) >> uvec4(0u, 2u, 4u, 6u)) & 3u);
        vec2 within = clamp(uv - voxel, 0.0, 1.0);
        vec2 alongU = mix(brightness.xz, brightness.yw, within.x);
        colour.rgb *= mix(alongU.x, alongU.y, within.y);
    }
}
