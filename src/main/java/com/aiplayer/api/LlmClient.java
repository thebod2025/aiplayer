package com.aiplayer.api;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容的 LLM 客户端（java.net.http，异步，不阻塞服务器线程）。
 * <p>
 * 稳定性方面的处理：
 * <ul>
 *   <li>连接 / 读取都有超时，超时、429、5xx 会自动重试一次（带退避），4xx 不重试；</li>
 *   <li>强制 HTTP/1.1 + 跟随重定向，兼容各种自建网关；</li>
 *   <li>把 HTTP 状态码包进 {@link LlmException}，上层可以据此 tellraw 提示（401 Key 无效 / 404 路径不对 / 429 限流）；</li>
 *   <li>响应体不是 JSON 或者没有 choices 时不会抛异常，而是返回原文；</li>
 *   <li>提示词过长会自动截断，避免 400。</li>
 * </ul>
 */
public final class LlmClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 总尝试次数（含第一次） */
    private static final int MAX_ATTEMPTS = 2;
    /** 重试前等待 */
    private static final long RETRY_DELAY_MS = 1200L;
    /** 提示词最大长度（字符） */
    private static final int MAX_PROMPT_CHARS = 6000;
    /**
     * 已确认不支持 {@code response_format=json_object} 的模型（小写）。
     * <p>
     * 以前被 400 拒绝时是把全局配置 {@code config.jsonMode} 改成 false 并写盘 —— 那会让
     * <b>所有</b>模型都永久降级成普通模式，之后换成支持 json 的模型也享受不到（而且配置被改写，
     * 玩家不改文件就回不来了）。现在只记住"是哪个模型"不支持，换模型仍然走 json 模式。
     */
    private static final java.util.Set<String> JSON_UNSUPPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 当前在途请求数：用来给全服 LLM 调用做并发闸门 */
    private static final java.util.concurrent.atomic.AtomicInteger INFLIGHT =
            new java.util.concurrent.atomic.AtomicInteger();

    private LlmClient() {
    }

    /** 调用失败（含 HTTP 状态码），方便上层分类提示 */
    public static final class LlmException extends RuntimeException {
        public final int status;
        /** 服务端 Retry-After 指定的等待毫秒数，取不到就是 0 */
        public final long retryAfterMs;

        public LlmException(int status, String message) {
            this(status, message, 0L);
        }

        public LlmException(int status, String message, long retryAfterMs) {
            super(message);
            this.status = status;
            this.retryAfterMs = retryAfterMs;
        }

        public boolean unauthorized() {
            return this.status == 401 || this.status == 403;
        }

        public boolean notFound() {
            return this.status == 404;
        }

        public boolean rateLimited() {
            return this.status == 429;
        }

        /** 402：账户余额不足（DeepSeek 免费额度过期就是这一条） */
        public boolean noBalance() {
            return this.status == 402;
        }

        /** 值得重试：限流 / 服务端错误 / 网络层错误（status=0） */
        public boolean retriable() {
            return this.status == 0 || this.status == 429 || this.status >= 500;
        }
    }

    /** 询问模型，返回模型输出的纯文本（一般是 JSON） */
    public static CompletableFuture<String> ask(AiProfile profile, String systemPrompt, String userPrompt) {
        return ask(profile, systemPrompt, userPrompt, false);
    }

    /**
     * 询问模型，可选择走“聊天 API”。
     * <p>
     * 双 API 关闭时 {@code chatChannel} 完全无效，仍然走主 API，行为和以前一模一样；
     * 开启后只把“回复玩家”的请求切到聊天 API，自主行动仍走主 API。
     * 两路只是换了一套 key / 地址 / 模型，提示词、对话记忆与状态仍是同一份，所以是同步的。
     *
     * @param chatChannel true = 本轮是回复玩家，用聊天 API（若双 API 开启）
     */
    public static CompletableFuture<String> ask(AiProfile profile, String systemPrompt, String userPrompt,
                                               boolean chatChannel) {
        AiPlayerConfig config = AiPlayerConfig.get();
        // API Key 从网页复制时经常带着换行 / 制表符 / 全角空格，直接塞进 HTTP 头会被
        // HttpRequest.Builder.header 拒绝并抛 IllegalArgumentException —— 那个异常会让上层的
        // llmBusy 永远卡在 true，之后每轮都走本地兜底，表现就是"换了模型也还是不说话"。
        boolean useChatApi = chatChannel && config.dualApiFor(profile);
        String key = sanitize(useChatApi ? config.chatKeyFor(profile) : config.keyFor(profile));
        String model = sanitize(useChatApi ? config.chatModelFor(profile) : config.modelFor(profile));
        String baseUrl = sanitize(useChatApi ? config.chatBaseUrlFor(profile) : config.baseUrlFor(profile));
        if (key == null || key.isBlank()) {
            return CompletableFuture.failedFuture(new LlmException(401,
                    useChatApi ? "没有配置聊天 API Key" : "没有配置 API Key"));
        }
        if (isNativeAnthropic(baseUrl)) {
            // Anthropic 原生接口是 /v1/messages + x-api-key 的另一套协议，不是 OpenAI 兼容格式。
            // 与其拼出一个注定 404 的地址，不如直接告诉用户该填什么。
            return CompletableFuture.failedFuture(new LlmException(404,
                    "这是 Anthropic 原生接口，本模组只支持 OpenAI 兼容端点（地址结尾填到 /v1 即可）"));
        }
        try {
            // 只做一次地址合法性预检，避免每次重试都在同样的非法 URI 上失败
            URI.create(endpoint(baseUrl));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new LlmException(404, "API 地址不合法：" + baseUrl));
        }
        // 全服并发闸门：AI 数量一多，单只 AI 的间隔限制挡不住总请求量。
        // 超限就放弃这一轮（AI 继续按本地规则行动），比排队堆积、集体超时更好。
        int limit = Math.max(1, config.maxConcurrentLlm);
        if (INFLIGHT.incrementAndGet() > limit) {
            INFLIGHT.decrementAndGet();
            return CompletableFuture.failedFuture(new LlmException(429,
                    "全服同时进行的 AI 请求已达上限（" + limit + "），本轮跳过"));
        }
        return attempt(config, baseUrl, key, model, systemPrompt, userPrompt,
                config.jsonMode && !JSON_UNSUPPORTED.contains(lower(model)), 0)
                .whenComplete((reply, error) -> INFLIGHT.decrementAndGet());
    }

    private static String lower(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    private static CompletableFuture<String> attempt(AiPlayerConfig config, String baseUrl, String key, String model,
                                                     String systemPrompt, String userPrompt,
                                                     boolean jsonMode, int attempt) {
        HttpRequest request;
        try {
            request = buildRequest(config, baseUrl, key, model, systemPrompt, userPrompt, jsonMode);
        } catch (Throwable t) {
            // 参数里混进非法字符（API Key 里的换行、Header 值里的控制字符等）会在建请求时抛异常。
            // 必须在这里转成失败的 Future，否则异常会同步冒泡出去，上层永远等不到回调。
            return CompletableFuture.failedFuture(new LlmException(400, "请求构造失败：" + t.getMessage()));
        }
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(LlmClient::parse)
                .exceptionallyCompose(error -> {
                    Throwable cause = unwrap(error);
                    // 网关不支持 response_format 时会回 400：去掉该字段重试一次。
                    // 只记下"这个模型"不支持，不动全局配置 —— 换模型仍然能用 json 模式。
                    if (jsonMode && cause instanceof LlmException le && le.status == 400) {
                        JSON_UNSUPPORTED.add(lower(model));
                        AiPlayerMod.LOGGER.warn("[aiplayer] 模型 {} 拒绝 response_format=json_object，"
                                + "本次改用普通模式重试（其它模型不受影响）", model);
                        return attempt(config, baseUrl, key, model, systemPrompt, userPrompt, false, attempt);
                    }
                    boolean retriable = !(cause instanceof LlmException le) || le.retriable();
                    if (attempt + 1 < MAX_ATTEMPTS && retriable) {
                        // 限流时优先按服务端 Retry-After 等待，比固定退避更不容易再撞 429
                        long delay = cause instanceof LlmException le && le.retryAfterMs > 0
                                ? le.retryAfterMs : RETRY_DELAY_MS;
                        return CompletableFuture
                                .supplyAsync(() -> Boolean.TRUE,
                                        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                                .thenCompose(ignored -> attempt(config, baseUrl, key, model, systemPrompt,
                                        userPrompt, jsonMode, attempt + 1));
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    /** 组装请求体；{@code jsonMode} 决定要不要带 response_format */
    private static HttpRequest buildRequest(AiPlayerConfig config, String baseUrl, String key, String model,
                                            String systemPrompt, String userPrompt, boolean jsonMode) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", config.temperature);
        if (usesMaxCompletionTokens(model)) {
            // GPT-5 / o 系列只认 max_completion_tokens，传老参数会被 400 拒掉
            body.addProperty("max_completion_tokens", config.maxTokens);
        } else {
            body.addProperty("max_tokens", config.maxTokens);
        }
        JsonArray messages = new JsonArray();
        messages.add(message("system", truncate(systemPrompt)));
        messages.add(message("user", truncate(userPrompt)));
        body.add("messages", messages);
        if (jsonMode) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint(baseUrl)))
                .timeout(Duration.ofMillis(Math.max(5000, config.httpTimeoutMs)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + key)
                .header("User-Agent", "aiplayer-mod")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    /** 新版推理模型不接受 {@code max_tokens} */
    private static boolean usesMaxCompletionTokens(String model) {
        if (model == null) {
            return false;
        }
        String m = model.trim().toLowerCase(Locale.ROOT);
        return m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") || m.startsWith("gpt-5");
    }

    /** Anthropic 原生端点（{@code /v1/messages}），不是 OpenAI 兼容格式 */
    private static boolean isNativeAnthropic(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        String url = baseUrl.trim().toLowerCase(Locale.ROOT);
        return url.contains("api.anthropic.com") || url.contains("/messages");
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private static String parse(HttpResponse<String> response) {
        int code = response.statusCode();
        String body = response.body();
        if (code / 100 != 2) {
            throw new LlmException(code, errorSummary(code, body), retryAfterMs(response));
        }
        return extractContent(body);
    }

    /**
     * 解析 429/503 常见的 {@code Retry-After} 响应头。
     * 只认“秒数”这一种写法（HTTP 日期格式直接忽略，交给默认退避），上限 30 秒防止被网关挂死。
     */
    private static long retryAfterMs(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        if (value.isEmpty()) {
            return 0L;
        }
        try {
            return Math.max(0L, Math.min(30_000L, Long.parseLong(value) * 1000L));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 把常见状态码翻译成人话，方便 tellraw 出去 */
    private static String errorSummary(int code, String body) {
        String detail = shorten(body);
        String hint = switch (code) {
            case 401, 403 -> "API Key 无效或没有权限";
            case 404 -> "接口地址不对（检查 apiBaseUrl 是否带 /v1）或模型名不存在";
            case 402 -> "API 余额不足（免费额度可能已过期，请充值或换 Key）";
            case 429 -> "请求太频繁 / 余额不足（限流）";
            case 400 -> "请求格式被拒绝（模型名或参数不对）";
            default -> code >= 500 ? "服务端错误" : "请求失败";
        };
        return "HTTP " + code + " " + hint + (detail.isEmpty() ? "" : " | " + detail);
    }

    private static String endpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            base = "https://api.openai.com/v1";
        }
        return base + "/chat/completions";
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** 从 OpenAI 兼容响应里取出正文 */
    private static String extractContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(raw);
        } catch (RuntimeException e) {
            // 不是 JSON：可能网关直接返回了文本，原样交给上层
            return raw;
        }
        if (!root.isJsonObject()) {
            return raw;
        }
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("error")) {
            throw new LlmException(400, shorten(obj.get("error").toString()));
        }
        if (!obj.has("choices")) {
            return raw;
        }
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices.size() == 0) {
            return "";
        }
        JsonObject first = choices.get(0).getAsJsonObject();
        if (first.has("message")) {
            JsonObject message = first.getAsJsonObject("message");
            // content 可能是字符串，也可能是 [{type:"text",text:"..."}] 数组（部分网关的新格式）
            String text = contentText(message.get("content"));
            if (!text.isEmpty()) {
                return text;
            }
        }
        if (first.has("text")) {
            return contentText(first.get("text"));
        }
        return "";
    }

    /**
     * 把 content 字段转成纯文本。
     * <p>
     * 兼容三种形态：字符串、{@code null}、以及
     * {@code [{"type":"text","text":"..."}]} 数组 —— 直接对整个数组调 {@code getAsString()}
     * 会抛异常，之前会被当成“网络错误”白白重试一轮。
     */
    private static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            if (part.isJsonObject()) {
                JsonElement text = part.getAsJsonObject().get("text");
                if (text != null && text.isJsonPrimitive()) {
                    sb.append(text.getAsString());
                }
            } else if (part.isJsonPrimitive()) {
                sb.append(part.getAsString());
            }
        }
        return sb.toString();
    }

    /** 去掉首尾空白与内部的控制字符（含换行），避免污染 HTTP 头 / 请求体 */
    private static String sanitize(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_PROMPT_CHARS ? s.substring(0, MAX_PROMPT_CHARS) : s;
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}