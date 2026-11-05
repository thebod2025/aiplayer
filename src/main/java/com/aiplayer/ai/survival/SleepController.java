package com.aiplayer.ai.survival;

import com.aiplayer.ai.AiInput;
import com.aiplayer.ai.path.PathExecutor;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InvUtil;
import com.aiplayer.util.PlaceUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/**
 * 睡觉与作息。
 * <p>
 * 夜里没有敌人、没有任务时，AI 会自己找附近的床走过去躺下；天亮 / 受伤 / 附近有怪 /
 * 床被拆了会自动醒来。玩家说"睡觉"时也会触发（白天会拒绝）。
 * <p>
 * 入睡走原版 {@link net.minecraft.world.entity.LivingEntity#startSleeping(BlockPos)}，
 * 因此睡姿、床的位置、醒来都与真人一致；不用自己摆姿势。
 */
public class SleepController {
    /** 找床的扫描范围（水平半径 / 垂直半径）与间隔（tick） */
    private static final int SCAN_RADIUS_XZ = 12;
    private static final int SCAN_RADIUS_Y = 3;
    private static final int SCAN_INTERVAL = 40;
    /** 附近确实没有床时的重扫间隔（tick）：没必要每 2 秒扫 4375 格 */
    private static final int SCAN_INTERVAL_EMPTY = 160;
    /** 敌对生物检查间隔（tick）：结果缓存，避免睡觉时每 tick 做实体范围查询 */
    private static final int MONSTER_SCAN_INTERVAL = 10;
    /** 离床多近才躺下（原版允许在床周围几格内入睡） */
    private static final double SLEEP_RANGE = 3.0D;
    /** 附近有敌对生物就不睡 */
    private static final double DANGER_RANGE = 8.0D;
    /** 玩家下令后最多记多久（避免过很久才回来睡） */
    private static final int REQUEST_TIMEOUT = 600;
    /** 放床失败后隔多久再试（tick），避免每 tick 换快捷栏 / 尝试放置 */
    private static final int PLACE_INTERVAL = 200;

    private final AiPlayer player;
    private BlockPos bedPos;
    private int scanCooldown;
    private int placeCooldown;
    private int requestTicks;
    private boolean requested;
    /** 敌对生物检查的缓存（10 tick 刷一次） */
    private int monsterCacheTicks;
    private boolean monsterCache;

    public SleepController(AiPlayer player) {
        this.player = player;
    }

    /** 玩家明确下令"去睡觉" */
    public void request() {
        this.requested = true;
        this.requestTicks = REQUEST_TIMEOUT;
        this.scanCooldown = 0;
    }

    /** 玩家下令过来的标记（大脑用它跳过"正在干长活"的门槛） */
    public boolean isRequested() {
        return this.requested;
    }

    public void reset() {
        this.requested = false;
        this.requestTicks = 0;
        this.bedPos = null;
        this.scanCooldown = 0;
        this.placeCooldown = 0;
        this.monsterCacheTicks = 0;
    }

    /**
     * 已经睡着时的维持逻辑。
     *
     * @return true 表示这一 tick 仍然在睡，所有其它行为都要让位
     */
    public boolean holdTick(AiInput in) {
        if (!this.player.isSleeping()) {
            return false;
        }
        // 睡着不做任何动作：不移动、不放方块、不挥刀
        in.resetAll();
        boolean day = !isSleepTime();
        boolean hurt = this.player.hurtTime > 0 || this.player.isOnFire();
        boolean danger = nearbyMonster();
        boolean low = this.player.getHealth() < 8.0F;
        if (day || hurt || danger || low) {
            // 原版也会在床上被破坏 / 天亮时自动唤醒，这里主动醒一次保证状态干净
            this.player.stopSleeping();
            this.bedPos = null;
            this.scanCooldown = 0;
            this.requested = false;
            return false;
        }
        this.requested = false;   // 已经躺下了，不再需要"去睡"的标记
        return true;
    }

