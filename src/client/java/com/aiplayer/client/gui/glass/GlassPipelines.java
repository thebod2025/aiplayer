package com.aiplayer.client.gui.glass;

import com.aiplayer.AiPlayerMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * 液态玻璃通道的渲染管线：一张全屏四边形 + 自己的顶点 / 片元着色器。
 * <p>
 * 与 {@link com.aiplayer.client.gui.gpu.RoundedPipelines} 最大的区别是：
 * 这里不接进原版 GUI 的批处理，而是在 GUI 的模糊阶段单独开一个 RenderPass 直接画到主画面
 * （ReGlass 就是这么做的）。所以阴影、圆角 SDF、背景磨砂都在同一次绘制里完成，
 * 不需要依赖 GUI 的顶点排序，也不会被界面缩放影响。
 * <p>
 * 绑定：`Sampler0` 是模糊前的画面副本，`GlassInfo` 是控件矩形 / 颜色 / 效果的结构化 uniform。
 * 顶点格式只有 POSITION（全屏四边形的四个角），拓扑 QUADS。
 */
public final class GlassPipelines {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("aiplayer", "core/glass");
    private static final Identifier BLIT_SHADER = Identifier.fromNamespaceAndPath("aiplayer", "core/blit");

    private static RenderPipeline pipeline;
    /** 把离屏玻璃图贴回主画面用的廉价管线（只采一次纹理） */
    private static RenderPipeline blit;
    private static boolean registered;
    private static boolean probed;
    private static volatile boolean available;

    private GlassPipelines() {
    }

    /** 在客户端初始化时调用：构建并注册管线（不编译，第一次真正使用时才编译）。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            RenderPipeline.Builder b = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("aiplayer", "pipeline/glass"))
                    .withVertexShader(SHADER)
                    .withFragmentShader(SHADER)
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withUniform("GlassInfo", UniformType.UNIFORM_BUFFER)
                            .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                            .build())
                    .withVertexBinding(0, DefaultVertexFormat.POSITION)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    // 离屏通道不混合：把最终像素原样写进玻璃纹理，混合留到 blit 那一步做一次，
                    // 否则半透明边缘会被混合两次、发灰发暗。
                    .withColorTargetState(ColorTargetState.DEFAULT);
            pipeline = b.build();
            RenderPipelines.registerOptional(pipeline);
            blit = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("aiplayer", "pipeline/glass_blit"))
                    .withVertexShader(SHADER)
                    .withFragmentShader(BLIT_SHADER)
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                            .build())
                    .withVertexBinding(0, DefaultVertexFormat.POSITION)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withColorTargetState(new ColorTargetState(
                            com.mojang.renderpearl.api.pipeline.BlendFunction.TRANSLUCENT))
                    .build();
            RenderPipelines.registerOptional(blit);
            AiPlayerMod.LOGGER.info("[aiplayer] 液态玻璃管线已注册");
        } catch (Throwable t) {
            pipeline = null;
            AiPlayerMod.LOGGER.warn("[aiplayer] 液态玻璃管线注册失败，界面保持原样", t);
        }
    }

    /**
     * 这条管线在这一帧真的能画吗。
     * <p>
     * 编译放在第一次真正绘制时（必须在渲染线程、设备就绪之后），失败就永久回退，
     * 界面会退回原来的深色面板，不会空白也不会崩。
     */
    public static boolean available() {
        if (pipeline == null) {
            return false;
        }
        if (!probed) {
            probed = true;
            try {
                available = RenderSystem.getCompiledPipeline(pipeline) != null
                        && RenderSystem.getCompiledPipeline(blit) != null;
            } catch (Throwable t) {
                available = false;
                AiPlayerMod.LOGGER.warn("[aiplayer] 液态玻璃着色器编译失败，回退普通面板", t);
            }
            if (available) {
                AiPlayerMod.LOGGER.info("[aiplayer] 液态玻璃={}", "启用");
            }
        }
        return available;
    }

    /** 绘制期发现通道出错时调用：本进程内不再尝试，界面立刻回到普通绘制。 */
    public static void disable() {
        available = false;
    }

    public static RenderPipeline pipeline() {
        return pipeline;
    }

    public static RenderPipeline blitPipeline() {
        return blit;
    }
}
