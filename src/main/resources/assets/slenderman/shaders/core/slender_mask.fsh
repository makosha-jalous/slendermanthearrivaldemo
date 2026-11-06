#version 150

// THE MASK: a plane that stands in the WORLD in front of Slenderman (from the ground to above his head, much wider than he is).
// It shows the picture of the screen (a copy taken just before) from a shifted place, so it bends everything behind it and him
// together with it. It does NOT paint a light rectangle: the waves differ in amplitude and stretch from place to place and from flash
// to flash, and the "exposure" works only on the LIGHT, not blue parts of the picture (they burn out to white), the dark parts stay
// dark. Because it is a real object it is hidden by everything that stands in front of it (the depth test of the game).
uniform sampler2D Sampler0;
uniform vec2 MaskScreen;
uniform vec4 Effect;   // x = how much the light parts burn out (0..1), y = warm streaks in the dark (0..1), z = not used, w = shift of red and blue
uniform vec4 Wave;     // x = how far the picture is bent (share of the screen), y = time (s), z = waves over the height, w = opacity scale
uniform vec4 Edge;     // x = width of the soft side border (share of the plane), y = stretch of THIS flash (0..1), z = seed of THIS flash, w = not used

in vec2 uv;
out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    // soft borders: at the sides and at the top (the bottom is the ground: the depth test cuts the plane there)
    float ex = clamp(min(uv.x, 1.0 - uv.x) / Edge.x, 0.0, 1.0);
    float et = clamp((1.0 - uv.y) / 0.22, 0.0, 1.0);
    float w = smoothstep(0.0, 1.0, ex) * smoothstep(0.0, 1.0, et);
    float t = Wave.y;
    float seed = Edge.z;
    float st = Edge.y;

    // the waves are NOT the same everywhere: bands along the height with a different amplitude, a different stretch (frequency)
    float band = vnoise(vec2(uv.y * 2.2 + seed * 3.0, t * 0.7 + seed));
    float amp = 0.40 + 1.50 * band * band;
    float freq = mix(Wave.z, Wave.z * 3.2, st);
    float sx = vnoise(vec2(uv.y * freq + t * 1.3 + seed * 5.0, uv.x * 1.2 - t * 0.5))
             + vnoise(vec2(uv.y * freq * 2.3 - t * 2.1, uv.x * 2.6 + seed * 3.0)) - 1.0;
    float sy = vnoise(vec2(uv.x * 2.0 + t * 0.7 + seed, uv.y * 1.5 + t * 1.1))
             + vnoise(vec2(uv.x * 3.1 - t * 1.4, uv.y * 2.2 - t * 0.6 + seed)) - 1.0;
    float amount = Wave.x * w * w;
    vec2 off = vec2(sx * amp, sy * (0.6 + 1.6 * st) * amp) * amount;
    off.y += (band - 0.5) * st * amount * 1.5;                      // the picture is dragged up / down in streaks

    vec2 sc = gl_FragCoord.xy / MaskScreen + off;
    vec3 c;
    c.r = texture(Sampler0, sc + vec2(Effect.w, 0.0)).r;
    c.g = texture(Sampler0, sc).g;
    c.b = texture(Sampler0, sc - vec2(Effect.w, 0.0)).b;

    // the high exposure of the flash: ONLY the light parts (and the warm, not the blue ones) burn out to white
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    float hi = smoothstep(0.18, 0.60, l);
    float warm = smoothstep(-0.03, 0.10, c.r - c.b);
    float weight = hi * (0.40 + 0.60 * warm);
    float burn = Effect.x * w * weight * (0.55 + 0.90 * band);
    c *= 1.0 + burn * 2.0;
    float peak = max(c.r, max(c.g, c.b));
    c = mix(c, vec3(peak), clamp(burn, 0.0, 1.0) * 0.35);            // white-hot: the colours are pulled together

    // the dark parts are not lifted (that would be a light rectangle): only thin warm streaks inside the waves
    float streak = vnoise(vec2(uv.x * 6.0 + seed * 9.0, uv.y * 1.5 - t * 0.8));
    c += vec3(0.28, 0.11, 0.02) * (1.0 - hi) * w * streak * streak * Effect.y * Effect.x;

    float a = clamp(Wave.w * w * w * w, 0.0, 1.0);
    fragColor = vec4(clamp(c, 0.0, 1.0), a);
}
