#version 120
#include "/common.glsl"
// bloom above the game's threshold, exposure and tone curve (the final pass adds FXAA)
uniform sampler2D colortex0;
uniform sampler2D colortex1; // sky flag from the gbuffers
uniform float viewWidth;
uniform float viewHeight;
varying vec2 texcoord;
vec3 bright(vec2 uv, float lod) {
	vec3 c = texture2DLod(colortex0, uv, lod).rgb * (1.0 - texture2D(colortex1, uv).r) * LCU_EXPOSURE * EXPOSURE;
	// the game's threshold (0.5) is for its HDR buffer; this buffer is 0..1, so the same share of the
	// range above it starts at 0.5 + 0.5 * 0.7
	return max(c - (LCU_BLOOM_THRESHOLD + (1.0 - LCU_BLOOM_THRESHOLD) * 0.7), 0.0);
}
void main() {
	// the game's auto exposure, fixed (see lcu_lighting.glsl); the sky dome is drawn already exposed
	float sky = texture2D(colortex1, texcoord).r;
	vec3 c = texture2D(colortex0, texcoord).rgb * mix(LCU_EXPOSURE * EXPOSURE, 1.0, sky);
	vec2 px = vec2(1.0 / viewWidth, 1.0 / viewHeight);
	vec3 b = vec3(0.0);
	float w = 0.0;
	for (int i = -2; i <= 2; i++) {
		for (int j = -2; j <= 2; j++) {
			float k = exp(-float(i * i + j * j) * 0.35);
			b += (bright(texcoord + vec2(i, j) * px * 12.0, 4.0) + bright(texcoord + vec2(i, j) * px * 5.0, 2.5)) * k;
			w += k;
		}
	}
	c += b / w * LCU_BLOOM_INTENSITY * BLOOM;
	// soft shoulder: linear up to 0.8, then rolls off towards 1 instead of clipping (keeps paint colours)
	c = mix(c, 0.8 + 0.2 * (1.0 - exp(-(c - 0.8) / 0.2)), step(0.8, c));
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(c, dot(c, vec3(0.299, 0.587, 0.114))); // luma in alpha for FXAA
}
