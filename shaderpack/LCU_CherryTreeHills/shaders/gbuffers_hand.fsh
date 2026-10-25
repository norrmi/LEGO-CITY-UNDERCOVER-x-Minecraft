#version 120
// first-person hand and held items: plain texture x colour x Minecraft light (the city's shadow, relief and
// sun shaping need world positions the hand's own projection does not have)
uniform sampler2D texture;
uniform sampler2D lightmap;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
void main() {
	vec4 c = texture2D(texture, texcoord) * glcolor;
	if (c.a < 0.1) discard;
	c.rgb *= texture2D(lightmap, lmcoord).rgb;
/* DRAWBUFFERS:01 */
	gl_FragData[0] = vec4(c.rgb, 1.0);
	gl_FragData[1] = vec4(0.0, 0.0, 0.0, 1.0);
}
