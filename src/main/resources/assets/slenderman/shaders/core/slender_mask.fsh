#version 150

// THE MASK: a plane that stands in the WORLD in front of Slenderman. It shows the picture of the screen (a copy taken just before)
// from a shifted place: waves, a split of the colours, and near him burnt-out contrast. Because it is a real object it is hidden by
// everything that stands in front of it (the depth test of the game), and it is not drawn where it is hidden.
uniform sampler2D Sampler0;
uniform vec2 MaskScreen;
uniform vec4 Effect;   // x = colours fade, y = more contrast, z = more brightness, w = shift of red and blue (colour split)
uniform vec4 Wave;     // x = how far the picture is bent (share of the screen), y = time (s), z = waves over the height, w = opacity scale
uniform vec4 Edge;     // x = width of the soft border (share of the plane)

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
    float ex = clamp(min(uv.x, 1.0 - uv.x) / Edge.x, 0.0, 1.0);
    float ey = clamp(min(uv.y, 1.0 - uv.y) / Edge.x, 0.0, 1.0);
    float w = smoothstep(0.0, 1.0, ex) * smoothstep(0.0, 1.0, ey);        // stands for (normal . view) of the original
    float amount = Wave.x * w * w;
    float t = Wave.y;

    // two slow noises slide through each other (the "heat"): LONG waves, a few over the whole height (not dozens)
    float nx = vnoise(vec2(uv.y * Wave.z + t * 1.3, uv.x * 1.4 - t * 0.5))
             + vnoise(vec2(uv.y * Wave.z * 1.9 - t * 2.1, uv.x * 2.3 + t * 0.9)) - 1.0;
    float ny = vnoise(vec2(uv.x * 2.0 + t * 0.7, uv.y * 1.5 + t * 1.1))
             + vnoise(vec2(uv.x * 3.1 - t * 1.4, uv.y * 2.2 - t * 0.6)) - 1.0;
    vec2 off = vec2(nx, ny * 0.5) * amount;

    vec2 sc = gl_FragCoord.xy / MaskScreen + off;
    vec3 c;
    c.r = texture(Sampler0, sc + vec2(Effect.w, 0.0)).r;
    c.g = texture(Sampler0, sc).g;
    c.b = texture(Sampler0, sc - vec2(Effect.w, 0.0)).b;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(c, vec3(l), Effect.x);
    c = (c - 0.12) * (1.0 + Effect.y) + 0.12;
    c *= 1.0 + Effect.z;
    float a = clamp(Wave.w * w * w * w, 0.0, 1.0);
    fragColor = vec4(clamp(c, 0.0, 1.0), a);
}
