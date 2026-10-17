package com.aiplayer.util;

import com.aiplayer.ai.path.AStarPathFinder;
import com.aiplayer.entity.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 方块放置工具。
 * <p>
 * 关键点：搭路时目标方块在脚下/身后，靠视线射线是打不到的，所以这里直接构造
 * {@link BlockHitResult}（真实玩家点击某个方块的某个面），再交给 {@code ServerPlayerGameMode.useItemOn}
 * 走原版放置流程 —— 原版会自己做碰撞、替换、朝向校验，因此不会放置失败作弊化、也不会凭空出现方块。
 */
public final class PlaceUtil {
    private PlaceUtil() {
    }

    /** 该位置是否可以被方块替换（空气或没有碰撞体积） */
    public static boolean isReplaceable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** 手上是否拿着可以放置的方块 */
    public static boolean holdingBlock(AiPlayer player) {
        ItemStack stack = player.getMainHandItem();
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    /**
     * 在 target 处放一个方块。会自动寻找相邻的可点击支撑方块，并挑一个距离最近/最合理的面。
     *
     * @param preferredFace 优先使用的面（搭路时通常是“来路方向”）
     * @return 是否成功放置
     */
    public static boolean placeAt(AiPlayer player, BlockPos target, Direction preferredFace) {
        return place(player, target, preferredFace, true);
    }

    /** 放置任意“可对空气使用”的物品（末地水晶、船、盔甲架等） */
    public static boolean placeItemAt(AiPlayer player, BlockPos target, Direction preferredFace) {
        return place(player, target, preferredFace, false);
    }

    public static boolean place(AiPlayer player, BlockPos target, Direction preferredFace, boolean mustBeBlock) {
        Level level = player.level();
        if (!isReplaceable(level, target)) {
            return false;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty() || mustBeBlock && !(stack.getItem() instanceof BlockItem)) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        Direction bestFace = null;
        Vec3 bestPoint = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Direction face : order(preferredFace)) {
            // support.relative(face) == target
            BlockPos support = target.relative(face.getOpposite());
            if (!AStarPathFinder.solid(level, support)) {
                continue;
            }
            Vec3 point = new Vec3(
                    support.getX() + 0.5D + face.getStepX() * 0.5D,
                    support.getY() + 0.5D + face.getStepY() * 0.5D,
                    support.getZ() + 0.5D + face.getStepZ() * 0.5D);
            double dist = eye.distanceTo(point);
            if (dist > 4.4D) {
                continue;
            }
            double score = 10.0D - dist;
            if (face == preferredFace) {
                score += 6.0D;
            }
            if (face == Direction.UP) {
                score += 2.0D;
            }
            if (score > bestScore) {
                bestScore = score;
                bestFace = face;
                bestPoint = point;
            }
        }
        if (bestFace == null) {
            return false;
        }
        BlockHitResult hit = new BlockHitResult(bestPoint, bestFace, target.relative(bestFace.getOpposite()), false);
        InteractionResult result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    /** 面搜索顺序：优先面 -> 上 -> 四个侧面 -> 下 */
    private static Direction[] order(Direction preferred) {
        Direction[] all = {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        Direction[] out = new Direction[all.length];
        int i = 0;
        if (preferred != null) {
            out[i++] = preferred;
        }
        for (Direction d : all) {
            if (i >= out.length) {
                break;
            }
            if (d == preferred) {
                continue;
            }
            out[i++] = d;
        }
        return out;
    }
}