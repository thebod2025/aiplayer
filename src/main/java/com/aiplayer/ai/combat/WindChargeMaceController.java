package com.aiplayer.ai.combat;

import com.aiplayer.ai.AiInput;
import com.aiplayer.ai.AiLevel;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.InvUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * 风弹重锤（Wind Charge + Mace）：低头发射风弹把自己炸上天 -> 空中飘向目标 ->
 * 下落时用重锤砸下去。原版重锤摔劈（fallDistance > 1.5）伤害按坠落高度大幅加成，
 * 命中后原版自动免除这次坠落伤害 —— 这是 1.21+ 的招牌连招。
 * <p>
 * 2026-10-17 重做（用户："AI 的重锤 PvP 就是不会瞄到人" / "风弹是可以边跳边放的"）：
 * <ul>
 *   <li><b>边跳边放</b>：AIM 阶段不再要求站定 —— 起跳的同时把风弹朝脚下扔，
 *       跳跃本身给的那点高度 + 风弹冲击波叠加，比站着扔高得多，也更难被近战打断；</li>
 *   <li><b>珍珠上天</b>：新增 PEARL 阶段 —— 目标太远/太高、或者手上没有风弹时，
 *       先朝"目标上方 6 格"扔末影珍珠，直接传送上高空，再从天上俯冲砸；</li>
 *   <li><b>每 tick 瞄人</b>：BOOST / DIVE 阶段每 tick 用最大转向速度（180°/tick，等于瞬间对准）
 *       重新 {@code lookAt} 目标，并按目标当前速度做提前量预判落点 —— 这才是"砸得到人"的关键；</li>
 *   <li>空中全程瞄准目标，落地没砸中会自动收手进入冷却，不会无脑连跳。</li>
 * </ul>
 * 重锤/风弹/珍珠的槽位用 CRYSTAL 级别锁，不被普通换武器抢走。
 */
public class WindChargeMaceController {
    private static final Random RNG = new Random();
    /** 起手距离：近到能被近战打断，远到风弹推不到目标身上都不合适 */
    private static final double MIN_RANGE = 2.5D;
    private static final double MAX_RANGE = 14.0D;
    /** 目标高度差超过这个值时优先走"珍珠上天"而不是风弹 */
    private static final double PEARL_HEIGHT_TRIGGER = 3.0D;
    private static final float MIN_SELF_HEALTH = 8.0F;
    /** 空中滞留超时（tick）：风弹没把自己炸起来 / 被卡住时兜底 */
    private static final int AIR_TIMEOUT = 120;
    /** 起跳/瞄准的 tick 数（边跳边放，跳起来后这一 tick 才出手） */
    private static final int AIM_TICKS = 3;
    /** 出手后等自己离地的宽限 tick：超了说明风弹没炸起来，认命收手 */
    private static final int AIM_FIRE_GRACE = 25;
    /** 扔了珍珠后等传送发生（位置突变）的宽限 tick */
    private static final int PEARL_GRACE = 35;
    /** 俯冲时允许挥锤的额外距离容差（原版重锤攻击距离约 3 格） */
    private static final double DIVE_HIT_PADDING = 2.0D;

    private enum Stage { IDLE, PEARL, AIM, BOOST, DIVE }

    private final AiPlayer player;
    private Stage stage = Stage.IDLE;
    private int stageTicks;
    private int cooldown;
    /** 本次连招有没有已经扔过风弹：风弹是消耗品，一次只许扔一发 */
    private boolean fired;
    /** 扔珍珠前的起手位置，用来判断"传送发生了" */
    private Vec3 pearlOrigin;

    public WindChargeMaceController(AiPlayer player) {
        this.player = player;
    }

    public void reset() {
        this.stage = Stage.IDLE;
        this.stageTicks = 0;
        this.fired = false;
        this.pearlOrigin = null;
        this.player.inventoryLock().release("windmace");
    }

    private void finish(int cooldownTicks) {
        reset();
        this.cooldown = cooldownTicks;
    }

    public boolean isActive() {
        return this.stage != Stage.IDLE;
    }

    /**
     * 背包里有没有这套连招：重锤是必须的，风弹或末影珍珠有其一即可
     * （珍珠负责"先上高空"，风弹负责"脚下一炸把人顶起来"）。
     */
    public static boolean hasKit(AiPlayer player) {
        return !InvUtil.find(player, Items.MACE).isEmpty()
                && (!InvUtil.find(player, Items.WIND_CHARGE).isEmpty()
                || !InvUtil.find(player, Items.ENDER_PEARL).isEmpty());
    }

