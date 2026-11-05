package com.aiplayer.client.gui.glass;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.client.config.ClientThemeConfig;
import com.aiplayer.client.gui.AiConfigScreen;
import com.aiplayer.client.gui.OnboardingScreen;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 液态玻璃的实际绘制：在 GUI 的模糊阶段接管主画面。
 * <p>
 * 顺序上是这样的：界面先把遮罩画进主画面、再要求"模糊分层"，随后 GUI 渲染器在
 * 分层处调用 {@code GameRenderer.processBlurEffect()}。原版是跑一张全屏模糊后处理；
 * 这里把它换掉：先拷贝一份当前画面（世界 + 遮罩，还没画面板），再开一个 RenderPass
 * 把全屏四边形画回去——片元着色器负责圆角 SDF、磨砂采样、渐变底色与高光。
 * <p>
 * 只有配置界面 / 新手教程会走这条通道；其它界面完全不受影响。任何一步失败都只是
 * 这一帧没有玻璃（下一帧自动退回普通面板），不会留下异常状态。
 */
public final class GlassRenderer {
    private static final int MAX = GlassLayer.MAX;
    /** uScreen + uParams + 5 个 vec4 数组 */
    private static final int UNIFORM_SIZE = 16 * 2 + 16 * MAX * 5;

    private static GpuBuffer uniformBuffer;
    private static GpuBuffer quadBuffer;
    private static GpuTexture backdropTexture;
    private static GpuTextureView backdropView;
    private static int backdropWidth;
    private static int backdropHeight;
    /** 离屏玻璃纹理（按设定帧率重画，每帧只 blit 回主画面） */
    private static GpuTexture glassTexture;
    private static GpuTextureView glassView;
    private static int glassWidth;
    private static int glassHeight;
    private static long lastRefresh;
    private static long lastSignature;
    /** 首帧真正画出玻璃时记一条日志，方便判断通道有没有生效 */
    private static boolean loggedFirstDraw;

    private GlassRenderer() {
    }

