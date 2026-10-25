#version 120
#include "/common.glsl"
uniform sampler2D texture;
varying vec2 texcoord;
varying vec4 glcolor;
void main() {
	vec4 c = texture2D(texture, texcoord);
	if (c.a < 0.1) discard;   // leaves and fences cast their real outline
	gl_FragData[0] = c;
}
