package com.aiplayer.ai.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 类 Baritone 的 A* 寻路：只走真实玩家能走的路（走、跳 1 格、掉最多 3 格、爬梯子），
 * 可选“挖方块”，并给挖方块额外代价。不会产生穿墙/瞬移路径。
 */
public final class AStarPathFinder {
    public static final int MAX_RANGE = 48;
    /** 节点上限：复杂地形（洞穴 / 树林）需要的节点远多于平原 */
    private static final int MAX_NODES = 24000;
    private static final double DIG_COST = 6.0D;
    /** 竖直下挖一格的代价：比侧向挖更贵，能绕路就绕，只有真要下探时才刨脚下 */
    private static final double DIG_DOWN_COST = 8.0D;
    /** 游泳一格额外代价：比走路贵，AI 能走陆路就不会特意下水 */
    private static final double SWIM_COST = 2.0D;
    /** 贴墙惩罚：斜走要贴着墙角蹭过去时加一点代价 —— 让路径走通道中央而不是擦着墙角走 */
    private static final double WALL_HUG_COST = 0.2D;
    /** 玩家能自动迈上去的最大高度（原版 maxUpStep）：矮台阶走上去，不用跳 */
    private static final double MAX_AUTO_STEP = 0.6D;

    private AStarPathFinder() {
    }

