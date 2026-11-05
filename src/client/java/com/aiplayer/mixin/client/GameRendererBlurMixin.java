package com.aiplayer.mixin.client;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.client.gui.glass.GlassRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 GUI 的模糊分层处接管绘制。
 * <p>
 * GUI 渲染器画完"模糊前"的元素（世界 + 遮罩）后会调用 {@code processBlurEffect()}，
 * 原版在这里跑全屏模糊。配置界面 / 新手教程打开时我们把它换成液态玻璃通道：
 * 拷贝当前画面做背景，再用一个全屏四边形按圆角 SDF 画出所有面板。
 * <p>
 * 返回 {@code false}（没接管）时原版模糊照常执行，其它界面完全不受影响。
 */
@Mixin(GameRenderer.class)
public class GameRendererBlurMixin {
    @Inject(method = "processBlurEffect", at = @At("HEAD"), cancellable = true)
    private void aiplayer$liquidGlass(CallbackInfo ci) {
        try {
            if (GlassRenderer.renderNow()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            // 玻璃通道出问题只回退到普通面板，绝不让界面崩掉
            AiPlayerMod.LOGGER.warn("[aiplayer] 液态玻璃绘制失败，本帧回退原版模糊", t);
        }
    }
}
