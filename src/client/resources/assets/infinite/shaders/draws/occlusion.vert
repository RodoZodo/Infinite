// Renders expanded section bounding boxes against first pass depth for occlusion culling

#import <infinite:common/depth.glsl>
#import <infinite:common/frame.glsl>
#import <infinite:common/curvature.glsl>
#import <infinite:common/section.glsl>

layout(binding = 0, std430) restrict readonly buffer Selected {
    uint selectedCount;
    uint selectedSections[];
};

layout(binding = 1, std430) restrict readonly buffer Sections {
    SectionMetadata sections[];
};

layout(location = 0) flat out uint sectionId;

void main() {
    sectionId = selectedMesh(selectedSections[gl_InstanceIndex]);
    SectionMetadata section = sections[sectionId];
    uint lod = lodLevel(section.header.xy);
    vec3 origin = vec3((lodOrigin(section.header.xy) - cameraSection) << 5);

    uint corner = uint(gl_VertexIndex);
    vec3 cornerMask = vec3(corner & 1u, (corner >> 2) & 1u, (corner >> 1) & 1u);
    vec3 boxMin = vec3(sectionBoxMin(section)) - 1.0;
    vec3 boxSize = vec3(sectionBoxSize(section)) + 2.0;
    vec3 position = origin + (boxMin + cornerMask * boxSize) * float(1u << lod);
    position.y -= curvatureDrop(position.xz - cameraOffset.xz, curveDrop, curveStart);

    gl_Position = viewProjection * vec4(position, 1.0);
    gl_Position.z += DEPTH_TOWARDS_CAMERA * 1e-6 * gl_Position.w;
}
