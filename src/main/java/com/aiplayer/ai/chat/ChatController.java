package com.aiplayer.ai.chat;

import com.aiplayer.ai.AiState;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.entity.AiPlayerManager;
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
    /** 同一句话的去重窗口：模型常把记忆里自己的旧回复当新回复再念一遍 */
    private static final long REPEAT_WINDOW_MS = 60_000L;
    /** 去重只保留最近几句，避免无限增长 */
    private static final int REPEAT_HISTORY = 8;

    private final AiPlayer player;
    private final Map<AiState, Long> stateSaidAt = new EnumMap<>(AiState.class);
    /** 最近说过的话（归一化后）与时间，用来拦截复读 */
    private final java.util.ArrayDeque<String> recentLines = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> recentLineAt = new java.util.ArrayDeque<>();
    /** 玩家最近一句（归一化后）：阻止 AI 把玩家原话照抄一遍当回复 */
    private String lastPlayerLineNorm = "";
    private long lastChatAt;
    private long lastCommandAt;
    private long lastErrorAt;
    /** 状态语音不进"同伴搭话"通道（否则 AI 会把彼此的状态播报当成聊天来回接） */
    private boolean suppressRelay;
    /** 正在把某句话转发给其他 AI：防止 A->B->A 无限对聊 */
    private static boolean relaying;

    public ChatController(AiPlayer player) {
        this.player = player;
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    /** 公屏说话（受冷却限制）。文本已按玩家语言生成时用这个。 */
    public boolean say(String text) {
        return text == null || text.isBlank() ? false : this.say(Component.literal(text));
    }

    /** 公屏说话（可翻译组件：服务端广播，客户端各自按自己语言渲染） */
    public boolean say(Component c) {
        if (isBlank(c)) {
            return false;
        }
        AiProfile profile = this.player.profile();
        if (profile != null && !profile.chat) {
            // GUI 关掉“聊天”后就不该再说话：自主闲聊和状态语音都走这个方法。
            // （sayForced 是玩家显式指令，不受此开关限制）
            return false;
        }
        if (isRepeat(c)) {
            return false;   // 一分钟内说过同一句：多半是模型把旧回复又念了一遍
        }
        long now = System.currentTimeMillis();
        // 下限兜底：老存档里 chatCooldownMs 可能还是很小的值，这里强制不低于 8 秒，防止话痨
        long cooldown = profile == null ? 15000L : Math.max(8000L, profile.chatCooldownMs);
        if (now - this.lastChatAt < cooldown) {
            return false;
        }
        this.lastChatAt = now;
        this.broadcast(c);
        return true;
    }

    /**
     * 事件语音（击杀嘲讽这类"值得说一句"的事件）。
     * <p>
     * 不能走 {@link #say(Component)}：那里为了防话痨硬性要求间隔 ≥8 秒，而且和自主闲聊 /
     * 状态语音共用 {@code lastChatAt}。战斗时状态语音刚播报过一次，击杀嘲讽就会被这 8 秒
     * 冷却直接吃掉 —— 表现就是"嘲讽台词配了但从来不说"。这里只保留 1 秒最低间隔，
     * 事件自身的频率（嘲讽 30 秒）由调用方负责。
     */
    public boolean sayEvent(Component c) {
        if (isBlank(c)) {
            return false;
        }
        AiProfile profile = this.player.profile();
        if (profile != null && !profile.chat) {
            return false;   // GUI 关掉"聊天"：谁也不说
        }
        if (isRepeat(c)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastChatAt < FORCED_CHAT_MIN_GAP_MS) {
            return false;
        }
        this.lastChatAt = now;
        this.broadcast(c);
        return true;
    }

    /** 玩家 / API 手动让 AI 说话：跳过业务冷却，但仍有最低间隔，避免被脚本刷屏 */
    public boolean sayForced(String text) {
        return text == null || text.isBlank() ? false : this.sayForced(Component.literal(text));
    }

    /** 强制说话（可翻译组件） */
    public boolean sayForced(Component c) {
        if (isBlank(c)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastChatAt < FORCED_CHAT_MIN_GAP_MS) {
            return false;
        }
        this.lastChatAt = now;
        this.broadcast(c);
        return true;
    }

    /**
     * 回玩家话：只受最低间隔约束，不走“业务冷却”。
     * <p>
     * 原因：回话走 {@link #say} 的话，默认 3 秒冷却会把回复吃掉 —— 玩家刚问完就听到沉默，
     * 过一会儿 AI 又自己说起别的事，看起来就是“不会聊天”。这里仍留 1 秒硬间隔防刷屏。
     */
    public boolean sayReply(String text) {
        return text == null || text.isBlank() ? false : this.sayReply(Component.literal(text));
    }

    /** 回玩家话（可翻译组件） */
    public boolean sayReply(Component c) {
        if (isBlank(c)) {
            return false;
        }
        AiProfile profile = this.player.profile();
        if (profile != null && !profile.chat) {
            return false;   // GUI 关掉"聊天"：谁也不说
        }
        if (isRepeat(c)) {
            // 模型偶尔会把对话记忆里自己刚说过的话再拿来回复：一分钟内同一句只放行一次
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastChatAt < FORCED_CHAT_MIN_GAP_MS) {
            return false;
        }
        this.lastChatAt = now;
        this.broadcast(c);
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

    /**
     * 报错提示（可翻译）：tellraw 用 translate 组件，每个玩家看到的是自己的语言。
     *
     * @param key 翻译键（如 {@code aiplayer.error.key}）
     * @param args 翻译参数（都是文本，会按 {@code {"text":...}} 塞进 with 数组）
     */
    public boolean reportKey(String key, String color, Object... args) {
        if (key == null || key.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastErrorAt < ERROR_MIN_GAP_MS) {
            return false;
        }
        this.lastErrorAt = now;
        StringBuilder with = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                with.append(',');
            }
            with.append("{\"text\":\"").append(escapeJson(String.valueOf(args[i]))).append("\"}");
        }
        with.append(']');
        String json = "{\"translate\":\"" + escapeJson(key) + "\",\"with\":" + with
                + ",\"color\":\"" + color + "\"}";
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
        if (hasLlm()) {
            // 配了 API 之后，说话交给模型：本地模板语音只会让 AI 看起来像复读机，
            // 所以状态语音直接让路（玩家主动对话、事件播报仍然保留）。
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
        this.suppressRelay = true;
        try {
            this.say(stateVoice(state));
        } finally {
            this.suppressRelay = false;
        }
    }

    private static Component stateVoice(AiState state) {
        return Component.translatable("aiplayer.say.voice." + state.name().toLowerCase(Locale.ROOT));
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
        this.broadcast(Component.literal(text));
    }

    /** 公屏广播一条聊天（可翻译组件由客户端各自渲染） */
    private void broadcast(Component c) {
        MinecraftServer server = this.player.serverLevel().getServer();
        if (server == null) {
            return;
        }
        String speaker = this.player.getScoreboardName();
        // 成功发出去了才记进"刚说过"列表，被冷却 / 去重拦掉的句子不会污染去重历史
        rememberLine(c);
        // 对话日志：AI 的所有公屏发言（回话 / 闲聊 / 状态语音 / 击杀嘲讽）都收在这一处
        com.aiplayer.util.ChatLog.ai(speaker, c.getString());
        Component line = Component.literal("<" + speaker + "> ").append(c);
        server.getPlayerList().broadcastSystemMessage(line, false);
        // TTS：把"谁说的 + 可翻译内容"发给装了客户端、且注册了这个包的玩家，
        // 由客户端按自己的语言渲染后用系统语音朗读（没装的直接跳过）
        com.aiplayer.net.TtsSpeakPayload tts = new com.aiplayer.net.TtsSpeakPayload(speaker, c);
        for (net.minecraft.server.level.ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp == this.player || sp instanceof AiPlayer) {
                continue;   // AI 之间不用互相朗读
            }
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(
                    sp, com.aiplayer.net.TtsSpeakPayload.TYPE)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(sp, tts);
            }
        }
        // 同伴搭话通道：别的 AI 听到这句话后会有 50% 概率接腔（见 AiBrain.onAiChat）
        if (!this.suppressRelay) {
            relayToOtherAi(c);
        }
    }

    /**
     * 把这只 AI 说的话转给其他 AI。
     * <p>
     * AI 的公屏聊天走的是 {@code broadcastSystemMessage}，不会触发原版的玩家聊天事件，
     * 所以"AI 搭理其他 AI 的话"必须在广播这一层直接回调过去，否则同伴永远是聋的。
     */
    private void relayToOtherAi(Component c) {
        if (relaying) {
            return;
        }
        relaying = true;
        try {
            String text = c.getString();
            for (AiPlayer other : AiPlayerManager.get().all()) {
                if (other == this.player || !other.isAlive() || other.brain() == null) {
                    continue;
                }
                other.brain().onAiChat(this.player, text);
            }
        } catch (Throwable ignored) {
            // 管理器未就绪 / 中途异常都不影响正常说话
        } finally {
            relaying = false;
        }
    }

    private static boolean isBlank(Component c) {
        return c == null || c.getString().isBlank();
    }

    /** 这只 AI 是否已经配置了可用的 LLM（有 API 时本地闲聊 / 状态语音减少） */
    public boolean hasLlm() {
        try {
            var cfg = com.aiplayer.config.AiPlayerConfig.get();
            if (!cfg.llmEnabled) {
                return false;
            }
            return cfg.hasAnyApiKey(this.player.profile());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 记录玩家刚说的原话（由聊天入口调用），用于阻止 AI 照抄玩家句子 */
    public void notePlayerSaid(String text) {
        this.lastPlayerLineNorm = normalizeForRepeat(text);
    }

    /**
     * 这句话是不是“不该再说”的：一分钟内说过同一句，或者在照抄玩家刚说的原话。
     * <p>
     * 模型有时会把“对话记忆”里自己上一轮的回复当成这一轮的新回复再输出一遍，
     * 表现就是玩家同一句话被回了两次、或者 AI 反复念同一段话。这里按归一化文本比对，
     * 忽略空格和标点。
     */
    private boolean isRepeat(Component c) {
        String key = normalizeForRepeat(c.getString());
        if (key.isEmpty()) {
            return false;
        }
        // 把玩家刚说的整句原样抄回来，不算回复
        if (!this.lastPlayerLineNorm.isEmpty()
                && (key.equals(this.lastPlayerLineNorm)
                || (this.lastPlayerLineNorm.length() >= 6 && key.contains(this.lastPlayerLineNorm)))) {
            return true;
        }
        long now = System.currentTimeMillis();
        while (!this.recentLineAt.isEmpty() && now - this.recentLineAt.peekFirst() > REPEAT_WINDOW_MS) {
            this.recentLineAt.pollFirst();
            this.recentLines.pollFirst();
        }
        return this.recentLines.contains(key);
    }

    /** 记录一次成功广播的发言，供 {@link #isRepeat} 去重 */
    private void rememberLine(Component c) {
        String key = normalizeForRepeat(c.getString());
        if (key.isEmpty()) {
            return;
        }
        this.recentLines.addLast(key);
        this.recentLineAt.addLast(System.currentTimeMillis());
        while (this.recentLines.size() > REPEAT_HISTORY) {
            this.recentLines.pollFirst();
            this.recentLineAt.pollFirst();
        }
    }

    /** 去重用的归一化：只保留字母 / 数字并转小写，中文标点、空格差异都视为同一句 */
    private static String normalizeForRepeat(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }
}