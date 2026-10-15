package com.aiplayer.ai;

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
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
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

/**
 * AI 大脑：每 tick 决定“这一 tick 按哪些键”。
 * <p>
 * 优先级（和真人一致）：活着 -> 吃饭 -> 搭路 -> 战斗 -> 玩家/API 下发的任务 -> 自主生存（捡东西/狩猎/闲逛）。
 * 大脑只写 {@link AiInput}，所有物理和交互都交给原版，所以行为跟真人一样、不会被反作弊拦。
 */
public class AiBrain {
    private static final Random RNG = new Random();
    /** 空闲时多久环顾一次 */
    private static final int IDLE_LOOK_INTERVAL = 60;
    /** LLM "use" 最多按住多少 tick（够食物 / 药水喝完，也给弓留出蓄力时间） */
    private static final int USE_HOLD_MAX_TICKS = 45;
    /** 听得到玩家说话的距离（格）：太远就当没听见，不触发请求 */
    private static final double CHAT_HEAR_RANGE = 32.0D;
    /** 没被点名时搭话的概率：大部分公屏消息不该惊动 LLM（省 token，也少插嘴） */
    private static final double CHAT_REACT_CHANCE = 0.1D;
    /** 没被点名时同一个 AI 的最短搭话间隔 */
    private static final long CHAT_REACT_COOLDOWN_MS = 30_000L;
    /** 被点名时的最低间隔，防连点刷屏 */
    private static final long CHAT_NAMED_COOLDOWN_MS = 1_500L;
    /** 单条玩家消息最多带给模型多少字符 */
    private static final int CHAT_TEXT_MAX = 200;
    /**
     * 没点名、但明显是在使唤 AI 的说法。命中就当成“在跟 AI 说话”，走立即响应通道。
     * <p>
     * 不这么做的话，像"给我一些羊毛"这种没点名的请求只会按 10% 概率被搭话，
     * 表现出来就是"AI 有时候不听指令"。
     */
    private static final String[] REQUEST_KEYWORDS = {
            "给我", "给点", "帮我", "拿来", "过来", "跟我", "跟着我", "带我去", "带我", "送我",
            "丢给", "扔给", "换一下", "去做", "去拿", "去打", "去挖", "去砍", "去放", "去找",
            "回家", "找一下",
            // 建造 / 动作类：玩家说"建房子""搭个墙""挖个洞"也是在使唤 AI，不能当成闲聊
            "建个", "建房", "建一", "建座", "帮我建", "给我建", "盖个", "盖房", "盖一", "盖座",
            "搭个", "搭一", "搭房", "搭桥", "造个", "造一", "围起来", "围个", "修一下", "挖个", "挖一",
            "给你建", "给你搭", "建房子", "盖房子",
            "give me", "bring me", "come here", "follow me", "help me", "build me"
    };
    /**
     * 明确“让 AI 建东西”的说法：命中就走本地兜底建造，不等模型回话。
     * <p>
     * 故意不包含“搭桥 / 桥”——桥要走 bridge 字段，用围墙的兜底逻辑会建错东西。
     */
    private static final String[] BUILD_WORDS = {
            "建个", "建房", "建屋", "建一", "建座", "建房子", "建屋子", "帮我建", "给我建", "给你建",
            "盖个", "盖房", "盖屋", "盖一", "盖座", "盖房子", "盖屋子",
            "搭个", "搭一", "搭房", "搭屋", "搭间",
            "造个", "造一", "造房", "造屋", "围起来", "围个", "围一圈", "围墙",
            "build", "house", "shelter"
    };
    /** 主动招呼附近玩家的距离 / 对同一个玩家的重复招呼间隔 */
    private static final double GREET_RANGE = 4.0D;
    private static final long GREET_COOLDOWN_MS = 600_000L;
    /** 同队闲聊：自己起话头的最短间隔与随机抖动（tick），45~75 秒一句，避免话痨 */
    private static final int TEAM_CHAT_MIN_GAP_TICKS = 900;
    private static final int TEAM_CHAT_JITTER_TICKS = 600;
    /** 队友话音刚落多久内可以接话 / 接同一个队友的最短间隔 */
    private static final long TEAM_CHAT_REPLY_WINDOW_MS = 12_000L;
    private static final long TEAM_CHAT_REPLY_GAP_MS = 20_000L;
    /** 闲聊的队友最远距离（格） */
    private static final double TEAM_CHAT_RANGE = 20.0D;
    /** 没有配置主人时的默认主人（房主） */
    public static final String DEFAULT_OWNER = "The_bodboy_";
    /** API Key 直接操控移动的持续时间（tick），约 3 秒 */
    private static final int MANUAL_HOLD_TICKS = 60;
    /** 对同一个赠予者道谢的最短间隔 */
    private static final long THANK_COOLDOWN_MS = 30_000L;

    private final AiPlayer player;
    private final AiPlayerManager manager;
    private final AiProfile profile;
    /** 主人说过"允许 PvP"后才主动打其他玩家（默认 false，避免主动招惹） */
    private boolean allowPlayerPvp;
    /** 仇恨目标：被谁打了以后记在这，死了 / 超时有仇必报 */
    private Entity grudgeTarget;
    private long grudgeUntilTick;

    public void setAllowPlayerPvp(boolean v) { this.allowPlayerPvp = v; }
    public boolean allowPlayerPvp() { return this.allowPlayerPvp; }

    private final CombatController combat;
    private final RangedController ranged;
    private final CrystalPvpController crystal;
    private final AnchorPvpController anchor;
    private final WindChargeMaceController windMace;
    private final ElytraSpearController elytra;
    private final BlockTacticsController blockTactics;
    private final FoodManager food;
    private final EquipmentManager equipment;
    private final EscapeController escape;
    private final ChatController chat;
    private final BridgeController bridge;
    private final PathExecutor path;
    private final InteractController interact;
    private final com.aiplayer.ai.survival.PassiveController passive;
    /** 挖矿（矿脉 + 支巷矿道） / 砍树（整棵树） / 探索（出门 + 回程） */
    private final MiningController mining;
    private final ChopController chop;
    private final ExploreController explore;

    private final ConcurrentLinkedQueue<Runnable> intents = new ConcurrentLinkedQueue<>();

    private AiState state = AiState.IDLE;
    private BlockPos walkTarget;
    private BlockPos mineTarget;
    /**
     * 当前挖掘目标是不是采集控制器（矿脉 / 整棵树 / 支巷）排出来的。
     * true 时挖完这一格就交回控制器，不再自己往脚下接着挖 —— 否则控制器排好的
     * 队列会被"40% 概率往下挖一格"随机打断，矿脉和整棵树永远挖不干净。
     */
    private boolean mineFromJob;
    private BlockPos placeTarget;
    /** 连续建造队列：从 LLM 的 build 字段来，一次放多块（简易墙/房），逐块消费 */
    private final Deque<BlockPos> buildQueue = new ArrayDeque<>();
    /** 当前这一格连续放失败的次数：超过上限就跳过，否则整条建造队列会永远卡在那一格 */
    private int buildRetry;
    /** 走到当前这一格已经花掉的 tick：太久了就跳过，避免整条队列卡在同一格上 */
    private int buildTicks;
    private BlockPos cookTarget;
    /** 要翻的容器位置（箱子 / 木桶 / 熔炉…） */
    private BlockPos containerTarget;
    /** 要交易的村民 */
    private Entity tradeTarget;
    /** 要去合成的工位（工作台）和目标物品 / 次数 */
    private BlockPos craftTarget;
    private Item craftWant;
    private int craftTimes = 1;
    /** 要去烧炼的熔炉 / 要去酿造的酿造台 */
    private BlockPos smeltTarget;
    private BlockPos brewTarget;
    /** 正在"垫脚"爬高 */
    private boolean pillaring;
    /** 垫脚时的起跳基准高度 */
    private int pillarBaseY = Integer.MIN_VALUE;
    private int pillarTicks;
    /** 掉水里时最近的岸边（上岸方向），上岸后清空 */
    private BlockPos shoreTarget;
    private int shoreRepathIn;
    /** 上一次工具查询结果（种子 / 结构坐标），下一轮喂给 LLM */
    private String lastToolResult = "";
    private Entity attackTarget;
    private String followName;
    /**
     * LLM 要求“使用某件物品”后的持续按住状态：吃东西 / 喝药要按住右键若干 tick 才生效，
     * 只点一次会被立刻中断。这里记下槽位和剩余 tick，由 {@code applyItemHold} 每 tick 施加。
     */
    private int useHoldTicks;
    private int useHoldSlot = -1;
    /** use 意图已经按下过几次右键：用来识别"即用即生效"的物品什么时候该松手 */
    private int useHoldApplied;
    private int actionCooldown;
    private int idleTicks;
    private int mineTicks;
    private int stuckTicks;
    private int lastSlot = -1;
    private Vec3 lastPos = Vec3.ZERO;
    private long stateSince = System.currentTimeMillis();

    private boolean llmBusy;
    private long lastLlmAt;
    private int llmFailures;
    /** 连续失败后的额外等待（毫秒），成功一次就清零 */
    private long llmBackoffMs;
    /** 请求在途时玩家消息已走本地规则处理：迟到的模型回复作废，避免动作打架 */
    private boolean llmStale;

    // ------------------------------------------------------------------
    // 玩家聊天互动（入口见 ChatListener）
    // ------------------------------------------------------------------
    /** 待回复的玩家名 / 原话：onPlayerChat 写入，maybeAskLlm 取走后清空（只带最后一句，省 token） */
    private String pendingSpeaker;
    private String pendingChatText;
    /** 上一次响应玩家聊天的时刻，用于搭话频率限制 */
    private long lastPlayerChatAt;
    /** 已经招呼过的玩家（UUID -> 时刻），同一个玩家几分钟内只招呼一次 */
    private final Map<UUID, Long> greetedAt = new HashMap<>();

    // ------------------------------------------------------------------
    // API Key 直接操控（移动等动作）
    // ------------------------------------------------------------------
    /** 剩余的手动操控 tick；> 0 时接管本 tick 的移动输入 */
    private int manualTicks;
    private boolean mForward;
    private boolean mBack;
    private boolean mLeft;
    private boolean mRight;
    private boolean mJump;
    private boolean mSneak;
    private boolean mSprint;
    private Float mYaw;
    private Float mPitch;

    /** 上一次道谢的玩家与时刻，避免同一份礼物反复感谢 */
    private UUID thankedUuid;
    private long thankedAt;

    public AiBrain(AiPlayer player, AiPlayerManager manager, AiProfile profile) {
        this.player = player;
        this.manager = manager;
        this.profile = profile;
        this.combat = new CombatController(player);
        this.ranged = new RangedController(player);
        this.crystal = new CrystalPvpController(player);
        this.anchor = new AnchorPvpController(player);
        this.windMace = new WindChargeMaceController(player);
        this.elytra = new ElytraSpearController(player);
        this.blockTactics = new BlockTacticsController(player);
        this.food = new FoodManager(player);
        this.equipment = new EquipmentManager(player);
        this.escape = new EscapeController(player);
        this.chat = new ChatController(player);
        this.bridge = new BridgeController(player);
        this.path = new PathExecutor(player);
        this.interact = new InteractController(player);
        this.passive = new com.aiplayer.ai.survival.PassiveController(player);
        this.mining = new MiningController(player);
        this.chop = new ChopController(player);
        this.explore = new ExploreController(player);
        // 默认允许挖一格通过（比如门口挡了一格方块的死胡同）。
        // 之前只有挖矿任务才 allowDig=true，追敌 / 走路 / 觅食等场景遇到死胡同 A* 完全不考虑挖路，
        // 结果算不出路就卡死。放开后挖一格代价 DIG_COST 会远高于绕路，A* 自然优先绕，
        // 只有绕路代价大太多时才会选挖一格（比如门口挡了一格）。
        this.path.setAllowDig(true);
        // GUI 里配置的“跟随玩家”要生效：followName 才是运行时真正读取的字段，
        // 之前只在 setFollow()（LLM / 指令）里赋值，导致 GUI 填了名字却毫无反应。
        this.followName = profile == null || profile.follow == null || profile.follow.isBlank()
                ? null : profile.follow.trim();
    }

    // ------------------------------------------------------------------
    // 对外状态
    // ------------------------------------------------------------------

    public AiState state() {
        return this.state;
    }

    public AiProfile profile() {
        return this.profile;
    }

    public FoodManager food() {
        return this.food;
    }

    public ChatController chat() {
        return this.chat;
    }

    public BridgeController bridge() {
        return this.bridge;
    }

    public BlockPos target() {
        if (this.mineTarget != null) {
            return this.mineTarget;
        }
        if (this.placeTarget != null) {
            return this.placeTarget;
        }
        if (this.containerTarget != null) {
            return this.containerTarget;
        }
        if (this.walkTarget != null) {
            return this.walkTarget;
        }
        return this.path.hasGoal() ? this.path.goal() : null;
    }

    /** 当前要去的点：任务目标，或者正在跟随的那个玩家所在位置 */
    private BlockPos goalPos() {
        BlockPos goal = target();
        if (goal == null && this.followName != null) {
            MinecraftServer server = this.player.serverLevel().getServer();
            Player fp = server == null ? null : server.getPlayerList().getPlayerByName(this.followName);
            if (fp != null) {
                goal = fp.blockPosition();
            }
        }
        return goal;
    }

