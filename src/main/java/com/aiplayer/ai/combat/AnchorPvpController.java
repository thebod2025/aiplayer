package com.aiplayer.ai.combat;

import com.aiplayer.ai.AiInput;
import com.aiplayer.ai.AiLevel;
import com.aiplayer.ai.path.AStarPathFinder;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.InvUtil;
import com.aiplayer.util.PlaceUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * 锚 PvP（Anchor PvP）：放下重生锚 -> 用萤石充能到 4 格 -> 引爆。
 * <p>
 * 主世界 / 末地使用已充能的重生锚会触发原版爆炸（威力 5，比 TNT 还大），全程走
 * {@code useItemOn} 原版交互，伤害 / 击退 / 方块破坏全部由原版结算，没有作弊成分。
 * <ul>
 *   <li>只在主世界（非下界）使用 —— 下界里重生锚是"回家睡觉"的，不会爆炸；</li>
 *   <li>引爆前先拉开距离 + 举盾，不裸吃自己的爆炸；</li>
 *   <li>残血保命规则与水晶 PvP 一致：没有图腾就收手。</li>
 * </ul>
 */
public class AnchorPvpController {
    private static final Random RNG = new Random();
    private static final double MAX_RANGE = 7.0D;
    /** 引爆时离锚至少这么远（威力 5 的爆炸，3 格内会重伤） */
    private static final double SAFE_BLAST_DISTANCE = 5.5D;
    private static final float MIN_SELF_HEALTH = 7.0F;

    private enum Stage { IDLE, PLACE, CHARGE, DETONATE }

    private final AiPlayer player;
    private Stage stage = Stage.IDLE;
    private BlockPos anchor;
    private int stageTicks;
    private int cooldown;

    public AnchorPvpController(AiPlayer player) {
        this.player = player;
    }

    public void reset() {
        this.stage = Stage.IDLE;
        this.anchor = null;
        this.stageTicks = 0;
        this.player.inventoryLock().release("anchor");
    }

    public boolean isActive() {
        return this.stage != Stage.IDLE;
    }

    public static boolean hasKit(AiPlayer player) {
        return !InvUtil.find(player, Items.RESPAWN_ANCHOR).isEmpty()
                && !InvUtil.find(player, Items.GLOWSTONE).isEmpty();
    }

