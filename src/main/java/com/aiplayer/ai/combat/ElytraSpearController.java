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
 * 26.x 原版新增了长矛（{@code Items.WOODEN_SPEAR} … {@code Items.NETHERITE_SPEAR}，物品标签
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
    /** 进入俯冲时目标的血量：滑翔中掉了血就说明扎中了，可以收手 */
    private float targetHealthAtStart = -1.0F;

    public ElytraSpearController(AiPlayer player) {
        this.player = player;
    }

    public void reset() {
        this.stage = Stage.IDLE;
        this.stageTicks = 0;
        this.targetHealthAtStart = -1.0F;
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
                // 起跳前就把矛握紧开始蓄力：原版要 15 tick 左右才出手，
                // 等俯冲到脸前才按就晚了，扎不出伤害。
                holdSpear();
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
                // 展开鞘翅：空中 + 有鞘翅 + 还没展开（原版玩家双击空格的展开逻辑）
                if (!this.player.onGround() && !this.player.isFallFlying() && hasElytraEquipped()) {
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

    /** 滑翔操控：远处压机头俯冲加速，接近后抬机头减速，全程按着长矛 */
    private boolean glideTick(AiInput in, AiLevel level, Entity target) {
        Vec3 toTarget = target.position().add(0.0D, target.getBbHeight() * 0.4D, 0.0D)
                .subtract(this.player.position());
        double horizDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);

        if (horizDist > 5.0D) {
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

        // 按住长矛：蓄力完成后原版每 tick 沿视线对 6.5 格内的实体做贯穿判定，
        // 冲得越快相对速度越高、伤害越大。命中判定交给原版，不再手搓平砍。
        holdSpear();

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

    /** 按住长矛蓄力：原版 KINETIC_WEAPON 靠"一直处于使用中"每 tick 做贯穿判定 */
    private void holdSpear() {
        if (!this.player.isUsingItem() && isSpear(this.player.getMainHandItem())) {
            this.player.startUsingItem(InteractionHand.MAIN_HAND);
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
