package com.aiplayer.entity;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.ai.AiBrain;
import com.aiplayer.ai.AiInput;
import com.aiplayer.config.AiProfile;
import com.aiplayer.util.BlockBreaker;
import com.aiplayer.util.InventoryLock;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * AI 玩家实体。
 * <p>
 * 关键设计：所有移动都由原版物理驱动 —— 我们只写入 {@code xxa/zza}、朝向、潜行/疾跑/跳跃等
 * “真实玩家输入”，然后调用 {@code doTick()} 让 {@code LivingEntity.aiStep() -> travel()}
 * 完成加速度、摩擦与碰撞。因此不会穿墙、不会瞬移、速度不会超过正常玩家。
 */
public class AiPlayer extends ServerPlayer {
    private final AiInput input = new AiInput();
    private final BlockBreaker breaker = new BlockBreaker();
    /** 快捷栏 / 手部槽位的统一占用锁，避免多个控制器互相抢物品栏 */
    private final InventoryLock inventoryLock = new InventoryLock();

    private AiBrain brain;
    private AiProfile profile;
    private AiPlayerManager manager;
    private int brainErrorCooldown;

    /** 防重复物理：同一服务器 tick 内 doTick() 只允许执行一次 */
    private boolean physicsDoneThisTick;

    /** 死亡瞬间（掉落之前）抓取的装备，由管理器在重生后还原 */
    private GearSnapshot deathGear;

    public AiPlayer(MinecraftServer server, ServerLevel level, GameProfile gameProfile, ClientInformation info) {
        super(server, level, gameProfile, info);
    }

    /**
     * 让 AI 的近战自带击退。
     * <p>
     * 注意：原版 {@code Player.createAttributes()} 的属性表里<b>没有</b> {@code ATTACK_KNOCKBACK}
     * （只有 ATTACK_DAMAGE / MOVEMENT_SPEED / LUCK 等），所以在构造器里
     * {@code getAttribute(ATTACK_KNOCKBACK)} 拿到的是 null，那条 {@code setBaseValue(1.0)}
     * 从来没有生效过 —— 这就是"AI 打人还是没有击退"的真正原因。
     * <p>
     * 这里覆写击退计算：返回 0.5 相当于属性值 1.0（原版公式是 属性值 / 2），
     * 等于每次命中都带一次接近冲刺攻击的击退，手感才像真人。
     * <p>
     * 另外覆写 {@link #attack} 在原版流程之后手动补一次击退 —— 26.2 里
     * ServerPlayer.attack() 内部的击退调用路径可能有变化，双保险。
     */
    @Override
    protected float getKnockback(Entity target, DamageSource source) {
        // 0.5 相当于 ATTACK_KNOCKBACK 属性 1.0；super 那部分再叠上武器"击退"附魔的加成
        return 0.8F + super.getKnockback(target, source);
    }

