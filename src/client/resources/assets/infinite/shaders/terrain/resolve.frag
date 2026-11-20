// Shades pixels won by the software rasteriser using the visibility buffer and writes their depth

#ifndef VISIBILITY_SPLIT
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

#extension GL_EXT_shader_atomic_int64 : require
#endif
#import <infinite:terrain/visibility.glsl>

layout(location = 0) uniform ivec2 screen;
layout(location = 1) uniform uint firstPayload;

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 colour;

bool greyscale(vec3 texel) {
    return abs(texel.r - texel.g) < 0.02 && abs(texel.g - texel.b) < 0.02;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    uint at = uint(pixel.y) * uint(screen.x) + uint(pixel.x);
    uint key = visibilityKeyAt(at);
    uint payload = visibilityPayloadAt(at);
    if (payload < firstPayload) discard;
    uint slot = (payload - 1u) / BLOCK_QUADS;
    uvec2 block = drawBlocks[slot];
    uint quadIndex = block.x + (payload - 1u) % BLOCK_QUADS;
    QuadSetup quad = setupQuad(quadIndex, block.y & 0x3FFFFFu);

#ifdef RESOLVE_ONE_TRIANGLE
    bool flatQuad = !quad.corners && curveDrop <= 0.0;
#else
    bool flatQuad = false;
#endif
    vec4 clip[4];
    vec2 faceUv[4];
    for (uint c = 0u; c < 3u; c++) clip[c] = viewProjection * vec4(quadCorner(quad, c, faceUv[c]), 1.0);
    if (!flatQuad) clip[3] = viewProjection * vec4(quadCorner(quad, 3u, faceUv[3]), 1.0);
    vec2 ndc = gl_FragCoord.xy / vec2(screen) * 2.0 - 1.0;
    vec2 step = 2.0 / vec2(screen);
    mat3 corners = cornerMatrix(clip[1], clip[2], clip[0]);
    vec3 weights = barycentrics(corners, ndc);
    vec2 uvA = faceUv[1], uvB = faceUv[2], uvC = faceUv[0];
    if (!flatQuad && min(weights.x, min(weights.y, weights.z)) < 0.0) {
        corners = cornerMatrix(clip[1], clip[3], clip[2]);
        uvB = faceUv[3];
        uvC = faceUv[2];
        weights = barycentrics(corners, ndc);
    }
    vec3 weightsX = barycentrics(corners, ndc + vec2(step.x, 0.0));
    vec3 weightsY = barycentrics(corners, ndc + vec2(0.0, step.y));
    QuadFlats flats = quadFlats(quad);
    Surface surface = surfaceAt(flats, weights.x * uvA + weights.y * uvB + weights.z * uvC,
            weightsX.x * uvA + weightsX.y * uvB + weightsX.z * uvC, weightsY.x * uvA + weightsY.y * uvB + weightsY.z * uvC);

    colour = sampleSurface(surface);
    uint tintMode = (flats.faceFlags >> 1) & 3u;
    if (tintMode == TINT_ALWAYS) {
        colour.rgb *= flats.tint;
    } else if (tintMode == TINT_GREYSCALE) {
        vec4 exact = surface.live ? textureLod(blockAtlas, surface.liveTexel, 0.0) : textureLod(atlas, surface.texel, 0.0);
        if (greyscale(exact.rgb)) colour.rgb *= flats.tint;
    }
    colour.a = 1.0;
    colour.rgb *= flats.shade;

    uint aoCodes = flats.faceFlags >> 8;
    if (flats.aoTable != 0u) {
        uint entry = tableEntry(flats.aoTable, uint(surface.voxel.y) * (flats.lastTile.x + 1u) + uint(surface.voxel.x));
        colour.rgb *= lightColour(tableLight(entry));
        if (ambientOcclusion != 0u) aoCodes = aoCorners(tableCode(entry));
    }
    if (aoCodes != 0u) {
        vec4 brightness = 1.0 - 0.2 * vec4((uvec4(aoCodes) >> uvec4(0u, 2u, 4u, 6u)) & 3u);
        vec2 within = clamp(surface.uv - surface.voxel, 0.0, 1.0);
        vec2 alongU = mix(brightness.xz, brightness.yw, within.x);
        colour.rgb *= mix(alongU.x, alongU.y, within.y);
    }
    gl_FragDepth = keyDepth(key);
}