    /**
     * 危险地形：岩浆 / 火 / 仙人掌 / 浆果丛 / 细雪 / 蜘蛛网 / 岩浆块上方。
     * 寻路直接绕开 —— 以前 A* 认为岩浆池上"能站"，AI 会一脚踩进岩浆里。
     */
    private static boolean isHazard(Level level, BlockPos feet) {
        for (BlockPos p : new BlockPos[]{feet.below(), feet, feet.above()}) {
            var block = level.getBlockState(p).getBlock();
            if (block instanceof net.minecraft.world.level.block.BaseFireBlock
                    || block instanceof net.minecraft.world.level.block.CactusBlock
                    || block instanceof net.minecraft.world.level.block.SweetBerryBushBlock
                    || block instanceof net.minecraft.world.level.block.PowderSnowBlock
                    || block instanceof net.minecraft.world.level.block.WebBlock
                    || block instanceof net.minecraft.world.level.block.MagmaBlock) {
                return true;
            }
            if (level.getFluidState(p).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private record Node(BlockPos pos, double g, double f, Node parent) {
    }

    /**
     * 开始一次可分片续算的寻路会话。
     * <p>
     * 以前 {@code find()} 一次性算到底（单次最多 30ms），多 AI 同屏重算时会把服务器主线程
     * 挤出明显卡顿。现在改成会话对象：每 tick 调 {@link Session#step(long)} 只算 1~2ms，
     * 算不完下一 tick 接着算 —— 多个 AI 的寻路开销被摊到各自的 tick 里，互不阻塞。
     */
    public static Session start(Level level, BlockPos start, BlockPos goal, boolean allowDig, boolean allowDownDig) {
        return new Session(level, start, goal, allowDig, allowDownDig);
    }

    /** 一次 A* 寻路会话：可跨多个 tick 分片推进，算完一次后作废 */
    public static final class Session {
        private final Level level;
        private final BlockPos start;
        private final BlockPos goal;
        private final boolean allowDig;
        /** 是否允许往脚下挖一格（竖直下探） */
        private final boolean allowDownDig;
        private final Map<BlockPos, Double> best = new HashMap<>();
        private final Set<BlockPos> closed = new HashSet<>();
        private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        private int expanded;
        private boolean done;
        private List<BlockPos> result;

        private Session(Level level, BlockPos start, BlockPos goal, boolean allowDig, boolean allowDownDig) {
            this.level = level;
            this.start = start.immutable();
            this.goal = goal.immutable();
            this.allowDig = allowDig;
            this.allowDownDig = allowDownDig;
            if (start.distManhattan(goal) > MAX_RANGE * 3) {
                // 超出射程：直接判死，别浪费节点
                this.done = true;
                this.result = null;
            } else {
                Node root = new Node(start.immutable(), 0.0D, heuristic(start, goal), null);
                this.open.add(root);
                this.best.put(root.pos, 0.0D);
            }
        }

        public BlockPos start() {
            return this.start;
        }

        public BlockPos goal() {
            return this.goal;
        }

        /** @return true 表示会话已经算完（找到路 / 确认没路 / 节点耗尽），result 可用 */
        public boolean done() {
            return this.done;
        }

        /** 算完后的路径；没找到路时为 null */
        public List<BlockPos> result() {
            return this.result;
        }

        /**
         * 推进一小片计算。
         *
         * @param maxMillis 这一片最多算多久（1~2ms 就够）：算不完下个 tick 再来
         */
        public void step(long maxMillis) {
            if (this.done) {
                return;
            }
            long deadline = System.nanoTime() + maxMillis * 1_000_000L;
            while (!this.open.isEmpty() && this.expanded++ < MAX_NODES) {
                // 每 64 个节点查一次时间（分片预算小，查得更勤）
                if ((this.expanded & 0x3F) == 0 && System.nanoTime() > deadline) {
                    return;   // 这一片用完了，还没算完
                }
                Node current = this.open.poll();
                if (!this.closed.add(current.pos)) {
                    continue;
                }
                if (isGoal(current.pos, this.goal)) {
                    finish(current);
                    return;
                }
                for (Node next : expand(this.level, current, this.goal, this.allowDig, this.allowDownDig)) {
                    if (this.closed.contains(next.pos)) {
                        continue;
                    }
                    Double known = this.best.get(next.pos);
                    if (known != null && known <= next.g) {
                        continue;
                    }
                    this.best.put(next.pos, next.g);
                    this.open.add(next);
                }
            }
            // open 空了 / 节点耗尽：确认没路
            this.done = true;
            this.result = null;
        }

        private void finish(Node goalNode) {
            this.done = true;
            List<BlockPos> path = new ArrayList<>();
            for (Node n = goalNode; n != null && n.parent != null; n = n.parent) {
                path.add(0, n.pos);
            }
            this.result = path;
        }
    }

    private static boolean isGoal(BlockPos pos, BlockPos goal) {
        return Math.abs(pos.getX() - goal.getX()) <= 1
                && Math.abs(pos.getZ() - goal.getZ()) <= 1
                && Math.abs(pos.getY() - goal.getY()) <= 1;
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        double dy = to.getY() - from.getY();
        // 垂直部分必须"不高估"，否则启发式不可采纳，A* 会算出绕远路的怪路径。
        // 上跳一格最少花 1.6，往下掉一格只要 0.3 —— 之前统一按 |dy| * 1.2 算，
        // 对下落是明显高估（掉 3 格实际只要 0.9，却被估成 3.6）。
        return Math.sqrt(dx * dx + dz * dz) + (dy > 0 ? dy * 1.6D : -dy * 0.3D);
    }

    private static List<Node> expand(Level level, Node from, BlockPos goal, boolean allowDig, boolean allowDownDig) {
        List<Node> out = new ArrayList<>(9);
        BlockPos p = from.pos;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                // 斜走：只要求 flat 本身和 flat.above 不是实心（脚+头两格空），
                // 不再要求两侧（dx 方向那列 + dz 方向那列）也都空。
                // 之前的双侧 passableColumn 会封掉"贴着墙斜着绕"的合法路径 ——
                // 比如 AI 要绕开右边的墙，斜走时 dz 方向那一列被墙占了就直接 skip，
                // 导致 A* 只能走直路或更远的绕路，看起来就是"不会绕开旁边的方块"。
                // 真穿墙（flat 本身是 solid 或 flat.below 也是墙）会被 canStand / breakable 拦住。
                double stepCost = (dx != 0 && dz != 0) ? 1.414D : 1.0D;
                // 斜走挤墙角惩罚：斜向那格的"正交两侧"（就是斜走要蹭过去的那两个角）有挡脚方块时
                // 加钱。执行端最容易在这种格子上撞墙原地打转 —— 加钱让 A* 优先选通道中间的格子。
                if (dx != 0 && dz != 0) {
                    if (solid(level, p.offset(dx, 0, 0))) {
                        stepCost += WALL_HUG_COST;
                    }
                    if (solid(level, p.offset(0, 0, dz))) {
                        stepCost += WALL_HUG_COST;
                    }
                }
                addCandidate(level, from, goal, p.offset(dx, 0, dz), stepCost, allowDig, out);
            }
        }
        // 竖直下探：往脚下挖一格。只有挖矿 / 下矿洞这类"真要往下钻"的任务才开（allowDownDig），
        // 平时走路开着会边走边在玩家地形上刨坑。
        // 条件：脚下那格是实心且挖得动（不是半砖 —— 半砖能直接站上去，不用挖）、
        // 挖穿后能落到一个实心面上、头顶那格是空的。
        if (allowDig && allowDownDig) {
            BlockPos down = p.below();
            if (!canStand(level, down) && breakable(level, down) && !isHazard(level, down)
                    && solid(level, down.below()) && !solid(level, down.above())) {
                out.add(node(from, down, DIG_DOWN_COST, goal));
            }
        }
        return out;
    }

    private static void addCandidate(Level level, Node from, BlockPos goal, BlockPos flat, double stepCost,
                                     boolean allowDig, List<Node> out) {
        // 危险地形（岩浆/火/仙人掌/浆果丛/细雪/蜘蛛网）：直接不生成节点，A* 自然绕开
        if (isHazard(level, flat)) {
            return;
        }
        // 平走
        if (canStand(level, flat)) {
            out.add(node(from, flat, stepCost, goal));
            return;
        }
        // 跳上一格
        BlockPos up = flat.above();
        // 只看"跳上去之后能不能站住"就够了（canStand 已经检查了上方两格的空间）。
        // 以前还额外要求当前位置的头顶上方（p.above(2)）是空的 —— 在两格高的走廊 / 房间里
        // 这个条件永远不成立，AI 就爬不上那一格台阶，直接卡死在死胡同里出不来。
        if (canStand(level, up)) {
            out.add(node(from, up, stepCost + 0.6D, goal));
            return;
        }
        // 往下掉（最多 3 格）：落差越大越贵 —— 以前 drop*0.3 太便宜，AI 会为了抄近路随便跳崖。
        // 1 格 0.5 / 2 格 2.0 / 3 格 3.5，掉 3 格比绕路贵，只有真没路时才跳。
        for (int drop = 1; drop <= 3; drop++) {
            BlockPos down = flat.below(drop);
            if (canStand(level, down) && !isHazard(level, down)) {
                out.add(node(from, down, stepCost + 0.5D + (drop - 1) * 1.5D, goal));
                return;
            }
            if (solid(level, down)) {
                break;
            }
        }
        // 游泳过河 / 过湖：水不是实心方块，canStand 永远不成立 —— 以前 A* 完全不会走水路，
        // AI 遇到水只会绕或者干脆卡在岸边。
        if (canSwim(level, flat)) {
            out.add(node(from, flat, stepCost + SWIM_COST, goal));
            return;
        }
        // 挖过去：只检查 flat 这一格能不能挖。
        // 之前要求 flat 和 flat.above() 都得挖得动 —— PathExecutor.digThrough 只挖一格就够了，
        // A* 条件太严会导致"只有一格挡路的死胡同"也算不出挖穿路径，AI 就只会绕路或卡住。
        if (allowDig) {
            if (breakable(level, flat)) {
                out.add(node(from, flat, stepCost + DIG_COST, goal));
            }
        }
    }

    private static Node node(Node from, BlockPos pos, double extraCost, BlockPos goal) {
        double g = from.g + extraCost;
        return new Node(pos.immutable(), g, g + heuristic(pos, goal), from);
    }

    /** 真正要挖的那一格能不能挖动（硬度 >= 0，且不是空气） */
    private static boolean breakable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getDestroySpeed(level, pos) >= 0.0F;
    }

