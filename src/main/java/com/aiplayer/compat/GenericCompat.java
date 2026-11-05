package com.aiplayer.compat;

import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.ItemIdUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 通用软兼容层：不针对具体模组写死，任何非 {@code minecraft:} 命名空间的物品都算“模组物品”。
 * <p>
 * 目的不是替每个模组实现全部玩法，而是保证 AI 拿到模组物品时不会把它当垃圾：
 * 能识别、能右键使用、能和模组方块交互，并且默认不把未知方块当成受保护方块。
 * 已知模组（tacz / create）仍由各自的兼容类先处理，这里只兜底。
 */
public class GenericCompat implements ModCompatibility {
    public static final GenericCompat INSTANCE = new GenericCompat();

    @Override
    public String modId() {
        return "generic";
    }

    @Override
    public boolean isModItem(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        String id = ItemIdUtil.idOf(item.getItem());
        int colon = id.indexOf(':');
        return colon > 0 && !"minecraft".equals(id.substring(0, colon));
    }

    @Override
    public void useModItem(AiPlayer player, ItemStack item) {
        // 绝大多数模组物品的“使用”就是右键：直接走物品自己的 use 逻辑，
        // 不额外猜它是什么（枪械开火仍由 TaczCompat 单独接管）。
        if (item != null && !item.isEmpty()) {
            item.use(player.level(), player, InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public boolean canInteractWith(BlockState block) {
        return true;
    }

    @Override
    public void interactWithBlock(AiPlayer player, BlockPos pos) {
        // 通用交互不额外处理：由上层现有的右键/使用逻辑完成
    }
}
