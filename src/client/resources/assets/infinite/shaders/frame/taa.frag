// Temporal anti-aliasing for the LOD colour buffer using depth reprojection and distance-based history trust

#import <infinite:common/depth.glsl>

layout(binding = 0) uniform sampler2D lodDepth;
layout(binding = 1) uniform sampler2D lodColour;
layout(binding = 2) uniform sampler2D history;

// Positive at dissolved vanilla borders, negative under vanilla water, 0 elsewhere
layout(binding = 3) uniform sampler2D border;
layout(binding = 4) uniform sampler2D historyDistance;  // Last frame camera-relative distance (0 = sky)

layout(location = 0) uniform mat4 reprojection;
layout(location = 1) uniform vec3 blend;
layout(location = 2) uniform mat4 toCamera;
layout(location = 3) uniform vec3 cameraMoved;

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 resolved;
layout(location = 1) out float resolvedDistance;

const float SAME_SURFACE = 0.02;
const float TRUSTED_SHARE = 0.05;
const float TRUSTED_PIXELS = 0.1;

#ifdef QUALITY_HIGH
const int TAPS = 9;
const ivec2 OFFSETS[TAPS] = ivec2[](ivec2(0, 0), ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1),
        ivec2(-1, -1), ivec2(1, -1), ivec2(-1, 1), ivec2(1, 1));
