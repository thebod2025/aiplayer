package com.aiplayer.bot;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.RemoteBotProfile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 一个外部 bot 的桥接会话：一条 TCP 连接 + JSON Lines 协议。
 * <p>
 * 外部进程（mineflayer 脚本）负责登录远程服务器并把状态 / 聊天上报过来，
 * 这里负责缓存状态、保存最近聊天与事件、把指令写回外部进程。
 */
final class BotSession {
    /** 单行 JSON 上限，超过直接丢弃（防止外部进程写坏数据把服务端内存撑爆） */
    static final int MAX_LINE = 16384;
    private static final int CHAT_HISTORY = 10;
    private static final int EVENT_HISTORY = 6;
    private static final int SAID_HISTORY = 8;

    private final Socket socket;
    private final BufferedWriter out;
    private final Object writeLock = new Object();
    private final long createdAt = System.currentTimeMillis();

    private volatile String name = "";
    private volatile String username = "";
    private volatile String server = "";
    private volatile String version = "";
    private volatile JsonObject state;
    private volatile long stateAt;
    private volatile long lastSeen = System.currentTimeMillis();
    private volatile boolean closed;

    /** 收到的公开聊天（含私聊），格式「说话人：内容」 */
    private final Deque<String> chatHistory = new ArrayDeque<>();
    /** 最近事件（加入 / 被踢 / 死亡等），给 LLM 当上下文 */
    private final Deque<String> events = new ArrayDeque<>();
    /** 自己最近说过的话，用来去重，避免复读 */
    private final Deque<String> recentSaid = new ArrayDeque<>();

    private volatile long lastLlmReplyAt;
    private volatile boolean lastLlmOwnerChannel;
    private volatile long lastSayAt;

    private volatile String lastEventKey = "";
    private volatile long lastEventAt;
    private volatile long lastFallbackAt;

    private volatile boolean busy;
    private volatile long busyAt;

    private volatile String lastError = "";
    private volatile long lastErrorAt;

    private volatile String msaCode = "";
    private volatile String msaUrl = "";
    private volatile long msaAt;
    private volatile String msaShownCode = "";
    private volatile long msaShownAt;

    /** 最近一次私聊 / 点名的玩家（brain 判断“谁在跟我说话”） */
    private volatile String lastSpeaker;

    BotSession(Socket socket) {
        this.socket = socket;
        try {
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法打开桥接输出流", e);
        }
    }

    // ---- 握手信息 ----

    String name() {
        return this.name;
    }

    String username() {
        return this.username;
    }

    String server() {
        return this.server;
    }

    /** 桥接表里的键（名字小写） */
    String key() {
        return this.name.toLowerCase(Locale.ROOT);
    }

    /** 对应的配置；先按握手名找，再按登录名找 */
    RemoteBotProfile profile() {
        RemoteBotProfile p = AiPlayerConfig.get().findRemote(this.name);
        if (p != null) {
            return p;
        }
        return this.username.isEmpty() ? null : AiPlayerConfig.get().findRemote(this.username);
    }

    /** 处理 hello：记录名字 / 登录名 / 服务器 / 版本 */
    void applyHello(JsonObject hello) {
        this.name = clean(hello.has("name") ? hello.get("name").getAsString() : "");
        this.username = clean(hello.has("username") ? hello.get("username").getAsString() : "");
        this.server = clean(hello.has("server") ? hello.get("server").getAsString() : "");
        this.version = clean(hello.has("version") ? hello.get("version").getAsString() : "");
        this.lastSeen = System.currentTimeMillis();
    }

