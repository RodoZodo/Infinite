#ifndef INFINITE_FRAME_AMBIENT
#define INFINITE_FRAME_AMBIENT


// Shared helpers for LoD SSAO passes to reconstruct camera relative positions from depth

#import <infinite:common/depth.glsl>

layout(binding = 0) uniform sampler2D lodDepth;

layout(location = 0) uniform mat4 lodToCamera;  // Inverse LOD view projection (to camera relative space)

bool lodDrawn(float depth) {
    return depth != DEPTH_FAR && depth != DEPTH_NEAR;
}

vec3 positionAt(ivec2 pixel, float depth) {
    vec2 uv = (vec2(pixel) + 0.5) / vec2(textureSize(lodDepth, 0));
    vec4 unprojected = lodToCamera * vec4(uvToNdc(uv), depthToNdc(depth), 1.0);
    return unprojected.xyz / unprojected.w;
}
#endif
