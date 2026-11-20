#ifndef INFINITE_TERRAIN_QUAD
#define INFINITE_TERRAIN_QUAD


// Packed 64-bit LOD quad and 192-byte BlockModel layouts shared with Java

struct Quad {
    uint face; // 0:D, 1:U, 2:N, 3:S, 4:W, 5:E
    uvec2 size; // In voxels (1..8)
    uvec3 voxel;
    uint model;
    uint biome;
    uint light; // block << 4 | sky
    uint occlusion; // AO code, fluid flow turn, or fluid corner bits
};

const uint FLUID_CORNERS = 64u;


const uint FLUID_SKIRT = 32u;
const float FLUID_HEIGHTS[8] = float[8](0.0, 1.0 / 6.0, 1.0 / 3.0, 0.5, 2.0 / 3.0, 7.0 / 9.0, 8.0 / 9.0, 1.0);

Quad unpackQuad(uvec2 packed) {
    Quad quad;
    quad.face = packed.x & 7u;
    quad.size = uvec2(bitfieldExtract(packed.x, 3, 3), bitfieldExtract(packed.x, 6, 3)) + 1u;
    quad.voxel = uvec3(bitfieldExtract(packed.x, 19, 5), bitfieldExtract(packed.x, 14, 5), bitfieldExtract(packed.x, 9, 5));
    quad.light = packed.x >> 24;
    quad.model = packed.y & 0xFFFFu;
    quad.biome = bitfieldExtract(packed.y, 16, 9);
    quad.occlusion = packed.y >> 25;
    return quad;
}

uint faceAxis(uint face) {
    return face >> 1;
}

vec3 alongFace(uint axis, vec2 uv, float depth) {
    if (axis == 0u) return vec3(uv.x, depth, uv.y);
    if (axis == 1u) return vec3(uv.x, uv.y, depth);
    return vec3(depth, uv.x, uv.y);
}


struct BlockModel {
    uint faces[6];
    uint flags; // Bits: 0 two-sided, 1 biome tint, 2 translucent, 3 shaded, 4 fluid, 5 plant, 6 offset, 7 full-voxel fluid
    uint tint; // ARGB (0xFFFFFFFF = none) or biome tint table base index
    uint customId;
    uint uvPad; // std430 8-byte alignment for fluidUv
    vec2 fluidUv[18];
    uint padding[2]; // Pads struct to 192 bytes
};

const uint TINT_NEVER = 0u;
const uint TINT_GREYSCALE = 1u;
const uint TINT_ALWAYS = 2u;

vec4 faceExtent(uint faceWord) {
    uvec4 nibbles = uvec4(bitfieldExtract(faceWord, 0, 4), bitfieldExtract(faceWord, 8, 4),
            bitfieldExtract(faceWord, 4, 4), bitfieldExtract(faceWord, 12, 4));
    return (vec4(nibbles) + vec4(0.0, 0.0, 1.0, 1.0)) / 16.0;
}

float faceInset(uint faceWord) {
    uint inset = bitfieldExtract(faceWord, 16, 6);
    return float(inset == 63u ? 64u : inset) / 64.0;
}

bool faceLive(uint faceWord) {
    return bitfieldExtract(faceWord, 26, 1) != 0u;
}

bool faceCutout(uint faceWord, uvec2 quadSize) {
    return bitfieldExtract(faceWord, 22, 1) != 0u || (bitfieldExtract(faceWord, 23, 1) != 0u && any(greaterThan(quadSize, uvec2(1u))));
}

uint faceTintMode(uint faceWord) {
    return bitfieldExtract(faceWord, 24, 2);
}

uint faceOpaqueMip(uint faceWord) {
    return bitfieldExtract(faceWord, 27, 2);
}

bool modelTintFromBiome(BlockModel model) {
    return (model.flags & 2u) != 0u;
}

bool modelShaded(BlockModel model) {
    return (model.flags & 8u) != 0u;
}

const uint MODEL_TWO_SIDED = 1u;
const uint MODEL_LIVE_UV = 16u;
const uint MODEL_PLANT = 32u;
const uint MODEL_OFFSET = 64u;
const uint MODEL_FLUID = 128u;
const uint MODEL_FOUR_PLANES = 256u;
const uint MODEL_LEAF = 512u;

uint plantPlane(Quad quad) {
    return (quad.face & 1u) + (quad.size.x > 1u ? 2u : 0u);
}

vec2 plantOffset(ivec2 block) {
    uint seed = uint(block.x) * 3129871u ^ uint(block.y) * 116129781u;
    seed = seed * seed * 42317861u + seed * 11u;
    return vec2(float(seed >> 16 & 15u), float(seed >> 24 & 15u)) / 15.0 * 0.5 - 0.25;
}
#endif
