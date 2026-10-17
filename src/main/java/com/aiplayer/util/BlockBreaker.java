package com.aiplayer.util;

import com.aiplayer.entity.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 模拟“正常玩家挖方块”的过程：先按原版公式计算挖掘耗时，边挖边挥手，
 * 时间到了才真正破坏方块 —— 不瞬破、不违反反作弊。
 */
public final class BlockBreaker {
    private BlockPos target;
    private int ticksLeft;

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
        this.ticksLeft = 0;
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
        if (!pos.equals(this.target)) {
            // 换了目标：按"自己的工具 + 方块硬度"重新算一次挖掘耗时
            this.target = pos.immutable();
            this.ticksLeft = breakTicks(player, state, this.target);
            if (this.ticksLeft < 0) {
                reset();   // 不可破坏
                return false;
            }
        }
        if (this.ticksLeft <= 0) {
            // 时间到：真正破坏方块。
            // 注意：这里必须在"倒计时 == 0"时先破坏，不能在前面再重新初始化 ticksLeft ——
            // 之前就是每次都把 ticksLeft 重新填满，导致方块永远挖不掉、AI 看起来一直在点左键。
            boolean broken = player.gameMode.destroyBlock(this.target);
            player.serverLevel().destroyBlockProgress(player.getId(), this.target, -1);
            reset();
            return broken;
        }
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        int total = Math.max(1, breakTicks(player, state, this.target));
        int stage = (int) (9.0F * (1.0F - (float) this.ticksLeft / (float) total));
        player.serverLevel().destroyBlockProgress(player.getId(), this.target, Math.max(0, Math.min(9, stage)));
        this.ticksLeft--;
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