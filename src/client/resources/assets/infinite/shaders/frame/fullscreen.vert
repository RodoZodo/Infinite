// Draws a fullscreen quad from the first six indices of the quad index buffer

layout(location = 0) out vec2 uv;

void main() {
    uint corner = uint(gl_VertexIndex);
    uv = vec2(corner & 1u, (corner >> 1) & 1u);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
