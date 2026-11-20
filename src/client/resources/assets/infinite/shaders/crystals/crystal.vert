// Vertex shader for far end crystals beyond vanilla render distance

#import <infinite:common/curvature.glsl>

struct Vertex {
    vec4 place;// xyz: camera-relative position, w: entity face shade
    vec4 look;// xy: UV, z: block light, w: sky light
};

layout(binding = 0, std430) restrict readonly buffer Vertices {
    Vertex vertices[];
};
layout(binding = 1) uniform sampler2D lightmap;

layout(location = 0) uniform mat4 viewProjection;   // Camera-relative


// x: curvature drop per block^2 (0 when off), y: start distance
layout(location = 1) uniform vec2 curvature;

layout(location = 0) out vec2 uv;
layout(location = 1) out vec3 shade;

void main() {
    Vertex vertex = vertices[gl_VertexIndex];
    vec3 position = vertex.place.xyz;
    position.y -= curvatureDrop(position.xz, curvature.x, curvature.y);
    gl_Position = viewProjection * vec4(position, 1.0);
    uv = vertex.look.xy;
    shade = textureLod(lightmap, (vertex.look.zw + 0.5) / 16.0, 0.0).rgb * vertex.place.w;
}