#else
const int TAPS = 5;
const ivec2 OFFSETS[TAPS] = ivec2[](ivec2(0, 0), ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
#endif
const float FAST_PIXELS = 6.0;

vec3 toYCoCg(vec3 c) {
    return vec3(dot(c, vec3(0.25, 0.5, 0.25)), dot(c, vec3(0.5, 0.0, -0.5)), dot(c, vec3(-0.25, 0.5, -0.25)));
}

vec3 fromYCoCg(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

#ifdef TAA_GATHER

void gatherTaps(sampler2D image, ivec2 pixel, out float taps[TAPS]) {
    vec2 texel = 1.0 / vec2(textureSize(image, 0));
    vec4 a = textureGather(image, vec2(pixel) * texel);
    vec4 b = textureGather(image, vec2(pixel + 1) * texel);
    taps[0] = a.y;
    taps[1] = a.x;
    taps[2] = b.z;
    taps[3] = a.z;
    taps[4] = b.x;

#ifdef QUALITY_HIGH
    vec4 c = textureGather(image, vec2(pixel.x + 1, pixel.y) * texel);
    vec4 d = textureGather(image, vec2(pixel.x, pixel.y + 1) * texel);
    taps[5] = a.w;
    taps[6] = c.z;
    taps[7] = d.x;
    taps[8] = b.y;
#endif
}
#endif

bool lodDrawn(float depth) {
    return depth != DEPTH_FAR && depth != DEPTH_NEAR;
}

bool underVanillaWater(float borderTap) {
    return borderTap < 0.0;
}

vec4 premultiplied(ivec2 at) {
    vec4 c = texelFetch(lodColour, at, 0);
    return vec4(c.rgb * c.a, c.a);
}

vec4 historyAt(vec2 uv) {
    vec2 size = vec2(textureSize(history, 0));
    vec2 position = uv * size;
    vec2 centre = floor(position - 0.5) + 0.5;
    vec2 f = position - centre;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 at12 = (centre + w2 / w12) / size, at0 = (centre - 1.0) / size, at3 = (centre + 2.0) / size;
    vec4 sum = texture(history, vec2(at12.x, at0.y)) * (w12.x * w0.y)
            + texture(history, vec2(at0.x, at12.y)) * (w0.x * w12.y)
            + texture(history, at12) * (w12.x * w12.y)
            + texture(history, vec2(at3.x, at12.y)) * (w3.x * w12.y)
            + texture(history, vec2(at12.x, at3.y)) * (w12.x * w3.y);
    float total = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return max(sum / total, 0.0);
}

void historyRange(vec2 uv, out vec4 lo, out vec4 hi) {
    ivec2 corner = ivec2(floor(uv * vec2(textureSize(history, 0)) - 0.5)), lastTexel = textureSize(history, 0) - 1;
    lo = vec4(1e9);
    hi = vec4(-1e9);
    for (int i = 0; i < 4; i++) {
        vec4 c = texelFetch(history, clamp(corner + ivec2(i & 1, i >> 1), ivec2(0), lastTexel), 0);
        lo = min(lo, c);
        hi = max(hi, c);
    }
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(lodColour, 0) - 1;
#ifdef TAA_GATHER
    float depths[TAPS], borders[TAPS];
    gatherTaps(lodDepth, pixel, depths);
    gatherTaps(border, pixel, borders);
#define DEPTH_TAP(i, at) depths[i]
#define BORDER_TAP(i, at) borders[i]
#else
#define DEPTH_TAP(i, at) texelFetch(lodDepth, at, 0).r
#define BORDER_TAP(i, at) texelFetch(border, at, 0).r
#endif
    float centreDepth = DEPTH_TAP(0, pixel);

    if (centreDepth == DEPTH_NEAR) {
        vec4 beside = vec4(0.0);
        float count = 0.0;
        for (int i = 1; i < 5; i++) {
            ivec2 at = clamp(pixel + OFFSETS[i], ivec2(0), last);
            if (!lodDrawn(DEPTH_TAP(i, at))) continue;
            beside += premultiplied(at);
            count++;
        }
        resolved = count > 0.0 ? beside / count : vec4(0.0);
        resolvedDistance = 0.0;
        return;
    }

    vec4 current = vec4(0.0), lo = vec4(1e9), hi = vec4(-1e9), beside = vec4(0.0);
    float count = 0.0, drawn = 0.0, nearest = centreDepth;
    bool besideVanilla = false, besideWater = false;
    bool underWater = underVanillaWater(BORDER_TAP(0, pixel));
    for (int i = 0; i < TAPS; i++) {
        ivec2 at = clamp(pixel + OFFSETS[i], ivec2(0), last);
        float depth = i == 0 ? centreDepth : DEPTH_TAP(i, at);
        if (depth == DEPTH_NEAR) {
            besideVanilla = true;
            continue;
        }
        if (i > 0 && underVanillaWater(BORDER_TAP(i, at)) != underWater) {
            besideWater = true;
            continue;
        }
        vec4 c = premultiplied(at);
        if (i == 0) current = c;
        if (depth != DEPTH_FAR) {
            beside += c;
            drawn++;
            nearest = depthNearest(nearest, depth);
        }
        vec4 y = vec4(toYCoCg(c.rgb), c.a);
        lo = min(lo, y);
        hi = max(hi, y);
        count++;
    }
    if (centreDepth == DEPTH_FAR && !besideVanilla && drawn > 0.0) {
        for (int i = 1; i < TAPS && !besideVanilla; i++) {
            besideVanilla = BORDER_TAP(i, clamp(pixel + OFFSETS[i], ivec2(0), last)) > 0.0;
        }
    }
    bool unknown = centreDepth == DEPTH_FAR && besideVanilla && drawn > 0.0;
    if (unknown) current = beside / drawn;
    vec4 currentY = vec4(toYCoCg(current.rgb), current.a);

    vec4 previous = reprojection * vec4(uvToNdc(uv), depthToNdc(nearest), 1.0);
    vec2 previousUv = ndcToUv(previous.xy / previous.w);
    vec4 unprojected = toCamera * vec4(uvToNdc(uv), depthToNdc(nearest), 1.0);
    vec3 position = unprojected.xyz / unprojected.w;
    resolvedDistance = lodDrawn(nearest) ? length(position) : 0.0;
    bool onScreen = previous.w > 0.0 && all(greaterThanEqual(previousUv, vec2(0.0))) && all(lessThanEqual(previousUv, vec2(1.0)));
    if (blend.z == 0.0 || !onScreen) {
        resolved = current;
        return;
    }

    float moved = length((previousUv - uv) * vec2(last + 1));
    vec4 past = moved < 1e-3 ? texelFetch(history, pixel, 0) : historyAt(previousUv), pastTrusted = past;
    float trusted = 0.0;
    if (lodDrawn(nearest) && !besideVanilla && !besideWater) {
        float expected = length(position + cameraMoved), closest = 1e9;
        ivec2 corner = ivec2(floor(previousUv * vec2(last + 1) - 0.5)), lastTexel = textureSize(historyDistance, 0) - 1;
#ifdef TAA_GATHER
        vec4 wasAll = textureGather(historyDistance, vec2(corner + 1) / vec2(lastTexel + 1));
        for (int i = 0; i < 4; i++) {
            float was = wasAll[i];
#else
        for (int i = 0; i < 4; i++) {
            float was = texelFetch(historyDistance, clamp(corner + ivec2(i & 1, i >> 1), ivec2(0), lastTexel), 0).r;
#endif
            if (was > 0.0) closest = min(closest, abs(was - expected));
        }
        trusted = 1.0 - smoothstep(SAME_SURFACE, 2.0 * SAME_SURFACE, closest / expected);
        trusted *= 1.0 - smoothstep(TRUSTED_PIXELS * 0.5, TRUSTED_PIXELS, moved);
    }
    if (trusted > 0.0 && moved >= 1e-3) {
        vec4 texelsLo, texelsHi;
        historyRange(previousUv, texelsLo, texelsHi);
        pastTrusted = clamp(past, texelsLo, texelsHi);
    }
    vec4 pastY = mix(clamp(vec4(toYCoCg(past.rgb), past.a), lo, hi), vec4(toYCoCg(pastTrusted.rgb), pastTrusted.a), trusted);

    float share = mix(mix(blend.x, min(blend.x, TRUSTED_SHARE), trusted), blend.y, clamp(moved / FAST_PIXELS, 0.0, 1.0));
    if (unknown) share = 0.0;
#ifdef QUALITY_HIGH
    float currentWeight = share / (1.0 + currentY.x), pastWeight = (1.0 - share) / (1.0 + pastY.x);
    vec4 mixed = (currentY * currentWeight + pastY * pastWeight) / max(currentWeight + pastWeight, 1e-6);
#else
    vec4 mixed = mix(pastY, currentY, share);
#endif
    resolved = max(vec4(fromYCoCg(mixed.xyz), mixed.w), 0.0);
}