    /**
     * 26.2 里 ServerPlayer.attack() 的击退调用路径不确定是否还走 getKnockback，
     * 这里直接覆写 attack，原版流程跑完再手动给目标加一次击退 —— 双保险。
     * LivingEntity.knockback(power, xd, zd) 是 public，方向按"目标 - 攻击者"算。
     */
    @Override
    public void attack(net.minecraft.world.entity.Entity target) {
        super.attack(target);
        // 原版流程跑完再手动补一次击退 —— 26.2 里 knockback 签名变复杂，直接改 deltaMovement
        if (target instanceof net.minecraft.world.entity.LivingEntity le) {
            double dx = le.getX() - this.getX();
            double dz = le.getZ() - this.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0E-6D) {
                le.setDeltaMovement(le.getDeltaMovement().x + dx / len * 0.6D,
                        le.getDeltaMovement().y,
                        le.getDeltaMovement().z + dz / len * 0.6D);
                le.hurtMarked = true;   // 让原版物理处理击退效果
            }
        }
    }

    public AiInput input() {
        return this.input;
    }

    public BlockBreaker breaker() {
        return this.breaker;
    }

    public InventoryLock inventoryLock() {
        return this.inventoryLock;
    }

    /**
     * 抢占式切换快捷栏槽位。
     *
     * @param owner 控制器名（food / crystal / bridge / combat / equip ...）
     * @param prio  优先级，数字大的可以抢占数字小的
     * @return true 表示槽位已切好
     */
    public boolean selectSlot(String owner, int prio, int slot) {
        if (slot < 0 || slot >= Inventory.SELECTION_SIZE) {
            return false;
        }
        if (!this.inventoryLock.acquire(owner, prio)) {
            return false;
        }
        this.getInventory().setSelectedSlot(slot);
        return true;
    }

    public AiProfile profile() {
        return this.profile;
    }

    public AiBrain brain() {
        return this.brain;
    }

    public AiPlayerManager manager() {
        return this.manager;
    }

    public ServerLevel serverLevel() {
        return (ServerLevel) this.level();
    }

    public void attach(AiPlayerManager manager, AiProfile profile) {
        this.manager = manager;
        this.profile = profile;
        this.brain = new AiBrain(this, manager, profile);
    }

    // ------------------------------------------------------------------
    // 死亡 / 复活
    // ------------------------------------------------------------------

    /**
     * 必须在 {@code super.die()} 之前抓装备快照：原版死亡流程会把背包和护甲全部掉到地上，
     * 之后再抓就只能拿到空背包。{@link AiPlayerManager} 会在重生时把快照还原。
     */
    @Override
    public void die(DamageSource cause) {
        if (this.deathGear == null) {
            this.deathGear = new GearSnapshot(this);
        }
        super.die(cause);
    }

    /** 取走死亡快照（只取一次）；没死过则返回 null */
    public GearSnapshot takeDeathGear() {
        GearSnapshot snapshot = this.deathGear;
        this.deathGear = null;
        return snapshot;
    }

    /**
     * 关键：不能让原版把死亡实体移除。
     * <p>
     * {@code LivingEntity.tickDeath()} 会在 {@code deathTime >= 20} 时执行
     * {@code remove(KILLED)} 并停止再调用 {@code tickDeath}，于是 {@code deathTime} 永远停在 20，
     * {@link AiPlayerManager} 里“{@code deathTime > 40} 就自动复活”的判定永远不会成立 —— AI 会一直躺在地上。
     * 这里只保留死亡动画和事件，实体不删，交给管理器重建。
     */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime == 20 && !this.level().isClientSide()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
        }
    }

    /** 装备快照：背包全部槽位 + 四件护甲 + 副手 */
    public static final class GearSnapshot {
        private static final EquipmentSlot[] SLOTS = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};

        private final ItemStack[] main;
        private final ItemStack[] equipment = new ItemStack[SLOTS.length];

        GearSnapshot(AiPlayer ai) {
            Inventory inv = ai.getInventory();
            this.main = new ItemStack[inv.getContainerSize()];
            for (int i = 0; i < this.main.length; i++) {
                this.main[i] = inv.getItem(i).copy();
            }
            for (int i = 0; i < SLOTS.length; i++) {
                this.equipment[i] = ai.getItemBySlot(SLOTS[i]).copy();
            }
        }

        public void restore(AiPlayer ai) {
            Inventory inv = ai.getInventory();
            int n = Math.min(inv.getContainerSize(), this.main.length);
            for (int i = 0; i < n; i++) {
                inv.setItem(i, this.main[i]);
            }
            for (int i = 0; i < SLOTS.length; i++) {
                ai.setItemSlot(SLOTS[i], this.equipment[i]);
            }
        }

        /** 空快照：说明死的时候身上什么都没有，重生后需要重新发一套基础装备 */
        public boolean isEmpty() {
            for (ItemStack stack : this.main) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            for (ItemStack stack : this.equipment) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    /**
     * 每服务器 tick 由管理器调用一次，重置物理防重标记。
     * <p>
     * 26.2 的调用关系（已用字节码确认）：
     * <ul>
     *   <li>{@code ServerPlayer.tick()} <b>不</b>调用 {@code super.tick()}，
     *       它只做服务器维护：{@code gameMode.tick()}、容器 {@code broadcastChanges()}、
     *       无敌帧倒计时、{@code wardenSpawnTracker} 等；</li>
     *   <li>物理在 {@code ServerPlayer.doTick()} 里（{@code doTick() -> Player.tick() -> LivingEntity.tick() -> aiStep()}），
     *       正常玩家由 {@code ServerGamePacketListenerImpl.tick()} 调用；我们的假连接不会被 tick，
     *       所以必须自己调一次。</li>
     * </ul>
     * 因此 {doTick + super.tick} 不会重复物理；再加一个每 tick 一次的标记做兜底。
     */
    public void resetPhysicsGuard() {
        this.physicsDoneThisTick = false;
    }

    @Override
    public void tick() {
        if (this.isRemoved()) {
            return;
        }
        AiBrain b = this.brain;
        // 死亡后不要再做决策：否则躺在地上还在放方块 / 挥刀，等复活期间也会乱跑
        if (b != null && this.isAlive()) {
            try {
                b.tick();
            } catch (Throwable t) {
                if (this.brainErrorCooldown-- <= 0) {
                    this.brainErrorCooldown = 100;
                    AiPlayerMod.LOGGER.error("[aiplayer] AI {} 决策异常", this.getScoreboardName(), t);
                }
            }
        }
        try {
            this.applyAiInput();
            // 物理：doTick() -> Player.tick() -> LivingEntity.tick() -> aiStep() -> travel()
            if (!this.physicsDoneThisTick) {
                this.physicsDoneThisTick = true;
                this.doTick();
                this.applyVerticalAssist();
            }
        } catch (Throwable t) {
            if (this.brainErrorCooldown-- <= 0) {
                this.brainErrorCooldown = 100;
                AiPlayerMod.LOGGER.error("[aiplayer] AI {} 物理异常", this.getScoreboardName(), t);
            }
        }
        try {
            // ServerPlayer 自身的维护逻辑（gamemode tick / 容器同步 / 无敌帧等）
            super.tick();
        } catch (Throwable t) {
            if (this.brainErrorCooldown-- <= 0) {
                this.brainErrorCooldown = 100;
                AiPlayerMod.LOGGER.error("[aiplayer] AI {} tick 异常", this.getScoreboardName(), t);
            }
        }
    }

    private void applyAiInput() {
        AiInput in = this.input;
        float strafe = 0.0F;
        float fwd = 0.0F;
        if (in.left) {
            strafe += 1.0F;
        }
        if (in.right) {
            strafe -= 1.0F;
        }
        if (in.forward) {
            fwd += 1.0F;
        }
        if (in.backward) {
            fwd -= 1.0F;
        }
        float len = (float) Math.sqrt(strafe * strafe + fwd * fwd);
        if (len > 1.0F) {
            strafe /= len;
            fwd /= len;
        }
        if (!this.isAlive()) {
            strafe = 0.0F;
            fwd = 0.0F;
        }
        // 受击硬直：暂停移动/跳跃输入，别用 AI 的移动逻辑把击退速度顶掉
        //（之前 AI 被打完全不后退，就是这里每 tick 继续推输入吃掉了 knockback 位移）
        if (this.hurtTime > 0) {
            strafe = 0.0F;
            fwd = 0.0F;
            in.jump = false;
            in.sprint = false;
        }
        this.xxa = strafe;
        this.zza = fwd;

        this.setShiftKeyDown(in.sneak);
        this.setSprinting(in.sprint && !in.sneak && fwd > 1.0E-4F && !this.isUsingItem());
        this.setYRot(in.yaw);
        this.setXRot(Math.max(-90.0F, Math.min(90.0F, in.pitch)));
        this.setYHeadRot(in.yaw);
        this.setLastClientInput(in.toVanilla());

        // 原版字段：游泳上浮 / 水中姿态都依赖它
        this.jumping = in.jump;

        boolean inWater = this.isInWater();
        this.setSwimming(inWater && !this.onGround() && (in.moving() || this.isSwimming()));

        if (in.jump && this.onGround() && !inWater) {
            this.jumpFromGround();
        }
        if (in.selectedSlot >= 0 && in.selectedSlot < Inventory.SELECTION_SIZE) {
            this.getInventory().setSelectedSlot(in.selectedSlot);
        }
        if (in.swapOffhand) {
            this.swapOffhand();
        }
        this.executeIntents();
    }

    /** 物理之后修正垂直速度：爬梯子 / 水中上浮（数值与原版一致） */
    private void applyVerticalAssist() {
        AiInput in = this.input;
        Vec3 dm = this.getDeltaMovement();
        if (this.onClimbable()) {
            if (in.sneak) {
                if (dm.y > 0.0D) {
                    this.setDeltaMovement(dm.x, 0.0D, dm.z);
                }
            } else if (in.forward || in.jump) {
                // 原版梯子上升速度 0.1176 格/tick
                if (dm.y < 0.1176D) {
                    this.setDeltaMovement(dm.x, 0.1176D, dm.z);
                }
            }
            return;
        }
        if (in.jump && this.isInWater() && dm.y < 0.3D) {
            this.setDeltaMovement(dm.x, dm.y + 0.04D, dm.z);
        }
    }

    // ------------------------------------------------------------------
    // 动作（由 AiInput 上的“意图”驱动，也可被外部 API 直接调用）
    // ------------------------------------------------------------------

    private void executeIntents() {
        AiInput in = this.input;
        if (in.useItem || in.useItemHold) {
            this.useItemRaycast();
        }
        if (in.releaseUse) {
            this.releaseUsingItemNow();
        }
        if (in.useOffhand && !this.isUsingItem()) {
            this.gameMode.useItem(this, this.level(), this.getItemInHand(InteractionHand.OFF_HAND), InteractionHand.OFF_HAND);
        }
        if (in.releaseOffhandUse && this.isUsingItem()) {
            this.releaseUsingItemNow();
        }
        if (in.attackEntity) {
            Entity target = in.attackTarget;
            if (target == null || !target.isAlive()
                    || !this.isWithinEntityInteractionRange(target, 0.0D)) {
                target = this.pickEntity(3.0D);
            }
            if (target != null) {
                this.attack(target);
                this.swing(InteractionHand.MAIN_HAND);
            }
        }
        if (in.mining) {
            // 优先用大脑指定的方块：每 tick 重新用视线射线选块的话，AI 转身过程中会不断换块，
            // 挖掘进度每次都被清零 —— 看起来就是"一直点左键但方块挖不掉"。
            BlockPos pos = in.mineTarget;
            if (pos == null) {
                BlockHitResult hit = this.pickBlock(4.5D);
                pos = hit == null ? null : hit.getBlockPos();
            }
            this.breaker.tick(this, pos);
        } else if (this.breaker.isMining()) {
            this.breaker.abort(this);
        }
    }

    public void swapOffhand() {
        Inventory inv = this.getInventory();
        ItemStack main = inv.getSelectedItem();
        ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
        inv.setSelectedItem(off);
        inv.setItem(Inventory.SLOT_OFFHAND, main);
    }

    public void releaseUsingItemNow() {
        ItemStack use = this.getUseItem();
        if (!use.isEmpty()) {
            use.releaseUsing(this.level(), this, this.getUseItemRemainingTicks());
        }
        this.stopUsingItem();
    }

    public void useItemRaycast() {
        InteractionHand hand = InteractionHand.MAIN_HAND;
        ItemStack stack = this.getItemInHand(hand);
        if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
            BlockHitResult hit = this.pickBlock(4.5D);
            if (hit != null) {
                InteractionResult result = this.gameMode.useItemOn(this, this.level(), stack, hand, hit);
                if (result.consumesAction()) {
                    this.swing(hand);
                    return;
                }
            }
        }
        this.gameMode.useItem(this, this.level(), this.getItemInHand(hand), hand);
        this.swing(hand);
    }

    public BlockHitResult pickBlock(double reach) {
        Vec3 eye = this.getEyePosition();
        Vec3 end = eye.add(this.getLookAngle().scale(reach));
        return this.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this));
    }

    /** 找出准星最近的可攻击实体 */
    public Entity pickEntity(double reach) {
        Vec3 eye = this.getEyePosition();
        Vec3 look = this.getLookAngle();
        Vec3 end = eye.add(look.scale(reach));
        AABB box = this.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0D);
        List<Entity> candidates = this.level().getEntities(this, box, e -> e != this && e.isAlive() && e.isPickable());
        Entity best = null;
        double bestDot = 0.90D;
        for (Entity e : candidates) {
            Vec3 center = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            Vec3 to = center.subtract(eye);
            double dist = to.length();
            if (dist > reach || dist < 1.0E-4D) {
                continue;
            }
            double dot = to.normalize().dot(look.normalize());
            if (dot > bestDot) {
                bestDot = dot;
                best = e;
            }
        }
        return best;
    }

    /** 视线方向对准某个点（带转身速度限制，不会瞬间 180 度） */
    public void lookAt(Vec3 point, float maxTurnDeg) {
        Vec3 eye = this.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx))) - 90.0F;
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        this.input.yaw = turnTowards(this.getYRot(), targetYaw, maxTurnDeg);
        this.input.pitch = turnTowards(this.getXRot(), targetPitch, maxTurnDeg);
    }

    public void lookAt(double x, double y, double z, float maxTurnDeg) {
        this.lookAt(new Vec3(x, y, z), maxTurnDeg);
    }

    private static float turnTowards(float current, float target, float maxDelta) {
        float delta = wrapDegrees(target - current);
        if (delta > maxDelta) {
            delta = maxDelta;
        } else if (delta < -maxDelta) {
            delta = -maxDelta;
        }
        return current + delta;
    }

    public static float wrapDegrees(float degrees) {
        float d = degrees % 360.0F;
        if (d >= 180.0F) {
            d -= 360.0F;
        }
        if (d < -180.0F) {
            d += 360.0F;
        }
        return d;
    }
}