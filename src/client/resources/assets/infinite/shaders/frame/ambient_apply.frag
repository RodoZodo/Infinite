// Upsamples half-resolution LOD ambient occlusion to full resolution using depth-aware bilateral weights

#import <infinite:frame/ambient.glsl>

layout(binding = 1) uniform sampler2D occlusion;
layout(location = 2) uniform vec2 fadeIn;

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 colour;

const float DEPTH_TOLERANCE = 0.03;
const float DOWNSCALE = 2.0;
const int REACH = 2;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(lodDepth, pixel, 0).r;
    if (!lodDrawn(depth)) discard;
    float blocks = length(positionAt(pixel, depth));
    if (blocks <= fadeIn.x) discard;

    ivec2 size = textureSize(occlusion, 0);
    vec2 centre = (vec2(pixel) + 0.5) / DOWNSCALE - 0.5;
    ivec2 base = ivec2(floor(centre));
    float visibility = 0.0, total = 0.0;
    for (int y = -1; y <= REACH; y++) {
        for (int x = -1; x <= REACH; x++) {
            ivec2 at = clamp(base + ivec2(x, y), ivec2(0), size - 1);
            vec2 texel = texelFetch(occlusion, at, 0).rg;
            if (texel.y == 0.0) continue;
            vec2 offset = vec2(at) - centre;
            float spatial = exp(-dot(offset, offset) * 0.5);
            float apart = abs(texel.y - blocks) / (blocks * DEPTH_TOLERANCE + 0.5);
            float weight = spatial * exp2(-apart);
            visibility += texel.x * weight;
            total += weight;
        }
    }

    if (total < 1e-4) discard;
    colour = vec4(vec3(visibility / total), 1.0);
}