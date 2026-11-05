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

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * AI 大脑：每 tick 决定“这一 tick 按哪些键”。
 * <p>
 * 优先级（和真人一致）：活着 -> 吃饭 -> 搭路 -> 战斗 -> 玩家/API 下发的任务 -> 自主生存（捡东西/狩猎/闲逛）。
 * 大脑只写 {@link AiInput}，所有物理和交互都交给原版，所以行为跟真人一样、不会被反作弊拦。
 */
public class AiBrain extends AiBrainLlm {
    public AiBrain(AiPlayer player, AiPlayerManager manager, AiProfile profile) {
        super(player, manager, profile);
    }

    /** 有没有任务在身（给 LOD 用：有任务就不降频） */
    public boolean isBusyTask() {
        return busyWithAnything();
    }

    /** 有没有还没回复的玩家消息（降频会拖慢回话） */
    public boolean hasPendingReply() {
        return this.pendingChatText != null;
    }

    public void tick() {
        runIntents();
        AiInput in = this.player.input();
        in.resetAll();
        applyItemHold(in);

        if (this.player.isRemoved()) {
            return;
        }
        if (this.player.isDeadOrDying()) {
            decided("aiplayer.state.dead");
            setState(AiState.DEAD);
            this.path.clear();
            this.bridge.stop();
            return;
        }
        // 1.0) 睡觉中：只维持睡眠（天亮 / 受伤 / 附近有怪 / 床没了会自动醒）。
        //      装备、社交、寻路、任务等子系统全部跳过 —— 睡着的 AI 不需要 20Hz 决策，
        //      夜里多只 AI 同时睡觉时这是最省 CPU 的一刀。
        if (this.player.isSleeping()) {
            // 睡前可能还占着物品栏锁，继续老化，防止醒来后卡死
            this.player.inventoryLock().tick();
            if (this.sleep.holdTick(in)) {
                decided("aiplayer.state.sleeping");
                setState(AiState.SLEEPING);
                return;
            }
        }
        if (this.actionCooldown > 0) {
            this.actionCooldown--;
        }
        trackMovement();
        // 射击诊断计数（/aiplayer gun 里显示的"多少 tick 前"）
        this.ranged.tickDiagnostics();
        // 物品栏锁每 tick 老化一次，避免任何控制器卡死占用
        this.player.inventoryLock().tick();
        // 竖直下挖（往脚下刨）只在进行挖掘任务时开：平时走路 / 追敌开着会边走边在
        // 玩家地形上刨坑。挖矿任务的目标在脚下时，A* 才算得出"挖穿下去"的路。
        this.path.setAllowDownDig(this.mineTarget != null);

        Entity enemy = this.attackTarget;
        this.food.setCombat(enemy != null && enemy.isAlive());
        // 战斗中 / 搭路 / 打水晶时不要让自动装备抢快捷栏
        this.equipment.setSuppressed(this.bridge.isActive() || this.crystal.isActive()
                || this.blockTactics.isActive()
                || this.food.isEating() || (enemy != null && enemy.isAlive()));

        if (this.profile.equip) {
            this.equipment.tick();
        }
        maybeAskLlm();
        greetNearby();
        teamChatTick();

        // 1) 活着最重要：饿到阈值就吃
        if (this.profile.eat) {
            // 整理食物（扫 41 格找吃的）不需要每 tick 做，AI 多时这是笔固定开销
            if (this.player.tickCount % 20 == 0) {
                this.food.organizeFood();
            }
            if (this.food.tick()) {
                decided("aiplayer.state.eating");
                setState(AiState.EATING);
                return;
            }
            // 低血吃金苹果
            if (this.player.getHealth() < 10.0F && this.food.goldenAppleSlot() >= 0 && !this.player.isUsingItem()) {
                int slot = this.food.goldenAppleSlot();
                if (this.player.selectSlot("food", com.aiplayer.util.InventoryLock.PRIO_FOOD, slot)) {
                    in.selectedSlot = slot;
                    in.useItem = true;
                }
            }
        }

        // 1.35) 虚空自救：正在坠入虚空时先用方块把自己垫住。
        //       放在打龙 / 战斗 / 逃生之前 —— 虚空坠落是秒杀级危险，不能被任何流程抢走这一 tick。
        if (this.escape.voidClutchTick(in)) {
            decided("aiplayer.state.escaping");
            setState(AiState.ESCAPING);
            return;
        }

        // 1.4) 末影龙团队战：只要身处末地、场上还有活着的末影龙，就交给 BossController 按团队打法接管。
        //      放在逃生之前：打龙时按团队打法判断（残血回撤、拆水晶、注意别踩空），比通用逃生更对症；
        //      但玩家用 API / 指令手动操控期间（manualTicks > 0）让位，听玩家的。
        if (bossTick(in)) {
            decided("aiplayer.state.boss");
            return;
        }

        // 1.5) 危险时逃生（末影珍珠 / 落地水 / 撤退）
        if (escapeTick(in)) {
            decided("aiplayer.state.escaping");
            return;
        }

        // 1.6) 掉水里会游泳：先浮出水面换气，再朝目标 / 最近的岸边游
        if (swimTick(in)) {
            decided("aiplayer.state.moving");
            return;
        }

        // 1.8) API Key 直接操控移动等动作：短暂接管（约 2 秒），期间不做别的自主行为
        if (this.manualTicks > 0) {
            applyManual(in);
            this.manualTicks--;
            this.path.clear();
            decided("aiplayer.state.commanding");
            setState(AiState.MOVING);
            return;
        }

        // 2) 真的走不通了才搭路：必须"有明确目的地 + 朝目的地卡住了一小会儿"，
        //    方向也取"朝目的地"而不是随手拿当前朝向 —— 否则 AI 站在坑边随便一瞥就开始乱搭。
        if (this.profile.bridge && this.profile.autoBridge && !this.bridge.isActive()
                && this.player.onGround() && this.stuckTicks > 15) {
            BlockPos goal = goalPos();
            if (goal != null) {
                Direction dir = directionTo(goal);
                if (BridgeController.needsBridge(this.player, dir)) {
                    this.bridge.start(dir);
                }
            }
        }
        if (this.bridge.isActive()) {
            if (this.bridge.tick()) {
                decided("aiplayer.state.bridging");
                setState(AiState.BRIDGING);
                if (this.profile.bridgeAttack) {
                    attackNearbyWhileBridging(in);
                }
                return;
            }
        }

        // 3) 战斗
        if (fightTick(in)) {
            decided("aiplayer.state.combat");
            return;
        }

        // 3.5) 卡住了又想去高处：垫一块方块踩上去（真人爬不上去也是这么干的）
        if (stepUpTick(in)) {
            decided("aiplayer.state.placing");
            return;
        }

        // 3.6) 组队后勤：队友缺食物 / 空手时送过去（低频触发，不当主业）
        if (teamShareTick(in)) {
            decided("aiplayer.task.container");
            return;
        }

        // 3.7) 被蜘蛛网缠住 / 身边就是蛛网：拿水桶冲掉（原版机制：流动的水会冲掉蛛网）
        if (webTick(in)) {
            decided("aiplayer.state.interact");
            return;
        }

        // 4) 明确任务：挖 / 放 / 去某处
        //    allowDig 默认就开着，A* 按 DIG_COST 权衡挖 vs 绕，这里不再强制覆盖
        if (taskTick(in)) {
            decided(this.mineTarget != null ? "aiplayer.task.mine"
                    : this.placeTarget != null ? "aiplayer.task.place" : "aiplayer.task.walk");
            return;
        }

        // 4.05) 死亡回收：复活后先回死亡点把掉落的装备捡回来（玩家明确任务优先）
        if (recoveryTick()) {
            decided("aiplayer.task.recover");
            return;
        }

        // 4.1) 智能① 多步计划：把上次 LLM 给的 2~4 步一条条做完（只在空下来时推进一步）
        if (planTick(in)) {
            decided("aiplayer.task.plan");
            return;
        }

        // 4.2) 夜里回床上睡觉：没任务、没敌人、也没在干长活时才会开始。
        //      玩家明确下令（sleep.isRequested()）时，先把手头的活停掉去睡。
        if (sleepTick(in)) {
            decided("aiplayer.state.sleeping");
            setState(this.player.isSleeping() ? AiState.SLEEPING : AiState.MOVING);
            return;
        }

        // 4.3) 守卫 / 巡逻：在玩家指定的保护区内来回走，被战斗打出区域后自己走回来。
        //      排在睡觉之后、自主生存之前 —— 战斗 / 吃饭 / 睡觉 / 玩家任务都不会被守卫挡住。
        if (guardTick(in)) {
            decided("aiplayer.task.guard");
            return;
        }

        // 5) 自主生存
        if (survivalTick(in)) {
            decided("aiplayer.state.idle");
            return;
        }

        // 5.1) 组队队形：队员离队长太远时先归队，再看要不要自己找活。
        //      必须排在 jobTick 前面，否则 AI 一空下来就接了长期生计（挖矿 / 砍树），
        //      队伍永远聚不起来 —— 表现就是“设了队伍但看起来各干各的”。
        if (teamFormationTick(in)) {
            decided("aiplayer.state.following");
            return;
        }

        // 5.2) 生计：挖矿（矿脉 + 支巷）/ 砍树（整棵树）/ 出门探索并记得回家
        //      这几件事都是"长活"，只在没任务没敌人时干，且每一步都走原版挖掘 / 寻路
        if (jobTick()) {
            decided("aiplayer.reason.job");
            return;
        }

        // 5.3) 智能④ 自主目标：没任务没敌人时按需求排序自己找活（缺工具 / 天黑回家）
        if (autonomyTick(in)) {
            decided("aiplayer.reason.autonomy");
            return;
        }

        // 5.6) 空闲生计：收庄稼补种 / 钓鱼 / 驯服动物（什么都不干才轮得到）
        if (passiveTick(in)) {
            decided("aiplayer.reason.passive");
            return;
        }

        decided("aiplayer.state.idle");
        idleTick(in);
    }

