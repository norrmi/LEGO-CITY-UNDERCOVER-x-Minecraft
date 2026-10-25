#version 120
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normal;
varying vec3 worldPos;
uniform mat4 gbufferModelViewInverse;
void main() {
	gl_Position = ftransform();
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	glcolor = gl_Color;
	normal = mat3(gbufferModelViewInverse) * (gl_NormalMatrix * gl_Normal);
	worldPos = (gbufferModelViewInverse * (gl_ModelViewMatrix * gl_Vertex)).xyz;
}
