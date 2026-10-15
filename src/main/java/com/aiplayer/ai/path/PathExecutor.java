package com.aiplayer.ai.path;

import com.aiplayer.ai.AiInput;
import com.aiplayer.entity.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 沿 A* 路径行走。只使用 forward/jump/sneak/sprint + 视角，
 * 移动完全交给原版物理，因此不会瞬移、不会穿墙。
 */
public final class PathExecutor {
    private final AiPlayer player;
    private BlockPos goal;
    private List<BlockPos> path;
    private int index;
    private int repathIn;
    private int stuckTicks;
    /** 当前这个路径节点已经走了多少个 tick：太久没到就跳过，避免被墙角卡死在原地打转 */
    private int nodeTicks;
    /** 连续多少次 tick 一条路都算不出来：超过上限就放弃这次寻路 */
    private int failTicks;
    /** 目标本身不可达时是否已经把它换成"紧贴目标的落脚点" */
    private boolean goalAdjusted;
    /** 进行中的 A* 寻路会话：每 tick 分片推进，算完作废（null = 没有在算） */
    private AStarPathFinder.Session pathSession;
    /** 每 tick 分给寻路的毫秒预算：多 AI 同屏时各自的寻路开销摊进自己的 tick，不挤主线程 */
    private static final long PATH_BUDGET_MILLIS = 2L;
    /** 目标偏移超过这个值（切比雪夫）才作废重开会话：跟随移动目标时不必每 tick 重算 */
    private static final int GOAL_DRIFT = 2;
    private Vec3 lastPos = Vec3.ZERO;
    private boolean allowDig = true;
    /** 是否允许往脚下挖一格（竖直下探）：只有挖矿 / 下矿洞这类任务才由上层打开 */
    private boolean allowDownDig;

    public PathExecutor(AiPlayer player) {
        this.player = player;
    }

    public void setAllowDig(boolean allow) {
        this.allowDig = allow;
    }

    /**
     * 往脚下挖一格（竖直下探）。
     * <p>
     * 默认关：平时走路 / 追敌时开着，A* 会为了抄近路直接在玩家地形上刨坑。
     * 只有真正要往下钻的任务（挖矿发现脚下的矿脉 / 下矿洞）才打开。
     */
    public void setAllowDownDig(boolean allow) {
        this.allowDownDig = allow;
    }

    public boolean hasGoal() {
        return this.goal != null;
    }

    public BlockPos goal() {
        return this.goal;
    }

    public void setGoal(BlockPos goal) {
        if (goal == null) {
            clear();
            return;
        }
        BlockPos g = goal.immutable();
        // 目标只是小幅挪动（跟随移动的玩家）：保留旧路径和会话，AI 沿旧路继续走，
        // 后台会话 / 下次重算自然会把新位置算进去。以前目标每 tick 变一点就把路径全清，
        // 触发每 tick 一次全量 A* —— 跟随时一卡一卡还烧主线程。
        if (this.goal == null || drifts(this.goal, g)) {
            this.path = null;
            this.index = 0;
            this.repathIn = 0;
            this.nodeTicks = 0;
            this.failTicks = 0;
            this.goalAdjusted = false;
        }
        this.goal = g;
    }

    public void clear() {
        this.goal = null;
        this.path = null;
        this.index = 0;
        this.stuckTicks = 0;
        this.nodeTicks = 0;
        this.failTicks = 0;
        this.goalAdjusted = false;
        this.pathSession = null;
    }

