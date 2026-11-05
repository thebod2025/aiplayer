#version 450

// 把离屏渲染好的玻璃图贴回主画面。
//
// 玻璃的 SDF / 折射 / 磨砂是重活，只在设定的刷新率（默认 25fps）重画一次离屏纹理；
// 中间那些帧只跑这一个"采一次纹理"的四边形，开销可以忽略。
layout(location = 0) in vec2 vUV;

uniform sampler2D Sampler0;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(Sampler0, vUV);
}
