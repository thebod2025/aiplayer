#version 450

// 液态玻璃片元着色器（ReGlass 的核心光照模型精简版）。
//
// 每帧 GUI 绘制时把"面板 / 顶栏"这类大圆角矩形的矩形与样式登记到 GlassInfo，
// GUI 渲染器的模糊分层处不再跑原版全屏模糊，而由这里一次性画完：
//   * 圆角由解析式 SDF 求出来（四角半径可以不同），fwidth 做 1 像素抗锯齿；
//   * 背景取模糊前的主画面副本；
//   * 边缘按折射定律做位移采样 —— 越靠近边框，采样点越往内偏，
//     背景因此被"挤"向边缘，形成 ReGlass 那种拉伸/透镜效果，并带轻微色散；
//   * 再叠渐变底色、内沿 rim light 与顶部高光。
//
// 坐标系与 ReGlass 一致：gl_FragCoord 原点在左下，矩形在上传时已翻转成 y 向上。

layout(std140) uniform GlassInfo {
    vec4 uScreen;      // (帧缓冲宽, 帧缓冲高, GUI 缩放, 时间)
    vec4 uParams;      // (控件数量, 预留, 预留, 预留)
    vec4 uRect[64];    // (x1, yBottom, x2, yTop)  已换算到帧缓冲像素
    vec4 uTop[64];     // 顶部颜色 rgba
    vec4 uBot[64];     // 底部颜色 rgba
    vec4 uRadii[64];   // (左下, 右下, 右上, 左上) 帧缓冲像素
    vec4 uFx[64];      // (rim 强度, 顶部高光强度, 模糊半径, 透明度上限)
};

uniform sampler2D Sampler0;

layout(location = 0) out vec4 fragColor;

// 四角半径可不同的圆角矩形 SDF（p 相对中心，b 半宽高，y 轴朝上）
float roundedBoxSdf(vec2 p, vec2 b, vec4 r) {
    float radius;
    // uRadii 上传时已重排成 (左下, 右下, 右上, 左上)；p.y < 0 是屏幕下半部分
    if (p.y < 0.0) {
        radius = (p.x < 0.0) ? r.x : r.y;
    } else {
        radius = (p.x < 0.0) ? r.w : r.z;
    }
    radius = clamp(radius, 0.0, min(b.x, b.y));
    vec2 q = abs(p) - b + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - radius;
}

// SDF 梯度（指向形状外部），用差分求，圆角处也能拿到正确方向
vec2 sdfNormal(vec2 p, vec2 b, vec4 r) {
    float e = 1.0;
    float dx = roundedBoxSdf(p + vec2(e, 0.0), b, r) - roundedBoxSdf(p - vec2(e, 0.0), b, r);
    float dy = roundedBoxSdf(p + vec2(0.0, e), b, r) - roundedBoxSdf(p - vec2(0.0, e), b, r);
    vec2 n = vec2(dx, dy);
    float len = length(n);
    return len > 1e-6 ? n / len : vec2(0.0);
}

// 九点采样做磨砂（中心权重更高）
vec3 sampleGlass(vec2 uv, float blurPx, vec2 fb) {
    vec2 texel = vec2(blurPx) / fb;
    uv = clamp(uv, vec2(0.0), vec2(1.0));
    vec3 c = texture(Sampler0, uv).rgb * 0.28;
    c += texture(Sampler0, clamp(uv + vec2(texel.x, 0.0), vec2(0.0), vec2(1.0))).rgb * 0.09;
    c += texture(Sampler0, clamp(uv + vec2(-texel.x, 0.0), vec2(0.0), vec2(1.0))).rgb * 0.09;
    c += texture(Sampler0, clamp(uv + vec2(0.0, texel.y), vec2(0.0), vec2(1.0))).rgb * 0.09;
    c += texture(Sampler0, clamp(uv + vec2(0.0, -texel.y), vec2(0.0), vec2(1.0))).rgb * 0.09;
    c += texture(Sampler0, clamp(uv + vec2(texel.x, texel.y) * 0.72, vec2(0.0), vec2(1.0))).rgb * 0.09;
    c += texture(Sampler0, clamp(uv + vec2(-texel.x, texel.y) * 0.72, vec2(0.0), vec2(1.0))).rgb * 0.09;
    c += texture(Sampler0, clamp(uv + vec2(texel.x, -texel.y) * 0.72, vec2(0.0), vec2(1.0))).rgb * 0.09;
    c += texture(Sampler0, clamp(uv + vec2(-texel.x, -texel.y) * 0.72, vec2(0.0), vec2(1.0))).rgb * 0.09;
    return c;
}

