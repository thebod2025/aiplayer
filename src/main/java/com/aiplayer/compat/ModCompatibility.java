package com.aiplayer.compat;

import com.aiplayer.entity.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 模组兼容层抽象接口。所有实现都用“物品/方块的注册名 + 反射”来识别，
 * 所以即使没装对应模组也能正常编译运行。
 */
public interface ModCompatibility {
    /** 模组 id（例如 tacz / create） */
    String modId();

    /** 是不是这个模组的物品（枪、齿轮等） */
    boolean isModItem(ItemStack item);

    /** 用这个模组物品（枪械开火等） */
    void useModItem(AiPlayer player, ItemStack item);

    /** 能不能和这个方块交互（机械动力的运行中机械不应该被挖掉） */
    boolean canInteractWith(BlockState block);

    /** 和方块交互（开 GUI 等） */
    void interactWithBlock(AiPlayer player, BlockPos pos);

    /** 这个方块是否受保护（不允许破坏） */
    default boolean isProtected(BlockState block) {
        return false;
    }
}