    /** 该列脚部与头部两格都是空的（用于斜走的防穿墙判定） */
    private static boolean passableColumn(Level level, BlockPos pos) {
        return !solid(level, pos) && !solid(level, pos.above());
    }

    /** 水面游泳节点：站在水面那一格（上方不是水），脚下是水或地面 —— 避免算出让 AI 沉底的路径 */
    private static boolean canSwim(Level level, BlockPos pos) {
        return isWater(level, pos) && !isWater(level, pos.above())
                && !solid(level, pos) && !solid(level, pos.above())
                && (isWater(level, pos.below()) || solid(level, pos.below()));
    }

    private static boolean isWater(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER);
    }

    /**
     * 该位置玩家能否站住。
     * <p>
     * 半砖 / 雪层 / 地毯这类矮台阶（碰撞盒顶面 ≤ {@value #MAX_AUTO_STEP}）也算能站：
     * 玩家的脚就在这一格里、站在矮台阶顶面上，原版 maxUpStep 会自动迈上去。
     * 以前一律要求"这一格非实心 + 下面一格实心"，半砖那格本身是实心的，
     * 于是 A* 只能把它当成"要跳上去的一格"，AI 走半砖路 / 上台阶就一路蹦。
     */
    public static boolean canStand(Level level, BlockPos pos) {
        if (solid(level, pos.above())) {
            return false;   // 头顶被占
        }
        return solid(level, pos) ? lowStep(level, pos) : solid(level, pos.below());
    }

    /** 这一格是不是矮台阶：碰撞盒顶面在 0.6 以内（半砖 0.5 / 雪层），玩家能直接走上去 */
    private static boolean lowStep(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        double top = state.getCollisionShape(level, pos).max(Direction.Axis.Y);
        return top > 0.0D && top <= MAX_AUTO_STEP;
    }

    public static boolean solid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    /** 找到紧贴目标的可站立位置（目标本身不可站立时使用） */
    public static BlockPos nearestStandable(Level level, BlockPos target, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (BlockPos pos : BlockPos.betweenClosed(target.offset(-r, -r, -r), target.offset(r, r, r))) {
                if (canStand(level, pos)) {
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    /** 依次尝试目标周围 6 个方向的可站立点 */
    public static BlockPos adjacentStandable(Level level, BlockPos target) {
        for (Direction d : Direction.values()) {
            BlockPos pos = target.relative(d);
            if (canStand(level, pos)) {
                return pos;
            }
        }
        return null;
    }
}