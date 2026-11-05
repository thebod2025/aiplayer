package com.aiplayer.compat;

import com.aiplayer.entity.AiPlayer;
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
        // 放在最后：已知模组先自己认领，剩下的任意模组物品统一由通用层兜住。
        // 这样新增模组不用改这里，也不会抢走 tacz / create 的专门处理。
        COMPAT.add(GenericCompat.INSTANCE);
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

    /**
     * 拦截需要模组特殊处理的指令（目前是 LLM 用 /give 造 TaCZ 枪时补 NBT）。
     *
     * @return true 表示已接管，调用方不要再执行原指令
     */
    public static boolean tryHandleCommand(AiPlayer player, String command) {
        return TaczCompat.tryHandleGunGive(player, command);
    }

    /** 任意模组物品（非 minecraft 命名空间）：交给通用软兼容层，后续按需扩展 */
    public static boolean isAnyModItem(ItemStack stack) {
        return GenericCompat.INSTANCE.isModItem(stack);
    }
}