    /** 从自身指向某个坐标的水平方向（朝目标搭路时用，避免方向乱来） */
    private Direction directionTo(BlockPos goal) {
        double dx = goal.getX() + 0.5D - this.player.getX();
        double dz = goal.getZ() + 0.5D - this.player.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void setState(AiState next) {
        if (this.state != next) {
            this.state = next;
            this.stateSince = System.currentTimeMillis();
            if (this.profile.autoChat) {
                this.chat.voice(next);
            }
        }
    }

    // ------------------------------------------------------------------
    // 指令接口（指令 / GUI / LLM 都走这里，线程安全）
    // ------------------------------------------------------------------

    public void queue(Runnable intent) {
        this.intents.add(intent);
    }

    public void walkTo(BlockPos pos) {
        queue(() -> {
            // 走路的落点先"贴地"：模型/指令给的坐标常常只有水平信息（Y 是 locate 的 ~ 被当成 0），
            // 直接拿 y=0 当目标会把 AI 一路带到 0 层去。
            BlockPos goal = surface(pos);
            this.walkTarget = goal;
            this.mineTarget = null;
            this.placeTarget = null;
            this.cookTarget = null;
            this.path.setGoal(goal);
        });
    }

    public void mineAt(BlockPos pos) {
        mineAt(pos, false);
    }

    /**
     * 采集控制器专用：把"下一块挖哪"交给控制器。
     * <p>
     * 挖完这一格后不会自己往脚下接着挖（{@code mineFromJob}），而是交回控制器排队，
     * 这样矿脉、整棵树、支巷的队列才能一块不落地挖完。
     */
    public void mineAtJob(BlockPos pos) {
        mineAt(pos, true);
    }

    private void mineAt(BlockPos pos, boolean fromJob) {
        queue(() -> {
            this.mineTarget = pos;
            this.mineFromJob = fromJob;
            this.walkTarget = null;
            this.placeTarget = null;
            this.path.setGoal(pos);
            this.mineTicks = 0;
        });
    }

    /** 手上正有一个挖掘目标（采集控制器据此避免重复下达） */
    public boolean busyMining() {
        return this.mineTarget != null;
    }

    /** 附近有没有"想捡的"掉落物：采集控制器用它给捡东西让路（先捡再挖） */
    public boolean wantsDrop(double range) {
        return nearestWantedDrop(range) != null;
    }

    public void placeAt(BlockPos pos) {
        queue(() -> {
            this.buildQueue.clear();
            this.buildRetry = 0;
            this.placeTarget = pos;
            this.walkTarget = null;
            this.mineTarget = null;
            this.path.setGoal(pos);
        });
    }

    /** 连续建造：按顺序把一串方块位置放出来（LLM 的 build 字段，用来搭墙/小屋） */
    public void buildAt(List<BlockPos> positions) {
        queue(() -> {
            this.buildQueue.clear();
            this.buildQueue.addAll(positions);
            this.buildRetry = 0;
            this.walkTarget = null;
            this.mineTarget = null;
            this.cookTarget = null;
            advanceBuild();
        });
    }

    /** 取建造队列的下一块作为当前放置目标；队列空了就结束 */
    private void advanceBuild() {
        BlockPos next = this.buildQueue.poll();
        this.placeTarget = next;
        this.buildRetry = 0;
        this.buildTicks = 0;
        if (next != null) {
            this.path.setGoal(next);
        }
    }

    public void cook() {
        queue(() -> {
            BlockPos furnace = this.food.nearestFurnace(16);
            if (furnace != null) {
                this.cookTarget = furnace;
                this.path.setGoal(furnace);
            }
        });
    }

    public void attack(Entity entity) {
        queue(() -> {
            // 主人永远打不得：即使有人用 /aiplayer attack 下命令，也直接忽略
            if (isOwner(entity)) {
                return;
            }
            this.attackTarget = entity;
            this.combat.setTarget(entity);
        });
    }

    /** 去打开一个容器（箱子 / 木桶 / 熔炉…）并把里面的东西收进背包 */
    public void openContainer(BlockPos pos) {
        queue(() -> {
            this.containerTarget = pos;
            this.walkTarget = null;
            this.mineTarget = null;
            this.placeTarget = null;
            this.cookTarget = null;
            this.tradeTarget = null;
            this.path.setGoal(pos);
        });
    }

    /** 去和村民交易（买得起就成交） */
    public void tradeWith(Entity entity) {
        queue(() -> {
            this.tradeTarget = entity;
            this.walkTarget = null;
            this.mineTarget = null;
            this.placeTarget = null;
            this.containerTarget = null;
            this.path.setGoal(entity.blockPosition());
        });
    }

    /** 去工作台合成目标物品（times 份） */
    public void openCraft(BlockPos pos, Item want, int times) {
        if (pos == null || want == null) {
            return;
        }
        queue(() -> {
            this.craftTarget = pos;
            this.craftWant = want;
            this.craftTimes = Math.max(1, times);
            this.walkTarget = null;
            this.mineTarget = null;
            this.placeTarget = null;
            this.containerTarget = null;
            this.tradeTarget = null;
            this.smeltTarget = null;
            this.brewTarget = null;
            this.path.setGoal(pos);
        });
    }

    /** 去熔炉烧炼（自动放原料 / 燃料，先收成品） */
    public void openSmelt(BlockPos pos) {
        if (pos == null) {
            return;
        }
        queue(() -> {
            this.smeltTarget = pos;
            this.walkTarget = null;
            this.mineTarget = null;
            this.placeTarget = null;
            this.containerTarget = null;
            this.tradeTarget = null;
            this.craftTarget = null;
            this.brewTarget = null;
            this.path.setGoal(pos);
        });
    }

    /** 去酿造台酿药水（先收成品，再放瓶子和原料） */
    public void openBrew(BlockPos pos) {
        if (pos == null) {
            return;
        }
        queue(() -> {
            this.brewTarget = pos;
            this.walkTarget = null;
            this.mineTarget = null;
            this.placeTarget = null;
            this.containerTarget = null;
            this.tradeTarget = null;
            this.craftTarget = null;
            this.smeltTarget = null;
            this.path.setGoal(pos);
        });
    }

    public void say(String text) {
        queue(() -> this.chat.sayForced(text));
    }

    public void runCommand(String command) {
        queue(() -> this.chat.forceCommand(command));
    }

    public void setFollow(String name) {
        queue(() -> {
            this.followName = name;
            this.profile.follow = name == null ? "" : name;
        });
    }

    /** 当前正在跟随的玩家名（没跟随返回 null）——队长的队员据此判断要不要"带队" */
    public String followTargetName() {
        return this.followName == null || this.followName.isBlank() ? null : this.followName;
    }

    public void stop() {
        queue(() -> {
            this.walkTarget = null;
            this.mineTarget = null;
            this.placeTarget = null;
            this.buildQueue.clear();
            this.buildRetry = 0;
            this.buildTicks = 0;
            this.cookTarget = null;
            this.attackTarget = null;
            this.followName = null;
            this.path.clear();
            this.combat.clear();
            this.crystal.reset();
            this.blockTactics.reset();
            this.escape.reset();
            this.bridge.stop();
            this.ranged.release();
            this.mining.reset();
            this.chop.reset();
            this.explore.stop();
            this.player.input().resetAll();
        });
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    public void tick() {
        runIntents();
        AiInput in = this.player.input();
        in.resetAll();
        applyItemHold(in);

        if (this.player.isRemoved()) {
            return;
        }
        if (this.player.isDeadOrDying()) {
            setState(AiState.DEAD);
            this.path.clear();
            this.bridge.stop();
            return;
        }
        if (this.actionCooldown > 0) {
            this.actionCooldown--;
        }
        trackMovement();
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
            this.food.organizeFood();
            if (this.food.tick()) {
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

        // 1.5) 危险时逃生（末影珍珠 / 落地水 / 撤退）
        if (escapeTick(in)) {
            return;
        }

        // 1.6) 掉水里会游泳：先浮出水面换气，再朝目标 / 最近的岸边游
        if (swimTick(in)) {
            return;
        }

        // 1.8) API Key 直接操控移动等动作：短暂接管（约 2 秒），期间不做别的自主行为
        if (this.manualTicks > 0) {
            applyManual(in);
            this.manualTicks--;
            this.path.clear();
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
                setState(AiState.BRIDGING);
                if (this.profile.bridgeAttack) {
                    attackNearbyWhileBridging(in);
                }
                return;
            }
        }

        // 3) 战斗
        if (fightTick(in)) {
            return;
        }

        // 3.5) 卡住了又想去高处：垫一块方块踩上去（真人爬不上去也是这么干的）
        if (stepUpTick(in)) {
            return;
        }

        // 3.6) 组队后勤：队友缺食物 / 空手时送过去（低频触发，不当主业）
        if (teamShareTick(in)) {
            return;
        }

        // 4) 明确任务：挖 / 放 / 去某处
        //    allowDig 默认就开着，A* 按 DIG_COST 权衡挖 vs 绕，这里不再强制覆盖
        if (taskTick(in)) {
            return;
        }

        // 5) 自主生存
        if (survivalTick(in)) {
            return;
        }

        // 5.1) 生计：挖矿（矿脉 + 支巷）/ 砍树（整棵树）/ 出门探索并记得回家
        //      这几件事都是"长活"，只在没任务没敌人时干，且每一步都走原版挖掘 / 寻路
        if (jobTick()) {
            return;
        }

        // 5.5) 组队队形：没任务没敌人时守在队长身边的环形位，一队人看起来是一伙的
        if (teamFormationTick(in)) {
            return;
        }

        // 5.6) 空闲生计：收庄稼补种 / 钓鱼 / 驯服动物（什么都不干才轮得到）
        if (passiveTick(in)) {
            return;
        }

        idleTick(in);
    }

    /**
     * 生计分支：挖矿 / 砍树 / 探索。
     * <p>
     * 顺序就是优先级：砍树（树就在眼前，先收拾完）→ 挖矿（矿脉 / 支巷）→ 探索。
     * 三者都只在没有任务、没有敌人时才会轮到（本方法排在 taskTick / fightTick 之后）。
     *
     * @return true 表示本 tick 已经被生计占住
     */
    private boolean jobTick() {
        // 跟随玩家时不自己乱跑（跟丢主人比挖矿严重得多）
        if (this.followName != null && !this.followName.isBlank()) {
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
        return false;
    }

    private boolean passiveTick(AiInput in) {
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

    private void runIntents() {
        Runnable intent;
        while ((intent = this.intents.poll()) != null) {
            try {
                intent.run();
            } catch (Throwable ignored) {
                // 单个指令出错不影响 AI 继续运行
            }
        }
    }

    private void trackMovement() {
        Vec3 pos = this.player.position();
        if (pos.distanceToSqr(this.lastPos) < 0.0004D) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = 0;
        }
        this.lastPos = pos;
    }

    // ------------------------------------------------------------------
    // 逃生
    // ------------------------------------------------------------------

    /** 危险时的自救：末影珍珠 / 落地水 / 撤退 */
    private boolean escapeTick(AiInput in) {
        if (!this.profile.escape) {
            return false;
        }
        // 一定要有敌人 / 投射物 / 高处坠落才算危险，否则会一直乱跑
        boolean threatened = this.escape.nearestThreat(6.0D) != null;
        boolean falling = !this.player.onGround() && this.player.fallDistance > 5.0F;
        if (!threatened && !falling) {
            return false;
        }
        if (this.escape.tick(in)) {
            setState(AiState.ESCAPING);
            return true;
        }
        return false;
    }

    /** 附近飞来的投射物（给逃生 / 举盾判定用） */
    public Projectile incomingProjectile(double range) {
        return this.ranged.incomingProjectile(range);
    }

    // ------------------------------------------------------------------
    // 战斗
    // ------------------------------------------------------------------

    private boolean fightTick(AiInput in) {
        if (!this.profile.combat) {
            this.combat.clear();
            this.ranged.release();
            return false;
        }
        // 挨打才还手：只有被玩家 / 怪主动攻击了，才把攻击者设成目标。
        // AI 不会主动去招惹任何玩家（包括陌生人），更不会攻击主人。
        LivingEntity hurtBy = this.player.getLastHurtByMob();
        if (hurtBy != null && hurtBy != this.player && hurtBy.isAlive()
                && this.player.tickCount - this.player.getLastHurtByMobTimestamp() < 200
                && !isOwner(hurtBy) && !sameTeam(hurtBy)) {
            if (this.attackTarget != hurtBy) {
                this.attackTarget = hurtBy;
                this.combat.setTarget(hurtBy);
            }
        }
        // 主人永远不是目标
        if (this.attackTarget != null && isOwner(this.attackTarget)) {
            this.attackTarget = null;
        }
        // 主人被谁打了 -> 优先帮主人打回去
        Player owner = ownerPlayer();
        if (owner != null) {
            LivingEntity ownerHurt = owner.getLastHurtByMob();
            if (ownerHurt != null && ownerHurt.isAlive() && ownerHurt != this.player
                    && owner.tickCount - owner.getLastHurtByMobTimestamp() < 200
                    && !isOwner(ownerHurt) && !(ownerHurt instanceof AiPlayer)
                    && this.player.distanceToSqr(ownerHurt) <= 576.0D) {
                if (this.attackTarget != ownerHurt) {
                    this.attackTarget = ownerHurt;
                    this.combat.setTarget(ownerHurt);
                }
            }
        }
        // 组队协战：自己没目标时，看队友在打谁 / 谁在打队友，赶去支援
        if (this.attackTarget == null) {
            Entity assist = findTeammateAssist();
            if (assist != null) {
                this.attackTarget = assist;
                this.combat.setTarget(assist);
            }
        }
        Entity enemy = this.attackTarget;
        if (enemy instanceof Player other && (other.isCreative() || other.isSpectator())) {
            // 创造 / 旁观模式的玩家打不动，别把状态机浪费在他们身上
            // （自动索敌已经过滤过，这里是给 /aiplayer attack 和 LLM 指定的目标兜底）
            this.attackTarget = null;
            enemy = null;
        }
        if (enemy != null && (!enemy.isAlive() || enemy.isRemoved())) {
            maybeTauntKill(enemy);
            this.attackTarget = null;
            enemy = null;
        }
        // 只在"根本没有目标"时才重新索敌。
        // 之前是 enemy == null || 看不见 就重新索敌：玩家打了 AI 一下绕到墙后，AI 就会
        // 把锁丢掉、换成附近的僵尸 / 别的怪，然后掉头往反方向去打 —— 看起来就是"往反方向攻击"。
        if (enemy == null) {
            // 省 CPU：没目标时不必每 tick 做一次 16 格实体查询（多 AI 同屏时这是最大的一笔常驻开销）。
            // 挨打（hurtTime>0）立刻扫，保证还手手感；平时每 4 tick 扫一次，最多 0.2 秒延迟，肉眼无感。
            if (this.player.hurtTime > 0 || this.player.tickCount % 4 == 0) {
                enemy = findEnemy(16.0D);
            }
        } else if (!this.player.hasLineOfSight(enemy) && this.player.distanceToSqr(enemy) > 256.0D) {
            // 既看不见、又离得很远（>16 格）：这才是真跟丢了，允许换目标
            this.attackTarget = null;
            enemy = findEnemy(16.0D);
        }
        if (enemy == null) {
            this.combat.clear();
            this.crystal.reset();
            this.blockTactics.reset();
            // 敌人已经死了 / 找不到了：把弓放下，别继续拉着蓄力（否则烧死的僵尸还会被补一箭）
            this.ranged.release();
            return false;
        }
        double dist = Math.sqrt(this.player.distanceToSqr(enemy));

        // 主动放方块：被近身 / 低血时堵路、搭塔抢高点（有冷却和概率限制，不会乱放）
        if (this.blockTactics.tick(enemy, in)) {
            setState(AiState.COMBAT);
            return true;
        }

        // 水晶 PvP：只对玩家（真玩家 / AI 玩家）用 —— 打怪不掏水晶，怪物用弓箭/近战就够了。
        if (this.profile.crystalPvp && enemy instanceof Player && dist <= 6.5D
                && CrystalPvpController.hasCrystalKit(this.player)) {
            // 水晶 PvP 前自动把不死图腾塞附手（如果背包里有）
            equipTotemOffhand();
            this.combat.clear();
            if (this.crystal.tick(enemy)) {
                setState(AiState.CRYSTAL);
                return true;
            }
        } else {
            this.crystal.reset();
        }

        // 锚 PvP：重生锚 + 萤石，主世界引爆（爆炸毁地形，只对玩家用）
        if (this.profile.anchorPvp && enemy instanceof Player && dist <= 7.0D
                && AnchorPvpController.hasKit(this.player)) {
            this.combat.clear();
            if (this.anchor.tick(enemy)) {
                setState(AiState.CRYSTAL);
                return true;
            }
        } else {
            this.anchor.reset();
        }

        // 风弹重锤：3~8 格起手（风弹上天 -> 空中瞄准 -> 重锤摔劈），命中自动免摔
        if (this.profile.windMace && dist <= 9.0D
                && WindChargeMaceController.hasKit(this.player)) {
            this.combat.clear();
            if (this.windMace.tick(enemy)) {
                setState(AiState.COMBAT);
                return true;
            }
        } else if (!this.windMace.isActive()) {
            this.windMace.reset();
        }

        // 鞘翅长矛：6~24 格突进（鞘翅滑翔俯冲 + 三叉戟刺击）
        if (this.profile.elytraSpear && ElytraSpearController.hasKit(this.player)) {
            this.combat.clear();
            if (this.elytra.tick(enemy)) {
                setState(AiState.COMBAT);
                return true;
            }
        } else if (!this.elytra.isActive()) {
            this.elytra.reset();
        }

        // 组队分工：有队友协同且自己是"拉扯位"时，不硬冲 —— 保持 5~8 格放风筝，只打玩家
        if (enemy instanceof Player && isHarasser() && countNearbyTeammates(24.0D) > 0) {
            harassTick(in, enemy, dist);
            setState(AiState.COMBAT);
            return true;
        }

        // 近战
        if (dist <= CombatController.ENGAGE_RANGE) {
            this.combat.setTarget(enemy);
            if (this.combat.tick()) {
                this.ranged.release();
                setState(AiState.COMBAT);
                return true;
            }
        }
        // 远程：必须看得见目标才射（否则会隔着墙 / 对着已经消失的目标拉弓放箭）；
        // 距离超过 14 格箭的抛物线弯得厉害，多半撞地形浪费箭 —— 干脆追近了再射。
        if (this.profile.ranged && dist >= 4.0D && dist <= 14.0D && this.ranged.hasRanged()
                && canShootLine(enemy) && this.ranged.tick(enemy)) {
            setState(AiState.RANGED);
            return true;
        }
        this.ranged.release();
        // 追
        if (dist > 2.0D) {
            this.path.setGoal(enemy.blockPosition());
            if (this.path.tick()) {
                setState(AiState.MOVING);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 击败嘲讽：击杀对方 AI 后来一句骚话（频率限制，不刷屏）
    // ------------------------------------------------------------------

    /** 两条嘲讽之间的最短间隔 */
    private static final long TAUNT_COOLDOWN_MS = 30_000L;
    private static final String[] TAUNT_LINES = {
            "%s，就这？",
            "承让了，%s。回去练练再来。",
            "%s 已经躺了，还有谁？",
            "下次带点装备再来吧，%s。",
            "%s：我的装备呢？哦，掉地上了。",
    };
    private long lastTauntAt;

    /** 目标死了之后调用：确实是我们的最后一击、且对方是 AI 玩家时，嘲讽一句 */
    private void maybeTauntKill(Entity victim) {
        if (!this.profile.taunt || !(victim instanceof AiPlayer victimAi)) {
            return;
        }
        // 最后一击必须是我们打的，抢人头不算
        if (victimAi.getLastHurtByMob() != this.player) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastTauntAt < TAUNT_COOLDOWN_MS) {
            return;
        }
        this.lastTauntAt = now;
        String line = TAUNT_LINES[this.RNG.nextInt(TAUNT_LINES.length)];
        this.chat.say(String.format(line, victimAi.getName().getString()));
    }

    /** 搭路时顺手打一下旁边的敌人 */
    private void attackNearbyWhileBridging(AiInput in) {
        Entity enemy = findEnemy(3.0D);
        if (enemy == null) {
            return;
        }
        in.attackEntity = true;
        in.attackTarget = enemy;
        Vec3 point = enemy.position().add(0.0D, enemy.getBbHeight() * 0.7D, 0.0D);
        this.player.lookAt(point, 25.0F);
    }

    /** 水晶 PvP 前自动把背包里的不死图腾塞到附手；附手已有图腾则不动 */
    private void equipTotemOffhand() {
        Inventory inv = this.player.getInventory();
        int offhandSlot = 40; // Inventory.SLOT_OFFHAND
        ItemStack off = inv.getItem(offhandSlot);
        if (off != null && !off.isEmpty() && off.getItem() == net.minecraft.world.item.Items.TOTEM_OF_UNDYING) {
            return; // 已经在附手了
        }
        // 找图腾（整个背包，包括快捷栏）
        int totemSlot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && !s.isEmpty() && s.getItem() == net.minecraft.world.item.Items.TOTEM_OF_UNDYING) {
                totemSlot = i;
                break;
            }
        }
        if (totemSlot < 0) return;
        // 交换：图腾 ↔ 附手当前物品
        ItemStack totem = inv.getItem(totemSlot);
        inv.setItem(totemSlot, off);
        inv.setItem(offhandSlot, totem);
    }

    private Entity findEnemy(double range) {
        AABB box = this.player.getBoundingBox().inflate(range);
        List<LivingEntity> list = this.player.level().getEntitiesOfClass(LivingEntity.class, box);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity e : list) {
            if (e == this.player || !e.isAlive() || e.isInvulnerable()) {
                continue;
            }
            if (this.player.isAlliedTo(e)) {
                continue;
            }
            // 组队系统：同队的 AI 永远不打
            if (sameTeam(e)) {
                continue;
            }
            boolean pvp = this.allowPlayerPvp || this.profile.alwaysPvp;
            if (e instanceof AiPlayer otherAi) {
                // 只在开了 PvP 时才打别的 AI（只要求攻击者开了就行）
                if (!pvp && otherAi != this.attackTarget) {
                    continue;
                }
            }
            if (e instanceof Player other) {
                if (other.isCreative() || other.isSpectator()) {
                    continue;
                }
                // 主人永远不打；其他玩家只有明确开过 PvP 才打
                if (isOwner(e) || !pvp) {
                    continue;
                }
            } else if (e instanceof net.minecraft.world.entity.monster.Monster) {
                // 敌对怪物（僵尸 / 骷髅 / 苦力怕）— 主动打
            } else if (e instanceof net.minecraft.world.entity.npc.villager.Villager
                    || e instanceof net.minecraft.world.entity.animal.golem.IronGolem) {
                // 村民系 + 铁傀儡 — 不能主动杀（玩家需要交易和保护）
                continue;
            } else if (e instanceof net.minecraft.world.entity.animal.Animal) {
                // 被动动物（牛 / 羊 / 猪 / 鸡）：只有 hunt=true 才主动打（获取食物）
                if (!this.profile.hunt) {
                    continue;
                }
            } else {
                // 其他杂项（Ambient / WaterCreature 等）默认不主动打
                continue;
            }






























            double score = this.player.distanceToSqr(e);
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 组队系统
    // ------------------------------------------------------------------

    /** 当前攻击目标（队友协战时读取） */
    public Entity currentTarget() {
        return this.attackTarget;
    }

    /** 对方是否与我同队（双方配置了相同且非空的 team） */
    private boolean sameTeam(Entity e) {
        if (!(e instanceof AiPlayer other) || other == this.player) {
            return false;
        }
        String mine = this.profile.team;
        return mine != null && !mine.isBlank() && mine.equals(other.profile().team);
    }

    /** 队友协战：队友正在打谁 / 谁在打队友（32 格内），就帮着打谁 */
    private Entity findTeammateAssist() {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank()) {
            return null;
        }
        boolean pvp = this.allowPlayerPvp || this.profile.alwaysPvp;
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate == this.player || !mate.isAlive() || mate.brain() == null) {
                continue;
            }
            if (!mine.equals(mate.profile().team)) {
                continue;
            }
            if (this.player.distanceToSqr(mate) > 1024.0D) {
                continue; // 32 格外的不支援，各打各的
            }
            // 队友在打谁就跟上（同队 / 主人 / 创造模式的不碰；打 AI 还要求自己开了 PvP）
            Entity mateTarget = mate.brain().currentTarget();
            if (mateTarget != null && mateTarget.isAlive() && !sameTeam(mateTarget) && !isOwner(mateTarget)) {
                if (mateTarget instanceof Player p && (p.isCreative() || p.isSpectator())) {
                    // 跳过
                } else if (!(mateTarget instanceof AiPlayer) || pvp) {
                    return mateTarget;
                }
            }
            // 谁在打队友就还击谁
            LivingEntity mateHurtBy = mate.getLastHurtByMob();
            if (mateHurtBy != null && mateHurtBy.isAlive() && mateHurtBy != this.player
                    && mate.tickCount - mate.getLastHurtByMobTimestamp() < 100
                    && !sameTeam(mateHurtBy) && !isOwner(mateHurtBy)
                    && (!(mateHurtBy instanceof AiPlayer) || pvp)) {
                return mateHurtBy;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 组队升级：分工（拉扯 / 输出）、共享（食物 / 武器）、队形跟随
    // ------------------------------------------------------------------

    /** 对同一个队友两次分享的最短间隔（tick） */
    private static final int SHARE_COOLDOWN_TICKS = 600;
    /** 自己身上少于这个口粮数就不算有余粮，不往外送 */
    private static final int SPARE_FOOD_MIN = 12;
    /** 拉扯位与敌人保持的距离区间 */
    private static final double HARASS_NEAR = 5.0D;
    private static final double HARASS_FAR = 8.0D;

    /** 拉扯位绕圈方向 / 剩余 tick：别绕成固定半径的圆，会被预判 */
    private int harassDir;
    private int harassDirTicks;
    /** 队友 -> 上次分享的游戏 tick */
    private final Map<UUID, Long> lastShareAt = new HashMap<>();

    // ---- 同队闲聊 ----
    /** 下一次可以开口的游戏 tick */
    private int teamChatNextTick;
    /** 我最近说出口的那句闲聊（队友据此接话） */
    private String teamChatLastLine;
    private long teamChatLastAtMs;
    /** 我最近一次接话的墙钟时间，防止两个 AI 来回刷屏 */
    private long teamChatRepliedAtMs;

    /**
     * 分工：同队 AI 按名字排序，排名偶数位当"输出位"（直冲近战），奇数位当"拉扯位"（放风筝）。
     * 保证一队至少有一个输出，不会全员绕圈没人砍。
     */
    private boolean isHarasser() {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank()) {
            return false;
        }
        int before = 0;
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate == this.player || !mate.isAlive() || !mine.equals(mate.profile().team)) {
                continue;
            }
            if (mate.getName().getString().compareTo(this.profile.name) < 0) {
                before++;
            }
        }
        return before % 2 == 1;
    }

    /** range 格内同队且活着的队友数（不含自己） */
    private int countNearbyTeammates(double range) {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank()) {
            return 0;
        }
        int n = 0;
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate != this.player && mate.isAlive() && mine.equals(mate.profile().team)
                    && this.player.distanceToSqr(mate) <= range * range) {
                n++;
            }
        }
        return n;
    }

    /**
     * 拉扯位：保持 {@value #HARASS_NEAR}~{@value #HARASS_FAR} 格放风筝。
     * 有弓就拉远了射；贴太近面朝敌人倒退（真人放风筝也是这样，不背对目标挨刀）；
     * 甜区里切向绕圈，方向定期随机换。
     */
    private void harassTick(AiInput in, Entity enemy, double dist) {
        this.combat.clear();
        if (this.profile.ranged && dist >= 4.0D && dist <= 14.0D && this.ranged.hasRanged()
                && canShootLine(enemy) && this.ranged.tick(enemy)) {
            return;
        }
        this.ranged.release();
        double dx = enemy.getX() - this.player.getX();
        double dz = enemy.getZ() - this.player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) {
            len = 1.0D;
        }
        // 一直面向敌人：侧移绕圈也盯着对方
        this.player.lookAt(enemy.getX(), enemy.getEyeY(), enemy.getZ(), 25.0F);
        in.sprint = dist > HARASS_FAR;
        if (dist < HARASS_NEAR) {
            in.backward = true;
            if (dist < 3.2D) {
                // 贴脸了才挥一刀，平时不硬砍
                in.attackEntity = true;
                in.attackTarget = enemy;
            }
        } else if (dist > HARASS_FAR) {
            in.forward = true;
        } else {
            if (--this.harassDirTicks <= 0) {
                this.harassDir = RNG.nextBoolean() ? 1 : -1;
                this.harassDirTicks = 30 + RNG.nextInt(30);
            }
            if (this.harassDir > 0) {
                in.right = true;
            } else {
                in.left = true;
            }
        }
        // 绕圈 / 后撤撞墙时跳一下脱困
        if (this.stuckTicks > 10 && this.player.onGround()) {
            in.jump = true;
        }
    }

    /**
     * 组队后勤：16 格内有队友饿了缺粮、或空手没武器，而我有多余的 —— 走到跟前当面丢过去。
     * 掉落物队友自己会捡（和玩家之间丢东西一个玩法）。
     */
    private boolean teamShareTick(AiInput in) {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank() || this.player.tickCount % 20 != 0) {
            return false;   // 低频检查，省性能
        }
        AiPlayer mate = neediestTeammate(mine);
        if (mate == null) {
            return false;
        }
        if (this.player.distanceToSqr(mate) > 9.0D) {
            this.path.setGoal(mate.blockPosition());
            if (!this.path.tick()) {
                setState(AiState.MOVING);
            }
            return true;
        }
        // 到跟前了：把东西丢给队友（真人按 Q 的动作）
        ItemStack gift = shareGiftFor(mate);
        if (gift != null) {
            dropToward(mate, gift);
            this.lastShareAt.put(mate.getUUID(), (long) this.player.tickCount);
        }
        return false;
    }

    /** 找一个值得送的队友：冷却已过且我正好有他能用的东西（16 格内取最近） */
    private AiPlayer neediestTeammate(String team) {
        AiPlayer best = null;
        double bestDist = 256.0D;   // 16 格
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate == this.player || !mate.isAlive()) {
                continue;
            }
            if (!team.equals(mate.profile().team)) {
                continue;
            }
            double d2 = this.player.distanceToSqr(mate);
            if (d2 > bestDist) {
                continue;
            }
            Long last = this.lastShareAt.get(mate.getUUID());
            if (last != null && this.player.tickCount - last < SHARE_COOLDOWN_TICKS) {
                continue;
            }
            if (shareGiftFor(mate) == null) {
                continue;
            }
            bestDist = d2;
            best = mate;
        }
        return best;
    }

    /**
     * 我能给这个队友什么（只探测不拿走）：他饿了给我多余的食物，他空手给我多余的武器。
     * 返回背包里的来源堆，交付时再从中拆出一份。
     */
    private ItemStack shareGiftFor(AiPlayer mate) {
        if (mate.getFoodData().getFoodLevel() < 17) {
            ItemStack spare = spareFood();
            if (spare != null) {
                return spare;
            }
        }
        if (!isWeapon(mate.getMainHandItem())) {
            ItemStack spare = spareWeapon();
            if (spare != null) {
                return spare;
            }
        }
        // 盔甲：他某个部位空着 / 比我身上那件差，而我还穿着更好的，就匀一件给他
        ItemStack armor = spareArmorFor(mate);
        if (armor != null) {
            return armor;
        }
        return null;
    }

    /** 我能匀给队友的盔甲：必须在对应槽位、比他现在那件好、又不比我身上那件好（自己不亏） */
    private ItemStack spareArmorFor(AiPlayer mate) {
        Inventory inv = this.player.getInventory();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            int mineTier = armorTier(this.player.getItemBySlot(slot));
            if (mineTier <= 0) {
                continue;   // 我自己这个部位都没穿，不能脱给他
            }
            int mateTier = armorTier(mate.getItemBySlot(slot));
            if (mateTier >= mineTier) {
                continue;   // 他这件不比我差，没必要换
            }
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || armorSlotOf(s) != slot) {
                    continue;
                }
                int t = armorTier(s);
                if (t > mateTier && t <= mineTier) {
                    return s;   // 送出去我不亏，他还能升级
                }
            }
        }
        return null;
    }

    /** 盔甲槽位顺序：头 -> 胸 -> 腿 -> 脚 */
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** 这件物品该穿哪个槽位（不是盔甲返回 null） */
    private static EquipmentSlot armorSlotOf(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (path.equals("turtle_helmet") || path.endsWith("_helmet")) {
            return EquipmentSlot.HEAD;
        }
        if (path.endsWith("_chestplate")) {
            return EquipmentSlot.CHEST;
        }
        if (path.endsWith("_leggings")) {
            return EquipmentSlot.LEGS;
        }
        if (path.endsWith("_boots")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    /** 盔甲档位：越大越硬（netherite 6 … leather 1）；不是盔甲返回 0 */
    private static int armorTier(ItemStack s) {
        EquipmentSlot slot = armorSlotOf(s);
        if (slot == null) {
            return 0;
        }
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (path.startsWith("netherite")) {
            return 6;
        }
        if (path.startsWith("diamond")) {
            return 5;
        }
        if (path.startsWith("iron")) {
            return 4;
        }
        if (path.startsWith("chainmail")) {
            return 3;
        }
        if (path.startsWith("golden")) {
            return 2;
        }
        return 1;   // 皮革 / 海龟壳 / 其它模组盔甲，统一算最低档
    }

    /** 一组能匀出去的食物（自己总口粮 >= {@value #SPARE_FOOD_MIN} 才算有余粮） */
    private ItemStack spareFood() {
        Inventory inv = this.player.getInventory();
        int total = 0;
        ItemStack found = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (FoodManager.isFood(s)) {
                total += s.getCount();
                if (found == null) {
                    found = s;
                }
            }
        }
        return total >= SPARE_FOOD_MIN ? found : null;
    }

    /** 一把能匀出去的武器（我至少要有两把才算多余，自己不能赤手空拳） */
    private ItemStack spareWeapon() {
        Inventory inv = this.player.getInventory();
        int count = 0;
        ItemStack found = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isWeapon(s)) {
                count++;
                if (found == null) {
                    found = s;
                }
            }
        }
        return count >= 2 ? found : null;
    }

    /** 武器判定：按物品类名匹配（和 EquipmentManager 同一套关键词，26.2 没有 SwordItem 类） */
    private static boolean isWeapon(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String name = s.getItem().getClass().getSimpleName();
        if (name.contains("Pickaxe")) {
            return false;   // "Pickaxe" 也含 "Axe"，但镐不是给队友防身的
        }
        return name.contains("Sword") || name.contains("Axe") || name.contains("Trident")
                || name.contains("Mace") || name.contains("Bow") || name.contains("Spear")
                || name.contains("Katana") || name.contains("Dagger") || name.contains("Scythe")
                || name.contains("Halberd");
    }

    /** 当面把东西丢给对方：轻抛一份过去，像真人按 Q */
    private void dropToward(Entity target, ItemStack source) {
        int n = Math.min(source.getCount(), FoodManager.isFood(source) ? 8 : 1);
        ItemStack gift = source.split(n);
        if (gift.isEmpty()) {
            return;
        }
        Vec3 from = this.player.position().add(0.0D, 1.2D, 0.0D);
        Vec3 to = target.position().add(0.0D, 0.8D, 0.0D);
        Vec3 vel = to.subtract(from);
        if (vel.lengthSqr() < 0.001D) {
            vel = new Vec3(0.0D, 0.1D, 0.0D);
        }
        vel = vel.normalize().scale(0.3D).add(0.0D, 0.1D, 0.0D);
        ItemEntity drop = new ItemEntity(this.player.level(), from.x, from.y, from.z, gift);
        drop.setDeltaMovement(vel);
        drop.setPickUpDelay(10);
        this.player.level().addFreshEntity(drop);
    }

    /**
     * 队形跟随：没任务没敌人时，同队按名字排序 0 号是队长，
     * 其他人守在队长身边按序号均分的环形位（2.5 格半径）。
     */
    private boolean teamFormationTick(AiInput in) {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank()) {
            return false;
        }
        List<AiPlayer> squad = new ArrayList<>();
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate.isAlive() && mine.equals(mate.profile().team)) {
                squad.add(mate);
            }
        }
        if (squad.size() < 2) {
            return false;
        }
        squad.sort((a, b) -> a.getName().getString().compareTo(b.getName().getString()));
        int myIdx = squad.indexOf(this.player);
        if (myIdx <= 0) {
            return false;   // 我是队长：站桩定坐标，队员来围
        }
        AiPlayer leader = squad.get(0);
        // 队长带队：队长正在跟随某个玩家（主人）时，全队直接跟到那个玩家身边，
        // 而不是原地守着队长——否则队长一走远就超出跟随距离，队伍当场散伙。
        String leaderFollow = leader == this.player ? null : leader.brain().followTargetName();
        Player anchorPlayer = leaderFollow == null ? null
                : this.player.serverLevel().getServer().getPlayerList().getPlayerByName(leaderFollow);
        boolean led = anchorPlayer != null && anchorPlayer.level() == this.player.level();
        // 集结点：队长带队时用被跟随的玩家，否则用队长本人
        double ax = led ? anchorPlayer.getX() : leader.getX();
        double ay = led ? anchorPlayer.getY() : leader.getY();
        double az = led ? anchorPlayer.getZ() : leader.getZ();
        double anchorDist2 = led ? this.player.distanceToSqr(ax, ay, az) : this.player.distanceToSqr(leader);
        if (anchorDist2 > 2304.0D) {
            return false;   // 集结点在 48 格外（可能在别的区域），各干各的
        }
        double angle = (Math.PI * 2.0D * myIdx) / squad.size();
        // 队长带队时用更大的半径，别把被跟随的玩家挤在中间
        double radius = led ? 4.0D : 2.5D;
        double sx = ax + Math.cos(angle) * radius;
        double sz = az + Math.sin(angle) * radius;
        if (this.player.distanceToSqr(sx, this.player.getY(), sz) < 4.0D) {
            return false;   // 已就位
        }
        this.path.setGoal(BlockPos.containing(sx, ay, sz));
        if (!this.path.tick()) {
            setState(AiState.FOLLOWING);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 同队闲聊
    // ------------------------------------------------------------------

    /**
     * 同队闲聊：附近有队友时偶尔互相搭两句话，让一队人看起来像在配合，而不是各打各的。
     * <p>
     * 纯本地文本、不花 token；只有开了 {@code chat + autoChat} 才说，自己起话头 45~75 秒一句。
     * 队友 {@value #TEAM_CHAT_REPLY_WINDOW_MS} 毫秒内刚说过话就优先接一句（每人 20 秒最多接一次），
     * 否则自己找个话头。战斗 / 打水晶 / 搭路时不插嘴。
     */
    private void teamChatTick() {
        AiProfile p = this.profile;
        if (p == null || p.team == null || p.team.isBlank() || !p.chat || !p.autoChat) {
            return;
        }
        if (this.player.tickCount % 20 != 0 || this.player.tickCount < this.teamChatNextTick) {
            return;   // 每秒看一次，且不早于冷却
        }
        if (this.state == AiState.COMBAT || this.state == AiState.CRYSTAL
                || this.state == AiState.BRIDGING || this.bridge.isActive()) {
            return;   // 打架 / 搭路时不闲聊
        }
        AiPlayer mate = nearestTeamMate();
        if (mate == null) {
            this.teamChatNextTick = this.player.tickCount + 200;   // 附近没队友，过会儿再看
            return;
        }
        String mateName = mate.getScoreboardName();
        long now = System.currentTimeMillis();
        // 1) 队友刚说了话 → 接一句
        AiBrain mateBrain = mate.brain();
        if (mateBrain != null && mateBrain.teamChatLastLine != null
                && now - mateBrain.teamChatLastAtMs < TEAM_CHAT_REPLY_WINDOW_MS
                && now - this.teamChatRepliedAtMs > TEAM_CHAT_REPLY_GAP_MS) {
            String line = teamReplyLine(mateName);
            if (this.chat.say(line)) {
                this.teamChatRepliedAtMs = now;
                this.teamChatLastLine = line;
                this.teamChatLastAtMs = now;
                this.teamChatNextTick = this.player.tickCount + TEAM_CHAT_MIN_GAP_TICKS;
            }
            return;
        }
        // 2) 自己起个话头
        String line = teamOpenLine(mateName);
        if (this.chat.say(line)) {
            this.teamChatLastLine = line;
            this.teamChatLastAtMs = now;
            this.teamChatNextTick = this.player.tickCount
                    + TEAM_CHAT_MIN_GAP_TICKS + RNG.nextInt(TEAM_CHAT_JITTER_TICKS);
        }
    }

    /** {@value #TEAM_CHAT_RANGE} 格内最近的同队且存活队友，没有返回 null */
    private AiPlayer nearestTeamMate() {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank()) {
            return null;
        }
        AiPlayer best = null;
        double bestDist = TEAM_CHAT_RANGE * TEAM_CHAT_RANGE;
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate == this.player || !mate.isAlive() || mate.profile() == null
                    || !mine.equals(mate.profile().team)) {
                continue;
            }
            double d = this.player.distanceToSqr(mate);
            if (d <= bestDist) {
                bestDist = d;
                best = mate;
            }
        }
        return best;
    }

    /** 起话头：招呼队友一句（按当前状态挑，偶尔夹带下一步打算） */
    private static String teamOpenLine(String mate) {
        String[] lines = {
                "「%s」跟紧点，别掉队。",
                "「%s」我先去前面探探路。",
                "「%s」这边有水，我去钓两条鱼。",
                "「%s」附近有矿，回头一起挖。",
                "「%s」你血还够吗？不够我掩护。",
                "「%s」我先收个庄稼，马上跟上。",
                "「%s」这地方不错，先歇口气。",
                "「%s」把盾举好，别硬吃伤害。",
                "「%s」我往东边去，有事喊我。",
                "「%s」缺吃的跟我说，我这边有余粮。"
        };
        return String.format(lines[RNG.nextInt(lines.length)], mate);
    }

    /** 接队友一句话 */
    private static String teamReplyLine(String mate) {
        String[] lines = {
                "收到，「%s」。",
                "「%s」明白，这就来。",
                "嗯，「%s」，我看着你。",
                "「%s」你自己也小心点。",
                "好，「%s」，交给我。"
        };
        return String.format(lines[RNG.nextInt(lines.length)], mate);
    }

    // ------------------------------------------------------------------
    // 任务
    // ------------------------------------------------------------------

    private boolean taskTick(AiInput in) {
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

    private boolean mineTick(AiInput in) {
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
        if (bestSlot >= 0) {
            this.player.selectSlot("mine", com.aiplayer.util.InventoryLock.PRIO_TASK, bestSlot);
        }
    }

    private BlockPos nextMineTarget(BlockPos pos) {
        // 继续往下挖一格（挖矿时最自然的方向）
        BlockPos below = pos.below();
        if (!this.player.level().getBlockState(below).isAir() && !CompatManager.isProtected(this.player.level().getBlockState(below))) {
            return below;
        }
        return null;
    }

    private boolean placeTick(AiInput in) {
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

    private boolean cookTick(AiInput in) {
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
    private boolean containerTick(AiInput in) {
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
    private boolean tradeTick(AiInput in) {
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
    private boolean craftTick(AiInput in) {
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
    private boolean smeltTick(AiInput in) {
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
    private boolean brewTick(AiInput in) {
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

    /** 方块类简单名（带缓存：同一个方块类型只查一次，省掉每格 new String） */
    private static final Map<Class<?>, String> BLOCK_CLASS_NAMES = new ConcurrentHashMap<>();

    private static String blockClassName(Block block) {
        return BLOCK_CLASS_NAMES.computeIfAbsent(block.getClass(), Class::getSimpleName);
    }

    /** 这个坐标是不是指定类名的工位（工作台 / 熔炉 / 酿造台）；多个名字任中其一 */
    private boolean stationAt(BlockPos pos, String... nameParts) {
        String name = blockClassName(this.player.level().getBlockState(pos).getBlock());
        for (String part : nameParts) {
            if (name.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** 工位的中文标签，不是工位返回 null */
    private String stationLabel(BlockPos pos) {
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
    private BlockPos nearestStation(int radius, String... nameParts) {
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
    private boolean swimTick(AiInput in) {
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

    /** 找最近能站人的岸边（半径内、非水、有碰撞、上方塞得下人且不是水） */
    private BlockPos nearestShore(double radius) {
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
    private boolean stepUpTick(AiInput in) {
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
    private boolean climbableAhead(float yaw) {
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
    private boolean ensureBlockInHand() {
        return ensureBlockInHand("stepup", com.aiplayer.util.InventoryLock.PRIO_BRIDGE);
    }

    /** 手上没方块就从背包切一块过来（owner / 优先级可指定） */
    private boolean ensureBlockInHand(String owner, int prio) {
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
    private boolean hasAnyBlock() {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).getItem() instanceof BlockItem) {
                return true;
            }
        }
        return false;
    }

    private boolean walkTick(AiInput in) {
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

    private boolean followTick(AiInput in) {
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

    // ------------------------------------------------------------------
    // 自主生存：捡东西 / 狩猎 / 闲逛
    // ------------------------------------------------------------------

    private boolean survivalTick(AiInput in) {
        // 捡掉落物（食物、装备）
        ItemEntity drop = nearestWantedDrop(12.0D);
        if (drop != null) {
            // 玩家丢给 AI 的东西：走到跟前时道个谢
            if (this.player.distanceToSqr(drop) < 9.0D) {
                thankIfGiven(drop);
            }
            this.path.setGoal(drop.blockPosition());
            if (!this.path.tick()) {
                setState(AiState.MOVING);
            }
            return true;
        }
        // 之前烤的东西该取了 -> 回熔炉一趟（cookAt 会先把成品收进背包）
        BlockPos pendingFurnace = this.food.pendingFurnace();
        if (pendingFurnace != null) {
            this.cookTarget = pendingFurnace;
            this.path.setGoal(pendingFurnace);
            setState(AiState.MOVING);
            return true;
        }
        // 缺食物就去打猎
        if (this.profile.hunt && this.food.bestFoodSlotAnywhere() < 0) {
            Animal prey = nearestAnimal(20.0D);
            if (prey != null) {
                if (this.player.distanceToSqr(prey) <= 9.0D) {
                    this.combat.setTarget(prey);
                    if (this.combat.tick()) {
                        setState(AiState.HUNTING);
                        return true;
                    }
                }
                this.path.setGoal(prey.blockPosition());
                if (!this.path.tick()) {
                    setState(AiState.HUNTING);
                }
                return true;
            }
        }
        // 熔炉还没用过而且有生肉 -> 去烤
        if (this.profile.eat && hasRawMeat() && RNG.nextFloat() < 0.01F) {
            BlockPos furnace = this.food.nearestFurnace(20);
            if (furnace != null) {
                this.cookTarget = furnace;
                this.path.setGoal(furnace);
                return true;
            }
        }
        // 有敌人之外的怪靠近也可以先打（由 fightTick 处理）
        // 饿了又没吃的：附近有箱子就去翻一翻（真人也是这么找物资的）
        if (this.profile.eat && this.food.hungry() && this.food.bestFoodSlotAnywhere() < 0
                && RNG.nextFloat() < 0.05F) {
            BlockPos container = nearestContainer(12.0D);
            if (container != null) {
                this.containerTarget = container;
                this.path.setGoal(container);
                return true;
            }
        }
        // 又饿又找不到任何吃的：附近随便走走找食物，别原地站桩
        if (this.profile.eat && this.food.hungry() && this.food.bestFoodSlotAnywhere() < 0
                && RNG.nextFloat() < 0.02F) {
            BlockPos wander = this.player.blockPosition()
                    .offset(RNG.nextInt(17) - 8, 0, RNG.nextInt(17) - 8);
            this.path.setGoal(wander);
            if (this.path.tick()) {
                setState(AiState.MOVING);
                return true;
            }
        }
        return false;
    }

    /** 玩家把东西丢给 AI 时道谢（同一玩家 30 秒内只谢一次，避免刷屏） */
    private void thankIfGiven(ItemEntity drop) {
        Entity owner = drop.getOwner();
        if (!(owner instanceof Player giver) || giver == this.player) {
            return;
        }
        long now = System.currentTimeMillis();
        if (giver.getUUID().equals(this.thankedUuid) && now - this.thankedAt < THANK_COOLDOWN_MS) {
            return;
        }
        if (this.chat.sayReply("谢谢 " + giver.getScoreboardName() + "！")) {
            this.thankedUuid = giver.getUUID();
            this.thankedAt = now;
        }
    }

    /** 主人是谁（档案里没配就用内置默认主人 The_bodboy_） */
    public String ownerName() {
        AiProfile p = this.profile;
        if (p != null && p.ownerName != null && !p.ownerName.isBlank()) {
            return p.ownerName.trim();
        }
        return DEFAULT_OWNER;
    }

    /** 这个实体是不是主人（AI 要听主人的话、绝不攻击主人） */
    public boolean isOwner(Entity entity) {
        return entity instanceof Player p && p.getScoreboardName().equalsIgnoreCase(ownerName());
    }

    /**
     * 允许攻击吗？玩家只有在"先动手打过 AI"（已成为 {@code attackTarget}）时才允许还击，
     * 主人永远不行；怪物 / 动物不受限制。
     * <p>
     * LLM 有时会自作主张挑人打架，这里兜一道底，从机制上保证不主动 PvP。
     */
    private boolean allowedToAttack(Entity target) {
        if (target instanceof Player p) {
            return target == this.attackTarget && !isOwner(p);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 工具：查种子 / 找结构（村庄、要塞…）
    // ------------------------------------------------------------------

    /** 查世界种子（等价于 /seed，但 AI 读不到指令回显，所以直接读游戏数据） */
    private void toolSeed() {
        long seed = this.player.serverLevel().getSeed();
        this.lastToolResult = "世界种子 = " + seed;
        this.chat.sayReply("这个世界的种子是 " + seed);
    }

    /**
     * 查最近的结构（村庄 / 要塞 / 沉船…），把坐标记进 {@link #lastToolResult} 喂给模型，
     * 模型下一轮就能用 goto 过去。
     */
    private void toolLocate(String kind) {
        TagKey<Structure> tag = structureTag(kind);
        if (tag == null) {
            this.lastToolResult = "不认识的结构类型：" + kind;
            return;
        }
        BlockPos origin = this.player.blockPosition();
        BlockPos found = this.player.serverLevel().findNearestMapStructure(tag, origin, 100, false);
        if (found == null) {
            this.lastToolResult = "附近 100 区块内没找到 " + kind;
            this.chat.sayReply("附近好像没有" + structureName(kind));
            return;
        }
        int dist = (int) Math.sqrt(this.player.distanceToSqr(
                found.getX() + 0.5D, found.getY() + 0.5D, found.getZ() + 0.5D));
        this.lastToolResult = structureName(kind) + " 在 x=" + found.getX() + ", z=" + found.getZ()
                + "（只给水平坐标：原版 locate 的 Y 是无效的 ~，千万别填 0，否则 AI 会跑到 0 层）"
                + "，直线约 " + dist + " 格";
        this.chat.sayReply("最近的" + structureName(kind) + "在 " + found.getX() + ", " + found.getZ()
                + " 附近，约 " + dist + " 格");
    }

    private static TagKey<Structure> structureTag(String kind) {
        return switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT).trim()) {
            case "village", "村庄" -> StructureTags.VILLAGE;
            case "stronghold", "要塞" -> StructureTags.EYE_OF_ENDER_LOCATED;
            case "ruined_portal", "废弃传送门" -> StructureTags.RUINED_PORTAL;
            case "shipwreck", "沉船" -> StructureTags.SHIPWRECK;
            case "ocean_ruin", "海底废墟" -> StructureTags.OCEAN_RUIN;
            case "mineshaft", "废弃矿井" -> StructureTags.MINESHAFT;
            case "trial_chambers", "试炼密室" -> StructureTags.ON_TRIAL_CHAMBERS_MAPS;
            case "buried_treasure", "宝藏" -> StructureTags.ON_TREASURE_MAPS;
            default -> null;
        };
    }

    private static String structureName(String kind) {
        return switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT).trim()) {
            case "village" -> "村庄";
            case "stronghold" -> "要塞";
            case "ruined_portal" -> "废弃传送门";
            case "shipwreck" -> "沉船";
            case "ocean_ruin" -> "海底废墟";
            case "mineshaft" -> "废弃矿井";
            case "trial_chambers" -> "试炼密室";
            case "buried_treasure" -> "宝藏";
            default -> kind;
        };
    }

    /** 在线的那个主人玩家；不在线返回 null */
    private Player ownerPlayer() {
        MinecraftServer server = this.player.serverLevel().getServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayerByName(ownerName());
    }

    private boolean hasRawMeat() {
        for (int i = 0; i < this.player.getInventory().getContainerSize(); i++) {
            if (FoodManager.isRawMeat(this.player.getInventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    private ItemEntity nearestWantedDrop(double range) {
        AABB box = this.player.getBoundingBox().inflate(range);
        List<ItemEntity> drops = this.player.level().getEntitiesOfClass(ItemEntity.class, box);
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity e : drops) {
            if (!e.isAlive() || e.hasPickUpDelay()) {
                continue;
            }
            if (!FoodManager.isFood(e.getItem()) && !FoodManager.worthPicking(e.getItem())) {
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

    /** 附近最近的容器方块（箱子 / 木桶 / 熔炉…），没有则返回 null */
    private BlockPos nearestContainer(double range) {
        BlockPos origin = this.player.blockPosition();
        int r = (int) Math.ceil(range);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -6, -r), origin.offset(r, 6, r))) {
            if (!(this.player.level().getBlockEntity(pos) instanceof Container)) {
                continue;
            }
            double d = this.player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    private Animal nearestAnimal(double range) {
        AABB box = this.player.getBoundingBox().inflate(range);
        List<Animal> list = this.player.level().getEntitiesOfClass(Animal.class, box);
        Animal best = null;
        double bestDist = Double.MAX_VALUE;
        for (Animal a : list) {
            if (!a.isAlive() || a.isBaby()) {
                continue;
            }
            double d = this.player.distanceToSqr(a);
            if (d < bestDist) {
                bestDist = d;
                best = a;
            }
        }
        return best;
    }

    private void idleTick(AiInput in) {
        setState(AiState.IDLE);
        this.path.clear();
        // 闲下来的时候像真人一样偶尔看看四周
        this.idleTicks++;
        if (this.idleTicks % IDLE_LOOK_INTERVAL == 0) {
            this.player.lookAt(
                    this.player.getX() + (RNG.nextDouble() * 8.0D - 4.0D),
                    this.player.getEyeY() + (RNG.nextDouble() * 2.0D - 1.0D),
                    this.player.getZ() + (RNG.nextDouble() * 8.0D - 4.0D),
                    8.0F);
        }
    }

    // ------------------------------------------------------------------
    // 玩家聊天互动
    // ------------------------------------------------------------------

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
        // AI 自己 / 别的 AI 说的话不算"玩家发言"
        if (speaker instanceof AiPlayer) {
            return;
        }
        // 公屏广播格式 "<名字> 内容"：系统 / AI 播出来的，不是玩家敲的原话
        String trimmed = text.trim();
        if (trimmed.startsWith("<") && trimmed.contains("> ")) {
            return;
        }
        // === 本地指令（不依赖 chat 开关、不受概率闸门影响，省 token） ===
        if (tryLocalCommand(text, speaker)) {
            return;
        }
        // === 下面走 LLM 通道 ===
        AiProfile p = this.profile;
        if (p == null || !p.chat) {
            return;   // GUI 里关了"聊天"：不响应
        }
        if (Math.sqrt(this.player.distanceToSqr(speaker)) > CHAT_HEAR_RANGE) {
            return;   // 太远，当没听见
        }
        boolean named = isAddressed(text) || isBuildRequest(text);
        long now = System.currentTimeMillis();
        if (named) {
            if (now - this.lastPlayerChatAt < CHAT_NAMED_COOLDOWN_MS) {
                return;
            }
        } else if (!p.autoChat || now - this.lastPlayerChatAt < CHAT_REACT_COOLDOWN_MS
                || RNG.nextDouble() > CHAT_REACT_CHANCE) {
            return;
        }
        this.lastPlayerChatAt = now;
        this.pendingSpeaker = speaker.getName().getString();
        this.pendingChatText = text.length() > CHAT_TEXT_MAX ? text.substring(0, CHAT_TEXT_MAX) : text;
        if (this.llmFailures == 0) {
            this.lastLlmAt = 0L;
        }
        if (isBuildRequest(text)) {
            queue(this::startSimpleBuild);
            return;
        }
    }



















































    /**
     * 这句话是不是"让 AI 建东西"（建房 / 搭墙 / 围个圈 / build a wall…）。
     * <p>
     * 只认最明确的说法，避免把"搭个桥"之类交给这条本地兜底（桥要走 bridge 字段，不是围墙）。
     */
    private static boolean isBuildRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : BUILD_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本地指令兜底 —— LLM API 失败 / 限流 / 额度耗尽时也能响应玩家。
     * 返回 true 表示已经本地处理，onPlayerChat 里直接 return 不走 LLM。
     * 只处理高频指令：停下来 / 跟着我 / 过来 / 打。
     */
    private boolean tryLocalCommand(String text, Player speaker) {
        String lower = text.toLowerCase(Locale.ROOT);

        // 停下来 / stop
        for (String word : new String[]{"停下来", "停下", "stop", "别打了", "别打", "别动", "wait"}) {
            if (lower.contains(word)) {
                queue(this::stop);
                return true;
            }
        }

        // 跟着我 / follow me
        for (String word : new String[]{"跟着我", "跟我走", "follow me", "follow", "跟着我走"}) {
            if (lower.contains(word)) {
                String name = speaker == null ? null : speaker.getName().getString();
                queue(() -> setFollow(name));
                return true;
            }
        }

        // 过来 / come here —— 走到说话者身边
        for (String word : new String[]{"过来", "来这里", "come here", "come", "到我这", "过来一下"}) {
            if (lower.contains(word)) {
                if (speaker != null) {
                    queue(() -> walkTo(speaker.blockPosition()));
                }
                return true;
            }
        }

        // 攻击 / 打 —— 打最近的敌人
        for (String word : new String[]{"打那个", "去打", "攻击那个", "attack that", "kill that", "打怪物"}) {
            if (lower.contains(word)) {
                queue(() -> {
                    Entity enemy = findEnemy(16.0D);
                    if (enemy != null) {
                        this.attackTarget = enemy;
                        this.combat.setTarget(enemy);
                    }
                });
                return true;
            }
        }

        // PvP 开关：主人说"允许 PvP"就开，"关掉 PvP"就关
        if (lower.contains("允许 pvp") || lower.contains("可以 pvp") || lower.contains("开 pvp") || lower.contains("turn pvp on")) {
            queue(() -> { this.allowPlayerPvp = true; });
            return true;
        }
        if (lower.contains("关掉 pvp") || lower.contains("停止 pvp") || lower.contains("关 pvp") || lower.contains("turn pvp off")) {
            queue(() -> {
                this.allowPlayerPvp = false;
                if (this.attackTarget instanceof Player) {
                    this.attackTarget = null;
                }
            });
            return true;
        }

        return false;
    }

    /**
     * 模型忙 / 退避中 / 没配 Key 时不干等：把还没送进模型的玩家消息用本地规则消化掉。
     * {@code dropInflight} 表示还有一个请求在路上——那种情况下这轮就当本地处理完，
     * 迟到的模型回复作废（llmStale），免得本地动作和模型动作前后打架。
     */
    private void consumePendingLocally(boolean dropInflight) {
        String speaker = this.pendingSpeaker;
        String heard = this.pendingChatText;
        this.pendingSpeaker = null;
        this.pendingChatText = null;
        if (dropInflight) {
            this.llmStale = true;
        }
        if (speaker == null || heard == null) {
            return;
        }
        final String sp = speaker;
        final String hd = heard;
        queue(() -> {
            Player pl = findByName(sp) instanceof Player p ? p : null;
            if (!tryLocalCommand(hd, pl)) {
                localIntent(hd, pl);
            }
        });
    }

    /** 本地意图兜底：tryLocalCommand 没认出的高频说法，用固定动作直接响应 */
    private void localIntent(String heard, Player speaker) {
        String lower = heard.toLowerCase(Locale.ROOT);

        // 给我东西：优先丢食物，其次丢多于一个的非武器物品
        if (lower.contains("给我") || lower.contains("给点") || lower.contains("丢给")
                || lower.contains("扔给") || lower.contains("give me")) {
            Inventory inv = this.player.getInventory();
            ItemStack best = null;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty()) {
                    continue;
                }
                if (FoodManager.isFood(s)) {
                    best = s;
                    break;
                }
                if (best == null && s.getCount() >= 2 && !isWeapon(s)) {
                    best = s;
                }
            }
            if (best != null) {
                dropToward(speaker != null ? speaker : this.player, best);
                this.chat.sayReply("拿好，别客气～");
            } else {
                this.chat.sayReply("我背包里也没有多余的，先自己去弄点吧");
            }
            return;
        }

        // 去挖矿 / 去砍树 / 出去转转：交给采集与探索控制器（矿脉、整棵树、出门记路）
        if (lower.contains("挖矿道") || lower.contains("矿道") || lower.contains("挖条道")) {
            if (!this.profile.mine) {
                this.chat.sayReply("我这边把挖矿关了，先开一下再说");
                return;
            }
            this.mining.startBranch();
            this.chat.sayReply("收到，这就开矿道找矿去！");
            return;
        }
        if (lower.contains("去挖") || lower.contains("挖矿") || lower.contains("挖点")) {
            if (!this.profile.mine) {
                this.chat.sayReply("我这边把挖矿关了，先开一下再说");
                return;
            }
            this.mining.startOre();
            this.chat.sayReply("收到，这就去挖矿！");
            return;
        }
        if (lower.contains("去砍") || lower.contains("砍树") || lower.contains("木头") || lower.contains("木材")) {
            if (!this.profile.mine) {
                this.chat.sayReply("我这边把挖矿砍树关了，先开一下再说");
                return;
            }
            this.chop.start();
            this.chat.sayReply("收到，这就去砍树！");
            return;
        }
        if (lower.contains("去探索") || lower.contains("探索") || lower.contains("出去转转")
                || lower.contains("出去看看") || lower.contains("周围转转")) {
            if (!this.profile.explore) {
                this.chat.sayReply("我这边把探索关了，先开一下再说");
                return;
            }
            this.explore.start();
            this.chat.sayReply("收到，我出去转转，记得住路，一会儿就回来");
            return;
        }
    }

    /**
     * 本地兜底建造：在自己身前搭一个 5x5、两格高的围墙（最多 64 块）。
     * <p>
     * 这是"玩家让建房子，AI 必须真的动手"的保底实现 —— 不依赖任何 LLM 输出。
     */
    private void startSimpleBuild() {
        if (!this.profile.place) {
            return;   // GUI 里关了“放置方块”
        }
        if (this.placeTarget != null || !this.buildQueue.isEmpty()) {
            return;   // 已经在建了，别重复开工
        }
        Level level = this.player.level();
        BlockPos base = this.player.blockPosition();
        int cx = base.getX() + 4;         // 房子中心挪到身前 4 格
        int cz = base.getZ() + 4;
        if (!level.hasChunkAt(new BlockPos(cx, base.getY(), cz))) {
            this.chat.sayReply("附近区块还没加载，等一下再建");
            return;
        }
        int gy = surfaceY(cx, cz);
        String wall = this.profile.bridgeBlock == null || this.profile.bridgeBlock.isBlank()
                ? "oak_planks" : this.profile.bridgeBlock;
        int r = 3;                        // 7x7 的房子
        int x1 = cx - r, z1 = cz - r, x2 = cx + r, z2 = cz + r;
        // 用 /fill 一条条砌：地板 → 实心墙 → 挖空内部 → 门口 → 屋顶
        // （fill 有提权走 forceCommand，一秒成型，比一格一格摆快得多）
        List<String> cmds = List.of(
                "fill " + x1 + " " + gy + " " + z1 + " " + x2 + " " + gy + " " + z2 + " " + wall,
                "fill " + x1 + " " + (gy + 1) + " " + z1 + " " + x2 + " " + (gy + 3) + " " + z2 + " " + wall,
                "fill " + (x1 + 1) + " " + (gy + 1) + " " + (z1 + 1) + " " + (x2 - 1) + " " + (gy + 3) + " " + (z2 - 1) + " air",
                "fill " + cx + " " + (gy + 1) + " " + z1 + " " + cx + " " + (gy + 2) + " " + z1 + " air",
                "fill " + x1 + " " + (gy + 4) + " " + z1 + " " + x2 + " " + (gy + 4) + " " + z2 + " " + wall);
        for (String c : cmds) {
            this.chat.forceCommand(c);
        }
        this.chat.sayReply("房子建好了，" + (r * 2 + 1) + "x" + (r * 2 + 1) + " 的，进去看看！");
    }

    /** 弓箭能不能真射到：从 AI 眼部对目标眼睛和躯干各打一条实心射线，两条全通才放箭。
     *  hasLineOfSight 只查中心线，半格/台阶/树叶下面能"看见"但箭就是撞方块 —— 表现为一直射不到。 */
    private boolean canShootLine(net.minecraft.world.entity.Entity enemy) {
        net.minecraft.world.phys.Vec3 from = this.player.getEyePosition();
        net.minecraft.world.phys.Vec3 chest = enemy.position().add(0, enemy.getBbHeight() * 0.5D, 0);
        net.minecraft.world.phys.Vec3[] points = { enemy.getEyePosition(), chest };
        for (net.minecraft.world.phys.Vec3 to : points) {
            net.minecraft.world.phys.BlockHitResult hit = this.player.level().clip(
                    new net.minecraft.world.level.ClipContext(from, to,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, this.player));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                return false;
            }
        }
        return true;
    }

    /** 这句话是不是冲着这名 AI 说的（点名 / @名字 / 直接以 ai 开头 / 明显在使唤它） */
    private boolean isAddressed(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String name = this.player.getScoreboardName().toLowerCase(Locale.ROOT);
        if (lower.contains(name) || lower.contains("@" + name) || lower.startsWith("ai")) {
            return true;
        }
        for (String keyword : REQUEST_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 主动和走近的玩家打招呼。纯本地文本、不花任何 token，但玩家能明显感觉到“它在理我”。
     * 战斗 / 搭路 / 打水晶中不打扰，同一个玩家 4 分钟内只招呼一次。
     */
    private void greetNearby() {
        AiProfile p = this.profile;
        if (p == null || !p.chat || !p.autoChat) {
            return;
        }
        if (this.state == AiState.COMBAT || this.state == AiState.CRYSTAL || this.bridge.isActive()) {
            return;
        }
        if (this.player.tickCount % 20 != 0) {
            return;   // 每秒查一次就够
        }
        long now = System.currentTimeMillis();
        for (Player other : this.player.level().getEntitiesOfClass(Player.class,
                this.player.getBoundingBox().inflate(GREET_RANGE))) {
            if (other == this.player || other.isSpectator()) {
                continue;
            }
            // 只在第一次见面时打招呼：见过的名字记进 profile（持久化），重启也还认得
            String otherName = other.getName().getString();
            if (p.metPlayers.contains(otherName)) {
                continue;
            }
            Long last = this.greetedAt.get(other.getUUID());
            if (last != null && now - last < GREET_COOLDOWN_MS) {
                continue;
            }
            if (this.greetedAt.size() > 64) {
                // 只是招呼记录，涨到上限直接清空，避免无上限增长
                this.greetedAt.clear();
            }
            // 只有真的说出口才算招呼过，否则会被 1 秒间隔静默吃掉、玩家再也等不到招呼
            if (this.chat.sayReply("你好，" + otherName + "！初次见面！")) {
                this.greetedAt.put(other.getUUID(), now);
                p.metPlayers.add(otherName);
                com.aiplayer.config.AiPlayerConfig.get().save();
            }
            return;   // 一次只招呼一个，免得一群人围上来时刷屏
        }
    }

    // ------------------------------------------------------------------
    // LLM
    // ------------------------------------------------------------------

    private void maybeAskLlm() {
        AiPlayerConfig config = AiPlayerConfig.get();
        if (!config.llmEnabled) {
            return;
        }
        boolean noKey = !config.hasApiKey() && (this.profile.apiKey == null || this.profile.apiKey.isBlank());
        long now = System.currentTimeMillis();
        // 间隔下限兜底：手改配置写成 0 也不会把账户的 RPM 打爆（HTTP 429）
        long interval = Math.max(8000L, config.llmIntervalMs);
        // 失败之后要额外等一会儿（退避），避免配置错了还每轮都打请求
        boolean waiting = this.llmBusy || noKey
                || now - this.lastLlmAt < interval + this.llmBackoffMs;
        if (waiting) {
            // 模型忙 / 退避中 / 没配 Key：玩家在等回复就先走本地规则，不让 AI 干等发呆
            consumePendingLocally(this.llmBusy);
            return;
        }
        this.lastLlmAt = now;
        this.llmBusy = true;
        // 取走待回复的玩家消息（取走即清空，绝不重复带入下一轮，省 token）
        String speaker = this.pendingSpeaker;
        String heard = this.pendingChatText;
        this.pendingSpeaker = null;
        this.pendingChatText = null;
        final boolean replying = speaker != null && heard != null;
        LlmClient.ask(this.profile, systemPrompt(), statePrompt(speaker, heard)).whenComplete((reply, error) -> {
            this.llmBusy = false;
            boolean stale = this.llmStale;
            this.llmStale = false;
            if (error != null) {
                onLlmFailure(error);
                return;
            }
            this.llmFailures = 0;
            this.llmBackoffMs = 0L;
            if (stale) {
                return;   // 这轮已经本地处理过了，丢掉迟到的模型动作
            }
            if (reply != null && !reply.isBlank()) {
                queue(() -> applyLlmReply(reply, replying));
            }
        });
    }

    /** LLM 调用失败：分类提示（tellraw @a）+ 指数退避 */
    private void onLlmFailure(Throwable raw) {
        Throwable error = raw;
        if (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        this.llmFailures++;
        this.llmBackoffMs = Math.min(60_000L, 3_000L * this.llmFailures);

        String name = this.profile.name;
        if (error instanceof LlmClient.LlmException le) {
            String hint;
            if (le.unauthorized()) {
                hint = "API Key 无效或没有权限（HTTP " + le.status + "），请在 GUI 里重新填 Key";
            } else if (le.notFound()) {
                hint = "接口地址不对（HTTP 404），检查 apiBaseUrl 要不要带 /v1、模型名是否存在";
            } else if (le.rateLimited()) {
                hint = "请求被限流或余额不足（HTTP 429）";
            } else if (le.status >= 500) {
                hint = "API 服务端错误（HTTP " + le.status + "），稍后自动重试";
            } else {
                hint = "API 调用被拒绝（HTTP " + le.status + "）";
            }
            // 401/404 属于“配置没改就一直错”，只提示第一次，避免刷屏
            boolean configError = le.unauthorized() || le.notFound();
            if (configError ? this.llmFailures == 1 : this.llmFailures <= 3) {
                String text = "[aiplayer] " + name + " " + hint;
                // 回调在 HTTP 线程上，执行指令要回到服务器主线程
                queue(() -> this.chat.reportError(text));
            }
            return;
        }
        if (this.llmFailures <= 3) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            String text = "[aiplayer] " + name + " 调用 AI 接口失败：" + message;
            queue(() -> this.chat.reportError(text));
        }
    }

    // ------------------------------------------------------------------
    // 物品操控（LLM 用物品 ID 驱动，见 {@link ItemIdUtil}）
    // ------------------------------------------------------------------

    /** 持续按住使用物品：吃东西 / 喝药要按满右键时间才生效，点一次会被立刻中断 */
    private void applyItemHold(AiInput in) {
        if (this.useHoldTicks <= 0 || this.useHoldSlot < 0) {
            return;
        }
        // 上一 tick 已经按过一次，但物品并没有进入"使用中"状态 —— 说明它是即用即生效的
        // （珍珠 / 雪球 / 水桶 / 放方块）。必须立刻松手：再按下去会每 tick 用一次，
        // 把一叠珍珠扔光、一口气放一整片方块。
        if (this.useHoldApplied > 0 && !this.player.isUsingItem()) {
            stopItemHold();
            return;
        }
        // 槽位被更高优先级（战斗 / 逃生）抢走时立刻放弃，不要跟原版逻辑抢
        if (!this.player.selectSlot("llm", com.aiplayer.util.InventoryLock.PRIO_TASK, this.useHoldSlot)) {
            stopItemHold();
            return;
        }
        in.selectedSlot = this.useHoldSlot;
        in.useItemHold = true;
        this.useHoldTicks--;
        this.useHoldApplied++;
        if (this.useHoldTicks <= 0) {
            // 兜底：按住太久（例如蓄力型物品）强制松手，避免一直保持开弓状态
            stopItemHold();
        }
    }

    private void stopItemHold() {
        this.useHoldTicks = 0;
        this.useHoldSlot = -1;
        this.useHoldApplied = 0;
        // 弓 / 三叉戟这类"蓄力型"物品要显式松开，否则会一直僵在开弓状态
        if (this.player.isUsingItem()) {
            this.player.releaseUsingItemNow();
        }
    }

    /**
     * 把物品挪到手上（不在快捷栏就和当前槽位互换），返回所在槽位；没有则 -1。
     * <p>
     * <b>调用前必须已经持有 {@code InventoryLock}</b>：这里的"互换"换掉的是
     * {@code getSelectedSlot()}，也就是别的控制器（搭路 / 战斗 / 水晶 PvP）可能正在使用的槽位。
     * 不持锁就换，会把它们手上的方块 / 武器 / 水桶换进背包，表现为搭路突然中断、
     * 攻击打空、水晶放不出来。
     */
    private int moveToHand(Item item) {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            if (inv.getItem(i).is(item)) {
                return i;
            }
        }
        if (!this.player.inventoryLock().heldBy("llm")
                && !this.player.inventoryLock().acquire("llm", com.aiplayer.util.InventoryLock.PRIO_TASK)) {
            // 锁被更高优先级（搭路 / 战斗 / 进食）占着：这次放弃，绝不抢槽位。
            // 锁空闲时自己拿一下 —— 这样背包里的物品也能被取出来用，不必依赖调用方先加锁。
            return -1;
        }
        for (int i = Inventory.SELECTION_SIZE; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) {
                int target = inv.getSelectedSlot();
                ItemStack old = inv.getItem(target);
                inv.setItem(target, inv.getItem(i));
                inv.setItem(i, old);
                return target;
            }
        }
        return -1;
    }

    /** LLM "use"：使用一次（吃 / 喝 / 投掷 / 放置） */
    private boolean useItem(String spec) {
        Item item = ItemIdUtil.resolve(spec);
        if (item == null) {
            return false;
        }
        // 先拿锁再动背包：拿不到说明别的控制器（搭路 / 战斗 / 逃生）正在用这个槽位，
        // 这次"使用物品"直接放弃，绝不抢槽位。
        if (!this.player.inventoryLock().acquire("llm", com.aiplayer.util.InventoryLock.PRIO_TASK)) {
            return false;
        }
        int slot = moveToHand(item);
        if (slot < 0) {
            return false;
        }
        this.useHoldSlot = slot;
        this.useHoldTicks = USE_HOLD_MAX_TICKS;
        this.useHoldApplied = 0;
        return true;
    }

    /** LLM "equip"：拿到主手上（护甲由 EquipmentManager 自动穿，不需要在这里处理） */
    private boolean holdItem(String spec) {
        Item item = ItemIdUtil.resolve(spec);
        if (item == null) {
            return false;
        }
        if (!this.player.inventoryLock().acquire("llm", com.aiplayer.util.InventoryLock.PRIO_TASK)) {
            return false;
        }
        int slot = moveToHand(item);
        return slot >= 0
                && this.player.selectSlot("llm", com.aiplayer.util.InventoryLock.PRIO_TASK, slot);
    }

    /** LLM "drop"：丢弃物品，count <= 0 表示整叠扔掉 */
    private boolean dropItem(String spec, int count) {
        Item item = ItemIdUtil.resolve(spec);
        if (item == null) {
            return false;
        }
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(item)) {
                continue;
            }
            int n = count <= 0 ? stack.getCount() : Math.min(count, stack.getCount());
            ItemStack dropped = stack.copyWithCount(n);
            stack.shrink(n);
            if (stack.isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            this.player.drop(dropped, false);
            return true;
        }
        return false;
    }

    private String systemPrompt() {
        AiProfile p = this.profile;
        String custom = p.customPrompt == null ? "" : p.customPrompt;
        // 只把“当前开着”的功能写进提示词：关掉的字段模型不该再输出，
        // 否则它会一直尝试被忽略的动作，看起来就像“AI 不听话”。
        StringBuilder fields = new StringBuilder();
        if (p.chat) {
            fields.append("  \"say\": \"要在聊天栏说的话\",\n");
        }
        fields.append("  \"goto\": [x, y, z],\n");
        // 移动类动作：API Key 可以像“遥控器”一样直接操控 AI 走位
        fields.append("  \"move\": \"forward|back|left|right|stop（持续约 2 秒的移动）\",\n");
        fields.append("  \"jump\": true,\n");
        fields.append("  \"sneak\": true,\n");
        fields.append("  \"sprint\": true,\n");
        fields.append("  \"look\": [yaw, pitch],\n");
        if (p.mine) {
            fields.append("  \"mine\": [x, y, z],\n");
            fields.append("  \"chop\": [x, y, z],  把这块原木所在的整棵树砍掉（会自动连树干树冠一起收）,\n");
            fields.append("  \"mineVein\": true,  自己就近找矿脉挖（附近有矿才会动手）,\n");
            fields.append("  \"digTunnel\": true,  附近没矿时开一条 1x2 的矿道找矿,\n");
        }
        if (p.explore) {
            fields.append("  \"explore\": true,  自己出门探索（会记路，走远了或受伤了就回家）,\n");
        }
        if (p.place) {
            fields.append("  \"place\": [x, y, z],  在指定位置放一个方块（手上没方块会自动从快捷栏切一块过来）,\n");
            fields.append("  \"build\": [[x,y,z], [x,y,z], ...],  连续建造：按顺序放一串方块（最多 64 块），搭墙/搭简易房时用（需要有方块）,\n");
        }
        if (p.combat) {
            fields.append("  \"attack\": \"要打的怪物名字（只在自保或获取资源时用，别主动打玩家）\",\n");
        }
        fields.append("  \"follow\": \"要跟随的玩家名，空字符串取消\",\n");
        if (p.eat) {
            fields.append("  \"cook\": true,\n");
        }
        fields.append("  \"open\": [x, y, z],  打开这个坐标的箱子/容器并把里面的物资收进背包,\n");
        fields.append("  \"trade\": \"附近村民的名字\",  和村民交易（买得起就成交）,\n");
        fields.append("  \"craft\": \"想合成的物品 ID（如 oak_planks），需要旁边有工作台，会自动摆材料、扣背包\",\n");
        fields.append("  \"craftCount\": 8,  合成几份（默认 1）,\n");
        fields.append("  \"smelt\": true,  去附近的熔炉烧炼（会自动放原料和燃料、先收成品）,\n");
        fields.append("  \"brew\": true,  去附近的酿造台酿药水（会先收成品，再放玻璃瓶/原料/烈焰粉）,\n");
        fields.append("  \"seed\": true,  查询世界种子,\n");
        fields.append("  \"locate\": \"village|stronghold|ruined_portal|shipwreck|ocean_ruin|mineshaft|trial_chambers|buried_treasure\",  查找最近的结构并拿到坐标,\n");
        if (p.bridge) {
            fields.append("  \"bridge\": \"sneak|god|telly|speed_telly|off（严禁乱用：只有真的要走过去、脚下又是深坑/虚空时才开，平时一律别碰）\",\n");
        }
        fields.append("  \"use\": \"要用一次的物品 ID（吃 / 喝 / 投掷 / 放置），如 golden_apple\",\n");
        fields.append("  \"equip\": \"要拿到主手上的物品 ID，如 diamond_sword\",\n");
        fields.append("  \"drop\": \"丢弃物品 ID（想把自己背包里的东西给玩家，就用这个，配 dropCount 指定数量）\",\n");
        fields.append("  \"dropCount\": 8,\n");
        if (p.command) {
            fields.append("  \"command\": \"要真正执行的服务器指令，不带 / 也行，例：give The_bodboy_ wool 8、time set day、weather clear\",\n");
        }
        fields.append("  \"stop\": true,\n  \"reason\": \"一句话说明你的想法\"\n");
        return """
                你是一个 Minecraft 生存服里的 AI 玩家，名字叫 "%s"，当前强度等级 %d/4（1 菜鸟 2 普通 3 高手 4 挂级）。
                你的主人是玩家 "%s"：要听主人的话，绝不攻击主人；主人被攻击时优先帮主人反击。
                【首要目标】像真人一样"活下去并推进游戏"：找食物填饱肚子、挖矿、收集资源、合成、建造、
                探索地图、变强。把大部分精力放在生存与发展上，而不是打架。
                【不要 PvP】除非有人先动手打你，否则绝不要主动攻击任何玩家、也不要追着人打；
                打怪只是为了自保或获取资源。想打架的冲动要克制，优先做上面那些生存的事。
                【找资源】想知道种子或找村庄 / 要塞 / 沉船等结构时，用 seed / locate 查；
                查到坐标后下一轮用 goto 过去，别瞎逛。
                注意 locate 只给 x 和 z —— 结构的 Y 是不可信的（原版显示为 ~），
                所以 goto 的 y 请填你自己的当前高度（状态里有），绝对不要填 0，否则会掉到 0 层。
                【别老往村庄跑】村庄不是你的任务目标。只有玩家明确让你去、或你正好需要交易 / 躲怪时才靠近；
                平时绝对不要主动 locate 村庄再长途跋涉过去，优先在附近挖矿、收集资源、盖个落脚点。
                同一件工具查询结果只出现一轮，用完就该换别的事做，不要反复跑同一个坐标。
                【执行指令】需要执行服务器指令（time set day、give、weather clear、tp…）时，
                必须写进 command 字段，系统会真的执行；绝对不要把指令写进 say 当成聊天说出去
                （那样只会在聊天栏显示一行字，什么都不会发生）。
                【玩家要东西必须给】玩家向你要物品（"给我一些羊毛""来把剑"等）时，必须照做：
                优先用 command 执行 give <玩家名> <物品ID> <数量>，<玩家名> 就填状态里"玩家 X 刚说"的那个人
                （例：give The_bodboy_ wool 8）；如果你背包里正好有，也可以直接 drop 丢给他。
                绝对不能只说一句"好"却不做任何事。
                【必须服从玩家】玩家让你执行指令、给东西、去某地、做什么事，就用对应字段照做；
                只有涉及 op / ban / kick / stop 这类危险指令才拒绝。
                【玩家让你建东西】玩家说"建房子 / 盖个房 / 搭个墙 / 围个圈"这类话时，
                绝对不要反问他"要建成什么样"然后什么都不做 —— 直接动手，用 build 字段：
                拿状态里"坐标"的 x、z 当作你自己脚下的格子，y 取脚下那一层的整数高度，
                把要放的方块坐标一个个列出来填进 build（最多 64 块，按从近到远的顺序）。
                先搭最简单的：4 面墙、每面墙在自己这一层往上叠 2~3 格，围成一个 5x5 或 7x7 的圈，
                不要一上来就搭屋顶或复杂造型；墙搭好再问玩家要不要继续。
                例：站在坐标 100 64 200 附近要围 5x5，就填
                "build": [[98,64,198],[99,64,198],[100,64,198],[101,64,198],[102,64,198],
                [98,64,199],[102,64,199],[98,64,200],[102,64,200],[98,64,201],[102,64,201],
                [98,64,202],[99,64,202],[100,64,202],[101,64,202],[102,64,202]]。
                手上没方块时：先用 command 执行 give <你的名字> dirt 128（<你的名字> 用状态里的名字）补给，
                或者就近挖土 / 砍树拿方块，再开始建。绝不能只说一句"好的"却没有 build。
                你的行为必须像一个真人玩家：正常走路、正常速度、不穿墙、不瞬移、不违反服务器规则。
                【重要】只能用简体中文说话（say 字段），任何情况下都不要输出英文。
                【少说话】别每轮都说话：绝大多数轮次根本不要写 say，安静做事就行。
                只有"玩家点名问你"或"确实值得一提的大事"（比如发现村庄/要塞、被打、拿到好东西）才说一句话，
                而且一次只说一句、别碎碎念、别自言自语、别汇报你每个动作。
                物品 ID 直接写背包清单里的名字（可省略 minecraft: 前缀，如 diamond_sword、cooked_beef）。
                每轮你必须只输出一个 JSON 对象，不要输出多余文字。可用字段（全部可选）：
                {
                %s}
                只做当前局势最合理的一件事。reason 只是你自己的备注，不会发到聊天栏。
                有玩家说话时（状态里出现"玩家 X 刚说"），必须先用 say 回他一句，语气自然、口语化、一句话，
                不要复读对方的话、不要用引号把整句包起来；对方如果让你做什么，再顺便用对应字段照做。%s
                """.formatted(p.name, p.levelId, ownerName(), fields, custom);
    }

    private String statePrompt(String speaker, String heard) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前状态：").append(this.state.display).append('\n');
        sb.append("坐标：").append(fmt(this.player.getX())).append(' ')
                .append(fmt(this.player.getY())).append(' ').append(fmt(this.player.getZ())).append('\n');
        sb.append("血量：").append(fmt(this.player.getHealth())).append('/')
                .append(fmt(this.player.getMaxHealth()))
                .append("，饥饿：").append(this.player.getFoodData().getFoodLevel()).append('\n');
        sb.append("主手：").append(this.player.getMainHandItem().getDisplayName().getString())
                .append("，副手：").append(this.player.getOffhandItem().getDisplayName().getString()).append('\n');
        sb.append("是否在地面：").append(this.player.onGround())
                .append("，是否在水中：").append(this.player.isInWater()).append('\n');
        Entity enemy = this.combat.target();
        if (enemy != null) {
            sb.append("正在交战：").append(enemy.getName().getString())
                    .append("，距离 ").append(fmt(Math.sqrt(this.player.distanceToSqr(enemy)))).append('\n');
        }
        List<Player> nearby = this.player.level().getEntitiesOfClass(Player.class, this.player.getBoundingBox().inflate(24.0D));
        if (!nearby.isEmpty()) {
            sb.append("附近玩家：");
            for (Player p : nearby) {
                if (p == this.player) {
                    continue;
                }
                sb.append(p.getName().getString()).append('(').append(fmt(Math.sqrt(this.player.distanceToSqr(p)))).append("格) ");
            }
            sb.append('\n');
        }
        sb.append("目标：").append(this.target() == null ? "无" : this.target().toShortString()).append('\n');
        // 玩家的最新发言：放在最后、字数有限，模型不用理解上下文就能接话（省 token）
        if (speaker != null && heard != null) {
            sb.append("玩家 ").append(speaker).append(" 刚说：\"").append(heard).append("\"\n")
                    .append("请先用 say 回他一句（口语化、一句话，不要用引号包起来），再决定要不要顺带做别的动作。\n")
                    .append("如果这句话是在让你做事（建/挖/给东西/去某地/执行指令），")
                    .append("这一轮必须同时带上对应的动作字段真正去做，绝不能只 say 一句就结束。\n");
        }
        // 把背包里的物品 ID 列给模型，它才能“认出”自己手头有什么，而不是瞎猜物品名
        String bag = ItemIdUtil.describe(this.player, 600);
        if (!bag.isBlank()) {
            sb.append("背包物品（item 相关字段直接用这些 ID）：").append(bag).append('\n');
        }
        // 附近的箱子 / 工位 / 村民：模型才知道有没有东西可翻、能不能合成、有没有村民可交易
        String spots = nearbyInteractables();
        if (!spots.isBlank()) {
            sb.append("附近可交互（open 用坐标、craft 用物品 ID、smelt/brew 用坐标、trade 用名字）：")
                    .append(spots).append('\n');
        }
        // 上一轮工具查询结果：模型要知道村庄/要塞坐标才能 goto 过去
        if (!this.lastToolResult.isBlank()) {
            sb.append("工具查询结果（可据此 goto，或据此回答玩家）：").append(this.lastToolResult).append('\n');
            // 只喂一次：否则这条旧坐标会在每一轮提示词里反复出现，
            // 模型就会把"去那个坐标"当成常驻任务，一直往村庄之类的方向跑。
            this.lastToolResult = "";
        }
        return sb.toString();
    }

    /** 附近可交互的东西：容器坐标 + 工位（工作台/熔炉/酿造台）+ 村民名字（各限制几条，省 token） */
    private String nearbyInteractables() {
        StringBuilder sb = new StringBuilder();
        int containers = 0;
        int stations = 0;
        BlockPos origin = this.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, -6, -12), origin.offset(12, 6, 12))) {
            if (this.player.level().getBlockEntity(pos) instanceof Container) {
                sb.append(pos.toShortString()).append(' ');
                containers++;
            }
            if (stations < 3) {
                String label = stationLabel(pos);
                if (label != null) {
                    sb.append(label).append('(').append(pos.toShortString()).append(") ");
                    stations++;
                }
            }
            if (containers >= 4 && stations >= 3) {
                break;
            }
        }
        int villagers = 0;
        List<Villager> list = this.player.level().getEntitiesOfClass(Villager.class,
                this.player.getBoundingBox().inflate(16.0D));
        for (Villager v : list) {
            if (!v.isAlive()) {
                continue;
            }
            sb.append("村民[").append(v.getName().getString()).append(']');
            if (++villagers >= 3) {
                break;
            }
        }
        return sb.toString().trim();
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    /** 解析 LLM 的 JSON 动作并执行。{@code replyingToPlayer} 表示这一轮是在回玩家的话 */
    private void applyLlmReply(String reply, boolean replyingToPlayer) {
        try {
            String json = reply.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('{');
                int end = json.lastIndexOf('}');
                if (start < 0 || end <= start) {
                    return;
                }
                json = json.substring(start, end + 1);
            }
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject obj = root.getAsJsonObject();
            boolean did = false;

            if (obj.has("bridge") && this.profile.bridge) {
                String mode = obj.get("bridge").getAsString();
                if ("off".equalsIgnoreCase(mode)) {
                    this.bridge.stop();
                } else {
                    BridgeMode byName = switch (mode.toLowerCase()) {
                        case "god", "godbridge" -> BridgeMode.GOD;
                        case "telly" -> BridgeMode.TELLY;
                        case "speed_telly", "speedtelly", "telly_speed" -> BridgeMode.SPEED_TELLY;
                        case "sneak", "eagle" -> BridgeMode.SNEAK;
                        default -> null;
                    };
                    if (byName != null) {
                        AiInput in = this.player.input();
                        this.bridge.start(byName == BridgeMode.SNEAK ? BridgeController.yawToDirection(in.yaw) : null);
                    }
                }
                did = true;
            }
            if (obj.has("stop") && obj.get("stop").getAsBoolean()) {
                stop();
                did = true;
            }
            if (obj.has("follow")) {
                setFollow(obj.get("follow").getAsString());
                did = true;
            }
            if (obj.has("cook") && obj.get("cook").getAsBoolean()) {
                cook();
                did = true;
            }
            if (obj.has("goto")) {
                BlockPos pos = readPos(obj.get("goto"));
                if (pos != null) {
                    walkTo(pos);
                    did = true;
                }
            }
            if (obj.has("mine") && this.profile.mine) {
                BlockPos pos = readPos(obj.get("mine"));
                if (pos != null) {
                    mineAt(pos);
                    did = true;
                }
            }
            if (obj.has("chop") && this.profile.mine) {
                BlockPos pos = readPos(obj.get("chop"));
                if (pos != null) {
                    // 给的是原木坐标：让它自己把整棵树连冠一起收
                    this.chop.chopAt(pos);
                    did = true;
                }
            }
            if (obj.has("mineVein") && this.profile.mine && obj.get("mineVein").getAsBoolean()) {
                this.mining.startOre();
                did = true;
            }
            if (obj.has("digTunnel") && this.profile.mine && obj.get("digTunnel").getAsBoolean()) {
                this.mining.startBranch();
                did = true;
            }
            if (obj.has("explore") && this.profile.explore && obj.get("explore").getAsBoolean()) {
                this.explore.start();
                did = true;
            }
            if (obj.has("place") && this.profile.place) {
                BlockPos pos = readPos(obj.get("place"));
                if (pos != null) {
                    placeAt(pos);
                    did = true;
                }
            }
            if (obj.has("build") && this.profile.place && obj.get("build").isJsonArray()) {
                // 连续建造：一次给一串坐标（搭墙 / 小屋），AI 会逐块走过去放
                List<BlockPos> plan = new ArrayList<>();
                for (JsonElement el : obj.getAsJsonArray("build")) {
                    BlockPos pos = readPos(el);
                    if (pos != null) {
                        plan.add(pos);
                        if (plan.size() >= 64) {
                            break; // 上限 64 块，别让一轮回复塞进几千个坐标
                        }
                    }
                }
                if (!plan.isEmpty() && this.placeTarget == null && this.buildQueue.isEmpty()) {
                    // 正在建就别被模型的新方案顶掉：本地兜底已经开工时，模型的坐标常常是错的，
                    // 覆盖过去反而会让 AI 站着不动。
                    if (plan.size() >= 12) {
                        // 坐标一大片 = 模型想盖房子。逐块放又慢坐标又容易歪，
                        // 直接取包围盒用 /fill 实心一灌再挖空，一秒成型。
                        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                        for (BlockPos p : plan) {
                            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
                            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
                        }
                        String wall = this.profile.bridgeBlock == null || this.profile.bridgeBlock.isBlank()
                                ? "oak_planks" : this.profile.bridgeBlock;
                        this.chat.forceCommand("fill " + minX + " " + minY + " " + minZ + " "
                                + maxX + " " + maxY + " " + maxZ + " " + wall);
                        if (maxX - minX >= 2 && maxZ - minZ >= 2 && maxY > minY) {
                            this.chat.forceCommand("fill " + (minX + 1) + " " + (minY + 1) + " " + (minZ + 1)
                                    + " " + (maxX - 1) + " " + maxY + " " + (maxZ - 1) + " air");
                        }
                        did = true;
                    } else {
                        buildAt(plan);
                        did = true;
                    }
                }
            }
            if (obj.has("attack") && this.profile.combat) {
                Entity target = findByName(obj.get("attack").getAsString());
                if (target != null && allowedToAttack(target)) {
                    attack(target);
                    did = true;
                }
            }
            if (obj.has("open")) {
                BlockPos pos = readPos(obj.get("open"));
                if (pos != null) {
                    openContainer(pos);
                    did = true;
                }
            }
            if (obj.has("trade")) {
                Entity target = findByName(obj.get("trade").getAsString());
                if (target instanceof Villager) {
                    tradeWith(target);
                    did = true;
                }
            }
            if (obj.has("craft")) {
                Item want = ItemIdUtil.resolve(obj.get("craft").getAsString());
                BlockPos table = want == null ? null : nearestStation(16, "CraftingTable");
                if (table != null) {
                    int times = obj.has("craftCount") ? Math.max(1, Math.min(8, obj.get("craftCount").getAsInt())) : 1;
                    openCraft(table, want, times);
                    did = true;
                }
            }
            if (obj.has("smelt")
                    && (obj.get("smelt").isJsonArray() || obj.get("smelt").getAsBoolean())) {
                BlockPos furnace = obj.get("smelt").isJsonArray()
                        ? readPos(obj.get("smelt"))
                        : nearestStation(16, "Furnace", "Smoker");
                if (furnace != null) {
                    openSmelt(furnace);
                    did = true;
                }
            }
            if (obj.has("brew")
                    && (obj.get("brew").isJsonArray() || obj.get("brew").getAsBoolean())) {
                BlockPos stand = obj.get("brew").isJsonArray()
                        ? readPos(obj.get("brew"))
                        : nearestStation(16, "BrewingStand");
                if (stand != null) {
                    openBrew(stand);
                    did = true;
                }
            }
            if (obj.has("seed") && obj.get("seed").getAsBoolean()) {
                toolSeed();
                did = true;
            }
            if (obj.has("locate")) {
                toolLocate(obj.get("locate").getAsString());
                did = true;
            }
            if (obj.has("say") && this.profile.chat) {
                String line = obj.get("say").getAsString();
                // 兜底：模型经常把指令当聊天说出去（结果聊天栏只显示一行字、什么都没发生）。
                // 只要整句就是一条安全的指令（以 / 开头，或 aiplayer ...），就直接执行、不广播；
                // 不安全（op/ban/@a 之类）则照常当聊天发出去，免得整句凭空消失。
                if (this.profile.command && isCommandLine(line) && ChatController.isSafe(line)) {
                    runCommand(line);
                } else if (replyingToPlayer) {
                    // 回话走 sayReply：只受 1 秒最低间隔限制，不会被业务冷却（默认 3 秒）吃掉
                    this.chat.sayReply(line);
                } else {
                    say(line);
                }
                did = true;
            }
            if (obj.has("use") && useItem(obj.get("use").getAsString())) {
                did = true;
            }
            if (obj.has("equip") && holdItem(obj.get("equip").getAsString())) {
                did = true;
            }
            if (obj.has("drop")) {
                int count = obj.has("dropCount") && obj.get("dropCount").isJsonPrimitive()
                        ? obj.get("dropCount").getAsInt() : 0;
                if (dropItem(obj.get("drop").getAsString(), count)) {
                    did = true;
                }
            }
            if (obj.has("command") && this.profile.command) {
                runCommand(obj.get("command").getAsString());
                did = true;
            }
            if (obj.has("move") || obj.has("jump") || obj.has("sneak") || obj.has("sprint") || obj.has("look")) {
                applyManual(obj);
                did = true;
            }
            // 注意：reason 只是模型给自己的备注，绝不当作聊天发出去
            //（以前这里会把 reason 当 say 广播，玩家看到的就是“AI 莫名其妙乱说话”）。
        } catch (Throwable ignored) {
            // 模型输出不是合法 JSON 就忽略这一轮
        }
    }

    /**
     * 整句是不是一条指令。
     * <p>
     * 模型经常把指令塞进 say（聊天栏只显示一行字、什么都没发生），这里做兜底识别：
     * 以 / 开头，或者形如 {@code aiplayer ...} 的裸指令名，都当作指令直接执行。
     */
    private static boolean isCommandLine(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return t.startsWith("/") || t.toLowerCase(Locale.ROOT).startsWith("aiplayer ");
    }

    /** 解析 LLM 的移动类字段（move/jump/sneak/sprint/look），进入短暂的手动操控状态 */
    private void applyManual(JsonObject obj) {
        this.mForward = false;
        this.mBack = false;
        this.mLeft = false;
        this.mRight = false;
        this.mJump = false;
        this.mSneak = false;
        this.mSprint = false;
        if (obj.has("move") && obj.get("move").isJsonPrimitive()) {
            switch (obj.get("move").getAsString().toLowerCase(Locale.ROOT)) {
                case "forward", "w", "up" -> this.mForward = true;
                case "back", "backward", "s" -> this.mBack = true;
                case "left", "a" -> this.mLeft = true;
                case "right", "d" -> this.mRight = true;
                default -> {
                    // stop / 其它：保持原地
                }
            }
        }
        if (obj.has("jump")) {
            this.mJump = obj.get("jump").getAsBoolean();
        }
        if (obj.has("sneak")) {
            this.mSneak = obj.get("sneak").getAsBoolean();
        }
        if (obj.has("sprint")) {
            this.mSprint = obj.get("sprint").getAsBoolean();
        }
        if (obj.has("look") && obj.get("look").isJsonArray()) {
            var arr = obj.getAsJsonArray("look");
            if (arr.size() >= 2) {
                this.mYaw = arr.get(0).getAsFloat();
                this.mPitch = arr.get(1).getAsFloat();
            }
        }
        this.manualTicks = MANUAL_HOLD_TICKS;
    }

    /** 把手动操控状态写进本 tick 的输入 */
    private void applyManual(AiInput in) {
        in.forward = this.mForward;
        in.backward = this.mBack;
        in.left = this.mLeft;
        in.right = this.mRight;
        in.jump = this.mJump;
        in.sneak = this.mSneak;
        in.sprint = this.mSprint;
        if (this.mYaw != null) {
            in.yaw = this.mYaw;
        }
        if (this.mPitch != null) {
            in.pitch = this.mPitch;
        }
    }

    /**
     * 解析坐标。除了 [x, y, z]，还兼容 y 写成 "~"（原版 /locate 的写法）或干脆缺省的情况 ——
     * 这时用该列地表高度补上，避免模型把 ~ 当成 0、把 AI 带到 y=0 层去。
     */
    private BlockPos readPos(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        var array = element.getAsJsonArray();
        if (array.size() < 3) {
            return null;
        }
        Integer x = readInt(array.get(0));
        Integer y = readInt(array.get(1));
        Integer z = readInt(array.get(2));
        if (x == null || z == null) {
            return null;
        }
        int yy = y == null ? surfaceY(x, z) : y;
        return new BlockPos(x, yy, z);
    }

    /** 把 "~"、空串、null 一律当作“没有这个数”，返回 null */
    private static Integer readInt(JsonElement element) {
        try {
            if (element == null || element.isJsonNull()) {
                return null;
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt();
            }
            String text = element.getAsString().trim();
            if (text.isEmpty() || text.startsWith("~")) {
                return null;
            }
            return (int) Math.round(Double.parseDouble(text));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 该列的地表高度；区块没加载就用 AI 自己的高度兜底 */
    private int surfaceY(int x, int z) {
        Level level = this.player.level();
        if (!level.hasChunkAt(new BlockPos(x, this.player.getBlockY(), z))) {
            return this.player.getBlockY();
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** 走路落点：Y 无效（<=0，多半是 locate 的 ~ 被当成了 0）就换成该列地表高度 */
    private BlockPos surface(BlockPos pos) {
        if (pos.getY() > 0) {
            return pos;
        }
        return new BlockPos(pos.getX(), surfaceY(pos.getX(), pos.getZ()), pos.getZ());
    }

    /** 把背包里符合条件的物品存进容器（箱子等），返回存入数量 */
    public int depositInto(BlockPos pos, java.util.function.Predicate<ItemStack> filter) {
        return this.interact.depositInto(pos, filter);
    }

    private Entity findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (this.player.serverLevel().getServer() != null) {
            Player player = this.player.serverLevel().getServer().getPlayerList().getPlayerByName(name);
            if (player != null) {
                return player;
            }
        }
        List<LivingEntity> list = this.player.level().getEntitiesOfClass(LivingEntity.class,
                this.player.getBoundingBox().inflate(48.0D));
        for (LivingEntity e : list) {
            if (e.getName().getString().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 供指令查询
    // ------------------------------------------------------------------

    public String describe() {
        return "%s[%s] %s 位置(%s %s %s) 血量%.1f 饥饿%d".formatted(
                this.profile.name, this.profile.level().display, this.state.display,
                fmt(this.player.getX()), fmt(this.player.getY()), fmt(this.player.getZ()),
                this.player.getHealth(), this.player.getFoodData().getFoodLevel());
    }

    public BridgeMode bridgeMode() {
        return this.profile.bridge();
    }

    public int idleTicks() {
        return this.idleTicks;
    }

    public boolean isStuck() {
        return this.stuckTicks > 60;
    }

    public double yaw() {
        return Mth.wrapDegrees(this.player.getYRot());
    }

    /** 附近有没有这个模组的物品（给兼容层用） */
    public boolean holdsModItem() {
        return CompatManager.forItem(this.player.getMainHandItem()) != null;
    }

    /** 附近的怪（用于强度判断 / 状态展示） */
    public List<Mob> nearbyMobs(double range) {
        return this.player.level().getEntitiesOfClass(Mob.class, this.player.getBoundingBox().inflate(range));
    }
}
