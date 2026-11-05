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
import com.aiplayer.ai.survival.SleepController;
import com.aiplayer.api.LlmClient;
import com.aiplayer.compat.CompatManager;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.entity.AiPlayerManager;
import com.aiplayer.util.ItemIdUtil;
import com.aiplayer.util.PlaceUtil;
import com.aiplayer.zone.ZoneManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
class AiBrainCore {
    static final Random RNG = new Random();
    /** 空闲时多久环顾一次 */
    static final int IDLE_LOOK_INTERVAL = 60;
    /** LLM "use" 最多按住多少 tick（够食物 / 药水喝完，也给弓留出蓄力时间） */
    static final int USE_HOLD_MAX_TICKS = 45;
    /**
     * 真人玩家发言的回复间隔（毫秒）。
     * <p>
     * 2026-10-17：原来是"32 格以内 + 10% 概率 + 30 秒冷却"三道闸门叠加，
     * 玩家正常说一句话被 AI 理会的综合概率低到几乎为零 —— 这就是"AI 总是不理玩家"的根因。
     * 现在玩家的每句话都无条件送进 AI 的持久记忆（AI 一定"知道"玩家说了什么），
     * 回复也不再看距离和概率，只留这 1.5 秒防刷屏（@点名和主人是 0 间隔，立即回）。
     */
    static final long CHAT_PLAYER_COOLDOWN_MS = 1_500L;
    /** 接同伴（别的 AI）一句话的最短间隔 */
    static final long AI_CHAT_MIN_GAP_MS = 10_000L;
    /** 单条玩家消息最多带给模型多少字符 */
    static final int CHAT_TEXT_MAX = 200;
    /**
     * 有玩家正在等回复时，两次 LLM 请求之间的最短间隔。
     * <p>
     * 自主行动按 {@code llmIntervalMs}（默认 12 秒）打请求；但如果玩家刚说完一句话在等回音，
     * 还硬等 12 秒就成了"AI 反应迟钝 / 不理人"，所以这里压到 3 秒。
     */
    static final long PENDING_REPLY_WAIT_MS = 3_000L;
    /**
     * 没点名、但明显是在使唤 AI 的说法。命中就当成“在跟 AI 说话”，走立即响应通道。
     * <p>
     * 不这么做的话，像"给我一些羊毛"这种没点名的请求只会按 10% 概率被搭话，
     * 表现出来就是"AI 有时候不听指令"。
     */
    static final String[] REQUEST_KEYWORDS = {
            "给我", "给点", "帮我", "拿来", "过来", "跟我", "跟着我", "带我去", "带我", "送我",
            "丢给", "扔给", "换一下", "去做", "去拿", "去打", "去挖", "去砍", "去放", "去找",
            "回家", "找一下", "睡觉", "去睡", "睡一", "睡会儿", "晚安", "上床", "休息",
            // 建造 / 动作类：玩家说"建房子""搭个墙""挖个洞"也是在使唤 AI，不能当成闲聊
            "建个", "建房", "建一", "建座", "帮我建", "给我建", "盖个", "盖房", "盖一", "盖座",
            "搭个", "搭一", "搭房", "搭桥", "造个", "造一", "围起来", "围个", "修一下", "挖个", "挖一",
            "给你建", "给你搭", "建房子", "盖房子",
            // 采集 / 探索 / 生产：缺这些词时"砍树""挖矿"会被当成闲聊、按 10% 概率静默丢掉
            "砍树", "砍木头", "砍点木头", "挖矿", "挖煤", "挖铁", "挖钻", "挖钻石", "挖点什么",
            "去探索", "去钓鱼", "种地", "种田", "收庄稼", "收菜", "浇一下", "喂一下", "驯一下",
            "give me", "bring me", "come here", "follow me", "help me", "build me",
            "chop tree", "cut tree", "cut some wood", "go mining", "mine some", "dig some",
            "go explore", "go fishing", "fish for", "farm", "plant some", "harvest", "tame"
    };
    /**
     * 明确“让 AI 建东西”的说法：命中就走本地兜底建造，不等模型回话。
     * <p>
     * 故意不包含“搭桥 / 桥”——桥要走 bridge 字段，用围墙的兜底逻辑会建错东西。
     */
    static final String[] BUILD_WORDS = {
            "建个", "建房", "建屋", "建一", "建座", "建房子", "建屋子", "帮我建", "给我建", "给你建",
            "盖个", "盖房", "盖屋", "盖一", "盖座", "盖房子", "盖屋子",
            "搭个", "搭一", "搭房", "搭屋", "搭间",
            "造个", "造一", "造房", "造屋", "围起来", "围个", "围一圈", "围墙",
            "build", "house", "shelter"
    };
    /** 主动招呼附近玩家的距离 / 对同一个玩家的重复招呼间隔 */
    static final double GREET_RANGE = 4.0D;
    static final long GREET_COOLDOWN_MS = 600_000L;
    /** 同队闲聊：自己起话头的最短间隔与随机抖动（tick），45~75 秒一句，避免话痨 */
    static final int TEAM_CHAT_MIN_GAP_TICKS = 900;
    static final int TEAM_CHAT_JITTER_TICKS = 600;
    /** 队友话音刚落多久内可以接话 / 接同一个队友的最短间隔 */
    static final long TEAM_CHAT_REPLY_WINDOW_MS = 12_000L;
    static final long TEAM_CHAT_REPLY_GAP_MS = 20_000L;
    /** 闲聊的队友最远距离（格） */
    static final double TEAM_CHAT_RANGE = 20.0D;
    /** 没有配置主人时的默认主人（房主） */
    public static final String DEFAULT_OWNER = "The_bodboy_";
    /** API Key 直接操控移动的持续时间（tick），约 3 秒 */
    static final int MANUAL_HOLD_TICKS = 60;
    /** 对同一个赠予者道谢的最短间隔 */
    static final long THANK_COOLDOWN_MS = 30_000L;

    final AiPlayer player;
    final AiPlayerManager manager;
    final AiProfile profile;
    /** 主人说过"允许 PvP"后才主动打其他玩家（默认 false，避免主动招惹） */
    boolean allowPlayerPvp;
    /** 仇恨目标：被谁打了以后记在这，死了 / 超时有仇必报 */
    Entity grudgeTarget;
    long grudgeUntilTick;

    public void setAllowPlayerPvp(boolean v) { this.allowPlayerPvp = v; }
    public boolean allowPlayerPvp() { return this.allowPlayerPvp; }

    final CombatController combat;
    final RangedController ranged;
    final CrystalPvpController crystal;
    final AnchorPvpController anchor;
    final WindChargeMaceController windMace;
    final ElytraSpearController elytra;
    final BlockTacticsController blockTactics;
    final FoodManager food;
    /** 睡觉与作息：夜里回床入睡，天亮 / 受伤 / 有怪自动醒 */
    final SleepController sleep;
    final EquipmentManager equipment;
    final EscapeController escape;
    final ChatController chat;
    final BridgeController bridge;
    final PathExecutor path;
    final InteractController interact;
    final com.aiplayer.ai.survival.PassiveController passive;
    /** 挖矿（矿脉 + 支巷矿道） / 砍树（整棵树） / 探索（出门 + 回程） */
    final MiningController mining;
    final ChopController chop;
    final ExploreController explore;
    /** 末影龙团队战（在末地时接管输入） */
    final com.aiplayer.ai.combat.BossController boss;
    /** 进/出末地跟随检查的低频计数器（每 40 tick 查一次跟随对象在哪） */
    int endFollowCheck;

