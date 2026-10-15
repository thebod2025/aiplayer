package com.aiplayer.ai.chat;

import com.aiplayer.ai.AiState;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.PlayerList;

import java.util.EnumMap;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Random;

/**
 * 聊天 / tellraw / 指令。
 * <p>
 * 公屏用 {@link PlayerList#broadcastSystemMessage} 广播，其它玩家看到的就是普通聊天；
 * 私聊、tellraw 以及报错提示走原版 tellraw 指令（带颜色、只有 @a 能收到，不会被安全策略拦）。
 * 所有自动指令都要过危险指令黑名单。
 */
public class ChatController {
    private static final Random RNG = new Random();
    /** 危险指令前缀（自动执行时一律拦截） */
    private static final String[] DANGEROUS = {
            "kick", "ban", "ban-ip", "banlist", "pardon", "pardon-ip", "op", "deop", "stop", "reload", "whitelist",
            "save-all", "save-off", "save-on", "setidletimeout", "forceload", "debug", "difficulty"
    };
    /**
     * 指令的命名空间前缀（{@code minecraft:op} 里的 {@code minecraft:}）。
     * <p>
     * 原版允许 {@code /minecraft:op} 这种写法，不剥掉前缀的话上面那张黑名单一句就能绕过。
     */
    private static final Pattern NAMESPACE_PREFIX = Pattern.compile("(?<=^|\\s)[a-z0-9_.\\-]+:");
    /** 这些指令不允许用 @a / @e / @p 之类的选择器（防止误伤全服玩家） */
    private static final String[] SELECTOR_SENSITIVE = {
            "give", "kill", "clear", "effect", "tp", "teleport", "spreadplayers", "gamemode", "damage"
    };
    /** 强制说话的最高频率（防止刷屏） */
    private static final long FORCED_CHAT_MIN_GAP_MS = 1000L;
    /** 报错提示的最高频率 */
    private static final long ERROR_MIN_GAP_MS = 5000L;

    private final AiPlayer player;
    private final Map<AiState, Long> stateSaidAt = new EnumMap<>(AiState.class);
    private long lastChatAt;
    private long lastCommandAt;
    private long lastErrorAt;

    public ChatController(AiPlayer player) {
        this.player = player;
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    /** 公屏说话（受冷却限制） */
    public boolean say(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        AiProfile profile = this.player.profile();
        if (profile != null && !profile.chat) {
            // GUI 关掉“聊天”后就不该再说话：自主闲聊和状态语音都走这个方法。
            // （sayForced 是玩家显式指令，不受此开关限制）
            return false;
        }
        long now = System.currentTimeMillis();
        // 下限兜底：老存档里 chatCooldownMs 可能还是很小的值，这里强制不低于 8 秒，防止话痨
        long cooldown = profile == null ? 15000L : Math.max(8000L, profile.chatCooldownMs);
        if (now - this.lastChatAt < cooldown) {
            return false;
        }
        this.lastChatAt = now;
        this.broadcast("<" + this.player.getScoreboardName() + "> " + text);
        return true;
    }

    /** 玩家 / API 手动让 AI 说话：跳过业务冷却，但仍有最低间隔，避免被脚本刷屏 */
    public boolean sayForced(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastChatAt < FORCED_CHAT_MIN_GAP_MS) {
            return false;
        }
        this.lastChatAt = now;
        this.broadcast("<" + this.player.getScoreboardName() + "> " + text);
        return true;
    }

    /**
     * 回玩家话：只受最低间隔约束，不走“业务冷却”。
     * <p>
     * 原因：回话走 {@link #say} 的话，默认 3 秒冷却会把回复吃掉 —— 玩家刚问完就听到沉默，
     * 过一会儿 AI 又自己说起别的事，看起来就是“不会聊天”。这里仍留 1 秒硬间隔防刷屏。
     */
    public boolean sayReply(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        AiProfile profile = this.player.profile();
        if (profile != null && !profile.chat) {
            return false;   // GUI 关掉“聊天”：谁也不说
        }
        long now = System.currentTimeMillis();
        if (now - this.lastChatAt < FORCED_CHAT_MIN_GAP_MS) {
            return false;
        }
        this.lastChatAt = now;
        this.broadcast("<" + this.player.getScoreboardName() + "> " + text);
        return true;
    }

    /** 私聊 /msg */
    public boolean tell(String target, String text) {
        return forceCommand("msg " + target + " " + text);
    }

    /** tellraw（json 是原版 tellraw 组件） */
    public boolean tellraw(String target, String json) {
        return forceCommand("tellraw " + target + " " + json);
    }

