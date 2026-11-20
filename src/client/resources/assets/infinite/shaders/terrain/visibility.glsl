#ifndef INFINITE_TERRAIN_VISIBILITY
#define INFINITE_TERRAIN_VISIBILITY


// Visibility buffer packing, depth-key encoding, barycentric interpolation, and surface / cutout helpers

#import <infinite:common/depth.glsl>
#import <infinite:terrain/expand.glsl>

layout(binding = 0) uniform sampler2D atlas;
layout(binding = 3) uniform sampler2D blockAtlas;

const vec2 TILE_SIZE = 1.0 / vec2(3.0 * 256.0, 2.0 * 256.0);

#define ATLAS_SCALE vec2(1.0, atlasScaleV)

// Depth key ordered so nearer values are larger uints for atomicMax
uint depthKey(float depth) {
#ifdef USE_REVERSE_Z
    return floatBitsToUint(depth);
#else
    return ~floatBitsToUint(depth);
#endif
}

float keyDepth(uint key) {
#ifdef USE_REVERSE_Z
    return uintBitsToFloat(key);
#else
    return uintBitsToFloat(~key);
#endif
}

#ifdef VISIBILITY_SPLIT
layout(binding = 7, std430) restrict buffer Visibility {
    uint visibility[]; // Two uints per pixel: [key, payload]
};

uint visibilityKeyAt(uint pixel) {
    return visibility[pixel * 2u];
}

uint visibilityPayloadAt(uint pixel) {
    return visibility[pixel * 2u + 1u];
}

void visibilityKeep(uint pixel, uint key, uint payload) {
    atomicMax(visibility[pixel * 2u], key);
}

void visibilityClaim(uint pixel, uint key, uint payload) {
    if (visibility[pixel * 2u] == key) visibility[pixel * 2u + 1u] = payload;
}
#else
layout(binding = 7, std430) restrict buffer Visibility {
    uint64_t visibility[];
};

uint visibilityKeyAt(uint pixel) {
    return uint(visibility[pixel] >> 32);
}

uint visibilityPayloadAt(uint pixel) {
    return uint(visibility[pixel] & 0xFFFFFFFFul);
}

void visibilityKeep(uint pixel, uint key, uint payload) {
    atomicMax(visibility[pixel], (uint64_t(key) << 32) | uint64_t(payload));
}

void visibilityClaim(uint pixel, uint key, uint payload) {
}
#endif

vec3 barycentrics(mat3 inverseCorners, vec2 ndc) {
    vec3 weights = inverseCorners * vec3(ndc, 1.0);
    return weights / (weights.x + weights.y + weights.z);
}

mat3 cornerMatrix(vec4 a, vec4 b, vec4 c) {
    return inverse(mat3(a.xyw, b.xyw, c.xyw));
}

struct Surface {
    vec2 uv;
    vec2 voxel;
    vec2 texel;
    vec2 liveTexel;
    vec2 texelDx, texelDy;
    vec2 liveDx, liveDy;
    bool live;
};

float liveBlocks(QuadFlats f) {
    return (f.modelBits & MODEL_PLANT) != 0u ? 1.0 : f.voxelBlocks;
}

vec2 clampToTile(vec2 faceUv, QuadFlats f) {
    return clamp(faceUv, vec2(0.0), vec2(f.lastTile) + 1.0 - 1.0 / 4096.0);
}

vec2 unwrappedOf(vec2 faceUv, QuadFlats f) {
    return clampToTile(faceUv, f) * TILE_SIZE;
}

vec2 liveUnwrappedOf(vec2 faceUv, QuadFlats f) {
    vec2 liveFace = clampToTile(faceUv, f) * liveBlocks(f);
    return f.liveUv + liveFace.x * f.liveUvU + liveFace.y * f.liveUvV;
}

Surface surfaceAt(QuadFlats f, vec2 faceUv, vec2 faceUvX, vec2 faceUvY) {
    Surface s;
    s.uv = clampToTile(faceUv, f);
    s.voxel = floor(s.uv);
    vec2 within = s.uv - s.voxel;
    s.live = (f.modelBits & (MODEL_LIVE_UV | MODEL_PLANT)) != 0u;
    vec2 liveFace = s.uv * liveBlocks(f);
    vec2 unwrapped = s.uv * TILE_SIZE;
    vec2 liveUnwrapped = f.liveUv + liveFace.x * f.liveUvU + liveFace.y * f.liveUvV;
    vec2 atlasScale = ATLAS_SCALE;
    s.texelDx = (unwrappedOf(faceUvX, f) - unwrapped) * atlasScale;
    s.texelDy = (unwrappedOf(faceUvY, f) - unwrapped) * atlasScale;
    s.liveDx = liveUnwrappedOf(faceUvX, f) - liveUnwrapped;
    s.liveDy = liveUnwrappedOf(faceUvY, f) - liveUnwrapped;
    vec2 liveWithin = liveFace - floor(liveFace);
    s.liveTexel = f.liveUv + liveWithin.x * f.liveUvU + liveWithin.y * f.liveUvV;
    s.texel = (f.atlasTile + within * TILE_SIZE) * atlasScale;
    return s;
}

vec4 sampleSurface(Surface s) {
    return s.live ? textureGrad(blockAtlas, s.liveTexel, s.liveDx, s.liveDy) : textureGrad(atlas, s.texel, s.texelDx, s.texelDy);
}

bool cutAway(QuadFlats f, vec2 faceUv, vec2 faceUvX, vec2 faceUvY) {
    bool leaf = (f.modelBits & MODEL_LEAF) != 0u;
    bool cutout = (f.faceFlags & 1u) != 0u && !(leaf && f.voxelBlocks >= 4.0);
    if (!cutout) return false;
    uint opaqueMip = (f.faceFlags >> 3) & 3u;
    if (opaqueMip != 0u) {
        vec2 uv = clampToTile(faceUv, f);
        vec2 dx = clampToTile(faceUvX, f) - uv;
        vec2 dy = clampToTile(faceUvY, f) - uv;
        if (max(dot(dx, dx), dot(dy, dy)) * 256.0 >= float(1u << (opaqueMip * 2u))) return false;
    }
    Surface s = surfaceAt(f, faceUv, faceUvX, faceUvY);
    bool plant = (f.modelBits & MODEL_PLANT) != 0u;
    return (plant ? textureLod(blockAtlas, s.liveTexel, 0.0).a : sampleSurface(s).a) <= 0.1;
}
#endif
