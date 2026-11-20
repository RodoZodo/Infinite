// Emits screen aligned quads for 16 x16 tiles marked by the newly visible rasteriser claim pass

#import <infinite:terrain/new_tiles.glsl>

layout(location = 0) uniform ivec2 screen;

layout(location = 0) out vec2 uv;

void main() {
    uint corner = uint(gl_VertexIndex);
    uint tile = newTiles[NEW_TILE_LIST + uint(gl_InstanceIndex)];
    uint columns = newTileColumns(screen);
    vec2 pixel = (vec2(tile % columns, tile / columns) + vec2(corner & 1u, (corner >> 1) & 1u)) * float(NEW_TILE_SIZE);
    uv = min(pixel / vec2(screen), vec2(1.0));
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
