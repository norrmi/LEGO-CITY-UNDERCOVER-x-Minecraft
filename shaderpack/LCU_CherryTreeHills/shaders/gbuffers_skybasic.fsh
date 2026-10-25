#version 120
#include "/common.glsl"
// fallback sky (outside Cherry Tree Hills worlds): the game's two fog colours as a gradient
uniform mat4 gbufferModelViewInverse;
varying vec3 viewPos;
void main() {
	vec3 dir = normalize(mat3(gbufferModelViewInverse) * viewPos);
/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(mix(LCU_LOWER_FOG, LCU_FOG, smoothstep(-0.1, 0.4, dir.y)), 1.0);
}
