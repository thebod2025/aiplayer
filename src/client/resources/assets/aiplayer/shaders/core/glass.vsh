#version 450

// 液态玻璃全屏四边形的顶点着色器。
// 顶点缓冲里存的是 (0,0),(1,0),(1,1),(0,1) 四个角，这里直接映射到裁剪空间的整屏。
// 不引用 Projection / DynamicTransforms：这条通道画的是屏幕空间，与 GUI 的位姿无关。
// vUV 给 blit 用：把离屏玻璃图原样贴回主画面。
layout(location = 0) in vec3 Position;

layout(location = 0) out vec2 vUV;

void main() {
    vUV = Position.xy;
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
}
