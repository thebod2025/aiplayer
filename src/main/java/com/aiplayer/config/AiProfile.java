package com.aiplayer.config;

import com.aiplayer.ai.AiLevel;
import com.aiplayer.ai.bridge.BridgeMode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个 AI 玩家的配置。可以单独配置强度、功能开关、搭路方式等。
 */
public class AiProfile {
    public String name = "AI";
    /** 使用哪个正版玩家的皮肤（留空 = 默认皮肤） */
    public String skinName = "";
    /** 强度 1-4 */
    public int levelId = 2;
    /** 独立 API Key，留空则使用全局配置 */
    public String apiKey = "";
    /** 独立 API 地址（留空用全局） */
    public String apiBaseUrl = "";
    /** 独立模型，留空则使用全局配置 */
    public String model = "";
    /**
     * 双 API 开关：开启后，聊天回复走“聊天 API”，自主行动 / 操控走上面的主 API。
     * 两路共用同一份对话记忆、习性与状态，所以不会出现“聊天说一套、行动做一套”的分裂。
     * 关闭时行为与以前完全一致（所有请求都走主 API）。
     */
    public boolean dualApi = false;
    /** 聊天 API 地址，留空则回退到聊天全局配置，再回退到主 API */
    public String chatApiBaseUrl = "";
    /** 聊天 API Key，留空则逐级回退（聊天全局 → 主 Key） */
    public String chatApiKey = "";
    /** 聊天 API 模型，留空则逐级回退（聊天全局 → 主模型） */
    public String chatModel = "";
    /** 自定义系统提示词，留空使用默认 */
    public String customPrompt = "";
    /**
     * 这只 AI 的性格（自由文本，会整段塞进系统提示词，影响它的说话风格与做事倾向）。
     * <p>
     * 留空 = 生成时从 {@link #PERSONALITY_PRESETS} 里随机抽一个，保证每只 AI 的脾气都不一样。
     */
    public String personality = "";

    /** 内置性格原型：留空时随机抽一条，玩家也可以自己在 GUI 里写 */
    public static final String[] PERSONALITY_PRESETS = {
            "勇猛：说话直来直去、敢打敢冲，遇到危险第一反应是迎面顶上去。",
            "谨慎：话少、凡事三思，打不过就先撤，永远先保住自己的命。",
            "财迷：特别在意资源和战利品，常惦记挖矿、收集，说话三句不离“这玩意儿值钱”。",
            "幽默：张口就爱开玩笑、自嘲，遇到麻烦也忍不住要吐槽两句。",
            "高冷：惜字如金，除非被点名否则不主动说话，一次只说最必要的几个字。",
            "热心：乐于帮主人和队友，接到请求先答应再动手，语气温和有耐心。",
            "好奇：爱探索、爱打听新鲜事，看到没见过的地方或生物就想凑过去看看。",
            "懒散：能省事就省事，嘴上爱抱怨“好累啊”，但答应下来的活还是会干完。",
    };

    private static final java.util.Random PERSONA_RNG = new java.util.Random();

    /** 随机抽一个性格原型 */
    public static String randomPersonality() {
        return PERSONALITY_PRESETS[PERSONA_RNG.nextInt(PERSONALITY_PRESETS.length)];
    }

    /** 性格文案：留空返回随机原型（只在生成时补，不写盘） */
    public String personalityText() {
        return personality == null || personality.isBlank() ? randomPersonality() : personality.trim();
    }

    /**
     * 自定义嘲讽台词：击败其他 AI 后说的话。多条之间用 {@code |} 分隔（GUI 是单行输入框），
     * 也可以直接写换行。文案里的 {@code %s} 会替换成被击败者的名字。
     * <p>
     * 留空 = 使用内置的 5 条多语言台词（{@code aiplayer.say.taunt.*}）。
     */
    public String customTaunts = "";

