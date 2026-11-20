#ifndef INFINITE_COMMON_FRAME
#define INFINITE_COMMON_FRAME
#import <infinite:common/depth.glsl>


// Per frame camera uniforms shared across LOD passes

layout(binding = 0, std140) uniform Frame {
    mat4 viewProjection;
    ivec3 cameraSection;
    uint frameId;
    vec3 cameraOffset;// Camera position inside its section, in blocks
    uint ambientOcclusion;// 1 when Minecraft smooth lighting is enabled
    float curveDrop;// Earth curvature drop per block^2 (0 when off)
    float curveStart;
    float vanillaDistance;// Vanilla terrain render distance in blocks
    vec2 jitterPixels;// TAA subpixel jitter in pixels (0 when off)
    vec4 faceShade;// Face brightness multipliers (up, down, N/S, E/W)
    mat4 inverseViewProjection;
    float atlasScaleV;// Model atlas V scale factor
};

bool fadedOut(uint fade, vec2 pixel) {
    float noise = 255.0 * fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
    return noise < float(fade & 0xFFu) || noise >= float(255u - (fade >> 8 & 0xFFu));
}

bool withinVanillaDistance(vec2 pixel, vec2 targetSize, float depth) {
    vec4 place = inverseViewProjection * vec4(uvToNdc(pixel / targetSize), depthToNdc(depth), 1.0);
    vec2 fromCamera = place.xz / place.w - cameraOffset.xz;
    return dot(fromCamera, fromCamera) < vanillaDistance * vanillaDistance;
}
#endif