void main() {
    vec2 fb = uScreen.xy;
    // gl_FragCoord 就是帧缓冲像素坐标（左下原点），与备份纹理的采样方向一致
    vec2 fragPx = gl_FragCoord.xy;
    vec2 uv = fragPx / fb;

    int count = int(uParams.x);
    if (count <= 0) {
        fragColor = vec4(0.0);
        return;
    }

    float covMax = 0.0;
    vec3 tint = vec3(0.0);
    float tintA = 0.0;
    float rimAcc = 0.0;
    float hlAcc = 0.0;
    float fresAcc = 0.0;
    float glareAcc = 0.0;
    float blurPx = 0.0;
    // 折射位移（像素），按覆盖率加权平均；多个形状重叠时取平均方向
    vec2 refrPx = vec2(0.0);
    float refrW = 0.0;

    for (int i = 0; i < 64; i++) {
        if (i >= count) {
            break;
        }
        vec4 rect = uRect[i];
        float height = rect.w - rect.y;
        vec2 halfSize = vec2(rect.z - rect.x, height) * 0.5;
        if (halfSize.x <= 0.0 || halfSize.y <= 0.0) {
            continue;
        }
        // 先做矩形外扩 2px 的快速剔除，避免每个像素对几十个控件都算一次 SDF
        if (fragPx.x < rect.x - 2.0 || fragPx.x > rect.z + 2.0
                || fragPx.y < rect.y - 2.0 || fragPx.y > rect.w + 2.0) {
            continue;
        }
        vec2 center = (rect.xy + rect.zw) * 0.5;
        vec2 rel = fragPx - center;
        float sdf = roundedBoxSdf(rel, halfSize, uRadii[i]);
        if (sdf > 1.6) {
            continue;
        }
        float aa = max(fwidth(sdf), 1e-3);
        float cov = clamp(0.5 - sdf / aa, 0.0, 1.0);
        if (cov <= 0.0) {
            continue;
        }

        // t = 0 在顶边、1 在底边（rect.w 是上沿）
        float t = clamp((rect.w - fragPx.y) / max(height, 1.0), 0.0, 1.0);
        vec4 top = uTop[i];
        vec4 bot = uBot[i];
        vec3 col = mix(top.rgb, bot.rgb, t);
        // 每层透明度上限：面板默认本来就是接近不透明的深色，直接照搬会看不见身后的磨砂，
        // 这里让"玻璃层"自己决定最多透多少（顶栏比面板更实一点，层次才立得住）。
        float aTint = mix(top.a, bot.a, t) * uFx[i].w;

        // 叠层按"上层覆盖下层"合成，面板 + 顶栏重叠处自然更实
        float sa = clamp(aTint * cov, 0.0, 1.0);
        tint = mix(tint, col, sa);
        tintA = sa + tintA * (1.0 - sa);

        // ---- 边缘折射：ReGlass 的"拉伸"效果来自这里 ----
        // 距边框 thickness 以内，入射角从 0 逼近 90°；按折射率 1.45 求透射角差，
        // 采样点沿法线反方向内移，越靠边移得越多 -> 背景被挤向边框。
        float inside = -sdf;
        // 小控件（按钮）如果按全尺寸玻璃的厚度算折射，整块都会变成透镜，所以按短边收窄
        float minDim = min(rect.z - rect.x, height);
        float thickness = min(46.0 * max(uScreen.z, 1.0), minDim * 0.45);
        float xR = clamp(1.0 - inside / thickness, 0.0, 1.0);
        float thetaI = asin(xR * xR);
        float thetaT = asin(min(sin(thetaI) / 1.45, 0.999));
        float edgeFactor = -tan(thetaT - thetaI);
        if (inside >= thickness) {
            edgeFactor = 0.0;
        }
        vec2 normal = sdfNormal(rel, halfSize, uRadii[i]);
        // 0.085 与 ReGlass 的默认位移量一致；换算到像素后 x/y 都是同一个尺度（各向同性）
        vec2 offPx = -normal * edgeFactor * 0.085 * fb.y;
        float olen = length(offPx);
        float maxDisp = minDim * 0.4;
        if (olen > maxDisp && olen > 1e-5) {
            offPx *= maxDisp / olen;
        }
        float w = cov;
        refrPx += offPx * w;
        refrW += w;

        float rimW = 1.4;
        float rim = clamp(1.0 - abs(sdf + rimW * 0.5) / rimW, 0.0, 1.0);
        rimAcc += rim * uFx[i].x * cov;

        float hlW = rimW * 1.6;
        float hl = clamp(1.0 - abs(sdf + hlW) / hlW, 0.0, 1.0);
        // 四面都有一圈内沿反光：法线朝上（顶边）最亮，底边也保留 35% 的基础亮度，
        // 左右两边按法线 y 分量介于两者之间，不再是"只有顶边亮"
        float lightK = 0.35 + 0.65 * clamp(normal.y * 0.5 + 0.5, 0.0, 1.0);
        hl = hl * lightK * uFx[i].y;
        hlAcc += hl * cov;

        // ---- 菲涅尔边缘光 + 眩光（ReGlass 的 fresnel / glare）----
        // 离边框越近越亮，五次方衰减，只在最外圈十几像素可见
        float depthPx = max(inside, 0.0);
        float fresRange = min(34.0 * max(uScreen.z, 1.0), minDim * 0.30);
        float glareRange = min(110.0 * max(uScreen.z, 1.0), minDim * 0.60);
        float fres = pow(clamp(1.0 - depthPx / fresRange, 0.0, 1.0), 5.0) * uFx[i].x;
        float glare = pow(clamp(1.0 - depthPx / glareRange, 0.0, 1.0), 5.0) * uFx[i].y;
        fresAcc += fres * cov;
        glareAcc += glare * cov;

        blurPx = max(blurPx, uFx[i].z);
        covMax = max(covMax, cov);
    }

    if (covMax < 0.002) {
        fragColor = vec4(0.0);
        return;
    }

    // 折射采样：三通道各偏一点点，做出边缘色散
    vec2 refrUV = (refrW > 1e-4 ? refrPx / refrW : vec2(0.0)) / fb;
    vec3 bg;
    bg.r = sampleGlass(uv + refrUV * 0.97, blurPx, fb).r;
    bg.g = sampleGlass(uv + refrUV * 1.00, blurPx, fb).g;
    bg.b = sampleGlass(uv + refrUV * 1.03, blurPx, fb).b;

    // 磨砂背景 + 渐变底色（ReGlass 的 tint 混合比例 0.8）
    vec3 color = mix(bg, tint, clamp(tintA * 0.8, 0.0, 1.0));
    // 菲涅尔边缘光：偏白的高光贴着边框，让玻璃有"厚度"
    vec3 fresCol = mix(vec3(1.0), tint, tintA * 0.5);
    color = mix(color, fresCol, clamp(fresAcc, 0.0, 0.6));
    // 眩光：边缘附近把背景往底色方向提一点
    color = mix(color, mix(bg, tint, 0.5), clamp(glareAcc * 0.25, 0.0, 0.25));
    color += vec3(rimAcc + hlAcc);
    // alpha 用几何覆盖率做边缘抗锯齿
    fragColor = vec4(color, covMax);
}
