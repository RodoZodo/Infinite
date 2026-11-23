// Draws bounding boxes for rendered vanilla chunk sections to record their farthest depth

#import <infinite:common/depth.glsl>

layout(binding = 0, std140) uniform Frame {
    mat4 viewProjection;    // Relative to cameraBlock
    ivec3 cameraBlock;
    vec4 cameraFraction;
    vec2 maskHalfTexel;
};

layout(binding = 0, std430) restrict readonly buffer SectionPositions {
    ivec2 sectionPositions[];
};

const uint BOXES_PER_INSTANCE = 32u;


// Packed Sodium section coords: x (high bits 10-31), y (low bits 0-19), z (high 0-9 over low 20-31)
ivec3 unpackSection(ivec2 packed) {
    int x = bitfieldExtract(packed.y, 10, 22);
    int y = bitfieldExtract(packed.x, 0, 20);
    int z = bitfieldExtract(int((uint(packed.y) << 12) | (uint(packed.x) >> 20)), 0, 22);
    return ivec3(x, y, z);
}

bool inRenderDistance(ivec3 origin) {
    vec3 nearest = vec3(clamp(ivec3(0), origin - 1, origin + 17)) - cameraFraction.xyz;
    float distance = cameraFraction.w;
    return dot(nearest.xz, nearest.xz) < distance * distance && abs(nearest.y) < distance;
}

void main() {
    uint vertex = uint(gl_VertexIndex);
    uint box = uint(gl_InstanceIndex) * BOXES_PER_INSTANCE + (vertex >> 3);
    ivec3 origin = unpackSection(sectionPositions[box]) * 16 - cameraBlock;
    if (!inRenderDistance(origin)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    ivec3 corner = ivec3(vertex & 1u, (vertex >> 2) & 1u, (vertex >> 1) & 1u) * 16;
    vec4 centreClip = viewProjection * vec4(vec3(origin) + 8.0, 1.0);
    vec4 clip = viewProjection * vec4(vec3(origin + corner), 1.0);
    if (clip.w > 0.0 && centreClip.w > 0.0) {
        vec2 away = clip.xy / clip.w - centreClip.xy / centreClip.w;
        clip.xy += vec2(away.x < 0.0 ? -1.0 : 1.0, away.y < 0.0 ? -1.0 : 1.0) * maskHalfTexel * clip.w;
    }
    gl_Position = clip;
    gl_Position.z += DEPTH_TOWARDS_CAMERA * VANILLA_MASK_BIAS;
}
