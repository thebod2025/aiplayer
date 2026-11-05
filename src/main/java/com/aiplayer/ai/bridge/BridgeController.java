package com.aiplayer.ai.bridge;

import com.aiplayer.ai.AiInput;
import com.aiplayer.ai.AiLevel;
import com.aiplayer.ai.path.AStarPathFinder;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.BlockPalette;
import com.aiplayer.util.InvUtil;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.PlaceUtil;
import com.aiplayer.zone.ZoneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

public class BridgeController {
    private static final Random RNG = new Random();

    private final AiPlayer player;
    private final AiInput input;

    private BridgeMode mode = BridgeMode.SNEAK;
    private Direction dir = Direction.SOUTH;
    private boolean active;

    private int bridgeY = Integer.MIN_VALUE;
    private long nextPlaceAt;
    private long sneakUntil;
    private long jumpAt;
    private float yawJitter;
    private float edgeSide;
    private int placed;
    private int skipped;
    private int staleTicks;
    private BlockPos lastTarget;

    public BridgeController(AiPlayer player) {
        this.player = player;
        this.input = player.input();
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    public boolean isActive() {
        return this.active;
    }

    public Direction direction() {
        return this.dir;
    }

    public BridgeMode mode() {
        return this.mode;
    }

    public void start(Direction direction) {
        this.dir = direction == null ? yawToDirection(this.input.yaw) : direction;
        this.active = true;
        this.bridgeY = Integer.MIN_VALUE;
        this.nextPlaceAt = 0L;
        this.sneakUntil = 0L;
        this.jumpAt = 0L;
        this.placed = 0;
        this.skipped = 0;
        this.staleTicks = 0;
        this.lastTarget = null;
        // 神桥 / Telly 的斜向偏角固定成一侧，避免每 tick 抖动
        this.yawJitter = RNG.nextBoolean() ? 32.0F + RNG.nextFloat() * 10.0F : -32.0F - RNG.nextFloat() * 10.0F;
        this.edgeSide = RNG.nextBoolean() ? 0.22F : 0.78F;
    }

    public void stop() {
        if (!this.active) {
            return;
        }
        this.active = false;
        this.bridgeY = Integer.MIN_VALUE;
        this.input.sneak = false;
        this.input.jump = false;
        // 搭完释放快捷栏锁，让进食 / 战斗可以立刻用
        this.player.inventoryLock().release("bridge");
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    /** @return true 表示本 tick 正在搭路（输入已被接管） */
    public boolean tick() {
        AiProfile profile = this.player.profile();
        if (profile == null || !profile.bridge || !profile.bridge().isActive()) {
            stop();
            return false;
        }
        this.mode = profile.bridge();
        if (this.active && !profile.level().canBridge(this.mode)) {
            // 强度不够就退化成蹲起搭
            this.mode = BridgeMode.SNEAK;
        }
        if (!selectBlock(profile.bridgeBlock)) {
            stop();
            return false;
        }

        long now = System.currentTimeMillis();
        if (this.player.onGround()) {
            this.bridgeY = Mth.floor(this.player.getY()) - 1;
        }
        if (this.bridgeY == Integer.MIN_VALUE) {
            this.bridgeY = Mth.floor(this.player.getY()) - 1;
        }

        int bx = Mth.floor(this.player.getX());
        int bz = Mth.floor(this.player.getZ());
        BlockPos feet = new BlockPos(bx, this.bridgeY, bz);
        BlockPos target = pickTarget(feet);

        if (target != null) {
            if (!target.equals(this.lastTarget)) {
                this.lastTarget = target;
                this.staleTicks = 0;
            } else {
                this.staleTicks++;
            }
        }

        applyRotation(target, now);

        boolean edge = floorAheadMissing();
        boolean airborne = !this.player.onGround();

        // ---- 前方被"跳不上去"的方块挡住：就地挖穿，而不是顶着墙原地抖。
        //      以前这里只会"能跳就跳"，跳不上去的（两格高的墙 / 树 / 石头）就一直撞墙，
        //      看起来就是"搭路时不会挖方块、也不会绕开"。挖不动（太硬 / 挖不动）就收手，
        //      把控制权交回寻路，由 A* 绕路。 ----
        BlockPos obstacle = obstacleAhead();
        if (obstacle != null) {
            if (digObstacle(obstacle)) {
                // 挖掘期间站定：继续往前顶只会把自己按在墙上
                this.input.forward = false;
                this.input.backward = false;
                this.input.sneak = false;
                this.input.jump = false;
                this.input.mining = true;
                this.input.mineTarget = obstacle;
                return true;
            }
            stop();
            return false;
        }

        // ---- 潜行：只有蹲起搭需要在边缘蹲下；其它模式不蹲 ----
        if (this.mode == BridgeMode.SNEAK) {
            if (edge && this.player.onGround()) {
                // Vape: 蹲下后随机 100~500ms 再站起来
                this.sneakUntil = now + 100L + (long) (RNG.nextFloat() * 400.0F);
            }
            this.input.sneak = now < this.sneakUntil;
            this.input.sprint = false;
        } else {
            this.input.sneak = false;
            this.input.sprint = !this.input.sneak;
        }

        // ---- 跳跃：Telly 系列在边缘起跳（hasReachedPathEdge）；前方有台阶/矮墙时一律要跳 ----
        boolean stepUp = this.player.onGround() && blockedAhead();
        if (this.mode == BridgeMode.TELLY || this.mode == BridgeMode.SPEED_TELLY) {
            if (stepUp || (edge && this.player.onGround() && now >= this.jumpAt)) {
                this.input.jump = true;
                this.jumpAt = now + 300L;
                this.nextPlaceAt = 0L;
            } else {
                this.input.jump = false;
            }
        } else {
            // 蹲起搭 / 神桥：平地上不跳；但搭到对岸遇到一格高的地形时必须跳上去，
            // 否则会一直顶着方块不动（之前 jump 被无条件置 false，所以"搭路时不会跳"）。
            this.input.jump = stepUp;
        }

        // ---- 放置 ----
        boolean canPlaceNow = this.mode != BridgeMode.TELLY && this.mode != BridgeMode.SPEED_TELLY || airborne;
        if (target != null && canPlaceNow && now >= this.nextPlaceAt && !this.fumble()) {
            if (PlaceUtil.placeAt(this.player, target, this.dir)) {
                this.placed++;
                this.staleTicks = 0;
                this.nextPlaceAt = now + interval();
            }
        }

        // ---- Java 版姿势：蹲起搭是"倒退着走"——脸背对前进方向、眼睛盯脚下边缘；
        //      神桥 / Telly 才面朝前进方向 ----
        if (this.mode == BridgeMode.SNEAK) {
            this.input.forward = false;
            this.input.backward = true;
        } else {
            this.input.forward = true;
            this.input.backward = false;
        }
        this.input.left = false;
        this.input.right = false;

        // ---- 到岸收手：前方 5-7 格出现落脚地（肯定不是自己刚放的桥块），说明搭到头了，
        //      交回寻路继续走。以前会闷头一直搭到 staleTicks 超时，看着像不知道自己在干嘛。----
        if (landAhead()) {
            stop();
            return false;
        }

        // ---- 卡住就收手，交给寻路 ----
        if (this.staleTicks > 60) {
            stop();
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 几何
    // ------------------------------------------------------------------

    /**
     * 选放置目标：镜像 的 updatePlacement ——
     * 脚下是空就先补脚下（掉下去了/刚开始搭），否则往前找第一个空位。
     */
    private BlockPos pickTarget(BlockPos feet) {
        // 未加载区块里的“可替换”是假象（读到的是空气），放下去等于往虚空丢方块
        if (!this.player.level().hasChunkAt(feet)) {
            return null;
        }
        if (PlaceUtil.isReplaceable(this.player.level(), feet)
                && !ZoneManager.isProtected(this.player.level(), feet)) {
            return feet;
        }
        int scan = this.mode == BridgeMode.SPEED_TELLY ? 3 : 2;
        BlockPos p = feet;
        for (int i = 1; i <= scan; i++) {
            p = p.relative(this.dir);
            if (PlaceUtil.isReplaceable(this.player.level(), p)
                    && !ZoneManager.isProtected(this.player.level(), p)) {
                return p;
            }
        }
        return null;
    }

    /** 前方脚下是否没有支撑（马上要走空）—— 对应 Vape ScaffoldEdgeSneakHelper 的碰撞箱检测 */
    private boolean floorAheadMissing() {
        double x = this.player.getX() + this.dir.getStepX() * 0.35D;
        double z = this.player.getZ() + this.dir.getStepZ() * 0.35D;
        int y = this.bridgeY == Integer.MIN_VALUE ? Mth.floor(this.player.getY()) - 1 : this.bridgeY;
        return !AStarPathFinder.solid(this.player.level(), new BlockPos(Mth.floor(x), y, Mth.floor(z)));
    }

    /**
     * 前方同一层是不是被方块挡住了（一格高的台阶 / 矮墙）。
     * <p>
     * 玩家的最大自动上台阶高度只有 0.6 格，遇到整格高的地形必须跳才能上去。
     * 搭路时前方被挡却一直不跳的话，AI 只会顶着方块原地不动。
     */
    private boolean blockedAhead() {
        Level level = this.player.level();
        int dx = Mth.floor(this.player.getX() + this.dir.getStepX() * 0.7D);
        int dz = Mth.floor(this.player.getZ() + this.dir.getStepZ() * 0.7D);
        int y = Mth.floor(this.player.getY());
        BlockPos ahead = new BlockPos(dx, y, dz);
        if (!level.hasChunkAt(ahead)) {
            return false;
        }
        if (!AStarPathFinder.solid(level, ahead)) {
            return false;   // 前方是空的，不用跳
        }
        // 只有"跳上去能站人"的矮台阶才跳：上面还得是空的，否则是堵高墙，跳也没用
        return !AStarPathFinder.solid(level, ahead.above()) && !AStarPathFinder.solid(level, ahead.above(2));
    }

    /**
     * 前方同一层被"跳不上去"的方块挡住时返回那一格，否则 null。
     * <p>
     * 和 {@link #blockedAhead()} 互补：那个只管"一格高、能跳上去"的矮台阶，
     * 这里管真正的障碍（两格高以上的墙、树干、石头）。
     */
    private BlockPos obstacleAhead() {
        Level level = this.player.level();
        int dx = Mth.floor(this.player.getX() + this.dir.getStepX() * 0.7D);
        int dz = Mth.floor(this.player.getZ() + this.dir.getStepZ() * 0.7D);
        int y = Mth.floor(this.player.getY());
        BlockPos ahead = new BlockPos(dx, y, dz);
        if (!level.hasChunkAt(ahead)) {
            return null;
        }
        if (AStarPathFinder.solid(level, ahead)) {
            // 一格高、上面两格空的矮台阶：跳上去就行，不用挖
            if (!AStarPathFinder.solid(level, ahead.above()) && !AStarPathFinder.solid(level, ahead.above(2))) {
                return null;
            }
            return ahead;
        }
        // 脚下那格是空的、但“上面那格”（人所在的高度）被堵住：身体一样过不去，必须挖。
        // 以前这里直接返回 null —— 挖穿矮墙的脚部那格之后，头顶那块永远没人挖，
        // 表现就是“搭路时上面的方块也不会挖”，AI 顶着它原地抖。
        return AStarPathFinder.solid(level, ahead.above()) ? ahead.above() : null;
    }

    /**
     * 挖穿挡路的方块，{@code target} 就是 {@link #obstacleAhead()} 给出的那一格
     * （脚部优先，脚部通了就是头顶那格）。
     *
     * @return false 表示挖不动（基岩 / 黑曜石之类太硬），该收手交给寻路绕开
     */
    private boolean digObstacle(BlockPos target) {
        Level level = this.player.level();
        BlockState state = level.getBlockState(target);
        float hardness = state.getDestroySpeed(level, target);
        if (hardness < 0.0F || hardness > 30.0F) {
            return false;   // 挖不动 / 太硬（黑曜石、远古残骸这类不硬啃）
        }
        // 用搭路自己的锁（60）去换工具：默认的任务锁（40）抢不过搭路锁，
        // 以前这里根本切不动工具，AI 就拿着手上的方块去抠石头 —— 看起来就是“不会切东西”。
        int slot = this.player.brain().equipBestTool(state, "bridge", InventoryLock.PRIO_BRIDGE);
        if (slot >= 0) {
            this.input.selectedSlot = slot;   // 否则本 tick 应用输入时又会被换回搭路的方块
        }
        return true;
    }

    /** 视角：看向要放的那个方块的边缘（Vape computePlacementPoint 用 0.2/0.8 偏移，这里同理） */
    private void applyRotation(BlockPos target, long now) {
        float maxTurn = 22.0F + this.player.profile().level().id * 4.0F;
        // Java 版倒退蹲搭的姿势：脸背对前进方向、视线朝下盯脚边 —— 真实玩家就是这样看着脚下倒退搭的
        if (this.mode == BridgeMode.SNEAK) {
            this.input.yaw = Mth.wrapDegrees(yawOf(this.dir) + 180.0F);
            this.input.pitch = 72.0F + RNG.nextFloat() * 10.0F;
            return;
        }
        if (this.mode == BridgeMode.TELLY || this.mode == BridgeMode.SPEED_TELLY) {
            if (!this.player.onGround()) {
                // Vape computeBridgeRotation：空中斜向 yaw、pitch 接近垂直向下
                this.input.yaw = Mth.wrapDegrees(yawOf(this.dir) + this.yawJitter);
                this.input.pitch = 86.0F + RNG.nextFloat() * 3.0F;
                return;
            }
        }
        double tx;
        double tz;
        if (target != null) {
            tx = target.getX() + this.edgeSide;
            tz = target.getZ() + (this.edgeSide > 0.5F ? 0.24D : 0.76D);
            double ty = target.getY() + 0.9D;
            this.player.lookAt(tx, ty, tz, maxTurn);
            // 高强度更接近“一帧到位”，但仍保留转身过程
            if (this.player.profile().level().id >= 4) {
                this.input.pitch = Math.max(this.input.pitch, 55.0F);
            }
            return;
        }
        double px = this.player.getX();
        double py = this.player.getEyeY();
        double pz = this.player.getZ();
        this.player.lookAt(px + this.dir.getStepX() * 2.0D, py - 1.6D, pz + this.dir.getStepZ() * 2.0D, maxTurn);
    }

    private long interval() {
        AiLevel level = this.player.profile().level();
        long base = level.buildDelayMs();
        double perSecond = this.player.profile().bridgeSpeed;
        if (perSecond > 0.1D) {
            base = Math.min(base, (long) (1000.0D / perSecond));
        }
        if (this.mode == BridgeMode.SPEED_TELLY) {
            base = Math.max(50L, base / 2L);
        }
        return Math.max(50L, base);
    }

    /** 低强度偶尔漏放一块（会掉下去，符合“菜鸟”设定） */
    private boolean fumble() {
        float chance = this.player.profile().level().mistakeChance * 0.15F;
        if (this.skipped < 2 && this.placed > 6 && RNG.nextFloat() < chance) {
            this.skipped++;
            return true;
        }
        return false;
    }

    private boolean selectBlock(String blockName) {
        Item item = BlockPalette.resolve(blockName);
        int slot = InvUtil.findHotbarSlot(this.player, item);
        if (slot >= 0) {
            // 走统一槽位锁：搭路过程中一直持锁，别人不会把方块槽换掉
            if (!this.player.selectSlot("bridge", InventoryLock.PRIO_BRIDGE, slot)) {
                return false;
            }
            this.input.selectedSlot = slot;
            return true;
        }
        ItemStack held = this.player.getMainHandItem();
        if (!held.isEmpty() && held.getItem() instanceof BlockItem) {
            return true;
        }
        // 退一步：背包里任意方块
        ItemStack any = InvUtil.findAnyBlock(this.player);
        if (any.isEmpty()) {
            return false;
        }
        if (!this.player.inventoryLock().acquire("bridge", InventoryLock.PRIO_BRIDGE)) {
            return false;
        }
        return InvUtil.selectStack(this.player, any);
    }

    // ------------------------------------------------------------------
    // 静态工具
    // ------------------------------------------------------------------

    /** yaw -> 水平方向（MC: yaw 0 = +Z） */
    public static Direction yawToDirection(float yaw) {
        float y = Mth.wrapDegrees(yaw);
        if (y >= -45.0F && y < 45.0F) {
            return Direction.SOUTH;
        }
        if (y >= 45.0F && y < 135.0F) {
            return Direction.WEST;
        }
        if (y >= -135.0F && y < -45.0F) {
            return Direction.EAST;
        }
        return Direction.NORTH;
    }

    public static float yawOf(Direction d) {
        return switch (d) {
            case SOUTH -> 0.0F;
            case WEST -> 90.0F;
            case EAST -> -90.0F;
            case NORTH -> 180.0F;
            default -> 0.0F;
        };
    }

    /**
     * 是否需要搭路（Java 版判断：能走 / 能跳 / 能跳下去就绝不搭）。
     * <p>
     * 以前只看"前方 1 格脚下 3 格内没地面"就开始搭 —— 一格宽的沟、矮台阶、缓坡全都触发，
     * AI 看起来就像见路就搭的基岩版。现在要求<b>连续 3 格</b>前进方向上都没有任何落脚点
     * （高一格可跳上 / 同层可走 / 低 1-3 格可跳下都不算），真的断路了才搭。
     */
    public static boolean needsBridge(AiPlayer player, Direction dir) {
        Level level = player.level();
        int y = Mth.floor(player.getY()) - 1;
        for (int i = 1; i <= 3; i++) {
            BlockPos ahead = new BlockPos(
                    Mth.floor(player.getX()) + dir.getStepX() * i,
                    y,
                    Mth.floor(player.getZ()) + dir.getStepZ() * i);
            // 前方区块还没加载时 getBlockState 会返回空气，被误判成"前面是空的"——
            // 结果就是 AI 在平地上朝着未加载区域疯狂搭路（浪费方块）。未加载一律不搭。
            if (!level.hasChunkAt(ahead)) {
                return false;
            }
            // 高一格能跳上 / 同层能走（canStand 含"低一格有地"的情况）
            if (AStarPathFinder.canStand(level, ahead) || AStarPathFinder.canStand(level, ahead.above())) {
                return false;
            }
            // 低 1-3 格能跳下去
            for (int d = 1; d <= 3; d++) {
                BlockPos below = ahead.below(d);
                if (!level.hasChunkAt(below)) {
                    return false;
                }
                if (AStarPathFinder.canStand(level, below)) {
                    return false;
                }
                if (AStarPathFinder.solid(level, below)) {
                    break;
                }
            }
        }
        return true;
    }

    /**
     * 到岸检测：前方 5-7 格出现落脚点（同高 / 低 1-3 格）。桥块最多铺到前方 3 格，
     * 5 格开外的地面一定是自然地形 —— 说明搭到对岸了，该收手交回寻路。
     */
    private boolean landAhead() {
        Level level = this.player.level();
        int y = Mth.floor(this.player.getY()) - 1;
        for (int i = 5; i <= 7; i++) {
            BlockPos col = new BlockPos(
                    Mth.floor(this.player.getX()) + this.dir.getStepX() * i,
                    y,
                    Mth.floor(this.player.getZ()) + this.dir.getStepZ() * i);
            if (!level.hasChunkAt(col)) {
                return false;
            }
            for (int d = 0; d <= 3; d++) {
                BlockPos p = col.below(d);
                if (AStarPathFinder.canStand(level, p)) {
                    return true;
                }
                if (AStarPathFinder.solid(level, p)) {
                    break;
                }
            }
        }
        return false;
    }
}