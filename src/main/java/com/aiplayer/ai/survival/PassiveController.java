package com.aiplayer.ai.survival;

import com.aiplayer.ai.AiInput;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.InvUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;

/**
 * 空闲生计控制器：没任务没敌人时像真人一样过日子。
 * <ol>
 *   <li><b>收庄稼</b>：扫描附近成熟的作物收掉，然后原位补种；</li>
 *   <li><b>钓鱼</b>：有鱼竿 + 附近有水就到岸边抛竿，随机等一会儿收杆，鱼进背包；</li>
 *   <li><b>驯服动物</b>：有骨头靠近野狼（有鱼靠近猫）就右键喂食驯服。</li>
 * </ol>
 * 所有交互都走原版路径（右键 / 挖掘），掉落、经验、音效由原版结算。
 */
public class PassiveController {
    private static final int SCAN_RANGE = 10;
    private static final int PRIO = InventoryLock.PRIO_TASK;

    private final AiPlayer player;
    private final Random rng = new Random();

    // ---- 种田 ----
    private int farmCooldown;
    /** 收完等补种的位置队列 */
    private final ArrayDeque<BlockPos> replantQueue = new ArrayDeque<>();

    // ---- 钓鱼 ----
    private enum FishStage { NONE, WALK, CAST, WAIT, REEL, COOLDOWN }
    private FishStage fish = FishStage.NONE;
    private int fishTicks;
    private int fishLostHooks;
    private BlockPos waterPos;

    // ---- 驯服 ----
    private int tameCooldown;
    private Entity tameTarget;
    private int tameAttempts;

    public PassiveController(AiPlayer player) {
        this.player = player;
    }

    /** @return true 表示本 tick 由生计活动接管 */
    public boolean tick(AiInput in) {
        if (tryStore(in)) {
            return true;
        }
        if (tryReplant(in)) {
            return true;
        }
        if (tryFarm()) {
            return true;
        }
        if (tryTame(in)) {
            return true;
        }
        if (tryFish(in)) {
            return true;
        }
        return false;
    }

    /** 有任务在身时叫停（AiBrain 进战斗 / 移动任务前不会走到这，保险起见留个口子） */
    public void reset() {
        this.fish = FishStage.NONE;
        this.tameTarget = null;
        this.waterPos = null;
    }

    // ------------------------------------------------------------------
    // 存箱：同一物品攒太多就去附近箱子入库
    // ------------------------------------------------------------------

    private int storeCooldown = 600;
    private BlockPos chestTarget;

    private boolean tryStore(AiInput in) {
        if (this.chestTarget != null) {
            if (!com.aiplayer.ai.interact.InteractController.isContainerBlock(this.player, this.chestTarget)) {
                this.chestTarget = null;
                return false;
            }
            if (!this.player.blockPosition().closerThan(this.chestTarget, 3.0D)) {
                return true;   // 在路上（walkTo 已排队）
            }
            ItemStack surplus = findSurplus();
            if (surplus.isEmpty()) {
                this.chestTarget = null;
                return false;
            }
            Item item = surplus.getItem();
            this.player.brain().depositInto(this.chestTarget, s -> s.getItem() == item);
            this.chestTarget = null;
            this.storeCooldown = 600;
            return true;
        }
        if (this.storeCooldown > 0) {
            this.storeCooldown--;
            return false;
        }
        this.storeCooldown = 400;
        if (findSurplus().isEmpty()) {
            return false;
        }
        BlockPos chest = findChest();
        if (chest == null) {
            this.storeCooldown = 1200;   // 附近没箱子，多等一会儿
            return false;
        }
        this.chestTarget = chest;
        this.player.brain().walkTo(chest);
        return false;   // 这 tick 不接管，先让寻路起步
    }

