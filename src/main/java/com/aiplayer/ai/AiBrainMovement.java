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

/** 移动相关决策：走路 / 跟随 / 游泳 / 垫脚 / 爬梯 / 蜘蛛网。 */
class AiBrainMovement extends AiBrainCore {
    AiBrainMovement(AiPlayer player, AiPlayerManager manager, AiProfile profile) {
        super(player, manager, profile);
    }

    /** 找最近能站人的岸边（半径内、非水、有碰撞、上方塞得下人且不是水） */
    BlockPos nearestShore(double radius) {
        Level level = this.player.level();
        BlockPos origin = this.player.blockPosition();
        int r = Mth.ceil(radius);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // 只看水面上下一格左右，够判断岸边了，避免每 tick 扫一大片方块
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.hasChunkAt(p)) {
                        continue;
                    }
                    if (!level.getFluidState(p).isEmpty()) {
                        continue;   // 还是水
                    }
                    if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                        continue;   // 站不住（空气 / 草）
                    }
                    BlockPos above = p.above();
                    if (!level.getFluidState(above).isEmpty()
                            || !level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
                        continue;   // 上方被水或方块堵住
                    }
                    double d = p.distSqr(origin);
                    if (d < bestDist) {
                        bestDist = d;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    /**
     * 垫脚爬高：卡住且目的地明显在上方时，起跳在脚下放一块，踩上去再继续走。
     * <p>
     * 一次只垫一格，垫完交回寻路；还上不去就等下次卡住再垫，不会无限往上堆。
     *
     * @return true 表示这一 tick 的输入已被垫脚接管
     */
    boolean stepUpTick(AiInput in) {
        if (!this.pillaring) {
            if (this.stuckTicks <= 25) {
                return false;
            }
            BlockPos goal = goalPos();
            // 已经在目标上方就别再往上堆了（防止一路搭到天上）
            if (goal != null && this.player.getY() > goal.getY() + 2.0D) {
                return false;
            }
            // 两种值得垫一格的情况：
            //   1) 目的地在上面至少 2 格，要爬高才能到；
            //   2) 正前方挡着一堵 1~2 格的台阶 / 矮墙 —— 这才是"上山"最常见的卡点，
            //      只看目标高度永远触发不了（目标在山那边，Y 并不更高）。
            boolean goalUp = goal != null && goal.getY() > this.player.getY() + 1.0D;
            if ((!goalUp && !climbableAhead(in.yaw)) || !ensureBlockInHand()) {
                return false;
            }
            this.pillaring = true;
            this.pillarBaseY = Mth.floor(this.player.getY());
            this.pillarTicks = 0;
        }

        if (++this.pillarTicks > 60) {
            this.pillaring = false;   // 超时放弃，别一直站着
            this.player.inventoryLock().release("stepup");
            return false;
        }
        setState(AiState.PLACING);
        this.path.clear();
        in.forward = false;
        in.backward = false;
        in.left = false;
        in.right = false;
        in.sprint = false;
        in.pitch = 90.0F;   // 朝下看，方便往脚下放

        BlockPos under = BlockPos.containing(this.player.getX(), this.pillarBaseY, this.player.getZ());
        if (this.player.onGround()) {
            in.jump = true;   // 起跳，腾出脚下这一格
            return true;
        }
        if (this.player.getY() > this.pillarBaseY + 0.55D
                && PlaceUtil.isReplaceable(this.player.level(), under)
                && PlaceUtil.placeAt(this.player, under, Direction.UP)) {
            this.pillaring = false;   // 放好了，落地就升了一格
            // 垫脚用的是"bridge"级优先级（60）的锁，不主动释放会一直压着 40 秒
            // （锁要 40 tick 才超时），期间别人抢不到槽位 —— AI 就"不会切工具/切物品"了。
            this.player.inventoryLock().release("stepup");
        }
        return true;
    }

    /**
     * 卡住时判断：正前方是不是一堵"垫一格就能上去"的台阶 / 矮墙（1~2 格高）。
     * <p>
     * 条件：面前脚部实心（真的挡住了路）、上方第三格是空的（1~2 格高，垫一格够得着）、
     * 自己头顶也腾得出起跳空间。这样"上山"时才会沿着坡一层层垫上去，
     * 而不会在平地上乱垫方块。
     */
    boolean climbableAhead(float yaw) {
        Level level = this.player.level();
        BlockPos feet = this.player.blockPosition();
        // 必须用本 tick 的目标朝向（in.yaw），不能用 getYRot()：寻路时实体朝向还在平滑中，
        // 用旧朝向会朝错方向判断，误判成“前面是空的”，于是上坡从不垫方块。
        Direction facing = BridgeController.yawToDirection(yaw);
        BlockPos front = feet.relative(facing);
        if (PlaceUtil.isReplaceable(level, front)) {
            return false;   // 前面是空的：不是被墙挡住的卡，别乱垫
        }
        if (!PlaceUtil.isReplaceable(level, front.above(2))) {
            return false;   // 3 格以上，垫一格也上不去
        }
        return PlaceUtil.isReplaceable(level, feet.above(2));
    }

    /** 手上没方块就从快捷栏切一块过来（垫脚用） */
    boolean ensureBlockInHand() {
        return ensureBlockInHand("stepup", com.aiplayer.util.InventoryLock.PRIO_BRIDGE);
    }

    /** 手上没方块就从背包切一块过来（owner / 优先级可指定） */
    boolean ensureBlockInHand(String owner, int prio) {
        if (PlaceUtil.holdingBlock(this.player)) {
            return true;
        }
        Inventory inv = this.player.getInventory();
        // 1) 快捷栏里已经有方块：直接切过去
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            if (inv.getItem(i).getItem() instanceof BlockItem) {
                return this.player.selectSlot(owner, prio, i);
            }
        }
        // 2) 快捷栏没有、主背包里有：把那一组方块和当前手上的物品对调，再选中它
        for (int i = Inventory.SELECTION_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() instanceof BlockItem) {
                int target = inv.getSelectedSlot();
                ItemStack old = inv.getItem(target);
                inv.setItem(target, stack);
                inv.setItem(i, old);
                return this.player.selectSlot(owner, prio, target);
            }
        }
        return false;
    }

    /** 背包（含主背包）里到底有没有方块可用 */
    boolean hasAnyBlock() {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).getItem() instanceof BlockItem) {
                return true;
            }
        }
        return false;
    }

    boolean walkTick(AiInput in) {
        if (this.stuckTicks > 120) {
            this.walkTarget = null;
            this.path.clear();
            return false;
        }
        if (this.path.tick()) {
            this.walkTarget = null;
            return true;
        }
        setState(AiState.MOVING);
        return true;
    }

    boolean followTick(AiInput in) {
        Player target = this.player.serverLevel().getServer() == null ? null
                : this.player.serverLevel().getServer().getPlayerList().getPlayerByName(this.followName);
        if (target == null || target == this.player) {
            // 只停掉本次跟随，不要把 profile.follow 抹掉：那是 GUI 里的持久配置，
            // 目标暂时离线（或还没上线）时被清空的话，玩家会发现设置莫名消失。
            this.followName = null;
            return false;
        }
        double dist = this.player.distanceToSqr(target);
        if (dist < 9.0D) {
            this.path.clear();
            this.player.lookAt(target.getX(), target.getY() + 1.5D, target.getZ(), 30.0F);
            setState(AiState.FOLLOWING);
            return true;
        }
        this.path.setGoal(target.blockPosition());
        if (this.path.tick()) {
            return true;
        }
        setState(AiState.FOLLOWING);
        return true;
    }

    /**
     * 水桶冲蜘蛛网。
     * <p>
     * 原版机制里"流动的水"会把蛛网冲掉，玩家过矿洞都是这么干的。AI 以前被蛛网粘住
     * 只会原地挣扎（stuckTicks 涨满就重算路径，还是同一条），所以给它一个主动动作：
     * 拿出水桶 -> 对着蛛网倒水 -> 等水流冲掉 -> 再用空桶把水源收回来（不白扔一桶水）。
     *
     * @return true 表示本 tick 由"清理蛛网"接管
     */
    boolean webTick(AiInput in) {
        if (this.profile == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        var level = this.player.level();
        if (this.webTarget == null) {
            if (now < this.webCooldownUntil || this.player.isInWater()) {
                return false;
            }
            // 搭路中不折腾（搭路有自己的一套节奏），其它情况"卡住够久"或"身上就粘着网"才触发
            if (this.bridge.isActive() || this.stuckTicks < 10 && !webTouching()) {
                return false;
            }
            if (com.aiplayer.util.InvUtil.findHotbarSlot(this.player, net.minecraft.world.item.Items.WATER_BUCKET) < 0
                    && !com.aiplayer.util.InvUtil.has(this.player, net.minecraft.world.item.Items.WATER_BUCKET)
                    && !com.aiplayer.util.InvUtil.has(this.player, net.minecraft.world.item.Items.BUCKET)) {
                return false;   // 没有水桶、也没有空桶：别进入状态
            }
            this.webTarget = findWeb();
            if (this.webTarget == null) {
                return false;
            }
            this.webStage = 0;
            this.webTicks = 0;
        }
        // 蛛网已经没了（被水冲掉 / 别人挖了）：收工，进入冷却免得反复折腾
        if (!level.getBlockState(this.webTarget).is(net.minecraft.world.level.block.Blocks.COBWEB)) {
            finishWeb(now);
            return false;
        }
        this.webTicks++;
        this.path.clear();
        this.player.lookAt(new Vec3(this.webTarget.getX() + 0.5D, this.webTarget.getY() + 0.5D,
                this.webTarget.getZ() + 0.5D), 25.0F);
        // 够不着就先走近点（直接调寻路，走两格就到的活没必要绕一大圈）
        if (!this.player.isWithinBlockInteractionRange(this.webTarget, 0.2D)) {
            if (this.webTicks > 100 && this.stuckTicks > 30) {
                finishWeb(now);   // 走不到就别耗着了
                return false;
            }
            this.path.setGoal(this.webTarget);
            this.path.tick();
            setState(AiState.MOVING);
            return true;
        }
        setState(AiState.INTERACT);
        if (this.webStage == 0) {
            if (!holdBucket()) {
                finishWeb(now);
                return false;
            }
            in.useItem = true;      // 倒水
            this.webStage = 1;
            this.webTicks = 0;
            return true;
        }
        if (this.webStage == 1 && this.webTicks > 25) {
            this.webStage = 2;      // 水流已经冲掉了蛛网，去把水源收回来
            this.webTicks = 0;
        }
        if (this.webStage == 2) {
            BlockPos src = findWaterSource();
            if (src != null && holdBucket()) {
                this.player.lookAt(new Vec3(src.getX() + 0.5D, src.getY() + 0.5D, src.getZ() + 0.5D), 25.0F);
                in.useItem = true;   // 空桶右键水源 = 装回一桶水
            }
            finishWeb(now);
            return true;
        }
        return true;
    }

    /** 身边（含自己身上）有没有蜘蛛网 */
    boolean webTouching() {
        var level = this.player.level();
        BlockPos feet = this.player.blockPosition();
        return level.getBlockState(feet).is(net.minecraft.world.level.block.Blocks.COBWEB)
                || level.getBlockState(feet.above()).is(net.minecraft.world.level.block.Blocks.COBWEB);
    }

    /** 3 格内最近的蜘蛛网 */
    BlockPos findWeb() {
        var level = this.player.level();
        BlockPos feet = this.player.blockPosition();
        BlockPos best = null;
        double bestD = 12.0D;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos p = new BlockPos(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!level.hasChunkAt(p)
                            || !level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.COBWEB)) {
                        continue;
                    }
                    double d = p.distSqr(feet);
                    if (d < bestD) {
                        bestD = d;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** 把水桶（没有的话用空桶）拿到手上 */
    boolean holdBucket() {
        int slot = com.aiplayer.util.InvUtil.findHotbarSlot(this.player, net.minecraft.world.item.Items.WATER_BUCKET);
        if (slot < 0) {
            slot = com.aiplayer.util.InvUtil.pullToHotbar(this.player, net.minecraft.world.item.Items.WATER_BUCKET);
        }
        if (slot < 0) {
            slot = com.aiplayer.util.InvUtil.findHotbarSlot(this.player, net.minecraft.world.item.Items.BUCKET);
            if (slot < 0) {
                slot = com.aiplayer.util.InvUtil.pullToHotbar(this.player, net.minecraft.world.item.Items.BUCKET);
            }
        }
        return slot >= 0 && this.player.selectSlot("web", com.aiplayer.util.InventoryLock.PRIO_TASK, slot);
    }

    /** 附近的水源（回收时用空桶装回来） */
    BlockPos findWaterSource() {
        var level = this.player.level();
        BlockPos feet = this.player.blockPosition();
        BlockPos best = null;
        double bestD = 30.0D;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos p = new BlockPos(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!level.hasChunkAt(p)) {
                        continue;
                    }
                    var fluid = level.getFluidState(p);
                    if (!fluid.is(net.minecraft.tags.FluidTags.WATER) || !fluid.isSource()) {
                        continue;
                    }
                    double d = p.distSqr(feet);
                    if (d < bestD) {
                        bestD = d;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    void finishWeb(long now) {
        this.webTarget = null;
        this.webStage = 0;
        this.webTicks = 0;
        this.webCooldownUntil = now + 15_000L;
    }

}