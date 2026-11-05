#version 330
#extension GL_ARB_separate_shader_objects : require

// 圆角「角块」的顶点着色器。
// uniform 块与原版 position_tex_color.vsh 逐字一致（引擎按管线布局绑定，不能改名）。
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

// 只用原版 GUI 的三件套，不额外增加顶点通道：
// 26.3 的 GUI 批处理会把格式外的属性静默丢掉，一旦丢了尺寸信息，
// 片元算出来全是 0，整块矩形被 discard —— 界面就"完全透明"了。
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 cornerUv;
layout(location = 1) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // cornerUv 在整个角块上铺满 0..1。角块边长 = 圆角半径 r，所以 UV 单位下的半径恒为 1.0，
    // 片元不需要任何尺寸参数；"1 个屏幕像素"对应多少 UV 由 fwidth 自动给出，任何界面缩放都正确。
    cornerUv = UV0;
    vertexColor = Color;
}
