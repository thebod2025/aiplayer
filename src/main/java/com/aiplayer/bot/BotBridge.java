package com.aiplayer.bot;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.RemoteBotProfile;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * 外部 bot 桥接：本机 TCP 服务端，接收游戏外真实客户端（mineflayer 脚本）的连接。
 * <p>
 * 协议是 JSON Lines（一行一个 JSON），由外部进程主动连进来并握手：
 * <pre>
 * 外部 -> 模组：hello / state / chat / event / error / pong
 * 模组 -> 外部：welcome / settings / say / command / request_state
 * </pre>
 */
public final class BotBridge {
    /** 桥接协议版本，外部脚本与模组不一致时由脚本决定是否降级 */
    public static final int PROTOCOL = 1;

    private static final Map<String, BotSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Queue<Pending> QUEUE = new ConcurrentLinkedQueue<>();
    private static volatile ServerSocket listener;
    private static volatile String listenHost = "";
    private static volatile int listenPort = -1;
    private static volatile String bindError = "";
    private static volatile long lastBindAttempt;
    private static volatile long lastTick;
    private static volatile long lastBrainTick;
    private static volatile MinecraftServer mcServer;
    private static volatile Consumer<Component> clientBroadcaster;
    private static volatile boolean shuttingDown;

    /** 外部进程上报的微软登录验证码（内存缓存，10 分钟过期） */
    private static final Map<String, MsaTicket> MSA_TICKETS = new ConcurrentHashMap<>();
    private static final Map<String, Long> MSA_SEEN_AT = new ConcurrentHashMap<>();
    private static final Set<String> MSA_CONSUMED = ConcurrentHashMap.newKeySet();
    private static final long MSA_TTL_MS = 600000L;

    private BotBridge() {
    }

    /** 服务器启动时重置计时器并立刻尝试按配置监听 */
    public static void applyConfig() {
        lastBindAttempt = 0L;
        lastTick = 0L;
        bindError = "";
        ensureListener();
    }

    /** 每 tick 由服务端线程调用 */
    public static void tick(MinecraftServer server) {
        if (server != null) {
            mcServer = server;
        }
        long now = System.currentTimeMillis();
        if (now - lastTick < 25L) {
            return;
        }
        lastTick = now;
        ensureListener();
        drainQueue();
        checkTimeouts(now);
        if (now - lastBrainTick >= 1000L) {
            lastBrainTick = now;
            for (BotSession session : SESSIONS.values()) {
                if (!session.name().isEmpty()) {
                    BotBrain.tick(session);
                }
            }
        }
    }

    /** 服务端关闭 / 客户端退出：断开所有连接并停止监听 */
    public static void shutdown() {
        shuttingDown = true;
        closeListener();
        for (BotSession session : new ArrayList<>(SESSIONS.values())) {
            session.close();
        }
        SESSIONS.clear();
        shuttingDown = false;
    }

    public static void clearServer() {
        mcServer = null;
    }

    /** 客户端把广播接到自己的聊天栏（单人 / 联机客户端侧） */
    public static void setBroadcaster(Consumer<Component> broadcaster) {
        clientBroadcaster = broadcaster;
    }

    /** 对外播报一条提示（没有服务端时回退到客户端聊天栏） */
    public static void notify(Component message) {
        broadcast(message);
    }

    // ---- 监听 ----

    private static void ensureListener() {
        AiPlayerConfig cfg = AiPlayerConfig.get();
        if (!cfg.botBridge) {
            if (listener != null) {
                closeListener();
                bindError = "";
            }
            if (!SESSIONS.isEmpty()) {
                for (BotSession session : new ArrayList<>(SESSIONS.values())) {
                    session.close();
                }
                SESSIONS.clear();
            }
            return;
        }
        String host = cfg.botBridgeBind != null && !cfg.botBridgeBind.isBlank()
                ? cfg.botBridgeBind.trim() : "127.0.0.1";
        int port = cfg.botBridgePort;
        if (listener == null || listener.isClosed() || !host.equals(listenHost) || port != listenPort) {
            if (listener != null) {
                closeListener();
            }
            long now = System.currentTimeMillis();
            if (now - lastBindAttempt >= 5000L) {
                lastBindAttempt = now;
                bind(host, port);
            }
        }
    }

