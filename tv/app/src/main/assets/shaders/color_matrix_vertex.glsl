#version 300 es
in vec4 aFramePosition;
out vec2 vTexSamplingCoord;
void main() {
    gl_Position = aFramePosition;
    // Map from [-1, 1] to [0, 1]
    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
