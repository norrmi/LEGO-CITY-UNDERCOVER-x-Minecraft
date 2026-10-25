#version 120
#include "/common.glsl"
// shadow map along the game's sun (see common.glsl); the mod draws the city into this pass
uniform mat4 shadowModelViewInverse;
uniform vec3 cameraPosition;
varying vec2 texcoord;
varying vec4 glcolor;
void main() {
	vec3 rel = (shadowModelViewInverse * (gl_ModelViewMatrix * gl_Vertex)).xyz; // camera-relative world position
	vec3 c = lcuShadowClip(rel, cameraPosition);
	gl_Position = vec4(lcuDistort(c.xy), c.z, 1.0);
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	glcolor = gl_Color;
}
