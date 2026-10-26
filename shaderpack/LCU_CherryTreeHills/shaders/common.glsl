#include "/lcu_lighting.glsl"

#define BLOOM 1.0 // [0.0 0.5 1.0 1.5 2.0]
#define EXPOSURE 1.0 // [0.6 0.8 1.0 1.2 1.4]
#define FOG_AMOUNT 1.0 // [0.0 0.5 1.0 1.5 2.0]
// the haze thickens towards the far side of the city (5000 blocks), so the low-detail districts stay in sight
#define LCU_FAR_FOG 5000.0
#define NORMAL_MAPS 1 // [0 1]
#define NORMAL_STRENGTH 0.75 // [0.5 0.75 1.0 1.5 2.0]
#define NORMAL_FLIP_Y 1 // [0 1]
#define SHADOWS 1 // [0 1]
#define SHADOW_STRENGTH 1.0 // [0.5 0.75 1.0 1.25 1.5]
#define SHADOW_HALF 48.0 // [32.0 48.0 64.0 80.0 112.0]
#define SHADOW_FILTER 0 // [0 1]
const int shadowMapResolution = 1024; // [1024 2048 4096]
const float shadowDistance = 160.0;
#define SHADOW_DEPTH 256.0

// time of day, same ranges as the mod (dev.lcucraft.TimeOfDay): 0 DAWN, 1 NOON, 2 DUSK (also the night)
uniform int worldTime;
int lcuTod() {
	int t = int(mod(float(worldTime), 24000.0));
	return (t >= 22500 || t < 3000) ? 0 : (t < 9000 ? 1 : 2);
}
#define LCU_PICK(D, N, K) (lcuTod() == 0 ? D : (lcuTod() == 1 ? N : K))
#define LCU_SUN_DIR LCU_PICK(LCU_SUN_DIR_DAWN, LCU_SUN_DIR_NOON, LCU_SUN_DIR_DUSK)
#define LCU_SUN_COLOUR LCU_PICK(LCU_SUN_COLOUR_DAWN, LCU_SUN_COLOUR_NOON, LCU_SUN_COLOUR_DUSK)
#define LCU_SUN_INTENSITY LCU_PICK(LCU_SUN_INTENSITY_DAWN, LCU_SUN_INTENSITY_NOON, LCU_SUN_INTENSITY_DUSK)
#define LCU_SUN_SHADOW LCU_PICK(LCU_SUN_SHADOW_DAWN, LCU_SUN_SHADOW_NOON, LCU_SUN_SHADOW_DUSK)
#define LCU_FOG LCU_PICK(LCU_FOG_DAWN, LCU_FOG_NOON, LCU_FOG_DUSK)
#define LCU_LOWER_FOG LCU_PICK(LCU_LOWER_FOG_DAWN, LCU_LOWER_FOG_NOON, LCU_LOWER_FOG_DUSK)
#define LCU_FOG_NEAR LCU_PICK(LCU_FOG_NEAR_DAWN, LCU_FOG_NEAR_NOON, LCU_FOG_NEAR_DUSK)
#define LCU_FOG_FAR LCU_PICK(LCU_FOG_FAR_DAWN, LCU_FOG_FAR_NOON, LCU_FOG_FAR_DUSK)
#define LCU_GRADE LCU_PICK(LCU_GRADE_DAWN, LCU_GRADE_NOON, LCU_GRADE_DUSK)
// the game stores shadow intensity 1.0 for DAWN and DUSK (0.5 at NOON) and softens it with its sky fill light,
// which this pack does not model: scale those down so they are not near-black
#define LCU_SHADOW_SCALE LCU_PICK(0.55, 1.0, 0.55)

// light from the sun on a surface, on top of the game's baked light
vec3 lcuDirect(vec3 worldNormal) {
	float ndl = dot(normalize(worldNormal), LCU_SUN_DIR);
	// baked light already holds most of the sun; this adds the game's shape (shadow intensity 0.5)
	return mix(vec3(1.0), LCU_SUN_COLOUR * (0.75 + 0.25 * max(ndl, 0.0) * LCU_SUN_INTENSITY), 0.5)
		* mix(1.0, 0.85, LCU_SUN_SHADOW * step(ndl, 0.0));
}

// Shadows along the game's own sun direction (LCU_SUN_DIR of the current time of day) instead of Minecraft's sun
// path. Orthographic and stable: the map's centre follows the player in whole shadow-map texels measured along
// the sun's own axes, so every texel keeps covering the same piece of the world and shadows do not swim or flicker
// while walking (a non-linear distortion or a world-grid snap would re-rasterise them at every step).
vec3 lcuShadowClip(vec3 rel, vec3 cameraPos) {
	vec3 L = LCU_SUN_DIR;
	vec3 up = abs(L.y) > 0.99 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
	vec3 r = normalize(cross(up, L));
	vec3 u = cross(L, r);
	float texel = 2.0 * SHADOW_HALF / float(shadowMapResolution);
	vec2 cam = vec2(dot(cameraPos, r), dot(cameraPos, u));
	vec2 offset = cam - floor(cam / texel) * texel;   // camera position inside its texel, 0..texel
	return vec3((dot(rel, r) + offset.x) / SHADOW_HALF, (dot(rel, u) + offset.y) / SHADOW_HALF, -dot(rel, L) / SHADOW_DEPTH);
}

vec2 lcuDistort(vec2 xy) {
	return xy;  // no distortion: it would make the texel grid move with the player
}
