package com.aiplayer.util;

/**
 * 快捷栏占用锁。
 * <p>
 * 多个控制器（进食 / 水晶 PvP / 搭路 / 战斗 / 自动装备）都会 {@code setSelectedSlot}，
 * 之前互相抢槽位；这里用一把“抢占式”锁统一仲裁：
 * <ul>
 *   <li>谁先拿到谁用，同一个人重复 acquire 不会失败；</li>
 *   <li>优先级高的可以直接抢占优先级低的（逃生 &gt; 进食 &gt; 水晶 &gt; 搭路 &gt; 战斗 &gt; 装备）；</li>
 *   <li>每 tick 调 {@link #tick()}，持有超过 {@link #MAX_HOLD_TICKS} 自动释放，避免“忘了解锁”造成死锁。</li>
 * </ul>
 */
public final class InventoryLock {
    /** 最长持有时间（tick），防止控制器异常退出导致锁不释放 */
    private static final int MAX_HOLD_TICKS = 40;
    /** 是否允许高优先级抢占低优先级 */
    private static final boolean PREEMPT = true;

    private String owner;
    private int priority = -1;
    private int holdTicks;

    private void take(String who, int prio) {
        this.owner = who;
        this.priority = prio;
        this.holdTicks = 0;
    }

    public boolean acquire(String who, int prio) {
        if (this.owner == null) {
            take(who, prio);
            return true;
        }
        if (this.owner.equals(who)) {
            this.holdTicks = 0;
            return true;
        }
        if (PREEMPT && prio > this.priority) {
            take(who, prio);
            return true;
        }
        return false;
    }

    public void release(String who) {
        if (who != null && who.equals(this.owner)) {
            this.owner = null;
            this.priority = -1;
            this.holdTicks = 0;
        }
    }

    /** 每 tick 调用一次：超时自动释放 */
    public void tick() {
        if (this.owner != null && ++this.holdTicks > MAX_HOLD_TICKS) {
            this.owner = null;
            this.priority = -1;
            this.holdTicks = 0;
        }
    }

    public String owner() {
        return this.owner;
    }

    public boolean heldBy(String who) {
        return who != null && who.equals(this.owner);
    }

    // ---------------- 优先级常量 ----------------
    public static final int PRIO_ESCAPE = 100;
    public static final int PRIO_FOOD = 80;
    public static final int PRIO_CRYSTAL = 70;
    public static final int PRIO_BRIDGE = 60;
    public static final int PRIO_COMBAT = 50;
    public static final int PRIO_TASK = 40;
    public static final int PRIO_EQUIP = 20;
}