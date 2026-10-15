package com.aiplayer.ai.combat;

import com.aiplayer.ai.AiInput;
import com.aiplayer.ai.AiLevel;
import com.aiplayer.ai.path.AStarPathFinder;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.InvUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 近战战斗：W-tap / S-tap / Strafing / 暴击跳跃 / 1.9+ 攻击冷却管理，按强度分级。
 * <p>
 * 所有输出都只是按键：不直接造成伤害，攻击走 {@code Player.attack}（原版会做冷却、暴击、击退判定）。
 */
public class CombatController {
    private static final Random RNG = new Random();
    /** 超过这个距离就交回寻路去追 */
    public static final double ENGAGE_RANGE = 8.0D;
    /** 想贴到的距离 */
    private static final double IDEAL_RANGE = 2.6D;
    /** 只在这个距离内才切近战武器（更远的位置交给 RangedController 用弓箭） */
    private static final double WEAPON_SWITCH_RANGE = 4.0D;
    /** 追击珍珠冷却：扔完歇 3 秒，别一串珍珠乱丢 */
    private static final int CHASE_PEARL_COOLDOWN = 60;
    /** 珍珠追击的距离窗口：太近不值当，太远落点不可控 */
    private static final double PEARL_CHASE_MIN = 10.0D;
    private static final double PEARL_CHASE_MAX = 26.0D;

    /** 武器评分：越大越好用（剑最快、斧伤高、三叉戟折中） */
    private static final Map<Item, Integer> WEAPON_SCORE = new HashMap<>();

    static {
        WEAPON_SCORE.put(Items.NETHERITE_SWORD, 100);
        WEAPON_SCORE.put(Items.DIAMOND_SWORD, 92);
        WEAPON_SCORE.put(Items.MACE, 90);
        WEAPON_SCORE.put(Items.TRIDENT, 88);
        WEAPON_SCORE.put(Items.NETHERITE_AXE, 85);
        WEAPON_SCORE.put(Items.NETHERITE_SPEAR, 84);
        WEAPON_SCORE.put(Items.DIAMOND_SPEAR, 78);
        WEAPON_SCORE.put(Items.IRON_SWORD, 78);
        WEAPON_SCORE.put(Items.DIAMOND_AXE, 76);
        WEAPON_SCORE.put(Items.COPPER_SPEAR, 70);
        WEAPON_SCORE.put(Items.WOODEN_SPEAR, 68);
        WEAPON_SCORE.put(Items.STONE_SWORD, 62);
        WEAPON_SCORE.put(Items.STONE_SPEAR, 60);
        WEAPON_SCORE.put(Items.IRON_AXE, 60);
        WEAPON_SCORE.put(Items.IRON_SPEAR, 58);
        WEAPON_SCORE.put(Items.GOLDEN_SWORD, 56);
        WEAPON_SCORE.put(Items.STONE_AXE, 48);
        WEAPON_SCORE.put(Items.GOLDEN_AXE, 44);
        WEAPON_SCORE.put(Items.WOODEN_AXE, 38);
        WEAPON_SCORE.put(Items.GOLDEN_SPEAR, 42);
    }

    private final AiPlayer player;
    private Entity target;

    private int reactionTicks;
    private int strafeTicks;
    private int strafeDir;
    private int wTapTicks;
    private int backTicks;
    private int critPendingTicks;
    /** 举盾剩余 tick：对手挥击瞬间举起，多举 2 tick 兜住延迟命中 */
    private int shieldTicks;
    /** 当前是否处于举盾状态：松盾只发一次 release */
    private boolean shieldUp;
    /** 盾换到副手的检查间隔 */
    private int shieldEquipTicks;
    /** 选武器的检查间隔（不必每 tick 都切槽） */
    private int weaponCheckTicks;
    /** 追击珍珠冷却 */
    private int chasePearlCooldown;

    public CombatController(AiPlayer player) {
        this.player = player;
    }

    public Entity target() {
        return this.target;
    }

    public void setTarget(Entity entity) {
        if (entity != this.target) {
            this.target = entity;
            this.reactionTicks = reactionDelay();
        }
    }

    public void clear() {
        release();
        this.target = null;
    }

