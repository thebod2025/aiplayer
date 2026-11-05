package com.aiplayer.ai;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.ai.bridge.BridgeController;
import com.aiplayer.ai.bridge.BridgeMode;
import com.aiplayer.ai.chat.ChatController;
import com.aiplayer.ai.combat.CombatController;
import com.aiplayer.ai.combat.CrystalPvpController;
import com.aiplayer.ai.combat.BlockTacticsController;
import com.aiplayer.ai.combat.AnchorPvpController;
import com.aiplayer.ai.combat.WindChargeMaceController;
import com.aiplayer.ai.combat.ElytraSpearController;
import com.aiplayer.ai.combat.RangedController;
import com.aiplayer.ai.gather.ChopController;
import com.aiplayer.ai.gather.MiningController;
import com.aiplayer.ai.inv.EquipmentManager;
import com.aiplayer.ai.interact.InteractController;
import com.aiplayer.ai.path.PathExecutor;
import com.aiplayer.ai.survival.EscapeController;
import com.aiplayer.ai.survival.ExploreController;
import com.aiplayer.ai.survival.FoodManager;
import com.aiplayer.api.LlmClient;
import com.aiplayer.compat.CompatManager;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.entity.AiPlayerManager;
import com.aiplayer.util.ItemIdUtil;
import com.aiplayer.util.PlaceUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 工作相关决策：挖掘 / 放置 / 合成 / 熔炼 / 酿造 / 容器 / 交易 / 采集任务。 */
class AiBrainWork extends AiBrainMovement {
    AiBrainWork(AiPlayer player, AiPlayerManager manager, AiProfile profile) {
        super(player, manager, profile);
    }

    /**
     * 生计分支：挖矿 / 砍树 / 探索。
     * <p>
     * 顺序就是优先级：砍树（树就在眼前，先收拾完）→ 挖矿（矿脉 / 支巷）→ 探索。
     * 三者都只在没有任务、没有敌人时才会轮到（本方法排在 taskTick / fightTick 之后）。
     *
     * @return true 表示本 tick 已经被生计占住
     */
    boolean jobTick() {
        // 跟随玩家时不自己乱跑（跟丢主人比挖矿严重得多），
        // 但玩家明确下令"去砍树/去挖矿/去看看"时会临时挂起跟随（followSuspended），
        // 否则第一行就 return false，砍树/挖矿控制器永远没机会跑 —— 表现就是"AI 答应了却原地不动"。
        if (this.followName != null && !this.followName.isBlank() && this.followSuspended == null) {
            return false;
        }
        if (this.profile.mine && this.chop.tick()) {
            setState(AiState.MINING);
            return true;
        }
        if (this.profile.mine && this.mining.tick()) {
            setState(AiState.MINING);
            return true;
        }
        if (this.profile.explore && this.explore.tick()) {
            setState(AiState.MOVING);
            return true;
        }
        // 活干完了：把跟随接回来，AI 自己走回主人身边
        if (this.followSuspended != null && !this.chop.isBusy() && !this.mining.isBusy()
                && !this.explore.isActive()) {
            this.followName = this.followSuspended;
            this.followSuspended = null;
        }
        return false;
    }

    /**
     * 明确任务分发：玩家 / 模型下达的挖、放、煮、翻箱子、交易、合成、熔炼、酿造、走路、跟随。
     * <p>
     * 放在 Work 里而不是 Team：这里调用的全是工作与移动子系统，与队伍逻辑无关，
     * 只是团队类恰好继承在工作类之上才“看得见”这些方法。
     *
     * @return true 表示本 tick 已经被某个明确任务占住
     */
    boolean taskTick(AiInput in) {
        if (this.mineTarget != null) {
            return mineTick(in);
        }
        if (this.placeTarget != null) {
            return placeTick(in);
        }
        if (this.cookTarget != null) {
            return cookTick(in);
        }
        if (this.containerTarget != null) {
            return containerTick(in);
        }
        if (this.tradeTarget != null) {
            return tradeTick(in);
        }
        if (this.craftTarget != null) {
            return craftTick(in);
        }
        if (this.smeltTarget != null) {
            return smeltTick(in);
        }
        if (this.brewTarget != null) {
            return brewTick(in);
        }
        if (this.walkTarget != null) {
            return walkTick(in);
        }
        if (this.followName != null && !this.followName.isBlank()) {
            return followTick(in);
        }
        return false;
    }

