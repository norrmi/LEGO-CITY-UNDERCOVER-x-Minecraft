#version 120
#include "/common.glsl"
uniform sampler2D texture;
uniform sampler2D lightmap;
uniform sampler2D normals; // the game's tangent-space normal map (handed to Iris by the mod), flat for others
uniform vec4 entityColor;
uniform sampler2D shadowtex0;
uniform vec3 cameraPosition;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normal;
varying vec3 worldPos;

// tangent frame from screen-space derivatives of position and UV (no precomputed tangents needed; also right
// for the mirrored X axis of the converted city)
vec3 mappedNormal(vec3 n) {
	vec3 dp1 = dFdx(worldPos), dp2 = dFdy(worldPos);
	vec2 duv1 = dFdx(texcoord), duv2 = dFdy(texcoord);
	vec3 dp2perp = cross(dp2, n), dp1perp = cross(n, dp1);
	vec3 t = dp2perp * duv1.x + dp1perp * duv2.x;
	vec3 b = dp2perp * duv1.y + dp1perp * duv2.y;
	float s = inversesqrt(max(max(dot(t, t), dot(b, b)), 1e-12));
	vec3 tn = texture2D(normals, texcoord).xyz * 2.0 - 1.0;
#if NORMAL_FLIP_Y == 1
	tn.y = -tn.y; // DirectX convention (green down)
#endif
	tn.xy *= NORMAL_STRENGTH;
	return normalize(mat3(t * s, b * s, n) * tn);
}

// relief only: how much brighter or darker the mapped normal is lit than the flat surface, on top of the
// game's baked light (keeps the average brightness of the game)
float relief(vec3 base, vec3 bumped) {
	vec3 up = vec3(0.0, 1.0, 0.0);
	float a = 0.6 * max(dot(bumped, LCU_SUN_DIR), 0.0) + 0.4 * (0.5 + 0.5 * dot(bumped, up));
	float b = 0.6 * max(dot(base, LCU_SUN_DIR), 0.0) + 0.4 * (0.5 + 0.5 * dot(base, up));
	return clamp(a / max(b, 0.05), 0.7, 1.35);
}

// 0 = lit by the sun, 1 = in shadow (3x3 filtered); outside the shadow map counts as lit
float lcuShadow(vec3 rel, vec3 n) {
	if (dot(n, LCU_SUN_DIR) <= 0.0) return 1.0;
	vec3 c = lcuShadowClip(rel + n * 0.15, cameraPosition);
	if (abs(c.x) >= 1.0 || abs(c.y) >= 1.0 || abs(c.z) >= 1.0) return 0.0;
	vec3 s = vec3(lcuDistort(c.xy), c.z) * 0.5 + 0.5;
#if SHADOW_FILTER == 1
	float texel = 1.0 / float(shadowMapResolution), lit = 0.0;
	for (int i = -1; i <= 1; i++) {
		for (int j = -1; j <= 1; j++) {
			lit += step(s.z - 0.0004, texture2D(shadowtex0, s.xy + vec2(i, j) * texel).r);
		}
	}
	return 1.0 - lit / 9.0;
#else
	return 1.0 - step(s.z - 0.0004, texture2D(shadowtex0, s.xy).r);
#endif
}

void main() {
	vec4 t = texture2D(texture, texcoord);
	if (t.a < 0.1) discard;
	vec4 c = t * glcolor;
	// interiors (police station, ice cream parlour: vertex alpha 124) are lit only by their own baked light
	bool inside = abs(glcolor.a - 124.0 / 255.0) < 0.006;
	bool lcu = inside || abs(glcolor.a - 128.0 / 255.0) < 0.006;
	// Cherry Tree Hills meshes: colour stored halved (vertex alpha 128) so the game's light can exceed 1;
	// the baked light is NOON, the grade re-colours it for DAWN and DUSK
	if (lcu) {
		c.rgb *= 2.0 * (inside ? vec3(1.0) : LCU_GRADE);
		c.a = t.a;
	}
	c.rgb = mix(c.rgb, entityColor.rgb, entityColor.a);
	c.rgb *= texture2D(lightmap, lmcoord).rgb;
	// LCU meshes come with zero-length normals only without a shader pack; here they are real
	if (dot(normal, normal) > 0.01) {
		vec3 n = normalize(normal);
		if (!inside) c.rgb *= lcuDirect(n);
#if NORMAL_MAPS == 1
		if (lcu) c.rgb *= relief(n, mappedNormal(n));
#endif
#if SHADOWS == 1
		// real-time sun shadows on top of the game's baked light; full strength = the game's shadow intensity
		if (!inside) c.rgb *= 1.0 - lcuShadow(worldPos, n) * clamp(LCU_SUN_SHADOW * LCU_SHADOW_SCALE * SHADOW_STRENGTH, 0.0, 0.9);
#endif
	}
/* DRAWBUFFERS:01 */
	gl_FragData[0] = c;
	gl_FragData[1] = vec4(0.0, 0.0, 0.0, 1.0); // not sky
}
