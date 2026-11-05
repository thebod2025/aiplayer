package com.aiplayer.client.gui.gpu;

import com.aiplayer.AiPlayerMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * 圆角绘制用的两条自定义渲染管线（填充 / 1px 描边）。
 * <p>
 * 26.3 的 GUI 不再有 {@code RenderSystem.setShader}，一块内容怎么画完全由
 * {@link RenderPipeline} 决定。这里以原版 {@link RenderPipelines#GUI_TEXTURED_SNIPPET}
 * 为底（bind group / 颜色目标 / 深度 / 拓扑 / push constant / **顶点格式** 全部沿用原版），
 * 只换掉顶点与片元着色器 —— 于是"怎么提交、怎么混合"和原版 GUI 完全一致。
 * <p>
 * 顶点格式**必须**与原版一致（Position / UV0 / Color）：GUI 的批处理只认这三件套，
 * 多出来的自定义属性会被静默丢掉。所以尺寸信息不靠顶点传，而是片元用
 * {@code fwidth} 从 UV 的屏幕空间导数反推（见 {@code rounded.fsh}）。
 * <p>
 * 管线构建失败（老驱动 / 资源包改坏原版着色器）时 {@link #available()} 返回 false，
 * 界面自动退回逐行采样的老实现，不会崩也不会空白。
 */
public final class RoundedPipelines {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("aiplayer", "core/rounded");

    private static RenderPipeline fill;
    private static RenderPipeline ring;

    private static boolean registered;
    private static volatile boolean available;
    private static volatile boolean probed;

    private RoundedPipelines() {
    }

    /**
     * 构建并注册管线。幂等；必须在界面第一次绘制之前调用（{@code onInitializeClient}）。
     * 整段包在 try/catch 里：这里失败也不能拖垮启动。
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            fill = build(false);
            ring = build(true);
            RenderPipelines.registerOptional(fill);
            RenderPipelines.registerOptional(ring);
            AiPlayerMod.LOGGER.info("[aiplayer] 圆角管线已注册");
        } catch (Throwable t) {
            fill = null;
            ring = null;
            // 打完整栈：只打 toString 时看不出是哪一步出的问题
            AiPlayerMod.LOGGER.warn("[aiplayer] 圆角管线注册失败，界面回退旧绘制", t);
        }
    }

    private static RenderPipeline build(boolean ringMode) {
        RenderPipeline.Builder b = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("aiplayer",
                        ringMode ? "pipeline/rounded_ring" : "pipeline/rounded"))
                .withVertexShader(SHADER)
                .withFragmentShader(SHADER);
        // 刻意**不**覆盖顶点格式：原版 GUI 的三件套（Position / UV0 / Color）够用，
        // 而自定义的第四个属性在 26.3 的 GUI 批处理里会被丢掉 —— 表现就是整块界面透明。
        if (ringMode) {
            b.withShaderDefine("RING");
        }
        return b.build();
    }

    /**
     * 默认关闭：这条自建管线在 26.3 上能编译、能注册，但提交的角块四边形实测不落像素，
     * 界面会退回逐行采样（带覆盖率抗锯齿的那版）。想继续实验 GPU 路径可以加启动参数
     * {@code -Daiplayer.gpuRounded=true}，此时优先走 GLSL 解析圆弧。
     */
    private static final boolean GPU_ENABLED = Boolean.getBoolean("aiplayer.gpuRounded");

    /** 管线在这一帧真的能画吗（编译不出来就是 false，调用方据此回退） */
    public static boolean available() {
        if (fill == null || !GPU_ENABLED) {
            return false;
        }
        if (!available) {
            try {
                available = RenderSystem.getCompiledPipelineNullable(fill) != null;
            } catch (Throwable t) {
                available = false;
            }
            if (!probed) {
                probed = true;
                // 只打一次：以后看日志就能确认这台机器走的是 GPU 圆角还是旧的逐行采样
                AiPlayerMod.LOGGER.info("[aiplayer] 圆角 GPU 着色器={}", available ? "启用" : "不可用，回退逐行采样");
            }
        }
        return available;
    }

    /** 资源重载 / 换显卡后让可用性重新探测一次 */
    public static void notifyRefresh() {
        available = false;
        probed = false;
    }

    public static RenderPipeline fill() {
        return fill;
    }

    public static RenderPipeline ring() {
        return ring;
    }
}
