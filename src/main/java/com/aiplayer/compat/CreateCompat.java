package com.aiplayer.compat;

import com.aiplayer.entity.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 机械动力（Create）兼容：
 * <ul>
 *   <li>识别齿轮 / 传动杆 / 发动机 / 储罐 / 管道等机械部件</li>
 *   <li>保护正在运行的机械结构（不挖掉别人的机器）</li>
 *   <li>允许使用它们的物品（手持右键正常交互）</li>
 * </ul>
 */
public class CreateCompat implements ModCompatibility {
    public static final CreateCompat INSTANCE = new CreateCompat();

    /** 这些方块名代表“机械结构”，默认不破坏 */
    private static final String[] MACHINE_KEYWORDS = {
            "Shaft", "Cogwheel", "Gearbox", "Belt", "Chassis", "Contraption", "Bearing",
            "Piston", "Pump", "Mixer", "Press", "Saw", "Drill", "Fan", "Tank", "Pipe",
            "Valve", "Boiler", "Engine", "Motor", "Generator", "Clutch", "Gearshift",
            "Funnel", "Chute", "Depot", "Arm", "Crane", "Elevator", "Pulley", "Winch"
    };

    @Override
    public String modId() {
        return "create";
    }

    @Override
    public boolean isModItem(ItemStack item) {
        if (item.isEmpty()) {
            return false;
        }
        return isCreateId(item.getItem().getDescriptionId())
                || looksLikeMachine(item.getItem().getClass().getSimpleName());
    }

    @Override
    public void useModItem(AiPlayer player, ItemStack item) {
        // 机械动力的物品基本都是正常右键使用
        player.input().useItem = true;
    }

    @Override
    public boolean canInteractWith(BlockState block) {
        return true;
    }

    @Override
    public void interactWithBlock(AiPlayer player, BlockPos pos) {
        player.input().useItem = true;
    }

    @Override
    public boolean isProtected(BlockState block) {
        if (block.isAir()) {
            return false;
        }
        return isCreateId(block.getBlock().getDescriptionId())
                && looksLikeMachine(block.getBlock().getClass().getSimpleName());
    }

    /** 注册名是否属于 create 命名空间 */
    private static boolean isCreateId(String descriptionId) {
        return descriptionId != null && descriptionId.contains("create");
    }

    /** 类名是否像机械结构（和 isModItem / isProtected 共用同一套关键字） */
    private static boolean looksLikeMachine(String className) {
        if (className == null) {
            return false;
        }
        for (String keyword : MACHINE_KEYWORDS) {
            if (className.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}