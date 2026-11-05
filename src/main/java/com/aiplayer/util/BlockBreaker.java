package com.aiplayer.util;

import com.aiplayer.entity.AiPlayer;
import com.aiplayer.zone.ZoneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 模拟“正常玩家挖方块”的过程：先按原版公式计算挖掘耗时，边挖边挥手，
 * 时间到了才真正破坏方块 —— 不瞬破、不违反反作弊。
 */
public final class BlockBreaker {
    private BlockPos target;
    /** 累计挖掘进度（0~1）：按"当前手上工具 + 方块硬度"每 tick 累加 */
    private float progress;

    public BlockBreaker() {
    }

    public static int breakTicks(AiPlayer player, BlockState state, BlockPos pos) {
        float hardness = state.getDestroySpeed(player.level(), pos);
        if (hardness < 0.0F) {
            return -1; // 不可破坏
        }
        boolean correct = player.getMainHandItem().isCorrectToolForDrops(state);
        float speed = player.getMainHandItem().getDestroySpeed(state);
        if (speed <= 0.0F) {
            speed = 1.0F;
        }
        float progressPerTick = correct ? (speed / hardness / 30.0F) : (speed / hardness / 100.0F);
        if (progressPerTick <= 0.0F) {
            return 100;
        }
        return Math.max(1, (int) Math.ceil(1.0F / progressPerTick));
    }

    public BlockPos target() {
        return this.target;
    }

    public boolean isMining() {
        return this.target != null;
    }

    public void reset() {
        this.target = null;
        this.progress = 0.0F;
    }

    /**
     * 每 tick 调用一次。
     *
     * @return true 表示方块刚刚被破坏完成
     */
    public boolean tick(AiPlayer player, BlockPos pos) {
        if (pos == null) {
            reset();
            return false;
        }
        BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) {
            reset();
            return true;
        }
        // 玩家标记的保护区：不拆方块（挖矿 / 砍树 / 搭路挖穿都会走这里，统一在这里拦）
        if (ZoneManager.isProtected(player.level(), pos)) {
            if (this.target != null) {
                player.serverLevel().destroyBlockProgress(player.getId(), this.target, -1);
            }
            reset();
            return false;
        }
        if (!pos.equals(this.target)) {
            // 换了目标：从头开始
            this.target = pos.immutable();
            this.progress = 0.0F;
        }
        int total = breakTicks(player, state, this.target);
        if (total < 0) {
            reset();   // 不可破坏
            return false;
        }
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        // 进度每 tick 都按"当前手上的工具"重算一次，而不是第一次选块时把倒计时焊死。
        // 以前 ticksLeft 只在换目标时算一次：AI 这一 tick 才刚切到镐子（槽位还没生效），
        // 于是整块方块一直按空手的速度挖 —— 表现就是"挖方块都很慢、切了工具也没变快"。
        this.progress += 1.0F / total;
        int stage = (int) (9.0F * Math.min(1.0F, this.progress));
        player.serverLevel().destroyBlockProgress(player.getId(), this.target, Math.max(0, Math.min(9, stage)));
        if (this.progress >= 1.0F) {
            // 时间到：真正破坏方块
            boolean broken = player.gameMode.destroyBlock(this.target);
            player.serverLevel().destroyBlockProgress(player.getId(), this.target, -1);
            reset();
            return broken;
        }
        return false;
    }

    /** 停止挖掘（清理裂纹显示） */
    public void abort(AiPlayer player) {
        if (this.target != null) {
            player.serverLevel().destroyBlockProgress(player.getId(), this.target, -1);
        }
        reset();
    }
}