#ifndef INFINITE_TERRAIN_NEW_TILES
#define INFINITE_TERRAIN_NEW_TILES


// Tracks 16x16 screen tiles touched by newly visible software-rasterised quads for sparse resolve

// Buffer layout: 8-word indirect draw header, tile index list, then per-tile bitmask
const uint NEW_TILE_SIZE = 16u;
const uint NEW_TILE_LIST = 8u;
const uint NEW_TILE_COUNT = 1u;

uint newTileColumns(ivec2 screen) {
    return (uint(screen.x) + NEW_TILE_SIZE - 1u) / NEW_TILE_SIZE;
}

uint newTileTotal(ivec2 screen) {
    return newTileColumns(screen) * ((uint(screen.y) + NEW_TILE_SIZE - 1u) / NEW_TILE_SIZE);
}

#ifdef NEW_TILES_CLAIM
layout(binding = 3, std430) restrict buffer NewTiles {
    uint newTiles[];
};

void markNewTile(uvec2 pixel, ivec2 screen) {
    uint tile = (pixel.y / NEW_TILE_SIZE) * newTileColumns(screen) + pixel.x / NEW_TILE_SIZE;
    uint word = NEW_TILE_LIST + newTileTotal(screen) + (tile >> 5), bit = 1u << (tile & 31u);
    if ((newTiles[word] & bit) != 0u) return;
    if ((atomicOr(newTiles[word], bit) & bit) != 0u) return;
    newTiles[NEW_TILE_LIST + atomicAdd(newTiles[NEW_TILE_COUNT], 1u)] = tile;
}
#else
layout(binding = 3, std430) restrict readonly buffer NewTiles {
    uint newTiles[];
};
#endif
#endif
