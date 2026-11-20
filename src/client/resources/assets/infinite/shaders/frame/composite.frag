// Composites the Lod colour and reprojected depth over Minecraft's frame with optional fog and distance fade

#import <infinite:common/depth.glsl>

layout(binding = 0) uniform sampler2D lodDepth;
layout(binding = 1) uniform sampler2D lodColour;
layout(binding = 2) uniform sampler2D border;

layout(location = 0) uniform mat4 lodToCamera;  // Inverse LOD view-projection (to camera-relative space)
layout(location = 1) uniform mat4 vanillaViewProjection;
#ifdef HAS_FOG
layout(location = 2) uniform vec3 fog;  // amount = clamp(distance * x + y, 0, z)
layout(location = 3) uniform vec4 fogColour;
#endif
#ifdef HAS_FADE
layout(location = 4) uniform vec2 fade; // opacity lost = clamp(horizontalDist * y + x, 0, 1)
#endif
#ifdef TAA
layout(location = 5) uniform float sharpness;
#endif

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 colour;

const float FAR_MARGIN = 2.0 / 16777215.0;  // Keeps reprojected depth inside far plane
const float BORDER_TOLERANCE = 0.5, BORDER_TOLERANCE_SHARE = 0.002;
const float BORDER_PULL = 0.995;

#ifdef TAA
const float LEAST_COVERAGE = 1.0 / 256.0;

vec4 resolvedAt(ivec2 pixel) {
    vec4 centre = texelFetch(lodColour, pixel, 0);
    if (sharpness == 0.0) return centre;
    ivec2 last = textureSize(lodColour, 0) - 1;
    vec4 lo = centre, hi = centre, sum = vec4(0.0);
    for (int i = 0; i < 4; i++) {
        ivec2 offset = i < 2 ? ivec2(i * 2 - 1, 0) : ivec2(0, i * 2 - 5);
        vec4 c = texelFetch(lodColour, clamp(pixel + offset, ivec2(0), last), 0);
        lo = min(lo, c);
        hi = max(hi, c);
        sum += c;
    }
    return clamp(centre + (centre - sum * 0.25) * sharpness, lo, hi);
}
#endif

void main() {
    float depth = texture(lodDepth, uv).r;
#ifdef TAA
    if (depth == DEPTH_NEAR) discard;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    colour = resolvedAt(pixel);
    if (colour.a < LEAST_COVERAGE) discard;
    if (depth == DEPTH_FAR) {
        ivec2 last = textureSize(lodDepth, 0) - 1;
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                float beside = texelFetch(lodDepth, clamp(pixel + ivec2(x, y), ivec2(0), last), 0).r;
                if (beside != DEPTH_NEAR) depth = depthNearest(depth, beside);
            }
        }
        if (depth == DEPTH_FAR) discard;
    }
    colour.rgb /= colour.a;
#else
    if (depth == DEPTH_FAR || depth == DEPTH_NEAR) discard;
    colour = texture(lodColour, uv);
    if (colour.a == 0.0) discard;
#endif

    vec4 unprojected = lodToCamera * vec4(uvToNdc(uv), depthToNdc(depth), 1.0);
    vec3 position = unprojected.xyz / unprojected.w;
    float vanillaDistance = texture(border, uv).r;
    if (vanillaDistance > 0.0) {
        float lodDistance = length(position);
        if (abs(lodDistance - vanillaDistance) > max(BORDER_TOLERANCE, vanillaDistance * BORDER_TOLERANCE_SHARE)) discard;
        position *= min(lodDistance, vanillaDistance) * BORDER_PULL / lodDistance;
    }
    vec4 clip = vanillaViewProjection * vec4(position, 1.0);
    float farLimit = depthToNdc(DEPTH_FAR) + DEPTH_TOWARDS_CAMERA * FAR_MARGIN;
    gl_FragDepth = ndcToDepth(depthNearest(clip.z / clip.w, farLimit));

#ifdef HAS_FOG
    if (fogColour.a > 0.0) {
        float amount = clamp(length(position) * fog.x + fog.y, 0.0, fog.z);
        colour.rgb = mix(colour.rgb, fogColour.rgb, amount * fogColour.a);
    }
#endif
#ifdef HAS_FADE
    colour.a *= 1.0 - clamp(length(position.xz) * fade.y + fade.x, 0.0, 1.0);
#endif
}