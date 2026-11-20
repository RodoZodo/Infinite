// Seeds lod depth with DEPTH_NEAR at opaque vanilla pixels, excluding clouds and dithered smooth border pixels

#import <infinite:common/depth.glsl>

layout(binding = 0) uniform sampler2D vanillaDepth;
layout(location = 0) uniform vec2 vanillaScale;
layout(location = 1) uniform mat4 vanillaToCamera; // Inverse vanilla view-projection (to camera-relative space)
layout(location = 2) uniform vec2 cloudBand; // Camera-relative Y bounds of cloud layer; empty if none
layout(location = 3) uniform vec2 borderFade;

layout(location = 0) in vec2 uv;
#ifdef BORDER
layout(location = 0) out float vanillaDistance;
#endif

float interleavedGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}


void main() {
    vec2 vanillaUv = uv * vanillaScale;
    float depth = texture(vanillaDepth, vanillaUv).r;
    if (depth == DEPTH_FAR) discard;
    vec4 unprojected = vanillaToCamera * vec4(uvToNdc(uv), depthToNdc(depth), 1.0);
    vec3 position = unprojected.xyz / unprojected.w;
    if (cloudBand.x < cloudBand.y && position.y >= cloudBand.x && position.y <= cloudBand.y) discard;
    bool dissolved = borderFade.x < borderFade.y
            && smoothstep(borderFade.x, borderFade.y, length(position.xz)) > interleavedGradientNoise(gl_FragCoord.xy);
#ifdef BORDER
    if (!dissolved) discard;
    vanillaDistance = length(position);
#else
    if (dissolved) discard;
    gl_FragDepth = DEPTH_NEAR;
#endif
}