#ifndef INFINITE_TERRAIN_EXPAND
#define INFINITE_TERRAIN_EXPAND


// Shared quad vertex expansion and flat shading setup for terrain rendering and software rasterisation

#import <infinite:common/frame.glsl>
#import <infinite:common/curvature.glsl>
#import <infinite:common/lod_position.glsl>
#import <infinite:terrain/quad.glsl>
#import <infinite:terrain/geometry.glsl>
layout(binding = 0, std430) restrict readonly buffer Models {
    BlockModel models[];
};

layout(binding = 1, std430) restrict readonly buffer BiomeTints {
    uint biomeTints[]; // ARGB per biome
};

layout(binding = 2, std430) restrict readonly buffer DrawPositions {
    uvec2 drawPositions[];
};

layout(binding = 5, std430) restrict readonly buffer DrawCorners {
    uvec4 drawCorners[];
};

#ifndef TRANSLUCENT

layout(binding = 4, std430) restrict readonly buffer DrawBlocks {
    uvec2 drawBlocks[];
};

// Per draw slot: starting index of section AO tables, or 0 if none
layout(binding = 6, std430) restrict readonly buffer DrawAo {
    uint drawAo[];
};
#endif

const float GROW = 0.00005; // Tiny overlap between adjacent quads to avoid hairline gaps
float directionalShade(bool shaded, uint face) {
    if (!shaded || face == 1u) return faceShade.x;
    if (face == 0u) return faceShade.y;
    return faceAxis(face) == 1u ? faceShade.z : faceShade.w;
}

vec3 unpackRgb(uint argb) {
    return vec3((uvec3(argb) >> uvec3(16u, 8u, 0u)) & 0xFFu) / 255.0;
}

bool droppedBelowCamera(vec3 origin, uint lod) {
    if (curveDrop <= 0.0) return false;
    vec3 size = vec3(32.0 * float(1u << lod));
    vec2 farthest = max(abs(origin.xz), abs(origin.xz + size.xz));
    return origin.y - curvatureDrop(farthest, curveDrop, curveStart) <= 0.0;
}

bool facesCamera(uint face, ivec3 relative, vec3 origin, uint lod) {
    if (face > 5u) return true;
    switch (face) {
        case 0u: return relative.y >= 0;
        case 1u: return relative.y <= 0 || droppedBelowCamera(origin, lod);
        case 2u: return relative.z >= 0;
        case 3u: return relative.z <= 0;
        case 4u: return relative.x >= 0;
    }
    return relative.x <= 0;
}

// Flow turn bits: 0 east, 1 north, 2 west, 3 south
uint slopeTurn(vec4 heights) {
    float east = heights.y + heights.w - heights.x - heights.z;
    float south = heights.z + heights.w - heights.x - heights.y;
    if (abs(east) >= abs(south)) return east > 0.0 ? 2u : 0u;
    return south > 0.0 ? 1u : 3u;
}

struct QuadSetup {
    Quad quad;
    BlockModel model;
    uint quadIndex;
    uint drawSlot;
    uvec4 corner4;
    float scale;
    vec3 sectionOrigin;
    bool plant;
    uint faceWord;
    bool corners;
    vec4 cornerHeights; // Fluid corner heights: NW, NE, SW, SE
    uint skirtSides;    // Bits 0-3: north, south, west, east
    bool standIn;
    uint fade;
};

QuadSetup setupQuad(uint quadIndex, uint drawSlot) {
    QuadSetup s;
    s.quadIndex = quadIndex;
    s.drawSlot = drawSlot;
    s.quad = unpackQuad(loadQuad(quadIndex));
    s.corner4 = drawCorners[drawSlot];
    s.scale = uintBitsToFloat(s.corner4.w & ~0x1FFFFFu);
    s.skirtSides = s.corner4.w & 0xFu;
    s.standIn = (s.corner4.w & 0x10u) != 0u;
    s.fade = (s.corner4.w >> 5) & 0xFFFFu;
    s.sectionOrigin = vec3(ivec3(s.corner4.xyz) - (cameraSection << 5));
    s.model = models[s.quad.model];
    s.plant = (s.model.flags & MODEL_PLANT) != 0u;
    s.faceWord = s.plant ? 0u : s.model.faces[min(s.quad.face, 5u)];
    s.corners = (s.model.flags & MODEL_FLUID) != 0u && (s.quad.occlusion & FLUID_CORNERS) != 0u;
    s.cornerHeights = vec4(1.0);
    if (s.corners) {
        s.cornerHeights = vec4(FLUID_HEIGHTS[s.quad.size.x - 1u], FLUID_HEIGHTS[s.quad.size.y - 1u],
                FLUID_HEIGHTS[s.quad.occlusion & 7u], FLUID_HEIGHTS[(s.quad.occlusion >> 3) & 7u]);
        s.quad.size = uvec2(1u);
    }
    return s;
}

