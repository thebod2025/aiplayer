package com.aiplayer.client.gui.gpu;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;

/**
 * 一个圆角矩形的「角块」：{@code r×r} 的正方形，其中四分之一圆盘由片元着色器解析出来。
 * <p>
 * 只写原版 GUI 的三个通道（Position / UV0 / Color），**不多带任何自定义属性**：
 * 26.3 的 GUI 批处理会把格式外的属性丢掉，一旦丢了片元收到的尺寸就是 0，整块被 discard
 * —— 界面会表现为"完全透明"。所以尺寸由片元用 {@code fwidth} 从 UV 导数反推，
 * 这里只需要把 UV 摆成"圆弧圆心位于 (+1,+1) 那一侧"（四个角靠 {@code flipX/flipY} 翻转）。
 * <p>
 * Color 上排用 {@code colorTop}、下排用 {@code colorBottom}，竖直渐变由 GPU 插值；
 * 片元只负责形状，所以整块不含任何内部分界线。
 */
public final class RoundedQuad implements GuiElementRenderState {
    private final RenderPipeline pipeline;
    /** 录制时的位姿快照：GUI 会延迟到本帧稍后才真正写顶点，此时位姿栈可能已经弹了 */
    private final Matrix3x2fc pose;
    private final int x1;
    private final int y1;
    private final int x2;
    private final int y2;
    /** 把 UV 反过来：让圆弧圆心落在角块的右/下侧（默认）或左/上侧 */
    private final boolean flipX;
    private final boolean flipY;
    private final int colorTop;
    private final int colorBottom;
    private final ScreenRectangle scissor;

    public RoundedQuad(RenderPipeline pipeline, Matrix3x2fc pose,
                       int x1, int y1, int x2, int y2, boolean flipX, boolean flipY,
                       int colorTop, int colorBottom, ScreenRectangle scissor) {
        this.pipeline = pipeline;
        this.pose = pose;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.flipX = flipX;
        this.flipY = flipY;
        this.colorTop = colorTop;
        this.colorBottom = colorBottom;
        this.scissor = scissor;
    }

    @Override
    public void buildVertices(VertexConsumer vc) {
        // TRIANGLE_STRIP：(左上, 左下, 右上, 右下)
        vertex(vc, x1, y1, colorTop);
        vertex(vc, x1, y2, colorBottom);
        vertex(vc, x2, y1, colorTop);
        vertex(vc, x2, y2, colorBottom);
    }

    private void vertex(VertexConsumer vc, float px, float py, int color) {
        vc.addVertexWith2DPose(this.pose, px, py);
        // UV 在整个角块上铺满 0..1（带宽就是半径），片元据此解析四分之一圆盘，
        // 不需要把尺寸塞进顶点通道 —— 那正是 26.3 会丢掉的属性。
        float u = (px - this.x1) / (float) (this.x2 - this.x1);
        float v = (py - this.y1) / (float) (this.y2 - this.y1);
        vc.setUv(this.flipX ? 1f - u : u, this.flipY ? 1f - v : v);
        vc.setColor((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >>> 24) & 0xFF);
    }

    @Override
    public RenderPipeline pipeline() {
        return this.pipeline;
    }

    @Override
    public TextureSetup textureSetup() {
        // 片元着色器不采样任何贴图，声明"无纹理"即可
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return this.scissor;
    }

    @Override
    public ScreenRectangle bounds() {
        int bx = Math.min(this.x1, this.x2);
        int by = Math.min(this.y1, this.y2);
        return new ScreenRectangle(bx, by, Math.abs(this.x2 - this.x1), Math.abs(this.y2 - this.y1));
    }
}