    public void release() {
        AiInput in = this.player.input();
        in.attackEntity = false;
        in.attackTarget = null;
        if (this.shieldUp) {
            in.releaseOffhandUse = true;
            this.shieldUp = false;
        }
        this.shieldTicks = 0;
        this.wTapTicks = 0;
        this.backTicks = 0;
        this.critPendingTicks = 0;
    }

    private int reactionDelay() {
        if (this.player.profile() == null) {
            return 8;
        }
        AiLevel level = this.player.profile().level();
        // 强度表：800~1000 / 400~600 / 150~250 / 50~100 ms
        int ms = level.reactionMs + (int) (RNG.nextFloat() * level.reactionMs * 0.25F);
        return Math.max(0, ms / 50);
    }

    /** @return true 表示正在战斗（输入已被接管） */
    public boolean tick() {
        AiInput in = this.player.input();
        if (this.target == null || !this.target.isAlive() || this.target.isRemoved()) {
            release();
            return false;
        }
        double dist = Math.sqrt(this.player.distanceToSqr(this.target));
        if (dist > ENGAGE_RANGE) {
            // 太远了，让寻路去追
            release();
            return false;
        }
        if (this.player.profile() == null) {
            release();
            return false;
        }
        AiLevel level = this.player.profile().level();

        if (this.reactionTicks > 0) {
            this.reactionTicks--;
        }
        if (this.chasePearlCooldown > 0) {
            this.chasePearlCooldown--;
        }

        aimAt(level, dist);

        // ---- 自动选武器：贴到近战距离时切到评分最高的武器 ----
        if (dist <= WEAPON_SWITCH_RANGE && !this.player.isUsingItem() && this.weaponCheckTicks-- <= 0) {
            this.weaponCheckTicks = 20;
            selectBestWeapon();
        }

        // ---- 盾上副手：近战对手进圈时备好盾（不动副手里的图腾） ----
        if (dist <= 6.0D && this.shieldEquipTicks-- <= 0) {
            this.shieldEquipTicks = 40;
            equipShieldOffhand();
        }

        // ---- 盾挡时机：对手挥击的瞬间举盾（1.9 盾能 100% 挡掉近战伤害） ----
        boolean enemySwinging = this.target instanceof LivingEntity le
                && le.swinging && le.swingTime < 8
                && dist <= 4.0D;
        // 预判举盾：只对玩家 —— 怪物没有攻击冷却条可读，高强度的 AI 会看对手的蓄力进度
        boolean predictedSwing = this.target instanceof Player p
                && level.id >= 3 && dist <= 3.6D
                && p.getAttackStrengthScale(0.5F) > 0.85F;
        // 已经挨到伤害了：这一帧优先补盾，别管别的冷却
        boolean hurtNow = this.player.hurtTime > 0;
        if ((enemySwinging || predictedSwing || hurtNow)
                && this.player.getOffhandItem().getItem() == Items.SHIELD
                && (hurtNow || (this.shieldTicks <= 0 && this.reactionTicks <= 0))) {
            this.shieldTicks = 6 + (level.id >= 3 ? 3 : 1);
        }
        if (this.shieldTicks > 0) {
            this.shieldTicks--;
            this.shieldUp = true;
            in.useOffhand = true;   // AiPlayer 只在未举盾时触发 use，按住即保持
        } else if (this.shieldUp) {
            this.shieldUp = false;
            in.releaseOffhandUse = true;
        }

        // ---- 站位：贴身、绕圈、退开 ----
        in.forward = false;
        in.backward = false;
        in.left = false;
        in.right = false;
        if (this.wTapTicks > 0) {
            // W-tap：松一瞬 W 重置疾跑（保留击退位移），随后重新疾跑
            this.wTapTicks--;
            in.sprint = false;
        } else if (this.backTicks > 0) {
            // S-tap：后退一步躲开对手的攻击距离
            this.backTicks--;
            in.backward = true;
            in.sprint = false;
        } else {
            if (dist > IDEAL_RANGE + 0.4D) {
                in.forward = true;
                // 弧线切入：中远程斜着接近，不再一条直线冲脸（真人逼近都带角度）
                if (dist > 4.5D && level.id >= 2 && this.strafeDir != 0) {
                    if (this.strafeDir > 0) {
                        in.right = true;
                    } else {
                        in.left = true;
                    }
                }
            } else if (dist < IDEAL_RANGE - 0.9D) {
                in.backward = true;
            }
            in.sprint = level.id >= 2 && dist > IDEAL_RANGE;
            strafe(level, in, dist);
        }

        // ---- 攻击（容错：够得着 + 看得见才出刀，不做无脑频率压制） ----
        if (this.reactionTicks <= 0 && this.critPendingTicks <= 0) {
            if (dist <= 2.8D && this.player.hasLineOfSight(this.target)) {
                maybeAttack(level, dist, in);
            }
        }
        if (this.critPendingTicks > 0) {
            this.critPendingTicks--;
            // 起跳后下落的那一帧才是暴击；距离也卡一下，落点飘到 3 格外那一刀其实够不着
            if (!this.player.onGround() && this.player.getDeltaMovement().y < 0.0D
                    && dist <= 3.0D
                    && this.player.isWithinEntityInteractionRange(this.target, 0.0D)) {
                this.critPendingTicks = 0;
                doAttack(in);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------

    /**
     * 自动选择武器：在快捷栏里挑评分最高的一件切到手上。
     * <p>
     * 走 {@link InventoryLock} 的 COMBAT 优先级，所以进食 / 逃生 / 水晶等高优先级动作
     * 仍然可以抢占槽位；如果手上已经是最好的武器就不动，避免槽位闪烁。
     */
    private void selectBestWeapon() {
        Inventory inv = this.player.getInventory();
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            int score = weaponScore(inv.getItem(i));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0 || inv.getSelectedSlot() == best) {
            return;
        }
        if (this.player.selectSlot("combat", InventoryLock.PRIO_COMBAT, best)) {
            // 换武器的这一帧不要立刻挥空
            this.reactionTicks = Math.max(this.reactionTicks, 2);
        }
    }

    private static int weaponScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        Integer score = WEAPON_SCORE.get(stack.getItem());
        return score == null ? 0 : score;
    }

    private void maybeAttack(AiLevel level, double dist, AiInput in) {
        // 空中不出手：跳劈只有下落那一帧算数（由 critPendingTicks 那块处理），
        // 滞空超时没打中就等落地重新再判断，别在半空挥空刀
        if (!this.player.onGround()) {
            return;
        }
        if (!this.player.isWithinEntityInteractionRange(this.target, 0.0D)) {
            return;
        }
        if (this.player.getAttackStrengthScale(1.0F) < 0.9F) {
            return;
        }
        // 还没转过来就先别挥空：低强度转身很慢，转身没到位就打，看起来就是"朝反方向攻击"
        if (!isAimedAtTarget(in)) {
            return;
        }
        // 暴击跳跃：地面 + 不疾跑时起跳；只在 2.0~3.4 的甜区跳（贴脸跳劈落点容易过头）
        if (this.player.onGround() && !this.player.isSprinting()
                && dist > 1.8D && dist < 3.4D
                && level.critChance > 0.0F && RNG.nextFloat() < level.critChance) {
            in.jump = true;
            // WD 跳劈节奏：起跳后 W 不松 + 带侧移，空中不是站桩靶子；疾跑会吃掉暴击判定
            in.forward = true;
            in.sprint = false;
            if (this.strafeDir == 0) {
                this.strafeDir = RNG.nextBoolean() ? 1 : -1;
            }
            // 高强度滞空短（落点更准），低强度给足时间把刀挥出去
            this.critPendingTicks = level.id >= 3 ? 10 : 14;
            return;
        }
        doAttack(in);
    }

    /**
     * 准星是否已经基本对着目标（水平误差 35° 以内）。
     * <p>
     * 用 {@code in.yaw}（本 tick 即将应用的朝向）而不是实体当前朝向，否则永远差一 tick，
     * 会在"还没转到位"的那一帧就挥出去。
     */
    private boolean isAimedAtTarget(AiInput in) {
        double dx = this.target.getX() - this.player.getX();
        double dz = this.target.getZ() - this.player.getZ();
        double d2 = dx * dx + dz * dz;
        if (d2 < 2.56D) {
            return true; // 贴脸（1.6 格内）不需要瞄准
        }
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float err = Math.abs(AiPlayer.wrapDegrees(targetYaw - in.yaw));
        return err < 35.0F;
    }

    private void doAttack(AiInput in) {
        in.attackEntity = true;
        in.attackTarget = this.target;
        in.sneak = false;
        AiLevel level = this.player.profile().level();
        // W-tap
        if (!this.player.isSprinting() || RNG.nextFloat() < level.wTapChance) {
            this.wTapTicks = 1 + level.id / 2;
        }
        // S-tap（强度 2 以上才有）
        if (level.id >= 2 && RNG.nextFloat() < level.strafeSkill * 0.35F) {
            this.backTicks = 2;
        }
    }

    /** Strafing：绕着对手走，强度越高越灵活 */
    private void strafe(AiLevel level, AiInput in, double dist) {
        // 贴脸（1.4 格内）不横移：跟对手身体互推只会原地抖，看着像卡住
        if (dist < 1.4D) {
            this.strafeDir = 0;
            this.strafeTicks = 6 + RNG.nextInt(8);
            return;
        }
        // 被打中的瞬间换向：真人挨了一刀会本能地变节奏，不沿原轨迹走
        if (this.player.hurtTime > 5 && this.strafeDir != 0 && this.strafeTicks > 4) {
            this.strafeDir = -this.strafeDir;
            this.strafeTicks = 6 + RNG.nextInt(10);
        }
        if (this.strafeTicks-- <= 0) {
            float skill = level.strafeSkill;
            // 贴身缠斗时高手不中断绕圈（停下来就是白挨打），远程才允许静止观望
            boolean locked = dist <= IDEAL_RANGE + 0.4D && skill > 0.4F;
            if (!locked && (skill <= 0.0F || RNG.nextFloat() > skill)) {
                this.strafeDir = 0;
                this.strafeTicks = 10 + RNG.nextInt(15);
            } else {
                this.strafeDir = RNG.nextBoolean() ? 1 : -1;
                // 绕背：只在 1.6~4.0 格才绕（更近是互推、更远够不着），
                // 往对手背后的方向走（抢背后位，对面转身就需要时间）
                if (level.id >= 2 && dist >= 1.6D && dist <= 4.0D && this.target instanceof LivingEntity le) {
                    double dx = this.player.getX() - le.getX();
                    double dz = this.player.getZ() - le.getZ();
                    double toSelf = Math.toDegrees(Math.atan2(-dx, dz));
                    double rel = AiPlayer.wrapDegrees((float) (toSelf - le.getYRot()));
                    if (Math.abs(rel) < 75.0D) {
                        // 还在对手正面：往相对角绝对值增大的方向绕（右移绕向 -rel，左移绕向 +rel）
                        this.strafeDir = rel > 0 ? -1 : 1;
                    }
                }
                this.strafeTicks = 8 + RNG.nextInt(20);
            }
        }
        // 卡墙 / 顶人自检：侧移输入开着但水平速度几乎为 0 —— 说明顶着障碍滑步，换向。
        // 顶着对手身体（贴到 1.5 格内）也是这个表现，一样要换向，别原地互推。
        if (this.strafeDir != 0 && this.player.onGround()
                && this.player.getDeltaMovement().horizontalDistanceSqr() < 0.002D
                && (dist < 1.5D || this.strafeTicks % 5 == 0)) {
            this.strafeDir = -this.strafeDir;
            this.strafeTicks = 8 + RNG.nextInt(12);
        }
        if (this.strafeDir > 0) {
            in.right = true;
        } else if (this.strafeDir < 0) {
            in.left = true;
        }
        if (level.id >= 3) {
            // 高手会预判对手转身方向，反向拉扯
            if (this.strafeDir != 0 && this.strafeTicks < 4) {
                in.left = this.strafeDir <= 0;
                in.right = this.strafeDir > 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // 末影珍珠追击：追不上（没视线 / 一直被卡住）时直接把自己送过去
    // ------------------------------------------------------------------

    /**
     * 用末影珍珠把距离拉近，只在"真的追不上"时才用 —— 隔着墙绕不出去，或者寻路一直卡住。
     * <p>
     * 投掷本身走原版：选好珍珠、按住右键、抬头 30°（这个角度原版珍珠飞得最远），
     * 并且先检查落点有没有能站的位置、是不是水 / 岩浆。
     *
     * @return true 表示这一 tick 的输入已经被珍珠接管，调用方应直接 return
     */
    public boolean chasePearl(Entity target, double dist) {
        if (this.chasePearlCooldown > 0) {
            return false;
        }
        if (dist <= PEARL_CHASE_MIN || dist > PEARL_CHASE_MAX) {
            return false;
        }
        if (this.player.profile() == null || !this.player.profile().throwables) {
            return false;
        }
        // 看得见又走得通就别浪费珍珠（寻路卡住 3 秒以上才算"走不通"）
        boolean stuck = this.player.brain() != null && this.player.brain().isStuck();
        if (!stuck && this.player.hasLineOfSight(target)) {
            return false;
        }
        if (this.player.fallDistance != 0.0F) {
            return false;   // 空中 / 下落中扔会把落点算歪，还可能把自己送得更远
        }
        int slot = InvUtil.findHotbarSlot(this.player, Items.ENDER_PEARL);
        if (slot < 0 || !pearlLandingSafe(target)) {
            return false;
        }
        if (!this.player.selectSlot("combat", InventoryLock.PRIO_COMBAT, slot)) {
            return false;
        }
        AiInput in = this.player.input();
        in.forward = true;                              // 助跑一下，抛物线能更远
        if (this.player.onGround()) {
            in.jump = true;
        }
        double dx = target.getX() - this.player.getX();
        double dz = target.getZ() - this.player.getZ();
        in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        in.pitch = -30.0F;
        in.useItem = true;
        this.chasePearlCooldown = CHASE_PEARL_COOLDOWN;
        return true;
    }

    /** 落点预检：目标脚下附近得有个能站的地方，而且不是水 / 岩浆（否则珍珠白扔） */
    private boolean pearlLandingSafe(Entity target) {
        Level level = this.player.level();
        BlockPos land = AStarPathFinder.nearestStandable(level, target.blockPosition(), 2);
        if (land == null) {
            return false;
        }
        return !isFluid(level.getBlockState(land)) && !isFluid(level.getBlockState(land.above()));
    }

    private static boolean isFluid(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    /** 瞄准：加一个和强度相关的误差，并且转身有速度上限 */
    private void aimAt(AiLevel level, double dist) {
        Vec3 eye = this.player.getEyePosition();
        double tx = this.target.getX();
        double ty = this.target.getY() + this.target.getBbHeight() * 0.82D;
        double tz = this.target.getZ();
        if (this.target instanceof LivingEntity living) {
            // 预判：按箭/拳的“命中时间”提前量瞄准移动方向
            double lead = Math.min(0.35D, dist / 12.0D);
            Vec3 motion = living.getDeltaMovement();
            tx += motion.x * lead;
            ty += motion.y * lead * 0.5D;
            tz += motion.z * lead;
        }
        if (level.aimErrorDeg > 0.01F) {
            double err = Math.toRadians(level.aimErrorDeg * (0.5D + RNG.nextDouble()));
            double radius = Math.max(0.1D, dist) * Math.tan(err);
            tx += (RNG.nextDouble() * 2.0D - 1.0D) * radius;
            ty += (RNG.nextDouble() * 2.0D - 1.0D) * radius * 0.6D;
            tz += (RNG.nextDouble() * 2.0D - 1.0D) * radius;
        }
        if (eye.distanceToSqr(tx, ty, tz) < 0.04D) {
            return;
        }
        float maxTurn = 14.0F + level.id * 9.0F;
        this.player.lookAt(tx, ty, tz, maxTurn);
    }

    /** 举盾（副手持盾、且当前没在贴脸输出时） */
    public void raiseShield() {
        AiInput in = this.player.input();
        in.useOffhand = true;
    }

    public void lowerShield() {
        AiInput in = this.player.input();
        in.releaseOffhandUse = true;
    }

    /**
     * 盾换到副手：近战对手进圈时备用。副手已有盾不动；副手是图腾时绝不覆盖（保命优先）。
     */
    private void equipShieldOffhand() {
        Inventory inv = this.player.getInventory();
        ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
        if (!off.isEmpty() && (off.getItem() == Items.SHIELD || off.getItem() == Items.TOTEM_OF_UNDYING)) {
            return;
        }
        int slot = -1;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).getItem() == Items.SHIELD) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return;
        }
        ItemStack shield = inv.getItem(slot);
        inv.setItem(slot, off);
        inv.setItem(Inventory.SLOT_OFFHAND, shield);
    }
}