    /** 同一物品总数 >= 32 且不是食物 / 工具 / 武器 / 盔甲 / 保命件，才算多余产出 */
    private ItemStack findSurplus() {
        var inv = this.player.getInventory();
        java.util.Map<Item, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                counts.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        Item best = null;
        int bestN = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null || bestN < 32) {
            return ItemStack.EMPTY;
        }
        String cls = best.getClass().getSimpleName();
        String path = BuiltInRegistries.ITEM.getKey(best).getPath();
        if (FoodManager.isFood(new ItemStack(best))
                || cls.contains("Pickaxe") || cls.contains("Shovel") || cls.contains("Axe")
                || cls.contains("Sword") || cls.contains("Hoe") || cls.contains("Bow")
                || cls.contains("Rod") || cls.contains("Armor") || cls.contains("Elytra")
                || cls.contains("Bucket") || cls.contains("Shield") || cls.contains("Pearl")
                || cls.contains("Totem") || cls.contains("Crystal")
                || path.contains("pickaxe") || path.contains("shovel") || path.contains("_axe")
                || path.contains("_sword") || path.contains("_hoe") || path.contains("_bow")
                || path.contains("rod") || path.contains("helmet") || path.contains("chestplate")
                || path.contains("leggings") || path.contains("boots") || path.contains("bucket")
                || path.contains("shield") || path.contains("pearl") || path.contains("totem")
                || path.contains("elytra") || path.contains("obsidian")) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == best) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 附近 13x7x13 找一个箱子 / 木桶 / 潜影盒 */
    private BlockPos findChest() {
        Level level = this.player.level();
        BlockPos base = this.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-6, -3, -6), base.offset(6, 3, 6))) {
            if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.EntityBlock)) {
                continue;
            }
            var be = level.getBlockEntity(pos);
            if (be == null) {
                continue;
            }
            String n = be.getClass().getSimpleName();
            if (n.contains("Chest") || n.contains("Barrel") || n.contains("ShulkerBox")) {
                return pos.immutable();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 种田：收成熟作物 + 原位补种
    // ------------------------------------------------------------------

    private boolean enabledFarm() {
        return this.player.profile() != null && this.player.profile().getFeature("farm");
    }

    private boolean tryReplant(AiInput in) {
        if (!enabledFarm() || this.replantQueue.isEmpty()) {
            return false;
        }
        BlockPos pos = this.replantQueue.peekFirst();
        Level level = this.player.level();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CropBlock) {
            return false;   // 还没收掉（也许只是路过看见的），继续等
        }
        if (!state.isAir() && !state.canBeReplaced()) {
            this.replantQueue.pollFirst();   // 位置被别的方块占了，放弃补种
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        if (!below.getBlock().getClass().getSimpleName().contains("Farm")) {
            this.replantQueue.pollFirst();   // 耕地没了，没法补
            return false;
        }
        Item seed = findSeedFor(pos);
        if (seed == null) {
            this.replantQueue.pollFirst();
            return false;
        }
        if (!holdItem(seed, "farm")) {
            return false;   // 下个 tick 再种
        }
        Vec3 hitPoint = new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        this.player.lookAt(hitPoint, 60.0F);
        this.player.gameMode.useItemOn(this.player, level,
                this.player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND,
                new BlockHitResult(hitPoint, Direction.UP, pos.below(), false));
        this.replantQueue.pollFirst();
        return true;
    }

    private boolean tryFarm() {
        if (!enabledFarm() || this.farmCooldown > 0) {
            if (this.farmCooldown > 0) {
                this.farmCooldown--;
            }
            return false;
        }
        this.farmCooldown = 120;
        Level level = this.player.level();
        BlockPos base = this.player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-SCAN_RANGE, -2, -SCAN_RANGE),
                base.offset(SCAN_RANGE, 3, SCAN_RANGE))) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof CropBlock)) {
                continue;
            }
            if (!isMature(state)) {
                continue;   // 还没熟
            }
            double d = base.distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        if (best == null) {
            return false;
        }
        if (this.replantQueue.size() < 16) {
            this.replantQueue.addLast(best);
        }
        this.farmCooldown = 400;   // 给挖掘任务留时间
        this.player.brain().mineAt(best);
        return true;
    }

    /** 作物是否成熟：按 state 的 "age" 属性是否达到最大值判断（避开 CropBlock 的 protected 访问） */
    private static boolean isMature(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if (prop instanceof IntegerProperty age && age.getName().equals("age")) {
                return state.getValue(age) >= age.getPossibleValues().size() - 1;
            }
        }
        return false;
    }

    /** 按“作物注册名”匹配背包里的种子：wheat→wheat_seeds、carrot→carrot、potato→potato */
    private Item findSeedFor(BlockPos cropPos) {
        Level level = this.player.level();
        String cropPath = BuiltInRegistries.BLOCK.getKey(level.getBlockState(cropPos).getBlock()).getPath();
        String want = cropPath.endsWith("_seeds") ? cropPath : cropPath + "_seeds";
        // 胡萝卜 / 土豆直接拿本体补种
        String alt = cropPath;
        var inv = this.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (path.equals(want) || path.equals(alt)) {
                return s.getItem();
            }
        }
        return null;
    }

    /** 把指定物品切到手上（走槽位锁）；没快捷栏空位时返回 false */
    private boolean holdItem(Item item, String owner) {
        int slot = InvUtil.findHotbarSlot(this.player, item);
        if (slot < 0) {
            slot = InvUtil.pullToHotbar(this.player, item);
            if (slot < 0) {
                return false;
            }
            slot = InvUtil.findHotbarSlot(this.player, item);
        }
        return slot >= 0 && this.player.selectSlot(owner, PRIO, slot);
    }

    // ------------------------------------------------------------------
    // 钓鱼
    // ------------------------------------------------------------------

    private boolean enabledFish() {
        return this.player.profile() != null && this.player.profile().getFeature("fishing");
    }

    private boolean tryFish(AiInput in) {
        if (!enabledFish()) {
            if (this.fish != FishStage.NONE) {
                this.fish = FishStage.NONE;
            }
            return false;
        }
        switch (this.fish) {
            case NONE -> {
                if (!InvUtil.has(this.player, Items.FISHING_ROD)) {
                    return false;
                }
                if (this.fishTicks > 0) {
                    this.fishTicks--;
                    return false;
                }
                this.waterPos = findWater();
                BlockPos spot = this.waterPos != null ? walkSpot(this.waterPos) : null;
                if (spot == null) {
                    this.fishTicks = 600;   // 附近没水，过会儿再看
                    return false;
                }
                this.player.brain().walkTo(spot);
                this.fish = FishStage.WALK;
                this.fishTicks = 300;
                return false;   // 这 tick 不接管，先让寻路起步
            }
            case WALK -> {
                if (this.waterPos == null || !nearWater()) {
                    if (--this.fishTicks <= 0) {
                        this.fish = FishStage.NONE;
                    }
                    return true;   // 还在走过去，别乱晃
                }
                this.fish = FishStage.CAST;
                // fall through 语义：下一 tick 直接进入抛竿
                return true;
            }
            case CAST -> {
                if (!holdItem(Items.FISHING_ROD, "fish")) {
                    this.fish = FishStage.NONE;
                    return false;
                }
                if (this.waterPos == null) {
                    this.fish = FishStage.NONE;
                    return false;
                }
                // 面向水面抛竿
                this.player.lookAt(new Vec3(this.waterPos.getX() + 0.5D,
                        this.waterPos.getY() + 0.9D, this.waterPos.getZ() + 0.5D), 60.0F);
                in.useItem = true;
                this.fish = FishStage.WAIT;
                this.fishTicks = 140 + this.rng.nextInt(260);
                this.fishLostHooks = 0;
                return true;
            }
            case WAIT -> {
                if (!InvUtil.has(this.player, Items.FISHING_ROD)) {
                    this.fish = FishStage.NONE;
                    return false;
                }
                FishingHook hook = ownedHook();
                if (hook == null) {
                    // 浮漂没了（断线 / 被打掉）：收杆重抛
                    if (++this.fishLostHooks > 15) {
                        this.fish = FishStage.REEL;
                    }
                    return true;
                }
                if (--this.fishTicks <= 0) {
                    this.fish = FishStage.REEL;
                }
                return true;
            }
            case REEL -> {
                if (holdItem(Items.FISHING_ROD, "fish")) {
                    in.useItem = true;   // 收杆：钓到的鱼原版直接进背包
                }
                this.fish = FishStage.COOLDOWN;
                this.fishTicks = 60 + this.rng.nextInt(120);
                return true;
            }
            case COOLDOWN -> {
                if (--this.fishTicks <= 0) {
                    this.waterPos = findWater();
                    if (this.waterPos != null) {
                        this.fish = FishStage.WALK;
                        this.fishTicks = 300;
                        BlockPos spot = walkSpot(this.waterPos);
                        if (spot != null) {
                            this.player.brain().walkTo(spot);
                        }
                    } else {
                        this.fish = FishStage.NONE;
                    }
                }
                return false;
            }
        }
        return false;
    }

    private boolean nearWater() {
        return this.waterPos != null && this.player.blockPosition().closerThan(this.waterPos, 3.5D);
    }

    /** 水面方块旁边的落脚点（实心地面 + 头顶两格空），找不到返回 null */
    private BlockPos walkSpot(BlockPos water) {
        Level level = this.player.level();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = water.relative(d);
            if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty()
                    && level.getBlockState(side.above()).isAir()
                    && level.getBlockState(side.above(2)).isAir()) {
                return side;
            }
        }
        return null;
    }

    private FishingHook ownedHook() {
        List<FishingHook> hooks = this.player.level().getEntitiesOfClass(FishingHook.class,
                this.player.getBoundingBox().inflate(40.0D));
        for (FishingHook h : hooks) {
            if (h.getOwner() == this.player) {
                return h;
            }
        }
        return null;
    }

    /** 附近找个能站的水边：水面方块 + 上方两格是空气 */
    private BlockPos findWater() {
        Level level = this.player.level();
        BlockPos base = this.player.blockPosition();
        for (int attempt = 0; attempt < 24; attempt++) {
            int dx = this.rng.nextInt(2 * SCAN_RANGE + 1) - SCAN_RANGE;
            int dz = this.rng.nextInt(2 * SCAN_RANGE + 1) - SCAN_RANGE;
            for (int dy = -2; dy <= 3; dy++) {
                BlockPos pos = base.offset(dx, dy, dz);
                if (!level.getFluidState(pos).is(FluidTags.WATER)) {
                    continue;
                }
                if (!level.getBlockState(pos.above()).isAir() || !level.getBlockState(pos.above(2)).isAir()) {
                    continue;
                }
                return pos;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 驯服动物：骨头喂狼、鱼喂猫
    // ------------------------------------------------------------------

    private boolean enabledTame() {
        return this.player.profile() != null && this.player.profile().getFeature("tame");
    }

    private boolean tryTame(AiInput in) {
        if (!enabledTame()) {
            if (this.tameTarget != null) {
                this.tameTarget = null;
            }
            return false;
        }
        if (this.tameTarget != null && (!this.tameTarget.isAlive() || alreadyTamed(this.tameTarget))) {
            this.tameTarget = null;
        }
        if (this.tameTarget == null) {
            if (this.tameCooldown > 0) {
                this.tameCooldown--;
                return false;
            }
            this.tameCooldown = 200;
            this.tameTarget = findTameable();
            if (this.tameTarget != null) {
                this.tameAttempts = 0;
                this.player.brain().walkTo(this.tameTarget.blockPosition());
            }
            return false;
        }
        double dist = Math.sqrt(this.player.distanceToSqr(this.tameTarget));
        if (dist > 2.8D) {
            if (++this.tameAttempts > 200) {
                this.tameTarget = null;   // 走不到就放弃
                this.tameCooldown = 600;
            }
            return true;
        }
        // 到跟前了：每 12 tick 右键喂一次（原版驯服要喂好几次）
        if (this.tameAttempts % 12 == 0) {
            Item bait = baitFor(this.tameTarget);
            if (bait == null || !holdItem(bait, "tame")) {
                this.tameTarget = null;
                this.tameCooldown = 600;
                return false;
            }
            this.player.lookAt(this.tameTarget.getX(), this.tameTarget.getEyeY(),
                    this.tameTarget.getZ(), 40.0F);
            this.player.interactOn(this.tameTarget, InteractionHand.MAIN_HAND, this.tameTarget.position());
        }
        if (++this.tameAttempts > 60) {
            this.tameTarget = null;
            this.tameCooldown = 1200;
            return false;
        }
        return true;
    }

    private boolean alreadyTamed(Entity e) {
        return e instanceof TamableAnimal t && t.isTame();
    }

    /** 有骨头找野狼，有生鱼找猫；都没有就空手而归 */
    private Entity findTameable() {
        Item bait = InvUtil.has(this.player, Items.BONE) ? Items.BONE
                : (InvUtil.has(this.player, Items.COD) ? Items.COD
                : (InvUtil.has(this.player, Items.SALMON) ? Items.SALMON : null));
        if (bait == null) {
            return null;
        }
        String want = bait == Items.BONE ? "Wolf" : "Cat";
        List<LivingEntity> list = this.player.level().getEntitiesOfClass(LivingEntity.class,
                this.player.getBoundingBox().inflate(20.0D));
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : list) {
            if (e == this.player || !e.isAlive()) {
                continue;
            }
            if (!e.getClass().getSimpleName().contains(want) || alreadyTamed(e)) {
                continue;
            }
            double d = this.player.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private Item baitFor(Entity target) {
        String n = target.getClass().getSimpleName();
        if (n.contains("Wolf")) {
            return InvUtil.has(this.player, Items.BONE) ? Items.BONE : null;
        }
        if (n.contains("Cat") || n.contains("Ocelot")) {
            if (InvUtil.has(this.player, Items.COD)) {
                return Items.COD;
            }
            return InvUtil.has(this.player, Items.SALMON) ? Items.SALMON : null;
        }
        return null;
    }
}
