package com.aiplayer.client.gui.gpu;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2f;

/**
 * 圆角矩形的 GPU 绘制入口。
 * <p>
 * 一块圆角矩形 = <b>4 个角块</b>（这里的角块四边形，圆弧由片元着色器解析）
 * + <b>几条直边带</b>（原版 {@code fill / fillGradient}）。
 * 这些块**互不重叠**，相邻边界处 alpha 恒为 1，所以拼接处既不会出现半透明叠色差，
 * 也不会出现两条抗锯齿斜坡相加造成的 1px 淡线 —— 那正是之前"割裂感"的来源。
 * <p>
 * 角块只写原版 GUI 的三个顶点通道；尺寸由片元用 {@code fwidth} 从 UV 导数反推，
 * 因此不依赖自定义顶点属性（26.3 的 GUI 批处理会把它们丢掉，导致整块界面透明）。
 * <p>
 * 所有方法都返回 {@code boolean}：{@code true} 表示"我画完了"，{@code false} 表示
 * "这条管线在这台机器上用不了，请你走老路"。{@link com.aiplayer.client.gui.UiTheme}
 * 靠这个返回值做回退，所以显卡不支持时界面只会退回逐行采样式圆角，不会崩也不会空白。
 */
public final class RoundedRenderer {
    private RoundedRenderer() {
    }

