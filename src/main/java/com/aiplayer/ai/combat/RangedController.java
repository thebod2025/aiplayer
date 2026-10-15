package com.aiplayer.ai.combat;

import com.aiplayer.ai.AiInput;
import com.aiplayer.ai.AiLevel;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InvUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

/**
 * 远程武器：弓 / 弩 / 三叉戟（投掷）/ 枪械（交给兼容层）。
 * <p>
 * 弓弩的蓄力与发射完全走原版 {@code useItem} / {@code releaseUsingItem}，
 * 因此蓄力时间、抛物线、弹药消耗都和真人一致。
 * <p>
 * 瞄准做了“平滑 + 低抖动频率”处理：真人瞄准是连续的，抖动不应该每 tick 都随机，
 * 而是每秒几次微调，而且强度 4（挂级）直接零抖动。
 */
public class RangedController {
    private static final Random RNG = new Random();
    /** 弓箭满蓄力出膛速度（格/tick） */
    private static final double ARROW_SPEED = 3.0D;
    private static final double CROSSBOW_SPEED = 3.15D;
    private static final double GRAVITY = 0.05D;
    /** 投掷物（雪球 / 鸡蛋 / 喷溅药水）出膛速度（格/tick） */
    private static final double THROW_SPEED = 1.5D;

    private final AiPlayer player;
    private int chargeTicks;
    private int cooldownTicks;

    /** 平滑后的实际瞄准角 */
    private float aimYaw;
    private float aimPitch;
    private boolean aimInit;
    /** 抖动：每隔几 tick 才重新随机一次，而不是每 tick 都抖 */
    private int jitterTicks;
    private float jitterYaw;
    private float jitterPitch;

    public RangedController(AiPlayer player) {
        this.player = player;
    }

    public void reset() {
        this.chargeTicks = 0;
        this.aimInit = false;
        this.jitterTicks = 0;
    }

    /** 手上/背包里有没有远程手段（弓 / 三叉戟 / 投掷物） */
    public boolean hasRanged() {
        // 弩的类名是 CrossbowItem，不含 "Bow"，必须单独查一次
        return findItem("Bow") >= 0 || findItem("Crossbow") >= 0
                || findItem("Trident") >= 0 || findThrowable() >= 0;
    }

    /**
     * 可以当远程武器扔的东西（雪球 / 鸡蛋 / 喷溅药水）。
     * <p>
     * 不含末影珍珠：珍珠只用于逃生（扔出去是瞬移，用来打人风险太大）。
     */
    private int findThrowable() {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() == Items.SNOWBALL || stack.getItem() == Items.EGG) {
                return i;
            }
            String name = stack.getItem().getClass().getSimpleName();
            if (name.contains("SplashPotion") || name.contains("LingeringPotion")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return true 表示正在使用远程武器（输入已被接管）
     */
    public boolean tick(Entity target) {
        AiInput in = this.player.input();
        if (target == null || !target.isAlive()) {
            stopDrawing();
            return false;
        }
        double dist = Math.sqrt(this.player.distanceToSqr(target));
        if (dist < 4.0D || dist > 40.0D) {
            stopDrawing();
            return false;
        }
        if (this.player.profile() == null) {
            return false;
        }
        AiLevel level = this.player.profile().level();

        int bowSlot = findItem("Bow");
        if (bowSlot < 0) {
            // 弩（CrossbowItem）类名不含 "Bow"，单查 "Bow" 会漏掉弩，导致有弩也不会远程
            bowSlot = findItem("Crossbow");
        }
        int tridentSlot = findItem("Trident");
        if (bowSlot < 0 && tridentSlot < 0) {
            // 没有弓 / 三叉戟：有投掷物就扔投掷物（雪球、鸡蛋、喷溅药水）
            return tickThrowable(target, dist, level);
        }
        boolean useBow = bowSlot >= 0;
        boolean crossbow = useBow
                && this.player.getInventory().getItem(bowSlot).getItem().getClass().getSimpleName().contains("Crossbow");
        int slot = useBow ? bowSlot : tridentSlot;
        if (this.player.isUsingItem() && this.player.getInventory().getSelectedSlot() != slot) {
            this.player.stopUsingItem();
        }
        if (!this.player.selectSlot("ranged", com.aiplayer.util.InventoryLock.PRIO_COMBAT, slot)) {
            return false;
        }

        // 瞄准（抛物线 + 平滑）
        double speed = crossbow ? CROSSBOW_SPEED : ARROW_SPEED;
        aimBallistic(target, dist, speed, level);

        // 弹药检查
        if (useBow && !crossbow && !this.player.gameMode.isCreative()
                && !InvUtil.has(this.player, Items.ARROW)) {
            return false;
        }

        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
            return true;
        }
        if (!this.player.isUsingItem()) {
            in.useItem = true;
            this.chargeTicks = 0;
            return true;
        }
        this.chargeTicks++;
        int needed = crossbow ? 25 : 20;
        // 强度越高越接近满蓄力才放
        int releaseAt = Math.max(12, (int) (needed * (1.0F - level.id * 0.02F)));
        if (this.chargeTicks >= releaseAt) {
            in.releaseUse = true;
            this.chargeTicks = 0;
            this.cooldownTicks = crossbow ? 25 : 10 + RNG.nextInt(8);
        }
        return true;
    }

    /**
     * 由外部（AiBrain）在“当前没有敌人 / 不该远程”时调用：立刻松手，别再拉着弓。
     * <p>
     * 之前战斗中目标死亡后，战斗分支直接 return，没人去松开右键，AI 会把弓一直拉着、
     * 甚至把已经蓄好的那一箭射出去 —— 表现为“僵尸都烧死了还在射箭”。
     */
    public void release() {
        stopDrawing();
    }