    /** 去掉控制字符并截断，防止外部进程塞进奇怪的字符 */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < 48; i++) {
            char c = raw.charAt(i);
            if (c >= ' ' && c != 127) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    // ---- 状态 ----

    JsonObject state() {
        return this.state;
    }

    long stateAt() {
        return this.stateAt;
    }

    long createdAt() {
        return this.createdAt;
    }

    long lastSeen() {
        return this.lastSeen;
    }

    void onState(JsonObject state) {
        this.state = state;
        this.stateAt = System.currentTimeMillis();
        this.lastSeen = this.stateAt;
    }

    /** 收到任何消息都算心跳 */
    void touch() {
        this.lastSeen = System.currentTimeMillis();
    }

    // ---- 聊天 / 事件 ----

    void addChat(String line) {
        synchronized (this.chatHistory) {
            this.chatHistory.addLast(line);
            while (this.chatHistory.size() > CHAT_HISTORY) {
                this.chatHistory.removeFirst();
            }
        }
    }

    List<String> chatHistory() {
        synchronized (this.chatHistory) {
            return new ArrayList<>(this.chatHistory);
        }
    }

    void addEvent(String line) {
        synchronized (this.events) {
            this.events.addLast(line);
            while (this.events.size() > EVENT_HISTORY) {
                this.events.removeFirst();
            }
        }
    }

    List<String> events() {
        synchronized (this.events) {
            return new ArrayList<>(this.events);
        }
    }

    /** 自己是否最近说过同一句话（去掉标点后比较），防止复读 */
    synchronized boolean canSay(String text) {
        String norm = normalize(text);
        if (norm.isEmpty()) {
            return false;
        }
        for (String old : this.recentSaid) {
            if (old.equals(norm)) {
                return false;
            }
        }
        this.recentSaid.addLast(norm);
        while (this.recentSaid.size() > SAID_HISTORY) {
            this.recentSaid.removeFirst();
        }
        return true;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s，。,.!！?？~～、]", "").trim();
    }

    // ---- 回复节流 ----

    long lastLlmReplyAt() {
        return this.lastLlmReplyAt;
    }

    void markLlmReply(boolean ownerChannel) {
        this.lastLlmReplyAt = System.currentTimeMillis();
        this.lastLlmOwnerChannel = ownerChannel;
    }

    boolean lastLlmOwnerChannel() {
        return this.lastLlmOwnerChannel;
    }

    /** 距上次主动发言是否已经够久（brain 的 say 用它限频） */
    synchronized boolean allowSay(long minGapMs) {
        long now = System.currentTimeMillis();
        if (now - this.lastSayAt < minGapMs) {
            return false;
        }
        this.lastSayAt = now;
        return true;
    }

    long lastFallbackAt() {
        return this.lastFallbackAt;
    }

    void markFallback() {
        this.lastFallbackAt = System.currentTimeMillis();
    }

    /** 同一条事件 60 秒内只播报一次 */
    synchronized boolean acceptEvent(String line) {
        long now = System.currentTimeMillis();
        if (line != null && line.equals(this.lastEventKey) && now - this.lastEventAt < 60000L) {
            return false;
        }
        this.lastEventKey = line == null ? "" : line;
        this.lastEventAt = now;
        return true;
    }

    // ---- 大模型决策节流 ----

    boolean busy() {
        return this.busy;
    }

    void markBusy() {
        this.busyAt = System.currentTimeMillis();
        this.busy = true;
    }

    long busyAt() {
        return this.busyAt;
    }

    // ---- 错误 ----

    void setError(String error) {
        this.lastError = error == null ? "" : error;
        this.lastErrorAt = System.currentTimeMillis();
    }

    void clearError() {
        this.lastError = "";
    }

    /** 最近 2 分钟内的错误（给界面显示） */
    String recentError() {
        return !this.lastError.isEmpty() && System.currentTimeMillis() - this.lastErrorAt <= 120000L
                ? this.lastError : "";
    }

    // ---- 微软登录（外部进程发来的验证码 / 链接） ----

    void setMsa(String code, String url) {
        this.msaCode = code == null ? "" : code.trim();
        this.msaUrl = url != null && !url.isBlank() ? url.trim() : "https://microsoft.com/link";
        this.msaAt = System.currentTimeMillis();
    }

    /** 待展示的验证码（10 分钟内有效） */
    String[] msa() {
        String code = this.msaCode;
        if (code.isEmpty()) {
            return null;
        }
        return System.currentTimeMillis() - this.msaAt > 600000L ? null : new String[]{code, this.msaUrl};
    }

    void markMsaShown(String code) {
        this.msaShownCode = code == null ? "" : code;
        this.msaShownAt = System.currentTimeMillis();
    }

    /** 需要弹窗的新验证码：和上次展示的不同、且距上次展示超过 45 秒 */
    String[] pendingMsa() {
        String[] msa = msa();
        if (msa == null || msa[0].equals(this.msaShownCode)) {
            return null;
        }
        if (System.currentTimeMillis() - this.msaShownAt < 45000L) {
            return null;
        }
        markMsaShown(msa[0]);
        return msa;
    }

    void clearMsa() {
        this.msaCode = "";
        this.msaUrl = "";
        this.msaShownCode = "";
    }

    boolean hasFreshMsa() {
        return !this.msaCode.isEmpty() && System.currentTimeMillis() - this.msaAt <= 600000L;
    }

    // ---- 连接状态 ----

    boolean isOpen() {
        return !this.closed && !this.socket.isClosed();
    }

    boolean isClosedFlag() {
        return this.closed;
    }

    // ---- 发送 ----

    void send(JsonObject message) {
        if (!isOpen()) {
            return;
        }
        String line = message.toString();
        synchronized (this.writeLock) {
            try {
                this.out.write(line);
                this.out.write(10);
                this.out.flush();
            } catch (IOException e) {
                close();
            }
        }
    }

    void say(String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "say");
        msg.addProperty("text", text == null ? "" : text);
        send(msg);
    }

    void commandGoto(String action, int x, int y, int z) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "command");
        msg.addProperty("action", action);
        msg.addProperty("x", x);
        msg.addProperty("y", y);
        msg.addProperty("z", z);
        send(msg);
    }

    void commandTarget(String action, String target) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "command");
        msg.addProperty("action", action);
        msg.addProperty("target", target == null ? "" : target);
        send(msg);
    }

    void commandTargetPos(String action, String target, int x, int y, int z) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "command");
        msg.addProperty("action", action);
        msg.addProperty("target", target == null ? "" : target);
        msg.addProperty("x", x);
        msg.addProperty("y", y);
        msg.addProperty("z", z);
        send(msg);
    }

    void commandAction(String action) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "command");
        msg.addProperty("action", action);
        send(msg);
    }

    void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            this.socket.close();
        } catch (IOException ignored) {
        }
    }

    /** 启动读线程：逐行读 JSON，交给 Bridge 在主线程消费 */
    void startReader() {
        Thread t = new Thread(this::readLoop, "aiplayer-bot-" + this.createdAt);
        t.setDaemon(true);
        t.start();
    }

    private void readLoop() {
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(this.socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!this.closed && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.length() > MAX_LINE) {
                        BotBridge.enqueue(this, null, "消息超过长度上限");
                        continue;
                    }
                    JsonObject message;
                    try {
                        message = JsonParser.parseString(line).getAsJsonObject();
                    } catch (RuntimeException e) {
                        BotBridge.enqueue(this, null, "JSON 解析失败");
                        continue;
                    }
                    touch();
                    BotBridge.enqueue(this, message, null);
                }
            } catch (IOException ignored) {
            }
        } finally {
            close();
            BotBridge.onSessionClosed(this);
        }
        AiPlayerMod.LOGGER.info("[aiplayer] 外部 bot 会话结束（{}）", this.name);
    }

    // ---- 发言人 ----

    String lastSpeaker() {
        return this.lastSpeaker;
    }

    void setLastSpeaker(String speaker) {
        this.lastSpeaker = speaker;
    }
}
