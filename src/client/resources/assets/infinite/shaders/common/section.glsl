#ifndef INFINITE_COMMON_SECTION
#define INFINITE_COMMON_SECTION
#import <infinite:common/lod_position.glsl>


// Metadata for one meshed LoD section and helpers for packed selection and group fields

struct SectionMetadata {
    uvec4 header;
    uvec4 counts;
    uvec4 extra;
};

const uint SELECTED_STAND_IN = 1u << 28;

uint selectedMesh(uint entry) {
    return entry & 0xFFFFFFu;
}

uint selectedSkirtSides(uint entry) {
    return entry >> 24 & 0xFu;
}

bool selectedStandIn(uint entry) {
    return (entry & SELECTED_STAND_IN) != 0u;
}

const uint GROUP_TRANSLUCENT = 0u;
const uint GROUP_DOUBLE_SIDED = 1u;// Including plant crosses

uint sectionFirstQuad(SectionMetadata section) {
    return section.header.w;
}

uint sectionQuadCount(SectionMetadata section, uint group) {
    return bitfieldExtract(section.counts[group >> 1], int(group & 1u) * 16, 16);
}

// Bounding box in section voxel units (0..31) (size >= 1)
ivec3 sectionBoxMin(SectionMetadata section) {
    uint box = section.header.z;
    return ivec3(bitfieldExtract(box, 0, 5), bitfieldExtract(box, 5, 5), bitfieldExtract(box, 10, 5));
}

bool sectionAnimated(SectionMetadata section) {
    return (section.header.z & (1u << 30)) != 0u;
}

ivec3 sectionBoxSize(SectionMetadata section) {
    uint box = section.header.z;
    return ivec3(bitfieldExtract(box, 15, 5), bitfieldExtract(box, 20, 5), bitfieldExtract(box, 25, 5)) + 1;
}

bool sectionMayDiscard(SectionMetadata section) {
    return (section.header.z & (1u << 31)) != 0u;
}

bool groupMayDiscard(SectionMetadata section, uint group) {
    return (section.extra.x & (1u << group)) != 0u;
}

// Geometry store index where the sections AO cell tables start, or 0 if none
uint sectionAoTables(SectionMetadata section) {
    return section.extra.y == 0u ? 0u : sectionFirstQuad(section) + section.extra.y;
}
#endif
