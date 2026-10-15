package com.aiplayer.ai.gather;

import com.aiplayer.compat.CompatManager;
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
import java.util.Set;

/**
 * 砍树控制器：从一块原木出发，把<b>整棵树</b>都排进挖掘队列。
 * <ol>
 *   <li>树种判定：看种子方块属于哪个物种标签（{@code OAK_LOGS}/{@code SPRUCE_LOGS}…），
 *       只收同种的原木，不会把旁边另一棵树也一起算进来（找不到物种标签就退回
 *       {@link BlockTags#LOGS}，比如下界的菌柄）；</li>
 *   <li>排序：原木按 Y <b>升序</b>（先砍下面、站着就能砍到的），同层按距自己近的先砍 ——
 *       真人砍树就是这么砍的，砍掉下面树干上面自己会掉下来/够得着；</li>
 *   <li>树冠：只清"贴着树干"的树叶（与树干 6 邻接，上限 {@link #MAX_LEAVES} 块），
 *       不会顺手把整片林子的叶子铲平；</li>
 *   <li>够不到的高处：交给 {@code AiBrain.mineTick} 的"够不着就跳一下"逻辑处理，
 *       单块超时就跳过，绝不原地死磕。</li>
 * </ol>
 * 和 {@link MiningController} 一样，控制器只决定"下一块砍哪"，真正的破坏仍然走
 * {@code AiBrain.mineTick} -> BlockBreaker 那条原版路径。
 */
public class ChopController {
    /** 找树的水平半径 / 上下范围（格） */
    private static final int SCAN_RANGE = 10;
    private static final int SCAN_UP = 6;
    private static final int SCAN_DOWN = 2;
    /** 两次找树的间隔（tick）：砍树是长活，不用每 tick 都扫 */
    private static final int SCAN_COOLDOWN = 30;
    /** 一棵树最多砍几块原木 / 几块树叶 */
    private static final int MAX_LOGS = 96;
    private static final int MAX_LEAVES = 32;
    /** 树干连通域的最大半径（巨木 2×2 也在范围内） */
    private static final int TREE_RADIUS = 8;
    /** 单块目标最多坚持多少 tick（够不到就跳过） */
    private static final int BLOCK_TIMEOUT = 200;

    private static final TagKey<Block>[] SPECIES_TAGS = {
            BlockTags.OAK_LOGS, BlockTags.SPRUCE_LOGS, BlockTags.BIRCH_LOGS, BlockTags.JUNGLE_LOGS,
            BlockTags.ACACIA_LOGS, BlockTags.DARK_OAK_LOGS, BlockTags.CHERRY_LOGS,
            BlockTags.MANGROVE_LOGS, BlockTags.PALE_OAK_LOGS};

    /** 扫描列的先后顺序：按到自己的水平距离由近到远（这样"最近的一棵树"通常几列就找到了） */
    private static final int[][] COLUMNS = buildColumns(SCAN_RANGE);

    private final AiPlayer player;

    private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private BlockPos current;
    private int currentTicks;
    private int scanCooldown;
    /** 玩家明确让砍树：扫到就开工；没扫到就回一句 */
    private boolean requested;

    public ChopController(AiPlayer player) {
        this.player = player;
    }

    public boolean isBusy() {
        return this.current != null;
    }

    public void reset() {
        this.queue.clear();
        this.current = null;
        this.currentTicks = 0;
        this.requested = false;
    }

    /** 玩家说"砍树"：把下一次扫描提前到本 tick */
    public void start() {
        this.requested = true;
        this.scanCooldown = 0;
    }

