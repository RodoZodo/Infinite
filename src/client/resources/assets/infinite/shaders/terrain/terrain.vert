// Transforms LoD quads to clip space, passing shading attributes from the provoking vertex (corner 1)

#import <infinite:terrain/expand.glsl>

layout(location = 0) out vec2 faceUv;
layout(location = 1) flat out vec2 atlasTile;
layout(location = 2) flat out uvec2 lastTile;
layout(location = 3) flat out vec3 shade;
layout(location = 4) flat out vec3 tint;
layout(location = 5) flat out uint faceFlags;   // Bit 0: cutout, bits 1-2: tint mode, bits 8-15: AO corners
layout(location = 6) flat out vec2 liveUv;
layout(location = 7) flat out vec2 liveUvU;
layout(location = 8) flat out vec2 liveUvV;
layout(location = 9) flat out uint modelBits;
layout(location = 10) flat out float voxelBlocks;
layout(location = 11) flat out uint nearVanilla;    // Bit 0: near vanilla, bit 1: standIn, bits 8+: fade
layout(location = 12) flat out uint aoTable;

void main() {
    uint corner = uint(gl_VertexIndex) & 3u;
#ifdef TRANSLUCENT
    uint quadIndex = uint(gl_VertexIndex) >> 2;
    uint drawSlot = uint(gl_InstanceIndex);
#else
    uvec2 block = drawBlocks[gl_InstanceIndex];
    uint withinBlock = uint(gl_VertexIndex) >> 2;
    if (withinBlock > (block.y >> 22)) {
        gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
        return;
    }
    uint quadIndex = block.x + withinBlock;
    uint drawSlot = block.y & 0x3FFFFFu;
#endif
    QuadSetup quad = setupQuad(quadIndex, drawSlot);
#ifdef TRANSLUCENT
#ifdef SKIRTS
    if (!fluidSkirt(quad) || skirtUnwanted(quad)) {
#else
    uvec2 section = drawPositions[drawSlot];
    bool turnedAway = (quad.model.flags & (MODEL_FLUID | MODEL_TWO_SIDED | MODEL_PLANT)) == 0u && !facesCamera(quad.quad.face,
            lodPosition(section) - (cameraSection >> lodLevel(section)), quad.sectionOrigin - cameraOffset, lodLevel(section));
    if (fluidSkirt(quad) || turnedAway) {
#endif
        gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
        return;
    }
#else
    if (quadTurnedAway(quad)) {
        gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
        return;
    }
#endif
    vec3 position = quadCorner(quad, corner, faceUv);
    gl_Position = viewProjection * vec4(position, 1.0);

    if (corner != 1u) return;
    QuadFlats flats = quadFlats(quad);
    atlasTile = flats.atlasTile;
    lastTile = flats.lastTile;
    shade = flats.shade;
    tint = flats.tint;
    faceFlags = flats.faceFlags;
    liveUv = flats.liveUv;
    liveUvU = flats.liveUvU;
    liveUvV = flats.liveUvV;
    modelBits = flats.modelBits;
    voxelBlocks = flats.voxelBlocks;
    nearVanilla = flats.nearVanilla;
    aoTable = flats.aoTable;
}
