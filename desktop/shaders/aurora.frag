#version 460 core
#include <flutter/runtime_effect.glsl>

precision mediump float;

uniform vec2 uSize;
uniform float uTime;

out vec4 fragColor;

// — Hash & value noise —————————————————————————————————————————————————————

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash(i),                hash(i + vec2(1.0, 0.0)), u.x),
        mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
        u.y
    );
}

// — Fractional Brownian Motion ——————————————————————————————————————————————
// 6 octaves, with a gentle rotation per octave to break up grid alignment
// and give the noise a more organic, swirly feel.

float fbm(vec2 p) {
    const mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 6; i++) {
        v += a * noise(p);
        p = rot * p * 2.0;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 fragCoord = FlutterFragCoord();
    // Normalize to [-1, 1] on the smaller axis — keeps the shape proportional
    // regardless of window aspect ratio.
    vec2 uv = (fragCoord * 2.0 - uSize) / min(uSize.x, uSize.y);

    float t = uTime * 0.08;

    // Two-stage domain warping. Each fbm call is fed a position offset by the
    // previous fbm's output → the field folds back on itself, producing the
    // smoky, fluid-like patterns.
    vec2 q = vec2(
        fbm(uv * 1.4 + vec2(0.0, t)),
        fbm(uv * 1.4 + vec2(5.2, 1.3) + t * 1.1)
    );

    vec2 r = vec2(
        fbm(uv * 1.4 + 3.0 * q + vec2(1.7, 9.2) + t * 1.25),
        fbm(uv * 1.4 + 3.0 * q + vec2(8.3, 2.8) + t * 1.40)
    );

    float density = fbm(uv * 1.4 + 3.5 * r);

    // — Color ramp ———————————————————————————————————————————————————————————
    // Deep near-black → midnight navy → electric blue → soft cyan highlights.
    // Each band uses smoothstep so the transitions read as volumetric clouds
    // rather than hard contour lines.
    vec3 cBlack    = vec3(0.005, 0.010, 0.035);
    vec3 cNavy     = vec3(0.020, 0.060, 0.180);
    vec3 cBlue     = vec3(0.150, 0.450, 0.950);
    vec3 cHighlight = vec3(0.700, 0.880, 1.000);

    vec3 col = cBlack;
    col = mix(col, cNavy,      smoothstep(0.05, 0.45, density));
    col = mix(col, cBlue,      smoothstep(0.40, 0.72, density));
    col = mix(col, cHighlight, smoothstep(0.72, 0.95, density) * 0.45);

    // Subtle vignette — the corners stay deep dark, so the eye is drawn
    // toward the center of the swirl.
    float vign = smoothstep(1.7, 0.3, length(uv));
    col *= mix(0.55, 1.0, vign);

    // Film-grain dither to kill 8-bit banding in the dark gradients.
    float grain = fract(sin(dot(fragCoord, vec2(12.9898, 78.233))) * 43758.5453);
    col += (grain - 0.5) * 0.008;

    fragColor = vec4(col, 1.0);
}