bool fluidSkirt(QuadSetup s) {
    return (s.model.flags & MODEL_FLUID) != 0u && (s.quad.occlusion & (FLUID_SKIRT | FLUID_CORNERS)) == FLUID_SKIRT;
}

bool skirtUnwanted(QuadSetup s) {
    return fluidSkirt(s) && (s.skirtSides & (1u << (s.quad.face - 2u))) == 0u;
}

#ifndef TRANSLUCENT
bool quadTurnedAway(QuadSetup s) {
    if (curveDrop > 0.0 && (s.model.flags & MODEL_TWO_SIDED) == 0u && s.quad.face == 1u) {
        uvec2 section = drawPositions[s.drawSlot];
        uint lod = lodLevel(section);
        return !facesCamera(s.quad.face, lodPosition(section) - (cameraSection >> lod), s.sectionOrigin - cameraOffset, lod);
    }
    return false;
}
#endif

vec3 quadCorner(QuadSetup s, uint corner, out vec2 faceUv) {
    Quad quad = s.quad;
    BlockModel model = s.model;
    float scale = s.scale;
    vec2 cornerUv = vec2((corner >> 1) & 1u, corner & 1u);
    vec3 position;
    if (s.plant) {
        uint plane = plantPlane(quad);
        uint at = plane < 2u ? plane * 5u + 3u : 11u + (plane - 2u) * 2u;
        // Coarse plant height field: bit 0 = full-voxel stalk height, bits 1-2 = sub-voxel x/z column
        uint spot = quad.size.y - 1u;
        vec2 column = vec2((spot >> 1) & 1u, (spot >> 2) & 1u);
        float height = (spot & 1u) != 0u ? scale : 1.0;
        vec2 xz = mix(model.fluidUv[at], model.fluidUv[at + 1u], cornerUv.x) + column;
        if ((model.flags & MODEL_OFFSET) != 0u) {
            ivec3 origin = ivec3(s.corner4.xyz);
            xz += plantOffset(origin.xz + ivec2(quad.voxel.xz) * int(scale) + ivec2(column));
        }
        vec3 local = vec3(xz.x, model.fluidUv[10].y, xz.y) + cornerUv.y * vec3(model.fluidUv[15].x,
                model.fluidUv[10].x * height, model.fluidUv[15].y);
        position = s.sectionOrigin + vec3(quad.voxel) * scale + local;
        faceUv = cornerUv;
    } else {
        bool fills = (model.flags & MODEL_FLUID) != 0u && scale > 1.0;
        vec4 extent = fills ? vec4(0.0, 0.0, 1.0, 1.0) : faceExtent(s.faceWord);
        vec2 start = extent.xy - GROW;
        vec2 span = extent.zw - start + vec2(quad.size - 1u) + vec2(GROW);
        vec2 uv = start + span * cornerUv;
        float inset = fills ? 0.0 : faceInset(s.faceWord);
        if ((model.flags & MODEL_FLUID) != 0u) inset = round(inset * 9.0) / 9.0;
        float depth = (quad.face & 1u) == 0u ? inset : 1.0 - inset;
        vec3 local = alongFace(faceAxis(quad.face), uv, depth);
        if (s.corners) {
            vec2 heights = local.z > 0.5 ? s.cornerHeights.zw : s.cornerHeights.xy;
            float height = local.x > 0.5 ? heights.y : heights.x;
            if (quad.face == 1u || local.y > 0.5) local.y = height;
            if (faceAxis(quad.face) == 1u) uv.y = local.y;
            else if (faceAxis(quad.face) == 2u) uv.x = local.y;
        }
        position = s.sectionOrigin + (vec3(quad.voxel) + local) * scale;
        faceUv = uv;
    }
    if (curveDrop > 0.0) position.y -= curvatureDrop(position.xz - cameraOffset.xz, curveDrop, curveStart);
    return position;
}

struct QuadFlats {
    vec2 atlasTile;
    uvec2 lastTile;
    vec3 shade;
    vec3 tint;
    uint faceFlags;
    vec2 liveUv;
    vec2 liveUvU;
    vec2 liveUvV;
    uint modelBits;
    float voxelBlocks;
    uint nearVanilla;
    uint aoTable;
};

