#version 120
varying vec3 viewPos;
void main() {
	gl_Position = ftransform();
	viewPos = (gl_ModelViewMatrix * gl_Vertex).xyz;
}
