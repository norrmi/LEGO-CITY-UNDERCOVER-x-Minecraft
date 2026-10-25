#version 120
varying vec2 lmcoord;
varying vec3 worldPos;   // relative to the camera
uniform mat4 gbufferModelViewInverse;
void main() {
	gl_Position = ftransform();
	lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	worldPos = (gbufferModelViewInverse * (gl_ModelViewMatrix * gl_Vertex)).xyz;
}