    // ---- 智能① LLM 多步计划队列 ----
    /** 模型一次返回 2~4 步的计划：空闲时逐条执行；被战斗/逃生打断后队列保留，脱战接着做 */
    final Deque<JsonObject> planQueue = new ArrayDeque<>();
    /** 上一步下达后先让它沉淀这么多 tick 再执行下一步 */
    static final int PLAN_STEP_SETTLE_TICKS = 20;
    /** 单步最多允许占住多少 tick，超了就丢掉这一步，免得整条计划卡死 */
    static final int PLAN_STUCK_TICKS = 600;
    /** 一次计划最多几步 */
    static final int PLAN_MAX_STEPS = 4;
    int planStepTicks;
    int planStallTicks;

    // ---- 智能③ 多轮连续对话 ----
    /**
     * 最近几轮对话（含 AI 自己说过的话），每行形如「玩家 X：…」/「你说：…」。
     * <p>
     * 只带"上一句我说了什么"不够：AI 会在几轮之后把之前答应过的事（比如"叫什么都行"）
     * 当成没发生过，反过来反对自己。这里保留一小段双向记录，明确标出哪句是它自己说的。
     */
    final Deque<String> dialogMemory = new ArrayDeque<>();
    static final int DIALOG_MEMORY_MAX = 8;
    static final int DIALOG_LINE_MAX = 120;
    /**
     * 对话记忆的总字符预算。
     * <p>
     * 固定"8 行"对短句（"嗯""好"）太浪费，对长句又不够。改成按字数裁：
     * 短对话能记十几轮，长发言自动只留最近几轮，提示词长度可控。
     * 这也相当于「言出法随」那种上下文压缩的极简版 —— 只是不做摘要，直接按预算丢最旧的。
     */
    static final int DIALOG_TOTAL_MAX = 700;
    /** 本轮请求是回谁的话（pendingSpeaker 在发起请求时就被清空了，回包时得靠它还原） */
    String replySpeaker;

    /**
     * 清空这只 AI 的记忆：对话上下文 + 持久化的玩家指令 / 关键事件 / 认识的人 / 玩家习性。
     * GUI 的“清空记忆”按钮和 {@code /aiplayer memory ... clear} 都走这一个入口。
     */
    public void clearMemory() {
        this.dialogMemory.clear();
        if (this.profile != null) {
            this.profile.clearMemory();
        }
        AiPlayerMod.LOGGER.info("[aiplayer] {} 的记忆已清空", this.profile == null ? "?" : this.profile.name);
    }

    /** 记一句话进对话记忆（超出上限丢掉最旧的；单行截断，防止一条长文本把提示词撑爆） */
    void rememberDialog(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String trimmed = line.trim();
        // 同一句话已经在记忆最后一条里，就别再存一遍：
        // 否则模型看到的上下文里会出现两条一模一样的"你说：…"，更容易把它当成新任务再回复一次。
        if (!this.dialogMemory.isEmpty() && this.dialogMemory.peekLast().equals(trimmed)) {
            return;
        }
        this.dialogMemory.addLast(trimmed.length() > DIALOG_LINE_MAX
                ? trimmed.substring(0, DIALOG_LINE_MAX) : trimmed);
        while (this.dialogMemory.size() > DIALOG_MEMORY_MAX) {
            this.dialogMemory.removeFirst();
        }
        // 再按总字数裁一次：保证提示词大小稳定，不会因为几句长发言把 token 顶上去
        int total = 0;
        for (String s : this.dialogMemory) {
            total += s.length();
        }
        while (total > DIALOG_TOTAL_MAX && this.dialogMemory.size() > 1) {
            total -= this.dialogMemory.removeFirst().length();
        }
    }

    // ---- 智能④ 自主目标 ----
    /** 自主目标的下一次触发间隔 */
    int autonomyTicks;

    final ConcurrentLinkedQueue<Runnable> intents = new ConcurrentLinkedQueue<>();

    AiState state = AiState.IDLE;
    BlockPos walkTarget;
    BlockPos mineTarget;
    /**
     * 当前挖掘目标是不是采集控制器（矿脉 / 整棵树 / 支巷）排出来的。
     * true 时挖完这一格就交回控制器，不再自己往脚下接着挖 —— 否则控制器排好的
     * 队列会被"40% 概率往下挖一格"随机打断，矿脉和整棵树永远挖不干净。
     */
    boolean mineFromJob;
    BlockPos placeTarget;
    /** 连续建造队列：从 LLM 的 build 字段来，一次放多块（简易墙/房），逐块消费 */
    final Deque<BlockPos> buildQueue = new ArrayDeque<>();
    /** 当前这一格连续放失败的次数：超过上限就跳过，否则整条建造队列会永远卡在那一格 */
    int buildRetry;
    /** 走到当前这一格已经花掉的 tick：太久了就跳过，避免整条队列卡在同一格上 */
    int buildTicks;
    BlockPos cookTarget;
    /** 要翻的容器位置（箱子 / 木桶 / 熔炉…） */
    BlockPos containerTarget;
    /** 要交易的村民 */
    Entity tradeTarget;
    /** 要去合成的工位（工作台）和目标物品 / 次数 */
    BlockPos craftTarget;
    Item craftWant;
    int craftTimes = 1;
    /** 要去烧炼的熔炉 / 要去酿造的酿造台 */
    BlockPos smeltTarget;
    BlockPos brewTarget;
    /** 正在"垫脚"爬高 */
    boolean pillaring;
    /** 垫脚时的起跳基准高度 */
    int pillarBaseY = Integer.MIN_VALUE;
    int pillarTicks;
    /** 掉水里时最近的岸边（上岸方向），上岸后清空 */
    BlockPos shoreTarget;
    int shoreRepathIn;
    /** 上一次工具查询结果（种子 / 结构坐标），下一轮喂给 LLM */
    String lastToolResult = "";
    Entity attackTarget;
    String followName;
    /**
     * 玩家明确下令干活（砍树 / 挖矿 / 探索）时临时挂起的跟随目标。
     * <p>
     * jobTick 的第一条规则是"跟随玩家时不自己乱跑"，于是玩家喊"去砍树"时
     * AI 只会回一句"好嘞"然后杵在原地。这里把跟随暂时挂起，活干完由 jobTick 自动接回。
     * null = 没有挂起。
     */
    String followSuspended;
    /**
     * LLM 要求“使用某件物品”后的持续按住状态：吃东西 / 喝药要按住右键若干 tick 才生效，
     * 只点一次会被立刻中断。这里记下槽位和剩余 tick，由 {@code applyItemHold} 每 tick 施加。
     */
    int useHoldTicks;
    int useHoldSlot = -1;
    /** use 意图已经按下过几次右键：用来识别"即用即生效"的物品什么时候该松手 */
    int useHoldApplied;
    int actionCooldown;
    int idleTicks;
    int mineTicks;
    int stuckTicks;
    int lastSlot = -1;
    Vec3 lastPos = Vec3.ZERO;
    long stateSince = System.currentTimeMillis();

    // ---- 死亡回收：复活后走回死亡点，把掉落的装备捡回来 ----
    BlockPos recoveryTarget;
    ResourceKey<Level> recoveryDimension;
    int recoveryTicks;
    int recoveryIdleTicks;
    /** 这一趟回收已经走到多近才把东西捡起来：用来判断"是不是一件都没救回来" */
    int recoveryPicked;
    /** 回收任务最多持续多少 tick（3 分钟）；走不到 / 东西被刷掉就放弃 */
    static final int RECOVERY_TIMEOUT_TICKS = 3600;
    /** 到达死亡点后又确认多久（tick）才收工 */
    static final int RECOVERY_ARRIVE_IDLE_TICKS = 100;

    // ---- 守卫 / 巡逻：在指定保护区内来回走，看到怪就打 ----
    String guardZone;
    BlockPos guardGoal;
    int guardWait;
    int guardTimeout;
    /** 一个巡逻点最多走多久（tick）就换下一个，免得卡在墙角一直顶着 */
    static final int GUARD_LEG_TIMEOUT_TICKS = 600;

    // ---- 调试：最近一次 tick 被哪个阶段接管（/aiplayer stats 用） ----
    String lastReason = "aiplayer.state.idle";
    long reasonSince = System.currentTimeMillis();

