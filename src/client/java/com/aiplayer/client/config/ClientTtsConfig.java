package com.aiplayer.client.config;

import com.aiplayer.AiPlayerMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端本地配置（TTS 语音合成）。
 * <p>
 * 只影响本机听不听得到 AI 说话，跟服务端的 AI 配置（{@code aiplayer.json}）完全无关，
 * 所以单独存一份 {@code config/aiplayer-client.json}：单人存档、联机、专用服务器都各读各的。
 */
public class ClientTtsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static ClientTtsConfig INSTANCE;

    /** 总开关：默认关闭，想听 AI 说话自己去 GUI 里开 */
    public boolean ttsEnabled = false;
    /** 语速：SAPI 的 -10（最慢）~ 10（最快），0 为正常 */
    public int ttsRate = 0;
    /** 指定系统语音名（如 "Microsoft Huihui Desktop"）；留空用系统默认语音 */
    public String ttsVoice = "";

    public static ClientTtsConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiplayer-client.json");
    }

    public static ClientTtsConfig load() {
        Path path = file();
        if (Files.exists(path)) {
            try {
                ClientTtsConfig cfg = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8),
                        ClientTtsConfig.class);
                if (cfg != null) {
                    INSTANCE = cfg;
                    return cfg;
                }
            } catch (IOException | JsonSyntaxException e) {
                AiPlayerMod.LOGGER.warn("[aiplayer] 客户端配置读取失败，使用默认配置: {}", e.toString());
            }
        }
        ClientTtsConfig cfg = new ClientTtsConfig();
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
            AiPlayerMod.LOGGER.warn("[aiplayer] 客户端配置保存失败: {}", e.toString());
        }
    }

    /** 试听用的固定台词（按客户端语言给个能听懂的句子） */
    public String previewText() {
        // 26.2 的 LanguageManager.getSelected() 直接返回语言代码（如 "zh_cn" / "en_us"）
        String lang = net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected();
        if (lang != null && lang.startsWith("zh")) {
            return "你好，我是你的 AI 伙伴，语音合成已经开启。";
        }
        return "Hello, I am your AI companion. Text to speech is now enabled.";
    }
}
