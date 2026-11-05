package com.aiplayer.ai.combat;

import com.aiplayer.ai.AiInput;
import com.aiplayer.ai.AiLevel;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.InvUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * 鞘翅长矛（Elytra + 原版长矛）：穿鞘翅跃入空中 -> 展开滑翔 -> 俯冲，长矛沿视线一刺贯穿。
 * <p>
 * 1.21.11 原版新增了长矛（{@code Items.WOODEN_SPEAR} … {@code Items.NETHERITE_SPEAR}，物品标签
 * {@code minecraft:spears}），机制是 {@code KINETIC_WEAPON} 数据组件：
 * 按住右键蓄力（15 tick 左右才出手），之后只要还在用着矛，每 tick 就沿视线对
 * {@code AttackRange}（最远约 6.5 格）内的实体做一次贯穿判定，
 * 伤害 = 基础攻击力 + 相对速度 × 倍率 —— 冲得越快扎得越狠。
 * <p>
 * 所以这里不再拿三叉戟近战平砍，而是"按住矛 + 高速俯冲"，命中判定整个交给原版：
 * <ul>
 *   <li>需要头顶开阔（能跳起来展开鞘翅）、目标 6~24 格、自己看得见目标；</li>
 *   <li>滑翔全程瞄准目标：远处压低机头加速，接近后抬机头减速避免摔死；</li>
 *   <li>全程按住长矛，蓄力完成后俯冲路径上的敌人挨个被扎；</li>
 *   <li>扎到人 / 掠过目标 / 落地即收手冷却；血量太低不玩（摔落风险）。</li>
 * </ul>
 */
public class ElytraSpearController {
    private static final double MIN_RANGE = 6.0D;
    private static final double MAX_RANGE = 24.0D;
    private static final float MIN_SELF_HEALTH = 8.0F;
    private static final int GLIDE_TIMEOUT = 200;
    /** 起跳后先抬机头爬升的 tick 数：跳跃给的高度不够俯冲，得靠抬头把高度吃回来 */
    private static final int CLIMB_TICKS = 30;
    /** 爬升目标高度（目标上方多少格就够俯冲了） */
    private static final double CLIMB_HEIGHT = 8.0D;
    /** 水平距离进到这么近才出矛：原版蓄力约十几 tick，正好在撞上目标前生效 */
    private static final double SPEAR_THRUST_DIST = 12.0D;
    /** 长矛档位：从好到差，挑背包里最好的一把（下界合金 > 钻石 > 铁 > 铜 > 石 > 金 > 木） */
    private static final Item[] SPEAR_TIERS = {
            Items.NETHERITE_SPEAR, Items.DIAMOND_SPEAR, Items.IRON_SPEAR,
            Items.COPPER_SPEAR, Items.STONE_SPEAR, Items.GOLDEN_SPEAR, Items.WOODEN_SPEAR
    };

    private enum Stage { IDLE, EQUIP, JUMP, GLIDE }

    private final AiPlayer player;
    private Stage stage = Stage.IDLE;
    private int stageTicks;
    private int cooldown;
    /** 本次突进是否已经出过矛：原版一次蓄力的"使用"会在 delay+伤害窗口后自动结束，每 tick 重开就会无限反复扎 */
    private boolean spearThrustStarted;
    /** 进入俯冲时目标的血量：滑翔中掉了血就说明扎中了，可以收手 */
    private float targetHealthAtStart = -1.0F;

    public ElytraSpearController(AiPlayer player) {
        this.player = player;
    }

    public void reset() {
        this.stage = Stage.IDLE;
        this.stageTicks = 0;
        this.targetHealthAtStart = -1.0F;
        this.spearThrustStarted = false;
        releaseSpear();
        this.player.inventoryLock().release("elytra");
    }

    public boolean isActive() {
        return this.stage != Stage.IDLE;
    }

    public static boolean hasKit(AiPlayer player) {
        return !InvUtil.find(player, Items.ELYTRA).isEmpty() && !findSpear(player).isEmpty();
    }

