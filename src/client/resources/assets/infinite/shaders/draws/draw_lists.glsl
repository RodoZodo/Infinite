#ifndef INFINITE_DRAWS_DRAW_LISTS
#define INFINITE_DRAWS_DRAW_LISTS


// Shared buffers and helpers for building LOD indirect draw lists
// Defines: SELECTED_CAPACITY, TRANSLUCENT_CAPACITY, SKIRT_CAPACITY, DISTANCE_BUCKETS, MAX_QUADS_PER_DRAW, BLOCK_QUADS

#import <infinite:common/frame.glsl>
#import <infinite:common/section.glsl>

struct DrawCommand {
    uint indexCount;
    uint instanceCount;
    uint firstIndex;
    int vertexOffset;
    uint firstInstance;
};

// Indirect draw arguments and counters reset each frame by draws/prepare.comp
layout(binding = 0, std430) restrict buffer Control {
    uvec3 generateGroups;
    uint translucentCount;
    uint newlyVisibleBlockCapacity;
    DrawCommand occlusionDraw;
    uint translucentSections;
    uint solidBlockCapacity;
    uint maskedBlockCapacity;
    uint newlySolidBase;
    uint newlyMaskedBase;
    DrawCommand newlySolidDraw;
    DrawCommand newlyMaskedDraw;
    uint solidBlocks;
    uint maskedBlocks;
    uint newlySolidBlocks;
    uint newlyMaskedBlocks;
    DrawCommand solidDraw;
    DrawCommand maskedDraw;
    uint solidQuads;
    uint maskedQuads;
    uint newlySolidQuads;
    uint newlyMaskedQuads;
    uint skirtCount;    // Fluid skirt draws following translucent draws in the command buffer
};

layout(binding = 1, std430) restrict buffer Selected {
    uint selectedCount;
    uint selectedSections[];
};

uint selectedTotal() {
    return min(selectedCount, uint(SELECTED_CAPACITY));
}

uint drawsFor(uint quads) {
    return (quads + MAX_QUADS_PER_DRAW - 1u) / MAX_QUADS_PER_DRAW;
}

uint blocksFor(uint quads) {
    return (quads + BLOCK_QUADS - 1u) / BLOCK_QUADS;
}

DrawCommand quadDraw(uint firstQuad, uint quads, uint drawSlot) {
    return DrawCommand(quads * 6u, 1u, 0u, int(firstQuad * 4u), drawSlot);
}

uint distanceBucket(SectionMetadata section) {
    uint lod = lodLevel(section.header.xy);
    uvec3 offset = uvec3(abs(lodPosition(section.header.xy) - (cameraSection >> lod)));
    uint distance = (offset.x + offset.y + offset.z) << lod;
    return (DISTANCE_BUCKETS - 1u) - min(distance, DISTANCE_BUCKETS - 1u);
}
#endif
