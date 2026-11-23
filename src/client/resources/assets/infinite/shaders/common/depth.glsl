#ifndef INFINITE_COMMON_DEPTH
#define INFINITE_COMMON_DEPTH


// Depth conventions of Minecraft's current projection.

#ifdef USE_REVERSE_Z
const float DEPTH_NEAR = 1.0;
const float DEPTH_FAR = 0.0;
#else
const float DEPTH_NEAR = 0.0;
const float DEPTH_FAR = 1.0;
#endif

const float DEPTH_TOWARDS_CAMERA = DEPTH_NEAR - DEPTH_FAR;  // Adding a positive multiple of this moves a depth value towards the camera
const float VANILLA_MASK_BIAS = 0.0005;

bool depthNearer(float a, float b) {
    return (a - b) * DEPTH_TOWARDS_CAMERA > 0.0;
}

bool depthNearerOrEqual(float a, float b) {
    return (a - b) * DEPTH_TOWARDS_CAMERA >= 0.0;
}

float depthNearest(float a, float b) {
    return depthNearer(a, b) ? a : b;
}

float depthFarthest(float a, float b) {
    return depthNearer(a, b) ? b : a;
}

float ndcToDepth(float z) {
#ifdef USE_ZERO_ONE_DEPTH
    return z;
#else
    return z * 0.5 + 0.5;
#endif
}

float depthToNdc(float depth) {
#ifdef USE_ZERO_ONE_DEPTH
    return depth;
#else
    return depth * 2.0 - 1.0;
#endif
}

//Normalised device xy to texture coordinates of the render target, and back
vec2 ndcToUv(vec2 ndc) {
    return ndc * 0.5 + 0.5;
}

vec2 uvToNdc(vec2 uv) {
    return uv * 2.0 - 1.0;
}
#endif
