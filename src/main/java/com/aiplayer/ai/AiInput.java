package com.aiplayer.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;

/**
 * AI 的“按键输入”。每 tick 由 {@code AiBrain} 填写，由 {@code AiPlayer} 应用到实体上。
 * 只包含真实玩家能做出来的输入：forward / backward / left / right / jump / sneak / sprint / yaw / pitch。
 */
public final class AiInput {
    public boolean forward;
    public boolean backward;
    public boolean left;
    public boolean right;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;

    public float yaw;
    public float pitch;

    /** 持续按住左键挖方块 */
    public boolean mining;
    /**
     * 指定要挖的方块位置。非空时 {@code AiPlayer} 直接按它算挖掘进度，
     * 不再每 tick 用视线重新射线选块 —— 否则 AI 一转身就会换块、进度反复清零，
     * 表现就是"一直在点左键却永远挖不掉"。
     */
    public BlockPos mineTarget;
    /** 左键攻击一次（实体） */
    public boolean attackEntity;
    /** 指定攻击目标；非空时 {@code AiPlayer} 直接攻击它（仍然会做距离校验） */
    public Entity attackTarget;
    /** 右键使用 / 放置一次 */
    public boolean useItem;
    /** 一直按住右键（TACZ 全自动枪械就是靠按住开火） */
    public boolean useItemHold;
    /** 松开右键（弓箭蓄力完成） */
    public boolean releaseUse;
    /** 用副手使用物品（举盾） */
    public boolean useOffhand;
    /** 松开副手 */
    public boolean releaseOffhandUse;
    /** 交换主副手 */
    public boolean swapOffhand;
    /** 需要切换快捷栏槽位（-1 表示不切换） */
    public int selectedSlot = -1;

    public void reset() {
        this.forward = false;
        this.backward = false;
        this.left = false;
        this.right = false;
        this.jump = false;
        this.sneak = false;
        this.sprint = false;
    }

    public void clearActions() {
        this.mining = false;
        this.attackEntity = false;
        this.attackTarget = null;
        this.useItem = false;
        this.useItemHold = false;
        this.releaseUse = false;
        this.useOffhand = false;
        this.releaseOffhandUse = false;
        this.swapOffhand = false;
        this.selectedSlot = -1;
    }

    public void resetAll() {
        reset();
        clearActions();
    }

    /** 是否有水平移动意图 */
    public boolean moving() {
        return forward || backward || left || right;
    }

    public Input toVanilla() {
        return new Input(forward, backward, left, right, jump, sneak, sprint);
    }
}