    // ---- 调试：大脑 tick 耗时（/aiplayer stats 用滑动平均，不在每 tick 打日志） ----
    volatile long lastTickNanos;
    long avgTickNanos;

    /**
     * 是否有一个 LLM 请求正在路上。
     * <p>
     * 必须 {@code volatile}：它在 HTTP 回调线程上复位、在服务端 tick 线程上读取。
     * 不加的话服务端线程可能永远读到缓存里的 {@code true} —— 表现和"模型一直忙"一样，
     * 玩家说什么 AI 都不回（这正是之前"换了几个模型也不说话"的一个成因）。
     */
    volatile boolean llmBusy;
    /**
     * 模型链路当前处在哪一步：只读展示用（日志 / 任务摘要），不参与分支判断。
     * <p>
     * 以前「为什么它不说话」要同时看 llmBusy、lastLlmAt、llmBackoffMs 三个字段才能推出来，
     * 有一个显式状态后玩家自己看摘要就知道是没 Key、在等间隔、还是请求在路上。
     */
    volatile LlmPhase llmPhase = LlmPhase.IDLE;

    /** LLM 链路的显式状态 */
    public enum LlmPhase {
        /** 模型开关关着 */
        IDLE,
        /** 没配 Key，只能走本地规则 */
        NO_KEY,
        /** 等下一次请求间隔 */
        WAITING,
        /** 请求正在路上 */
        REQUESTING,
        /** 连续失败后的退避等待 */
        BACKOFF
    }

    long lastLlmAt;
    /** 连续失败次数（HTTP 线程写、主线程读） */
    volatile int llmFailures;
    /** 连续失败后的额外等待（毫秒），成功一次就清零（HTTP 线程写、主线程读） */
    volatile long llmBackoffMs;
    /** 请求在途时玩家消息已走本地规则处理：迟到的模型回复作废，避免动作打架 */
    volatile boolean llmStale;

    // ------------------------------------------------------------------
    // 玩家聊天互动（入口见 ChatListener）
    // ------------------------------------------------------------------
    /** 待回复的玩家名 / 原话：onPlayerChat 写入，maybeAskLlm 取走后清空（只带最后一句，省 token） */
    String pendingSpeaker;
    String pendingChatText;
    /** 本轮模型回复里有没有真的对玩家说出一句话（没说的话补一句本地应答，别让玩家等空） */
    boolean spokeToPlayer;
    /** 跟 pendingSpeaker 一起快照的玩家语言码（原版 ClientInformation 里就有，不需要额外网络包） */
    String pendingLocale;
    /** 本轮请求要用的语言码：回话时是玩家的语言，平时是跟随对象/主人的语言 */
    String replyLocale;
    /** 上一次响应玩家聊天的时刻，用于搭话频率限制 */
    long lastPlayerChatAt;
    /** 上次接同伴（别的 AI）话的时间 */
    long lastAiChatAt;
    /** 正在用桶冲掉的蜘蛛网（null = 没在干这活） */
    BlockPos webTarget;
    /** 0=倒水 1=等水流冲 2=把水源收回桶里 */
    int webStage;
    int webTicks;
    long webCooldownUntil;
    /** 已经招呼过的玩家（UUID -> 时刻），同一个玩家几分钟内只招呼一次 */
    final Map<UUID, Long> greetedAt = new HashMap<>();

    // ------------------------------------------------------------------
    // API Key 直接操控（移动等动作）
    // ------------------------------------------------------------------
    /** 剩余的手动操控 tick；> 0 时接管本 tick 的移动输入 */
    int manualTicks;
    boolean mForward;
    boolean mBack;
    boolean mLeft;
    boolean mRight;
    boolean mJump;
    boolean mSneak;
    boolean mSprint;
    Float mYaw;
    Float mPitch;

    /** 上一次道谢的玩家与时刻，避免同一份礼物反复感谢 */
    UUID thankedUuid;
    long thankedAt;

