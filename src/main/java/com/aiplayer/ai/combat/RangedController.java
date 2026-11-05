package com.aiplayer.ai.combat;

import com.aiplayer.ai.AiInput;
import com.aiplayer.ai.AiLevel;
import com.aiplayer.compat.TaczCompat;
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
    /** 枪弹速度（格/tick）：TaCZ 子弹远快于箭，这里只用于瞄准预判，几乎等同于直线 */
    private static final double GUN_SPEED = 30.0D;

    private final AiPlayer player;
    private int chargeTicks;
    private int cooldownTicks;

    // ---- 枪械（TaCZ）----
    /** 挑中的枪所在槽位；-1 表示手上没有能用的枪 */
    private int gunSlot = -1;
    /** 多久之后重新挑枪（tick）：40 tick 一次，避免每 tick 扫 36 格背包 */
    private int gunRepickTicks;
    /** 背包里有没有枪的缓存（避免每 tick 全背包扫描） */
    private boolean gunAvailable;
    private int gunCheckTicks;
    /** 当前这把枪是否已经拔枪（切枪 / 换枪后要重新拔） */
    private boolean gunDrawn;
    private int gunDrawnSlot = -1;
    /** 连续打不出去多少 tick 就重新拔枪（拔枪动画没走完 / 状态卡住） */
    private int gunIdleTicks;
    /** 弹匣空后重试换弹多少 tick 还没真正进入换弹状态（超时就换一把枪） */
    private int reloadWaitTicks;


    /** 平滑后的实际瞄准角 */
    private float aimYaw;
    private float aimPitch;
    private boolean aimInit;
    /** 抖动：每隔几 tick 才重新随机一次，而不是每 tick 都抖 */
    private int jitterTicks;
    private float jitterYaw;
    private float jitterPitch;
    /** 最近一次扣扳机的 TaCZ 结果（给 /aiplayer gun 诊断用） */
    private String lastGunResult;
    private int lastGunTicks;

    public RangedController(AiPlayer player) {
        this.player = player;
    }

    /** 最近一次射击结果 + 距今多少 tick（排查"不开枪"时看这个） */
    public String lastShot() {
        if (this.lastGunResult == null) {
            return "还没开过枪";
        }
        return this.lastGunResult + "（" + this.lastGunTicks + " tick 前）";
    }

    /** 记录一次射击结果；每 tick 老化一次 */
    public void noteGunResult(String result) {
        this.lastGunResult = result;
        this.lastGunTicks = 0;
    }

    public void tickDiagnostics() {
        if (this.lastGunResult != null) {
            this.lastGunTicks++;
        }
    }

    public void reset() {
        this.chargeTicks = 0;
        this.aimInit = false;
        this.jitterTicks = 0;
        this.gunSlot = -1;
        this.gunRepickTicks = 0;
        this.gunDrawn = false;
        this.gunDrawnSlot = -1;
        this.gunIdleTicks = 0;
        this.reloadWaitTicks = 0;
    }

    /** 手上/背包里有没有远程手段（枪 / 弓 / 弩 / 三叉戟 / 投掷物） */
    public boolean hasRanged() {
        // 弩的类名是 CrossbowItem，不含 "Bow"，必须单独查一次
        return hasGun() || findItem("Bow") >= 0 || findItem("Crossbow") >= 0
                || findItem("Trident") >= 0 || findThrowable() >= 0;
    }

    /** 远程手段的有效射程：有枪就以枪为准（比弓远得多），否则按原版弓的 14 格 */
    public double maxRange() {
        return hasGun() ? TaczCompat.GUN_RANGE : 14.0D;
    }

    /** 快捷栏 / 背包里有没有近战武器（剑 / 斧 / 重锤 / 三叉戟） */
    public boolean hasMeleeWeapon() {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String n = stack.getItem().getClass().getSimpleName();
            if (n.contains("Sword") || n.contains("Axe") || n.contains("Mace") || n.contains("Trident")) {
                return true;
            }
        }
        return false;
    }

    /** 背包里有没有枪（结果缓存 20 tick，避免每次调用都扫一遍 36 格背包） */
    public boolean hasGun() {
        if (this.gunCheckTicks > 0) {
            this.gunCheckTicks--;
            return this.gunAvailable;
        }
        this.gunCheckTicks = 20;
        this.gunAvailable = TaczCompat.hasGun(this.player);
        return this.gunAvailable;
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
        // 有枪就贴脸也开火（1.5 格起）：以前有剑时把枪收到 4 格以外，AI 一被贴住就再也不会开枪
        double minGunDist = hasGun() ? 1.5D : 4.0D;
        if (dist < minGunDist) {
            stopDrawing();
            return false;
        }
        if (this.player.profile() == null) {
            return false;
        }
        AiLevel level = this.player.profile().level();

        // 有枪先用枪（射速快、射程远）。打空了没备弹再退回弓 / 投掷物
        // 有效射程取"模组常量"与玩家配置的较小值：配置只用来收紧，不会放大到散布失控的距离
        var gunCfg = com.aiplayer.config.AiPlayerConfig.get();
        double gunRange = gunCfg.aiGunRange > 0
                ? Math.min(TaczCompat.GUN_RANGE, gunCfg.aiGunRange) : TaczCompat.GUN_RANGE;
        if (dist <= gunRange && hasGun() && tickGun(target, dist, level)) {
            return true;
        }
        if (dist > 40.0D) {
            stopDrawing();
            return false;
        }

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
        if (!this.player.selectSlot("ranged", com.aiplayer.util.InventoryLock.PRIO_RANGED, slot)) {
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
     * 用 TaCZ 枪械射击。
     * <p>
     * 服务端假玩家没有客户端按键包，所以不能靠"右键"开枪：直接调 {@code IGunOperator}：
     * 拔枪 -> 等拔枪动画结束 -> 瞄准 -> 打空弹匣 -> 换弹。每 40 tick 重新挑一次"最好的枪"
     * （弹匣有子弹 > 有备弹 > DPS 高）。
     *
     * @return true 表示本 tick 的输入已被枪械接管
     */
    private boolean tickGun(Entity target, double dist, AiLevel level) {
        com.aiplayer.config.AiPlayerConfig cfg = com.aiplayer.config.AiPlayerConfig.get();
        if (!TaczCompat.loaded() || !cfg.aiGun) {
            return false;
        }
        // 射程上限：太远就先追近（0 = 不限制）。远距离空放枪既浪费弹药又打不死人。
        if (cfg.aiGunRange > 0 && dist > cfg.aiGunRange) {
            return false;
        }
        if (this.gunRepickTicks > 0) {
            this.gunRepickTicks--;
        } else {
            this.gunSlot = TaczCompat.bestGunSlot(this.player);
            this.gunRepickTicks = 40;
        }
        if (this.gunSlot < 0) {
            this.gunRepickTicks = 0;
            return false;
        }
        AiInput in = this.player.input();
        // TaCZ 在疾跑状态下会拒绝射击（ShootResult.IS_SPRINTING）。
        // 之前 AI 一边追一边 sprint，于是"有枪却一发都打不出去" —— 开枪前必须收油门。
        // 注意：大脑 tick 跑在 applyAiInput() 之前，只改 in.sprint 要等这一 tick 结束才生效，
        // 而本 tick 紧接着就调 shoot()，读到的仍是上一 tick 的疾跑状态 → 白丢一枪（甚至卡在 IS_SPRINTING）。
        // 所以这里直接落一次 setSprinting(false)，让 shoot 立刻看到"没在跑"。
        in.sprint = false;
        in.forward = false;
        if (this.player.isSprinting()) {
            this.player.setSprinting(false);
        }

        if (!TaczCompat.selectGun(this.player, this.gunSlot)) {
            return false;
        }
        int selected = this.player.getInventory().getSelectedSlot();
        // 背包里的枪会被"交换进快捷栏"，原来记的槽位号就失效了（那个位置现在是别的物品）。
        // 不更新的话接下来 40 tick 会一直切到非枪槽位 → NOT_GUN，表现得像"拿着枪却不开火"。
        this.gunSlot = selected;
        if (this.gunDrawnSlot != selected) {
            // 换枪了：TaCZ 的拔枪状态跟着槽位走，必须重新拔
            this.gunDrawnSlot = selected;
            this.gunDrawn = false;
            this.gunIdleTicks = 0;
            this.gunRepickTicks = 40;
        }

        aimBallistic(target, dist, GUN_SPEED, level);

        if (!this.gunDrawn) {
            TaczCompat.draw(this.player);
            this.gunDrawn = true;
            return true;
        }
        if (TaczCompat.drawing(this.player)) {
            // 拔枪动画还没走完：这时候 shoot 只会返回 NOT_DRAW
            return true;
        }
        TaczCompat.aim(this.player, cfg.aiGunAim);
        if (TaczCompat.isReloading(this.player)) {
            this.reloadWaitTicks = 0;
            return true;
        }
        // 弹匣空了就换弹。TaCZ 的 IGunOperator.reload() 在下面这些情况下会**静默 return**：
        // 射击冷却未归零 / 拔枪冷却未归零 / 正在拉栓 / canReload=false。
        // 以前用 cooldownTicks=10 隔 10 tick 才试一次，很容易整段落在冷却窗口里被吞掉，
        // 表现就是"AI 永远不换弹"。现在每 tick 重试，直到 isReloading 真正成立。
        if (TaczCompat.loadedAmmo(this.player) <= 0) {
            // 创造模式弹匣：直接把弹匣填满，不消耗背包弹药，也就用不着换弹动画
            if (cfg.aiInfiniteAmmo && TaczCompat.refillMagazine(this.player)) {
                this.reloadWaitTicks = 0;
                return true;
            }
            if (TaczCompat.hasReserveAmmo(this.player)) {
                TaczCompat.reload(this.player);
                if (++this.reloadWaitTicks > 30) {
                    // 重试 30 tick 还进不了换弹：口径对不上 / 这把枪不走背包弹药。
                    // 再耗下去只会一直空枪，直接换一把（有备弹的别的枪）。
                    this.reloadWaitTicks = 0;
                    this.gunRepickTicks = 0;
                }
                return true;
            }
            // 这把枪彻底打空了：立刻重挑一把（可能还有别的枪 / 别的口径的备弹）
            this.gunRepickTicks = 0;
            return false;
        }
        this.reloadWaitTicks = 0;
        // 射速限制：冷却中扣扳机只会得到 COOL_DOWN，别当成故障去重新拔枪
        if (TaczCompat.shootOnCooldown(this.player)) {
            return true;
        }
        // 必须把这一 tick 算好的瞄准角传给 TaCZ：它的 shoot(Supplier, Supplier) 第一个参数是
        // pitch、第二个是 yaw，用它们决定子弹方向。以前传的是 0，子弹全飞向固定方向，打不到人。
        String result = TaczCompat.shootResult(this.player, this.aimPitch, this.aimYaw);
        noteGunResult(result == null ? "调用失败" : result);
        if ("SUCCESS".equals(result)) {
            this.gunIdleTicks = 0;
            return true;
        }
        if ("NEED_BOLT".equals(result)) {
            // 栓动枪：打完一发要拉栓，否则下一发永远 NEED_BOLT
            TaczCompat.bolt(this.player);
            this.gunIdleTicks = 0;
            return true;
        }
        if ("NOT_DRAW".equals(result) || "IS_DRAWING".equals(result)) {
            this.gunDrawn = false;
            this.gunIdleTicks = 0;
            return true;
        }
        if ("COOL_DOWN".equals(result) || "IS_RELOADING".equals(result) || "IS_BOLTING".equals(result)
                || "IS_SPRINTING".equals(result) || "IS_DRAWING".equals(result)) {
            // 这些都不是故障：疾跑收油门后 sprintTimeS 要几百毫秒才归零，等它自己衰减即可，
            // 不能计入失败次数（否则 20 tick 后又重新拔枪，永远卡在拔枪动画里）
            this.gunIdleTicks = 0;
            return true;
        }
        if ("NO_AMMO".equals(result)) {
            this.gunRepickTicks = 0;   // 没弹：下一 tick 重挑（有备弹会换弹，没备弹换另一把）
            return true;
        }
        // 其它（UNKNOWN_FAIL / IS_MELEE / ID_NOT_EXIST …）：连续 20 tick 都失败就重新拔一次
        if (++this.gunIdleTicks > 20) {
            this.gunDrawn = false;
            this.gunIdleTicks = 0;
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
        TaczCompat.aim(this.player, false);
        // 放开远程槽位锁：不放的话它会以 PRIO_RANGED(55) 卡住最多 40 tick，
        // 期间近战（PRIO_COMBAT=50）抢不回快捷栏，AI 会拿着枪被贴脸打。
        this.player.inventoryLock().release("ranged");
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
        if (!this.player.selectSlot("ranged", com.aiplayer.util.InventoryLock.PRIO_RANGED, slot)) {
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

    /**
     * 附近飞来的箭 / 三叉戟 / 投掷物（用于举盾格挡与逃生判定）。
     * 缓存：AiBrain 每 tick 都问一次"有没有东西朝我飞过来"，原来每 tick 做一次实体范围查询，
     * 改成每 10 tick 刷一次（0.5 秒的反应延迟对躲箭没影响）。
     */
    private static final int PROJ_SCAN_INTERVAL = 10;
    private Projectile projCache;
    private double projCacheRange = -1.0D;
    private int projCacheTicks;

    public Projectile incomingProjectile(double range) {
        if (range == this.projCacheRange && this.projCacheTicks > 0) {
            this.projCacheTicks--;
            return this.projCache;
        }
        this.projCacheRange = range;
        this.projCacheTicks = PROJ_SCAN_INTERVAL;
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
        this.projCache = best;
        return best;
    }
}