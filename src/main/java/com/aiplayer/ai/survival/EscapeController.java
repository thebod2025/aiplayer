package com.aiplayer.ai.survival;

import com.aiplayer.ai.AiInput;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.InvUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 逃生控制器：危险时会像真人一样自救。
 * <ol>
 *   <li><b>落地水（MLG water）</b>：从高处坠落时朝脚下倒水；</li>
 *   <li><b>末影珍珠逃生</b>：被贴脸围殴 / 残血 / 有投射物飞来时，朝反方向跳起扔末影珍珠；</li>
 *   <li><b>撤退</b>：没有珍珠就背对敌人疾跑脱离，同时举盾。</li>
 * </ol>
 * 所有动作都走原版 {@code useItem}，投掷物速度、落地传送、摔落伤害都由原版结算。
 */
public class EscapeController {
    /** 末影珍珠两次使用之间的最小间隔（tick） */
    private static final int PEARL_COOLDOWN = 80;
    /** 触发落地水的最小下落高度 */
    private static final float WATER_CLUTCH_FALL = 5.0F;
    /** 刚挨过打 / 被击退打飞的坠落（跳劈被顶下来）留更短的余量，等 5 格就来不及了 */
    private static final float WATER_CLUTCH_FALL_HURT = 3.5F;
    /** 倒完水后的冷却：桶空了、水还要流一会儿，这段时间别重复倒 */
    private static final int WATER_COOLDOWN = 100;
    /** 残血阈值 */
    private static final float LOW_HEALTH = 8.0F;

    private final AiPlayer player;
    private int cooldown;
    private int pearlCooldown;
    private int waterCooldown;

    public EscapeController(AiPlayer player) {
        this.player = player;
    }

    public void reset() {
        this.pearlCooldown = 0;
        this.cooldown = 0;
        this.waterCooldown = 0;
    }

    private boolean enabled() {
        return this.player.profile() != null && this.player.profile().escape;
    }

    /** @return true 表示本 tick 的输入已被逃生接管 */
    public boolean tick(AiInput in) {
        if (this.pearlCooldown > 0) {
            this.pearlCooldown--;
        }
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        if (this.waterCooldown > 0) {
            this.waterCooldown--;
        }
        if (!enabled() || this.cooldown > 0) {
            return false;
        }
        if (tryWaterClutch(in)) {
            return true;
        }
        if (tryElytraClutch(in)) {
            return true;
        }
        boolean hasPearl = this.player.profile().throwables && InvUtil.has(this.player, Items.ENDER_PEARL);
        Entity threat = nearestThreat(6.0D);
        boolean inDanger = threat != null
                && (this.player.getHealth() <= LOW_HEALTH || hostileCount(7.0D) >= 2
                    // 被玩家贴到脸上的残血：血条还够但再挨两刀就死了，也该跑
                    || (threat instanceof Player
                        && this.player.distanceToSqr(threat) <= 9.0D
                        && this.player.getHealth() < this.player.getMaxHealth() * 0.4F));
        Entity projectile = this.player.brain() == null ? null
                : this.player.brain().incomingProjectile(10.0D);

        // 被投射物瞄准 -> 珍珠位移躲开
        if (hasPearl && this.pearlCooldown == 0
                && (inDanger || projectile != null) && throwPearl(in, threat != null ? threat : projectile)) {
            return true;
        }
        if (inDanger && retreat(in, threat)) {
            return true;
        }
        return false;
    }

    /** 从高处坠落时朝脚下倒水（只在下落高度足够时才做，避免到处放水） */
    private boolean tryWaterClutch(AiInput in) {
        if (this.waterCooldown > 0) {
            return false;
        }
        if (this.player.onGround() || this.player.isInWater() || this.player.isInLava()) {
            return false;
        }
        // 刚挨打 / 刚被击退打飞（跳劈被顶下来）时阈值放低：这种坠落高度通常已经不多了
        float need = this.player.hurtTime > 0 || this.player.getLastHurtByMob() != null
                ? WATER_CLUTCH_FALL_HURT : WATER_CLUTCH_FALL;
        if (this.player.fallDistance < need) {
            return false;
        }
        int slot = InvUtil.findHotbarSlot(this.player, Items.WATER_BUCKET);
        if (slot < 0) {
            return false;
        }
        if (!this.player.selectSlot("escape", InventoryLock.PRIO_ESCAPE, slot)) {
            return false;
        }
        in.pitch = 89.0F;
        in.useItem = true;
        this.cooldown = 5;
        this.waterCooldown = WATER_COOLDOWN;
        return true;
    }

    /** 触发鞘翅缓冲的最小下落高度（有水桶时水更稳，轮不到它） */
    private static final float ELYTRA_CLUTCH_FALL = 8.0F;

