#version 120
#include "/common.glsl"
// The sea of Cherry Tree Hills: the game's sea colour (bm_sea02) in depth, the sky of the current time of day
// reflected at grazing angles, a sun glint and small moving waves. (The game draws its sea plane with its own
// water shader; Minecraft's water stands in for that plane and stays swimmable.)
uniform vec3 cameraPosition;
uniform float frameTimeCounter;
varying vec2 lmcoord;
varying vec3 worldPos;

vec3 waveNormal(vec2 p) {
	float t = frameTimeCounter;
	vec2 g = vec2(0.0);
	g += vec2(0.8, 0.6) * cos(dot(p, vec2(0.8, 0.6)) * 0.9 + t * 1.1) * 0.9;
	g += vec2(-0.5, 0.9) * cos(dot(p, vec2(-0.5, 0.9)) * 1.7 + t * 1.6) * 0.5;
	g += vec2(0.3, -0.95) * cos(dot(p, vec2(0.3, -0.95)) * 3.1 + t * 2.3) * 0.25;
	return normalize(vec3(-g.x * 0.06, 1.0, -g.y * 0.06));
}

void main() {
	vec3 p = worldPos + cameraPosition;
	vec3 n = waveNormal(p.xz);
	vec3 v = normalize(worldPos);
	float fres = 0.15 + 0.85 * pow(1.0 - max(dot(-v, n), 0.0), 4.0);
	vec3 r = reflect(v, n);
	// sky seen in the reflection: horizon (fog) colour towards the sky colour higher up
	vec3 sky = mix(LCU_LOWER_FOG, vec3(0.30, 0.55, 0.95) * LCU_GRADE, smoothstep(0.0, 0.4, r.y));
	vec3 deep = LCU_SEA_COLOUR * 1.6 * LCU_GRADE;
	vec3 c = mix(deep, sky, fres);
	c += LCU_SUN_COLOUR * LCU_SUN_INTENSITY * pow(max(dot(r, LCU_SUN_DIR), 0.0), 300.0) * 2.0;
	// the final pass applies the game's exposure to everything that is not sky: undo it here
	c /= LCU_EXPOSURE;
/* DRAWBUFFERS:01 */
	gl_FragData[0] = vec4(c, mix(0.85, 1.0, fres));
	gl_FragData[1] = vec4(0.0, 0.0, 0.0, 1.0);
}
