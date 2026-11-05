package com.aiplayer.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * 修 Player Animation Library（PAL）的崩溃：
 * <pre>
 * NullPointerException: Cannot invoke "AnimationData.getPartialTick()" because "this.data" is null
 *   at AdjustmentModifier.get3DTransform(AdjustmentModifier.java:173)
 * </pre>
 * PAL 的 {@code AdjustmentModifier.data} 只在 {@code setupAnim} / {@code tick} 时赋值；
 * 渲染时机早于第一次 tick（AI 玩家刚被生成、或动画栈里没有控制器）时它就是 null，
 * 而 {@code get3DTransform} 直接解引用 → 渲染线程崩溃，整个客户端退出。
 * <p>
 * 这里在方法入口发现 data 为 null 就直接跳过这一段骨骼调整 —— 失去的只是"模组动画的微调"，
 * 角色会退回原版动作，但不会崩游戏。
 * <p>
 * 目标类在第三方模组里，编译期不存在，所以用 {@code targets} 字符串 + 反射读字段，
 * 不引任何 PAL 类型；PAL 没装时由 {@link AiPlayerMixinPlugin} 直接跳过本 mixin。
 */
@Mixin(targets = "com.zigythebird.playeranimcore.animation.layered.modifier.AdjustmentModifier")
public class PalAdjustmentGuardMixin {
    private static Field aiplayer$dataField;

    @Inject(method = "get3DTransform", at = @At("HEAD"), cancellable = true)
    private void aiplayer$skipWhenNoAnimationData(CallbackInfo ci) {
        try {
            if (aiplayer$dataField == null) {
                Field f = this.getClass().getDeclaredField("data");
                f.setAccessible(true);
                aiplayer$dataField = f;
            }
            if (aiplayer$dataField.get(this) == null) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 反射失败（PAL 改了字段名）：放行原逻辑，行为和没装本 mixin 一样
        }
    }
}
