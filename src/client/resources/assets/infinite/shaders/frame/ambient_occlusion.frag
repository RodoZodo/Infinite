// Computes half-resolution screen-space ambient occlusion for opaque LoD terrain

#import <infinite:frame/ambient.glsl>

layout(location = 1) uniform vec4 shape;
layout(location = 2) uniform vec2 fadeIn;

layout(location = 0) in vec2 uv;
layout(location = 0) out vec2 result;

const int SAMPLES = 6;
const float GOLDEN_ANGLE = 2.39996323;
const float BIAS = 0.1;
const float MAX_PIXELS = 32.0;
const int DOWNSCALE = 2;

float interleavedGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

vec3 surfaceStep(ivec2 pixel, ivec2 axis, vec3 position, ivec2 size) {
    ivec2 after = clamp(pixel + axis, ivec2(0), size - 1), before = clamp(pixel - axis, ivec2(0), size - 1);
    float afterDepth = texelFetch(lodDepth, after, 0).r, beforeDepth = texelFetch(lodDepth, before, 0).r;
    vec3 forward = lodDrawn(afterDepth) ? positionAt(after, afterDepth) - position : vec3(1e30);
    vec3 backward = lodDrawn(beforeDepth) ? position - positionAt(before, beforeDepth) : vec3(1e30);
    return dot(forward, forward) < dot(backward, backward) ? forward : backward;
}

void main() {
    ivec2 size = textureSize(lodDepth, 0);
    ivec2 pixel = min(ivec2(gl_FragCoord.xy) * DOWNSCALE, size - 1);
    float depth = texelFetch(lodDepth, pixel, 0).r;
    if (!lodDrawn(depth)) {
        result = vec2(1.0, 0.0);
        return;
    }
    vec3 position = positionAt(pixel, depth);
    float blocks = length(position);
    float strength = shape.w * smoothstep(fadeIn.x, fadeIn.y, blocks);
    if (strength <= 0.0) {
        result = vec2(1.0, blocks);
        return;
    }

    vec3 normal = cross(surfaceStep(pixel, ivec2(1, 0), position, size), surfaceStep(pixel, ivec2(0, 1), position, size));
    if (dot(normal, normal) < 1e-12 || dot(normal, normal) > 1e20) {
        result = vec2(1.0, blocks);
        return;
    }
    normal = normalize(normal);
    if (dot(normal, position) > 0.0) normal = -normal;

    float radius = max(shape.z, blocks * shape.y);
    float pixels = min(radius * shape.x / blocks, MAX_PIXELS);
    float angle = interleavedGradientNoise(gl_FragCoord.xy) * 6.28318530718;
    float occlusion = 0.0;
    int counted = 0;
    for (int i = 0; i < SAMPLES; i++) {
        angle += GOLDEN_ANGLE;
        float along = (float(i) + 0.5) / float(SAMPLES);
        ivec2 at = pixel + ivec2(round(vec2(cos(angle), sin(angle)) * along * pixels));
        if (any(lessThan(at, ivec2(0))) || any(greaterThanEqual(at, size))) continue;
        float sampleDepth = texelFetch(lodDepth, at, 0).r;
        if (!lodDrawn(sampleDepth)) continue;
        vec3 towards = positionAt(at, sampleDepth) - position;
        float apart = length(towards);
        counted++;
        if (apart < 1e-4) continue;
        float falloff = 1.0 - clamp(apart / radius, 0.0, 1.0);
        occlusion += max(dot(normal, towards) / apart - BIAS, 0.0) * falloff * falloff;
    }
    occlusion /= float(max(counted, 1));
    result = vec2(1.0 - clamp(occlusion * strength, 0.0, 1.0), blocks);
}