    /**
     * 尝试开始睡觉：夜晚（或雷雨）/ 玩家下令时找床 → 走过去 → 躺下。
     *
     * @return true 表示正在去睡或已经睡下，本 tick 不再做别的
     */
    public boolean startTick(AiInput in, PathExecutor path) {
        if (this.requestTicks > 0 && --this.requestTicks == 0) {
            this.requested = false;
        }
        // 只有夜里 / 雷雨才睡；玩家下令也不能违背原版作息（白天睡着也会被立刻唤醒）
        if (!this.requested && !isSleepTime()) {
            return false;
        }
        if (isSleepTime() && !this.requested && !this.player.level().dimensionType().hasSkyLight()) {
            return false;   // 下界 / 末地没有床可睡
        }
        // 有危险 / 血太少就不睡，先处理威胁
        if (this.player.getHealth() < 8.0F || nearbyMonster()) {
            return false;
        }

        BlockPos bed = findBed();
        if (bed == null) {
            // 附近没有床：背包里有床就自己铺一张（不只玩家下令时才铺，
            // 否则两只 AI 晚上各自睡觉时，只有被下过令的那只会铺床）
            if (this.placeCooldown > 0) {
                this.placeCooldown--;
            } else {
                bed = tryPlaceBed();
                this.placeCooldown = PLACE_INTERVAL;
            }
        }
        if (bed == null) {
            // 找不到床就别一直卡在"要睡觉"的状态
            if (this.requested) {
                this.requested = false;
            }
            return false;
        }

        if (nearBed(bed)) {
            path.clear();
            // 走过来的路上可能被另一只 AI 抢先睡了这一半：重新找床（另一半空着就和它挤一张）
            if (!canSleepAt(bed)) {
                this.bedPos = null;
                this.scanCooldown = 0;
                return false;
            }
            if (this.player.startSleeping(bed)) {
                in.resetAll();
                this.bedPos = bed;
                this.requested = false;
                return true;
            }
            // 这格睡不了（被占 / 床不完整 / 不在范围内）：下次重新找
            this.bedPos = null;
            this.scanCooldown = 0;
            return false;
        }

        in.resetAll();
        path.setGoal(bed);
        path.tick();
        return true;
    }

    /** 现在是不是可以睡觉的时间（有天空光照的维度 + 夜晚 / 雷雨） */
    public boolean canSleepNow() {
        return isSleepTime();
    }

    /** 附近有床，或者背包里有一张床可以放下来 */
    public boolean hasBedAvailable() {
        return findBed() != null || findBedStack() != null;
    }

    /** 现在是不是可以睡觉的时间（有天空光照的维度 + 夜晚 / 雷雨） */
    private boolean isSleepTime() {
        if (!this.player.level().dimensionType().hasSkyLight()) {
            return false;
        }
        return this.player.level().getSkyDarken() >= 5 || this.player.level().isThundering();
    }

    /** 附近是否有敌对生物（有就别睡，否则会被咬醒）；结果缓存 10 tick */
    private boolean nearbyMonster() {
        if (this.monsterCacheTicks > 0) {
            this.monsterCacheTicks--;
            return this.monsterCache;
        }
        this.monsterCacheTicks = MONSTER_SCAN_INTERVAL;
        this.monsterCache = !this.player.level().getEntitiesOfClass(Monster.class,
                this.player.getBoundingBox().inflate(DANGER_RANGE), Monster::isAlive).isEmpty();
        return this.monsterCache;
    }

    /** 是不是站在床附近（原版允许在床周围几格内入睡） */
    private boolean nearBed(BlockPos bed) {
        BlockPos feet = this.player.blockPosition();
        int dx = Math.abs(feet.getX() - bed.getX());
        int dz = Math.abs(feet.getZ() - bed.getZ());
        int dy = Math.abs(feet.getY() - bed.getY());
        return dx <= 2 && dz <= 2 && dy <= 2
                && Math.sqrt(dx * dx + dz * dz) <= SLEEP_RANGE;
    }