    private static void bind(String host, int port) {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(host, port));
            listener = socket;
            listenHost = host;
            listenPort = port;
            if (!bindError.isEmpty()) {
                AiPlayerMod.LOGGER.info("[aiplayer] 外部 bot 桥接已恢复监听 {}:{}", host, port);
            }
            bindError = "";
            AiPlayerMod.LOGGER.info("[aiplayer] 外部 bot 桥接开始监听 {}:{}", host, port);
            ServerSocket bound = socket;
            Thread thread = new Thread(() -> acceptLoop(bound), "aiplayer-bot-bridge");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            bindError = e.getMessage() == null ? e.toString() : e.getMessage();
            AiPlayerMod.LOGGER.warn("[aiplayer] 外部 bot 桥接监听失败 {}:{} -> {}", host, port, bindError);
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeListener() {
        ServerSocket socket = listener;
        listener = null;
        listenHost = "";
        listenPort = -1;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void acceptLoop(ServerSocket serverSocket) {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                BotSession session = new BotSession(socket);
                session.startReader();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    AiPlayerMod.LOGGER.warn("[aiplayer] 外部 bot 连接接收失败: {}", e.toString());
                }
            }
        }
    }

    // ---- 消息队列（网络线程 -> 服务端线程） ----

    static void enqueue(BotSession session, JsonObject message, String problem) {
        if (QUEUE.size() <= 1000) {
            QUEUE.add(new Pending(session, message, problem));
        }
    }

    private static void drainQueue() {
        int budget = 80;
        Pending pending;
        while (budget-- > 0 && (pending = QUEUE.poll()) != null) {
            if (pending.message() == null) {
                if (pending.problem() != null) {
                    AiPlayerMod.LOGGER.debug("[aiplayer] 外部 bot 消息丢弃：{}", pending.problem());
                }
                continue;
            }
            try {
                handle(pending.session(), pending.message());
            } catch (Throwable t) {
                AiPlayerMod.LOGGER.warn("[aiplayer] 处理外部 bot 消息失败: {}", t.toString());
            }
        }
    }

    private static void handle(BotSession session, JsonObject message) {
        String type = message.has("type") ? message.get("type").getAsString() : "";
        switch (type) {
            case "hello" -> onHello(session, message);
            case "state" -> session.onState(message);
            case "chat" -> onChat(session, message);
            case "event" -> onEvent(session, message);
            case "error" -> {
                String detail = message.has("message") ? message.get("message").getAsString() : "";
                broadcast(Component.translatable("aiplayer.bridge.error", session.name(), detail));
            }
            case "pong" -> session.touch();
            default -> {
            }
        }
    }

    private static void onHello(BotSession session, JsonObject hello) {
        session.applyHello(hello);
        if (session.name().isEmpty()) {
            JsonObject error = new JsonObject();
            error.addProperty("type", "error");
            error.addProperty("message", "hello 缺少 name");
            session.send(error);
            session.close();
            return;
        }
        RemoteBotProfile profile = session.profile();
        if (profile != null && !profile.enabled) {
            JsonObject error = new JsonObject();
            error.addProperty("type", "error");
            error.addProperty("message", "该 bot 在配置里被停用");
            session.send(error);
            session.close();
            return;
        }
        BotSession old = SESSIONS.put(session.key(), session);
        if (old != null && old != session) {
            old.close();
        }
        sendWelcome(session, profile);
        broadcast(Component.translatable("aiplayer.bridge.joined", session.name(), addressOf(session, profile)));
    }

    private static void onChat(BotSession session, JsonObject message) {
        String sender = message.has("sender") ? message.get("sender").getAsString() : "?";
        String text = message.has("text") ? message.get("text").getAsString() : "";
        boolean whisper = message.has("whisper") && message.get("whisper").getAsBoolean();
        if (text.isBlank() || text.length() > 512) {
            return;
        }
        session.addChat(sender + "：" + text);
        broadcast(Component.literal("[" + session.name() + "] " + sender + "：" + text).withStyle(ChatFormatting.GRAY));
        BotBrain.onChat(session, sender, text, whisper);
    }

    private static void onEvent(BotSession session, JsonObject message) {
        String detail = message.has("detail") ? message.get("detail").getAsString() : "";
        String event = message.has("event") ? message.get("event").getAsString() : "event";
        if ("msa_code".equals(event)) {
            String code = message.has("code") ? message.get("code").getAsString() : "";
            String url = message.has("url") ? message.get("url").getAsString() : "";
            session.setMsa(code, url);
            if (!code.isBlank()) {
                broadcast(Component.translatable("aiplayer.bridge.msa", session.name()));
            }
        } else if ("unsupported".equals(event)) {
            BotProcessManager.retryWithProxy(session.name());
            session.clearMsa();
            return;
        } else if ("join".equals(event) || ("auth".equals(event) && detail.contains("logged in"))) {
            session.clearMsa();
        }
        session.addEvent(event + " " + detail);
        boolean important = "join".equals(event) || "kicked".equals(event) || "death".equals(event)
                || "msa_failed".equals(event) || "unsupported".equals(event);
        if (important && session.acceptEvent(event + " " + detail)) {
            broadcast(Component.translatable("aiplayer.bridge.event", session.name(), event + " " + detail));
        }
    }

    // ---- 微软登录验证码 ----

    /** 取一个待弹窗的新验证码（客户端 tick 里轮询） */
    public static MsaTicket pendingMsa() {
        for (BotSession session : SESSIONS.values()) {
            String[] msa = session.pendingMsa();
            if (msa != null) {
                return new MsaTicket(session.name(), msa[0], msa[1]);
            }
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, MsaTicket> entry : MSA_TICKETS.entrySet()) {
            Long seen = MSA_SEEN_AT.get(entry.getKey());
            if (seen != null && now - seen <= MSA_TTL_MS) {
                if (MSA_CONSUMED.add(entry.getKey())) {
                    return entry.getValue();
                }
            } else {
                MSA_TICKETS.remove(entry.getKey());
                MSA_SEEN_AT.remove(entry.getKey());
                MSA_CONSUMED.remove(entry.getKey());
            }
        }
        return null;
    }

    /** ViaProxy 等外部登录流程上报验证码 */
    public static void registerMsa(String bot, String code, String url) {
        if (bot != null && !bot.isBlank() && code != null && !code.isBlank()) {
            String key = bot.trim().toLowerCase(Locale.ROOT);
            MSA_TICKETS.put(key, new MsaTicket(bot, code, url == null ? "" : url));
            MSA_SEEN_AT.put(key, System.currentTimeMillis());
            MSA_CONSUMED.remove(key);
        }
    }

    public static void unregisterMsa(String bot) {
        if (bot != null && !bot.isBlank()) {
            String key = bot.trim().toLowerCase(Locale.ROOT);
            MSA_TICKETS.remove(key);
            MSA_SEEN_AT.remove(key);
            MSA_CONSUMED.remove(key);
        }
    }

    /** 指定 bot 当前有效的验证码（客户端 LinkDialog 用） */
    public static MsaTicket msa(String bot) {
        if (bot == null || bot.isBlank()) {
            return null;
        }
        String key = bot.trim().toLowerCase(Locale.ROOT);
        Long seen = MSA_SEEN_AT.get(key);
        if (seen != null && System.currentTimeMillis() - seen > MSA_TTL_MS) {
            MSA_TICKETS.remove(key);
            MSA_SEEN_AT.remove(key);
            MSA_CONSUMED.remove(key);
            return null;
        }
        MsaTicket ticket = MSA_TICKETS.get(key);
        if (ticket != null) {
            return ticket;
        }
        BotSession session = lookup(bot);
        if (session == null) {
            return null;
        }
        String[] msa = session.msa();
        return msa == null ? null : new MsaTicket(session.name(), msa[0], msa[1]);
    }

    /** 记录“这个验证码已经给玩家看过了”，避免重复弹窗 */
    public static void markMsaShown(String bot, String code) {
        if (bot != null && !bot.isBlank()) {
            MSA_CONSUMED.add(bot.trim().toLowerCase(Locale.ROOT));
        }
        BotSession session = lookup(bot);
        if (session != null) {
            session.markMsaShown(code);
        }
    }

    /** 该 bot 是否还有有效的登录验证码（客户端据此决定是否继续弹窗） */
    public static boolean hasFreshMsa(String bot) {
        if (bot != null && !bot.isBlank()) {
            String key = bot.trim().toLowerCase(Locale.ROOT);
            Long seen = MSA_SEEN_AT.get(key);
            if (MSA_TICKETS.containsKey(key) && seen != null
                    && System.currentTimeMillis() - seen <= MSA_TTL_MS) {
                return true;
            }
        }
        BotSession session = lookup(bot);
        return session != null && session.hasFreshMsa();
    }

    // ---- 会话查询 ----

    private static BotSession lookup(String bot) {
        return bot != null && !bot.isBlank() ? SESSIONS.get(bot.trim().toLowerCase(Locale.ROOT)) : null;
    }

    public static boolean isListening() {
        ServerSocket socket = listener;
        return socket != null && !socket.isClosed();
    }

    public static String address() {
        return isListening() ? listenHost + ":" + listenPort : "";
    }

    public static String bindError() {
        return bindError;
    }

    public static int onlineCount() {
        int count = 0;
        for (BotSession session : SESSIONS.values()) {
            if (!session.name().isEmpty() && session.isOpen()) {
                count++;
            }
        }
        return count;
    }

    public static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (BotSession session : SESSIONS.values()) {
            if (!session.name().isEmpty() && session.isOpen()) {
                names.add(session.name());
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    public static boolean isOnline(String bot) {
        BotSession session = lookup(bot);
        return session != null && session.isOpen();
    }

    public static String currentOwner(String bot) {
        BotSession session = lookup(bot);
        return session == null ? "" : session.lastSpeaker();
    }

    // ---- 下发指令 ----

    public static boolean say(String bot, String text) {
        BotSession session = lookup(bot);
        if (session != null && session.isOpen() && text != null && !text.isBlank()) {
            session.say(text);
            session.addChat("我：" + text);
            return true;
        }
        return false;
    }

    public static boolean command(String bot, String action) {
        BotSession session = lookup(bot);
        if (session != null && session.isOpen()) {
            session.commandAction(action);
            return true;
        }
        return false;
    }

    public static boolean gotoPos(String bot, int x, int y, int z) {
        BotSession session = lookup(bot);
        if (session != null && session.isOpen()) {
            session.commandGoto("goto", x, y, z);
            return true;
        }
        return false;
    }

    public static boolean command(String bot, String action, String target) {
        BotSession session = lookup(bot);
        if (session != null && session.isOpen() && target != null && !target.isBlank()) {
            session.commandTarget(action, target);
            return true;
        }
        return false;
    }

    public static boolean requestState(String bot) {
        BotSession session = lookup(bot);
        if (session != null && session.isOpen()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "request_state");
            session.send(msg);
            return true;
        }
        return false;
    }

    /** 配置改动后把新设置推给已连接的 bot */
    public static void sendSettings(String bot) {
        BotSession session = lookup(bot);
        if (session != null) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "settings");
            msg.add("settings", settingsOf(session.profile(), session));
            session.send(msg);
        }
    }

    static void onSessionClosed(BotSession session) {
        boolean removed = SESSIONS.remove(session.key(), session);
        if (removed && !shuttingDown) {
            broadcast(Component.translatable("aiplayer.bridge.left", session.name()));
        }
    }

    // ---- 内部 ----

    private static void checkTimeouts(long now) {
        for (BotSession session : SESSIONS.values()) {
            if (session.isClosedFlag()) {
                SESSIONS.remove(session.key(), session);
            } else if (now - session.lastSeen() > 20000L) {
                AiPlayerMod.LOGGER.info("[aiplayer] 外部 bot {} 超过 20 秒无消息，断开", session.name());
                session.close();
            }
        }
    }

    private static void sendWelcome(BotSession session, RemoteBotProfile profile) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "welcome");
        msg.addProperty("protocol", PROTOCOL);
        msg.addProperty("name", profile == null ? session.name() : profile.safeName());
        msg.addProperty("stateIntervalMs", 2000);
        msg.add("settings", settingsOf(profile, session));
        session.send(msg);
    }

    private static JsonObject settingsOf(RemoteBotProfile profile, BotSession session) {
        JsonObject settings = new JsonObject();
        settings.addProperty("name", profile == null ? session.name() : profile.safeName());
        settings.addProperty("username", profile == null ? session.username() : profile.username);
        settings.addProperty("personality", profile == null ? "" : profile.personality);
        settings.addProperty("owner", profile == null ? "" : profile.ownerName);
        settings.addProperty("autoReply", profile == null || profile.autoReply);
        settings.addProperty("autoBrain", profile == null || profile.autoBrain);
        settings.addProperty("pvp", profile != null && profile.pvp);
        settings.addProperty("listenAll", profile != null && profile.listenAll);
        settings.addProperty("password", profile == null ? "" : profile.password);
        settings.addProperty("authMode", profile == null ? "auto" : profile.authMode);
        return settings;
    }

    private static String addressOf(BotSession session, RemoteBotProfile profile) {
        if (!session.server().isEmpty()) {
            return session.server();
        }
        return profile != null ? profile.host + ":" + profile.port : "?";
    }

    private static void broadcast(Component message) {
        MinecraftServer server = mcServer;
        if (server != null) {
            server.execute(() -> server.getPlayerList().broadcastSystemMessage(message, false));
        } else {
            Consumer<Component> consumer = clientBroadcaster;
            if (consumer != null) {
                consumer.accept(message);
            }
        }
    }

    private record Pending(BotSession session, JsonObject message, String problem) {
    }

    /** 微软登录验证码：bot 名字 + 代码 + 链接 */
    public record MsaTicket(String bot, String code, String url) {
    }
}
