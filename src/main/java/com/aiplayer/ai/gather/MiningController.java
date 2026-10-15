package com.aiplayer.ai.gather;

import com.aiplayer.entity.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 挖矿控制器：把 Minecraft 挖矿这件事按真人的做法分两种。
 * <ol>
 *   <li><b>矿脉开采</b>：按深度分层优先级找附近的矿石，做一次连通域搜索把整条矿脉
 *       （最多 {@link #MAX_VEIN} 块）排进队列，按距自己由近到远逐块挖；</li>
 *   <li><b>支巷矿道</b>：附近没矿可挖时，朝实心岩层方向开一条 1×2 的巷道，
 *       每挖完一段就左右交替转向 90°（支巷式矿道），挖够 {@link #MAX_LEGS} 段
 *       就回来继续找矿。</li>
 * </ol>
 * 控制器自己不动手挖：它只负责"下一块挖哪"，把目标交给
 * {@code AiBrain.mineAtJob}（= 设置 mineTarget + 寻路目标），
 * 真正的破坏仍然走 {@code AiBrain.mineTick} -> BlockBreaker 那条原版路径。
 * <p>
 * 性能：找矿是"限半径 + 节流"的（半径 {@link #VEIN_RADIUS}×{@link #VEIN_VERT}，
 * 每 {@link #SCAN_COOLDOWN} tick 才扫一次，扫到当前分层里优先级最高的矿就直接返回）。
 */
public class MiningController {
    private static final Random RNG = new Random();

    /** 找矿的水平半径 / 竖直半径（格） */
    private static final int VEIN_RADIUS = 6;
    private static final int VEIN_VERT = 4;
    /** 两次找矿扫描之间的间隔（tick） */
    private static final int SCAN_COOLDOWN = 8;
    /** 一条矿脉最多开采几块（防止一条巨脉把 AI 钉死在一个角落） */
    private static final int MAX_VEIN = 24;
    /** 矿脉连通域相对种子方块的最大半径 */
    private static final int VEIN_SPREAD = 6;
    /** 单块目标最多坚持多少 tick（挖不动 / 走不到就跳过） */
    private static final int BLOCK_TIMEOUT = 240;
    /** 一段支巷的长度 */
    private static final int BRANCH_LENGTH = 16;
    /** 一次支巷作业最多挖几段（挖完就回去找矿） */
    private static final int MAX_LEGS = 4;
    /** 两段支巷之间 / 一段支巷结束后的冷却 */
    private static final int BRANCH_COOLDOWN = 120;
    /** 找不到矿时，在地下待多久才自己开矿道（按扫描次数累计，1 次 ≈ SCAN_COOLDOWN tick） */
    private static final int IDLE_BEFORE_BRANCH = 40;
    /** 自主开矿道的最大高度：只在地下挖，别在玩家基地下面刨坑 */
    private static final int AUTO_BRANCH_MAX_Y = 56;

    /** 分层优先级：越靠前越优先（同一层里按顺序打分） */
    private static final TagKey<Block>[] ORDER_SURFACE = {
            BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES};
    private static final TagKey<Block>[] ORDER_MID = {
            BlockTags.IRON_ORES, BlockTags.COPPER_ORES, BlockTags.GOLD_ORES,
            BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES, BlockTags.COAL_ORES};
    private static final TagKey<Block>[] ORDER_DEEP = {
            BlockTags.GOLD_ORES, BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES,
            BlockTags.DIAMOND_ORES, BlockTags.IRON_ORES};
    private static final TagKey<Block>[] ORDER_BOTTOM = {
            BlockTags.DIAMOND_ORES, BlockTags.REDSTONE_ORES, BlockTags.GOLD_ORES,
            BlockTags.LAPIS_ORES, BlockTags.EMERALD_ORES, BlockTags.IRON_ORES};

    /** 所有矿石标签（判"这格是不是矿"用；分层优先级见上面的 ORDER_*） */
    private static final TagKey<Block>[] ALL_ORE_TAGS = {
            BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES, BlockTags.GOLD_ORES,
            BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES, BlockTags.DIAMOND_ORES, BlockTags.EMERALD_ORES};

    private final AiPlayer player;

    /** 待开采队列（已按距自己由近到远排好） */
    private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private BlockPos current;
    private int currentTicks;
    private int cooldown;
    private int scanCooldown;
    private int idleScans;
    /** 玩家明确要求挖矿（"去挖矿"）：找不到矿就立刻开矿道，而不是干等 */
    private boolean requested;

    // ---- 支巷矿道状态 ----
    private boolean branching;
    private BlockPos branchOrigin;
    private Direction branchDir;
    private Direction lastBranchDir;
    private int branchStep;
    private int legIndex;
    /** 1×2 巷道的上半格（脚部挖完接着挖头部的方块） */
    private boolean headHalf;

    public MiningController(AiPlayer player) {
        this.player = player;
    }

    /** 有没有正在进行的挖掘作业 */
    public boolean isBusy() {
        return this.current != null || this.branching;
    }

    public void reset() {
        this.queue.clear();
        this.current = null;
        this.currentTicks = 0;
        this.branching = false;
        this.branchOrigin = null;
        this.branchDir = null;
        this.branchStep = 0;
        this.legIndex = 0;
        this.headHalf = false;
        this.requested = false;
    }

    /** 玩家说"去挖矿"：先找矿脉，附近没有就开矿道 */
    public void startOre() {
        reset();
        this.cooldown = 0;
        this.scanCooldown = 0;
        this.idleScans = 0;
        this.requested = true;
    }

    /** 玩家说"挖矿道"：直接开巷道 */
    public void startBranch() {
        reset();
        this.idleScans = 0;
        startBranchLeg(true);
    }

    /**
     * 每 tick 由 AiBrain 空闲分支调用。
     *
     * @return true 表示"本轮有活要干"（已经被这个控制器占住，别的自主行为别插队）
     */
    public boolean tick() {
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        if (this.current != null) {
            return tickCurrent();
        }
        if (this.branching) {
            return advanceBranch();
        }
        return seekWork();
    }

    // ------------------------------------------------------------------
    // 单块目标：交给 mineTick 去挖，挖掉了就接着下一块
    // ------------------------------------------------------------------

    private boolean tickCurrent() {
        Level level = this.player.level();
        if (this.current == null || level.getBlockState(this.current).isAir()) {
            // 这块已经挖掉了：接着下一块
            this.current = null;
            advance();
            return isBusy();
        }
        if (this.player.brain() == null) {
            reset();
            return false;
        }
        // 地上有自己挖出来的掉落物：先让 survivalTick 去捡，捡完回来接着挖
        if (!this.player.brain().busyMining() && this.player.brain().wantsDrop(6.0D)) {
            return false;
        }
        if (this.player.brain().busyMining()) {
            // mineTick 正在挖它，别插嘴
            return true;
        }
        if (++this.currentTicks > BLOCK_TIMEOUT) {
            // 挖不动 / 走不到这格：跳过，免得整条队列卡死在这一块上
            this.current = null;
            this.currentTicks = 0;
            advance();
            return isBusy();
        }
        this.player.brain().mineAtJob(this.current);
        return true;
    }

    /** 队列里取下一位（跳过已经被挖掉的） */
    private void advance() {
        this.currentTicks = 0;
        Level level = this.player.level();
        while (!this.queue.isEmpty()) {
            BlockPos next = this.queue.poll();
            if (level.getBlockState(next).isAir()) {
                continue;
            }
            this.current = next;
            return;
        }
        this.current = null;
        // 矿脉挖完了：歇一下再找下一处，别一刻不停地扫
        this.cooldown = BRANCH_COOLDOWN;
        this.idleScans = 0;
    }

    // ------------------------------------------------------------------
    // 找矿 / 决定要不要开矿道
    // ------------------------------------------------------------------

    private boolean seekWork() {
        if (this.cooldown > 0 || this.scanCooldown-- > 0) {
            return false;
        }
        this.scanCooldown = SCAN_COOLDOWN;
        if (!hasPickaxe()) {
            this.requested = false;
            return false;
        }
        BlockPos seed = findOreSeed();
        if (seed != null) {
            this.idleScans = 0;
            fillVein(seed);
            advance();
            return isBusy();
        }
        // 附近没有矿
        if (this.requested) {
            // 玩家明确让挖矿：没有矿就直接开矿道找（挖矿本来就有"找不到就往下开道"的共识）
            this.requested = false;
            startBranchLeg(true);
            return isBusy();
        }
        if (this.player.getY() <= AUTO_BRANCH_MAX_Y
                && ++this.idleScans > IDLE_BEFORE_BRANCH
                && RNG.nextFloat() < 0.15F) {
            // 在地下闲逛太久了：自己开一条矿道
            startBranchLeg(false);
            return isBusy();
        }
        return false;
    }

    /**
     * 扫描附近找一块"当前深度优先级最高"的矿石当种子。
     * <p>
     * 分层优先级见 {@link #orderFor(int)}；扫描时按分值取最优，扫到当前层的最高优先级
     * 就直接提前返回（大多数情况下都命中这条快速路径）。
     */
    private BlockPos findOreSeed() {
        Level level = this.player.level();
        BlockPos center = this.player.blockPosition();
        TagKey<Block>[] order = orderFor(center.getY());
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int dx = -VEIN_RADIUS; dx <= VEIN_RADIUS; dx++) {
            for (int dz = -VEIN_RADIUS; dz <= VEIN_RADIUS; dz++) {
                for (int dy = -VEIN_VERT; dy <= VEIN_VERT; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(p)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(p);
                    int rank = rankOf(state, order);
                    if (rank < 0) {
                        continue;
                    }
                    if (rank == 0) {
                        // 当前层最想要的矿：不用再比了
                        return p.immutable();
                    }
                    double score = (order.length - rank) * 100.0D - this.distSqr(p) * 4.0D;
                    if (score > bestScore) {
                        bestScore = score;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** 从种子出发搜整条矿脉（同一种矿石 26 邻域连通），按距自己由近到远入队 */
    private void fillVein(BlockPos seed) {
        Level level = this.player.level();
        TagKey<Block> tag = oreTagOf(level.getBlockState(seed));
        if (tag == null) {
            return;
        }
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> found = new ArrayList<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        BlockPos start = seed.immutable();
        seen.add(start);
        open.add(start);
        while (!open.isEmpty() && found.size() < MAX_VEIN) {
            BlockPos p = open.poll();
            found.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos n = p.offset(dx, dy, dz);
                        if (seen.contains(n) || !start.closerThan(n, VEIN_SPREAD)) {
                            continue;
                        }
                        if (!level.hasChunkAt(n) || !level.getBlockState(n).is(tag)) {
                            continue;
                        }
                        seen.add(n);
                        open.add(n.immutable());
                    }
                }
            }
        }
        // 近的先挖：一条矿脉通常无法一次站定挖完，先挖够得着的更自然
        found.sort(Comparator
                .comparingDouble((BlockPos p) -> this.distSqr(p))
                .thenComparingInt(BlockPos::getY));
        this.queue.addAll(found);
    }

    /** 按深度取分层优先级表 */
    private static TagKey<Block>[] orderFor(int y) {
        if (y >= 48) {
            return ORDER_SURFACE;
        }
        if (y >= 0) {
            return ORDER_MID;
        }
        if (y >= -48) {
            return ORDER_DEEP;
        }
        return ORDER_BOTTOM;
    }

    private static int rankOf(BlockState state, TagKey<Block>[] order) {
        for (int i = 0; i < order.length; i++) {
            if (state.is(order[i])) {
                return i;
            }
        }
        return -1;
    }

    private static TagKey<Block> oreTagOf(BlockState state) {
        for (TagKey<Block> tag : ALL_ORE_TAGS) {
            if (state.is(tag)) {
                return tag;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 支巷矿道
    // ------------------------------------------------------------------

    /** 开一段新巷道：选一个"前方实心岩层最多"的水平方向 */
    private void startBranchLeg(boolean keepZigZag) {
        Direction dir = pickBranchDirection(keepZigZag);
        this.branching = true;
        this.branchOrigin = this.player.blockPosition();
        this.branchDir = dir;
        this.lastBranchDir = dir;
        this.branchStep = 0;
        this.legIndex = 0;
        this.headHalf = false;
    }

    private Direction pickBranchDirection(boolean avoidBack) {
        Direction best = null;
        int bestScore = -1;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (avoidBack && this.lastBranchDir != null && d == this.lastBranchDir.getOpposite()) {
                // 别沿着上一条巷道的反方向原路折返
                continue;
            }
            int score = 0;
            BlockPos base = this.player.blockPosition();
            for (int i = 1; i <= 8; i++) {
                BlockPos p = base.relative(d, i);
                if (diggable(p)) {
                    score++;
                }
                if (diggable(p.above())) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best == null ? Direction.NORTH : best;
    }

    /** 推进巷道：先挖脚部那格，再挖头部那格；这一段挖完就转向接着挖下一段 */
    private boolean advanceBranch() {
        if (!this.branching || this.branchDir == null) {
            return false;
        }
        while (this.branching) {
            if (this.branchStep >= BRANCH_LENGTH) {
                // 这一段挖完了：左右交替转向 90°，形成支巷网
                this.branchOrigin = this.branchOrigin.relative(this.branchDir, BRANCH_LENGTH);
                this.legIndex++;
                if (this.legIndex >= MAX_LEGS) {
                    // 挖够一段时间的巷道了：回去找矿（免得一直在原地刨隧道）
                    this.branching = false;
                    this.lastBranchDir = this.branchDir;
                    this.cooldown = BRANCH_COOLDOWN;
                    this.idleScans = 0;
                    return false;
                }
                this.branchDir = (this.legIndex % 2 == 0)
                        ? this.branchDir.getClockWise()
                        : this.branchDir.getCounterClockWise();
                this.branchStep = 0;
                this.headHalf = false;
                continue;
            }
            BlockPos foot = this.branchOrigin.relative(this.branchDir, this.branchStep);
            BlockPos target = this.headHalf ? foot.above() : foot;
            if (diggable(target)) {
                this.current = target.immutable();
                this.currentTicks = 0;
                return true;
            }
            // 已经是空的（本来就通 / 之前挖过）：直接推进
            if (this.headHalf) {
                this.branchStep++;
                this.headHalf = false;
            } else {
                this.headHalf = true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------

    private boolean diggable(BlockPos pos) {
        if (!this.player.level().hasChunkAt(pos)) {
            return false;
        }
        BlockState state = this.player.level().getBlockState(pos);
        return !state.isAir() && state.getDestroySpeed(this.player.level(), pos) >= 0.0F;
    }

    /** 快捷栏里有没有镐子（没有镐子挖矿是白费力气，石类方块基本挖不动） */
    private boolean hasPickaxe() {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.PICKAXES)) {
                return true;
            }
        }
        return false;
    }

    private double distSqr(BlockPos p) {
        return this.player.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
    }
}
