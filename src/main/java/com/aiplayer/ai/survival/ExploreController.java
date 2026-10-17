package com.aiplayer.ai.survival;

import com.aiplayer.ai.path.AStarPathFinder;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * 探索控制器：空闲时像真人一样往外走走、看看，然后自己认得回家的路。
 * <ol>
 *   <li><b>一段一段地走</b>：每次挑一个 32~48 格外的落点（在 A* 单次预算之内），
 *       走到了就记个点、歇几秒，再挑下一个；</li>
 *   <li><b>记点</b>：路过的地方记成路标（最多 {@link #MAX_MARKS} 个、彼此间隔
 *       {@link #MARK_GAP} 格以上），这些点就是回程的"面包屑"；</li>
 *   <li><b>回程</b>：走够 {@link #TRIP_LEGS} 段 / 血量偏低 / 饿了 / 离家超过
 *       {@link #HOME_RANGE} 格就掉头回家；家 = 本次出行开始时站的地方；</li>
 *   <li><b>让路</b>：玩家任务、跟随、战斗、采集都会把寻路目标抢走，这时候探索
 *       只等不抢 —— 它只在"别人都不用它"的间隙重新下达走路目标。</li>
 * </ol>
 * 只输出 {@code walkTo} 这一种意图，不直接改坐标，所有移动照样过原版寻路。
 */
public class ExploreController {
    private static final Random RNG = new Random();

    /** 每段出行的水平距离（格）：留在 A* 单次搜索预算内 */
    private static final int LEG_MIN = 32;
    private static final int LEG_MAX = 48;
    /** 一段路最多走多久（tick）：超时就换目标，不原地死磕 */
    private static final int LEG_TIMEOUT = 600;
    /** 走完一段后歇多久（tick） */
    private static final int REST_TICKS = 100;
    /** 回到家后歇多久（tick） */
    private static final int HOME_REST_TICKS = 300;
    /** 走完几段就回家 */
    private static final int TRIP_LEGS = 6;
    /** 离家超过多远就必须往回走（格） */
    private static final int HOME_RANGE = 96;
    /** 回到家 / 到目标的判定距离（格） */
    private static final int ARRIVE_HOME = 8;
    private static final int ARRIVE = 3;
    /** 记点之间的最小间距（格）：免得在一个小圈子里反复记同一个地方 */
    private static final int MARK_GAP = 8;
    /** 最多记几个路标 */
    private static final int MAX_MARKS = 16;
    /** 连续失败几次就清掉记点、重新规划 */
    private static final int FAIL_LIMIT = 3;
    /** 多久重下一次走路目标（tick）：被别的行为顶掉后能自己接回来 */
    private static final int REPATH_TICKS = 40;
    /** 空闲多久才考虑自己出去转（tick） */
    private static final int IDLE_BEFORE_START = 100;
    /** 每次自触发出发的概率（很低：一队 AI 不会一起跑掉） */
    private static final float START_CHANCE = 0.01F;
    /** 出发的最低血量比例 */
    private static final float MIN_HEALTH_RATIO = 0.6F;
    /** 出发的最高高度：别站在高塔 / 半空平台上就往远处走 */
    private static final int MAX_START_Y = 80;

    private final AiPlayer player;

    /** 走过的路标（回程的面包屑） */
    private final Deque<BlockPos> marks = new ArrayDeque<>();
    /** 家 = 本次出行开始的位置 */
    private BlockPos home;
    /** 当前这一段的目的地 */
    private BlockPos goal;
    private int legTicks;
    private int restTicks;
    private int idleTicks;
    private int failCount;
    /** 已经走了几段 */
    private int legs;
    private boolean active;
    /** 正在往回走 */
    private boolean returning;

    public ExploreController(AiPlayer player) {
        this.player = player;
    }

    public boolean isActive() {
        return this.active;
    }

    public BlockPos home() {
        return this.home;
    }

    /** 玩家 / LLM 明确让"出去转转"：立刻出发 */
    public void start() {
        if (this.active) {
            return;
        }
        this.active = true;
        this.returning = false;
        this.home = this.player.blockPosition().immutable();
        this.goal = null;
        this.legTicks = 0;
        this.restTicks = 0;
        this.idleTicks = 0;
        this.failCount = 0;
        this.legs = 0;
        this.marks.clear();
        pickGoal();
    }

    /** 停下探索（接到别的任务 / AI 被清空任务时调用） */
    public void stop() {
        this.active = false;
        this.returning = false;
        this.goal = null;
        this.restTicks = 0;
        this.idleTicks = 0;
    }

    /**
     * 每 tick 由 AiBrain 空闲分支调用。
     *
     * @return true 表示"本轮在探索"（已经占住这个 tick）
     */
    public boolean tick() {
        if (this.restTicks > 0) {
            this.restTicks--;
            // 歇着也算占住本 tick：真人走到一处也要站着看看四周
            return this.active;
        }
        if (!this.active) {
            return autoStart();
        }
        if (this.player.brain() == null) {
            stop();
            return false;
        }
        if (this.returning) {
            return tickReturn();
        }
        if (shouldReturn()) {
            this.returning = true;
            this.goal = this.home;
            this.legTicks = 0;
            return true;
        }
        if (this.goal == null || this.player.blockPosition().closerThan(this.goal, ARRIVE)) {
            arrive();
            return true;
        }
        if (++this.legTicks > LEG_TIMEOUT) {
            fail();
            return true;
        }
        if (this.legTicks % REPATH_TICKS == 0) {
            BlockPos other = this.player.brain().target();
            if (other != null && !other.equals(this.goal)) {
                // 别的行为（玩家任务 / 采集 / 捡东西）在用寻路：让路，下个周期再看
                return true;
            }
            this.player.brain().walkTo(this.goal);
        }
        return true;
    }

    // ------------------------------------------------------------------

    /** 空闲时的自触发：满足条件才出门，概率很低 */
    private boolean autoStart() {
        AiProfile profile = this.player.profile();
        if (profile == null || !profile.explore) {
            return false;
        }
        if (++this.idleTicks < IDLE_BEFORE_START) {
            return false;
        }
        if (this.player.getY() > MAX_START_Y) {
            return false;
        }
        if (this.player.getHealth() < this.player.getMaxHealth() * MIN_HEALTH_RATIO) {
            return false;
        }
        if (this.player.brain() != null && this.player.brain().food().hungry()) {
            return false;
        }
        if (RNG.nextFloat() >= START_CHANCE) {
            return false;
        }
        start();
        return this.active;
    }

    /** 是不是该掉头回家了 */
    private boolean shouldReturn() {
        if (this.home == null) {
            return false;
        }
        if (this.legs >= TRIP_LEGS) {
            return true;
        }
        if (this.player.getHealth() <= 8.0F) {
            return true;
        }
        if (!this.player.blockPosition().closerThan(this.home, HOME_RANGE)) {
            return true;   // 走太远了
        }
        return this.player.brain() != null && this.player.brain().food().hungry();
    }

    private boolean tickReturn() {
        if (this.home == null) {
            stop();
            return false;
        }
        if (this.player.blockPosition().closerThan(this.home, ARRIVE_HOME)) {
            // 到家：歇一会儿，路标清掉（下次出行重新记）
            this.active = false;
            this.returning = false;
            this.goal = null;
            this.marks.clear();
            this.failCount = 0;
            this.legs = 0;
            this.restTicks = HOME_REST_TICKS;
            return true;
        }
        if (++this.legTicks > LEG_TIMEOUT) {
            // 一路都走不回去：退而求其次，沿记下来的路标往回挪（面包屑回程）
            BlockPos crumb = nearestMarkToHome();
            if (crumb != null) {
                this.player.brain().walkTo(crumb);
                this.marks.remove(crumb);
                this.legTicks = 0;
                return true;
            }
            // 连路标都没有：就在这儿歇着，别再越走越远
            this.active = false;
            this.returning = false;
            this.goal = null;
            this.restTicks = HOME_REST_TICKS;
            return true;
        }
        if (this.legTicks % REPATH_TICKS == 0) {
            this.player.brain().walkTo(this.home);
        }
        return true;
    }

    /** 到目的地了：记点、歇一下、挑下一个落点 */
    private void arrive() {
        mark(this.goal != null ? this.goal : this.player.blockPosition());
        this.legs++;
        this.legTicks = 0;
        this.restTicks = REST_TICKS;
        pickGoal();
    }

    /** 这一段走失败了（超时 / 没路）：连续失败就清掉记点重新规划 */
    private void fail() {
        this.failCount++;
        this.legTicks = 0;
        if (this.failCount >= FAIL_LIMIT) {
            this.marks.clear();
            this.failCount = 0;
        }
        this.restTicks = REST_TICKS;
        pickGoal();
    }

    /**
     * 挑下一个落点：随机方向、{@link #LEG_MIN}~{@link #LEG_MAX} 格外，
     * 落点要区块已加载、地块不在记过的点旁边、而且要能站人。
     */
    private void pickGoal() {
        Level level = this.player.level();
        BlockPos base = this.player.blockPosition();
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = RNG.nextDouble() * Math.PI * 2.0D;
            int dist = LEG_MIN + RNG.nextInt(LEG_MAX - LEG_MIN + 1);
            int x = base.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = base.getZ() + (int) Math.round(Math.sin(angle) * dist);
            BlockPos probe = new BlockPos(x, base.getY(), z);
            if (!level.hasChunkAt(probe)) {
                continue;   // 区块还没加载：换方向
            }
            if (nearMark(x, z)) {
                continue;   // 记过的地方不去，保证是往外扩
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos stand = AStarPathFinder.nearestStandable(level, new BlockPos(x, y, z), 3);
            if (stand == null || dangerous(level, stand)) {
                continue;   // 站不住 / 是水岩浆：换方向
            }
            this.goal = stand;
            this.legTicks = 0;
            return;
        }
        // 一圈都没找到合适的落点：回家歇着，别乱走
        this.goal = this.home;
        this.legTicks = 0;
    }

    private void mark(BlockPos pos) {
        if (pos == null || nearMark(pos.getX(), pos.getZ())) {
            return;
        }
        this.marks.addLast(pos.immutable());
        while (this.marks.size() > MAX_MARKS) {
            this.marks.pollFirst();
        }
    }

    /** 这个水平位置附近有没有记过的点（切比雪夫距离） */
    private boolean nearMark(int x, int z) {
        for (BlockPos m : this.marks) {
            if (Math.abs(m.getX() - x) <= MARK_GAP && Math.abs(m.getZ() - z) <= MARK_GAP) {
                return true;
            }
        }
        return false;
    }

    /** 离 home 最近的那个路标（回程走不通时当面包屑用） */
    private BlockPos nearestMarkToHome() {
        if (this.home == null) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos m : this.marks) {
            double d = m.distSqr(this.home);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    /** 落点脚下 / 自身是不是水 / 岩浆（别把 AI 送去游泳或者洗澡） */
    private static boolean dangerous(Level level, BlockPos pos) {
        BlockState at = level.getBlockState(pos);
        BlockState below = level.getBlockState(pos.below());
        return !at.getFluidState().isEmpty() || !below.getFluidState().isEmpty();
    }
}
