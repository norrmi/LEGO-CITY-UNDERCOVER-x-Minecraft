#version 120
// the mod draws the game's NOON sky dome, sun and mountains with this program
uniform sampler2D texture;
varying vec2 texcoord;
varying vec4 glcolor;
void main() {
	vec4 c = texture2D(texture, texcoord) * glcolor;
	if (c.a < 0.02) discard;
/* DRAWBUFFERS:01 */
	gl_FragData[0] = c;
	gl_FragData[1] = vec4(1.0, 0.0, 0.0, 1.0); // sky: no fog
}
