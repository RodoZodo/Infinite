#ifndef INFINITE_COMMON_NODE
#define INFINITE_COMMON_NODE
#import <infinite:common/lod_position.glsl>

const uint NODE_NONE = 0xFFFFFFu;//No mesh yet / no children yet
const uint NODE_EMPTY = 0xFFFFFEu;//Mesh known to be empty / child list known to be empty
const uint NODE_REQUESTED = 1u << 24;

uint nodeMesh(uvec4 node) {
    return node.z & 0xFFFFFFu;
}

uint nodeFirstChild(uvec4 node) {
    return node.w & 0xFFFFFFu;
}

uint nodeChildCount(uvec4 node) {
    return bitfieldExtract(node.z, 26, 3) + 1u;
}

bool nodeRequested(uvec4 node) {
    return (node.z & NODE_REQUESTED) != 0u;
}

bool nodeUnused(uvec4 node) {
    return all(equal(node, uvec4(0xFFFFFFFFu)));
}
#endif