    /** 当前锁定目标（给 /aiplayer gun 诊断用） */
    public net.minecraft.world.entity.Entity currentTarget() {
        return this.attackTarget;
    }

    /** 最近一次枪械射击结果（给 /aiplayer gun 诊断用） */
    public String lastGunResult() {
        return this.ranged.lastShot();
    }

    // ---------------- LOD 用的公开查询 ----------------

    /**
     * 智能① 多步计划：模型一次给 2~4 步，这里空闲时逐条执行。
     * <p>
     * 上一步还在进行（有目标 / 在战斗 / 在搭路）就等它做完；单步卡太久就丢掉这一步，
     * 不会因为某一步走不通把整条计划卡死。战斗、逃生打断时队列保留，脱战后接着做。
     *
     * @return true 表示本 tick 刚下达了计划里的下一步
     */
    boolean planTick(AiInput in) {
        if (this.planQueue.isEmpty()) {
            return false;
        }
        if (this.planStepTicks > 0) {
            this.planStepTicks--;
            return false;
        }
        if (busyWithAnything()) {
            if (++this.planStallTicks > PLAN_STUCK_TICKS) {
                this.planQueue.poll();   // 这一步卡太久，丢掉，做下一步
                this.planStallTicks = 0;
            }
            return false;
        }
        this.planStallTicks = 0;
        JsonObject step = this.planQueue.poll();
        applyAction(step, false);
        this.planStepTicks = PLAN_STEP_SETTLE_TICKS;
        return true;
    }