    /**
     * 玩家明确下令干活时，把"跟随主人"临时挂起。
     * <p>
     * 只记{@code followName}，不动 {@code profile.follow}：干完活 jobTick 自动接回，
     * GUI 里配置的跟随目标也不会被改掉。
     */
    void suspendFollowForJob() {
        if (this.followName != null && !this.followName.isBlank()) {
            this.followSuspended = this.followName;
            this.followName = null;
            this.path.clear();
        }
    }

    /** 砍树指令：先解除跟随限制，再真正启动砍树控制器 */
    void startChopNow() {
        suspendFollowForJob();
        this.chop.start();
    }

    boolean passiveTick(AiInput in) {
        // 有任务在身（挖 / 放 / 走路 / 开箱 / 战斗 / 采矿砍树探索）就先不干副业，任务做完自己会续上
        if (this.mineTarget != null || this.placeTarget != null || this.walkTarget != null
                || this.containerTarget != null || this.tradeTarget != null
                || this.attackTarget != null || !this.buildQueue.isEmpty()
                || this.mining.isBusy() || this.chop.isBusy() || this.explore.isActive()) {
            return false;
        }
        if (this.passive.tick(in)) {
            setState(AiState.INTERACT);
            return true;
        }
        return false;
    }

    boolean mineTick(AiInput in) {
        // 够不够得着按原版算：从眼睛到方块碰撞盒的距离 < 方块交互距离（4.5）。
        // 以前从"脚"量 4.5 格，脚上方 5 格以上的方块永远够不到 —— 高一点的树砍不完、
        // 陡一点的矿挖不到，表现就是 AI 站在方块下面发呆。
        if (this.player.isWithinBlockInteractionRange(this.mineTarget, 0.0D)) {
            this.path.clear();
            BlockPos target = this.mineTarget;
            BlockState state = this.player.level().getBlockState(target);
            Vec3 center = new Vec3(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
            this.player.lookAt(center, 35.0F);
            // 挖之前先把最合适的工具换到手上，不然就是空手慢慢抠（石头会挖到天荒地老）。
            // 换完工具再交给 BlockBreaker 按"工具速度 + 方块硬度"算耗时。
            equipBestTool(state);
            in.mining = true;
            in.mineTarget = target;
            setState(AiState.MINING);
            this.mineTicks++;
            if (this.player.level().getBlockState(target).isAir()) {
                // 挖到了；挖矿的掉落物会被原版自动拾取
                if (this.mineFromJob) {
                    // 采集控制器排的活：交回控制器接着下一块（矿脉 / 整棵树 / 支巷）
                    this.mineFromJob = false;
                    this.mineTarget = null;
                } else if (this.profile.mine && RNG.nextFloat() < 0.4F) {
                    this.mineTarget = nextMineTarget(target);
                    this.mineTicks = 0;
                } else {
                    this.mineTarget = null;
                }
            } else if (this.mineTicks > 400) {
                this.mineTarget = null;
                this.mineFromJob = false;
            }
            return true;
        }
        // 就在头顶 / 脚边但够不着（砍树时树干上半段那块）：跳一下把眼睛抬上去
        BlockPos foot = this.player.blockPosition();
        if (this.player.onGround()
                && this.mineTarget.getY() > foot.getY()
                && Math.abs(this.mineTarget.getX() - foot.getX()) <= 1
                && Math.abs(this.mineTarget.getZ() - foot.getZ()) <= 1
                && this.player.isWithinBlockInteractionRange(this.mineTarget, 1.4D)) {
            in.jump = true;
            setState(AiState.MINING);
            return true;
        }
        this.path.setGoal(this.mineTarget);
        if (this.path.tick()) {
            this.mineTarget = null;
            return true;
        }
        setState(AiState.MOVING);
        return true;
    }

    /**
     * 挖方块前切到最合适的工具（镐挖石头、斧砍树、铲挖土……）。
     * <p>
     * 只从快捷栏里挑：{@code selectSlot} 只能选 0~8，主背包里的工具拿不出来。
     * 挑不到趁手的就保持原样（空手也能挖，只是慢）。
     * <p>
     * public：挖掘之外，"沿路径挖穿障碍"（PathExecutor）也要用同一套换工具逻辑。
     */
    public void equipBestTool(BlockState state) {
        equipBestTool(state, "mine", com.aiplayer.util.InventoryLock.PRIO_TASK);
    }

    /**
     * 带“锁归属”的换工具版本。
     * <p>
     * 搭路控制器全程持有 {@code "bridge"} 锁（优先级 60 &gt; 任务锁 40），用默认优先级去换工具
     * 会 {@code acquire} 失败 —— 表现就是“搭路时不会切工具，一直拿着手里的方块去抠石头”。
     * 所以让调用方把自己的锁名 / 优先级传进来，用同一把锁把工具换到手上。
     *
     * @return 换到的快捷栏槽位；没有趁手工具或抢不到锁时返回 -1
     */
    public int equipBestTool(BlockState state, String owner, int prio) {
        Inventory inv = this.player.getInventory();
        ItemStack held = this.player.getMainHandItem();
        float bestSpeed = held.isCorrectToolForDrops(state) ? held.getDestroySpeed(state) : -1.0F;
        int bestSlot = -1;
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            // 快捷栏里没有趁手工具时再去主背包（9~35）找，并把最好的那把搬到快捷栏。
            // 以前只扫 0~8：玩家一开局工具都在主背包里，AI 就永远切不过来 ——
            // 表现就是"AI 不会自己切工具"，一路空手抠石头（慢到离谱）。
            Item bestItem = null;
            for (int i = Inventory.SELECTION_SIZE; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) {
                    continue;
                }
                float speed = stack.getDestroySpeed(state);
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestItem = stack.getItem();
                }
            }
            if (bestItem == null) {
                return -1;   // 身上真没有更好的工具：就用手上这把（空手也能挖，只是慢）
            }
            int moved = com.aiplayer.util.InvUtil.pullToHotbar(this.player, bestItem);
            if (moved < 0) {
                return -1;
            }
            bestSlot = moved;
        }
        return this.player.selectSlot(owner, prio, bestSlot) ? bestSlot : -1;
    }

    BlockPos nextMineTarget(BlockPos pos) {
        // 继续往下挖一格（挖矿时最自然的方向）
        BlockPos below = pos.below();
        if (!this.player.level().getBlockState(below).isAir() && !CompatManager.isProtected(this.player.level().getBlockState(below))) {
            return below;
        }
        return null;
    }

    boolean placeTick(AiInput in) {
        // 手上必须是方块才能放。以前只扫快捷栏：方块放在主背包里就永远切不过来 ——
        // 表现就是"AI 不会切物品放置"。现在没有方块时会把主背包里的方块换到手上。
        if (!PlaceUtil.holdingBlock(this.player)) {
            ensureBlockInHand("build", com.aiplayer.util.InventoryLock.PRIO_TASK);
        }
        if (!PlaceUtil.holdingBlock(this.player)) {
            if (!hasAnyBlock()) {
                // 身上真没方块：放弃这次放置（有建造队列就整条放弃）
                this.placeTarget = null;
                this.buildQueue.clear();
                return false;
            }
            // 有方块但槽位被别的动作占着（如战斗中）：等下一 tick 再试，别丢目标
            setState(AiState.PLACING);
            return true;
        }
        // 目标格已经被别人（玩家 / 另一只 AI）填上了：不必再走过去，直接下一格
        if (!PlaceUtil.isReplaceable(this.player.level(), this.placeTarget)) {
            advanceBuild();
            if (this.placeTarget == null) {
                return false;
            }
        }
        if (this.player.blockPosition().closerThan(this.placeTarget, 4.0D)) {
            this.path.clear();
            Vec3 center = new Vec3(this.placeTarget.getX() + 0.5D, this.placeTarget.getY() + 0.5D, this.placeTarget.getZ() + 0.5D);
            this.player.lookAt(center, 35.0F);
            if (PlaceUtil.placeAt(this.player, this.placeTarget, Direction.UP)) {
                this.buildRetry = 0;
                advanceBuild(); // 队列里还有就继续下一块，空了自然结束
            } else if (++this.buildRetry >= 5) {
                // 这一格反复放不上（被挡住 / 面朝向不对）：跳过它，否则整条建造队列
                // 会永远卡在同一格上 —— 表现就是"AI 说要建房子，然后站着不动"。
                this.buildRetry = 0;
                advanceBuild();
            }
            setState(AiState.PLACING);
            return true;
        }
        this.path.setGoal(this.placeTarget);
        if (this.path.tick()) {
            this.placeTarget = null;
            this.buildQueue.clear();
            this.buildRetry = 0;
            this.buildTicks = 0;
            return true;
        }
        // 走太久还没到（目标在山对面 / 被墙挡住）：跳过这一格，别让建造整体卡死。
        if (++this.buildTicks > 300) {
            this.buildTicks = 0;
            this.buildRetry = 0;
            if (!this.buildQueue.isEmpty()) {
                advanceBuild();      // 队列里还有：换下一格
            } else {
                this.placeTarget = null;
            }
        }
        setState(AiState.MOVING);
        return true;
    }

    boolean cookTick(AiInput in) {
        if (this.player.blockPosition().closerThan(this.cookTarget, 3.0D)) {
            this.path.clear();
            this.food.cookAt(this.cookTarget);
            this.cookTarget = null;
            return true;
        }
        this.path.setGoal(this.cookTarget);
        if (this.path.tick()) {
            this.cookTarget = null;
            return true;
        }
        setState(AiState.MOVING);
        return true;
    }

    /** 走到容器跟前翻东西 */
    boolean containerTick(AiInput in) {
        BlockPos pos = this.containerTarget;
        if (!InteractController.isContainerBlock(this.player, pos)) {
            // 容器没了（被拆了 / 本来就不是容器）：放弃
            this.containerTarget = null;
            return false;
        }
        if (this.player.blockPosition().closerThan(pos, 3.5D)) {
            this.path.clear();
            setState(AiState.INTERACT);
            this.interact.lootContainer(pos);
            this.containerTarget = null;
            return true;
        }
        this.path.setGoal(pos);
        if (this.path.tick()) {
            this.containerTarget = null;
            return true;
        }
        setState(AiState.MOVING);
        return true;
    }

    /** 走到村民跟前交易 */
    boolean tradeTick(AiInput in) {
        Entity target = this.tradeTarget;
        if (!(target instanceof Villager villager) || !villager.isAlive() || villager.isRemoved()) {
            this.tradeTarget = null;
            return false;
        }
        if (this.player.distanceToSqr(villager) <= 9.0D) {
            this.path.clear();
            setState(AiState.INTERACT);
            this.interact.trade(villager);
            this.tradeTarget = null;
            return true;
        }
        this.path.setGoal(villager.blockPosition());
        if (this.path.tick()) {
            this.tradeTarget = null;
            return true;
        }
        setState(AiState.MOVING);
        return true;
    }

    /** 走到工作台跟前，按目标物品合成一份（材料 / 成品都走原版） */
    boolean craftTick(AiInput in) {
        BlockPos pos = this.craftTarget;
        if (pos == null || this.craftWant == null || !stationAt(pos, "CraftingTable")) {
            this.craftTarget = null;
            this.craftWant = null;
            return false;
        }
        if (this.player.blockPosition().closerThan(pos, 3.5D)) {
            this.path.clear();
            setState(AiState.INTERACT);
            this.interact.craft(pos, this.craftWant, this.craftTimes);
            this.craftTarget = null;
            this.craftWant = null;
            return true;
        }
        this.path.setGoal(pos);
        if (this.path.tick()) {
            this.craftTarget = null;
            this.craftWant = null;
            return true;
        }
        setState(AiState.MOVING);
        return true;
    }

    /** 走到熔炉跟前：先收成品，再把原料和燃料塞进去 */
    boolean smeltTick(AiInput in) {
        BlockPos pos = this.smeltTarget;
        if (pos == null || !stationAt(pos, "Furnace", "Smoker")) {
            this.smeltTarget = null;
            return false;
        }
        if (this.player.blockPosition().closerThan(pos, 3.5D)) {
            this.path.clear();
            setState(AiState.INTERACT);
            this.interact.smelt(pos);
            this.smeltTarget = null;
            return true;
        }
        this.path.setGoal(pos);
        if (this.path.tick()) {
            this.smeltTarget = null;
            return true;
        }
        setState(AiState.MOVING);
        return true;
    }

    /** 走到酿造台跟前：先收成品，再放瓶子和原料 */
    boolean brewTick(AiInput in) {
        BlockPos pos = this.brewTarget;
        if (pos == null || !stationAt(pos, "BrewingStand")) {
            this.brewTarget = null;
            return false;
        }
        if (this.player.blockPosition().closerThan(pos, 3.5D)) {
            this.path.clear();
            setState(AiState.INTERACT);
            this.interact.brew(pos);
            this.brewTarget = null;
            return true;
        }
        this.path.setGoal(pos);
        if (this.path.tick()) {
            this.brewTarget = null;
            return true;
        }
        setState(AiState.MOVING);
        return true;
    }

    static String blockClassName(Block block) {
        return BLOCK_CLASS_NAMES.computeIfAbsent(block.getClass(), Class::getSimpleName);
    }

    /** 这个坐标是不是指定类名的工位（工作台 / 熔炉 / 酿造台）；多个名字任中其一 */
    boolean stationAt(BlockPos pos, String... nameParts) {
        String name = blockClassName(this.player.level().getBlockState(pos).getBlock());
        for (String part : nameParts) {
            if (name.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** 工位的中文标签，不是工位返回 null */
    String stationLabel(BlockPos pos) {
        String name = blockClassName(this.player.level().getBlockState(pos).getBlock());
        if (name.contains("CraftingTable")) {
            return "工作台";
        }
        if (name.contains("BrewingStand")) {
            return "酿造台";
        }
        if (name.contains("Furnace") || name.contains("Smoker")) {
            return "熔炉";
        }
        return null;
    }

    /** 找附近最近的指定工位（radius 格内，含上下 4 格）；多个名字只扫一趟 */
    BlockPos nearestStation(int radius, String... nameParts) {
        BlockPos origin = this.player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -4, -radius), origin.offset(radius, 4, radius))) {
            if (!stationAt(pos, nameParts)) {
                continue;
            }
            double d = this.player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * 掉水里会游泳。
     * <p>
     * 真人掉水里就两件事：头埋下去就上浮换气，然后朝目标（或最近的岸边）游过去。
     * 这里完全走原版游泳：水里按前进 = 朝视线方向游、按跳跃 = 上浮，
     * 所以不会瞬移、不会水上飘。A* 是陆地寻路，在水里不适用，所以先清掉寻路目标。
     *
     * @return true 表示这一 tick 的输入已被游泳接管
     */
    boolean swimTick(AiInput in) {
        if (!this.player.isInWater()) {
            this.shoreTarget = null;
            return false;
        }
        setState(AiState.MOVING);
        this.path.clear();

        // 游动方向：有任务朝任务，否则朝最近的岸边
        Vec3 goal = null;
        BlockPos t = target();
        if (t != null) {
            this.shoreTarget = null;
            goal = new Vec3(t.getX() + 0.5D, t.getY() + 0.5D, t.getZ() + 0.5D);
        } else {
            BlockPos here = this.player.blockPosition();
            if (this.shoreTarget != null && here.distSqr(this.shoreTarget) <= 4) {
                this.shoreTarget = null;   // 已经上岸，下一 tick 交回正常行为
            }
            if (this.shoreTarget == null && --this.shoreRepathIn <= 0) {
                this.shoreTarget = nearestShore(16.0D);
                if (this.shoreTarget == null) {
                    this.shoreTarget = nearestShore(32.0D);   // 近处没岸（大湖/海）再放宽一圈
                }
                this.shoreRepathIn = 40;
            }
            if (this.shoreTarget != null) {
                goal = new Vec3(this.shoreTarget.getX() + 0.5D, this.shoreTarget.getY() + 0.5D, this.shoreTarget.getZ() + 0.5D);
            }
        }

        if (goal != null) {
            // lookAt 会直接改写 input 的 yaw/pitch，所以“抬头换气”必须放在它之后
            this.player.lookAt(goal, 15.0F);
            in.forward = true;   // 水里按住前进 = 朝视线方向游
            in.sprint = true;
        }
        // 换气优先级最高：眼睛还在水里就一直上浮 + 抬头；浮出水面就松手，
        // 不然会像蹦床一样一直跳出水面。pitch 越负越朝上，取更朝上的那个。
        if (this.player.isUnderWater()) {
            in.jump = true;
            in.pitch = Math.min(in.pitch, -55.0F);
        }
        return true;
    }

}