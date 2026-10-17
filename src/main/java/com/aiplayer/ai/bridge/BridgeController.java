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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * 搭路控制器（4 种方式，参考 Vape Scaffold 的实现思路改写为服务端 AI 版本）。
 *
 * <p>参考要点：
 * <ul>
 *   <li>方向由视角 yaw 推导（Vape: {@code getCardinalDirection()}），路径沿该方向延伸。</li>
 *   <li>放置目标优先级（Vape {@code updatePlacement}）：脚下空 -> 补脚下；否则 1 格外 -> 补前方；再否则 2 格外。</li>
 *   <li>边缘检测（Vape {@code ScaffoldEdgeSneakHelper}）：把玩家碰撞箱横向收缩 0.2、整体下移 1 格，
 *       若沿线没有碰撞体说明马上要走空 -> 蹲下。蹲下持续一个随机延迟（Vape "Sneak delay" 100~500ms）后再站起来。</li>
 *   <li>蹲起搭 = 走一步蹲一下；神桥 = 全程疾跑不蹲；Telly = 到边缘起跳、空中放块（Vape {@code TellyBridgeScaffoldMode}，
 *       空中视角改为斜向 yaw、pitch 接近 90°）。</li>
 *   <li>准星瞄方块边缘而不是中心（Vape {@code computePlacementPoint} 的 0.2/0.8 偏移）。</li>
 *   <li>放置没有推进时判定卡住并收手（Vape 用放置历史累计值 >= 10 重置）。</li>
 * </ul>
 *
 * <p>所有动作仍然只是写入 {@link AiInput}（按键 + 视角），物理由原版完成，因此速度正常、不会瞬移。
 */
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

        // ---- 跳跃：Telly 系列在边缘起跳（Vape hasReachedPathEdge）；前方有台阶/矮墙时一律要跳 ----
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
     * 选放置目标：镜像 Vape 的 updatePlacement ——
     * 脚下是空就先补脚下（掉下去了/刚开始搭），否则往前找第一个空位。
     */
    private BlockPos pickTarget(BlockPos feet) {
        // 未加载区块里的“可替换”是假象（读到的是空气），放下去等于往虚空丢方块
        if (!this.player.level().hasChunkAt(feet)) {
            return null;
        }
        if (PlaceUtil.isReplaceable(this.player.level(), feet)) {
            return feet;
        }
        int scan = this.mode == BridgeMode.SPEED_TELLY ? 3 : 2;
        BlockPos p = feet;
        for (int i = 1; i <= scan; i++) {
            p = p.relative(this.dir);
            if (PlaceUtil.isReplaceable(this.player.level(), p)) {
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