    /**
     * 夜间作息：回床睡觉。
     * <p>
     * 正在挖矿 / 砍树 / 探索 / 战斗时不强行打断；玩家明确下令（{@code sleep.isRequested()}）例外。
     *
     * @return true 表示正在去床边或已经睡下
     */
    boolean sleepTick(AiInput in) {
        if (this.attackTarget != null && this.attackTarget.isAlive()) {
            return false;
        }
        // 玩家明确下令的活（挖矿 / 砍树 / 下令的探索 / 搭路）不强行打断；
        // AI 自己跑出去溜达的探索不算活，天黑了让睡觉把它顶掉。
        if (!this.sleep.isRequested()
                && (this.mining.isBusy() || this.chop.isBusy()
                || (this.explore.isActive() && !this.explore.isSelfStarted())
                || this.bridge.isActive())) {
            return false;
        }
        return this.sleep.startTick(in, this.path);
    }

    /**
     * 智能④ 自主目标：没有指令、没有任务、没有敌人时，自己按需求排序找活干。
     * <p>
     * 目前覆盖两件最有价值的：天黑了又离家太远就先回家；缺镐就先砍树攒木料，
     * 附近有工作台且材料够就直接做一把木镐。
     *
     * @return true 表示本 tick 已被自主目标占住
     */
    boolean autonomyTick(AiInput in) {
        if (this.followName != null && !this.followName.isBlank()) {
            return false;   // 跟着玩家时不自己乱跑
        }
        if (busyWithAnything()) {
            return false;
        }
        if (this.autonomyTicks > 0) {
            this.autonomyTicks--;
            return false;
        }
        // 1) 天黑了又离家太远：先回基地（夜里在外面太危险）
        //    26.2 没有 Level#isNight()，用天空变暗程度判断（原版幻翼生成也用这个阈值）
        boolean night = this.player.level().dimensionType().hasSkyLight()
                && this.player.level().getSkyDarken() >= 5;
        BlockPos home = this.explore.home();
        if (night && home != null
                && !this.player.blockPosition().closerThan(home, 48.0D)
                && this.player.getHealth() > this.player.getMaxHealth() * 0.5F) {
            walkTo(home);
            this.autonomyTicks = 400;
            setState(AiState.MOVING);
            return true;
        }
        // 2) 缺镐：有工作台且材料够就做一把，否则就近砍树攒木料
        if (this.profile.mine && !hasItemPath("_pickaxe")) {
            BlockPos table = nearestStation(16, "CraftingTable");
            if (table != null && hasItemPath("_planks") && hasItemPath("stick")) {
                openCraft(table, net.minecraft.world.item.Items.WOODEN_PICKAXE, 1);
                this.autonomyTicks = 200;
                return true;
            }
            this.chop.start();
            this.autonomyTicks = 200;
            return false;   // 交给下个 tick 的 jobTick 砍树分支去执行
        }
        return false;
    }

