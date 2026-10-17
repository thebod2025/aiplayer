package com.aiplayer.compat;

import com.aiplayer.entity.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * TACZ（现代枪械）兼容：
 * <ul>
 *   <li>识别枪械 / 弹药 / 配件（按注册名里的 tacz 命名空间）</li>
 *   <li>右键开火（原版 useItem 就会触发枪械的开火逻辑）</li>
 *   <li>R 键装弹（把 swapOffhand 之外由 AI 自己按 R？服务端没有 R 键 —— 用枪械自带的换弹动作）</li>
 *   <li>贴脸切近战</li>
 * </ul>
 */
public class TaczCompat implements ModCompatibility {
    public static final TaczCompat INSTANCE = new TaczCompat();

    /** 贴脸距离：小于这个距离就换成近战武器，不要拿枪怼人 */
    public static final double MELEE_SWITCH_DISTANCE = 2.5D;
    /** 超过这个距离才值得开枪 */
    public static final double MIN_GUN_DISTANCE = 3.0D;

    @Override
    public String modId() {
        return "tacz";
    }

    @Override
    public boolean isModItem(ItemStack item) {
        if (item.isEmpty()) {
            return false;
        }
        String id = item.getItem().getDescriptionId();
        if (id.contains("tacz")) {
            return true;
        }
        String name = item.getItem().getClass().getSimpleName();
        return name.contains("Gun") || name.contains("Ammo") || name.contains("Magazine");
    }

    /** 是不是枪（而不是弹药 / 配件） */
    public boolean isGun(ItemStack item) {
        if (item.isEmpty()) {
            return false;
        }
        String name = item.getItem().getClass().getSimpleName();
        if (name.contains("Ammo") || name.contains("Magazine") || name.contains("Attachment")) {
            return false;
        }
        String id = item.getItem().getDescriptionId();
        return id.contains("tacz") && (name.contains("Gun") || name.contains("ItemGun")
                || name.contains("ModernKineticGun") || extraLooksLikeGun(item));
    }

    private boolean extraLooksLikeGun(ItemStack item) {
        // 兜底：tacz 的枪类通常带 "Gun" 或实现了 IGun
        for (Class<?> c = item.getItem().getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getSimpleName();
            if (n.contains("Gun") || n.contains("Firearm")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void useModItem(AiPlayer player, ItemStack item) {
        // 原版右键会用物品自身的 use 逻辑，TACZ 的枪就是这样开火的。
        // 用 useItemHold（“一直按住右键”）而不是 useItem（单次点击）：
        // 全自动枪需要持续按住，半自动枪也只在冷却结束后才会真正击发，不会出现“每 tick 乱开火”。
        player.input().useItemHold = true;
    }

    @Override
    public boolean canInteractWith(BlockState block) {
        return true;
    }

    @Override
    public void interactWithBlock(AiPlayer player, BlockPos pos) {
        // 不需要特殊处理
    }

    /** 根据距离决定该拿枪还是拿刀 */
    public static boolean shouldUseGun(double distance) {
        return distance > MIN_GUN_DISTANCE;
    }

    public static boolean shouldSwitchToMelee(double distance) {
        return distance < MELEE_SWITCH_DISTANCE;
    }
}