#ifndef INFINITE_COMMON_LOD_POSITION
#define INFINITE_COMMON_LOD_POSITION

uint lodLevel(uvec2 packed) {
    return packed.x >> 28;
}

ivec3 lodPosition(uvec2 packed) {
    int x = bitfieldExtract(int(packed.y), 4, 24);
    int y = bitfieldExtract(int(packed.x), 20, 8);
    int z = bitfieldExtract(int((packed.x << 4) | (packed.y >> 28)), 0, 24);
    return ivec3(x, y, z);
}

//Minimum corner of the section in level 0 section units (32 blocks)
ivec3 lodOrigin(uvec2 packed) {
    return lodPosition(packed) << lodLevel(packed);
}
#endif