    AiBrainCore(AiPlayer player, AiPlayerManager manager, AiProfile profile) {
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
        this.sleep = new SleepController(player);
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
        this.boss = new com.aiplayer.ai.combat.BossController(player);
        // 默认允许挖一格通过（比如门口挡了一格方块的死胡同）。
        // 之前只有挖矿任务才 allowDig=true，追敌 / 走路 / 觅食等场景遇到死胡同 A* 完全不考虑挖路，
        // 结果算不出路就卡死。放开后挖一格代价 DIG_COST 会远高于绕路，A* 自然优先绕，
        // 只有绕路代价大太多时才会选挖一格（比如门口挡了一格）。
        this.path.setAllowDig(true);
        // GUI 里配置的“跟随玩家”要生效：followName 才是运行时真正读取的字段，
        // 之前只在 setFollow()（LLM / 指令）里赋值，导致 GUI 填了名字却毫无反应。
        this.followName = profile == null || profile.follow == null || profile.follow.isBlank()
                ? null : profile.follow.trim();
        // 持久化的"家"和"死亡回收点"要在生成时接回来：之前它们只在内存里，重启就没了
        String dim = player.level().dimension().identifier().toString();
        if (profile != null && profile.hasHome && dim.equals(profile.homeDimension)) {
            this.explore.setHome(new BlockPos(Mth.floor(profile.homeX), Mth.floor(profile.homeY),
                    Mth.floor(profile.homeZ)));
        }
        if (profile != null && profile.hasRecover && dim.equals(profile.recoverDimension)) {
            this.recoveryTarget = new BlockPos(Mth.floor(profile.recoverX), Mth.floor(profile.recoverY),
                    Mth.floor(profile.recoverZ));
            this.recoveryDimension = player.level().dimension();
            this.recoveryTicks = 0;
            this.recoveryIdleTicks = 0;
        }
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
    BlockPos goalPos() {
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
    Direction directionTo(BlockPos goal) {
        double dx = goal.getX() + 0.5D - this.player.getX();
        double dz = goal.getZ() + 0.5D - this.player.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    void setState(AiState next) {
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

    /**
     * 死亡回收：复活后先走回死亡点，把掉落的装备捡回来。
     * <p>
     * 掉落物由 {@link #recoveryTick()} 用普通的"就近捡东西"逻辑拾取；这里只负责记住目标。
     * 找不到（东西被刷掉 / 燃烧掉）或超时会自动放弃，不会让 AI 永远往一个空坐标跑。
     */
    public void recoverAt(BlockPos pos) {
        queue(() -> {
            this.recoveryTarget = pos == null ? null : pos.immutable();
            this.recoveryDimension = this.player.level().dimension();
            this.recoveryTicks = 0;
            this.recoveryIdleTicks = 0;
            this.recoveryPicked = 0;
            this.walkTarget = null;
            this.mineTarget = null;
            this.placeTarget = null;
            this.followSuspended = null;
            // 写进档案：中途关服 / 重启后回来还能接着找
            if (this.profile != null) {
                this.profile.hasRecover = pos != null;
                this.profile.recoverDimension = pos == null
                        ? "" : this.player.level().dimension().identifier().toString();
                this.profile.recoverX = pos == null ? 0.0D : pos.getX();
                this.profile.recoverY = pos == null ? 0.0D : pos.getY();
                this.profile.recoverZ = pos == null ? 0.0D : pos.getZ();
                AiPlayerConfig.get().save();
            }
        });
    }

    /** 是否正在执行死亡回收（/aiplayer stats 展示用） */
    public boolean isRecovering() {
        return this.recoveryTarget != null;
    }

    private void clearRecovery() {
        clearRecovery(false);
    }

    /**
     * 结束回收任务。
     *
     * @param giveBack true 表示这一趟没救回来（跨维度 / 超时 / 一件都没捡到），
     *                 用死亡瞬间的背包快照把装备补回背包
     */
    private void clearRecovery(boolean giveBack) {
        this.recoveryTarget = null;
        this.recoveryDimension = null;
        this.recoveryTicks = 0;
        this.recoveryIdleTicks = 0;
        this.recoveryPicked = 0;
        if (this.profile != null && this.profile.hasRecover) {
            this.profile.hasRecover = false;
            this.profile.recoverDimension = "";
            AiPlayerConfig.get().save();
        }
        if (giveBack) {
            giveBackupGear();
        } else if (this.profile != null && this.profile.gearBackup != null
                && !this.profile.gearBackup.isBlank()) {
            // 装备已经捡回来了：快照作废，免得下次死得早时被重复补发
            this.profile.gearBackup = "";
            AiPlayerConfig.get().save();
        }
    }

    /**
     * 兜底：回收失败时把死亡瞬间的背包快照合并回来（只补空位，不覆盖现有装备）。
     * <p>
     * 覆盖的场景：装备落进岩浆 / 虚空、被人捡走、跨维度复活、时间太久被刷掉。
     * 没有这一步，"走不到死亡点"就等于装备永久消失。
     */
    private void giveBackupGear() {
        if (this.profile == null || this.profile.gearBackup == null || this.profile.gearBackup.isBlank()) {
            return;
        }
        String json = this.profile.gearBackup;
        this.profile.gearBackup = "";
        int n = com.aiplayer.util.InvUtil.mergeInventoryJson(this.player, json);
        AiPlayerConfig.get().save();
        if (this.profile != null) {
            this.profile.rememberEvent("回收失败，已用死亡快照补回装备（" + n + " 件）");
        }
    }

    /**
     * 死亡回收每 tick 逻辑：
     * 附近有自己掉的装备就先捡；没有就往死亡点走；到了且附近确实没东西了就收工。
     *
     * @return true 表示本 tick 已被回收任务占住
     */
    boolean recoveryTick() {
        if (this.recoveryTarget == null) {
            return false;
        }
        if (this.recoveryDimension == null
                || !this.recoveryDimension.equals(this.player.level().dimension())
                || ++this.recoveryTicks > RECOVERY_TIMEOUT_TICKS) {
            // 跨维度 / 超时：这趟肯定捡不齐了，按快照补发
            clearRecovery(true);
            return false;
        }
        ItemEntity drop = nearestWantedDrop(16.0D);
        if (drop != null) {
            if (drop.distanceToSqr(this.player) <= 9.0D) {
                this.recoveryPicked++;
            }
            this.path.setGoal(drop.blockPosition());
            if (!this.path.tick()) {
                setState(AiState.MOVING);
            }
            this.recoveryIdleTicks = 0;
            return true;
        }
        double distSqr = this.player.distanceToSqr(Vec3.atCenterOf(this.recoveryTarget));
        if (distSqr <= 4.0D) {
            // 到地方了、附近也没有想捡的东西：多半是装备已被刷掉或被人捡走
            if (++this.recoveryIdleTicks > RECOVERY_ARRIVE_IDLE_TICKS) {
                // 一件都没捡到才整包补发；捡到过说明大部分已经回到背包，不重复发
                clearRecovery(this.recoveryPicked == 0);
                return false;
            }
            setState(AiState.IDLE);
            return true;
        }
        this.path.setGoal(this.recoveryTarget);
        if (this.path.tick()) {
            // 寻路放弃（走不到）：这一趟也算失败，东西没捡回来就用快照兜底
            clearRecovery(this.recoveryPicked == 0);
            return false;
        }
        setState(AiState.MOVING);
        return true;
    }

    // ------------------------------------------------------------------
    // 家（基地）
    // ------------------------------------------------------------------

    /**
     * 指定这只 AI 的家（基地）并写进档案：夜间回家、自主回撤都用它。
     * <p>
     * 以前家只存在内存里，重启就退回出生点；现在跨存档重开也还在。
     */
    public void setHome(BlockPos pos) {
        queue(() -> {
            if (pos == null) {
                return;
            }
            this.explore.setHome(pos);
            if (this.profile != null) {
                this.profile.hasHome = true;
                this.profile.homeDimension = this.player.level().dimension().identifier().toString();
                this.profile.homeX = pos.getX();
                this.profile.homeY = pos.getY();
                this.profile.homeZ = pos.getZ();
                AiPlayerConfig.get().save();
            }
        });
    }

    /** 取消指定的家：之后每次出门的起点重新当作临时家 */
    public void clearHome() {
        queue(() -> {
            this.explore.clearHome();
            if (this.profile != null) {
                this.profile.hasHome = false;
                this.profile.homeDimension = "";
                AiPlayerConfig.get().save();
            }
        });
    }

    /** 当前的家（可能是玩家指定的，也可能是本次出行的起点） */
    public BlockPos homePos() {
        return this.explore.home();
    }

    /** 家是不是玩家指定过并写进档案的 */
    public boolean hasPinnedHome() {
        return this.profile != null && this.profile.hasHome;
    }

    // ------------------------------------------------------------------
    // 守卫 / 巡逻
    // ------------------------------------------------------------------

    /** 让这只 AI 去指定保护区里守卫：区域内来回巡逻，看到怪物就交给战斗逻辑 */
    public void guardZone(String zoneName) {
        queue(() -> {
            this.guardZone = zoneName == null || zoneName.isBlank() ? null : zoneName.trim();
            this.guardGoal = null;
            this.guardWait = 0;
            this.guardTimeout = 0;
            this.walkTarget = null;
            this.mineTarget = null;
            this.placeTarget = null;
            if (this.guardZone != null) {
                setState(AiState.MOVING);
            }
        });
    }

    public void stopGuard() {
        guardZone(null);
    }

    public boolean isGuarding() {
        return this.guardZone != null;
    }

    public String guardZoneName() {
        return this.guardZone;
    }

    /**
     * 守卫每 tick：在区域内挑巡逻点来回走；被战斗 / 逃生打出区域后自己走回来。
     * <p>
     * 战斗、吃饭、睡觉、玩家明确任务都排在这之前，所以守卫不会妨碍正事；
     * 区域内的挖矿 / 砍树 / 自主探索都被挡住 —— 否则"守卫"会变成"到处乱跑"。
     *
     * @return true 表示本 tick 已被守卫占用
     */
    boolean guardTick(AiInput in) {
        if (this.guardZone == null) {
            return false;
        }
        ZoneManager.Zone zone = ZoneManager.find(this.guardZone);
        if (zone == null) {
            this.guardZone = null;   // 区域被删了，自动解除守卫
            return false;
        }
        Level level = this.player.level();
        if (!zone.contains(level, this.player.blockPosition())) {
            // 跑出区域了（追怪 / 被打飞）：先回中心
            this.guardGoal = null;
            this.guardWait = 0;
            this.path.setGoal(zone.center());
            this.path.tick();
            setState(AiState.MOVING);
            return true;
        }
        if (this.guardGoal != null) {
            if (this.player.blockPosition().closerThan(this.guardGoal, 3.0D)) {
                // 到了巡逻点：站一会儿再换下一个
                if (this.guardWait == 0) {
                    this.guardWait = 120 + RNG.nextInt(120);
                }
                if (--this.guardWait > 0) {
                    setState(AiState.IDLE);
                    return true;
                }
                this.guardWait = 0;
                this.guardGoal = null;
                return true;
            }
            if (--this.guardTimeout <= 0) {
                // 走不到（墙 / 水 / 落差）：直接换下一个点，别一直顶着
                this.guardGoal = null;
                return true;
            }
            this.path.setGoal(this.guardGoal);
            if (!this.path.tick()) {
                setState(AiState.MOVING);
            }
            return true;
        }
        this.guardGoal = pickGuardSpot(zone, level);
        if (this.guardGoal == null) {
            setState(AiState.IDLE);
            return true;
        }
        this.guardTimeout = GUARD_LEG_TIMEOUT_TICKS;
        this.path.setGoal(this.guardGoal);
        this.path.tick();
        setState(AiState.MOVING);
        return true;
    }

    /** 在区域里随机挑一个能站的地表点；挑不到就回中心 */
    private BlockPos pickGuardSpot(ZoneManager.Zone zone, Level level) {
        int spanX = zone.maxX - zone.minX + 1;
        int spanZ = zone.maxZ - zone.minZ + 1;
        for (int i = 0; i < 8; i++) {
            int x = zone.minX + RNG.nextInt(spanX);
            int z = zone.minZ + RNG.nextInt(spanZ);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y >= zone.minY && y <= zone.maxY) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 调试：最近一次 tick 被哪个阶段接管
    // ------------------------------------------------------------------

    /**
     * 记录本 tick 的决定由哪个阶段做出（/aiplayer stats 显示）。
     * <p>
     * 只记"阶段切换"，同一阶段连续 tick 不会反复刷新时间戳，所以看到的
     * 就是"它已经在这个状态里待了多久"。
     */
    void decided(String reasonKey) {
        if (reasonKey == null) {
            return;
        }
        if (!reasonKey.equals(this.lastReason)) {
            this.lastReason = reasonKey;
            this.reasonSince = System.currentTimeMillis();
        }
    }

    public String lastReasonKey() {
        return this.lastReason;
    }

    public long reasonSeconds() {
        return Math.max(0L, (System.currentTimeMillis() - this.reasonSince) / 1000L);
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

    void mineAt(BlockPos pos, boolean fromJob) {
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
            this.placeTarget = null;
            this.buildRetry = 0;
            this.walkTarget = null;
            this.mineTarget = null;
            this.cookTarget = null;
            List<BlockPos> plan = prepareBuildPlan(positions);
            // 材料预检：手上一块砖都没有就别开工，否则走到工地也只能站着
            int available = countBlocks();
            if (plan.isEmpty() || available <= 0) {
                AiPlayerMod.LOGGER.info("[aiplayer] {} 放弃建造：计划 {} 格，背包可用方块 {}",
                        this.profile.name, plan.size(), available);
                return;
            }
            this.buildQueue.addAll(plan);
            if (plan.size() > available) {
                AiPlayerMod.LOGGER.info("[aiplayer] {} 建造材料偏少：计划 {} 格，背包方块 {}",
                        this.profile.name, plan.size(), available);
            }
            advanceBuild();
        });
    }

    /**
     * 整理建造计划：去重、丢掉已经填好的格、按「先地基后上层」排序。
     * <p>
     * 这两步是从「言出法随」的建造线程借来的思路：
     * <ul>
     *   <li>已经放好的格直接跳过 —— 以前会先走过去、放不上、重试 5 次才跳过；</li>
     *   <li>Y 从小到大排 —— 悬空的格没有相邻支撑，{@link PlaceUtil} 根本找不到可点击的面，
     *       所以必须先让下层长出来；同层再按离自己近的优先，少走冤枉路。</li>
     * </ul>
     */
    private List<BlockPos> prepareBuildPlan(List<BlockPos> positions) {
        Level level = this.player.level();
        Map<BlockPos, BlockPos> unique = new LinkedHashMap<>();
        for (BlockPos p : positions) {
            if (p == null) {
                continue;
            }
            BlockPos pos = p.immutable();
            // 已经是实体方块（上一轮建过 / 本来就挡着）= 这一格不用再放
            if (!PlaceUtil.isReplaceable(level, pos)) {
                continue;
            }
            unique.putIfAbsent(pos, pos);
        }
        List<BlockPos> plan = new ArrayList<>(unique.values());
        BlockPos base = this.player.blockPosition();
        plan.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingDouble(p -> p.distSqr(base)));
        return plan;
    }

    /** 背包里一共有多少块方块（含主背包，不只是快捷栏） */
    private int countBlocks() {
        var inv = this.player.getInventory();
        int total = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 取建造队列的下一块作为当前放置目标；队列空了就结束 */
    void advanceBuild() {
        BlockPos next = null;
        // 队列里可能混着"刚被别人放好 / 地形本来就有的"格，逐格核一遍再走，
        // 免得为了一个已经到位的方块白跑一趟（最多核 256 格，防止异常队列卡住 tick）
        for (int i = 0; i < 256; i++) {
            BlockPos p = this.buildQueue.poll();
            if (p == null) {
                break;
            }
            if (PlaceUtil.isReplaceable(this.player.level(), p)) {
                next = p;
                break;
            }
        }
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
        queue(() -> {
            // TaCZ 的枪靠物品 NBT 区分枪种：原版 /give 造出来的是空壳打不响，
            // 所以这类指令先由兼容层接管（没装 tacz 时直接返回 false，照旧走原指令）。
            if (!com.aiplayer.compat.CompatManager.tryHandleCommand(this.player, command)) {
                this.chat.forceCommand(command);
            }
        });
    }

    public void setFollow(String name) {
        queue(() -> {
            this.followName = name;
            // 玩家重新指定跟随，之前的"干活挂起"作废
            this.followSuspended = null;
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
            this.followSuspended = null;
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
            this.sleep.reset();
            this.boss.reset();
            this.player.input().resetAll();
        });
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------


    /**
     * 末影龙团队战分支。
     * <p>
     * 在末地且场上还有活龙时，整 tick 交给 {@link com.aiplayer.ai.combat.BossController}；
     * 不在末地时低频检查"跟随对象进末地了没有"，跟进去（玩家过门、AI 一起过去）。
     *
     * @return true 表示本 tick 已被末影龙战占住
     */
    boolean bossTick(AiInput in) {
        if (this.profile == null || !this.profile.boss || !this.profile.combat) {
            return false;
        }
        if (this.manualTicks > 0) {
            return false;   // 玩家正在手动操控：听玩家的
        }
        if (this.boss.tick(in)) {
            this.path.clear();
            this.attackTarget = null;
            setState(AiState.BOSS);
            return true;
        }
        if (++this.endFollowCheck >= 40) {
            this.endFollowCheck = 0;
            followIntoEnd();
        }
        return false;
    }

    /**
     * 跟随对象进/出末地时带着 AI 一起走。
     * <p>
     * 进入：跟随的玩家 / 主人在末地而 AI 还在别处 —— 传送到他身边（玩家过末地传送门时全队跟进）。
     * 返回：龙已经打完（场上无龙）且对方离开了末地 —— 把 AI 带回他的维度。
     */
    void followIntoEnd() {
        MinecraftServer server = this.player.serverLevel().getServer();
        if (server == null) {
            return;
        }
        Player anchor = null;
        if (this.followName != null && !this.followName.isBlank()) {
            anchor = server.getPlayerList().getPlayerByName(this.followName);
        }
        if (anchor == null) {
            anchor = ownerPlayer();
        }
        if (anchor == null) {
            return;
        }
        boolean aiInEnd = this.player.level().dimension() == Level.END;
        boolean anchorInEnd = anchor.level().dimension() == Level.END;
        if (!(anchor.level() instanceof ServerLevel target)) {
            return;
        }
        if (anchorInEnd && !aiInEnd) {
            // 玩家过末地传送门 → 跟进去
            teleportPlayer(target, anchor.getX(), anchor.getY(), anchor.getZ());
        } else if (!anchorInEnd && aiInEnd && this.boss.dragon() == null) {
            // 龙打完了、对方离开末地 → 带回去
            teleportPlayer(target, anchor.getX(), anchor.getY(), anchor.getZ());
        }
    }

    /** 把 AI 传送到指定维度的某个坐标（跨维度也走原版 teleportTo） */
    boolean teleportPlayer(ServerLevel level, double x, double y, double z) {
        return this.player.teleportTo(level, x, y, z, java.util.Set.of(),
                this.player.getYRot(), this.player.getXRot(), false);
    }

    /** 指令 / 聊天入口：把自己和同队 AI 一起拉进末影龙战（先传送到末地再进入战斗状态） */
    public void startBossFight() {
        teleportToEnd();
        this.boss.engage();
        if (this.profile != null) {
            this.profile.rememberEvent("去打末影龙");
        }
        String team = this.profile == null ? "" : this.profile.team;
        if (team == null || team.isBlank()) {
            return;
        }
        for (AiPlayer mate : this.manager.all()) {
            if (mate == this.player || !mate.isAlive()) {
                continue;
            }
            AiBrain other = mate.brain();
            if (other == null || other.profile == null || !team.equals(other.profile.team)) {
                continue;
            }
            other.teleportToEnd();
            other.boss.engage();
        }
    }

    /** 把自己传到末地（优先贴到已经在末地的玩家身边，其次落到主岛上，都不会落虚空） */
    public boolean teleportToEnd() {
        if (this.player.level().dimension() == Level.END) {
            return true;
        }
        MinecraftServer server = this.player.serverLevel().getServer();
        ServerLevel end = server == null ? null : server.getLevel(Level.END);
        if (end == null) {
            return false;
        }
        // 1) 已经有人在末地：直接贴过去（跟人走最安全，也符合"全队一起进末地"）
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension() == Level.END && p.level() instanceof ServerLevel endLevel) {
                return teleportPlayer(endLevel, p.getX(), p.getY(), p.getZ());
            }
        }
        // 2) 没人在：落在主岛上一个偏心的位置（别站龙的正下方）。先强制加载主岛区块，
        //    否则 heightmap 查不到高度，会被传进虚空。
        BlockPos probe = new BlockPos(12, 0, 12);
        end.getChunkAt(probe);
        int y = end.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe).getY();
        if (y <= end.getMinY() + 1) {
            // 主岛没生成出来（极端情况）：垫一块黑曜石兜底，绝不落虚空
            BlockPos pad = new BlockPos(probe.getX(), -50, probe.getZ());
            end.setBlockAndUpdate(pad, net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
            y = pad.getY() + 1;
        }
        return teleportPlayer(end, probe.getX() + 0.5D, y, probe.getZ() + 0.5D);
    }



    /** 有没有任何"正经事"占着本 tick（计划 / 自主目标都靠它判断空不空闲） */
    boolean busyWithAnything() {
        return this.mineTarget != null || this.placeTarget != null || this.walkTarget != null
                || this.containerTarget != null || this.tradeTarget != null
                || this.attackTarget != null || !this.buildQueue.isEmpty()
                || this.mining.isBusy() || this.chop.isBusy() || this.explore.isActive()
                || this.bridge.isActive() || this.path.hasGoal()
                || this.mineFromJob;
    }

    /** 背包里有没有名字以某后缀结尾的物品（缺工具判定用，避开 26.2 被删掉的标签常量） */
    boolean hasItemPath(String suffix) {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }





    void runIntents() {
        Runnable intent;
        while ((intent = this.intents.poll()) != null) {
            try {
                intent.run();
            } catch (Throwable ignored) {
                // 单个指令出错不影响 AI 继续运行
            }
        }
    }

    void trackMovement() {
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
    boolean escapeTick(AiInput in) {
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


    // ------------------------------------------------------------------
    // 击败嘲讽：击杀对方 AI 后来一句骚话（频率限制，不刷屏）
    // ------------------------------------------------------------------

    /** 两条嘲讽之间的最短间隔 */
    static final long TAUNT_COOLDOWN_MS = 30_000L;
    /** 击杀嘲讽：存翻译键，真正渲染时按玩家各自的语言显示 */
    static final String[] TAUNT_KEYS = {
            "aiplayer.say.taunt.1",
            "aiplayer.say.taunt.2",
            "aiplayer.say.taunt.3",
            "aiplayer.say.taunt.4",
            "aiplayer.say.taunt.5",
    };
    long lastTauntAt;





    // ------------------------------------------------------------------
    // 组队系统
    // ------------------------------------------------------------------




    // ------------------------------------------------------------------
    // 组队升级：分工（拉扯 / 输出）、共享（食物 / 武器）、队形跟随
    // ------------------------------------------------------------------

    /** 对同一个队友两次分享的最短间隔（tick） */
    static final int SHARE_COOLDOWN_TICKS = 600;
    /** 自己身上少于这个口粮数就不算有余粮，不往外送 */
    static final int SPARE_FOOD_MIN = 12;
    /** 拉扯位与敌人保持的距离区间 */
    static final double HARASS_NEAR = 5.0D;
    static final double HARASS_FAR = 8.0D;

    /** 拉扯位绕圈方向 / 剩余 tick：别绕成固定半径的圆，会被预判 */
    int harassDir;
    int harassDirTicks;
    /** 队友 -> 上次分享的游戏 tick */
    final Map<UUID, Long> lastShareAt = new HashMap<>();

    // ---- 同队闲聊 ----
    /** 下一次可以开口的游戏 tick */
    int teamChatNextTick;
    /** 我最近说出口的那句闲聊（队友据此接话） */
    String teamChatLastLine;
    long teamChatLastAtMs;
    /** 我最近一次接话的墙钟时间，防止两个 AI 来回刷屏 */
    long teamChatRepliedAtMs;








    /** 盔甲槽位顺序：头 -> 胸 -> 腿 -> 脚 */
    static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };








    // ------------------------------------------------------------------
    // 同队闲聊
    // ------------------------------------------------------------------





    // ------------------------------------------------------------------
    // 任务
    // ------------------------------------------------------------------













    /** 方块类简单名（带缓存：同一个方块类型只查一次，省掉每格 new String） */
    static final Map<Class<?>, String> BLOCK_CLASS_NAMES = new ConcurrentHashMap<>();














    // ------------------------------------------------------------------
    // 自主生存：捡东西 / 狩猎 / 闲逛
    // ------------------------------------------------------------------

    boolean survivalTick(AiInput in) {
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
        // 后面几个分支都要看"背包里有没有吃的"：算一次复用，别重复扫背包
        int foodSlot = this.food.bestFoodSlotAnywhere();
        // 缺食物就去打猎
        if (this.profile.hunt && foodSlot < 0) {
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
        if (this.profile.eat && this.food.hungry() && foodSlot < 0
                && RNG.nextFloat() < 0.05F) {
            BlockPos container = nearestContainer(12.0D);
            if (container != null) {
                this.containerTarget = container;
                this.path.setGoal(container);
                return true;
            }
        }
        // 又饿又找不到任何吃的：附近随便走走找食物，别原地站桩
        if (this.profile.eat && this.food.hungry() && foodSlot < 0
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
    void thankIfGiven(ItemEntity drop) {
        Entity owner = drop.getOwner();
        if (!(owner instanceof Player giver) || giver == this.player) {
            return;
        }
        long now = System.currentTimeMillis();
        if (giver.getUUID().equals(this.thankedUuid) && now - this.thankedAt < THANK_COOLDOWN_MS) {
            return;
        }
        if (this.chat.sayReply(Component.translatable("aiplayer.say.thanks", giver.getScoreboardName()))) {
            this.thankedUuid = giver.getUUID();
            this.thankedAt = now;
            // 智能②：收到东西也算关键事件，记进持久记忆
            if (this.profile != null) {
                this.profile.rememberEvent("收到 " + giver.getScoreboardName() + " 给的 "
                        + drop.getItem().getHoverName().getString());
            }
        }
    }




    // ------------------------------------------------------------------
    // 工具：查种子 / 找结构（村庄、要塞…）
    // ------------------------------------------------------------------






    // ------------------------------------------------------------------
    // 语言（i18n）：AI 说的话要跟着"当前对话对象 / 跟随对象 / 主人"的语言走
    // ------------------------------------------------------------------





    /** 掉落物扫描缓存：捡东西的判定原本每 tick 做一次实体范围查询，改成每 10 tick 刷一次 */
    static final int DROP_SCAN_INTERVAL = 10;
    /** 缓存统一按固定半径扫一次，调用方再按自己的半径过滤 —— 6 格与 12 格两个调用点共用同一次扫描 */
    static final double DROP_SCAN_RANGE = 12.0D;
    ItemEntity dropCache;
    int dropCacheTicks;

    /** 打猎目标缓存：缺食物时原本每 tick 做一次 20 格实体查询，改成每 10 tick 刷一次 */
    static final int PREY_SCAN_INTERVAL = 10;
    Animal preyCache;
    int preyCacheTicks;
    double preyCacheRange;





    void idleTick(AiInput in) {
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













































































    // ------------------------------------------------------------------
    // LLM
    // ------------------------------------------------------------------



    // ------------------------------------------------------------------
    // 物品操控（LLM 用物品 ID 驱动，见 {@link ItemIdUtil}）
    // ------------------------------------------------------------------
























    // ------------------------------------------------------------------
    // 供指令查询
    // ------------------------------------------------------------------










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


    /** 在线的那个主人玩家；不在线返回 null */
    Player ownerPlayer() {
        MinecraftServer server = this.player.serverLevel().getServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayerByName(ownerName());
    }


    /**
     * 取玩家客户端设的语言码（{@code zh_cn}/{@code en_us}…）。
     * <p>
     * 原版 {@code ServerPlayer} 自己就维护着 {@code ClientInformation.language()}，登录和改语言时都会同步，
     * 所以不需要我们另发网络包。AI 实体本身没有客户端语言，返回 null。
     */
    static String localeOf(Player p) {
        if (p instanceof AiPlayer) {
            return null;
        }
        if (p instanceof net.minecraft.server.level.ServerPlayer sp) {
            try {
                String lang = sp.clientInformation().language();
                return lang == null || lang.isBlank() ? null : lang;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }


    /** 本轮 LLM 请求该用哪种语言：回话对象 > 跟随对象 > 主人 > 简体中文 */
    String targetLocale() {
        if (this.replyLocale != null) {
            return this.replyLocale;
        }
        String follow = this.followName;
        if (follow != null && !follow.isBlank()) {
            if (findByName(follow) instanceof ServerPlayer sp) {
                String lang = localeOf(sp);
                if (lang != null) {
                    return lang;
                }
            }
        }
        String lang = localeOf(ownerPlayer());
        return lang == null ? "zh_cn" : lang;
    }


    /** 语言码 -> 给模型看的语言名（模型对语言名比语言码可靠得多） */
    static String localeName(String code) {
        if (code == null) {
            return "English";
        }
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "zh_cn", "zh" -> "简体中文";
            case "zh_tw", "zh_hk" -> "繁體中文";
            case "ja_jp" -> "日本語";
            case "ko_kr" -> "한국어";
            case "de_de" -> "Deutsch";
            case "fr_fr" -> "Français";
            case "es_es", "es_mx", "es_ar" -> "Español";
            case "pt_br", "pt_pt" -> "Português";
            case "ru_ru" -> "Русский";
            case "it_it" -> "Italiano";
            case "pl_pl" -> "Polski";
            case "tr_tr" -> "Türkçe";
            case "nl_nl" -> "Nederlands";
            case "uk_ua" -> "Українська";
            default -> "English";
        };
    }


    /** 查世界种子（等价于 /seed，但 AI 读不到指令回显，所以直接读游戏数据） */
    void toolSeed() {
        long seed = this.player.serverLevel().getSeed();
        this.lastToolResult = "世界种子 = " + seed;
        this.chat.sayReply(Component.translatable("aiplayer.say.seed", String.valueOf(seed)));
    }


    /**
     * 查最近的结构（村庄 / 要塞 / 沉船…），把坐标记进 {@link #lastToolResult} 喂给模型，
     * 模型下一轮就能用 goto 过去。
     */
    void toolLocate(String kind) {
        TagKey<Structure> tag = structureTag(kind);
        if (tag == null) {
            this.lastToolResult = "不认识的结构类型：" + kind;
            return;
        }
        BlockPos origin = this.player.blockPosition();
        BlockPos found = this.player.serverLevel().findNearestMapStructure(tag, origin, 100, false);
        if (found == null) {
            this.lastToolResult = "附近 100 区块内没找到 " + kind;
            this.chat.sayReply(Component.translatable("aiplayer.say.locateNone", structureName(kind)));
            return;
        }
        int dist = (int) Math.sqrt(this.player.distanceToSqr(
                found.getX() + 0.5D, found.getY() + 0.5D, found.getZ() + 0.5D));
        this.lastToolResult = structureName(kind) + " 在 x=" + found.getX() + ", z=" + found.getZ()
                + "（只给水平坐标：原版 locate 的 Y 是无效的 ~，千万别填 0，否则 AI 会跑到 0 层）"
                + "，直线约 " + dist + " 格";
        this.chat.sayReply(Component.translatable("aiplayer.say.locateFound", structureName(kind),
                String.valueOf(found.getX()), String.valueOf(found.getZ()), String.valueOf(dist)));
        // 智能②：关键事件记进持久记忆（下次问起还能说出来）
        if (this.profile != null) {
            this.profile.rememberEvent("发现" + structureName(kind) + "（" + found.getX() + ", " + found.getZ() + "）");
        }
    }


    static TagKey<Structure> structureTag(String kind) {
        return switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT).trim()) {
            case "village", "村庄" -> StructureTags.VILLAGE;
            case "stronghold", "要塞" -> StructureTags.EYE_OF_ENDER_LOCATED;
            case "ruined_portal", "废弃传送门" -> StructureTags.RUINED_PORTAL;
            case "shipwreck", "沉船" -> StructureTags.SHIPWRECK;
            case "ocean_ruin", "海底废墟" -> StructureTags.OCEAN_RUIN;
            case "mineshaft", "废弃矿井" -> StructureTags.MINESHAFT;
            case "trial_chambers", "试炼密室" -> StructureTags.ON_BURIED_TRIAL_CHAMBERS_MAPS;
            case "buried_treasure", "宝藏" -> StructureTags.ON_TREASURE_MAPS;
            default -> null;
        };
    }


    static String structureName(String kind) {
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


    boolean hasRawMeat() {
        for (int i = 0; i < this.player.getInventory().getContainerSize(); i++) {
            if (FoodManager.isRawMeat(this.player.getInventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }


    ItemEntity nearestWantedDrop(double range) {
        double scan = Math.max(DROP_SCAN_RANGE, range);
        if (this.dropCacheTicks > 0) {
            this.dropCacheTicks--;
        } else {
            this.dropCacheTicks = DROP_SCAN_INTERVAL;
            this.dropCache = scanWantedDrop(scan);
        }
        // 缓存里存的是扫描半径内最近的一个；它比 range 还远，就说明 range 内根本没有
        // （最近的那个都在范围外，不可能有更近的），所以直接按距离过滤即可
        if (this.dropCache == null || !this.dropCache.isAlive()) {
            return null;
        }
        return this.dropCache.distanceToSqr(this.player) <= range * range ? this.dropCache : null;
    }


    /** 真正的实体范围查询（结果会被 {@link #nearestWantedDrop(double)} 缓存 10 tick） */
    ItemEntity scanWantedDrop(double range) {
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
    BlockPos nearestContainer(double range) {
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


    Animal nearestAnimal(double range) {
        if (this.preyCacheTicks > 0 && range <= this.preyCacheRange) {
            this.preyCacheTicks--;
        } else {
            this.preyCacheTicks = PREY_SCAN_INTERVAL;
            this.preyCacheRange = range;
            this.preyCache = scanAnimal(range);
        }
        if (this.preyCache == null || !this.preyCache.isAlive()) {
            return null;
        }
        return this.preyCache.distanceToSqr(this.player) <= range * range ? this.preyCache : null;
    }

    /** 真正的猎物范围查询（结果会被 {@link #nearestAnimal(double)} 缓存 10 tick） */
    Animal scanAnimal(double range) {
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


    /** 该列的地表高度；区块没加载就用 AI 自己的高度兜底 */
    int surfaceY(int x, int z) {
        Level level = this.player.level();
        if (!level.hasChunkAt(new BlockPos(x, this.player.getBlockY(), z))) {
            return this.player.getBlockY();
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }


    /** 走路落点：Y 无效（<=0，多半是 locate 的 ~ 被当成了 0）就换成该列地表高度 */
    BlockPos surface(BlockPos pos) {
        if (pos.getY() > 0) {
            return pos;
        }
        return new BlockPos(pos.getX(), surfaceY(pos.getX(), pos.getZ()), pos.getZ());
    }


    /** 把背包里符合条件的物品存进容器（箱子等），返回存入数量 */
    public int depositInto(BlockPos pos, java.util.function.Predicate<ItemStack> filter) {
        return this.interact.depositInto(pos, filter);
    }


    Entity findByName(String name) {
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


    public Component describe() {
        return Component.translatable("aiplayer.cmd.describe",
                this.profile.name,
                Component.translatable("aiplayer.level." + this.profile.level().id),
                Component.translatable(this.state.key()),
                fmt(this.player.getX()), fmt(this.player.getY()), fmt(this.player.getZ()),
                String.format(Locale.ROOT, "%.1f", this.player.getHealth()),
                String.valueOf(this.player.getFoodData().getFoodLevel()));
    }

    /**
     * 任务摘要：这只 AI 现在到底在干什么、要去做哪、属于哪支队伍、计划还剩几步。
     * <p>
     * 复用 tick 里的优先级顺序判断，所以摘要和实际行为一致；全部来自内存字段，
     * 不扫方块也不发请求，随时调用都可以。
     */
    public Component taskSummary() {
        String task;
        String goal = null;
        if (this.attackTarget != null && this.attackTarget.isAlive()) {
            task = "aiplayer.task.combat";
            goal = this.attackTarget.getName().getString();
        } else if (this.mineTarget != null) {
            task = "aiplayer.task.mine";
            goal = posText(this.mineTarget);
        } else if (this.placeTarget != null) {
            task = "aiplayer.task.place";
            goal = posText(this.placeTarget);
        } else if (this.cookTarget != null) {
            task = "aiplayer.task.cook";
            goal = posText(this.cookTarget);
        } else if (this.containerTarget != null) {
            task = "aiplayer.task.container";
            goal = posText(this.containerTarget);
        } else if (this.tradeTarget != null) {
            task = "aiplayer.task.trade";
            goal = this.tradeTarget.getName().getString();
        } else if (this.craftTarget != null) {
            task = "aiplayer.task.craft";
            goal = posText(this.craftTarget);
        } else if (this.smeltTarget != null) {
            task = "aiplayer.task.smelt";
            goal = posText(this.smeltTarget);
        } else if (this.brewTarget != null) {
            task = "aiplayer.task.brew";
            goal = posText(this.brewTarget);
        } else if (this.recoveryTarget != null) {
            task = "aiplayer.task.recover";
            goal = posText(this.recoveryTarget);
        } else if (this.guardZone != null) {
            task = "aiplayer.task.guard";
            goal = this.guardZone;
        } else if (this.walkTarget != null) {
            task = "aiplayer.task.walk";
            goal = posText(this.walkTarget);
        } else if (this.followName != null && !this.followName.isBlank()) {
            task = "aiplayer.task.follow";
            goal = this.followName;
        } else if (!this.planQueue.isEmpty()) {
            task = "aiplayer.task.plan";
            goal = String.valueOf(this.planQueue.size());
        } else {
            task = "aiplayer.task.idle";
        }
        String team = this.profile.team == null || this.profile.team.isBlank()
                ? Component.translatable("aiplayer.cmd.teamNone").getString()
                : this.profile.team;
        String last = this.profile.recentCommands.isEmpty()
                ? Component.translatable("aiplayer.task.none").getString()
                : this.profile.recentCommands.get(this.profile.recentCommands.size() - 1);
        return Component.translatable("aiplayer.cmd.task",
                Component.translatable(task),
                goal == null ? Component.translatable("aiplayer.task.none") : Component.literal(goal),
                Component.translatable(this.state.key()),
                Component.literal(team),
                Component.literal(String.valueOf(this.planQueue.size())),
                Component.literal(last));
    }

    private String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** 模型链路状态对应的语言键（/aiplayer task 第二行） */
    public String llmPhaseKey() {
        return switch (this.llmPhase) {
            case IDLE -> "aiplayer.llm.idle";
            case NO_KEY -> "aiplayer.llm.noKey";
            case WAITING -> "aiplayer.llm.waiting";
            case REQUESTING -> "aiplayer.llm.requesting";
            case BACKOFF -> "aiplayer.llm.backoff";
        };
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

    // ------------------------------------------------------------------
    // 调试面板：/aiplayer stats
    // ------------------------------------------------------------------

    /** 记录一次大脑 tick 的实际耗时（滑动平均，供 /aiplayer stats 用） */
    public void recordTickNanos(long nanos) {
        this.lastTickNanos = nanos;
        this.avgTickNanos = this.avgTickNanos == 0L ? nanos : (this.avgTickNanos * 15L + nanos) / 16L;
    }

    public long lastTickNanos() {
        return this.lastTickNanos;
    }

    public long averageTickNanos() {
        return this.avgTickNanos;
    }

    /**
     * stats 第二行：睡眠 / 搭路 / 挖掘 / 回收 / 卡住 / 空闲 / 状态持续 / 模型链路 /
     * 最近一次决策阶段（谁抢走了控制权）及其持续时长 / 寻路结论 / tick 耗时 / 最近指令。
     * 全部读内存字段，不打日志、不扫世界。
     */
    public Component statsDetail() {
        String last = this.profile.recentCommands.isEmpty()
                ? Component.translatable("aiplayer.task.none").getString()
                : this.profile.recentCommands.get(this.profile.recentCommands.size() - 1);
        return Component.translatable("aiplayer.cmd.statsDetail",
                flag(this.player.isSleeping()),
                flag(this.bridge.isActive()),
                flag(this.mining.isBusy() || this.mineTarget != null),
                flag(this.recoveryTarget != null),
                Component.literal(String.valueOf(this.stuckTicks)),
                Component.literal(String.valueOf(this.idleTicks)),
                Component.literal(String.valueOf(Math.max(0L, (System.currentTimeMillis() - this.stateSince) / 1000L))),
                Component.translatable(this.llmPhaseKey(), Component.literal(this.profile.name)),
                Component.translatable(this.lastReason),
                Component.literal(String.valueOf(reasonSeconds())),
                Component.translatable(this.path.failReasonKey()),
                Component.literal(fmtMicros(this.lastTickNanos)),
                Component.literal(fmtMicros(this.avgTickNanos)),
                Component.literal(last));
    }

    private static Component flag(boolean value) {
        return Component.translatable(value ? "aiplayer.cmd.yes" : "aiplayer.cmd.no");
    }

    private static String fmtMicros(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1000.0D);
    }


    public double yaw() {
        return Mth.wrapDegrees(this.player.getYRot());
    }


    /** 附近有没有这个模组的物品（给兼容层用） */
    public boolean holdsModItem() {
        return CompatManager.forItem(this.player.getMainHandItem()) != null;
    }


    static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0.0";
        }
        // 手工保留 1 位小数：每轮提示词要调十几次，String.format 的解析开销不值得
        long scaled = Math.round(v * 10.0D);
        long abs = Math.abs(scaled);
        String text = (abs / 10L) + "." + (abs % 10L);
        return scaled < 0L && abs != 0L ? "-" + text : text;
    }
}