    /** 两点任一轴（切比雪夫）偏移超过 {@value #GOAL_DRIFT} 格 */
    private static boolean drifts(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) > GOAL_DRIFT
                || Math.abs(a.getY() - b.getY()) > GOAL_DRIFT
                || Math.abs(a.getZ() - b.getZ()) > GOAL_DRIFT;
    }

    /** @return true 表示已经到达目标（或者已经放弃：走不到就不要再死等） */
    public boolean tick() {
        if (this.goal == null) {
            return true;
        }
        Vec3 pos = this.player.position();
        if (pos.distanceToSqr(this.lastPos) < 0.0025D) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = 0;
        }
        this.lastPos = pos;

        BlockPos feet = this.player.blockPosition();
        // 到达判定必须和 A* 的终点判定一致（切比雪夫距离 <= 1）。
        // 之前用"水平欧氏距离 <= 1.2"，而 A* 允许最后落在斜对角一格（1.41 格），
        // 于是 AI 站在斜对角、判定为"没到"，再重算还是同一条路 —— 表现就是走到墙角前反复顶墙。
        if (Math.abs(feet.getX() - this.goal.getX()) <= 1
                && Math.abs(feet.getZ() - this.goal.getZ()) <= 1
                && Math.abs(feet.getY() - this.goal.getY()) <= 1) {
            clear();
            return true;
        }
        if (this.path == null || this.index >= this.path.size() || --this.repathIn <= 0 || this.stuckTicks > 25) {
            this.repathIn = 30;
            this.stuckTicks = 0;
            // 没有在算的会话 / 目标挪远了 / AI 离会话起点太远（旧起点已失真）才重开会话
            if (this.pathSession == null || drifts(this.pathSession.goal(), this.goal)
                    || this.pathSession.start().distSqr(feet) > 6.25D) {
                this.pathSession = AStarPathFinder.start(this.player.level(), feet, this.goal, this.allowDig, this.allowDownDig);
            }
        }
        // 分片推进：每 tick 只算 2ms，算不完下个 tick 接着算
        if (this.pathSession != null) {
            stepSession(feet);
        }
        if (this.path == null || this.path.isEmpty() || this.index >= this.path.size()) {
            // 一条路都算不出来：给 A* 和"贴近目标"的兜底一点时间，实在不行就放弃。
            if (++this.failTicks > 40) {
                clear();
                return true;   // 当作"到了"处理，让上层清掉目标，别让 AI 永远杵在死胡同里
            }
            return false;
        }
        this.failTicks = 0;
        step();
        return false;
    }

    /** 会话算完后把结果装进执行器；没算完就什么都不做（旧路径继续走） */
    private void stepSession(BlockPos feet) {
        AStarPathFinder.Session session = this.pathSession;
        if (drifts(session.goal(), this.goal)) {
            this.pathSession = null;   // 目标被换远了，旧会话作废
            return;
        }
        session.step(PATH_BUDGET_MILLIS);
        if (!session.done()) {
            return;
        }
        this.pathSession = null;
        List<BlockPos> found = session.result();
        if ((found == null || found.isEmpty()) && !this.goalAdjusted) {
            // 目标本身到不了（在方块里 / 在死胡同的另一侧）：退一步，改去"紧贴目标的可站立点"，
            // AI 至少能走到目标附近，而不是站在死胡同口一遍遍重算同一条不存在的路。
            this.goalAdjusted = true;
            BlockPos fallback = AStarPathFinder.nearestStandable(this.player.level(), this.goal, 3);
            if (fallback != null && !fallback.equals(this.goal)) {
                this.goal = fallback.immutable();
                this.pathSession = AStarPathFinder.start(this.player.level(), feet, this.goal, this.allowDig, this.allowDownDig);
                return;
            }
        }
        if (found == null || found.isEmpty()) {
            this.path = null;
            this.index = 0;
            this.nodeTicks = 0;
            return;
        }
        this.path = found;
        this.index = 0;
        this.nodeTicks = 0;
        this.failTicks = 0;
        this.goalAdjusted = true;   // 已经能走到目标附近了，别再降级目标
    }

    private void step() {
        var in = this.player.input();
        BlockPos next = this.path.get(this.index);
        // 下一个路径节点是被方块占着的（A* 开了"挖方块"才会算出来的节点）：先就地挖穿再走。
        // 以前执行器只会照着走，走到那儿就顶墙站着 —— 这是"AI 老是走到死胡同"的一大来源。
        if (digThrough(next, in)) {
            this.nodeTicks = 0;   // 挖掘期间不算"这个节点磨太久"
            return;
        }
        double nx = next.getX() + 0.5D;
        double nz = next.getZ() + 0.5D;
        double px = this.player.getX();
        double pz = this.player.getZ();
        double ddx = nx - px;
        double ddz = nz - pz;
        double d2 = ddx * ddx + ddz * ddz;

        // 已经贴到当前节点 -> 先推进 index 再转身。这样拐角处 AI 直接朝向下一格走，
        // 不会先走到节点正中心、急停、甩头 90 度再走（看起来一顿一顿像机器人）。
        // 之前是先转身后推进，被墙角的斜方块卡住时 index 永远不前进，就一直原地打转。
        if (d2 < 0.55D || ++this.nodeTicks > 20) {
            this.index++;
            this.nodeTicks = 0;
            if (this.index >= this.path.size()) {
                return;   // 下一个 tick 会做到达判定 / 重算
            }
            next = this.path.get(this.index);
            nx = next.getX() + 0.5D;
            nz = next.getZ() + 0.5D;
            ddx = nx - px;
            ddz = nz - pz;
            d2 = ddx * ddx + ddz * ddz;
        }

        // 转身：lookAt 会把“限速后的朝向”直接写进 in.yaw / in.pitch（同一个 AiInput 对象）。
        // 这里绝不能再覆盖 in.yaw —— 之前又赋了一次 player.getYRot()（本 tick 尚未应用的旧朝向），
        // 结果朝向永远不变，AI 就朝着旧方向一路走反了。
        this.player.lookAt(new Vec3(nx, next.getY() + 1.0D, nz), 18.0F);

        // 朝向和目标差太多时先站住转身，别横着 / 倒着"平移"过去 —— 这是走路看起来
        // 最别扭的来源（AI 侧着身子甚至面朝后倒退着走）。转过来再前进。
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(-ddx, ddz)));
        float yawErr = Math.abs(AiPlayer.wrapDegrees(desiredYaw - this.player.getYRot()));
        in.forward = yawErr < 60.0F;

        double distGoal = Math.abs(this.goal.getX() - this.player.getX()) + Math.abs(this.goal.getZ() - this.player.getZ());
        // 疾跑加迟滞：阈值附近不再每 tick 开 / 关，避免速度忽快忽慢（看起来一顿一顿的）
        if (distGoal > 7.0D) {
            in.sprint = true;
        } else if (distGoal < 5.0D) {
            in.sprint = false;
        } else {
            in.sprint = this.player.isSprinting();
        }
        // 跳跃只在前方一格且已经贴近时才起跳 —— 以前离台阶还有 2 格多就开始跳，
        // 跳早了撞在台阶侧面上，看起来就是"不停蹦跳"（not a bouncing zombie）。
        int dy = next.getY() - this.player.blockPosition().getY();
        if (dy > 0 && this.player.onGround() && d2 < 1.44D) {
            in.jump = true;
        }
        if (this.player.isInWater()) {
            in.jump = true;
        }
        if (this.player.onClimbable()) {
            in.jump = true;
        }
        if (this.stuckTicks > 8) {
            in.jump = this.player.onGround();
        }
        if (this.player.isShiftKeyDown()) {
            in.sneak = false;
        }
    }

    /**
     * 路径节点被方块挡着时，就地把它挖穿（脚部那格优先，再挖头部那格）。
     *
     * @return true 表示这一 tick 在原地挖，不要往前走
     */
    private boolean digThrough(BlockPos next, AiInput in) {
        if (!this.allowDig) {
            return false;   // 平时走路不拆方块（免得把玩家搭的东西挖了），交给节点超时跳过
        }
        Level level = this.player.level();
        // 注意用 solid 而不是 isAir 判断：水里那格不是空气，但绝对不能当成"要挖的方块"去挖。
        BlockPos target;
        if (AStarPathFinder.solid(level, next)) {
            // 半砖 / 雪层这类矮台阶：碰撞盒是实心的，但玩家直接走上去就行，不能当挡路方块挖掉
            if (AStarPathFinder.canStand(level, next)) {
                return false;
            }
            target = next;
        } else if (AStarPathFinder.solid(level, next.above())) {
            target = next.above();
        } else {
            return false;   // 已经挖穿了，正常走
        }
        if (!this.player.blockPosition().closerThan(target, 4.5D)) {
            return false;   // 还够不着，先走近点
        }
        BlockState state = level.getBlockState(target);
        if (state.getDestroySpeed(level, target) < 0.0F) {
            return false;   // 挖不动（基岩 / 屏障之类）
        }
        if (this.player.brain() != null) {
            this.player.brain().equipBestTool(state);   // 别空手抠石头
        }
        this.player.lookAt(new Vec3(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D), 30.0F);
        in.mining = true;
        in.mineTarget = target;
        return true;
    }
}