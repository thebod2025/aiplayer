#version 330
#extension GL_ARB_separate_shader_objects : require

// 圆角片元着色器：画圆角矩形的一个「角块」（r×r 正方形里的四分之一圆盘）。
//
// 整块圆角矩形 = 4 个角块（这里）+ 几条直边带（原版 fill / fillGradient）。
// 各块互不重叠、边界处 alpha 恒为 1，所以拼接处既没有半透明叠色差，
// 也没有两条抗锯齿斜坡相加造成的 1px 淡线 —— 那就是之前"割裂感"的来源。
//
// 角块的边长就是圆角半径 r，UV 在整个角块上铺满 0..1 → 半径在 UV 空间恒为 1.0，
// 片元不需要 halfW / halfH / radius 这些参数（那条自定义顶点通道的路在 26.3 会被丢弃）。
// "一个屏幕像素对应多少 UV"由 fwidth 实时给出，所以任意界面缩放下抗锯齿都是一像素宽。
//
// 两种模式共用一份源码，靠管线 define 切：
//   默认   —— 填充（实心四分之一圆盘）
//   RING  —— 只保留贴着外沿的 1px 环带（圆角描边）

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

// 管线布局派生自原版 GUI_TEXTURED，带纹理采样器槽位；必须声明同名 uniform（只声明、不采样）。
uniform sampler2D Sampler0;

layout(location = 0) in vec2 cornerUv;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    // 圆心在内角 (1,1)，半径 1.0（UV 单位 = 整个角块边长 = r）：dist < 0 即在圆弧内部。
    // 注意：这里必须是 1.0，写成 0.5 只会留下半径 r/2 的小圆盘，角块外侧半块被整片 discard，
    // 外观就是"四个角被咬掉一大块"（之前报的"圆角损坏"就是这个）。
    float dist = length(cornerUv - vec2(1.0)) - 1.0;
    float w = max(fwidth(dist), 1e-5);   // 一个屏幕像素的 UV 宽度

#ifdef RING
    // 环带：dist ∈ [-w, 0]，正好贴着外沿一像素；band 是到环带自身边界的距离
    float band = min(-dist, dist + w);
    float alpha = clamp(band / w + 0.5, 0.0, 1.0);
#else
    float alpha = clamp(0.5 - dist / w, 0.0, 1.0);
#endif

    if (alpha <= 0.002) {
        discard;
    }
    // 竖直渐变由上下顶点的颜色插值完成，片元只管形状
    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}
