// Fragment shader for far end crystals

layout(binding = 2) uniform sampler2D crystal;

layout(location = 0) in vec2 uv;
layout(location = 1) in vec3 shade;
layout(location = 0) out vec4 colour;

void main() {
    vec4 texel = texture(crystal, uv);
    if (texel.a < 0.1) discard;
    colour = vec4(texel.rgb * shade, 1.0);
}