    /** 解析自定义嘲讽：按 {@code |} 或换行切分，去掉空白项；没有自定义返回空列表 */
    public java.util.List<String> tauntLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (customTaunts == null || customTaunts.isBlank()) {
            return out;
        }
        for (String part : customTaunts.split("[|\\n]")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 生成时是否给予基础装备包 */
    public boolean giveKit = true;
    /**
     * 自定义装备包内容，逗号 / 分号 / 换行分隔，例如
     * {@code minecraft:diamond_sword, golden_apple x8, cooked_beef x32}。
     * <p>
     * 留空 = 使用内置的默认套装；写错导致一个都解析不出来时也会退回默认套装，
     * 不会让 AI 空手上场。写 {@code minecraft:} 前缀可省略，护甲不用手动穿
     * （AI 会自动装备）。
     */
    public String kitItems = "";

    /** 完整背包快照（JSON）：41 格逐槽 SNBT，含盔甲 / 副手 / 附魔 NBT。非空时优先于 kitItems */
    public String kitNbt = "";

    /** 打过招呼的玩家名单：只在第一次见面时问候，持久化到 JSON（重启也认得） */
    public java.util.List<String> metPlayers = new java.util.ArrayList<>();

    /**
     * 主人（房主）玩家名。留空 = 使用内置默认主人 {@code The_bodboy_}。
     * <p>
     * GUI 生成 AI 时会自动填成“生成它的那个玩家”。AI 会听主人的话、绝不攻击主人，
     * 主人被攻击时还会帮主人一起反击。
     */
    public String ownerName = "";

    /**
     * 队伍名。同一个队伍的 AI 之间互不攻击，还会协战：
     * 队友被打 / 队友开打时会赶去支援。留空 = 无队伍（对所有人为敌/中立）。
     */
    public String team = "";

    // ---- 功能开关 ----
    public boolean mine = true;
    public boolean place = true;
    public boolean combat = true;
    public boolean chat = true;
    public boolean command = true;
    public boolean eat = true;
    public boolean equip = true;
    public boolean hunt = true;
    public boolean bridge = true;
    /** 脚下没方块时自动搭路 */
    public boolean autoBridge = true;
    /** 水晶 PvP（需要黑曜石 + 末地水晶） */
    public boolean crystalPvp = true;
    /** 锚 PvP（重生锚 + 萤石，主世界引爆，只对玩家用） */
    public boolean anchorPvp = true;
    /** 风弹重锤连招（风弹上天 + 重锤砸地） */
    public boolean windMace = true;
    /** 鞘翅长矛突进（鞘翅滑翔 + 三叉戟刺击） */
    public boolean elytraSpear = true;
    /** 击败其他 AI 后嘲讽一句（30 秒冷却） */
    public boolean taunt = true;
    /** 空闲时去钓鱼 */
    public boolean fishing = true;
    /** 空闲时收庄稼并补种 */
    public boolean farm = true;
    /** 有骨头 / 生鱼时驯服野狼 / 猫 */
    public boolean tame = true;
    /** 空闲时出门探索（一段一段往外走、记路、记得回家） */
    public boolean explore = true;
    /** 远程武器（弓 / 弩 / 三叉戟） */
    public boolean ranged = true;
    /** 危险时自动逃生（落地水 / 撤退 / 举盾） */
    public boolean escape = true;
    /** 始终 PvP：主动攻击附近所有 AI / 玩家（主人除外） / 敌对生物 */
    public boolean alwaysPvp = false;
    /** 允许使用投掷物（末影珍珠等）逃生 */
    public boolean throwables = true;
    /** 跟随指定玩家（名字，留空不跟随） */
    public String follow = "";
    /** 末影龙团队战：在末地时自动按团队打法协同打龙 */
    public boolean boss = true;

    // ---- 持久记忆（随 prompt 带上，省得每次从零问） ----
    /** 最近收到的玩家指令（最多 5 条，新的在前） */
    public java.util.List<String> recentCommands = new java.util.ArrayList<>();
    /** 最近的关键事件（最多 5 条，新的在前）：发现结构 / 拿到好东西 / 被击败等 */
    public java.util.List<String> keyEvents = new java.util.ArrayList<>();
    /**
     * 玩家习性：玩家名 → 简短习惯摘要（每人最多 3 条，用 {@code  / } 分隔）。
     * 只记录从对话里观察到的短句，持久化到 config，LLM 提示词会带上，避免每次重新摸索。
     */
    public java.util.Map<String, String> playerHabits = new java.util.LinkedHashMap<>();

    /**
     * 记一条玩家习性（短句去重，最多保留 3 条；玩家最多保留 8 个）。
     * 过长的整句话不存 —— 那属于对话记忆，不属于“习性”。
     */
    public void rememberHabit(String player, String text) {
        if (player == null || player.isBlank() || text == null || text.isBlank()) {
            return;
        }
        String name = player.trim();
        String habit = text.trim().replace('\n', ' ').replace('\r', ' ');
        // 寒暄没有信息量，记进去只会污染习性或让 AI 变得油嘴滑舌
        if (habit.length() < 2 || habit.matches("你好|您好|谢谢|谢了|嗯|哦|好的|好吧|在吗|哈喽|hi|hello|ok")) {
            return;
        }
        if (habit.length() > 24) {
            habit = habit.substring(0, 24);
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        String prev = playerHabits.get(name);
        if (prev != null && !prev.isBlank()) {
            for (String part : prev.split(" / ")) {
                if (!part.isBlank()) {
                    parts.add(part);
                }
            }
        }
        parts.remove(habit);
        parts.add(habit);
        while (parts.size() > 3) {
            parts.remove(0);
        }
        playerHabits.put(name, String.join(" / ", parts));
        while (playerHabits.size() > 8) {
            java.util.Iterator<String> it = playerHabits.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /** 清空这只 AI 的所有持久记忆（对话在 AiBrainCore 里单独清） */
    public void clearMemory() {
        recentCommands.clear();
        keyEvents.clear();
        metPlayers.clear();
        playerHabits.clear();
    }

    /** 记一条玩家指令 */
    public void rememberCommand(String text) {
        remember(recentCommands, text);
    }

    /** 记一条关键事件 */
    public void rememberEvent(String text) {
        remember(keyEvents, text);
    }

    private static void remember(java.util.List<String> list, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        list.remove(text);
        list.add(0, text);
        while (list.size() > 5) {
            list.remove(list.size() - 1);
        }
    }

    // ---- 搭路配置 ----
    /** 0 关闭 / 1 蹲起搭 / 2 神桥 / 3 Telly / 4 SpeedTelly */
    public int bridgeMode = 1;
    /** 每秒放置的方块数 */
    public double bridgeSpeed = 4.0D;
    /** forward / backward / left / right / diagonal */
    public String bridgeDirection = "backward";
    /** 使用的方块名（见 BlockPalette） */
    public String bridgeBlock = "wool";
    /** 搭路时是否同时攻击附近敌人 */
    public boolean bridgeAttack = true;

    // ---- 聊天 / 命令 ----
    /** 自主说话的间隔（毫秒）。默认调大，避免 AI 话痨刷屏 */
    public int chatCooldownMs = 15000;
    public int commandCooldownMs = 8000;
    /** 是否允许自主聊天（不依赖 API） */
    public boolean autoChat = true;
    /** 是否允许自主执行命令 */
    public boolean autoCommand = false;

    // ---- 退出存档后自动恢复 ----
    /**
     * 服务器 / 存档重新加载时是否把这个 AI 生成回来。
     * <p>
     * 之前退出存档 AI 就全没了：它们的玩家数据确实存在硬盘里，但没有任何代码去 spawn，
     * 于是"再进存档发现 AI 都不见了"。现在关服时把位置写进档案，下次启动按原位置恢复。
     */
    public boolean autoKeep = true;
    /** 上次关服时所在的维度 ID（例如 {@code minecraft:overworld}） */
    public String lastDimension = "";
    public double lastX;
    public double lastY;
    public double lastZ;
    /** 是否已经有可用的位置记录（没记录过就不恢复，免得把 AI 生成到 (0,0,0)） */
    public boolean hasLastPos = false;
    /**
     * 位置记录属于哪个存档（存档根目录路径的短哈希）。
     * <p>
     * 配置是全局的（{@code config/aiplayer.json}，不随存档走），没有这个标识的话
     * 新建一个存档也会把旧存档的 AI 按旧坐标生成出来。世界名相同的不同存档无法区分，
     * 但保存路径本身唯一，足够用。
     */
    public String lastWorld = "";

    // ---- 家（基地）与死亡回收点：跨重启保留 ----
    /**
     * 玩家指定的"家"（基地）。夜间回家、巡逻回撤都用它，而不是每次出门时的临时位置。
     * <p>
     * 之前家只存在 {@code ExploreController} 的内存里，重启后 AI 会把出生点当成家。
     */
    public boolean hasHome = false;
    public String homeDimension = "";
    public double homeX;
    public double homeY;
    public double homeZ;

    /** 最近一次死亡的坐标：复活后走回去捡装备，重启后依然记得 */
    public boolean hasRecover = false;
    public String recoverDimension = "";
    public double recoverX;
    public double recoverY;
    public double recoverZ;

    /**
     * 死亡瞬间的背包快照（JSON，41 格逐槽）。
     * <p>
     * 回收失败（掉落物进岩浆 / 虚空 / 被人捡走 / 超时）时用它把装备补回来，
     * 不再让"走不过去"变成装备永久丢失。补发或回收成功后清空。
     */
    public String gearBackup = "";

    public AiLevel level() {
        return AiLevel.byId(this.levelId);
    }

    public BridgeMode bridge() {
        return BridgeMode.byId(this.bridgeMode);
    }

    public Map<String, Boolean> features() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("mine", mine);
        m.put("place", place);
        m.put("combat", combat);
        m.put("chat", chat);
        m.put("command", command);
        m.put("eat", eat);
        m.put("equip", equip);
        m.put("hunt", hunt);
        m.put("bridge", bridge);
        m.put("autoBridge", autoBridge);
        m.put("crystalPvp", crystalPvp);
        m.put("anchorPvp", anchorPvp);
        m.put("windMace", windMace);
        m.put("elytraSpear", elytraSpear);
        m.put("taunt", taunt);
        m.put("fishing", fishing);
        m.put("farm", farm);
        m.put("tame", tame);
        m.put("explore", explore);
        m.put("ranged", ranged);
        m.put("escape", escape);
        m.put("throwables", throwables);
        m.put("boss", boss);
        return m;
    }

    public boolean getFeature(String key) {
        Boolean v = features().get(key);
        return v != null && v;
    }

    /** @return true 表示 key 合法 */
    public boolean setFeature(String key, boolean value) {
        switch (key) {
            case "mine" -> mine = value;
            case "place" -> place = value;
            case "combat" -> combat = value;
            case "chat" -> chat = value;
            case "command" -> command = value;
            case "eat" -> eat = value;
            case "equip" -> equip = value;
            case "hunt" -> hunt = value;
            case "bridge" -> bridge = value;
            case "autoBridge" -> autoBridge = value;
            case "crystalPvp" -> crystalPvp = value;
            case "anchorPvp" -> anchorPvp = value;
            case "windMace" -> windMace = value;
            case "elytraSpear" -> elytraSpear = value;
            case "taunt" -> taunt = value;
            case "fishing" -> fishing = value;
            case "farm" -> farm = value;
            case "tame" -> tame = value;
            case "explore" -> explore = value;
            case "ranged" -> ranged = value;
            case "escape" -> escape = value;
            case "throwables" -> throwables = value;
            case "boss" -> boss = value;
            default -> {
                return false;
            }
        }
        return true;
    }
}