    /** @return true 表示本 tick 被风弹重锤连招占用 */
    public boolean tick(Entity target) {
        AiLevel level = this.player.profile() != null ? this.player.profile().level() : null;
        if (level == null || target == null || !target.isAlive()) {
            reset();
            return false;
        }
        AiInput in = this.player.input();
        if (this.stage == Stage.IDLE) {
            if (this.cooldown > 0) {
                this.cooldown--;
                return false;
            }
            double dist = Math.sqrt(this.player.distanceToSqr(target));
            boolean canStart = dist >= MIN_RANGE && dist <= MAX_RANGE
                    && this.player.onGround()
                    && this.player.getHealth() >= MIN_SELF_HEALTH
                    && this.player.hurtTime <= 3;
            if (!canStart) {
                return false;
            }
            // 起手：先把重锤切到手上（空中再来回换槽容易和锁定打架）
            if (!equipMace(in)) {
                return false;
            }
            double dy = target.getY() - this.player.getY();
            boolean wantPearl = !InvUtil.find(this.player, Items.ENDER_PEARL).isEmpty()
                    && (dy > PEARL_HEIGHT_TRIGGER
                    || InvUtil.find(this.player, Items.WIND_CHARGE).isEmpty());
            this.stage = wantPearl ? Stage.PEARL : Stage.AIM;
            this.stageTicks = 0;
            this.fired = false;
            this.pearlOrigin = this.player.position();
            return true;
        }
        this.stageTicks++;

        switch (this.stage) {
            case PEARL -> {
                // 珍珠上天：朝"目标上方 6 格 + 朝目标偏 3 格"扔，传送点正好落在目标头顶，
                // 省掉"从地面靠风弹慢慢顶"的过程 —— 这就是用户要的"风弹珍珠飞到天上"。
                if (this.pearlMoved()) {
                    this.stage = Stage.BOOST;
                    this.stageTicks = 0;
                    return true;
                }
                if (this.stageTicks > PEARL_GRACE) {
                    // 没扔出去 / 扔歪了：退回风弹路线（重新允许扔风弹）
                    this.stage = Stage.AIM;
                    this.stageTicks = 0;
                    this.fired = false;
                    return true;
                }
                if (!this.fired) {
                    if (!equipPearl(in)) {
                        this.stage = Stage.AIM;
                        this.stageTicks = 0;
                        return true;
                    }
                    // 珍珠落点 = 目标 + 上方 6 格；朝它扔（原版珍珠就是走视线抛物线）
                    Vec3 aim = new Vec3(target.getX(), target.getY() + 6.0D, target.getZ());
                    this.player.lookAt(aim, 180.0F);
                    if (this.stageTicks >= AIM_TICKS) {
                        in.useItem = true;
                        this.fired = true;
                    }
                } else {
                    // 扔出去了还在原地：说明抛物线飞不到，换回风弹
                    this.player.lookAt(new Vec3(target.getX(), target.getY() + 6.0D, target.getZ()), 180.0F);
                }
                return true;
            }
            case AIM -> {
                // 边跳边放：不再要求站定。风弹朝脚下扔，跳跃 + 冲击波叠加把人顶上天。
                if (!this.player.onGround()) {
                    this.stage = Stage.BOOST;
                    this.stageTicks = 0;
                    return true;
                }
                if (!equipWindCharge(in)) {
                    // 没风弹了但还有珍珠：改走珍珠路线
                    if (!InvUtil.find(this.player, Items.ENDER_PEARL).isEmpty()) {
                        this.stage = Stage.PEARL;
                        this.stageTicks = 0;
                        this.fired = false;
                        this.pearlOrigin = this.player.position();
                        return true;
                    }
                    finish(60);
                    return false;
                }
                // 先把 yaw 对准目标（保证 in.forward 真的是朝目标助跑），
                // 再把 pitch 单独压到脚下 —— 风弹要朝脚底扔，爆炸点在自己脚底，冲击波把人往上顶。
                // 注意不能直接 lookAt "正下方"：那样水平分量为 0，yaw 会算成任意值，
                // 助跑方向就乱了（人朝后跑、风弹还朝前扔）。也不能往目标方向偏移爆炸点 ——
                // 冲击波是"把人推离爆炸点"的，爆炸点偏向目标等于把自己往反方向推。
                this.player.lookAt(target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D), 180.0F);
                in.pitch = 88.0F;
                in.forward = true;
                in.sprint = true;
                // 边跳边放：跳跃与出手同一 tick 触发
                in.jump = true;
                if (!this.fired) {
                    if (this.stageTicks >= AIM_TICKS) {
                        // 只按这一 tick：风弹是消耗品，多按会把一整叠全扔出去
                        in.useItem = true;
                        this.fired = true;
                    }
                } else if (this.stageTicks > AIM_TICKS + AIM_FIRE_GRACE && this.player.onGround()) {
                    // 扔了却没把自己炸起来（砸到方块 / 被卡住）：收手
                    finish(80);
                    return false;
                }
                if (RNG.nextFloat() < level.mistakeChance * 0.5F) {
                    this.stageTicks -= 1;
                }
                return true;
            }
            case BOOST -> {
                // 刚起飞：切回重锤，等获得上升速度
                if (!equipMace(in)) {
                    finish(100);
                    return false;
                }
                if (this.player.getDeltaMovement().y > 0.0D || !this.player.onGround()) {
                    this.stage = Stage.DIVE;
                    this.stageTicks = 0;
                    return true;
                }
                if (this.stageTicks > 20) {
                    finish(60);
                }
                return true;
            }
            case DIVE -> {
                if (this.player.onGround()) {
                    // 落地了还没砸中：收手冷却
                    finish(100);
                    return true;
                }
                if (this.stageTicks > AIR_TIMEOUT) {
                    finish(100);
                    return true;
                }
                // 空中手上必须是重锤（半路被别的控制器换掉就补回来，不然白跳一次）
                if (!this.player.getMainHandItem().is(Items.MACE)) {
                    equipMace(in);
                }
                // 每 tick 重新瞄人：按目标当前速度做提前量，180° 最大转向 = 瞬间对准。
                // 这是"重锤 PvP 砸不到人"的关键修复 —— 以前只在出手那一刻瞄一次，
                // 目标一横向走位就砸空了。
                Vec3 predicted = predict(target, (float) this.player.fallDistance);
                this.player.lookAt(predicted, 180.0F);
                in.forward = true;
                in.sprint = false;
                in.jump = false;
                // 下落中且够得着 -> 挥锤（原版按 fallDistance 结算摔劈加成）
                boolean falling = this.player.getDeltaMovement().y < -0.2D;
                if (falling && this.player.fallDistance > 1.0D
                        && this.player.isWithinEntityInteractionRange(target, DIVE_HIT_PADDING)
                        && this.player.getAttackStrengthScale(1.0F) >= 0.9F) {
                    // 挥锤前最后再对准一次目标中心，保证 attack 不会走 pickEntity 兜底而落空
                    this.player.lookAt(target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D), 180.0F);
                    in.attackEntity = true;
                    in.attackTarget = target;
                    finish(level.id >= 3 ? 60 : 120);
                }
                return true;
            }
            default -> {
                reset();
                return false;
            }
        }
    }

    /** 目标速度外推：按剩余下落时间预估它那时在哪，避免砸空 */
    private Vec3 predict(Entity target, float fallDistance) {
        double remaining = Math.max(1.0D, 12.0D - fallDistance * 0.5D);
        Vec3 v = target.getDeltaMovement();
        return target.position()
                .add(v.x * remaining, target.getBbHeight() * 0.5D + v.y * remaining, v.z * remaining);
    }

    /** 珍珠有没有把自己传送走（位置突变超过 2 格） */
    private boolean pearlMoved() {
        if (this.pearlOrigin == null) {
            return false;
        }
        return this.pearlOrigin.distanceToSqr(this.player.position()) > 4.0D;
    }

    private boolean equipMace(AiInput in) {
        return equip(in, Items.MACE);
    }

    private boolean equipWindCharge(AiInput in) {
        return equip(in, Items.WIND_CHARGE);
    }

    private boolean equipPearl(AiInput in) {
        return equip(in, Items.ENDER_PEARL);
    }

    private boolean equip(AiInput in, net.minecraft.world.item.Item item) {
        int slot = InvUtil.findHotbarSlot(this.player, item);
        if (slot < 0) {
            slot = InvUtil.pullToHotbar(this.player, item);
            if (slot < 0) {
                return false;
            }
        }
        if (!this.player.selectSlot("windmace", InventoryLock.PRIO_CRYSTAL, slot)) {
            return false;
        }
        in.selectedSlot = slot;
        return true;
    }
}