    /**
     * 给全服玩家发一条 tellraw 提示（带颜色）。
     * 用于 API Key 失效 / 404 / 限流之类的关键报错 —— 这类消息不属于聊天，不受聊天冷却限制。
     */
    public boolean report(String text, String color) {
        if (text == null || text.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastErrorAt < ERROR_MIN_GAP_MS) {
            return false;
        }
        this.lastErrorAt = now;
        String json = "{\"text\":\"" + escapeJson(text) + "\",\"color\":\"" + color + "\"}";
        return forceCommand("tellraw @a " + json);
    }

    public boolean reportError(String text) {
        return report(text, "red");
    }

    public boolean reportWarn(String text) {
        return report(text, "yellow");
    }

    /** 执行任意指令（原版 + 模组指令都走原版派发器，受冷却限制） */
    public boolean runCommand(String raw) {
        if (!isSafe(raw)) {
            return false;
        }
        AiProfile profile = this.player.profile();
        long now = System.currentTimeMillis();
        long cooldown = profile == null ? 8000L : profile.commandCooldownMs;
        if (now - this.lastCommandAt < cooldown) {
            return false;
        }
        this.lastCommandAt = now;
        return forceCommand(raw);
    }

    /** 跳过冷却直接执行（玩家手动下发的指令） */
    public boolean forceCommand(String raw) {
        if (!isSafe(raw)) {
            return false;
        }
        MinecraftServer server = this.player.serverLevel().getServer();
        if (server == null) {
            return false;
        }
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        CommandSourceStack source = this.player.createCommandSourceStack();
        // AI 的默认指令源权限等级是普通玩家（0），/aiplayer、/give、/tp、/time 这类都会被静默拒绝。
        // 这里显式提权到 ALL_PERMISSIONS；危险指令仍由 isSafe() 在入口拦掉（op/ban/kick/stop…）。
        source = source.withPermission(PermissionSet.ALL_PERMISSIONS);
        server.getCommands().performPrefixedCommand(source, command);
        this.lastCommandAt = System.currentTimeMillis();
        return true;
    }

    /** 状态语音：只在状态变化或超过冷却时报一句 */
    public void voice(AiState state) {
        AiProfile profile = this.player.profile();
        if (profile == null || !profile.autoChat) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = this.stateSaidAt.get(state);
        // 状态语音最容易刷屏（状态频繁切换），下限拉到 15 秒
        long cooldown = Math.max(15000L, profile.chatCooldownMs);
        if (last != null && now - last < cooldown) {
            return;
        }
        float chance = profile.level().chatChance() / 100.0F;
        if (RNG.nextFloat() > chance) {
            return;
        }
        this.stateSaidAt.put(state, now);
        this.say(stateVoice(state));
    }

    private static String stateVoice(AiState state) {
        return switch (state) {
            case MINING -> "我在挖矿";
            case PLACING -> "搭两格";
            case COMBAT -> "别过来啊！";
            case RANGED -> "看我射他";
            case CRYSTAL -> "打水晶时间~";
            case EATING -> "先吃点东西";
            case HUNTING -> "去弄点吃的";
            case BRIDGING -> "搭路中";
            case ESCAPING -> "先撤了";
            case MOVING -> "我这就过去";
            case FOLLOWING -> "跟着你";
            case DEAD -> "我又死了……";
            default -> "嗯";
        };
    }

    // ------------------------------------------------------------------
    // 安全
    // ------------------------------------------------------------------

    public static boolean isSafe(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        String lower = command.toLowerCase(Locale.ROOT).trim();
        // 先剥掉命名空间前缀，否则 /minecraft:op 这类写法能直接绕过黑名单
        lower = NAMESPACE_PREFIX.matcher(lower).replaceAll("");
        String head = lower.split("\\s+")[0];
        for (String bad : DANGEROUS) {
            if (head.equals(bad)) {
                return false;
            }
        }
        // 危险指令出现在第一个 / 之后（execute as @a run op @s 之类）
        for (String bad : DANGEROUS) {
            if (lower.contains(" run " + bad + " ") || lower.endsWith(" run " + bad)) {
                return false;
            }
        }
        for (String sensitive : SELECTOR_SENSITIVE) {
            // 只拦“一网打尽”型选择器（@a 全玩家 / @e 全实体 / @r 随机玩家）。
            // @s（自己）和 @p（最近玩家）必须放行，否则"玩家让 AI 给自己东西"时
            // give @p / tp @s 这类会被静默拒掉，表现就是"AI 根本不会执行指令"。
            if (head.equals(sensitive) && lower.matches(".*\\s@[aer]\\b.*")) {
                return false;
            }
        }
        // execute ... run <子指令>：递归检查最后一段（用最后一次出现，避免误判字符串里的 run）
        if (head.equals("execute")) {
            int idx = lower.lastIndexOf(" run ");
            if (idx >= 0) {
                return isSafe(lower.substring(idx + 5));
            }
        }
        return true;
    }

    private static String escapeJson(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private void broadcast(String text) {
        MinecraftServer server = this.player.serverLevel().getServer();
        if (server == null) {
            return;
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    }
}