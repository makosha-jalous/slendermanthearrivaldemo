#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
// x = how much the colours fade, y = more contrast, z = more brightness, w = the shift of the red and the blue picture (chromatic aberration)
uniform vec4 Effect;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec3 c;
    c.r = texture(Sampler0, texCoord0 + vec2(Effect.w, 0.0)).r;
    c.g = texture(Sampler0, texCoord0).g;
    c.b = texture(Sampler0, texCoord0 - vec2(Effect.w, 0.0)).b;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(c, vec3(l), Effect.x);                  // the colours fade
    c = (c - 0.3) * (1.0 + Effect.y) + 0.3;         // dark stays dark, light gets brighter
    c *= 1.0 + Effect.z;                            // the exposure is turned up
    fragColor = vec4(clamp(c, 0.0, 1.0), vertexColor.a * ColorModulator.a);
}