    /**
     * 找最近的可用床（结果缓存，床被拆 / 睡了没成功时重扫）。
     * <p>
     * 判断"有没有人睡"看的是<b>真的躺着的生物</b>，而不是方块状态里的 {@code OCCUPIED}：
     * 原版只有一张床时，第一只 AI 躺下后整张床都会变成 OCCUPIED，第二只 AI 就永远找不到床。
     * 这里改成：优先整张空床；没有空床时，如果另一半躺着的是另一只 AI，就睡剩下那一半
     * （双人床两个人各占一半）。真人在睡时不去打扰。
     */
    private BlockPos findBed() {
        if (this.bedPos != null && canSleepAt(this.bedPos)) {
            return this.bedPos;
        }
        this.bedPos = null;
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return null;
        }
        BlockPos center = this.player.blockPosition();
        BlockPos bestFree = null;
        double bestFreeSq = Double.MAX_VALUE;
        BlockPos bestShare = null;
        double bestShareSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-SCAN_RADIUS_XZ, -SCAN_RADIUS_Y, -SCAN_RADIUS_XZ),
                center.offset(SCAN_RADIUS_XZ, SCAN_RADIUS_Y, SCAN_RADIUS_XZ))) {
            BlockState state = this.player.level().getBlockState(pos);
            if (!state.is(BlockTags.BEDS)) {
                continue;
            }
            // 统一换算到床头那一半，避免同一张床算两次、也保证躺下的位置和真人一致
            BlockPos head = state.getValue(AbstractBedBlock.PART) == BedPart.HEAD
                    ? pos.immutable()
                    : pos.relative(AbstractBedBlock.getConnectedDirection(state));
            BlockState headState = this.player.level().getBlockState(head);
            if (!headState.is(BlockTags.BEDS) || headState.getValue(AbstractBedBlock.PART) != BedPart.HEAD) {
                continue;   // 床不完整（另一半被拆了）
            }
            BlockPos foot = head.relative(AbstractBedBlock.getConnectedDirection(headState));
            LivingEntity headSleeper = sleeperAt(head);
            LivingEntity footSleeper = sleeperAt(foot);
            BlockPos candidate;
            if (headSleeper == null && isShareable(footSleeper)) {
                candidate = head;
            } else if (footSleeper == null && isShareable(headSleeper)) {
                candidate = foot;
            } else {
                continue;   // 两个半张都有人（或躺着的是真人）
            }
            boolean free = (candidate == head ? footSleeper : headSleeper) == null;
            double d2 = center.distSqr(candidate);
            if (free && d2 <= 36.0D) {
                // 6 格内就有空床：不用再扫完整片区域
                this.bedPos = candidate;
                this.scanCooldown = SCAN_INTERVAL;
                return candidate;
            }
            if (free) {
                if (d2 < bestFreeSq) {
                    bestFreeSq = d2;
                    bestFree = candidate;
                }
            } else if (d2 < bestShareSq) {
                bestShareSq = d2;
                bestShare = candidate;
            }
        }
        this.bedPos = bestFree != null ? bestFree : bestShare;
        // 附近根本没床时把重扫间隔拉长，别整夜每 2 秒扫一遍
        this.scanCooldown = this.bedPos != null ? SCAN_INTERVAL : SCAN_INTERVAL_EMPTY;
        return this.bedPos;
    }

    /** 这一格床现在能不能由本 AI 睡：这一半空着，另一半空着或躺着的是另一只 AI */
    private boolean canSleepAt(BlockPos pos) {
        BlockState state = this.player.level().getBlockState(pos);
        if (!state.is(BlockTags.BEDS)) {
            return false;
        }
        if (sleeperAt(pos) != null) {
            return false;   // 这一半已经有人（真人 / AI / 村民）躺着了
        }
        BlockPos other = pos.relative(AbstractBedBlock.getConnectedDirection(state));
        return isShareable(sleeperAt(other));
    }

    /** 另一半躺着的是不是另一只 AI（空着也算可以睡） */
    private boolean isShareable(LivingEntity sleeper) {
        return sleeper == null || (sleeper instanceof AiPlayer && sleeper != this.player);
    }

    /** 某一格床上躺着的生物（不含自己）；没有就返回 null */
    private LivingEntity sleeperAt(BlockPos pos) {
        // 玩家（真人 + AI）统一走玩家列表，避免依赖实体索引
        for (ServerPlayer p : this.player.serverLevel().players()) {
            if (p == this.player || !p.isSleeping()) {
                continue;
            }
            if (p.getSleepingPos().map(pos::equals).orElse(false)) {
                return p;
            }
        }
        // 村民等非玩家生物也会睡床，同样占着格子
        for (LivingEntity e : this.player.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(pos).inflate(0.5D),
                e -> !(e instanceof Player) && e.isSleeping()
                        && e.getSleepingPos().map(pos::equals).orElse(false))) {
            return e;
        }
        return null;
    }

    /**
     * 附近没有床但背包里有床：在脚边空地上放一张。
     * <p>
     * 只做一次尝试，放不下去就算了 —— 走路 / 寻路仍然由原版完成。
     */
    private BlockPos tryPlaceBed() {
        ItemStack bedStack = findBedStack();
        if (bedStack == null || !InvUtil.selectStack(this.player, bedStack)) {
            return null;
        }
        BlockPos base = this.player.blockPosition();
        Direction facing = this.player.getDirection();
        Direction[] dirs = {facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite()};
        for (Direction d : dirs) {
            for (int step = 1; step <= 2; step++) {
                BlockPos pos = base.relative(d, step);
                if (!PlaceUtil.isReplaceable(this.player.level(), pos)) {
                    continue;
                }
                if (this.player.level().getBlockState(pos.below()).isAir()) {
                    continue;   // 底下没支撑，原版也会放失败
                }
                if (!PlaceUtil.placeAt(this.player, pos, Direction.UP)) {
                    continue;
                }
                if (this.player.level().getBlockState(pos).is(BlockTags.BEDS)) {
                    this.bedPos = pos.immutable();
                    this.scanCooldown = 0;
                    return this.bedPos;
                }
            }
        }
        return null;
    }

    /** 背包里任意一张床（床在 26.3 是普通 BlockItem，按注册名后缀判断） */
    private ItemStack findBedStack() {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && id.getPath().endsWith("_bed")) {
                return stack;
            }
        }
        return null;
    }
}
