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
    /** 自定义系统提示词，留空使用默认 */
    public String customPrompt = "";
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
            default -> {
                return false;
            }
        }
        return true;
    }
}