    /** @return true 表示本 tick 被锚 PvP 占用 */
    public boolean tick(Entity target) {
        AiLevel level = this.player.profile() != null ? this.player.profile().level() : null;
        if (level == null || target == null || !target.isAlive()) {
            reset();
            return false;
        }
        AiInput in = this.player.input();
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        // 下界里重生锚是正常功能，不会爆炸
        if (this.player.level().dimension() == Level.NETHER) {
            return false;
        }
        double dist = Math.sqrt(this.player.distanceToSqr(target));
        if (dist > MAX_RANGE) {
            reset();
            return false;
        }
        if (this.player.getHealth() < MIN_SELF_HEALTH && !hasTotem()) {
            reset();
            this.cooldown = 60;
            return false;
        }
        this.stageTicks++;

        switch (this.stage) {
            case IDLE -> {
                this.anchor = findAnchor(target);
                if (this.anchor == null) {
                    this.cooldown = level.id >= 3 ? 10 : 25;
                    return false;
                }
                this.stage = Stage.PLACE;
                this.stageTicks = 0;
            }
            case PLACE -> {
                BlockState state = this.player.level().getBlockState(this.anchor);
                if (state.getBlock() instanceof RespawnAnchorBlock) {
                    // 已经放好了
                    this.stage = Stage.CHARGE;
                    this.stageTicks = 0;
                    return true;
                }
                if (placeAnchor(in)) {
                    this.stage = Stage.CHARGE;
                    this.stageTicks = 0;
                } else if (this.stageTicks > 30) {
                    reset();
                    this.cooldown = 20;
                }
            }
            case CHARGE -> {
                if (this.anchor == null) {
                    reset();
                    return false;
                }
                BlockState state = this.player.level().getBlockState(this.anchor);
                if (!(state.getBlock() instanceof RespawnAnchorBlock)) {
                    reset();
                    return false;
                }
                int charges = state.getValue(RespawnAnchorBlock.CHARGE);
                if (charges >= RespawnAnchorBlock.MAX_CHARGES) {
                    this.stage = Stage.DETONATE;
                    this.stageTicks = 0;
                    return true;
                }
                if (!chargeAnchor(in)) {
                    if (this.stageTicks > 40) {
                        reset();
                        this.cooldown = 20;
                    }
                    return true;
                }
                // 充到满下一阶段（下一 tick 读到 CHARGE==4 会切过去）
            }
            case DETONATE -> {
                return detonate(in, level, target);
            }
        }
        return this.stage != Stage.IDLE;
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private boolean placeAnchor(AiInput in) {
        int slot = InvUtil.findHotbarSlot(this.player, Items.RESPAWN_ANCHOR);
        if (slot < 0) {
            slot = InvUtil.pullToHotbar(this.player, Items.RESPAWN_ANCHOR);
            if (slot < 0) {
                return false;
            }
        }
        if (!this.player.selectSlot("anchor", InventoryLock.PRIO_CRYSTAL, slot)) {
            return false;
        }
        in.selectedSlot = slot;
        this.player.lookAt(center(this.anchor), 40.0F);
        return PlaceUtil.placeAt(this.player, this.anchor, Direction.UP);
    }

    /** 拿萤石右键锚 -> 原版充能一格 */
    private boolean chargeAnchor(AiInput in) {
        int slot = InvUtil.findHotbarSlot(this.player, Items.GLOWSTONE);
        if (slot < 0) {
            slot = InvUtil.pullToHotbar(this.player, Items.GLOWSTONE);
            if (slot < 0) {
                return false;
            }
        }
        if (!this.player.selectSlot("anchor", InventoryLock.PRIO_CRYSTAL, slot)) {
            return false;
        }
        in.selectedSlot = slot;
        return clickBlock(this.anchor, this.player.getMainHandItem());
    }

    /**
     * 引爆：先拉开距离（回头确认锚还在），到安全距离外右键引爆。
     */
    private boolean detonate(AiInput in, AiLevel level, Entity target) {
        if (this.anchor == null) {
            reset();
            return false;
        }
        BlockState state = this.player.level().getBlockState(this.anchor);
        if (!(state.getBlock() instanceof RespawnAnchorBlock)
                || state.getValue(RespawnAnchorBlock.CHARGE) < RespawnAnchorBlock.MAX_CHARGES) {
            // 锚被拆了 / 没充满：放弃这一轮
            reset();
            this.cooldown = 20;
            return false;
        }
        double selfDist = Math.sqrt(this.player.distanceToSqr(
                this.anchor.getX() + 0.5D, this.anchor.getY() + 0.5D, this.anchor.getZ() + 0.5D));
        // 爆炸半径内必须先退：举盾 + 后撤
        if (selfDist < SAFE_BLAST_DISTANCE) {
            if (this.player.getOffhandItem().getItem() == Items.SHIELD) {
                in.useOffhand = true;
            }
            double dx = this.player.getX() - (this.anchor.getX() + 0.5D);
            double dz = this.player.getZ() - (this.anchor.getZ() + 0.5D);
            if (dx * dx + dz * dz > 1.0E-4D) {
                in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            }
            in.backward = true;
            in.sprint = level.id >= 2;
            if (this.stageTicks > 60) {
                // 退不出去就别硬引爆，把自己也炸进去太亏
                reset();
                this.cooldown = 40;
            }
            return true;
        }
        // 萤石也能引爆（满格时 canBeCharged 为 false，原版直接走 useWithoutItem -> explode）
        this.player.lookAt(center(this.anchor), 50.0F);
        if (clickBlock(this.anchor, this.player.getMainHandItem())) {
            reset();
            this.cooldown = level.id >= 3 ? 6 : 15;
        } else if (this.stageTicks > 80) {
            reset();
        }
        return true;
    }

    /** 对锚所在方块右键一次（原版交互流程） */
    private boolean clickBlock(BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        BlockHitResult hit = new BlockHitResult(
                new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D),
                Direction.UP, pos, false);
        InteractionResult result = this.player.gameMode.useItemOn(
                this.player, this.player.level(), stack, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            this.player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private boolean hasTotem() {
        return this.player.getMainHandItem().getItem() == Items.TOTEM_OF_UNDYING
                || this.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING
                || !InvUtil.find(this.player, Items.TOTEM_OF_UNDYING).isEmpty();
    }

    /** 在目标脚下旁找一格放锚：可替换 + 有支撑 + 在放置距离内 */
    private BlockPos findAnchor(Entity target) {
        BlockPos base = target.blockPosition();
        BlockPos[] candidates = {
                base.relative(Direction.NORTH),
                base.relative(Direction.SOUTH),
                base.relative(Direction.WEST),
                base.relative(Direction.EAST),
                base.below()
        };
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        Level level = this.player.level();
        for (BlockPos pos : candidates) {
            if (!PlaceUtil.isReplaceable(level, pos) || !AStarPathFinder.solid(level, pos.below())) {
                continue;
            }
            double d = this.player.getEyePosition().distanceToSqr(
                    pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D);
            if (d > 19.0D) {
                continue;
            }
            // 越靠近目标越好，但离自己也要够近（放得下）
            double score = d + target.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }
        return best;
    }
}