    /**
     * 玩家在公屏说话了（由 {@code ChatListener} 在服务端线程调用）。
     * <p>
     * 省 token 的三道闸门：太远不听、没点名大概率不理、每个 AI 每种情况都有最短间隔。
     * 命中后<b>不直接发请求</b>，只把这句话记下来并让下一轮 LLM 立刻执行 —— 复用
     * {@link #maybeAskLlm()} 里已有的间隔 / 退避 / 并发保护，不会多打任何一次请求。
     */
    public void onPlayerChat(Player speaker, String text) {
        if (speaker == null || text == null || text.isBlank() || speaker == this.player) {
            return;
        }
        // 名字兜底：唯一来源里偶尔会出现"同名的另一个 AI 实体"（重登 / 恢复），
        // 只靠 == 比较会让它把自己刚说的话当成玩家发言再回一遍。
        if (speaker.getScoreboardName().equals(this.player.getScoreboardName())) {
            return;
        }
        // 旁观者（含死亡后观战）说的话一律不理：他不在局内，AI 对他做任何反应都很出戏
        if (speaker.isSpectator()) {
            return;
        }
        // AI 自己 / 别的 AI 说的话不走这条"玩家发言"通道，改由 ChatController -> onAiChat 处理
        if (speaker instanceof AiPlayer) {
            return;
        }
        // 公屏广播格式 "<名字> 内容"：系统 / AI 播出来的，不是玩家敲的原话
        String trimmed = text.trim();
        if (trimmed.startsWith("<") && trimmed.contains("> ")) {
            return;
        }
        // === 点名隔离：玩家 @ 的是别的 AI，就让被点到的那个回应，我不抢答 ===
        boolean atMe = mentionsMe(trimmed);
        if (mentionsOtherAi(trimmed) && !atMe) {
            return;
        }
        // 去掉 "@名字" 前缀，剩下的才是真正要执行的内容（否则关键词会被前缀挡住）
        String body = stripMention(trimmed);
        if (body.isBlank()) {
            return;
        }
        // === 本地指令（不依赖 chat 开关、不受概率闸门影响，省 token） ===
        if (tryLocalCommand(body, speaker)) {
            // 智能②：玩家交代过的事记进持久记忆，下次不必重复说
            if (this.profile != null) {
                this.profile.rememberCommand(speaker.getName().getString() + "： " + body);
            }
            return;
        }
        // === 下面走 LLM 通道 ===
        AiProfile p = this.profile;
        if (p == null) {
            return;
        }
        String speakerName = speaker.getName().getString();
        // 先把这句话记进持久记忆：不管这一 tick 最终回不回，AI 都"知道"玩家说了什么。
        // 这是用户的要求 —— 玩家的每句话都要让 AI 知道，而不是被概率闸门静默丢掉。
        p.rememberCommand(speakerName + "： " + body);
        // 玩家自己把"聊天"关了就闭嘴（唯一还保留的开关：这是玩家主动关的）。
        // 注意这句话已经在上面的 rememberCommand 里记过了，所以关掉聊天只是不回嘴，不是没听见。
        if (!p.chat) {
            return;
        }
        long now = System.currentTimeMillis();
        // 距离闸门（32 格）和概率闸门（10%）已删除 —— 以前玩家正常说一句话，
        // 要同时满足"32 格内"(几乎总是) × "10% 概率" × "距上次搭话超过 30 秒"，
        // 综合下来 AI 基本永远不会理人。现在只看一个极短的防刷屏间隔：
        // @点名 / 主人 0 间隔立刻回，其他玩家的消息 1.5 秒（避免连说三句被吞掉两句）。
        boolean owner = isOwner(speaker);
        long cooldown = (atMe || owner) ? 0L : CHAT_PLAYER_COOLDOWN_MS;
        if (cooldown > 0L && now - this.lastPlayerChatAt < cooldown) {
            return;
        }
        this.lastPlayerChatAt = now;
        this.pendingSpeaker = speakerName;
        this.pendingLocale = localeOf(speaker);
        this.pendingChatText = body.length() > CHAT_TEXT_MAX ? body.substring(0, CHAT_TEXT_MAX) : body;
        // 对话记忆：玩家这句原话要留着，模型下一轮才知道自己刚答应过什么
        rememberDialog("玩家 " + speakerName + "：" + this.pendingChatText);
        // 记下玩家原话：AI 若把它整句抄回来当回复，会被 ChatController 直接拦掉
        this.chat.notePlayerSaid(this.pendingChatText);
        // 习性记忆：玩家平时爱让你做什么，短句存进 config，下一轮提示词直接用
        if (this.profile != null) {
            this.profile.rememberHabit(speakerName, this.pendingChatText);
        }
        // 立刻回 vs 排到下一个 LLM 间隔：
        // @点名 / 主人 -> 直接插队，马上回话（lastLlmAt 清零）；
        // 其他玩家的话也一定会被处理（pendingChatText 已经挂上了），
        // 但最多等一个 llmIntervalMs（默认 12 秒）就轮到自己 ——
        // 这样"每句话都让 AI 知道"和"别把 API 打爆"两头都顾上。
        if (this.llmFailures == 0 && (atMe || owner)) {
            this.lastLlmAt = 0L;
        }
        // 建造不再走本地模板：统一交给 LLM 用 fill 设计建筑（无 API 时不会自行建房）。
    }

    /**
     * 别的 AI 说话了（由 {@code ChatController} 广播聊天时回调过来）。
     * <p>
     * 用户要求：AI 要搭理同伴的话，但只有 50% 概率接腔 —— 两只 AI 才不会你一句我一句刷屏。
     * 这里只回一句本地台词（不花 token），也绝不会把同伴的话当成"玩家的指令"去执行。
     */
    public void onAiChat(AiPlayer speaker, String text) {
        if (speaker == null || speaker == this.player || text == null || text.isBlank()) {
            return;
        }
        AiProfile p = this.profile;
        if (p == null || !p.chat) {
            return;
        }
        // 同队有专门的闲聊机制（teamChatTick），这里不重复接腔
        String mine = p.team;
        if (mine != null && !mine.isBlank() && speaker.profile() != null && mine.equals(speaker.profile().team)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastAiChatAt < AI_CHAT_MIN_GAP_MS) {
            return;   // 最短间隔：一群 AI 同屏时才不会互相接龙刷屏
        }
        if (RNG.nextFloat() >= 0.5F) {
            return;   // 50% 概率才理它
        }
        this.lastAiChatAt = now;
        this.chat.sayReply(Component.translatable("aiplayer.say.buddy", speaker.getScoreboardName()));
    }

}