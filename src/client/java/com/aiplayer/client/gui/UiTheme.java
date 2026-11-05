package com.aiplayer.client.gui;

import com.aiplayer.client.config.ClientThemeConfig;
import com.aiplayer.client.gui.gpu.RoundedRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * 配置界面的统一视觉规范：深色玻璃底 + 层次卡片 + 青绿品牌色。
 * <p>
 * 26.2 的绘制走 {@link GuiGraphicsExtractor}：阴影、高光用"叠加填充"手工模拟，
 * 圆角则交给 **GPU 的解析式 SDF**（{@code aiplayer:core/rounded} 片元着色器）：
 * 四个角各提交一个 {@code r×r} 的四边形，片元里 {@code length(UV)==1} 就是弧线，
 * 靠 {@code fwidth} 做一像素宽的抗锯齿 —— 与界面缩放倍数无关，任何缩放下都是平滑弧线，
 * 而且一个圆角矩形从 {@code 2r+3} 次提交降到 {@code 3+4} 次。
 * <p>
 * 管线可用性由 {@code RoundedRenderer.ready()} 判定：老显卡 / 被资源包改坏的原版着色器
 * 导致编译不出来时，自动退回下面那套逐像素行采样的实现，界面只会变回去、不会崩。
 * 圆角由 {@link #fillRounded} / {@link #fillRoundedGradient} / {@link #outlineRounded} 三件套完成。
 * <p>
 * 字体走 {@code assets/aiplayer/font/ui.json} 里的微软雅黑粗体，
 * 所有文字方法内部都会自动套上 {@link #uiStyle()}，调用方只管传文本。
 * <p>
 * 视觉层次从上到下依次是：面板外阴影 → 面板底 → 顶栏（品牌带）→ 分区卡片 → 控件。
 * 所有颜色集中在这里定义，别的类不要再写死颜色值。
 */
public final class UiTheme {
    // ---- 主题色 token（可换肤：由 refresh() 按 ClientThemeConfig 重算，所以不是 final）----
    // 只有中性色（文字 / 阴影 / 卡片填充）固定在下面，保证任何配色下都有足够对比度。

    /** 强调色（默认青绿，取自 Logo） */
    public static int ACCENT = 0xFF3BE8D8;
    public static int ACCENT_DIM = 0xFF1B8F88;
    public static int ACCENT_DEEP = 0xFF0E5C58;
    public static int ACCENT_SOFT = 0x332AD4C8;
    /** 顶栏：比面板更亮一档，让标题"立"起来 */
    public static int HEADER_TOP = 0xF0142A30;
    public static int HEADER_BOTTOM = 0xF00D1A1F;
    /** 链接 / 可点击文字（固定中性高亮，不跟随主题） */
    public static final int LINK = 0xFF7FD8FF;

    /** 面板：微弱渐变 + 细边框 */
    public static int PANEL_TOP = 0xF00E1A1F;
    public static int PANEL_BOTTOM = 0xF0091114;
    public static int PANEL_BORDER = 0xFF24454C;
    /** 面板外的柔和阴影（固定黑，一层一层往外淡）；alpha 由「阴影强度」缩放 */
    public static int SHADOW_1 = 0x50000000;
    public static int SHADOW_2 = 0x30000000;
    public static int SHADOW_3 = 0x18000000;
    /** 输入框 */
    public static int FIELD_BG = 0xFF070C0F;
    public static int FIELD_BORDER = 0xFF23434A;

    /** 文字三级灰（固定，保证对比度） */
    public static final int TEXT = 0xFFEAF6F8;
    public static final int TEXT_DIM = 0xFFA9BEC6;
    public static final int TEXT_MUTED = 0xFF6F858D;
    /** 关闭 / 删除之类的警示色 */
    public static final int DANGER = 0xFFE8635F;

    /**
     * 控件顶部那一条 1px 内高光 / 底部那一条 1px 内暗边。
     * <p>
     * 由 {@link #innerHairline} 画：以前是一段"躲开圆角"的直线，圆角处会突兀断开；
     * 现在跟着圆弧一起拐弯，控件的边缘终于是一整条闭合的高光。
     */
    public static final int INNER_HIGHLIGHT = 0x22FFFFFF;
    public static final int INNER_SHADE = 0x40000000;

    /** 分区卡片：叠在面板上的一层极淡白 + 描边（填充用固定白，深浅底都稳）；alpha 由「卡片亮度」缩放 */
    public static int CARD_BG = 0x12FFFFFF;
    public static int CARD_TOP = 0x1CFFFFFF;
    public static int CARD_BOTTOM = 0x36000000;
    public static int CARD_FILL_TOP = 0x20FFFFFF;
    public static int CARD_FILL_BOTTOM = 0x0AFFFFFF;
    public static int CARD_BORDER = 0xFF1E353C;

    // ---- 控件色：全部由「面板底色 + 强调色」推导 ----
    public static int BUTTON_BG = 0xFF121C21;
    public static int BUTTON_BG_HOVER = 0xFF1B2A30;
    public static int BUTTON_BORDER = 0xFF24454C;
    public static int BUTTON_DISABLED = 0xFF070C0F;
    public static int CHIP_BG = 0xFF0F1519;
    public static int CHIP_BG_HOVER = 0xFF161F24;
    public static int CHIP_BORDER = 0xFF1E2A2F;
    public static int CHIP_ON_BG = 0xFF112423;
    public static int CHIP_ON_BG_HOVER = 0xFF16302F;

    /**
     * 圆角半径档位。
     * <p>
     * 数值由 {@code ClientThemeConfig.radius} 推导（见 {@link #refresh()}），
     * 玩家在「外观」页选小 / 中 / 大 / 圆润四档即可整体调整。
     * 最外层控件半径最大，往里逐级减小，做出"套娃"层次。
     */
    public static int RADIUS_PANEL = 8;
    public static int RADIUS_CARD = 6;
    public static int RADIUS_FIELD = 4;
    public static int RADIUS_BUTTON = 5;

    /**
     * 打开界面时压在整个游戏画面上的黑色遮罩。
     * <p>
     * 由 {@code ClientThemeConfig.backdrop}（浓度 0~100）推导，玩家可在「外观」页调。
     */
    public static int BACKDROP = 0x66000000;

    /**
     * 界面整体缩放的百分比（100 = 不额外缩放）。
     * <p>
     * 界面先按屏幕大小自适应，再乘上这个值 —— 玩家嫌字小 / 面板太大时可以手动微调。
     */
    public static int UI_SCALE = 100;

    /**
     * 可配置渐变（「外观」页可改两端颜色与速度）。
     * <p>
     * 不额外叠一层四边形：刷新时直接把这两个颜色按 {@link #gradientPhase()} 混进
     * {@link #PANEL_TOP} / {@link #PANEL_BOTTOM} / {@link #HEADER_TOP} / {@link #HEADER_BOTTOM}，
     * 于是面板和顶栏本身就是渐变的，绘制仍然只有"每块一个四边形"。
     * 速度是 0 时 {@code phase} 恒为 0，就是一张静止的 A→B 渐变。
     */
    public static int GRADIENT_A = 0xFF17333B;
    public static int GRADIENT_B = 0xFF2A1A44;
    public static int GRADIENT_SPEED = 35;

    /** 渐变对面板底色的染色强度（0 = 不染色，1 = 完全用渐变顶掉底色） */
    private static final float GRADIENT_MIX = 0.55F;

    /** 界面字体：微软雅黑粗体（{@code assets/aiplayer/font/msyhbd.ttc}，TTF provider 取 face 0） */
    public static final FontDescription UI_FONT =
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("aiplayer", "ui"));

    private UiTheme() {
    }

    // ------------------------------------------------------------------
    // 主题刷新 / 颜色工具 / 动画
    // ------------------------------------------------------------------

    /**
     * 按客户端主题配置（{@link ClientThemeConfig}）重算全部颜色 / 圆角 token。
     * <p>
     * 幂等且开销极小（几十次整数运算），所以界面每次绘制前、或玩家改了配色后调用都很安全：
     * 在「外观」页点一下色块，整个界面立刻换肤。
     */
    public static void refresh() {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        int accent = cfg.accentRgb();
        int base = cfg.panelRgb();
        // 强调色三档：本色 / 压暗（描边）/ 更暗（渐变末端）
        ACCENT = argb(0xFF, accent);
        ACCENT_DIM = argb(0xFF, darken(accent, 0.34f));
        ACCENT_DEEP = argb(0xFF, darken(accent, 0.58f));
        ACCENT_SOFT = argb(0x33, accent);
        // 遮罩浓度：0 = 完全看穿（不压暗），100 = 接近全黑
        BACKDROP = argb(Math.round(2.55f * Math.max(0, Math.min(100, cfg.backdrop))), 0x000000);
        // 界面额外缩放（乘在屏幕自适应之上）
        UI_SCALE = Math.max(60, Math.min(140, cfg.uiScale));
        // 阴影强度：三层阴影的 alpha 一起按比例缩放，0 就是"面板贴平在画面上"
        float sh = Math.max(0, Math.min(100, cfg.shadow)) / 100f;
        SHADOW_1 = alpha(0x50000000, sh);
        SHADOW_2 = alpha(0x30000000, sh);
        SHADOW_3 = alpha(0x18000000, sh);
        // 卡片亮度：分区卡片的填充 / 描边高光一起缩放，0 只剩描边
        float ct = Math.max(0, Math.min(200, cfg.cardTint)) / 100f;
        CARD_BG = alpha(0x12FFFFFF, ct);
        CARD_TOP = alpha(0x1CFFFFFF, ct);
        CARD_BOTTOM = alpha(0x36000000, ct);
        CARD_FILL_TOP = alpha(0x20FFFFFF, ct);
        CARD_FILL_BOTTOM = alpha(0x0AFFFFFF, ct);
        // 面板 / 顶栏：都以面板底色为基准微调明度，保持同色系。
        // 基准 alpha 0xF0 再乘上「面板不透明度」— 玩家把它调低就能透过面板看见身后的世界。
        int pa = Math.round(0xF0 * Math.max(0, Math.min(100, cfg.panelOpacity)) / 100f);
        // 可配置渐变：按当前相位在两端颜色之间来回混合，再染进面板 / 顶栏的上下两端。
        // 相位每帧重算（refresh() 每帧调用），所以速度 > 0 时界面会缓慢"呼吸"。
        GRADIENT_A = argb(0xFF, cfg.gradientARgb());
        GRADIENT_B = argb(0xFF, cfg.gradientBRgb());
        GRADIENT_SPEED = Math.max(0, Math.min(100, cfg.gradientSpeed));
        float gp = gradientPhase();
        int gTop = mix(GRADIENT_A, GRADIENT_B, gp);
        int gBottom = mix(GRADIENT_B, GRADIENT_A, gp);
        HEADER_TOP = argb(pa, mix(lighten(base, 0.07f), gTop, GRADIENT_MIX));
        HEADER_BOTTOM = argb(pa, mix(darken(base, 0.05f), gBottom, GRADIENT_MIX));
        PANEL_TOP = argb(pa, mix(lighten(base, 0.03f), gTop, GRADIENT_MIX));
        PANEL_BOTTOM = argb(pa, mix(darken(base, 0.34f), gBottom, GRADIENT_MIX));
        PANEL_BORDER = argb(0xFF, mix(base, accent, 0.22f));
        FIELD_BG = argb(0xFF, darken(base, 0.52f));
        FIELD_BORDER = argb(0xFF, mix(base, accent, 0.20f));
        CARD_BORDER = argb(0xFF, mix(base, accent, 0.13f));
        BUTTON_BG = argb(0xFF, lighten(base, 0.035f));
        BUTTON_BG_HOVER = argb(0xFF, lighten(base, 0.09f));
        BUTTON_BORDER = argb(0xFF, mix(base, accent, 0.24f));
        BUTTON_DISABLED = argb(0xFF, darken(base, 0.30f));
        CHIP_BG = argb(0xFF, darken(base, 0.20f));
        CHIP_BG_HOVER = argb(0xFF, lighten(base, 0.05f));
        CHIP_BORDER = argb(0xFF, lighten(base, 0.07f));
        CHIP_ON_BG = argb(0xFF, mix(darken(base, 0.18f), accent, 0.12f));
        CHIP_ON_BG_HOVER = argb(0xFF, mix(darken(base, 0.10f), accent, 0.20f));
        // 圆角：内层半径贴着外层减 1（同心），层次之间不会出现"有的圆有的方"
        // 「外观」页可以一键关掉圆角（全直角），玻璃着色器也读同一组半径
        int r = cfg.roundedCorners ? Math.max(0, Math.min(12, cfg.radius)) : 0;
        RADIUS_PANEL = r;
        RADIUS_CARD = r <= 1 ? r : r - 1;
        RADIUS_FIELD = r <= 2 ? r : r - 2;
        RADIUS_BUTTON = r <= 1 ? r : r - 1;
    }

    /**
     * 渐变当前相位（0~1 的三角波，来回摆动而不是突然跳回）。
     * <p>
     * 速度 0（或玩家关了动画）时恒为 0 → 静止的 A→B 渐变；
     * 速度 100 时约 3 秒一个来回，速度 1 时约 24 秒，慢到几乎察觉不到。
     * 用 {@code System.currentTimeMillis()} 而不是 tick 计数，界面打开时不会从半路开始跳。
     */
    public static float gradientPhase() {
        if (GRADIENT_SPEED <= 0 || !ClientThemeConfig.get().animations) {
            return 0f;
        }
        float period = 24000f - 21000f * (GRADIENT_SPEED / 100f);
        float t = (System.currentTimeMillis() % (long) period) / period;
        return t < 0.5f ? t * 2f : (1f - t) * 2f;
    }

    /** 组装 ARGB：{@code a} 取 0~255，{@code rgb} 只取低 24 位 */
    public static int argb(int a, int rgb) {
        return ((a & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }

    /** 按比例缩放已有颜色的 alpha（0~1） */
    public static int alpha(int color, float mul) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, mul)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    /**
     * ARGB 线性混合（四个通道都插值）。
     * <p>
     * alpha 一起插值，所以返回值可以直接丢给绘制 API，不会出现"算完颜色忘了补 alpha"导致整块透明的情况。
     * 注意：若输入本身只有 RGB（alpha=0，例如 {@link ClientThemeConfig} 解析出来的色值），
     * 结果 alpha 也会偏小——这种"取色"场景请用 {@link #argb} 显式补上 alpha。
     */
    public static int mix(int colorA, int colorB, float t) {
        if (t <= 0f) {
            return colorA;
        }
        if (t >= 1f) {
            return colorB;
        }
        int aa = (colorA >>> 24) & 0xFF, ar = (colorA >> 16) & 0xFF, ag = (colorA >> 8) & 0xFF, ab = colorA & 0xFF;
        int ba = (colorB >>> 24) & 0xFF, br = (colorB >> 16) & 0xFF, bg = (colorB >> 8) & 0xFF, bb = colorB & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24)
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    /** 往白里提亮（alpha 跟着一起插值） */
    public static int lighten(int color, float t) {
        return mix(color, 0xFFFFFFFF, t);
    }

    /** 往黑里压暗（alpha 跟着一起插值） */
    public static int darken(int color, float t) {
        return mix(color, 0xFF000000, t);
    }

    /** HSV → RGB（h / s / v 都是 0~1）：随机强调色用，免得依赖 AWT */
    public static int hsvToRgb(float h, float s, float v) {
        float hh = (h - (float) Math.floor(h)) * 6f;
        int i = (int) hh;
        float f = hh - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r;
        float g;
        float b;
        switch (i % 6) {
            case 0 -> {
                r = v;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = v;
                b = p;
            }
            case 2 -> {
                r = p;
                g = v;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = v;
            }
            case 4 -> {
                r = t;
                g = p;
                b = v;
            }
            default -> {
                r = v;
                g = p;
                b = q;
            }
        }
        return (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }

    /**
     * 逐帧指数平滑的动画值。
     * <p>
     * 用法：在渲染回调里 {@code to(目标值)} 一次，返回值就是这一帧该用的插值结果。
     * 内部按真实时间差推进，与帧率无关；配置里关掉动画时直接吸附到目标（瞬间切换）。
     */
    public static final class Anim {
        private float value;
        private long last;

        public Anim(float start) {
            this.value = start;
        }

        public float value() {
            return this.value;
        }

        /** 立刻跳到某个值（不做动画） */
        public void set(float v) {
            this.value = v;
            this.last = 0L;
        }

        /** 向目标逼近一帧（默认速度），返回当前插值 */
        public float to(float target) {
            return to(target, 14f);
        }

        /** 向目标逼近一帧，{@code speed} 越大越跟手 */
        public float to(float target, float speed) {
            long now = System.currentTimeMillis();
            float dt = this.last == 0L ? 0.05f : Math.min(0.1f, (now - this.last) / 1000f);
            this.last = now;
            if (!ClientThemeConfig.get().animations) {
                this.value = target;
                return this.value;
            }
            this.value += (target - this.value) * (1f - (float) Math.exp(-dt * speed));
            if (Math.abs(target - this.value) < 0.0015f) {
                this.value = target;
            }
            return this.value;
        }
    }

    /**
     * 柔和外发光：往外叠三圈逐渐变淡的矩形。
     * <p>
     * 只往外扩，内部留给调用方自己覆盖，所以能安全地垫在按钮 / 芯片 / 色块下面当 hover 光晕。
     * 半径沿用控件自己的，光晕跟着控件的圆角一起圆。
     */
    public static void glow(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                            int radius, int color, float strength) {
        if (strength <= 0.02f) {
            return;
        }
        for (int i = 3; i >= 1; i--) {
            // 半径跟着外扩量一起加，三圈弧线才是同一个圆心；否则角上三条弧错开叠色，
            // 就成了玩家说的"角外面一圈发暗发糊的脏边"。
            fillRounded(g, x1 - i, y1 - i, x2 + i, y2 + i, radius + i,
                    alpha(color, strength * 0.20f / i));
        }
    }

    // ------------------------------------------------------------------
    // 字体
    // ------------------------------------------------------------------

    /** 界面字体样式（不带颜色） */
    public static Style uiStyle() {
        return Style.EMPTY.withFont(UI_FONT);
    }

    /** 输入框 hint 用的样式：界面字体 + 弱化灰（带样式才不会被原版 hint 样式覆盖） */
    public static Style hintStyle() {
        return uiStyle().withColor(TEXT_MUTED);
    }

    /**
     * 把整棵组件树的字体换成界面字体，保留各节点自己的颜色等样式。
     * <p>
     * 必须逐个节点处理：按钮文案多是 {@code translatable(...).append(...)} 拼出来的，
     * 只给根节点套样式，追加的兄弟节点不会跟着变（字体是逐段解析的）。
     */
    public static Component ui(Component text) {
        if (text == null) {
            return Component.empty();
        }
        // 已经全套过界面字体（常见于布局阶段预置好、每帧复用的文本）就直接返回原对象，
        // 免掉每帧一次 copy() + 递归 setStyle —— 文字多的页面这是最实在的 GC 减负。
        if (hasUiFont(text)) {
            return text;
        }
        MutableComponent copy = text.copy();
        applyFont(copy);
        return copy;
    }

    /** 这棵组件树是否每个节点都已经用界面字体 */
    private static boolean hasUiFont(Component component) {
        if (!UI_FONT.equals(component.getStyle().getFont())) {
            return false;
        }
        for (Component sibling : component.getSiblings()) {
            if (!hasUiFont(sibling)) {
                return false;
            }
        }
        return true;
    }

    private static void applyFont(MutableComponent component) {
        component.setStyle(component.getStyle().withFont(UI_FONT));
        for (Component sibling : component.getSiblings()) {
            if (sibling instanceof MutableComponent mutable) {
                applyFont(mutable);
            }
        }
    }

    // ------------------------------------------------------------------
    // 圆角绘制（逐行解析 + 覆盖率抗锯齿）
    // ------------------------------------------------------------------
    //
    // 圆弧的解析式：圆心在角块的内角，半径 R。第 y 行（行中心 y + 0.5）到圆心的垂直距离
    // |dy| 一旦 ≥ R，这一行就在圆外；否则该行的水平半宽是 sqrt(R² - dy²)。

    /** 半径 {@code radius}、圆心 {@code cy} 的圆在第 {@code y} 行（按行中心算）的水平半宽；整行在圆外返回 -1 */
    private static float diskHalfWidth(float radius, float cy, int y) {
        float dy = (y + 0.5f) - cy;
        float room = radius * radius - dy * dy;
        return room <= 0f ? -1f : (float) Math.sqrt(room);
    }

    /** 竖直渐变第 {@code y} 行（按行中心取值）的颜色；两端色相同时直接返回该色 */
    private static int rowColor(int topColor, int bottomColor, int y, int gradY1, int gradH) {
        if (topColor == bottomColor || gradH <= 0) {
            return topColor;
        }
        return lerp(topColor, bottomColor, (y - gradY1 + 0.5f) / gradH);
    }

    /** ARGB 线性插值（自己算，不依赖不确定的 API） */
    private static int lerp(int a, int b, float t) {
        if (t <= 0f) {
            return a;
        }
        if (t >= 1f) {
            return b;
        }
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = Math.round(aa + (ba - aa) * t);
        int rr = Math.round(ar + (br - ar) * t);
        int rg = Math.round(ag + (bg - ag) * t);
        int rb = Math.round(ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    /**
     * 纯色圆角矩形。
     * <p>
     * 半径被夹到 {@code min(w, h) / 2}，所以角块与中间段永远不会重叠；
     * {@code radius <= 0} 直接退回普通矩形。
     */
    public static void fillRounded(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int radius, int color) {
        fillRoundedGradient(g, x1, y1, x2, y2, radius, color, color);
    }

    /**
     * 上下渐变（或纯色）的圆角矩形，本类的核心绘制方法。
     * <p>
     * GPU 可用时：**整块矩形一次提交**（{@link RoundedRenderer}），四条直边与四个圆角都在
     * 同一个四边形里由片元着色器的 SDF 解析出来，所以没有拼接缝、没有叠色差；上下渐变靠
     * 上下顶点色插值，纯色只是"两端颜色相同"的特例。
     * <p>
     * GPU 不可用时：整段退回逐行采样实现（{@link #fillArcRows}），观感与改造前完全一致。
     */
    public static void fillRoundedGradient(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                                           int radius, int topColor, int bottomColor) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            if (topColor == bottomColor) {
                g.fill(x1, y1, x2, y2, topColor);
            } else {
                g.fillGradient(x1, y1, x2, y2, topColor, bottomColor);
            }
            return;
        }
        if (RoundedRenderer.fill(g, x1, y1, x2, y2, r, topColor, bottomColor)) {
            return;
        }
        // ---- 回退：逐行采样 ----
        g.fillGradient(x1, y1 + r, x2, y2 - r,
                rowColor(topColor, bottomColor, y1 + r, y1, h),
                rowColor(topColor, bottomColor, y2 - r - 1, y1, h));
        fillArcRows(g, x1, y1, x2, y2, r, topColor, bottomColor, y1, h);
    }

    /**
     * 上下两段圆弧：逐像素行填，每行的左右端各补一个按覆盖率调低 alpha 的边缘像素，
     * 于是弧线在任何界面缩放下都是平滑过渡，而不是整数像素的台阶。
     */
    private static void fillArcRows(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r,
                                    int topColor, int bottomColor, int gradY1, int gradH) {
        topArc(g, x1, x2, y1, r, topColor, bottomColor, gradY1, gradH);
        bottomArc(g, x1, x2, y2, r, topColor, bottomColor, gradY1, gradH);
    }

    /** 上边圆弧（逐行填，左右两端带覆盖率抗锯齿） */
    private static void topArc(GuiGraphicsExtractor g, int x1, int x2, int y1, int r,
                               int topColor, int bottomColor, int gradY1, int gradH) {
        int cxL = x1 + r;
        int cxR = x2 - r;
        float cy = y1 + r;
        for (int y = y1; y < y1 + r; y++) {
            float half = diskHalfWidth(r, cy, y);
            if (half < 0f) {
                continue;
            }
            arcRow(g, y, y + 1, cxL - half, cxR + half,
                    rowColor(topColor, bottomColor, y, gradY1, gradH));
        }
    }

    /** 下边圆弧（逐行填，越往下越窄） */
    private static void bottomArc(GuiGraphicsExtractor g, int x1, int x2, int y2, int r,
                                  int topColor, int bottomColor, int gradY1, int gradH) {
        int cxL = x1 + r;
        int cxR = x2 - r;
        float cy = y2 - r;
        for (int y = y2 - r; y < y2; y++) {
            float half = diskHalfWidth(r, cy, y);
            if (half < 0f) {
                continue;
            }
            arcRow(g, y, y + 1, cxL - half, cxR + half,
                    rowColor(topColor, bottomColor, y, gradY1, gradH));
        }
    }

    /**
     * 画圆弧的一行：中间实心，两端各补一个半透明像素做覆盖率抗锯齿。
     * <p>
     * 原版 {@code fill} 只能画整像素矩形，圆弧落在像素之间时要么多填一列、要么少填一列，
     * 放大看就是台阶。这里把"被覆盖了 0.3 / 0.7 个像素"的那一列按覆盖率调低 alpha，
     * 边缘就成了渐变过渡，任何界面缩放下都是一条平滑弧线。
     */
    private static void arcRow(GuiGraphicsExtractor g, int y, int yEnd, float left, float right, int color) {
        int ia = (int) Math.ceil(left);
        int ib = (int) Math.floor(right);
        if (ib > ia) {
            g.fill(ia, y, ib, yEnd, color);
        }
        float fa = ia - left;
        if (fa > 0.01f && ia - 1 >= 0) {
            g.fill(ia - 1, y, ia, yEnd, withCoverage(color, fa));
        }
        float fb = right - ib;
        if (fb > 0.01f) {
            g.fill(ib, y, ib + 1, yEnd, withCoverage(color, fb));
        }
    }

    /** 按覆盖率缩放 alpha（0~1） */
    private static int withCoverage(int color, float coverage) {
        int a = (color >>> 24) & 0xFF;
        int na = Math.round(a * Math.max(0f, Math.min(1f, coverage)));
        if (na <= 0) {
            return color & 0x00FFFFFF;
        }
        return (na << 24) | (color & 0x00FFFFFF);
    }

    /**
     * 圆角矩形顶边渐变（下边两个角是直角）。
     * <p>
     * 顶栏用：它贴着面板上沿，只需要圆上面两个角。
     */
    private static void fillTopRoundedGradient(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                                               int radius, int topColor, int bottomColor) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            g.fillGradient(x1, y1, x2, y2, topColor, bottomColor);
            return;
        }
        g.fillGradient(x1, y1 + r, x2, y2,
                rowColor(topColor, bottomColor, y1 + r, y1, h),
                rowColor(topColor, bottomColor, y2 - 1, y1, h));
        topArc(g, x1, x2, y1, r, topColor, bottomColor, y1, h);
    }

    /**
     * 圆角矩形底边（上面两个角是直角）。
     * <p>
     * 底栏用：它贴着面板下沿。以前底栏是一整条普通 {@code fill}，方角直接盖住了
     * 面板下方的两个圆角 —— 那块本该是透明的背景被涂上了颜色，看起来就是"面板角上糊了一块"。
     */
    public static void fillBottomRounded(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                                         int radius, int color) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x1, y1, x2, y2, color);
            return;
        }
        g.fill(x1, y1, x2, y2 - r, color);
        bottomArc(g, x1, x2, y2, r, color, color, y1, h);
    }

    /**
     * 圆角描边（只画边框，不填内部）。
     * <p>
     * GPU 可用时：**整块一个四边形**走 {@code RING} 模式，边框从左侧直边起、绕过四个圆角、
     * 再回到右侧，全程是一条连续的线，没有拼接、没有断口。
     * 不可用时退回"只画四条直边"，圆角处留 1px 缺口（改造前就有的观感）。
     * <p>
     * 其它线宽仍走旧路径：环宽要按线宽另建管线变体，而界面里全部是 1px。
     */
    public static void outlineRounded(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                                      int radius, int thickness, int color) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0 || thickness <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            g.outline(x1, y1, w, h, color);
            return;
        }
        int t = Math.min(thickness, Math.min(w, h) / 2);
        if (t == 1 && RoundedRenderer.ring(g, x1, y1, x2, y2, r, color)) {
            return;
        }
        g.fill(x1 + r, y1, x2 - r, y1 + t, color);
        g.fill(x1 + r, y2 - t, x2 - r, y2, color);
        g.fill(x1, y1 + r, x1 + t, y2 - r, color);
        g.fill(x2 - t, y1 + r, x2, y2 - r, color);
        // 四个圆角也补上 1px 描边，否则边框会在拐角处断开（放大看就是"角上少一段"）
        arcOutline(g, x1, x2, y1, y2, r, color);
    }

    /** 沿四个圆弧走一圈 1px 描边（逐行一个像素，配合覆盖率取整仍是连续的一条线） */
    private static void arcOutline(GuiGraphicsExtractor g, int x1, int x2, int y1, int y2,
                                   int r, int color) {
        int cxL = x1 + r;
        int cxR = x2 - r;
        for (int y = y1; y < y1 + r; y++) {
            float half = diskHalfWidth(r, (float) (y1 + r), y);
            if (half < 0f) {
                continue;
            }
            arcPixel(g, cxL - half, y, color);
            arcPixel(g, cxR + half, y, color);
        }
        for (int y = y2 - r; y < y2; y++) {
            float half = diskHalfWidth(r, (float) (y2 - r), y);
            if (half < 0f) {
                continue;
            }
            arcPixel(g, cxL - half, y, color);
            arcPixel(g, cxR + half, y, color);
        }
    }

    /** 圆弧上的一个描边像素 */
    private static void arcPixel(GuiGraphicsExtractor g, float x, int y, int color) {
        int p = (int) Math.floor(x);
        g.fill(p, y, p + 1, y + 1, color);
    }

    /**
     * 内嵌 1px 高光 / 暗边：**沿着圆角走**的一条边。
     * <p>
     * 以前控件顶部那条高光为了躲圆角，只能画 {@code [x1+r, x2-r]} 这一段直线，圆角两侧
     * 突兀断掉，近看像被剪了一刀。现在整条高光是同一个图元：上排顶点给实色、下排给透明，
     * 于是从左弧起、绕过顶部、顺着右弧自然淡出，中间没有任何接缝。
     *
     * @param top {@code true} = 顶部高光（上亮下淡），{@code false} = 底部暗边（上淡下暗）
     */
    public static void innerHairline(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                                     int radius, int color, boolean top) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        int clear = color & 0x00FFFFFF;
        if (r == 0) {
            if (top) {
                g.fill(x1, y1, x2, y1 + 1, color);
            } else {
                g.fill(x1, y2 - 1, x2, y2, color);
            }
            return;
        }
        if (RoundedRenderer.ring(g, x1, y1, x2, y2, r,
                top ? color : clear, top ? clear : color)) {
            return;
        }
        if (top) {
            g.fill(x1 + r, y1, x2 - r, y1 + 1, color);
        } else {
            g.fill(x1 + r, y2 - 1, x2 - r, y2, color);
        }
    }

    /**
     * 三层柔和投影：半径逐层 +1、透明度逐层递减，画出"面板浮在画面上"的层次。
     * <p>
     * 每层本身就是 GPU 圆角矩形（3 次直边 + 4 个角），比改造前逐行采样便宜得多，
     * 所以这里放心用三层；强度为 0 时整段跳过。
     */
    public static void softShadow(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                                  int radius, int color, float strength) {
        if (strength <= 0.02f || (color >>> 24) == 0) {
            return;
        }
        for (int i = 3; i >= 1; i--) {
            fillRounded(g, x1 - i, y1 - i, x2 + i, y2 + i, radius + i,
                    alpha(color, strength * 0.30f / i));
        }
    }

    /** 胶囊：半径取一半高度，用于状态点 / 滚动条 / 指示器等小圆角件 */
    public static void pill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        fillRounded(g, x1, y1, x2, y2, (y2 - y1) / 2, color);
    }

    /** 胶囊描边（1px） */
    public static void pillOutline(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        outlineRounded(g, x1, y1, x2, y2, (y2 - y1) / 2, 1, color);
    }

    /** 圆角容器：填底色 + 四条直边描边（不再整块再铺一层描边色，省一个圆角矩形） */
    public static void roundedBox(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                                  int radius, int borderColor, int fillColor) {
        fillRounded(g, x1, y1, x2, y2, radius, fillColor);
        outlineRounded(g, x1, y1, x2, y2, radius, 1, borderColor);
    }

    // ------------------------------------------------------------------
    // 面板 / 卡片
    // ------------------------------------------------------------------

    /** 主面板：液态玻璃底（不可用时退回深色渐变）+ 细边框 + 顶部强调色细线 */
    public static void panel(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        int r = RADIUS_PANEL;
        // 液态玻璃：把这块圆角矩形登记给玻璃通道，真正绘制发生在界面模糊分层处
        // （那时背景已经可以拷出来做磨砂，文字还没画上去）。登记成功就不要再铺底色了。
        boolean glass = com.aiplayer.client.gui.glass.GlassLayer.add(g, x1, y1, x2, y2,
                r, r, r, r, PANEL_TOP, PANEL_BOTTOM, 0.9f, 0.8f, 7.0f, 0.66f);
        if (!glass) {
            // 外阴影：三层柔和投影（强度为 0 时整段跳过）。
            // 改造前是"两层各画一遍全部圆弧"，现在每层都走 GPU 的四个角，成本低一个量级。
            if ((SHADOW_1 >>> 24) != 0) {
                softShadow(g, x1, y1, x2, y2, r, 0xFF000000, ((SHADOW_1 >>> 24) & 0xFF) / 128f);
            }
            // 底色一次画完，边框只走四条直边（不再"整块描边色 + 内层渐变"两层圆角矩形）
            fillRoundedGradient(g, x1, y1, x2, y2, r, PANEL_TOP, PANEL_BOTTOM);
        }
        if (!glass) {
            outlineRounded(g, x1, y1, x2, y2, r, 1, PANEL_BORDER);
        }
        // 液态玻璃的三层质感：顶部高光边缘（折射感）+ 上部反光 + 底部内阴影
        innerHairline(g, x1, y1, x2, y2, r, 0x33FFFFFF, true);
        innerHairline(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, r - 1, 0x1A000000, false);
        int sheenBottom = Math.min(y1 + 22, y2 - r);
        if (sheenBottom > y1 + 3) {
            g.fillGradient(x1 + r, y1 + 2, x2 - r, sheenBottom, 0x16FFFFFF, 0x00FFFFFF);
        }
        // 玻璃形态下边缘由着色器画（rim + 菲涅尔），不再叠这两条强调色细线
        if (!glass) {
            g.fillGradient(x1 + r, y1 + 1, x2 - r, y1 + 2, ACCENT, ACCENT_SOFT);
            g.fillGradient(x1 + r, y2 - 2, x2 - r, y2 - 1, ACCENT_SOFT, ACCENT_DIM);
        }
    }

    /**
     * 顶栏：面板最上面那条品牌带（品牌环 + 标题 + 右侧版本号），底边压一条强调色分隔线。
     */
    public static void header(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        // 顶栏只圆上面两个角，下面两个角是直角（贴着面板主体）。
        boolean glass = com.aiplayer.client.gui.glass.GlassLayer.add(g, x1, y1, x2, y2,
                RADIUS_PANEL, RADIUS_PANEL, 0, 0, HEADER_TOP, HEADER_BOTTOM, 0.9f, 0.9f, 4.0f, 0.74f);
        if (!glass) {
            fillTopRoundedGradient(g, x1, y1, x2, y2, RADIUS_PANEL, HEADER_TOP, HEADER_BOTTOM);
        }
        // 底边：强调色渐变细线（左侧亮、右侧淡出），做出品牌感
        g.fillGradient(x1, y2 - 1, x2, y2, ACCENT_DIM, ACCENT_SOFT);
    }

    /** 分区卡片底：略亮的内层渐变 + 四条直边描边 + 左侧品牌竖条 */
    public static void card(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        int r = RADIUS_CARD;
        // 卡片也走玻璃通道：它画在面板之后、按钮之前，顺序正好保证按钮叠在卡片上
        boolean glass = com.aiplayer.client.gui.glass.GlassLayer.add(g, x1, y1, x2, y2,
                r, r, r, r, CARD_FILL_TOP, CARD_FILL_BOTTOM, 0.55f, 0.5f, 5.0f, 0.5f);
        if (!glass) {
            fillRoundedGradient(g, x1, y1, x2, y2, r, CARD_FILL_TOP, CARD_FILL_BOTTOM);
            outlineRounded(g, x1, y1, x2, y2, r, 1, CARD_BORDER);
        }
        // 卡片同样带一条顶部高光，和面板的玻璃质感保持一致
        innerHairline(g, x1, y1, x2, y2, r, 0x1FFFFFFF, true);
        // 左侧品牌竖条（渐变）：两端做成圆头（半径 = 半宽），视觉上是根实体小棍，
        // 不会在两端露出被圆角剪断的直角；竖直范围仍避开卡片的圆弧段。
        int barTop = y1 + Math.max(2, r - 2);
        int barBottom = y2 - Math.max(2, r - 2);
        if (barBottom > barTop) {
            fillRoundedGradient(g, x1 + 2, barTop, x1 + 4, barBottom, 1, ACCENT, ACCENT_DEEP);
        }
    }

    /** 一条横向分隔线（左端青绿，向右淡出） */
    public static void divider(GuiGraphicsExtractor g, int x1, int y, int x2) {
        g.fillGradient(x1, y, x2, y + 1, ACCENT_DIM, CARD_BORDER);
    }

    /** 小徽章（版本号之类）：圆角底 + 描边 */
    public static void badge(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, boolean accent) {
        roundedBox(g, x1, y1, x2, y2, Math.min(RADIUS_FIELD, (y2 - y1) / 2),
                accent ? ACCENT_DIM : CARD_BORDER,
                accent ? 0x331BE8D8 : 0x22FFFFFF);
    }

    /** 模组 logo（由 icon.png 预缩放出的 64×64 版本，画到 16px 时不会糊成一团） */
    private static final net.minecraft.resources.Identifier LOGO =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("aiplayer", "textures/icon_64.png");

    /** 品牌小图标：按给定边长绘制，比例固定为 1:1（源图是 64×64，缩到 16 依然清晰） */
    public static void brand(GuiGraphicsExtractor g, int x, int y, int size) {
        g.blit(LOGO, x, y, size, size, 0.0f, 0.0f, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------
    // 输入框 / 文字
    // ------------------------------------------------------------------

    /** 输入框外框，圆角，聚焦时描边换成品牌青绿并带一圈光晕 */
    public static void field(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, boolean focused) {
        int r = RADIUS_FIELD;
        if (focused) {
            // 聚焦时外围一圈更淡的光晕（同屏只有一个是聚焦态，代价可以忽略）
            fillRounded(g, x1 - 1, y1 - 1, x2 + 1, y2 + 1, r + 1, ACCENT_SOFT);
        }
        // 底色一次画完，描边只走四条直边：以前是"描边圆角块 + 内层圆角块"两层，多一倍四边形
        fillRounded(g, x1, y1, x2, y2, r, FIELD_BG);
        outlineRounded(g, x1, y1, x2, y2, r, 1, focused ? ACCENT : FIELD_BORDER);
    }

    /**
     * 菜单 / 标题界面里 {@code Minecraft.level} 和 {@code player} 都是 null，而部分物品模型的
     * 属性要从世界或玩家身上取值（例如指南针 {@code CompassAngleState} 第一行就是
     * {@code level.getGameTime()}），这时 {@code g.item(...)} 会直接 NPE 把游戏打崩。
     * 所以**没有世界时一律不画物品图标**，只留文字。
     */
    public static boolean canDrawItemIcons() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null;
    }

    /** 普通文字（自动套界面字体） */
    public static void text(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
        g.text(font, ui(text), x, y, color);
    }

    /** 居中文字（自动套界面字体） */
    public static void centeredText(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
        g.centeredText(font, ui(text), x, y, color);
    }

    /** 分组标题：青绿竖条 + 原版物品图标 + 青绿标签 */
    public static void section(GuiGraphicsExtractor g, Font font, ItemStack icon, Component text, int x, int y) {
        g.fill(x, y, x + 2, y + 9, ACCENT);
        g.fill(x + 2, y + 2, x + 3, y + 7, ACCENT_DIM);
        int tx = x + 6;
        if (icon != null && !icon.isEmpty() && canDrawItemIcons()) {
            // 图标比标题带高，往上挪 3px 让它在视觉上和文字居中对齐
            g.item(icon, x + 6, y - 3);
            tx = x + 26;
        }
        text(g, font, text, tx, y + 1, ACCENT);
    }

    /** 没有图标的分组标题 */
    public static void section(GuiGraphicsExtractor g, Font font, Component text, int x, int y) {
        section(g, font, ItemStack.EMPTY, text, x, y);
    }

    /** 普通标签 */
    public static void label(GuiGraphicsExtractor g, Font font, Component text, int x, int y) {
        text(g, font, text, x, y, TEXT_DIM);
    }

    /** 弱化的说明文字 */
    public static void hint(GuiGraphicsExtractor g, Font font, Component text, int x, int y) {
        text(g, font, text, x, y, TEXT_MUTED);
    }

    /** 居中文字（画在指定矩形里） */
    public static void centered(GuiGraphicsExtractor g, Font font, Component text, int x1, int x2, int y, int color) {
        Component styled = ui(text);
        g.text(font, styled, (x1 + x2 - font.width(styled)) / 2, y, color);
    }
}
