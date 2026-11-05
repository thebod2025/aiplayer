package com.aiplayer.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 彩蛋：名字叫 bodboy 的 AI 玩家头顶会飘一面红色的旗帜。
 * <p>
 * 实现方式不碰实体渲染器——在客户端本地生成一个"掉落物"实体（红底旗帜），
 * 每 tick 把它钉在目标头顶，并关掉重力。这样它自带原版掉落物的自转 + 上下浮动，
 * 看起来就是一面插在头上的旗，而且只在客户端存在：
 * <ul>
 *   <li>服务端完全不知道这个实体，不会同步、不会被拾取、不会影响存档；</li>
 *   <li>退出重进 / 切维度后由本类自动重新生成，目标改名后自动消失。</li>
 * </ul>
 */
public final class BodboyFlag {
    /** 彩蛋触发的名字（不分大小写） */
    private static final String TARGET_NAME = "bodboy";
    /** 旗子离头顶多高（格）：要高于 1.8 高的玩家模型，才不会和头发穿模 */
    private static final double LIFT = 0.85;

    /** 每个目标玩家一个旗子实体，按 UUID 记忆，避免每 tick 生成垃圾实体 */
    private static final Map<UUID, ItemEntity> FLAGS = new HashMap<>();

    private BodboyFlag() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(BodboyFlag::tick);
    }

    private static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            // 离开世界：客户端实体随世界一起没了，把记录也清掉
            FLAGS.clear();
            return;
        }

        Set<UUID> alive = new HashSet<>();
        for (var p : level.players()) {
            if (!TARGET_NAME.equalsIgnoreCase(p.getName().getString())) {
                continue;
            }
            alive.add(p.getUUID());
            placeFlag(level, p);
        }
        // 本机玩家通常不在 players() 里，单独判一次（自己叫 bodboy 也认）
        if (mc.player != null && TARGET_NAME.equalsIgnoreCase(mc.player.getName().getString())) {
            alive.add(mc.player.getUUID());
            placeFlag(level, mc.player);
        }

        Iterator<Map.Entry<UUID, ItemEntity>> it = FLAGS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ItemEntity> e = it.next();
            if (!alive.contains(e.getKey()) || e.getValue().isRemoved()) {
                e.getValue().discard();
                it.remove();
            }
        }
    }

    /** 生成（首次）或摆正旗子：始终钉在目标头顶 */
    private static void placeFlag(ClientLevel level, net.minecraft.world.entity.player.Player owner) {
        double y = owner.getY() + owner.getBbHeight() + LIFT;
        ItemEntity flag = FLAGS.get(owner.getUUID());
        if (flag == null || flag.isRemoved()) {
            flag = new ItemEntity(level, owner.getX(), y, owner.getZ(), new ItemStack(Items.BANNER.red()));
            flag.setNoGravity(true);
            level.addFreshEntity(flag);
            FLAGS.put(owner.getUUID(), flag);
        }
        flag.setPos(owner.getX(), y, owner.getZ());
        flag.setDeltaMovement(Vec3.ZERO);
    }
}