    private void stopDrawing() {
        if (this.player.isUsingItem()) {
            this.player.stopUsingItem();
        }
        this.chargeTicks = 0;
        this.aimInit = false;
    }

    /**
     * 没有弓 / 三叉戟时用投掷物：雪球、鸡蛋、喷溅药水。
     * 投掷物不需要蓄力，右键一次就出手，节奏全靠冷却控制。
     */
    private boolean tickThrowable(Entity target, double dist, AiLevel level) {
        int slot = findThrowable();
        if (slot < 0) {
            return false;
        }
        if (!this.player.selectSlot("ranged", com.aiplayer.util.InventoryLock.PRIO_COMBAT, slot)) {
            return false;
        }
        aimBallistic(target, dist, THROW_SPEED, level);
        AiInput in = this.player.input();
        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
            return true;
        }
        in.useItem = true;
        // 强度越高扔得越快
        this.cooldownTicks = Math.max(3, 14 - level.id * 3) + RNG.nextInt(6);
        return true;
    }

    private int findItem(String keyword) {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem().getClass().getSimpleName().contains(keyword)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 抛物线解算 + 拟人化瞄准：
     * <ul>
     *   <li>先算理论角度；</li>
     *   <li>低强度叠加误差，但误差每 6~10 tick 才重新随机一次（不是每 tick 都抖）；</li>
     *   <li>强度 4 零误差；</li>
     *   <li>用插值把“当前角度”平滑推到目标角度，转身速度受强度限制，看起来像真人甩枪。</li>
     * </ul>
     */
    private void aimBallistic(Entity target, double dist, double speed, AiLevel level) {
        Vec3 eye = this.player.getEyePosition();
        double dx = target.getX() - eye.x;
        double dz = target.getZ() - eye.z;
        double horizontal = Math.max(0.001D, Math.sqrt(dx * dx + dz * dz));
        double dy = (target.getY() + target.getBbHeight() * 0.6D) - eye.y;
        // 预判移动
        Vec3 motion = target.getDeltaMovement();
        double flight = horizontal / speed;
        dx += motion.x * flight;
        dz += motion.z * flight;
        dy += motion.y * flight * 0.4D;
        horizontal = Math.max(0.001D, Math.sqrt(dx * dx + dz * dz));

        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        double v2 = speed * speed;
        double disc = v2 * v2 - GRAVITY * (GRAVITY * horizontal * horizontal + 2.0D * dy * v2);
        float pitch;
        if (disc >= 0.0D && horizontal > 1.5D) {
            pitch = (float) -Math.toDegrees(Math.atan((v2 - Math.sqrt(disc)) / (GRAVITY * horizontal)));
        } else {
            pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        }

        // 误差：强度 4 完全不抖；其它强度每 6~10 tick 才换一次偏差
        float err = level.id >= 4 ? 0.0F : level.aimErrorDeg * 0.6F;
        if (--this.jitterTicks <= 0) {
            this.jitterTicks = level.id >= 3 ? 10 : 6;
            this.jitterYaw = (RNG.nextFloat() * 2.0F - 1.0F) * err;
            this.jitterPitch = (RNG.nextFloat() * 2.0F - 1.0F) * err * 0.7F;
        }
        float targetYaw = yaw + this.jitterYaw;
        float targetPitch = pitch + this.jitterPitch;

        AiInput in = this.player.input();
        if (!this.aimInit) {
            this.aimYaw = targetYaw;
            this.aimPitch = targetPitch;
            this.aimInit = true;
        }
        // 平滑跟随：每 tick 最多转 maxTurn 度；强度越低转身越慢（真人甩枪也需要时间）
        float maxTurn = 6.0F + level.id * 8.0F;
        this.aimYaw = approachYaw(this.aimYaw, targetYaw, maxTurn);
        this.aimPitch = approach(this.aimPitch, targetPitch, maxTurn);
        in.yaw = this.aimYaw;
        in.pitch = Math.max(-90.0F, Math.min(90.0F, this.aimPitch));
    }

    private static float approach(float current, float target, float maxDelta) {
        float delta = target - current;
        if (delta > maxDelta) {
            delta = maxDelta;
        } else if (delta < -maxDelta) {
            delta = -maxDelta;
        }
        return current + delta;
    }

    private static float approachYaw(float current, float target, float maxDelta) {
        float delta = approach(0.0F, AiPlayer.wrapDegrees(target - current), maxDelta);
        return AiPlayer.wrapDegrees(current + delta);
    }

    /** 附近飞来的箭 / 三叉戟 / 投掷物（用于举盾格挡与逃生判定） */
    public Projectile incomingProjectile(double range) {
        AABB box = this.player.getBoundingBox().inflate(range);
        List<Projectile> list = this.player.level().getEntitiesOfClass(Projectile.class, box);
        Projectile best = null;
        double bestDist = Double.MAX_VALUE;
        for (Projectile p : list) {
            if (p.getOwner() == this.player) {
                continue;
            }
            boolean hostile = p instanceof AbstractArrow || p instanceof ThrowableItemProjectile;
            if (!hostile) {
                continue;
            }
            // 只关心朝自己飞来的（速度方向与"弹体->玩家"方向一致）
            Vec3 toPlayer = this.player.position().subtract(p.position());
            if (toPlayer.lengthSqr() > 1.0E-4D) {
                Vec3 v = p.getDeltaMovement();
                if (v.lengthSqr() > 1.0E-6D && v.normalize().dot(toPlayer.normalize()) < 0.2D) {
                    continue;
                }
            }
            double d = this.player.distanceToSqr(p);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }
}