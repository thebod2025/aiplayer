package com.aiplayer.client.gui.glass;

import com.aiplayer.client.config.ClientThemeConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2f;

/**
 * 液态玻璃形状登记表。
 * <p>
 * 界面每帧绘制面板 / 顶栏时，把它们的矩形、四角半径、上下颜色和效果强度登记在这里；
 * 真正绘制发生在**下一帧的 GUI 模糊阶段**（{@link GlassRenderer}）——
 * 那时主画面的颜色已经可以安全地拷贝出来做磨砂，界面上的文字也还没画上去。
 * <p>
 * 矩形在登记时就把当前的位姿缩放乘进去了（配置界面整体会按 uiScale 缩放），
 * 所以着色器里只需要再乘一次 GUI 缩放。登记失败（管线不可用）返回 {@code false}，
 * 调用方照旧走原来的 CPU 绘制，界面观感只会变回去，不会消失。
 */
public final class GlassLayer {
    /** 单帧最多登记的圆角面（面板 + 顶栏 + 所有按钮/页签，64 个够用） */
    public static final int MAX = 64;

    private static final float[] RECT = new float[MAX * 4];
    private static final float[] TOP = new float[MAX * 4];
    private static final float[] BOTTOM = new float[MAX * 4];
    private static final float[] RADII = new float[MAX * 4];
    private static final float[] FX = new float[MAX * 4];

    private static int count;

    private GlassLayer() {
    }

    /** 一帧的界面绘制开始时清空（界面布局基本稳定，模糊阶段用的是上一帧登记的形状）。 */
    public static void beginFrame() {
        count = 0;
        clipSet = false;
    }

    public static int count() {
        return count;
    }

    /** 内容区裁剪框（帧缓冲像素）；未设置时 clipSet=false */
    private static boolean clipSet;
    private static float clipX1;
    private static float clipY1;
    private static float clipX2;
    private static float clipY2;

    /** 玻璃通道能否使用（玩家没关掉 + 管线注册 + 编译成功 + 没被运行时禁用） */
    public static boolean usable() {
        return ClientThemeConfig.get().liquidGlass && GlassPipelines.available();
    }

    /** 记下内容区的裁剪框（GUI 坐标，调用方已按 uiScale 缩放）；之后登记的面都会与它求交 */
    public static void setClip(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        float s = Math.abs(new Matrix3x2f(g.pose()).m00);
        if (s <= 0f) {
            s = 1f;
        }
        clipX1 = Math.min(x1, x2) * s;
        clipX2 = Math.max(x1, x2) * s;
        clipY1 = Math.min(y1, y2) * s;
        clipY2 = Math.max(y1, y2) * s;
        clipSet = true;
    }

    public static void clearClip() {
        clipSet = false;
    }

    /**
     * 登记一块圆角面。
     *
     * @param g           当前绘制上下文（用来读位姿缩放）
     * @param topColor    ARGB 顶部颜色
     * @param bottomColor ARGB 底部颜色
     * @param rim         内沿 rim light 强度（0~1，越大越"亮边"）
     * @param highlight   顶部高光强度（0~1）
     * @param blurGui     这块玻璃背后要磨砂多少 GUI 像素（0 = 不模糊）
     * @param alphaCap    这层玻璃最多能盖住背景多少（0~1，按钮要比面板更实才好读字）
     * @return {@code true} = 已登记，调用方不要再画底；{@code false} = 走原来的实现
     */
    public static boolean add(GuiGraphicsExtractor g,
                              int x1, int y1, int x2, int y2,
                              int radiusLeftTop, int radiusRightTop, int radiusRightBottom, int radiusLeftBottom,
                              int topColor, int bottomColor,
                              float rim, float highlight, float blurGui, float alphaCap) {
        if (!usable() || count >= MAX) {
            return false;
        }
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0) {
            return false;
        }
        float s;
        try {
            s = Math.abs(new Matrix3x2f(g.pose()).m00);
        } catch (Throwable t) {
            s = 1f;
        }
        if (s <= 0f) {
            s = 1f;
        }
        int i = count++;
        int o = i * 4;
        float gx1 = x1 * s;
        float gy1 = y1 * s;
        float gx2 = x2 * s;
        float gy2 = y2 * s;
        // 内容区有 scissor 时把玻璃面同步裁掉：否则滚动的卡片/按钮会画到视口外面
        if (clipSet) {
            gx1 = Math.max(gx1, clipX1);
            gy1 = Math.max(gy1, clipY1);
            gx2 = Math.min(gx2, clipX2);
            gy2 = Math.min(gy2, clipY2);
            if (gx2 <= gx1 || gy2 <= gy1) {
                count--;
                return false;
            }
        }
        RECT[o] = gx1;
        RECT[o + 1] = gy1;
        RECT[o + 2] = gx2;
        RECT[o + 3] = gy2;
        TOP[o] = ((topColor >> 16) & 0xFF) / 255f;
        TOP[o + 1] = ((topColor >> 8) & 0xFF) / 255f;
        TOP[o + 2] = (topColor & 0xFF) / 255f;
        TOP[o + 3] = ((topColor >>> 24) & 0xFF) / 255f;
        BOTTOM[o] = ((bottomColor >> 16) & 0xFF) / 255f;
        BOTTOM[o + 1] = ((bottomColor >> 8) & 0xFF) / 255f;
        BOTTOM[o + 2] = (bottomColor & 0xFF) / 255f;
        BOTTOM[o + 3] = ((bottomColor >>> 24) & 0xFF) / 255f;
        RADII[o] = radiusLeftTop * s;
        RADII[o + 1] = radiusRightTop * s;
        RADII[o + 2] = radiusRightBottom * s;
        RADII[o + 3] = radiusLeftBottom * s;
        FX[o] = rim;
        FX[o + 1] = highlight;
        FX[o + 2] = blurGui * s;
        FX[o + 3] = alphaCap;
        return true;
    }

    static float[] rect() {
        return RECT;
    }

    static float[] top() {
        return TOP;
    }

    static float[] bottom() {
        return BOTTOM;
    }

    static float[] radii() {
        return RADII;
    }

    static float[] fx() {
        return FX;
    }
}
