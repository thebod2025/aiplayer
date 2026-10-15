package com.aiplayer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.aiplayer.AiPlayerMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    /** 全局默认装备包（JSON 完整背包快照）：AI 档案没配自己的 kit 时用它 */
    public String defaultKitNbt = "";
    public double temperature = 0.7D;
    public int maxTokens = 400;
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

    /** AI 默认强度（生成新 AI 时使用） */
    public int defaultLevelId = 2;

    /** 所有 AI 的配置 */
    public List<AiProfile> profiles = new CopyOnWriteArrayList<>();

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
                    INSTANCE = cfg;
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

    public void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AiPlayerMod.LOGGER.warn("[aiplayer] 配置保存失败: {}", e.toString());
        }
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
}