QuadFlats quadFlats(QuadSetup s) {
    Quad quad = s.quad;
    BlockModel model = s.model;
    bool plant = s.plant;
    float scale = s.scale;
    QuadFlats f;
    f.liveUv = vec2(0.0);
    f.liveUvU = vec2(0.0);
    f.liveUvV = vec2(0.0);

    vec2 modelCell = vec2(quad.model & 0xFFu, (quad.model >> 8) & 0xFFu);
    f.lastTile = plant ? uvec2(0u) : quad.size - 1u;
    f.atlasTile = vec2(0.0);
    if (!plant) {
        vec2 faceCell = vec2(quad.face >> 1, quad.face & 1u) / vec2(3.0, 2.0);
        f.atlasTile = (modelCell + faceCell) / 256.0;
    }

    bool skirt = (model.flags & MODEL_FLUID) != 0u && (quad.occlusion & (FLUID_SKIRT | FLUID_CORNERS)) == FLUID_SKIRT;
    uint lookFace = skirt ? 1u : quad.face;
    float facing = plant ? faceShade.x : directionalShade(modelShaded(model), lookFace);
    f.shade = lightColour(quad.light) * facing;
    // Fluid under ice: vanilla's ambient occlusion shades it to a fifth
    if ((model.flags & MODEL_FLUID) != 0u && quad.face == 1u && (quad.occlusion & (FLUID_COVERED | FLUID_CORNERS)) == FLUID_COVERED) f.shade *= 0.2;    f.tint = unpackRgb(modelTintFromBiome(model) ? biomeTints[model.tint + quad.biome] : model.tint);
    f.aoTable = 0u;
    if (plant) {
        f.faceFlags = 1u | ((model.tint == 0xFFFFFFFFu ? TINT_NEVER : TINT_ALWAYS) << 1);
    } else {
        f.faceFlags = uint(faceCutout(s.faceWord, quad.size)) | (faceTintMode(s.faceWord) << 1)
                | (faceOpaqueMip(s.faceWord) << 3);
        if ((model.flags & MODEL_FLUID) == 0u) {
#ifndef TRANSLUCENT
            if (quad.occlusion == AO_TABLE) {
                uint group = (model.flags & MODEL_TWO_SIDED) != 0u ? 1u : 2u + quad.face;
                f.aoTable = aoTableOf(s.quadIndex, drawAo[s.drawSlot], group, quad.light);
                f.shade = vec3(facing);
            } else
#endif
            if (ambientOcclusion != 0u) f.faceFlags |= aoCorners(quad.occlusion) << 8;
        }
    }

    f.modelBits = model.flags & (MODEL_LIVE_UV | MODEL_PLANT | MODEL_FLUID);
    if (!plant && scale == 1.0 && faceLive(s.faceWord)) f.modelBits |= MODEL_LIVE_UV;
    f.voxelBlocks = scale;
    if (plant) {
        uint at = (plantPlane(quad) & 1u) * 5u;
        f.liveUv = model.fluidUv[at];
        f.liveUvU = model.fluidUv[at + 1u] - f.liveUv;
        f.liveUvV = model.fluidUv[at + 2u] - f.liveUv;
    } else if (f.modelBits != 0u) {
        uint at = lookFace * 3u;
        f.liveUv = model.fluidUv[at];
        f.liveUvU = model.fluidUv[at + 1u];
        f.liveUvV = model.fluidUv[at + 2u];
        if ((f.modelBits & MODEL_FLUID) != 0u && quad.face == 1u) {
            uint turn = s.corners ? slopeTurn(s.cornerHeights) : quad.occlusion & 3u;
            if (turn != 0u) {
                vec2 centre = f.liveUv + 0.5 * f.liveUvU + 0.5 * f.liveUvV;
                for (uint i = 0u; i < turn; i++) {
                    vec2 across = f.liveUvU;
                    f.liveUvU = f.liveUvV;
                    f.liveUvV = -across;
                }
                f.liveUv = centre - 0.5 * f.liveUvU - 0.5 * f.liveUvV;
            }
        }
    }
    f.modelBits |= model.flags & MODEL_LEAF;

    vec3 halfSize = vec3(16.0 * scale);
    vec3 centre = s.sectionOrigin - cameraOffset + halfSize;
    vec3 nearest = max(abs(centre) - halfSize, vec3(0.0));
    float vanillaReach = vanillaDistance + 64.0;
    f.nearVanilla = (dot(nearest, nearest) <= vanillaReach * vanillaReach ? (s.standIn ? 3u : 1u) : 0u) | s.fade << 8;
    return f;
}
#endif