    /**
     * 纯色 / 竖直渐变的圆角矩形。
     * 上排两个顶点用 {@code topColor}、下排用 {@code bottomColor}，中间由 GPU 线性插值，
     * 所以纯色（两个颜色相同）与渐变走的是同一条路径。
     */
    public static boolean fill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                              int radius, int topColor, int bottomColor) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0 || radius <= 0) {
            return false;
        }
        if (!ready()) {
            return false;
        }
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0) {
            return false;
        }
        // 中间整宽横带（上下都不与角块相邻）
        if (y2 - r > y1 + r) {
            band(g, x1, y1 + r, x2, y2 - r, y1, y2, topColor, bottomColor);
        }
        // 上 / 下短带：夹在两个角块之间
        if (x2 - r > x1 + r) {
            band(g, x1 + r, y1, x2 - r, y1 + r, y1, y2, topColor, bottomColor);
            band(g, x1 + r, y2 - r, x2 - r, y2, y1, y2, topColor, bottomColor);
        }
        // 四个角块
        corner(g, RoundedPipelines.fill(), x1, y1, x1 + r, y1 + r, false, false, y1, y2, topColor, bottomColor);
        corner(g, RoundedPipelines.fill(), x2 - r, y1, x2, y1 + r, true, false, y1, y2, topColor, bottomColor);
        corner(g, RoundedPipelines.fill(), x1, y2 - r, x1 + r, y2, false, true, y1, y2, topColor, bottomColor);
        corner(g, RoundedPipelines.fill(), x2 - r, y2 - r, x2, y2, true, true, y1, y2, topColor, bottomColor);
        return true;
    }

    /** 圆角描边（贴着外沿的 1px 环带） */
    public static boolean ring(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                               int radius, int color) {
        return ring(g, x1, y1, x2, y2, radius, color, color);
    }

    /**
     * 上下不同色的圆角描边：上排用 {@code topColor}、下排用 {@code bottomColor}。
     * <p>
     * 控件的「顶部 1px 高光」就是这么画的：上排给实色、下排给全透明，
     * 高光就会从左弧起绕过顶部、最后顺着右侧弧线自然淡出，中间没有任何接缝。
     */
    public static boolean ring(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                               int radius, int topColor, int bottomColor) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0 || radius <= 0) {
            return false;
        }
        if (!ready() || RoundedPipelines.ring() == null) {
            return false;
        }
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0) {
            return false;
        }
        // 直边：上 / 下 / 左 / 右，各自与角块的圆弧在切点处相接
        if (x2 - r > x1 + r) {
            g.fill(x1 + r, y1, x2 - r, y1 + 1, colorAt(y1, y1, y2, topColor, bottomColor));
            g.fill(x1 + r, y2 - 1, x2 - r, y2, colorAt(y2 - 1, y1, y2, topColor, bottomColor));
        }
        if (y2 - r > y1 + r) {
            int cTop = colorAt(y1 + r, y1, y2, topColor, bottomColor);
            int cBottom = colorAt(y2 - r, y1, y2, topColor, bottomColor);
            g.fillGradient(x1, y1 + r, x1 + 1, y2 - r, cTop, cBottom);
            g.fillGradient(x2 - 1, y1 + r, x2, y2 - r, cTop, cBottom);
        }
        corner(g, RoundedPipelines.ring(), x1, y1, x1 + r, y1 + r, false, false, y1, y2, topColor, bottomColor);
        corner(g, RoundedPipelines.ring(), x2 - r, y1, x2, y1 + r, true, false, y1, y2, topColor, bottomColor);
        corner(g, RoundedPipelines.ring(), x1, y2 - r, x1 + r, y2, false, true, y1, y2, topColor, bottomColor);
        corner(g, RoundedPipelines.ring(), x2 - r, y2 - r, x2, y2, true, true, y1, y2, topColor, bottomColor);
        return true;
    }

    /** 管线可用性（带缓存，`UiTheme` 每帧问一次即可） */
    public static boolean ready() {
        return RoundedPipelines.available();
    }

    // ------------------------------------------------------------------
    // 内部：直边带（原版填充） / 角块（GPU）
    // ------------------------------------------------------------------

    /** 一整条竖直渐变带：颜色在整块矩形的 {@code wholeY1..wholeY2} 上插值，拼起来是连续渐变 */
    private static void band(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                             int wholeY1, int wholeY2, int topColor, int bottomColor) {
        int c1 = colorAt(y1, wholeY1, wholeY2, topColor, bottomColor);
        int c2 = colorAt(y2, wholeY1, wholeY2, topColor, bottomColor);
        if (c1 == c2) {
            g.fill(x1, y1, x2, y2, c1);
        } else {
            g.fillGradient(x1, y1, x2, y2, c1, c2);
        }
    }

    private static void corner(GuiGraphicsExtractor g, RenderPipeline pipeline,
                               int x1, int y1, int x2, int y2, boolean flipX, boolean flipY,
                               int wholeY1, int wholeY2, int topColor, int bottomColor) {
        int c1 = colorAt(y1, wholeY1, wholeY2, topColor, bottomColor);
        int c2 = colorAt(y2, wholeY1, wholeY2, topColor, bottomColor);
        add(g, pipeline, x1, y1, x2, y2, flipX, flipY, c1, c2);
    }

    /** 按 y 在整块矩形的高度上取渐变色（保证各块拼接处颜色完全一致） */
    private static int colorAt(int y, int wholeY1, int wholeY2, int topColor, int bottomColor) {
        int span = wholeY2 - wholeY1;
        if (span <= 0) {
            return topColor;
        }
        float t = (float) (y - wholeY1) / span;
        if (t <= 0f) {
            return topColor;
        }
        if (t >= 1f) {
            return bottomColor;
        }
        int aa = (topColor >>> 24) & 0xFF, ar = (topColor >> 16) & 0xFF;
        int ag = (topColor >> 8) & 0xFF, ab = topColor & 0xFF;
        int ba = (bottomColor >>> 24) & 0xFF, br = (bottomColor >> 16) & 0xFF;
        int bg = (bottomColor >> 8) & 0xFF, bb = bottomColor & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24)
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    private static void add(GuiGraphicsExtractor g, RenderPipeline pipeline,
                            int x1, int y1, int x2, int y2, boolean flipX, boolean flipY,
                            int topColor, int bottomColor) {
        GuiRenderState state = g.guiRenderState;
        // 裁剪框：没压 scissor 时给"整屏"而不是 null —— 原版排序阶段会拿它做相交判断，
        // 传 null 会让整个界面的绘制在这一帧直接中断（表现就是"GUI 完全透明"）。
        ScreenRectangle scissor = g.scissorStack.peek();
        if (scissor == null) {
            scissor = new ScreenRectangle(0, 0,
                    Minecraft.getInstance().getWindow().getGuiScaledWidth(),
                    Minecraft.getInstance().getWindow().getGuiScaledHeight());
        }
        // 位姿要在录制时快照：GUI 会攒到本帧稍后才统一写顶点，那时位姿栈已经弹回
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        state.addGuiElement(new RoundedQuad(pipeline, pose, x1, y1, x2, y2,
                flipX, flipY, topColor, bottomColor, scissor));
    }
}
