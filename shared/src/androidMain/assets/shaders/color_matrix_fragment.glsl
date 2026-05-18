#version 300 es
precision mediump float;
uniform sampler2D uTexSampler;
uniform mat4 uColorMatrix;
uniform vec4 uColorOffset;
in vec2 vTexSamplingCoord;
out vec4 outColor;

void main() {
    vec4 color = texture(uTexSampler, vTexSamplingCoord);
    
    // ColorMatrix multiplication and offset addition
    // Unpremultiply alpha before filtering
    if (color.a > 0.0) {
        color.rgb /= color.a;
    }
    
    vec4 transformedColor = uColorMatrix * color + uColorOffset;
    
    // Repremultiply alpha
    transformedColor.rgb *= transformedColor.a;
    
    outColor = clamp(transformedColor, 0.0, 1.0);
}
