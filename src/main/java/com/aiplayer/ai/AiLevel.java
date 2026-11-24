package com.aiplayer.ai;

import com.aiplayer.ai.bridge.BridgeMode;

/**
 * AI 强度分级。
 * 1 菜鸟 / 2 普通 / 3 高手 / 4 类似外挂。
 * 所有战斗、瞄准、搭路、失误率参数都在这里集中定义。
 */
public enum AiLevel {
    NOOB(1, "菜鸟", 1100, 24.0F, 0.00F, 0.00F, 0.00F, 0.00F, false, BridgeMode.SNEAK, 0.40F),
    NORMAL(2, "普通", 560, 11.0F, 0.28F, 0.24F, 0.45F, 0.22F, false, BridgeMode.SNEAK, 0.18F),
    PRO(3, "高手", 220, 4.2F, 0.72F, 0.68F, 0.84F, 0.78F, true, BridgeMode.GOD, 0.06F),
    HACKER(4, "挂级", 50, 0.5F, 1.00F, 1.00F, 1.00F, 1.00F, true, BridgeMode.TELLY, 0.00F);

    /** 强度编号 1-4 */
    public final int id;
    /** 显示名 */
    public final String display;
    /** 反应延迟（毫秒） */
    public final int reactionMs;
    /** 瞄准误差（角度，越小越准） */
    public final float aimErrorDeg;
    /** W-tap 使用概率 0-1 */
    public final float wTapChance;
    /** Strafing 水平 0-1 */
    public final float strafeSkill;
    /** 暴击（跳跃攻击）概率 0-1 */
    public final float critChance;
    /** 攻击冷却控制水平 0-1（1 = 每次都等满冷却） */
    public final float cooldownSkill;
    /** 是否会水晶 PvP */
    public final boolean crystalPvp;
    /** 最擅长的搭路方式 */
    public final BridgeMode bestBridge;
    /** 失误率 0-1 */
    public final float mistakeChance;

    AiLevel(int id, String display, int reactionMs, float aimErrorDeg, float wTapChance, float strafeSkill,
            float critChance, float cooldownSkill, boolean crystalPvp, BridgeMode bestBridge, float mistakeChance) {
        this.id = id;
        this.display = display;
        this.reactionMs = reactionMs;
        this.aimErrorDeg = aimErrorDeg;
        this.wTapChance = wTapChance;
        this.strafeSkill = strafeSkill;
        this.critChance = critChance;
        this.cooldownSkill = cooldownSkill;
        this.crystalPvp = crystalPvp;
        this.bestBridge = bestBridge;
        this.mistakeChance = mistakeChance;
    }

    public static AiLevel byId(int id) {
        for (AiLevel l : values()) {
            if (l.id == id) {
                return l;
            }
        }
        return NORMAL;
    }

    /** 挖方块最小间隔（毫秒） */
    public int mineDelayMs() {
        return switch (this) {
            case NOOB -> 1600;
            case NORMAL -> 850;
            case PRO -> 400;
            case HACKER -> 150;
        };
    }

    /** 放置方块最小间隔（毫秒） */
    public int buildDelayMs() {
        return switch (this) {
            case NOOB -> 600;
            case NORMAL -> 320;
            case PRO -> 150;
            case HACKER -> 20;
        };
    }

    /** 攻击最小间隔（毫秒） */
    public int attackDelayMs() {
        return switch (this) {
            case NOOB -> 1400;
            case NORMAL -> 700;
            case PRO -> 380;
            case HACKER -> 200;
        };
    }

    /** 自主执行命令的概率（百分比） */
    public int commandChance() {
        return switch (this) {
            case NOOB -> 5;
            case NORMAL -> 10;
            case PRO -> 80;
            case HACKER -> 100;
        };
    }

    /** 自主说话的概率（百分比）。压低一档，AI 才不会一路碎碎念 */
    public int chatChance() {
        return switch (this) {
            case NOOB -> 1;
            case NORMAL -> 1;
            case PRO -> 2;
            case HACKER -> 2;
        };
    }

    /** 出手所需的最低攻击蓄力（1.5.1：低强度更早出手，高手更等满冷却） */
    public float attackStrengthMin() {
        return 0.55F + cooldownSkill * 0.25F;
    }

    /** 允许使用指定搭路方式吗（低强度不会高级搭路） */
    public boolean canBridge(BridgeMode mode) {
        return mode.id <= bestBridge.id;
    }
}