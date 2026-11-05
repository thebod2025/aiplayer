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
 * 客户端界面主题配置（强调色 / 面板底色 / 圆角 / 动画）。
 * <p>
 * 纯观感设置，跟服务端的 AI 配置（{@code aiplayer.json}）无关，单独存
 * {@code config/aiplayer-theme.json}：单人、联机、专用服务器各读各的。
 * 颜色用 RRGGBB 十六进制（不带 {@code #}），由 {@code UiTheme.refresh()} 推导出全套 token。
 */
public class ClientThemeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** 默认强调色：品牌青绿 */
    public static final String DEFAULT_ACCENT = "3BE8D8";
    /** 默认面板底色：青黑 */
    public static final String DEFAULT_PANEL = "0E1A1F";
    /** 默认圆角半径 */
    public static final int DEFAULT_RADIUS = 8;
    /** 默认遮罩浓度 */
    public static final int DEFAULT_BACKDROP = 40;
    /** 默认面板不透明度 */
    public static final int DEFAULT_PANEL_OPACITY = 100;
    /** 默认界面缩放（100 = 不额外缩放，只按屏幕自适应） */
    public static final int DEFAULT_UI_SCALE = 100;
    /** 默认阴影强度 */
    public static final int DEFAULT_SHADOW = 100;
    /** 默认卡片亮度 */
    public static final int DEFAULT_CARD_TINT = 100;
    /** 默认是否对游戏画面做全屏背景模糊（液态玻璃观感的前提，默认开） */
    public static final boolean DEFAULT_BLUR_BACKGROUND = true;
    /** 默认是否启用液态玻璃（GLSL 磨砂 + 边缘折射），默认开 */
    public static final boolean DEFAULT_LIQUID_GLASS = true;
    /** 默认是否启用圆角（关掉 = 全直角），默认开 */
    public static final boolean DEFAULT_ROUNDED_CORNERS = true;
    /** 默认液态玻璃刷新帧率（重活按这个频率重画，其余帧只贴图） */
    public static final int DEFAULT_GLASS_FPS = 25;
    /** 默认渐变起点色（青黑一档亮） */
    public static final String DEFAULT_GRADIENT_A = "17333B";
    /** 默认渐变终点色（偏紫的深色，和品牌青绿拉开层次） */
    public static final String DEFAULT_GRADIENT_B = "2A1A44";
    /** 默认渐变流动速度（0 = 静止渐变，100 = 最快） */
    public static final int DEFAULT_GRADIENT_SPEED = 35;

    private static ClientThemeConfig INSTANCE;

    /** 强调色（RRGGBB） */
    public String accent = DEFAULT_ACCENT;
    /** 面板底色（RRGGBB，建议深色以保证文字对比度） */
    public String panel = DEFAULT_PANEL;
    /** 圆角半径（0 = 直角，最大 12） */
    public int radius = DEFAULT_RADIUS;
    /** 界面动画总开关 */
    public boolean animations = true;
    /** 打开界面时压在游戏画面上的黑色遮罩浓度（0~100，默认 40） */
    public int backdrop = DEFAULT_BACKDROP;
    /** 面板不透明度（0~100，默认 100 = 完全不透明；调低能看见身后的世界） */
    public int panelOpacity = DEFAULT_PANEL_OPACITY;
    /**
     * 界面整体缩放（60~140，默认 100）。
     * <p>
     * 这个是"在屏幕自适应之上的额外缩放"：屏幕太小会自动缩小，这个值再叠上去，
     * 用于玩家嫌字小 / 嫌太大时手动微调。改完界面大小立即变化，不会错位。
     */
    public int uiScale = DEFAULT_UI_SCALE;
    /** 面板外阴影强度（0 = 完全没有阴影，默认 100） */
    public int shadow = DEFAULT_SHADOW;
    /** 分区卡片亮度（0 = 卡片全透明只剩描边，100 = 默认，200 = 明显发亮） */
    public int cardTint = DEFAULT_CARD_TINT;
    /**
     * 打开界面时是否对身后的游戏画面做全屏模糊（默认关）。
     * <p>
     * 原版 {@code Screen.extractBackground} 每帧都会跑一次全屏模糊后处理，是界面帧率的最大开销；
     * 关掉后背景保持清晰、只压一层遮罩，帧率明显回升。喜欢模糊观感可在「外观」页手动打开。
     */
    public boolean blurBackground = DEFAULT_BLUR_BACKGROUND;
    /**
     * 是否启用液态玻璃（GLSL 磨砂 + 边缘折射 + 菲涅尔）。
     * <p>
     * 关掉后回到"普通渐变面板 + CPU 圆角"的老样式，帧率开销更低。
     */
    public boolean liquidGlass = DEFAULT_LIQUID_GLASS;
    /** 是否启用圆角（关掉后所有面板 / 按钮都是直角） */
    public boolean roundedCorners = DEFAULT_ROUNDED_CORNERS;
    /**
     * 液态玻璃的刷新帧率：拷贝背景 + 全屏 SDF + 磨砂采样按这个频率重画，
     * 中间的帧只把离屏结果贴回主画面。0 = 每帧都重画（跟不限制一样）。
     */
    public int glassFps = DEFAULT_GLASS_FPS;
    /**
     * 界面渐变的两个端点色（RRGGBB）。
     * <p>
     * 面板 / 顶栏会在这两个颜色之间做一层**逐像素渐变**（GPU 顶点色插值 + SDF 圆角），
     * 面板底色仍然可见，渐变只是叠在上面的染色，所以换任何底色都不会糊成一片。
     */
    public String gradientA = DEFAULT_GRADIENT_A;
    public String gradientB = DEFAULT_GRADIENT_B;
    /**
     * 渐变流动速度（0~100）。0 = 静止的 A→B 渐变；
     * 越大两个端点色交替越快，界面会缓慢"呼吸"。
     */
    public int gradientSpeed = DEFAULT_GRADIENT_SPEED;

    public static ClientThemeConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiplayer-theme.json");
    }

    public static ClientThemeConfig load() {
        Path path = file();
        if (Files.exists(path)) {
            try {
                ClientThemeConfig cfg = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8),
                        ClientThemeConfig.class);
                if (cfg != null) {
                    if (cfg.accent == null || !isValid(cfg.accent)) {
                        cfg.accent = DEFAULT_ACCENT;
                    }
                    if (cfg.panel == null || !isValid(cfg.panel)) {
                        cfg.panel = DEFAULT_PANEL;
                    }
                    if (cfg.gradientA == null || !isValid(cfg.gradientA)) {
                        cfg.gradientA = DEFAULT_GRADIENT_A;
                    }
                    if (cfg.gradientB == null || !isValid(cfg.gradientB)) {
                        cfg.gradientB = DEFAULT_GRADIENT_B;
                    }
                    cfg.gradientSpeed = Math.max(0, Math.min(100, cfg.gradientSpeed));
                    cfg.radius = Math.max(0, Math.min(12, cfg.radius));
                    cfg.backdrop = Math.max(0, Math.min(100, cfg.backdrop));
                    cfg.panelOpacity = Math.max(0, Math.min(100, cfg.panelOpacity));
                    cfg.uiScale = Math.max(60, Math.min(140, cfg.uiScale == 0 ? DEFAULT_UI_SCALE : cfg.uiScale));
                    cfg.shadow = Math.max(0, Math.min(100, cfg.shadow));
                    cfg.cardTint = Math.max(0, Math.min(200, cfg.cardTint));
                    INSTANCE = cfg;
                    return cfg;
                }
            } catch (IOException | JsonSyntaxException e) {
                AiPlayerMod.LOGGER.warn("[aiplayer] 主题配置读取失败，使用默认配色: {}", e.toString());
            }
        }
        ClientThemeConfig cfg = new ClientThemeConfig();
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
            AiPlayerMod.LOGGER.warn("[aiplayer] 主题配置保存失败: {}", e.toString());
        }
    }

    /** 恢复默认配色 */
    public void reset() {
        this.accent = DEFAULT_ACCENT;
        this.panel = DEFAULT_PANEL;
        this.radius = DEFAULT_RADIUS;
        this.animations = true;
        this.backdrop = DEFAULT_BACKDROP;
        this.panelOpacity = DEFAULT_PANEL_OPACITY;
        this.uiScale = DEFAULT_UI_SCALE;
        this.shadow = DEFAULT_SHADOW;
        this.cardTint = DEFAULT_CARD_TINT;
        this.blurBackground = DEFAULT_BLUR_BACKGROUND;
        this.liquidGlass = DEFAULT_LIQUID_GLASS;
        this.roundedCorners = DEFAULT_ROUNDED_CORNERS;
        this.glassFps = DEFAULT_GLASS_FPS;
        this.gradientA = DEFAULT_GRADIENT_A;
        this.gradientB = DEFAULT_GRADIENT_B;
        this.gradientSpeed = DEFAULT_GRADIENT_SPEED;
    }

    /** 渐变起点 RGB；解析失败回退默认 */
    public int gradientARgb() {
        return parse(gradientA, parse(DEFAULT_GRADIENT_A, 0x17333B));
    }

    /** 渐变终点 RGB；解析失败回退默认 */
    public int gradientBRgb() {
        return parse(gradientB, parse(DEFAULT_GRADIENT_B, 0x2A1A44));
    }

    /** 当前强调色 RGB；解析失败回退默认 */
    public int accentRgb() {
        return parse(accent, 0x3BE8D8);
    }

    /** 当前面板底色 RGB；解析失败回退默认 */
    public int panelRgb() {
        return parse(panel, 0x0E1A1F);
    }

    /** RRGGBB → RGB，非法回退 fallback */
    public static int parse(String hex, int fallback) {
        if (!isValid(hex)) {
            return fallback;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        return Integer.parseInt(s, 16) & 0xFFFFFF;
    }

    /** 是否是合法的 RRGGBB（允许带 #） */
    public static boolean isValid(String hex) {
        if (hex == null) {
            return false;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** RGB → RRGGBB（大写，不带 #） */
    public static String toHex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }
}
