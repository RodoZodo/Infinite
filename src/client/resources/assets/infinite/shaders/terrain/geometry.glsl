#ifndef INFINITE_TERRAIN_GEOMETRY
#define INFINITE_TERRAIN_GEOMETRY


// Shared accessors for paged LoD geometry quads, per cell AO / light tables, and the lightmap

layout(set = 1, binding = 1, std430) restrict readonly buffer GeometryPage {
    uvec2 quads[];
} geometryPages[GEOMETRY_PAGE_COUNT];

const uint AO_CORNERS[AO_CORNER_WORDS] = uint[](AO_CORNER_TABLE);

const uint AO_TABLE = 127u; // Sentinel AO code for quads using a per-cell AO/light table

uvec2 loadQuad(uint index) {
    return geometryPages[min(index >> GEOMETRY_PAGE_SHIFT, uint(GEOMETRY_PAGE_COUNT) - 1u)]
            .quads[index & ((1u << GEOMETRY_PAGE_SHIFT) - 1u)];
}

uint loadWord(uint index) {
    return loadQuad(index >> 1)[index & 1u];
}

uint aoCorners(uint code) {
    return bitfieldExtract(AO_CORNERS[code >> 2], int(code & 3u) * 8, 8);
}


uint aoTableOf(uint quadIndex, uint tables, uint group, uint low) {
    uint word = tables * 2u;
    uint groupStart = tables - loadWord(word + group);
    uint table = loadWord(word + 8u + group) + (quadIndex - groupStart);
    uint mark = loadWord(word + 17u + (table >> 2));
    return word * 2u + mark + ((low - mark) & 255u);
}


// 16-bit table entry per cell (row-major): low byte = AO code, high byte = light

uint tableEntry(uint table, uint cell) {
    uint at = table + cell;
    return bitfieldExtract(loadWord(at >> 1), int(at & 1u) * 16, 16);
}

uint tableCode(uint entry) {
    return entry & 0x7Fu;
}

uint tableLight(uint entry) {
    return entry >> 8;
}

layout(binding = 1) uniform sampler2D lightmap;

vec3 lightColour(uint light) {
    return textureLod(lightmap, (vec2(light >> 4, light & 15u) + 0.5) / 16.0, 0.0).rgb;
}
#endif
