#version 460 core
#include <flutter/runtime_effect.glsl>

precision mediump float;

uniform vec2 uSize;
uniform float uTime;

out vec4 fragColor;

// Smooth radial blob at `c` of given `radius`.
float blob(vec2 uv, vec2 c, float radius) {
    float d = distance(uv, c);
    return smoothstep(radius, 0.0, d);
}

void main() {
    vec2 fragCoord = FlutterFragCoord();
    // Normalize to [0,1] across the smaller axis so blobs look round on any
    // window aspect ratio.
    float minSide = min(uSize.x, uSize.y);
    vec2 uv = fragCoord / minSide;
    vec2 cuv = (fragCoord - 0.5 * uSize) / minSide;

    float t = uTime * 0.18;

    // Three slow-moving colored blobs that orbit different paths.
    vec2 c1 = vec2( 0.45 * sin(t * 1.10),  0.45 * cos(t * 1.30));
    vec2 c2 = vec2(-0.55 * cos(t * 0.85),  0.55 * sin(t * 0.95));
    vec2 c3 = vec2( 0.35 * sin(t * 0.70 + 1.7),
                   -0.50 * cos(t * 0.65 + 0.4));

    vec3 col = vec3(0.04, 0.02, 0.10);
    col += vec3(0.45, 0.10, 0.85) * blob(cuv, c1, 0.85);
    col += vec3(0.05, 0.40, 0.95) * blob(cuv, c2, 0.95);
    col += vec3(0.95, 0.25, 0.55) * blob(cuv, c3, 0.80);

    // Subtle vignette so the edges don't feel flat.
    float vign = smoothstep(1.4, 0.5, length(cuv));
    col *= mix(0.55, 1.0, vign);

    // Gentle film-grain dither to break up banding in the dark areas.
    float n = fract(sin(dot(fragCoord, vec2(12.9898, 78.233))) * 43758.5453);
    col += (n - 0.5) * 0.012;

    fragColor = vec4(col, 1.0);
}