    /** 在 {@code processBlurEffect} 头部调用；返回 {@code true} 表示已接管，原版模糊应取消。 */
    public static boolean renderNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return false;
        }
        Screen screen = mc.gui.screen();
        if (!(screen instanceof AiConfigScreen) && !(screen instanceof OnboardingScreen)) {
            return false;
        }
        if (GlassLayer.count() <= 0 || !GlassLayer.usable()) {
            return false;
        }
        try {
            return draw(mc);
        } catch (Throwable t) {
            // 一次失败就永久关掉这条通道：宁可回到普通深色面板，也不能让界面空白 / 崩溃
            GlassPipelines.disable();
            AiPlayerMod.LOGGER.warn("[aiplayer] 液态玻璃通道异常，已回退普通面板", t);
            return false;
        }
    }

    private static boolean draw(Minecraft mc) {
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        int w = target.width;
        int h = target.height;
        if (w <= 0 || h <= 0) {
            return false;
        }
        ensureBackdrop(w, h, target.getColorTexture().getFormat());
        ensureGlass(w, h, target.getColorTexture());

        // 玻璃的重活（拷背景 + 全屏 SDF + 9 点磨砂）只按设定帧率重画离屏纹理，
        // 中间的帧只跑一次"采样一张纹理"的 blit，开销可以忽略。
        int fps = ClientThemeConfig.get().glassFps;
        long interval = fps > 0 ? 1_000_000_000L / fps : 0L;
        long now = System.nanoTime();
        long sig = signature();
        if (lastRefresh == 0L || sig != lastSignature || now - lastRefresh >= interval) {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(target.getColorTexture(), backdropTexture, 0, 0, 0, 0, 0, w, h);
            upload(mc, w, h);
            try (RenderPass pass = encoder.createRenderPass(() -> "aiplayer liquid glass",
                    glassView, Optional.of(new org.joml.Vector4f(0f, 0f, 0f, 0f)), null,
                    OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(GlassPipelines.pipeline()));
                pass.setUniform("GlassInfo", ensureUniform());
                pass.setUniform("Sampler0", backdropView, sampler());
                pass.setVertexBuffer(0, ensureQuad().slice());
                RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                pass.setIndexBuffer(indices.getBuffer(6), indices.type());
                pass.drawIndexed(6, 1, 0, 0, 0);
            }
            encoder.submit();
            lastRefresh = now;
            lastSignature = sig;
            if (!loggedFirstDraw) {
                loggedFirstDraw = true;
                AiPlayerMod.LOGGER.info("[aiplayer] 液态玻璃首帧绘制：{} 块，{}×{}，刷新 {}fps",
                        GlassLayer.count(), w, h, fps);
            }
        }

        // 把离屏玻璃图贴回主画面
        CommandEncoder out = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = out.createRenderPass(() -> "aiplayer liquid glass blit",
                target.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(GlassPipelines.blitPipeline()));
            pass.setUniform("Sampler0", glassView, sampler());
            pass.setVertexBuffer(0, ensureQuad().slice());
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            pass.setIndexBuffer(indices.getBuffer(6), indices.type());
            pass.drawIndexed(6, 1, 0, 0, 0);
        }
        out.submit();
        return true;
    }

    private static com.mojang.renderpearl.api.textures.GpuSampler sampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    /** 形状 / 颜色 / 效果有没有变：变了就立刻重画，不然最多等 1/25 秒 */
    private static long signature() {
        int n = GlassLayer.count();
        float[] rect = GlassLayer.rect();
        float[] top = GlassLayer.top();
        float[] bottom = GlassLayer.bottom();
        float[] radii = GlassLayer.radii();
        float[] fx = GlassLayer.fx();
        long h = n;
        int len = n * 4;
        for (int i = 0; i < len; i++) {
            h = h * 31 + Float.floatToIntBits(rect[i]);
            h = h * 31 + Float.floatToIntBits(top[i]);
            h = h * 31 + Float.floatToIntBits(bottom[i]);
            h = h * 31 + Float.floatToIntBits(radii[i]);
            h = h * 31 + Float.floatToIntBits(fx[i]);
        }
        return h;
    }

    /** 离屏玻璃纹理：重活画在这里，之后每帧原样贴回主画面 */
    private static void ensureGlass(int w, int h, GpuTexture mainColor) {
        if (glassTexture != null && glassWidth == w && glassHeight == h) {
            return;
        }
        if (glassView != null) {
            glassView.close();
            glassView = null;
        }
        if (glassTexture != null) {
            glassTexture.close();
        }
        glassTexture = RenderSystem.getDevice().createTexture(
                "aiplayer glass offscreen", 8 | 4, mainColor.getFormat(), w, h, 1, 1);
        glassView = RenderSystem.getDevice().createTextureView(glassTexture);
        glassWidth = w;
        glassHeight = h;
        lastRefresh = 0L;
    }

    private static void upload(Minecraft mc, int fbW, int fbH) {
        float guiScale = (float) mc.getWindow().getGuiScale();
        float time = (System.currentTimeMillis() % 100000L) / 1000f;
        boolean blur = ClientThemeConfig.get().blurBackground;
        int n = GlassLayer.count();
        float[] rect = GlassLayer.rect();
        float[] top = GlassLayer.top();
        float[] bottom = GlassLayer.bottom();
        float[] radii = GlassLayer.radii();
        float[] fx = GlassLayer.fx();
        try (var map = ensureUniform().map(false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(map.data());
            b.putVec4(fbW, fbH, guiScale, time);
            b.putVec4(n, 0f, 0f, 0f);
            // 矩形按 GUI 缩放换算成帧缓冲像素，并把 y 翻成"左下原点"（与 gl_FragCoord 一致）
            for (int i = 0; i < MAX; i++) {
                int o = i * 4;
                float x1 = rect[o] * guiScale;
                float y1 = rect[o + 1] * guiScale;
                float x2 = rect[o + 2] * guiScale;
                float y2 = rect[o + 3] * guiScale;
                b.putVec4(x1, fbH - y2, x2, fbH - y1);
            }
            for (int i = 0; i < MAX; i++) {
                int o = i * 4;
                b.putVec4(top[o], top[o + 1], top[o + 2], top[o + 3]);
            }
            for (int i = 0; i < MAX; i++) {
                int o = i * 4;
                b.putVec4(bottom[o], bottom[o + 1], bottom[o + 2], bottom[o + 3]);
            }
            // 半径顺序翻转成 (左下, 右下, 右上, 左上)
            for (int i = 0; i < MAX; i++) {
                int o = i * 4;
                b.putVec4(radii[o + 3] * guiScale, radii[o + 2] * guiScale,
                        radii[o + 1] * guiScale, radii[o] * guiScale);
            }
            for (int i = 0; i < MAX; i++) {
                int o = i * 4;
                b.putVec4(fx[o], fx[o + 1], blur ? fx[o + 2] * guiScale : 0f, fx[o + 3]);
            }
        }
    }

    private static GpuBuffer ensureUniform() {
        if (uniformBuffer == null) {
            uniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "aiplayer glass uniforms", 130, UNIFORM_SIZE);
        }
        return uniformBuffer;
    }

    private static GpuBuffer ensureQuad() {
        if (quadBuffer == null) {
            ByteBuffer data = ByteBuffer.allocateDirect(4 * 3 * Float.BYTES).order(ByteOrder.nativeOrder());
            // QUADS 的绕序：(左上) (右上) (右下) (左下)
            put(data, 0f, 0f);
            put(data, 1f, 0f);
            put(data, 1f, 1f);
            put(data, 0f, 1f);
            data.flip();
            quadBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "aiplayer glass quad",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST,
                    data);
        }
        return quadBuffer;
    }

    private static void put(ByteBuffer data, float x, float y) {
        data.putFloat(x).putFloat(y).putFloat(0f);
    }

    private static void ensureBackdrop(int w, int h, com.mojang.renderpearl.api.GpuFormat format) {
        if (backdropTexture != null && backdropWidth == w && backdropHeight == h
                && backdropTexture.getFormat() == format) {
            return;
        }
        if (backdropView != null) {
            backdropView.close();
            backdropView = null;
        }
        if (backdropTexture != null) {
            backdropTexture.close();
            backdropTexture = null;
        }
        backdropTexture = RenderSystem.getDevice().createTexture(
                "aiplayer glass backdrop",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                format, w, h, 1, 1);
        backdropView = RenderSystem.getDevice().createTextureView(backdropTexture);
        backdropWidth = w;
        backdropHeight = h;
    }
}
