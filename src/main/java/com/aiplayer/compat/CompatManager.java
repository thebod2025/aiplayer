package com.aiplayer.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** 兼容层注册表：统一查询所有已支持的模组。 */
public final class CompatManager {
    private static final List<ModCompatibility> COMPAT = new ArrayList<>();

    static {
        COMPAT.add(TaczCompat.INSTANCE);
        COMPAT.add(CreateCompat.INSTANCE);
    }

    private CompatManager() {
    }

    public static List<ModCompatibility> all() {
        return COMPAT;
    }

    public static void register(ModCompatibility compat) {
        COMPAT.add(compat);
    }

    /** 这个物品是不是某个模组的特殊物品 */
    public static ModCompatibility forItem(ItemStack stack) {
        for (ModCompatibility compat : COMPAT) {
            if (compat.isModItem(stack)) {
                return compat;
            }
        }
        return null;
    }

    /** 这个方块是否受保护（不允许 AI 破坏） */
    public static boolean isProtected(BlockState state) {
        for (ModCompatibility compat : COMPAT) {
            if (compat.isProtected(state)) {
                return true;
            }
        }
        return false;
    }

    /** 手上是不是枪 */
    public static boolean isGun(ItemStack stack) {
        return TaczCompat.INSTANCE.isGun(stack);
    }
}