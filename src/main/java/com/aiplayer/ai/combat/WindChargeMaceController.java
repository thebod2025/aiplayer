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
 * <ul>
 *   <li>只在健康（>=10 心血量）且目标在中近距离（3~8 格）时起跳；</li>
 *   <li>空中全程瞄准目标，落地没砸中会自动收手进入冷却，不会无脑连跳；</li>
 *   <li>重锤/风弹的槽位用 CRYSTAL 级别锁，不被普通换武器抢走。</li>
 * </ul>
 */
public class WindChargeMaceController {
    private static final Random RNG = new Random();
    private static final double MIN_RANGE = 3.0D;
    private static final double MAX_RANGE = 8.0D;
    private static final float MIN_SELF_HEALTH = 10.0F;
    /** 空中滞留超时（tick）：风弹没把自己炸起来 / 被卡住时兜底 */
    private static final int AIR_TIMEOUT = 100;

    private enum Stage { IDLE, AIM, BOOST, DIVE }

    private final AiPlayer player;
    private Stage stage = Stage.IDLE;
    private int stageTicks;
    private int cooldown;

    public WindChargeMaceController(AiPlayer player) {
        this.player = player;
    }

    public void reset() {
        this.stage = Stage.IDLE;
        this.stageTicks = 0;
        this.player.inventoryLock().release("windmace");
    }

    public boolean isActive() {
        return this.stage != Stage.IDLE;
    }

    public static boolean hasKit(AiPlayer player) {
        return !InvUtil.find(player, Items.MACE).isEmpty()
                && !InvUtil.find(player, Items.WIND_CHARGE).isEmpty();
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
                    && this.player.hurtTime == 0;
            if (!canStart) {
                return false;
            }
            // 起手：先把重锤切到手上（空中再来回换槽容易和锁定打架）
            if (!equipMace(in)) {
                return false;
            }
            this.stage = Stage.AIM;
            this.stageTicks = 0;
            return true;
        }
        this.stageTicks++;

        switch (this.stage) {
            case AIM -> {
                // 低头发射风弹：爆炸在自己脚下，冲击波把自己顶上天
                if (!this.player.onGround()) {
                    this.stage = Stage.BOOST;
                    this.stageTicks = 0;
                    return true;
                }
                if (!equipWindCharge(in)) {
                    reset();
                    this.cooldown = 60;
                    return false;
                }
                if (this.stageTicks > 40) {
                    reset();
                    this.cooldown = 60;
                    return false;
                }
                // 站定 + 低头（45~65 度，风弹落在脚下半格到一格处）
                in.forward = false;
                in.backward = false;
                in.left = false;
                in.right = false;
                in.sprint = false;
                this.player.lookAt(this.player.position().add(0.6D, -1.2D, 0.0D), 60.0F);
                in.useItem = true;
                if (RNG.nextFloat() < level.mistakeChance * 0.5F) {
                    // 低强度偶尔手抖扔偏（多等几 tick）
                    this.stageTicks += 10;
                }
                return true;
            }
            case BOOST -> {
                // 刚起飞：切回重锤，等获得上升速度
                if (!equipMace(in)) {
                    reset();
                    this.cooldown = 100;
                    return false;
                }
                if (this.player.getDeltaMovement().y > 0.0D || !this.player.onGround()) {
                    this.stage = Stage.DIVE;
                    this.stageTicks = 0;
                }
                if (this.stageTicks > 20) {
                    reset();
                    this.cooldown = 60;
                }
                return true;
            }
            case DIVE -> {
                if (this.player.onGround()) {
                    // 落地了还没砸中：收手冷却
                    reset();
                    this.cooldown = 100;
                    return true;
                }
                if (this.stageTicks > AIR_TIMEOUT) {
                    reset();
                    this.cooldown = 100;
                    return true;
                }
                // 空中瞄准目标，往前飘（原版鞘翅/下落都吃 forward 输入）
                Vec3 aim = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
                this.player.lookAt(aim, 40.0F + level.id * 8.0F);
                in.forward = true;
                in.sprint = false;
                in.jump = false;
                // 下落中且够得着 -> 挥锤（原版按 fallDistance 结算摔劈加成）
                boolean falling = this.player.getDeltaMovement().y < -0.2D;
                if (falling && this.player.fallDistance > 1.5D
                        && this.player.isWithinEntityInteractionRange(target, 0.5D)
                        && this.player.getAttackStrengthScale(1.0F) >= 0.9F) {
                    in.attackEntity = true;
                    in.attackTarget = target;
                    reset();
                    this.cooldown = level.id >= 3 ? 60 : 120;
                }
                return true;
            }
            default -> {
                reset();
                return false;
            }
        }
    }

    private boolean equipMace(AiInput in) {
        int slot = InvUtil.findHotbarSlot(this.player, Items.MACE);
        if (slot < 0) {
            slot = InvUtil.pullToHotbar(this.player, Items.MACE);
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

    private boolean equipWindCharge(AiInput in) {
        int slot = InvUtil.findHotbarSlot(this.player, Items.WIND_CHARGE);
        if (slot < 0) {
            slot = InvUtil.pullToHotbar(this.player, Items.WIND_CHARGE);
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