    /** 背包里最好的一把原版长矛（按档位从高到低找），没有就返回空 */
    public static ItemStack findSpear(AiPlayer player) {
        Inventory inv = player.getInventory();
        for (Item tier : SPEAR_TIERS) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.is(tier)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** 这把是不是原版长矛（认 KINETIC_WEAPON 组件，比认物品名更稳） */
    public static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.KINETIC_WEAPON);
    }

    /** @return true 表示本 tick 被鞘翅突进占用 */
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
                    && this.player.hurtTime == 0
                    && this.player.hasLineOfSight(target)
                    && skyAbove(12)
                    && !this.player.isFallFlying();
            if (!canStart) {
                return false;
            }
            this.stage = Stage.EQUIP;
            this.stageTicks = 0;
            return true;
        }
        this.stageTicks++;

        switch (this.stage) {
            case EQUIP -> {
                if (!equipElytra()) {
                    finish(100);
                    return false;
                }
                if (!equipSpear()) {
                    finish(100);
                    return false;
                }
                this.stage = Stage.JUMP;
                this.stageTicks = 0;
                return true;
            }
            case JUMP -> {
                // 起跳阶段不预先出矛：原版一次"使用"只有十几 tick 就会自动结束，
                // 在这里提前扎完，真正俯冲到目标跟前时矛已经收回去了。
                if (!this.player.onGround()) {
                    this.stage = Stage.GLIDE;
                    this.stageTicks = 0;
                    if (target instanceof LivingEntity le) {
                        this.targetHealthAtStart = le.getHealth();
                    }
                    return true;
                }
                if (this.stageTicks > 10) {
                    // 起跳（applyAiInput 会把 in.jump 转成 jumpFromGround）
                    in.jump = true;
                }
                if (this.stageTicks > 30) {
                    finish(60);
                }
                return true;
            }
            case GLIDE -> {
                // 展开鞘翅：空中 + 有鞘翅 + 还没展开（原版玩家双击空格的展开逻辑）。
                // 只有在"不再上升"时才试，正在往上冲的时候原版判定会失败，白喊一嗓子。
                if (!this.player.onGround() && !this.player.isFallFlying() && hasElytraEquipped()
                        && this.player.getDeltaMovement().y <= 0.0D) {
                    this.player.tryToStartFallFlying();
                }
                if (this.player.onGround() || !hasElytraEquipped()) {
                    // 落地 / 鞘翅被打掉：收手
                    finish(200);
                    return true;
                }
                if (this.stageTicks > GLIDE_TIMEOUT) {
                    finish(200);
                    return true;
                }
                return glideTick(in, level, target);
            }
            default -> {
                reset();
                return false;
            }
        }
    }

    /** 滑翔操控：先抬机头把高度吃回来，再压机头俯冲，全程按着长矛 */
    private boolean glideTick(AiInput in, AiLevel level, Entity target) {
        Vec3 toTarget = target.position().add(0.0D, target.getBbHeight() * 0.4D, 0.0D)
                .subtract(this.player.position());
        double horizDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);

        // 一个跳跃只有一格多高，直接俯冲几格就砸地上了 —— 这就是"AI 不会鞘翅长矛"的原因。
        // 真人玩鞘翅都是先抬头爬升再俯冲：抬头时原版会把速度换成高度，够到目标上方再压机头。
        boolean needClimb = !this.player.isFallFlying()
                || (this.stageTicks < CLIMB_TICKS && this.player.getY() < target.getY() + CLIMB_HEIGHT);
        if (needClimb) {
            this.player.lookAt(target.position().add(0.0D, CLIMB_HEIGHT, 0.0D), 45.0F);
        } else if (horizDist > 5.0D) {
            // 俯冲：直指目标（略高于目标中心，压机头加速）
            this.player.lookAt(target.position().add(0.0D, 0.2D, 0.0D), 40.0F + level.id * 8.0F);
        } else {
            // 接近：抬机头 20 度减速，别一头栽地上
            Vec3 shallow = target.position().add(0.0D, target.getBbHeight() * 0.7D, 0.0D);
            this.player.lookAt(shallow, 50.0F);
        }
        in.forward = true;
        in.jump = false;
        in.sprint = false;

        // 出矛：只在真正接近目标时按一次（约 12 格，正好抵消原版十几 tick 的蓄力延迟），
        // 一次突进只扎一次 —— 以前每 tick 重开会变成"伸出-收回"无限抽搐。
        if (horizDist <= SPEAR_THRUST_DIST) {
            holdSpear();
        }

        // 扎中了（目标掉血）：收手冷却，把位置让给近战 / 远程
        if (this.targetHealthAtStart > 0.0F && target instanceof LivingEntity le
                && le.getHealth() < this.targetHealthAtStart) {
            finish(level.id >= 3 ? 80 : 160);
            return true;
        }
        // 掠过目标且速度耗尽：收手，交给近战接管
        double speed = this.player.getDeltaMovement().horizontalDistance();
        if (horizDist < 2.0D && speed < 0.3D) {
            finish(120);
            return true;
        }
        return true;
    }

    private void finish(int cooldownTicks) {
        reset();
        this.cooldown = cooldownTicks;
    }

    // ------------------------------------------------------------------
    // 装备
    // ------------------------------------------------------------------

    private boolean hasElytraEquipped() {
        return this.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    /** 把鞘翅装进胸甲槽（换下来的原装备放回背包） */
    private boolean equipElytra() {
        if (hasElytraEquipped()) {
            return true;
        }
        Inventory inv = this.player.getInventory();
        int elytraSlot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(Items.ELYTRA)) {
                elytraSlot = i;
                break;
            }
        }
        if (elytraSlot < 0) {
            return false;
        }
        ItemStack elytra = inv.getItem(elytraSlot);
        ItemStack oldChest = this.player.getItemBySlot(EquipmentSlot.CHEST);
        this.player.setItemSlot(EquipmentSlot.CHEST, elytra.copy());
        inv.setItem(elytraSlot, oldChest);
        return true;
    }

    /** 把最好的一把长矛换到手上 */
    private boolean equipSpear() {
        ItemStack spear = findSpear(this.player);
        if (spear.isEmpty()) {
            return false;
        }
        Item best = spear.getItem();
        int slot = InvUtil.findHotbarSlot(this.player, best);
        if (slot < 0) {
            slot = InvUtil.pullToHotbar(this.player, best);
            if (slot < 0) {
                return false;
            }
        }
        return this.player.selectSlot("elytra", InventoryLock.PRIO_CRYSTAL, slot);
    }

    /**
     * 按住长矛蓄力：原版 KINETIC_WEAPON 靠"处于使用中"每 tick 做贯穿判定。
     * <p>
     * 但原版这次"使用"是有时限的（{@code delayTicks + 伤害窗口}，十几 tick 就自动结束）。
     * 以前这里每 tick 发现"没在用"就重开，于是一次突进里出矛 —— 收回 —— 再出矛无限循环，
     * 看起来就是长矛一直在抽搐。现在一次突进只出一次矛，落地 / 扎中后由 {@link #reset()} 复位。
     */
    private void holdSpear() {
        if (this.player.isUsingItem() || this.spearThrustStarted) {
            return;
        }
        if (isSpear(this.player.getMainHandItem())) {
            this.player.startUsingItem(InteractionHand.MAIN_HAND);
            this.spearThrustStarted = true;
        }
    }

    private void releaseSpear() {
        if (this.player.isUsingItem()) {
            // 走 releaseUsing：长矛松手时原版还要结算最后一击，直接 stopUsingItem 会漏掉
            this.player.releaseUsingItemNow();
        }
    }

    /** 头顶是否开阔（能起跳展开鞘翅） */
    private boolean skyAbove(int height) {
        BlockPos feet = this.player.blockPosition();
        for (int i = 2; i <= height; i++) {
            BlockPos pos = feet.above(i);
            if (!this.player.level().getBlockState(pos).getCollisionShape(this.player.level(), pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