    /** 鞘翅缓冲：没带水桶时从高处坠落，就地穿上鞘翅展开滑翔 */
    private boolean tryElytraClutch(AiInput in) {
        if (this.player.onGround() || this.player.isInWater() || this.player.isInLava()
                || this.player.isFallFlying()) {
            return false;
        }
        if (this.player.fallDistance < ELYTRA_CLUTCH_FALL) {
            return false;
        }
        // 有水桶就不折腾鞘翅：MLG 水更稳
        if (InvUtil.findHotbarSlot(this.player, Items.WATER_BUCKET) >= 0) {
            return false;
        }
        if (!this.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA)
                && !equipElytra()) {
            return false;
        }
        this.player.tryToStartFallFlying();
        if (this.player.isFallFlying()) {
            // 稍微抬头，别一头栽进地里
            in.pitch = -15.0F;
            this.cooldown = 5;
            return true;
        }
        return false;
    }

    /** 把背包里的鞘翅换到胸甲槽（换下来的原装备放回背包） */
    private boolean equipElytra() {
        Inventory inv = this.player.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(Items.ELYTRA)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return false;
        }
        ItemStack elytra = inv.getItem(slot);
        ItemStack old = this.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        this.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, elytra.copy());
        inv.setItem(slot, old);
        return true;
    }

    /**
     * 扔末影珍珠：先起跳（飞得更远），然后朝远离威胁的方向抛出。
     * 视角按抛物线给一个固定的上仰角，原版会自己算飞行轨迹。
     */
    private boolean throwPearl(AiInput in, Entity awayFrom) {
        int slot = InvUtil.findHotbarSlot(this.player, Items.ENDER_PEARL);
        if (slot < 0) {
            return false;
        }
        if (!this.player.selectSlot("escape", InventoryLock.PRIO_ESCAPE, slot)) {
            return false;
        }
        double yaw;
        if (awayFrom == null) {
            yaw = this.player.getYRot();
        } else {
            double dx = this.player.getX() - awayFrom.getX();
            double dz = this.player.getZ() - awayFrom.getZ();
            if (dx * dx + dz * dz < 1.0E-4D) {
                yaw = this.player.getYRot();
            } else {
                yaw = Math.toDegrees(Math.atan2(-dx, dz));
            }
        }
        in.yaw = (float) yaw;
        // 上仰 32° 左右，末影珍珠能飞最远
        in.pitch = -32.0F;
        if (this.player.onGround()) {
            in.jump = true;
        }
        in.useItem = true;
        this.pearlCooldown = PEARL_COOLDOWN;
        this.cooldown = 8;
        return true;
    }

    /** 背对敌人疾跑脱离，顺手举盾 */
    private boolean retreat(AiInput in, Entity threat) {
        if (threat == null) {
            return false;
        }
        double dx = this.player.getX() - threat.getX();
        double dz = this.player.getZ() - threat.getZ();
        if (dx * dx + dz * dz < 1.0E-4D) {
            dx = 1.0D;
        }
        in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        in.pitch = 0.0F;
        in.forward = true;
        in.sprint = true;
        // 高强度才会一边跑一边举盾（菜鸟只会乱跑）
        if (this.player.profile() != null && this.player.profile().levelId >= 3
                && this.player.getOffhandItem().getItem() == Items.SHIELD) {
            in.useOffhand = true;
        }
        this.cooldown = 15;
        return true;
    }

    // ------------------------------------------------------------------

    /** 最近的敌对目标（玩家或怪） */
    public Entity nearestThreat(double range) {
        AABB box = this.player.getBoundingBox().inflate(range);
        List<LivingEntity> list = this.player.level().getEntitiesOfClass(LivingEntity.class, box);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : list) {
            if (e == this.player || !e.isAlive() || this.player.isAlliedTo(e)) {
                continue;
            }
            if (e instanceof Player p) {
                if (p.isCreative() || p.isSpectator() || e instanceof AiPlayer) {
                    continue;
                }
            } else if (!(e instanceof Monster)) {
                continue;
            }
            double d = this.player.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private int hostileCount(double range) {
        AABB box = this.player.getBoundingBox().inflate(range);
        List<LivingEntity> list = this.player.level().getEntitiesOfClass(LivingEntity.class, box);
        int n = 0;
        for (LivingEntity e : list) {
            if (e == this.player || !e.isAlive() || this.player.isAlliedTo(e)) {
                continue;
            }
            if (e instanceof Player p) {
                if (p.isCreative() || p.isSpectator() || e instanceof AiPlayer) {
                    continue;
                }
            } else if (!(e instanceof Monster)) {
                continue;
            }
            n++;
        }
        return n;
    }

    /** 附近有没有飞来的投射物（不含自己扔的） */
    public Projectile nearestProjectile(double range) {
        AABB box = this.player.getBoundingBox().inflate(range);
        List<Projectile> list = this.player.level().getEntitiesOfClass(Projectile.class, box);
        Projectile best = null;
        double bestDist = Double.MAX_VALUE;
        for (Projectile p : list) {
            if (p.getOwner() == this.player) {
                continue;
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