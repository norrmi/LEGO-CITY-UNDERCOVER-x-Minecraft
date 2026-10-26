#version 120
#include "/common.glsl"
// LCU NOON fog (Fog01): colour fades from the upper to the lower fog colour towards the horizon
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform sampler2D colortex1; // r = 1 where the sky dome was drawn (cleared to 1: empty pixels are sky too)
/*
const int colortex0Format = RGBA16F; // the game's light goes above 1 (LCU meshes are stored halved)
const int colortex1Format = R8;
const bool colortex1Clear = true;
const vec4 colortex1ClearColor = vec4(1.0, 0.0, 0.0, 1.0);
*/
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
varying vec2 texcoord;
void main() {
	vec3 c = texture2D(colortex0, texcoord).rgb;
	float z = texture2D(depthtex0, texcoord).r;
	// z < 0.56: the first-person hand (own depth range) - its view position is not valid for fog
	if (z < 1.0 && z > 0.56 && texture2D(colortex1, texcoord).r < 0.5) {
		vec4 v = gbufferProjectionInverse * vec4(vec3(texcoord, z) * 2.0 - 1.0, 1.0);
		vec3 view = v.xyz / v.w;
		vec3 dir = normalize(mat3(gbufferModelViewInverse) * view);
		float d = length(view);
		float f = 0.85 * smoothstep(LCU_FOG_NEAR + 0.5 * LCU_FOG_FAR, max(2.0 * LCU_FOG_FAR, LCU_FAR_FOG) / max(FOG_AMOUNT, 0.01), d) * step(0.01, FOG_AMOUNT);
		vec3 fogc = mix(LCU_LOWER_FOG, LCU_FOG, smoothstep(-0.2, 0.3, dir.y));
		c = mix(c, fogc, f);
	}
/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(c, 1.0);
}