    /**
     * 指定一块原木：把它所在的那棵树整棵排队。
     * （LLM / 玩家给出坐标时用；给的不是原木就退回"自己就近找一棵"）
     */
    public void chopAt(BlockPos pos) {
        if (pos == null) {
            start();
            return;
        }
        Level level = this.player.level();
        BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.LOGS)) {
            start();
            return;
        }
        reset();
        collectTree(pos);
        advance();
    }

    /**
     * 每 tick 由 AiBrain 空闲分支调用。
     *
     * @return true 表示"本轮有树要砍"（已经占住这个 tick）
     */
    public boolean tick() {
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
        }
        if (this.current != null) {
            return tickCurrent();
        }
        if (!this.queue.isEmpty()) {
            advance();
            return isBusy();
        }
        return seekTree();
    }

    private boolean tickCurrent() {
        Level level = this.player.level();
        if (level.getBlockState(this.current).isAir()) {
            // 砍掉了：接着砍下一块
            this.current = null;
            advance();
            return isBusy();
        }
        if (this.player.brain() == null) {
            reset();
            return false;
        }
        // 地上有自己的掉落物：先让 survivalTick 去捡，捡完回来接着砍
        if (!this.player.brain().busyMining() && this.player.brain().wantsDrop(6.0D)) {
            return false;
        }
        if (this.player.brain().busyMining()) {
            return true;
        }
        if (++this.currentTicks > BLOCK_TIMEOUT) {
            // 够不到 / 走不到（比如树顶那块）：跳过，不让整棵树卡在这一块上
            this.current = null;
            this.currentTicks = 0;
            advance();
            return isBusy();
        }
        this.player.brain().mineAtJob(this.current);
        return true;
    }

    private void advance() {
        this.currentTicks = 0;
        Level level = this.player.level();
        while (!this.queue.isEmpty()) {
            BlockPos next = this.queue.poll();
            BlockState state = level.getBlockState(next);
            if (state.isAir() || CompatManager.isProtected(state)) {
                continue;
            }
            this.current = next;
            return;
        }
        this.current = null;
        this.scanCooldown = SCAN_COOLDOWN;
    }

    // ------------------------------------------------------------------

    private boolean seekTree() {
        if (this.scanCooldown > 0) {
            return false;
        }
        this.scanCooldown = SCAN_COOLDOWN;
        if (!hasAxe()) {
            this.requested = false;
            return false;
        }
        BlockPos seed = findNearestLog();
        if (seed == null) {
            if (this.requested) {
                this.requested = false;
                this.player.brain().chat().sayReply("附近没看到树，我往别处走走");
            }
            return false;
        }
        this.requested = false;
        collectTree(seed);
        advance();
        return isBusy();
    }

    /** 从近到远扫列，找到第一块原木（竖直方向自上而下，先命中树干上半段） */
    private BlockPos findNearestLog() {
        Level level = this.player.level();
        BlockPos base = this.player.blockPosition();
        for (int[] col : COLUMNS) {
            int x = base.getX() + col[0];
            int z = base.getZ() + col[1];
            for (int dy = SCAN_UP; dy >= -SCAN_DOWN; dy--) {
                BlockPos p = new BlockPos(x, base.getY() + dy, z);
                if (!level.hasChunkAt(p)) {
                    break;
                }
                BlockState state = level.getBlockState(p);
                if (state.is(BlockTags.LOGS) && !CompatManager.isProtected(state)) {
                    return p;
                }
            }
        }
        return null;
    }

    /** 从种子方块出发，把整棵树（原木 + 贴身树冠）排进队列 */
    private void collectTree(BlockPos seed) {
        Level level = this.player.level();
        BlockState seedState = level.getBlockState(seed);
        TagKey<Block> species = speciesOf(seedState);
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        BlockPos start = seed.immutable();
        seen.add(start);
        open.add(start);
        // 26 邻域连通：2×2 的巨木、分叉的树干都能连上
        while (!open.isEmpty() && logs.size() < MAX_LOGS) {
            BlockPos p = open.poll();
            logs.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos n = p.offset(dx, dy, dz);
                        if (seen.contains(n) || !start.closerThan(n, TREE_RADIUS)) {
                            continue;
                        }
                        if (!level.hasChunkAt(n) || !level.getBlockState(n).is(species)) {
                            continue;
                        }
                        seen.add(n);
                        open.add(n.immutable());
                    }
                }
            }
        }
        // 树冠：与树干 6 邻接的树叶（只清挡着树/贴着树的冠，不铲平整片林子）
        List<BlockPos> leaves = new ArrayList<>();
        Set<BlockPos> leafSeen = new HashSet<>();
        for (BlockPos log : logs) {
            if (leaves.size() >= MAX_LEAVES) {
                break;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = log.relative(d);
                if (leafSeen.contains(n) || !level.hasChunkAt(n)) {
                    continue;
                }
                if (!level.getBlockState(n).is(BlockTags.LEAVES)) {
                    continue;
                }
                leafSeen.add(n);
                leaves.add(n.immutable());
                if (leaves.size() >= MAX_LEAVES) {
                    break;
                }
            }
        }
        // 从下往上砍原木；树叶最后清，且从上往下（先把高处的冠打掉）
        logs.sort(Comparator
                .comparingInt((BlockPos p) -> p.getY())
                .thenComparingDouble((BlockPos p) -> this.distSqr(p)));
        leaves.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
        this.queue.addAll(logs);
        this.queue.addAll(leaves);
    }

    /** 种子方块属于哪个物种标签（认不出就退回通用 LOGS，比如菌柄） */
    private static TagKey<Block> speciesOf(BlockState state) {
        for (TagKey<Block> tag : SPECIES_TAGS) {
            if (state.is(tag)) {
                return tag;
            }
        }
        return BlockTags.LOGS;
    }

    private boolean hasAxe() {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.AXES)) {
                return true;
            }
        }
        return false;
    }

    private double distSqr(BlockPos p) {
        return this.player.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
    }

    private static int[][] buildColumns(int range) {
        List<int[]> list = new ArrayList<>();
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                list.add(new int[]{dx, dz});
            }
        }
        list.sort(Comparator.comparingInt((int[] o) -> Math.abs(o[0]) + Math.abs(o[1])));
        return list.toArray(new int[0][]);
    }
}
