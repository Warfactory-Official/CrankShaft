#version 430 core
layout(location=0) in vec3 position;
void main() { gl_Position = vec4(position.xy * 2.0 - 1.0, 0.0, 1.0); }
