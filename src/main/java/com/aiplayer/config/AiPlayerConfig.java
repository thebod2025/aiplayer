package com.aiplayer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.aiplayer.AiPlayerMod;
import com.aiplayer.util.ChatLog;
import com.aiplayer.util.DebugLog;
import com.aiplayer.zone.ZoneManager.Zone;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局配置（config/aiplayer.json）。API Key 等可以在 GUI 里配置。
 */
public class AiPlayerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static AiPlayerConfig INSTANCE;

    // ---- API ----
    /** OpenAI 兼容接口地址（不含 /chat/completions） */
    public String apiBaseUrl = "https://api.openai.com/v1";
    public String apiKey = "";
    public String model = "gpt-4o-mini";
    // ---- 双 API（聊天 / 操控分开）----
    /** 全局双 API 开关；单个 AI 也可以在档案里单独开启 */
    public boolean dualApi = false;
    /** 全局聊天 API 地址（留空逐级回退到主 API） */
    public String chatApiBaseUrl = "";
    /** 全局聊天 API Key（留空逐级回退到主 API） */
    public String chatApiKey = "";
    /** 全局聊天 API 模型（留空逐级回退到主模型） */
    public String chatModel = "";
    /** 全局默认装备包（JSON 完整背包快照）：AI 档案没配自己的 kit 时用它 */
    public String defaultKitNbt = "";
    public double temperature = 0.7D;
    public int maxTokens = 600;
    /** 是否允许 AI 调用大模型做决策 */
    public boolean llmEnabled = true;
    /**
     * 两次大模型决策之间的最小间隔（毫秒）。
     * <p>
     * 太短会打爆账户的 RPM 限制（HTTP 429），默认放到 12 秒。
     * 代码里还有 {@code max(8000, ...)} 的下限兜底，手改配置也刷不出高频请求。
     */
    public int llmIntervalMs = 12000;
    /** HTTP 超时（毫秒） */
    public int httpTimeoutMs = 15000;
    /**
     * 是否给请求带上 {@code response_format=json_object}。
     * <p>
     * 官方 OpenAI / 大多数中转都支持；少数自建网关、Ollama 兼容层会直接回 400。
     * 遇到 400 时会自动关掉这个开关并重试一次（结果会写回配置），所以不用手动改。
     */
    public boolean jsonMode = true;

    /**
     * 全服同时进行的 LLM 请求上限。
     * <p>
     * 每只 AI 自己都有 8 秒以上的间隔，但 20 只 AI 叠起来仍可能每秒打出好几个请求：
     * 既容易撞 429，也会让账单随人数线性上涨。超过上限的请求直接跳过这一轮，
     * AI 会继续用本地规则行为，不会卡住。
     */
    public int maxConcurrentLlm = 5;

    /**
     * 全服 AI 玩家数量上限。
     * <p>
     * 防止误操作（GUI 连点 / 指令批量生成 / 多个 autoKeep 档案）一次性拉起上百只 AI，
     * 把服务端 tick 拖垮。达到上限时新的生成请求会直接失败，已有 AI 不受影响。
     */
    public int maxAiPlayers = 512;

    /**
     * 距离分级（LOD）：周围没有任何真人玩家、也没有任务在身的 AI，决策降频到每 4 tick 一次。
     * <p>
     * 物理仍然每 tick 跑（否则会穿地 / 悬空），降的只是寻路、扫描与状态机判断。
     * 单人挂机、多人但 AI 分散时能明显省服务端 tick；想要 AI 在任何距离都全速运行就关掉。
     */
    public boolean aiLod = true;

    /**
     * 装了 TaCZ 枪械模组时，给没有枪的 AI 自动配一把枪 + 配套弹药。
     * <p>
     * TaCZ 的所有枪共用同一个物品 id，枪种只存在物品数据里 —— 手动塞进装备包的枪很容易变成
     * "看着是枪、实际没有枪种数据"的空壳，反查不到枪种就打不响。这里直接按枪包里的真实枪 id
     * 造一把带弹匣和弹药的枪，保证 AI 手上那把一定能开火。不想要就改成 false。
     */
    public boolean aiGunKit = true;

    /**
     * 允许 AI 使用枪械（只在装了 TaCZ 时生效）。
     * <p>
     * 关掉之后 AI 完全不会掏枪，只用弓 / 投掷物 / 近战 —— 想要"纯原版对抗"或者觉得枪太强时用。
     * 配置界面里的「枪械」页只在检测到 TaCZ 时才会出现，所以这个开关不会给没装枪械模组的玩家造成困惑。
     */
    public boolean aiGun = true;

    /**
     * 开枪时是否开镜（瞄准）。
     * <p>
     * 开镜会显著降低 TaCZ 的子弹散布，中远距离命中率差别很明显；
     * 但开镜后视野变窄、移速也会略降，想要"泼水式"扫射可以关掉。
     */
    public boolean aiGunAim = true;

    /**
     * 创造模式弹匣：AI 手里的枪永远不会打空。
     * <p>
     * 弹匣见底时直接把当前弹匣填满（走 TaCZ 的 {@code setCurrentAmmoCount}），
     * 不消耗背包里的弹药，也就不需要换弹动画。适合"只想让 AI 有火力压制感"的场景；
     * 觉得太强就在「枪械」页关掉，AI 会回到正常的打空→换弹流程。
     */
    public boolean aiInfiniteAmmo = true;

    /**
     * 持枪交战的射程上限（格）。
     * <p>
     * 超过这个距离 AI 会先追近再开枪：远距离散布大、很难命中，空放枪既浪费弹药又打不死人。
     * 设成 0 表示不限制。
     */
    public int aiGunRange = 48;

    /** LOD 判定距离（格）：最近真人玩家在这个范围内就不降频 */
    public int aiLodDistance = 80;

    /**
     * 调试日志：把本模组的日志另存到 {@code logs/aiplayer/} 下的独立文件，方便排查问题。
     * <p>
     * 写入前会把 API Key / Bearer / token 之类的敏感串打码，玩家把日志贴出来不会泄露 Key。
     * 默认关闭；改完保存即生效，最多保留最近 5 份文件。
     */
    public boolean debugLog = false;

    /**
     * AI 混战：打开后 AI 会主动把「不同队伍的其他 AI」当成敌人，可以互相开火（枪 / 弓 / 水晶 PvP 都会用上）。
     * <p>
     * 默认关闭 —— 多数玩家把 AI 当同伴，突然互相打起来会显得像 bug。
     * 想做「AI 对枪」演示或让两支 AI 队伍真打，把它改成 true 即可；同队的 AI 永远不打。
     * 注意：原版 {@code /gamerule pvp} 关掉时，玩家与玩家（AI 也是 ServerPlayer）之间的伤害会被原版拦掉。
     */
    public boolean aiDuel = false;

    /**
     * 参战队伍（队伍对战）：名单里的队伍会把「其他所有队伍的 AI」当成敌人，可以互相开火。
     * <p>
     * 与 {@link #aiDuel} 的区别：{@code aiDuel} 是全员混战；这里只让指定队伍出战，
     * 名单外的队伍保持原样（当同伴）。队伍名来自每个 AI 档案页里的「队伍」。
     */
    public List<String> aiWarTeams = new ArrayList<>();

    /** 这个队伍是否参战（名单里且名字非空） */
    public boolean teamAtWar(String team) {
        return team != null && !team.isBlank() && aiWarTeams != null && aiWarTeams.contains(team.trim());
    }

    /** 把界面里输入的「红队, 蓝队」拆成队伍名单（去空、去重、保序） */
    public static List<String> parseTeams(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("[,，\\s]+")) {
            String name = raw.trim();
            if (!name.isEmpty() && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * AI 对话日志：玩家与 AI 的公屏对话另存到 {@code logs/aiplayer/chat-<日期>.log}。
     * <p>
     * 默认开启；写入前会把 API Key 之类的敏感串打码（玩家把 Key 贴进聊天也不会被记下来）。
     * 不想要在配置里改成 false。
     */
    public boolean chatLog = true;

    /** AI 默认强度（生成新 AI 时使用） */
    public int defaultLevelId = 2;

    /** 所有 AI 的配置 */
    public List<AiProfile> profiles = new CopyOnWriteArrayList<>();

    /**
     * 领地保护区：区域内的方块 AI 不挖、不放、不点火。
     * 用 {@code /aiplayer zone add <名字> ...} 标记，重启后仍然生效。
     */
    public List<Zone> zones = new CopyOnWriteArrayList<>();

    public static AiPlayerConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiplayer.json");
    }

    public static AiPlayerConfig load() {
        Path path = file();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                AiPlayerConfig cfg = GSON.fromJson(json, AiPlayerConfig.class);
                if (cfg != null) {
                    // Gson 反序列化出来的是普通 ArrayList，换成 CopyOnWriteArrayList：
                    // 单人会同时有两个线程碰这份列表 —— 服务端线程生成 AI 时会 getOrCreate 往里加，
                    // 客户端线程在 GUI 里遍历它。普通 ArrayList 会在这种情况下抛
                    // ConcurrentModificationException，表现就是"点生成没反应 / 客户端崩溃"。
                    List<AiProfile> loaded = cfg.profiles;
                    cfg.profiles = new CopyOnWriteArrayList<>();
                    if (loaded != null) {
                        cfg.profiles.addAll(loaded);
                    }
                    List<Zone> loadedZones = cfg.zones;
                    cfg.zones = new CopyOnWriteArrayList<>();
                    if (loadedZones != null) {
                        cfg.zones.addAll(loadedZones);
                    }
                    // 手改配置写成 0 / 负数时给个安全下限，避免 LOD 完全失效或判定区过小
                    if (cfg.aiLodDistance < 16) {
                        cfg.aiLodDistance = 64;
                    }
                    INSTANCE = cfg;
                    DebugLog.apply(cfg);
                    ChatLog.apply(cfg);
                    // 老配置文件里没有后加的开关（debugLog / aiLod 等）：补写一次，
                    // 让玩家能在 config/aiplayer.json 里直接看到并调整
                    if (missingNewSwitch(json)) {
                        cfg.save();
                    }
                    return cfg;
                }
            } catch (IOException | JsonSyntaxException e) {
                AiPlayerMod.LOGGER.warn("[aiplayer] 配置读取失败，使用默认配置: {}", e.toString());
            }
        }
        AiPlayerConfig cfg = new AiPlayerConfig();
        cfg.profiles.add(new AiProfile());
        INSTANCE = cfg;
        cfg.save();
        return cfg;
    }

    /** 配置里是否缺了后加的开关（只看字段名是否存在，不解析内容） */
    private static boolean missingNewSwitch(String json) {
        return !json.contains("\"debugLog\"")
                || !json.contains("\"chatLog\"")
                || !json.contains("\"aiLod\"")
                || !json.contains("\"aiGunKit\"")
                || !json.contains("\"aiGun\"")
                || !json.contains("\"aiGunAim\"")
                || !json.contains("\"aiGunRange\"")
                || !json.contains("\"aiDuel\"")
                || !json.contains("\"aiWarTeams\"")
                || !json.contains("\"aiLodDistance\"")
                || !json.contains("\"dualApi\"")
                || !json.contains("\"chatApiBaseUrl\"")
                || !json.contains("\"chatApiKey\"")
                || !json.contains("\"chatModel\"")
                || !json.contains("\"zones\"");
    }

    public void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AiPlayerMod.LOGGER.warn("[aiplayer] 配置保存失败: {}", e.toString());
        }
        // 调试开关 / 对话日志 / API Key 改动后立刻生效（关掉就停止写文件，换 Key 就刷新打码用的值）
        DebugLog.apply(this);
        ChatLog.apply(this);
    }

    /** 找到（或创建）指定名字的配置 */
    public AiProfile getOrCreate(String name) {
        String key = (name == null || name.isBlank()) ? "AI" : name.trim();
        AiProfile existing = find(key);
        if (existing != null) {
            return existing;
        }
        AiProfile p = new AiProfile();
        p.name = key;
        p.levelId = this.defaultLevelId;
        // 新建的 AI 自动分一个性格，保证每只 AI 脾气都不一样
        p.personality = AiProfile.randomPersonality();
        this.profiles.add(p);
        save();
        return p;
    }

    /**
     * 按名字找配置；找不到返回 null。
     * <p>
     * 必须容忍 {@code p.name} 为 null：手改过配置文件（或旧版本写坏）时会出现
     * {@code "name": null}，直接 {@code p.name.equalsIgnoreCase(...)} 会抛 NPE ——
     * 而生成 AI 的路径上抛 NPE，玩家看到的就是"点生成没反应 / 无法添加 AI"。
     */
    public AiProfile find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim();
        for (AiProfile p : this.profiles) {
            if (p != null && p.name != null && p.name.equalsIgnoreCase(key)) {
                return p;
            }
        }
        return null;
    }

    public boolean hasApiKey() {
        return this.apiKey != null && !this.apiKey.isBlank() && !this.apiKey.equals("YOUR_API_KEY");
    }

    public String keyFor(AiProfile profile) {
        if (profile != null && profile.apiKey != null && !profile.apiKey.isBlank()) {
            return profile.apiKey;
        }
        return this.apiKey;
    }

    public String baseUrlFor(AiProfile profile) {
        if (profile != null && profile.apiBaseUrl != null && !profile.apiBaseUrl.isBlank()) {
            return profile.apiBaseUrl;
        }
        return this.apiBaseUrl;
    }

    public String modelFor(AiProfile profile) {
        if (profile != null && profile.model != null && !profile.model.isBlank()) {
            return profile.model;
        }
        return this.model;
    }

    /** 双 API 是否对这只 AI 生效（全局开或档案单独开） */
    public boolean dualApiFor(AiProfile profile) {
        return this.dualApi || (profile != null && profile.dualApi);
    }

    /**
     * 聊天 API 的回退顺序：档案聊天 Key → 全局聊天 Key → 档案主 Key → 全局主 Key。
     * 这样只打开开关、不填聊天 Key 也不会把 AI 变成哑巴。
     */
    public String chatKeyFor(AiProfile profile) {
        if (profile != null && profile.chatApiKey != null && !profile.chatApiKey.isBlank()) {
            return profile.chatApiKey;
        }
        if (this.chatApiKey != null && !this.chatApiKey.isBlank()) {
            return this.chatApiKey;
        }
        return keyFor(profile);
    }

    public String chatBaseUrlFor(AiProfile profile) {
        if (profile != null && profile.chatApiBaseUrl != null && !profile.chatApiBaseUrl.isBlank()) {
            return profile.chatApiBaseUrl;
        }
        if (this.chatApiBaseUrl != null && !this.chatApiBaseUrl.isBlank()) {
            return this.chatApiBaseUrl;
        }
        return baseUrlFor(profile);
    }

    public String chatModelFor(AiProfile profile) {
        if (profile != null && profile.chatModel != null && !profile.chatModel.isBlank()) {
            return profile.chatModel;
        }
        if (this.chatModel != null && !this.chatModel.isBlank()) {
            return this.chatModel;
        }
        return modelFor(profile);
    }

    /** 主 API + 聊天 API 任意一个配了 Key（本地发言降频判断用） */
    public boolean hasAnyApiKey(AiProfile profile) {
        return isRealKey(keyFor(profile)) || isRealKey(chatKeyFor(profile));
    }

    private static boolean isRealKey(String key) {
        return key != null && !key.isBlank() && !key.equals("YOUR_API_KEY");
    }
}