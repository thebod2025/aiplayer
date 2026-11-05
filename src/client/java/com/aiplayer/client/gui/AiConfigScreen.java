package com.aiplayer.client.gui;

import com.aiplayer.client.config.ClientThemeConfig;
import com.aiplayer.client.config.ClientTtsConfig;
import com.aiplayer.client.skin.CslSkinWriter;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayerManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * AI Player 配置界面（默认按 K 打开）。
 * <p>
 * 三层结构：
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │ ◉ AI Player  v1.4.2                          │ 顶栏
 * ├──────────────┬───────────────────────────────┤
 * │ AI 列表 [+]  │ [档案][功能][装备][全局][外观][关于] │ 标签页
 * │ ● Steve-1    │ ┌ 卡片 ──────────────────────┐ │
 * │ 状态 ● 存活  │ │  控件…                     │ │ 内容区（可滚动）
 * │ 皮肤 [____]  │ └────────────────────────────┘ │
 * ├──────────────┴───────────────────────────────┤
 * │  [存盘] [关闭] [默认装备包] [关于]           │ 底栏
 * └──────────────────────────────────────────────┘
 * </pre>
 * 存盘策略是「全自动」：控件改动即写内存并置脏，停 0.8 秒落一次盘，底栏常驻「已自动保存 ✓」；
 * 底栏的「存盘」按钮只是立刻落盘 + 立刻同步服务端的快捷方式。
 * <p>
 * 内容区超出可视高度时用 scissor 裁剪 + 滚轮滚动；控件跟着滚动偏移走，
 * 滚出可视区的控件直接 {@code visible=false}（控件本身不受 scissor 影响）。
 * <p>
 * 视觉全部走 {@link UiTheme}，文本全部走翻译键（{@code aiplayer.gui.*}）。
 */
public class AiConfigScreen extends Screen {
    // ---- 面板几何 ----
    private static final int PANEL_W = 500;
    private static final int SIDEBAR_W = 118;
    /** 顶栏（品牌带）高度 */
    private static final int TOP_H = 28;
    /** 标签页高度 */
    private static final int TAB_H = 24;
    /** 标签页宽度 / 间距（8 个标签要挤进 374px 宽的标签带：8×45 + 7×2 = 374） */
    private static final int TAB_W = 45;
    private static final int TAB_GAP = 2;
    /** 底栏高度 */
    private static final int BOTTOM_H = 28;
    private static final int PAD = 10;
    private static final int ROW_H = 16;
    private static final int LABEL_W = 44;
    /**
     * 模型预设：{ 显示名, 接口地址, 模型名 }。
     * <p>
     * 只是把两个输入框一次填好，仍然可以手动改 —— 地址都是兼容 OpenAI 协议的
     * {@code /v1/chat/completions}，所以填完直接能用。
     */
    private static final String[][] MODEL_PRESETS = {
            {"DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash"},
            {"OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"},
            {"Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"},
            {"智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3-flash"},
            {"Ollama", "http://localhost:11434/v1", "qwen2.5:7b"},
    };
    private static final int MAX_PANEL_H = 440;
    /** 左侧 AI 列表每行高度 */
    private static final int LIST_ROW_H = 18;
    /** 嘲讽列表最多几行 */
    private static final int MAX_TAUNT_ROWS = 6;
    /** 内容区右侧预留的滚动条宽度 */
    private static final int SCROLLBAR_W = 6;

    private static final String GITHUB_URL = "https://github.com/thebod2025/aiplayer";
    private static final String MODRINTH_URL = "https://modrinth.com/mod/aiplayerawa";

    /** 功能开关分组：[0] 是分组翻译键，其后是这个组里的功能键 */
    private static final String[][] GROUPS = {
            {"survival", "mine", "place", "eat", "equip"},
            {"combat", "combat", "ranged", "escape", "throwables", "taunt", "hunt"},
            {"pvp", "crystalPvp", "anchorPvp", "windMace", "elytraSpear", "boss"},
            {"move", "bridge", "autoBridge", "explore", "fishing"},
            {"social", "chat", "command", "farm", "tame"},
    };
    private static final int CHIP_COLS = 3;
    private static final int CHIP_H = 16;
    private static final int CHIP_ROW = 18;

    /** 9 个标签页的翻译键后缀（批量生成、AI 列表都是独立页，关于页放最后） */
    private static final String[] TAB_KEYS = {"profile", "features", "kit", "global", "style", "batch", "list", "about", "compat"};
    /** 「枪械」页在 TAB_KEYS 里的下标：装没装 TaCZ 决定这一页显不显示 */
    private static final int COMPAT_TAB = 8;

    /** 当前有没有"需要显示枪械页"的兼容模组（目前是 TaCZ） */
    private static boolean compatAvailable() {
        return com.aiplayer.compat.TaczCompat.loaded();
    }
    /** 强调色预设色板（覆盖常见的几种品牌配色） */
    private static final int[] ACCENT_PRESETS = {
            0x3BE8D8, 0x5AA9FF, 0x9D7BFF, 0xFF8A65, 0xFF5C7A, 0x7BE06A, 0xFFD166, 0xE8E8EA};
    /** 面板底色预设（都是深色，保证任何强调色下文字都有对比度） */
    private static final int[] PANEL_PRESETS = {
            0x0E1A1F, 0x12141C, 0x1A1420, 0x1C1614, 0x101010};
    /** 圆角半径预设（对应 小 / 中 / 大 / 圆润 四档） */
    private static final int[] RADIUS_PRESETS = {2, 6, 8, 12};
    /** 渐变起点预设（冷色为主，配默认青绿强调色不打架） */
    private static final int[] GRADIENT_A_PRESETS = {
            0x17333B, 0x0E2A4A, 0x123A2A, 0x2A1A44, 0x1E1E2E, 0x3A1220};
    /** 渐变终点预设（比起点更暗 / 更偏紫红，保证纵向有层次） */
    private static final int[] GRADIENT_B_PRESETS = {
            0x2A1A44, 0x123A4F, 0x0E3A34, 0x2E1430, 0x203050, 0x421A2A};

    private final Screen parent;

    private AiPlayerConfig config;
    private int index;
    /** 同步 UI 时置位，避免 setValue 反过来把值写进别的档案 */
    private boolean sync;

    /** 当前标签页（0 档案 / 1 功能 / 2 装备 / 3 全局 / 4 外观 / 5 关于） */
    private int tab;
    /** 内容区滚动偏移（每 tick 平滑逼近 scrollTarget） */
    private float scroll;
    private float scrollTarget;
    /** 左侧 AI 列表滚动偏移 */
    private float listScroll;
    private float listScrollTarget;
    /** 滚动后若干 tick 内滚动条全亮，停下来后淡下去 */
    private int scrollTimer;
    /** 当前帧的鼠标位置（侧栏行 hover 高亮用） */
    private int mouseX;
    private int mouseY;
    private final UiTheme.Anim scrollbarFade = new UiTheme.Anim(1f);
    /** 活动标签指示条的滑动位置（浮点标签序号） */
    private final UiTheme.Anim tabPos = new UiTheme.Anim(0f);
    /** 当前页签实际宽度（layoutTabs 每帧按可见数量重算，指示条必须跟着它走） */
    private int tabW = TAB_W;
    /** 打开界面的入场进度 0→1 */
    private final UiTheme.Anim openFade = new UiTheme.Anim(0f);
    private int contentTotalH;
    private int listTotalH;

    // ---- 计算出来的几何 ----
    private int left;
    private int top;
    private int panelH;
    private int contentX;
    private int contentY;
    private int contentW;
    private int contentH;
    private int sidebarBottom;

    // ---- 控件 ----
    /** 随标签页显示 / 隐藏的控件 */
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    private final List<ToggleChip> chips = new ArrayList<>();
    private final FlatButton[] tabButtons = new FlatButton[TAB_KEYS.length];
    private final FlatButton[] bottomButtons = new FlatButton[4];

    private EditBox baseUrlBox;
    private EditBox apiKeyBox;
    private EditBox modelBox;
    // ---- 双 API：聊天 API 单独一套配置 ----
    private EditBox chatUrlBox;
    private EditBox chatKeyBox;
    private EditBox chatModelBox;
    private FlatButton dualApiButton;
    private FlatButton testChatButton;
    private boolean testingChat;
    /** 模型预设一键填充按钮（DeepSeek / OpenAI / Kimi / 智谱 / Ollama） */
    private final FlatButton[] presetButtons = new FlatButton[MODEL_PRESETS.length];
    private EditBox nameBox;
    private EditBox followBox;
    /** 跟随目标的建议按钮：点一下在 "无 → 在线玩家…" 之间循环，省得手打名字 */
    private FlatButton followSuggestButton;
    private EditBox teamBox;
    private EditBox skinBox;
    private EditBox kitBox;
    private EditBox speedBox;
    private EditBox personalityBox;

    private FlatButton llmButton;
    /** 全局页「测试连接」：直接发一个最小请求，把结果写在按钮上 */
    private FlatButton testConnButton;
    private boolean testingConn;
    private FlatButton levelButton;
    private FlatButton bridgeButton;
    private FlatButton ttsButton;
    private FlatButton ttsPreviewButton;
    private FlatButton ttsVoiceButton;
    // ---- 队伍对战（全局页）：参战队伍名单 + 全员混战开关 ----
    private FlatButton duelButton;
    private EditBox warTeamsBox;

    // ---- 枪械页（只在装了 TaCZ 时出现，所以这些控件允许为 null 但不影响别的页）----
    private ToggleChip gunChip;
    private ToggleChip gunKitChip;
    private ToggleChip gunAimChip;
    private ToggleChip gunAmmoChip;
    private EditBox gunRangeBox;
    private FlatButton kitChestButton;
    private FlatButton defaultKitButton;
    private FlatButton spawnButton;
    private FlatButton deleteButton;
    private FlatButton memoryButton;
    private FlatButton newAiButton;
    private FlatButton tauntAddButton;
    private FlatButton githubButton;
    private FlatButton modrinthButton;
    /** 「关于」页的"重看新手教学" */
    private FlatButton tutorialButton;
    // ---- 批量生成：逐行配置（名称 / 强度 / 皮肤），一次全部生成 ----
    private static final int MAX_BATCH_ROWS = 6;
    private final EditBox[] batchNameBoxes = new EditBox[MAX_BATCH_ROWS];
    private final EditBox[] batchSkinBoxes = new EditBox[MAX_BATCH_ROWS];
    private final FlatButton[] batchLevelButtons = new FlatButton[MAX_BATCH_ROWS];
    private EditBox batchTeamBox;
    /** 批量命名模板，例如 {@code 红队{n}}，点右侧按钮一次填满所有行 */
    private EditBox batchTemplateBox;
    private FlatButton batchTemplateButton;
    /** 「全部移除」的两段式确认：第一次点击只记时间，3 秒内再点才执行 */
    private long removeAllArmedAt;
    /** 按钮当前是否显示着"再点一次确认"，到点后要恢复文案 */
    private boolean removeAllLabelArmed;
    // ---- AI 列表页：搜索 + 选中档案的整体操作 ----
    private EditBox listSearchBox;
    private FlatButton listEditButton;
    private FlatButton listTpButton;
    private FlatButton listKeepButton;
    private FlatButton listRemoveButton;
    private FlatButton listRemoveAllButton;
    /** 当前搜索词（只影响列表页与左侧列表的显示，不改档案） */
    private String listQuery = "";
    /** 列表页每一行的可点击区域：[x1, y1, x2, y2, 档案下标] */
    private final java.util.List<int[]> aiRows = new java.util.ArrayList<>();
    /** 每一行的强度等级（1~4，点按钮循环） */
    private final int[] batchLevels = new int[MAX_BATCH_ROWS];
    /** 当前显示几行（1~MAX_BATCH_ROWS） */
    private int batchRows = 3;
    private FlatButton batchLessButton;
    private FlatButton batchMoreButton;
    private FlatButton batchSpawnButton;
    private FlatButton batchRunButton;

    // ---- 外观页控件 ----
    private final ColorSwatch[] accentSwatches = new ColorSwatch[ACCENT_PRESETS.length];
    private final ColorSwatch[] panelSwatches = new ColorSwatch[PANEL_PRESETS.length];
    private final ColorSwatch[] gradientASwatches = new ColorSwatch[GRADIENT_A_PRESETS.length];
    private final ColorSwatch[] gradientBSwatches = new ColorSwatch[GRADIENT_B_PRESETS.length];
    /** 渐变两端色的自定义 HEX 输入框（预设色板之外想用任意颜色时用） */
    private EditBox gradientAHexBox;
    private EditBox gradientBHexBox;
    private final FlatButton[] radiusButtons = new FlatButton[RADIUS_PRESETS.length];
    private FlatButton gradientSpeedMinusButton;
    private FlatButton gradientSpeedPlusButton;
    private FlatButton animButton;
    private FlatButton blurButton;
    /** 液态玻璃 / 圆角总开关 */
    private FlatButton glassButton;
    private FlatButton roundButton;
    /** 液态玻璃刷新帧率 */
    private FlatButton glassFpsButton;
    private FlatButton randomAccentButton;
    private FlatButton themeResetButton;
    private EditBox accentHexBox;
    /** 遮罩浓度 / 面板不透明度 / 界面缩放 / 阴影 / 卡片亮度：− + 各一对 */
    private FlatButton backdropMinusButton;
    private FlatButton backdropPlusButton;
    private FlatButton opacityMinusButton;
    private FlatButton opacityPlusButton;
    private FlatButton uiScaleMinusButton;
    private FlatButton uiScalePlusButton;
    private FlatButton shadowMinusButton;
    private FlatButton shadowPlusButton;
    private FlatButton cardMinusButton;
    private FlatButton cardPlusButton;

    /** 嘲讽列表编辑器：每行一个输入框 + 一个删除按钮 */
    private final EditBox[] tauntBoxes = new EditBox[MAX_TAUNT_ROWS];
    private final FlatButton[] tauntRemove = new FlatButton[MAX_TAUNT_ROWS];
    private int tauntCount;

    // ---- 每帧重算的绘制数据（保证控件位置与卡片 / 文字绝对对齐）----
    private final List<int[]> cards = new ArrayList<>();
    private final List<Object[]> texts = new ArrayList<>();
    private final List<EditBox> drawFields = new ArrayList<>();
    /** 左侧 AI 列表每行的可点击矩形：{x1, y1, x2, y2, profileIndex} */
    private final List<int[]> listRows = new ArrayList<>();

    /**
     * 皮肤热更的防抖状态：输入框逐字符回调，每敲一个字都重建 AI 太浪费，
     * 所以停下 1 秒后再应用一次。
     */
    private String skinPendingName;
    private String skinPendingValue;
    private int skinPendingTicks;
    private String skinAppliedValue = "";

    /** 自动存盘：改动后停 0.8 秒落盘一次 */
    private long dirtyAt;

    /**
     * 自动缩放：当前 GUI 缩放换算出的屏幕装不下 500 宽的面板时（高分屏 3x/4x 缩放 +
     * 小窗口），把整个界面按比例缩小绘制，避免按钮和文字被屏幕边缘裁掉。
     * 布局照旧在"虚拟坐标"里进行，渲染时整体乘 uiScale，鼠标坐标反向换算。
     */
    private float uiScale = 1.0F;
    /**
     * 布局用的"虚拟画布"尺寸（= 真实屏幕尺寸 / uiScale）。
     * <p>
     * 注意：这里<b>绝不</b>去改 {@code this.width / this.height} —— 那两个字段是 Screen 基类
     * 跟原版管线共用的（工具提示夹紧、scissor、mouseMoved 分发都用它），改了会让原版拿到
     * 一个对不上的尺寸，表现就是缩放错位、面板外冒出"脏东西"、文字互相重叠。
     * 我们只在自己的布局 / 绘制里用 layoutW / layoutH。
     */
    private int layoutW;
    private int layoutH;

    public AiConfigScreen(Screen parent) {
        super(Component.translatable("aiplayer.gui.title"));
        this.parent = parent;
    }

    /** 直接打开指定页签（新手教学点「去配置 API」时跳「全局」页） */
    public AiConfigScreen(Screen parent, int initialTab) {
        this(parent);
        this.tab = initialTab;
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        // ---- 自动缩放：面板 + 边距装不下就整体缩小，布局在虚拟画布里照常算 ----
        float realW = this.width;
        float realH = this.height;
        float s = Math.min(realW / (PANEL_W + 16.0F), realH / 300.0F);
        if (s < 0.999F) {
            s = Math.max(0.5F, s);
        } else {
            s = 1.0F;
        }
        // 外观页里的「界面缩放」是在屏幕自适应之上再乘一档，用于手动放大 / 缩小
        s *= Math.max(0.6F, Math.min(1.4F, UiTheme.UI_SCALE / 100f));
        // 放大档（>100%）也要生效，所以上下都夹住；太小会让布局算出 0 宽
        this.uiScale = Math.max(0.4F, Math.min(2.0F, s));
        // 关键：不动 this.width / this.height，只算自己的虚拟画布
        this.layoutW = Math.round(realW / this.uiScale);
        this.layoutH = Math.round(realH / this.uiScale);
        this.config = AiPlayerConfig.get();
        if (this.config.profiles.isEmpty()) {
            this.config.profiles.add(new AiProfile());
        }
        if (this.index < 0 || this.index >= this.config.profiles.size()) {
            this.index = 0;
        }
        this.contentWidgets.clear();
        this.chips.clear();
        geometry();

        // ---- 顶部标签页 ----
        for (int i = 0; i < TAB_KEYS.length; i++) {
            final int t = i;
            this.tabButtons[i] = content(new FlatButton(0, 0, 62, TAB_H - 4,
                    Component.translatable("aiplayer.gui.tab." + TAB_KEYS[i]), () -> switchTab(t)));
        }

        // ---- 左侧 AI 列表的「+」 ----
        this.newAiButton = content(new FlatButton(0, 0, 22, ROW_H,
                Component.literal("+"), this::newProfile).icon(icon(Items.PLAYER_HEAD)));

        // ---- 侧栏底部：皮肤 ----
        AiProfile profile = current();
        this.skinBox = makeField(0, 0, SIDEBAR_W - 48, profile.skinName,
                Component.translatable("aiplayer.gui.hint.skinShort"), v -> {
                    String skin = v.trim();
                    AiProfile cur = current();
                    cur.skinName = skin;
                    markDirty();
                    // 防抖：先在 tick() 里等 1 秒无输入，再真正热更
                    this.skinPendingName = cur.name;
                    this.skinPendingValue = skin;
                    this.skinPendingTicks = 20;
                }, null);
        this.addRenderableWidget(this.skinBox);
        this.skinAppliedValue = profile.skinName == null ? "" : profile.skinName;

        this.sync = true;
        // ---- 档案页 ----
        int inner = contentInset();
        this.nameBox = contentField(inner + LABEL_W, 0, 150, profile.name,
                Component.translatable("aiplayer.gui.hint.name"), v -> {
                    current().name = v.trim();
                    markDirty();
                });
        this.nameBox.setMaxLength(16);
        this.teamBox = contentField(inner + LABEL_W, 0, 150, profile.team,
                Component.translatable("aiplayer.gui.hint.team"), v -> {
                    current().team = v.trim();
                    markDirty();
                });
        this.followBox = contentField(inner + LABEL_W, 0, 150, profile.follow,
                Component.translatable("aiplayer.gui.hint.follow"), v -> {
                    current().follow = v.trim();
                    markDirty();
                });
        this.speedBox = contentField(0, 0, 96, String.valueOf(profile.bridgeSpeed),
                Component.translatable("aiplayer.gui.hint.speed"), v -> {
                    try {
                        current().bridgeSpeed = Double.parseDouble(v.trim());
                        markDirty();
                    } catch (NumberFormatException ignored) {
                        // 输入到一半的非法数字直接忽略，不要写进配置
                    }
                });
        this.followSuggestButton = content(new FlatButton(0, 0, 44, ROW_H,
                Component.literal("⇄"), this::cycleFollow)
                .icon(icon(Items.PLAYER_HEAD)));
        this.personalityBox = contentField(inner + LABEL_W, 0, 200, profile.personality,
                Component.translatable("aiplayer.gui.hint.personality"), v -> {
                    current().personality = v.trim();
                    markDirty();
                });
        this.levelButton = content(new FlatButton(0, 0, 140, ROW_H,
                Component.translatable("aiplayer.gui.label.level"), () -> {
                    AiProfile p = current();
                    p.levelId = p.levelId % 4 + 1;
                    markDirty();
                    refresh();
                }));
        this.bridgeButton = content(new FlatButton(0, 0, 140, ROW_H,
                Component.translatable("aiplayer.gui.label.bridge"), () -> {
                    AiProfile p = current();
                    p.bridgeMode = (p.bridgeMode + 1) % 5;
                    markDirty();
                    refresh();
                }));
        for (int i = 0; i < MAX_TAUNT_ROWS; i++) {
            final int idx = i;
            this.tauntBoxes[i] = contentField(0, 0, 200, "",
                    Component.translatable("aiplayer.gui.hint.tauntRow"), v -> applyTaunts());
            this.tauntRemove[i] = content(new FlatButton(0, 0, 24, ROW_H,
                    Component.literal("−"), () -> removeTaunt(idx)).icon(icon(Items.BARRIER)));
        }
        this.tauntAddButton = content(new FlatButton(0, 0, 120, ROW_H,
                Component.translatable("aiplayer.gui.button.tauntAdd"), this::addTaunt).icon(icon(Items.PAPER)));
        this.spawnButton = content(new FlatButton(0, 0, 100, ROW_H,
                Component.translatable("aiplayer.gui.button.spawn"), this::spawnProfile).icon(icon(Items.EMERALD)));
        // 批量生成：每行"名称 / 强度 / 皮肤"，右上角 −/+ 调行数、▶ 一键全部生成
        for (int i = 0; i < MAX_BATCH_ROWS; i++) {
            final int idx = i;
            this.batchLevels[i] = 2;
            this.batchNameBoxes[i] = contentField(0, 0, 120, "",
                    Component.translatable("aiplayer.gui.hint.batchName"), v -> {
                    });
            this.batchSkinBoxes[i] = contentField(0, 0, 120, "",
                    Component.translatable("aiplayer.gui.hint.batchSkin"), v -> {
                    });
            this.batchLevelButtons[i] = content(new FlatButton(0, 0, 24, ROW_H, Component.literal("Lv2"),
                    () -> cycleBatchLevel(idx)));
        }
        this.batchLessButton = content(new FlatButton(0, 0, 20, ROW_H, Component.literal("−"), this::lessBatchRows));
        this.batchMoreButton = content(new FlatButton(0, 0, 20, ROW_H, Component.literal("+"), this::moreBatchRows));
        this.batchSpawnButton = content(new FlatButton(0, 0, 20, ROW_H, Component.literal("▶"), this::spawnBatch)
                .accent(true).icon(icon(Items.EMERALD)));
        this.batchRunButton = content(new FlatButton(0, 0, 140, ROW_H,
                Component.translatable("aiplayer.gui.button.spawnBatch"), this::spawnBatch)
                .accent(true).icon(icon(Items.EMERALD)));
        // 整批共用一支队伍：方便一次拉起一队互相协战的 AI（留空 = 不入队）
        this.batchTeamBox = contentField(0, 0, 140, "",
                Component.translatable("aiplayer.gui.hint.batchTeam"), v -> {
                });
        // 命名模板：{n} 会替换成行号，避免逐行手打
        this.batchTemplateBox = contentField(0, 0, 140, "",
                Component.translatable("aiplayer.gui.hint.batchTemplate"), v -> {
                });
        this.batchTemplateButton = content(new FlatButton(0, 0, 56, ROW_H,
                Component.translatable("aiplayer.gui.button.applyTemplate"), this::applyNameTemplate)
                .icon(icon(Items.NAME_TAG)));
        this.deleteButton = content(new FlatButton(0, 0, 80, ROW_H,
                Component.translatable("aiplayer.gui.button.delete"), this::deleteProfile).icon(icon(Items.LAVA_BUCKET)));
        // 清空记忆：忘掉对话上下文 + 持久化的习性 / 指令 / 事件 / 认识的人
        this.memoryButton = content(new FlatButton(0, 0, 96, ROW_H,
                Component.translatable("aiplayer.gui.button.memoryClear"), this::clearMemory)
                .icon(icon(Items.WRITABLE_BOOK)));

        // ---- AI 列表页 ----
        this.listSearchBox = contentField(0, 0, 200, "", Component.translatable("aiplayer.gui.list.searchHint"), v -> {
            this.listQuery = v == null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
            // 只重排当前页，不重建控件，输入焦点不会丢
            layoutPass();
        });
        this.listEditButton = content(new FlatButton(0, 0, 72, ROW_H,
                Component.translatable("aiplayer.gui.button.edit"), () -> switchTab(0))
                .icon(icon(Items.WRITABLE_BOOK)));
        this.listTpButton = content(new FlatButton(0, 0, 72, ROW_H,
                Component.translatable("aiplayer.gui.button.teleport"), () -> aiCommand("aiplayer tp "))
                .icon(icon(Items.ENDER_PEARL)));
        this.listKeepButton = content(new FlatButton(0, 0, 96, ROW_H,
                Component.translatable("aiplayer.gui.button.autoKeep"), this::toggleAutoKeep)
                .icon(icon(Items.RESPAWN_ANCHOR)));
        this.listRemoveButton = content(new FlatButton(0, 0, 72, ROW_H,
                Component.translatable("aiplayer.gui.button.removeOne"), () -> aiCommand("aiplayer remove "))
                .icon(icon(Items.BARRIER)));
        this.listRemoveAllButton = content(new FlatButton(0, 0, 96, ROW_H,
                Component.translatable("aiplayer.gui.button.removeAll"), this::removeAllAi)
                .icon(icon(Items.LAVA_BUCKET)));

        // ---- 功能页：23 颗开关芯片 ----
        buildChips(profile);

        // ---- 装备页 ----
        this.kitBox = contentField(inner + LABEL_W, 0, 200, profile.kitItems,
                Component.translatable("aiplayer.gui.hint.kit"), v -> {
                    current().kitItems = v.trim();
                    markDirty();
                });
        this.kitChestButton = content(new FlatButton(0, 0, 170, ROW_H,
                Component.translatable("aiplayer.gui.button.kitChest"), this::openKitChest).icon(icon(Items.CHEST)));
        this.defaultKitButton = content(new FlatButton(0, 0, 150, ROW_H,
                Component.translatable("aiplayer.gui.button.defaultKit"), this::openDefaultKit).icon(icon(Items.SHULKER_BOX)));

        // ---- 全局页 ----
        this.baseUrlBox = contentField(inner + LABEL_W, 0, 200, this.config.apiBaseUrl,
                Component.literal("https://api.openai.com/v1"), v -> {
                    this.config.apiBaseUrl = v.trim();
                    markDirty();
                });
        // 输入框显示成 * 号（防偷窥），但 EditBox 内部保存的仍是真实 Key ——
        // addFormatter 只替换渲染出来的文本，getValue() 拿到的还是原文。
        // 掩码和界面字体必须写在同一个 formatter 里：只有第一个非空 formatter 会被采用。
        this.apiKeyBox = contentField(inner + LABEL_W, 0, 200, this.config.apiKey,
                Component.translatable("aiplayer.gui.hint.apiKey"), v -> {
                    this.config.apiKey = v.trim();
                    markDirty();
                }, (text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), UiTheme.uiStyle()));
        this.modelBox = contentField(inner + LABEL_W, 0, 200, this.config.model,
                Component.literal("gpt-4o-mini"), v -> {
                    this.config.model = v.trim();
                    markDirty();
                });
        // 模型预设：点一下把「接口地址 + 模型名」一起填好，Key 保持不动
        for (int i = 0; i < MODEL_PRESETS.length; i++) {
            final String[] preset = MODEL_PRESETS[i];
            this.presetButtons[i] = content(new FlatButton(0, 0, 72, ROW_H,
                    Component.literal(preset[0]), () -> applyModelPreset(preset)));
        }
        this.llmButton = content(new FlatButton(0, 0, 140, ROW_H,
                Component.translatable("aiplayer.gui.llm.on"), () -> {
                    this.config.llmEnabled = !this.config.llmEnabled;
                    markDirty();
                    refresh();
                }).icon(icon(Items.REDSTONE_TORCH)));
        this.testConnButton = content(new FlatButton(0, 0, 150, ROW_H,
                Component.translatable("aiplayer.gui.button.testConn"), this::testConnection)
                .icon(icon(Items.COMPARATOR)));
        // ---- 双 API：聊天回复单独走另一套 API，自主行动仍走上面的主 API ----
        this.dualApiButton = content(new FlatButton(0, 0, 150, ROW_H, dualApiLabel(), () -> {
            this.config.dualApi = !this.config.dualApi;
            markDirty();
            refresh();
        }).icon(icon(Items.BOOK)));
        this.chatUrlBox = contentField(inner + LABEL_W, 0, 200, this.config.chatApiBaseUrl,
                Component.literal("https://api.openai.com/v1"), v -> {
                    this.config.chatApiBaseUrl = v.trim();
                    markDirty();
                });
        this.chatKeyBox = contentField(inner + LABEL_W, 0, 200, this.config.chatApiKey,
                Component.translatable("aiplayer.gui.hint.chatApiKey"), v -> {
                    this.config.chatApiKey = v.trim();
                    markDirty();
                }, (text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), UiTheme.uiStyle()));
        this.chatModelBox = contentField(inner + LABEL_W, 0, 200, this.config.chatModel,
                Component.translatable("aiplayer.gui.hint.chatModel"), v -> {
                    this.config.chatModel = v.trim();
                    markDirty();
                });
        this.testChatButton = content(new FlatButton(0, 0, 150, ROW_H,
                Component.translatable("aiplayer.gui.button.testChat"), this::testChatConnection)
                .icon(icon(Items.COMPARATOR)));
        this.ttsButton = content(new FlatButton(0, 0, 110, ROW_H, ttsLabel(), () -> {
            ClientTtsConfig cfg = ClientTtsConfig.get();
            cfg.ttsEnabled = !cfg.ttsEnabled;
            cfg.save();
            if (cfg.ttsEnabled) {
                // 打开时立刻读一句，让玩家马上听到效果
                com.aiplayer.client.tts.TtsSpeaker.get().speak("", cfg.previewText());
            }
            refresh();
        }).icon(icon(Items.GOAT_HORN)));
        this.ttsPreviewButton = content(new FlatButton(0, 0, 70, ROW_H,
                Component.translatable("aiplayer.gui.button.ttsPreview"), () -> {
                    ClientTtsConfig cfg = ClientTtsConfig.get();
                    if (!cfg.ttsEnabled) {
                        cfg.ttsEnabled = true;
                        cfg.save();
                        refresh();
                    }
                    com.aiplayer.client.tts.TtsSpeaker.get().preview();
                }));
        this.ttsVoiceButton = content(new FlatButton(0, 0, 150, ROW_H, ttsVoiceLabel(), this::cycleVoice)
                .icon(icon(Items.NOTE_BLOCK)));

        // ---- 队伍对战：参战队伍名单（逗号分隔，和服务端配置 aiWarTeams 同一份）----
        this.duelButton = content(new FlatButton(0, 0, 170, ROW_H, duelLabel(), () -> {
            this.config.aiDuel = !this.config.aiDuel;
            markDirty();
            if (this.duelButton != null) {
                this.duelButton.setMessage(duelLabel());
            }
        }).icon(icon(Items.IRON_SWORD)));
        this.warTeamsBox = contentField(inner + LABEL_W, 0, 200, String.join(", ", this.config.aiWarTeams),
                Component.translatable("aiplayer.gui.hint.warTeams"), v ->
                        this.config.aiWarTeams = AiPlayerConfig.parseTeams(v), null);

        // ---- 枪械页：三个开关 + 射程。只在装了 TaCZ 时才有意义（没装时整个页签都隐藏）----
        this.gunChip = content(new ToggleChip(0, 0, 150, CHIP_H, "aiGun",
                icon(Items.CROSSBOW), Component.translatable("aiplayer.gui.gun.enabled"),
                this.config.aiGun, v -> {
                    this.config.aiGun = v;
                    markDirty();
                }));
        this.gunKitChip = content(new ToggleChip(0, 0, 150, CHIP_H, "aiGunKit",
                icon(Items.CHEST), Component.translatable("aiplayer.gui.gun.kit"),
                this.config.aiGunKit, v -> {
                    this.config.aiGunKit = v;
                    markDirty();
                }));
        this.gunAimChip = content(new ToggleChip(0, 0, 150, CHIP_H, "aiGunAim",
                icon(Items.SPYGLASS), Component.translatable("aiplayer.gui.gun.aim"),
                this.config.aiGunAim, v -> {
                    this.config.aiGunAim = v;
                    markDirty();
                }));
        this.gunAmmoChip = content(new ToggleChip(0, 0, 150, CHIP_H, "aiInfiniteAmmo",
                icon(Items.ARROW), Component.translatable("aiplayer.gui.gun.infinite"),
                this.config.aiInfiniteAmmo, v -> {
                    this.config.aiInfiniteAmmo = v;
                    markDirty();
                }));
        this.gunRangeBox = contentField(inner + LABEL_W, 0, 70, String.valueOf(this.config.aiGunRange),
                Component.translatable("aiplayer.gui.hint.gunRange"), v -> {
                    try {
                        this.config.aiGunRange = Math.max(0, Math.min(128, Integer.parseInt(v.trim())));
                        markDirty();
                    } catch (NumberFormatException ignored) {
                        // 输入到一半的数字（空串 / 只有负号）不写进配置
                    }
                });

        // ---- 关于页 ----
        this.githubButton = content(new FlatButton(0, 0, 150, ROW_H,
                Component.translatable("aiplayer.gui.about.open"), () -> openUrl(GITHUB_URL))
                .accent(true).icon(icon(Items.BOOK)));
        this.modrinthButton = content(new FlatButton(0, 0, 150, ROW_H,
                Component.translatable("aiplayer.gui.about.openModrinth"), () -> openUrl(MODRINTH_URL))
                .icon(icon(Items.EMERALD_BLOCK)));
        // 重看新手教学：随时可以把引导再走一遍（尤其"忘了 API 填哪儿"的时候）
        this.tutorialButton = content(new FlatButton(0, 0, 150, ROW_H,
                Component.translatable("aiplayer.gui.about.tutorial"),
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(new OnboardingScreen(this.parent));
                    }
                }).icon(icon(Items.WRITTEN_BOOK)));

        // ---- 外观页：预设色板 + 色值输入 + 圆角 / 动画开关（纯客户端，改动即时落盘）----
        for (int i = 0; i < ACCENT_PRESETS.length; i++) {
            final int rgb = ACCENT_PRESETS[i];
            this.accentSwatches[i] = content(new ColorSwatch(0, 0, 20, rgb,
                    () -> ClientThemeConfig.get().accentRgb() == rgb,
                    () -> setAccent(rgb)));
        }
        for (int i = 0; i < PANEL_PRESETS.length; i++) {
            final int rgb = PANEL_PRESETS[i];
            this.panelSwatches[i] = content(new ColorSwatch(0, 0, 20, rgb,
                    () -> ClientThemeConfig.get().panelRgb() == rgb,
                    () -> setPanel(rgb)));
        }
        this.accentHexBox = contentField(0, 0, 82, ClientThemeConfig.toHex(ClientThemeConfig.get().accentRgb()),
                Component.translatable("aiplayer.gui.hint.accentHex"), v -> {
                    if (ClientThemeConfig.isValid(v)) {
                        setAccentRaw(v);
                    }
                });
        this.accentHexBox.setMaxLength(6);
        this.randomAccentButton = content(new FlatButton(0, 0, 90, ROW_H,
                Component.translatable("aiplayer.gui.button.random"), this::randomAccent).accent(true));
        // 渐变两端色：两组色板，改完立刻落盘（面板 / 顶栏当帧换渐变）
        for (int i = 0; i < GRADIENT_A_PRESETS.length; i++) {
            final int rgb = GRADIENT_A_PRESETS[i];
            this.gradientASwatches[i] = content(new ColorSwatch(0, 0, 18, rgb,
                    () -> ClientThemeConfig.get().gradientARgb() == rgb,
                    () -> setGradient(true, rgb)));
        }
        for (int i = 0; i < GRADIENT_B_PRESETS.length; i++) {
            final int rgb = GRADIENT_B_PRESETS[i];
            this.gradientBSwatches[i] = content(new ColorSwatch(0, 0, 18, rgb,
                    () -> ClientThemeConfig.get().gradientBRgb() == rgb,
                    () -> setGradient(false, rgb)));
        }
        // 预设之外还要能填任意颜色：两个 HEX 输入框（# 开头也认，6 位）
        this.gradientAHexBox = contentField(0, 0, 74,
                ClientThemeConfig.toHex(ClientThemeConfig.get().gradientARgb()),
                Component.translatable("aiplayer.gui.hint.accentHex"), v -> {
                    if (ClientThemeConfig.isValid(v)) {
                        setGradientRaw(true, v);
                    }
                });
        this.gradientAHexBox.setMaxLength(7);
        this.gradientBHexBox = contentField(0, 0, 74,
                ClientThemeConfig.toHex(ClientThemeConfig.get().gradientBRgb()),
                Component.translatable("aiplayer.gui.hint.accentHex"), v -> {
                    if (ClientThemeConfig.isValid(v)) {
                        setGradientRaw(false, v);
                    }
                });
        this.gradientBHexBox.setMaxLength(7);
        this.gradientSpeedMinusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("−"), () -> stepGradientSpeed(-10)));
        this.gradientSpeedPlusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("+"), () -> stepGradientSpeed(10)));
        for (int i = 0; i < RADIUS_PRESETS.length; i++) {
            final int value = RADIUS_PRESETS[i];
            this.radiusButtons[i] = content(new FlatButton(0, 0, 58, ROW_H,
                    Component.translatable("aiplayer.gui.radius." + i), () -> setRadius(value)));
        }
        this.animButton = content(new FlatButton(0, 0, 130, ROW_H, animLabel(), () -> {
            ClientThemeConfig cfg = ClientThemeConfig.get();
            cfg.animations = !cfg.animations;
            cfg.save();
            UiTheme.refresh();
            refresh();
        }).icon(icon(Items.CLOCK)));
        this.blurButton = content(new FlatButton(0, 0, 130, ROW_H, blurLabel(), () -> {
            ClientThemeConfig cfg = ClientThemeConfig.get();
            cfg.blurBackground = !cfg.blurBackground;
            cfg.save();
            refresh();
        }).icon(icon(Items.SPYGLASS)));
        this.glassButton = content(new FlatButton(0, 0, 130, ROW_H, glassLabel(), () -> {
            ClientThemeConfig cfg = ClientThemeConfig.get();
            cfg.liquidGlass = !cfg.liquidGlass;
            cfg.save();
            UiTheme.refresh();
            refresh();
        }).icon(icon(Items.GLASS_BOTTLE)));
        this.roundButton = content(new FlatButton(0, 0, 130, ROW_H, roundLabel(), () -> {
            ClientThemeConfig cfg = ClientThemeConfig.get();
            cfg.roundedCorners = !cfg.roundedCorners;
            cfg.save();
            UiTheme.refresh();
            refresh();
        }).icon(icon(Items.ITEM_FRAME)));
        this.glassFpsButton = content(new FlatButton(0, 0, 130, ROW_H, glassFpsLabel(), () -> {
            ClientThemeConfig cfg = ClientThemeConfig.get();
            cfg.glassFps = nextGlassFps(cfg.glassFps);
            cfg.save();
            refresh();
        }).icon(icon(Items.CLOCK)));
        this.themeResetButton = content(new FlatButton(0, 0, 130, ROW_H,
                Component.translatable("aiplayer.gui.button.themeReset"), this::resetTheme)
                .icon(icon(Items.BARRIER)));
        this.backdropMinusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("−"), () -> stepBackdrop(-10)));
        this.backdropPlusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("+"), () -> stepBackdrop(10)));
        this.opacityMinusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("−"), () -> stepPanelOpacity(-10)));
        this.opacityPlusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("+"), () -> stepPanelOpacity(10)));
        this.uiScaleMinusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("−"), () -> stepUiScale(-10)));
        this.uiScalePlusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("+"), () -> stepUiScale(10)));
        this.shadowMinusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("−"), () -> stepShadow(-25)));
        this.shadowPlusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("+"), () -> stepShadow(25)));
        this.cardMinusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("−"), () -> stepCardTint(-25)));
        this.cardPlusButton = content(new FlatButton(0, 0, 20, ROW_H,
                Component.literal("+"), () -> stepCardTint(25)));

        // ---- 底栏 ----
        this.bottomButtons[0] = addRenderableWidget(new FlatButton(0, 0, 110, 18,
                Component.translatable("aiplayer.gui.button.save"), this::saveNow)
                .accent(true).icon(icon(Items.WRITABLE_BOOK)));
        this.bottomButtons[1] = addRenderableWidget(new FlatButton(0, 0, 80, 18,
                Component.translatable("aiplayer.gui.button.close"), this::closeScreen)
                .icon(icon(Items.BARRIER)));
        this.bottomButtons[2] = addRenderableWidget(new FlatButton(0, 0, 110, 18,
                Component.translatable("aiplayer.gui.button.defaultKit"), this::openDefaultKit)
                .icon(icon(Items.SHULKER_BOX)));
        this.bottomButtons[3] = addRenderableWidget(new FlatButton(0, 0, 70, 18,
                Component.translatable("aiplayer.gui.button.about"), () -> switchTab(6))
                .icon(icon(Items.BOOK)));

        this.sync = false;
        this.scroll = 0;
        this.scrollTarget = 0;
        this.listScrollTarget = 0;
        relayout();
        refresh();
    }

    /** 换标签页：滚动位置归零并整体重排 */
    private void switchTab(int t) {
        if (this.tab == t) {
            return;
        }
        // 枪械页在没装 TaCZ 时是隐藏的：即便被内部跳转指到它也直接拦回去，避免空白页
        if (t == COMPAT_TAB && !compatAvailable()) {
            return;
        }
        this.tab = t;
        this.scroll = 0;
        this.scrollTarget = 0;
        relayout();
        refresh();
    }

    private <T extends AbstractWidget> T content(T widget) {
        this.contentWidgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private EditBox contentField(int x, int y, int width, String value, Component hint, Consumer<String> setter) {
        return contentField(x, y, width, value, hint, setter, null);
    }

    private EditBox contentField(int x, int y, int width, String value, Component hint, Consumer<String> setter,
                                 EditBox.TextFormatter display) {
        return content(makeField(x, y, width, value, hint, setter, display));
    }

    /**
     * 通用输入框。
     * <p>
     * {@code display} 为 null 时默认给文字套界面字体；需要自定义渲染（例如 API Key 打码）
     * 时传自己的 formatter —— 掩码和字体必须写在同一个 formatter 里，因为 EditBox 只采用
     * 第一个非空 formatter 的结果。
     */
    private EditBox makeField(int x, int y, int width, String value, Component hint,
                              Consumer<String> setter, EditBox.TextFormatter display) {
        EditBox box = new EditBox(this.font, x, y, width, ROW_H, Component.empty());
        box.setBordered(false);
        box.setTextColor(UiTheme.TEXT);
        box.setMaxLength(512);
        box.setHint(UiTheme.ui(hint).copy().withStyle(UiTheme.hintStyle()));
        box.setValue(value == null ? "" : value);
        EditBox.TextFormatter formatter = display != null ? display
                : (text, offset) -> FormattedCharSequence.forward(text, UiTheme.uiStyle());
        box.addFormatter(formatter);
        box.setResponder(v -> {
            if (!this.sync) {
                setter.accept(v);
            }
        });
        return box;
    }

    /** 内容区左边距（卡片内的控件从这里再往里缩 8px） */
    private int contentInset() {
        return this.contentX + 8;
    }

    private int row(int offset) {
        return this.contentY + offset - (int) Math.round(this.scroll);
    }

    // ------------------------------------------------------------------
    // 几何 / 排版
    // ------------------------------------------------------------------

    private void geometry() {
        int h = Math.min(MAX_PANEL_H, this.layoutH - 20);
        if (h < 220) {
            h = Math.min(220, this.layoutH - 4);
        }
        this.panelH = h;
        this.left = Math.max(0, (this.layoutW - PANEL_W) / 2);
        this.top = Math.max(2, (this.layoutH - this.panelH) / 2);
        this.contentX = this.left + SIDEBAR_W + PAD;
        this.contentY = this.top + TOP_H + TAB_H + PAD;
        this.contentW = PANEL_W - SIDEBAR_W - PAD * 2 - SCROLLBAR_W - 2;
        this.contentH = Math.max(40, this.top + this.panelH - BOTTOM_H - PAD - this.contentY);
        this.sidebarBottom = this.top + this.panelH - BOTTOM_H;
    }

    /** 重排：先按当前标签页算一遍，滚动超界就夹紧后再算一遍 */
    private void relayout() {
        geometry();
        layoutPass();
        int max = Math.max(0, this.contentTotalH - this.contentH);
        this.scrollTarget = clampF(this.scrollTarget, 0, max);
        if (this.scroll > max) {
            this.scroll = max;
            layoutPass();
        }
    }

    private void layoutPass() {
        this.cards.clear();
        this.texts.clear();
        this.drawFields.clear();
        this.listRows.clear();
        // 列表页的行命中区必须跟着重排一起清掉，否则切到别的页后仍会响应点击
        this.aiRows.clear();
        this.contentTotalH = 0;
        for (AbstractWidget w : this.contentWidgets) {
            w.visible = false;
            w.setFocused(false);
        }

        layoutTabs();
        layoutSidebar();
        layoutBottom();

        switch (this.tab) {
            case 1 -> layoutFeatures();
            case 2 -> layoutKit();
            case 3 -> layoutGlobal();
            case 4 -> layoutStyle();
            case 5 -> layoutBatch();
            case 6 -> layoutList();
            case 7 -> layoutAbout();
            case 8 -> layoutCompat();
            default -> layoutProfile();
        }
        // 文本样式只在这里套一次：绘制时 UiTheme.ui() 发现已经带界面字体就直接复用，
        // 不再每帧对每段文字 copy() 一遍整棵组件树（那是每帧几十次临时对象）。
        for (Object[] t : this.texts) {
            t[0] = UiTheme.ui((Component) t[0]);
        }
    }

    private void layoutTabs() {
        int shown = 0;
        for (int i = 0; i < this.tabButtons.length; i++) {
            FlatButton b = this.tabButtons[i];
            if (b == null) {
                continue;
            }
            // 「枪械」页只在检测到对应模组（TaCZ）时才出现：没装的玩家看不到这一页，
            // 也就不会去调一个对自己毫无作用的开关。
            boolean show = !(i == COMPAT_TAB && !compatAvailable());
            // layoutPass() 开头会把所有控件先设成 invisible，这里必须重新点亮，
            // 否则页签永远不可见（上方那排按钮就是因此消失的）。
            b.visible = show;
            if (!show) {
                b.setFocused(false);
                continue;
            }
            shown++;
        }
        // 页签宽度按「可见数量」均分，隐藏一页时其余页签会自动变宽，不会挤到面板外
        int avail = PANEL_W - SIDEBAR_W - PAD * 2 - SCROLLBAR_W - 2 - 4;
        int tabW = shown <= 0 ? TAB_W : Math.min(TAB_W + 12, (avail - (shown - 1) * TAB_GAP) / shown);
        this.tabW = tabW;
        int slot = 0;
        for (int i = 0; i < this.tabButtons.length; i++) {
            FlatButton b = this.tabButtons[i];
            if (b == null || !b.visible) {
                continue;
            }
            b.setX(this.left + SIDEBAR_W + 4 + slot * (tabW + TAB_GAP));
            b.setY(this.top + TOP_H + 2);
            b.setWidth(tabW);
            b.setFocused(false);
            b.accent(i == this.tab);
            slot++;
        }
    }

    private void layoutSidebar() {
        if (this.newAiButton != null) {
            this.newAiButton.setX(this.left + SIDEBAR_W - 30);
            this.newAiButton.setY(this.top + TOP_H + 5);
            this.newAiButton.visible = true;
        }
        int listTop = this.top + TOP_H + 26;
        int viewH = Math.max(0, this.sidebarBottom - 46 - listTop);
        // 侧栏与列表页共用同一个搜索词：AI 多时不必在几十行里翻找
        java.util.List<Integer> shown = filteredIndices();
        this.listTotalH = shown.size() * LIST_ROW_H;
        int maxScroll = Math.max(0, this.listTotalH - viewH);
        this.listScrollTarget = clampF(this.listScrollTarget, 0, maxScroll);
        this.listScroll = clampF(this.listScroll, 0, maxScroll);
        for (int k = 0; k < shown.size(); k++) {
            int i = shown.get(k);
            int y = listTop + k * LIST_ROW_H - (int) Math.round(this.listScroll);
            if (y + LIST_ROW_H <= listTop || y >= listTop + viewH) {
                continue;
            }
            this.listRows.add(new int[]{this.left + 6, y, this.left + SIDEBAR_W - 6, y + LIST_ROW_H - 2, i});
        }
        if (this.skinBox != null) {
            this.skinBox.setX(this.left + 40);
            this.skinBox.setY(this.sidebarBottom - 32);
            this.skinBox.visible = true;
            this.drawFields.add(this.skinBox);
        }
    }

    private void layoutBottom() {
        int by = this.top + this.panelH - BOTTOM_H + 5;
        int bx = this.left + PAD;
        int[] widths = {110, 80, 110, 70};
        for (int i = 0; i < this.bottomButtons.length; i++) {
            this.bottomButtons[i].setX(bx);
            this.bottomButtons[i].setY(by);
            this.bottomButtons[i].setWidth(widths[i]);
            this.bottomButtons[i].visible = true;
            bx += widths[i] + 8;
        }
    }

    /** 放置内容区控件：自动记录要不要画输入框外框，并把滚出可视区的藏起来 */
    private void put(AbstractWidget w, int x, int y) {
        if (w == null) {
            return;
        }
        w.setX(x);
        w.setY(y);
        boolean inView = y + w.getHeight() > this.contentY && y < this.contentY + this.contentH;
        w.visible = inView;
        if (!inView) {
            w.setFocused(false);
        }
        if (inView && w instanceof EditBox box) {
            this.drawFields.add(box);
        }
    }

    private void card(int off, int height) {
        int y1 = row(off);
        this.cards.add(new int[]{this.contentX, y1, this.contentX + this.contentW, y1 + height});
    }

    private void title(int off, String key) {
        this.texts.add(new Object[]{Component.translatable(key), this.contentInset(), row(off), UiTheme.ACCENT, true});
    }

    private void label(int off, String key) {
        this.texts.add(new Object[]{Component.translatable(key), this.contentInset(), row(off) + 4, UiTheme.TEXT_DIM, false});
    }

    private void plain(int off, String key) {
        this.texts.add(new Object[]{Component.translatable(key), this.contentInset(), row(off), UiTheme.TEXT_MUTED, false});
    }

    // ---- 档案页 ----
    private void layoutProfile() {
        int inner = contentInset();
        int innerW = this.contentW - 16;
        int rightEdge = inner + innerW;

        card(0, 78);
        title(6, "aiplayer.gui.section.profile");
        label(20, "aiplayer.gui.label.name");
        put(this.nameBox, inner + LABEL_W, row(20));
        put(this.levelButton, rightEdge - 140, row(20));
        label(40, "aiplayer.gui.label.team");
        put(this.teamBox, inner + LABEL_W, row(40));
        put(this.bridgeButton, rightEdge - 140, row(40));
        label(60, "aiplayer.gui.label.follow");
        put(this.followBox, inner + LABEL_W, row(60));
        put(this.followSuggestButton, rightEdge - 140, row(60));
        put(this.speedBox, rightEdge - 96, row(60));

        card(86, 44);
        title(92, "aiplayer.gui.label.personality");
        put(this.personalityBox, inner + LABEL_W, row(108));
        this.personalityBox.setWidth(innerW - LABEL_W);

        int tauntTop = 162;
        int rows = Math.max(1, this.tauntCount);
        card(138, tauntTop + rows * 18 + 30 - 138);
        title(144, "aiplayer.gui.label.taunt");
        if (this.tauntCount == 0) {
            // 空状态提示放在「＋ 添加」按钮右侧：原先与按钮同一坐标，两段文字会叠在一起
            this.texts.add(new Object[]{Component.translatable("aiplayer.gui.taunt.empty"),
                    inner + 132, row(tauntTop) + 4, UiTheme.TEXT_MUTED, false});
        }
        for (int i = 0; i < this.tauntCount; i++) {
            int y = row(tauntTop + i * 18);
            put(this.tauntBoxes[i], inner + 8, y);
            this.tauntBoxes[i].setWidth(innerW - 40);
            put(this.tauntRemove[i], rightEdge - 24, y);
        }
        put(this.tauntAddButton, inner, row(tauntTop + this.tauntCount * 18 + 4));

        int btnY = tauntTop + rows * 18 + 34;
        put(this.spawnButton, inner, row(btnY));
        put(this.deleteButton, inner + 108, row(btnY));
        put(this.memoryButton, inner + 196, row(btnY));

        this.contentTotalH = btnY + 30;
    }

    // ---- 批量生成页：单独一页，逐行配置 名称 / 强度 / 皮肤，一键全部生成 ----
    private void layoutBatch() {
        int inner = contentInset();
        int innerW = this.contentW - 16;
        int rightEdge = inner + innerW;

        // 行编辑区 + 右上角 −/+ 调行数、▶ 一键生成，底部一行放显眼的「批量生成」按钮
        card(0, 26 + this.batchRows * 20 + 34);
        title(6, "aiplayer.gui.label.batch");
        put(this.batchLessButton, rightEdge - 66, row(6));
        put(this.batchMoreButton, rightEdge - 44, row(6));
        put(this.batchSpawnButton, rightEdge - 22, row(6));
        for (int i = 0; i < MAX_BATCH_ROWS; i++) {
            int y = row(26 + i * 20);
            if (i >= this.batchRows) {
                // 超出行数：直接藏起来。
                // 不能靠"挪到屏幕外"——put() 只按 y 判断可见性，横坐标挪走仍算在视区内，
                // 这些输入框会留在 drawFields 里继续参与绘制与 Tab 焦点切换。
                put(this.batchNameBoxes[i], inner, y);
                put(this.batchLevelButtons[i], inner, y);
                put(this.batchSkinBoxes[i], inner, y);
                this.batchNameBoxes[i].visible = false;
                this.batchLevelButtons[i].visible = false;
                this.batchSkinBoxes[i].visible = false;
                this.drawFields.remove(this.batchNameBoxes[i]);
                this.drawFields.remove(this.batchSkinBoxes[i]);
                continue;
            }
            int nameW = Math.max(60, (innerW - 96) / 2);
            put(this.batchNameBoxes[i], inner, y);
            this.batchNameBoxes[i].setWidth(nameW);
            put(this.batchLevelButtons[i], inner + nameW + 4, y);
            this.batchLevelButtons[i].setMessage(Component.literal("Lv" + this.batchLevels[i]));
            put(this.batchSkinBoxes[i], inner + nameW + 30, y);
            this.batchSkinBoxes[i].setWidth(Math.max(50, innerW - nameW - 34));
        }
        put(this.batchRunButton, inner, row(26 + this.batchRows * 20 + 6));
        // 队伍输入框紧跟在批量按钮右边，整批共用
        put(this.batchTeamBox, inner + 148, row(26 + this.batchRows * 20 + 6));
        if (this.batchTeamBox != null) {
            this.batchTeamBox.setWidth(Math.max(60, innerW - 220));
            this.batchTeamBox.visible = true;
        }
        // 命名模板 + 一键填充
        int tplY = 26 + this.batchRows * 20 + 28;
        put(this.batchTemplateBox, inner, row(tplY));
        if (this.batchTemplateBox != null) {
            this.batchTemplateBox.setWidth(Math.max(60, innerW - 62));
            this.batchTemplateBox.visible = true;
        }
        put(this.batchTemplateButton, inner + innerW - 56, row(tplY));
        if (this.batchTemplateButton != null) {
            this.batchTemplateButton.visible = true;
        }

        this.contentTotalH = tplY + 30;
    }

    // ---- 功能页：5 张分组卡 ----
    private void layoutFeatures() {
        int gy = 0;
        for (String[] group : GROUPS) {
            int count = group.length - 1;
            int rows = (count + CHIP_COLS - 1) / CHIP_COLS;
            int h = 16 + rows * CHIP_ROW + 8;
            card(gy, h);
            title(gy + 6, "aiplayer.gui.group." + group[0]);
            for (int i = 0; i < count; i++) {
                ToggleChip chip = chipOf(group[i + 1]);
                if (chip == null) {
                    continue;
                }
                int col = i % CHIP_COLS;
                int line = i / CHIP_COLS;
                put(chip, this.contentInset() + col * (chip.getWidth() + 6), row(gy + 22 + line * CHIP_ROW));
            }
            gy += h + 8;
        }
        this.contentTotalH = gy;
    }

    private ToggleChip chipOf(String key) {
        for (ToggleChip chip : this.chips) {
            if (chip.key().equals(key)) {
                return chip;
            }
        }
        return null;
    }

    // ---- 装备页 ----
    private void layoutKit() {
        int inner = contentInset();
        card(0, 78);
        title(6, "aiplayer.gui.label.kit");
        put(this.kitBox, inner + LABEL_W, row(24));
        this.kitBox.setWidth(this.contentW - 16 - LABEL_W);
        put(this.kitChestButton, inner, row(48));
        put(this.defaultKitButton, inner + 178, row(48));
        plain(88, "aiplayer.gui.hint.kit");
        this.contentTotalH = 116;
    }

    // ---- 枪械页：只在装了 TaCZ 时出现 ----
    private void layoutCompat() {
        int inner = contentInset();
        card(0, 156);
        title(6, "aiplayer.gui.section.gun");
        put(this.gunChip, inner, row(24));
        put(this.gunKitChip, inner, row(44));
        put(this.gunAimChip, inner, row(64));
        put(this.gunAmmoChip, inner, row(84));
        label(108, "aiplayer.gui.label.gunRange");
        put(this.gunRangeBox, inner + LABEL_W, row(106));
        // 射程说明直接写成一行提示，玩家不用去翻文档
        plain(130, "aiplayer.gui.hint.gunRange");

        card(168, 96);
        title(174, "aiplayer.gui.section.gunHow");
        plain(194, "aiplayer.gui.hint.gunHow1");
        plain(208, "aiplayer.gui.hint.gunHow2");
        plain(222, "aiplayer.gui.hint.gunHow3");
        this.contentTotalH = 272;
    }

    // ---- 全局页 ----
    private void layoutGlobal() {
        int inner = contentInset();
        int innerW = this.contentW - 16;
        card(0, 212);
        title(6, "aiplayer.gui.section.api");
        label(24, "aiplayer.gui.label.baseUrl");
        put(this.baseUrlBox, inner + LABEL_W, row(24));
        label(44, "aiplayer.gui.label.apiKey");
        put(this.apiKeyBox, inner + LABEL_W, row(44));
        label(64, "aiplayer.gui.label.model");
        put(this.modelBox, inner + LABEL_W, row(64));
        put(this.llmButton, inner + innerW - 140, row(64));
        // 预设一行：点誰就把地址与模型名一起换掉（Key 不动）
        for (int i = 0; i < this.presetButtons.length; i++) {
            put(this.presetButtons[i], inner + i * 76, row(88));
        }
        // 第二行：连通性自检（异步，结果直接写在按钮上，不占聊天栏）
        put(this.testConnButton, inner, row(112));
        // 双 API 开关：聊天回复走单独的 API，自主行动仍走主 API
        put(this.dualApiButton, inner + 160, row(112));
        label(136, "aiplayer.gui.label.chatUrl");
        put(this.chatUrlBox, inner + LABEL_W, row(136));
        label(156, "aiplayer.gui.label.chatKey");
        put(this.chatKeyBox, inner + LABEL_W, row(156));
        label(176, "aiplayer.gui.label.chatModel");
        put(this.chatModelBox, inner + LABEL_W, row(176));
        put(this.testChatButton, inner + innerW - 150, row(176));

        card(248, 60);
        title(254, "aiplayer.gui.button.tts");
        put(this.ttsButton, inner, row(274));
        put(this.ttsPreviewButton, inner + 118, row(274));
        put(this.ttsVoiceButton, inner + 196, row(274));

        // ---- 队伍对战 ----
        card(320, 84);
        title(326, "aiplayer.gui.section.war");
        put(this.duelButton, inner, row(346));
        label(370, "aiplayer.gui.label.warTeams");
        put(this.warTeamsBox, inner + LABEL_W, row(370));
        this.warTeamsBox.setWidth(Math.max(90, innerW - LABEL_W));
        plain(394, "aiplayer.gui.hint.warTeamsNote");

        plain(430, "aiplayer.gui.configPath");
        this.contentTotalH = 450;
    }

    /**
     * 测试连接：发一个最小请求，把「模型名 + 耗时」或失败原因直接写在按钮上。
     * <p>
     * 服务端用 {@code /aiplayer testllm} 也能测，但普通玩家不会去敲指令；
     * 这里和「言出法随」的设置页一样，填完就能当场知道 Key / 地址 / 模型对不对。
     * 请求在线程池里跑，回调切回客户端主线程再改按钮文字。
     */
    private void testConnection() {
        if (this.testingConn) {
            return;
        }
        this.testingConn = true;
        this.testConnButton.setMessage(Component.translatable("aiplayer.gui.button.testing"));
        // 用当前界面上的值（可能还没保存）构造一个临时档案，测的就是玩家眼前这套配置
        AiProfile probe = new AiProfile();
        probe.name = "test";
        probe.apiKey = this.config.apiKey;
        probe.apiBaseUrl = this.config.apiBaseUrl;
        probe.model = this.config.model;
        long start = System.currentTimeMillis();
        com.aiplayer.api.LlmClient.ask(probe,
                        "你是连接测试助手。只输出 JSON，不要解释。",
                        "只回复 {\"say\":\"ok\"}")
                .whenComplete((reply, error) -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        this.testingConn = false;
                        if (this.testConnButton == null) {
                            return;
                        }
                        long ms = System.currentTimeMillis() - start;
                        if (error == null) {
                            this.testConnButton.setMessage(Component.translatable(
                                    "aiplayer.gui.testConn.ok", String.valueOf(ms)));
                            return;
                        }
                        Throwable cause = error instanceof java.util.concurrent.CompletionException
                                && error.getCause() != null ? error.getCause() : error;
                        String msg = cause.getMessage() == null
                                ? cause.getClass().getSimpleName() : cause.getMessage();
                        // 按钮宽度有限：只留最关键的一段（HTTP 402 / 401 / 地址不合法 之类）
                        if (msg.length() > 24) {
                            msg = msg.substring(0, 24) + "…";
                        }
                        this.testConnButton.setMessage(Component.literal("✗ " + msg));
                    });
                });
    }

    /**
     * 测试聊天 API：和主 API 连通性测试同一套逻辑，但强制走聊天通道。
     * 聊天地址 / Key / 模型留空时会按回退规则自动用主 API，所以只开开关也能测。
     */
    private void testChatConnection() {
        if (this.testingChat) {
            return;
        }
        this.testingChat = true;
        this.testChatButton.setMessage(Component.translatable("aiplayer.gui.button.testing"));
        AiProfile probe = new AiProfile();
        probe.name = "test";
        probe.dualApi = true;
        probe.apiKey = this.config.apiKey;
        probe.apiBaseUrl = this.config.apiBaseUrl;
        probe.model = this.config.model;
        probe.chatApiKey = this.config.chatApiKey;
        probe.chatApiBaseUrl = this.config.chatApiBaseUrl;
        probe.chatModel = this.config.chatModel;
        long start = System.currentTimeMillis();
        com.aiplayer.api.LlmClient.ask(probe,
                        "你是连接测试助手。只输出 JSON，不要解释。",
                        "只回复 {\"say\":\"ok\"}", true)
                .whenComplete((reply, error) -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        this.testingChat = false;
                        if (this.testChatButton == null) {
                            return;
                        }
                        long ms = System.currentTimeMillis() - start;
                        if (error == null) {
                            this.testChatButton.setMessage(Component.translatable(
                                    "aiplayer.gui.testConn.ok", String.valueOf(ms)));
                            return;
                        }
                        Throwable cause = error instanceof java.util.concurrent.CompletionException
                                && error.getCause() != null ? error.getCause() : error;
                        String msg = cause.getMessage() == null
                                ? cause.getClass().getSimpleName() : cause.getMessage();
                        if (msg.length() > 24) {
                            msg = msg.substring(0, 24) + "…";
                        }
                        this.testChatButton.setMessage(Component.literal("✗ " + msg));
                    });
                });
    }

    private Component dualApiLabel() {
        return Component.translatable(this.config.dualApi
                ? "aiplayer.gui.dualApi.on" : "aiplayer.gui.dualApi.off");
    }

    /** 模型预设：只改「接口地址 + 模型名」，API Key 保持玩家已填的内容 */
    private void applyModelPreset(String[] preset) {
        this.config.apiBaseUrl = preset[1];
        this.config.model = preset[2];
        if (this.baseUrlBox != null) {
            this.baseUrlBox.setValue(preset[1]);
        }
        if (this.modelBox != null) {
            this.modelBox.setValue(preset[2]);
        }
        markDirty();
    }

    // ---- AI 列表页：搜索 + 在线状态 + 整体操作 ----
    private void layoutList() {
        int inner = contentInset();
        int innerW = this.contentW - 16;
        this.aiRows.clear();

        put(this.listSearchBox, inner, row(6));
        if (this.listSearchBox != null) {
            this.listSearchBox.setWidth(Math.max(80, innerW - 104));
            this.listSearchBox.visible = true;
        }
        this.texts.add(new Object[]{Component.translatable("aiplayer.gui.list.count",
                String.valueOf(filteredCount())), inner + innerW - 84, row(10), UiTheme.TEXT_MUTED, false});

        int y = 34;
        int shown = 0;
        for (int i = 0; i < this.config.profiles.size(); i++) {
            AiProfile p = this.config.profiles.get(i);
            if (p == null || !matchesQuery(p)) {
                continue;
            }
            int ry = row(y + shown * 20);
            this.aiRows.add(new int[]{inner, ry - 2, inner + innerW, ry + 14, i});
            boolean sel = i == this.index;
            // 左侧状态点由 drawContent 统一画（跟着滚动一起裁剪）
            this.texts.add(new Object[]{Component.literal(p.name == null ? "AI" : p.name),
                    inner + 16, ry, sel ? UiTheme.ACCENT : UiTheme.TEXT, sel});
            if (!llmReady(p)) {
                // L = LLM 未就绪（全局关掉了，或这只 AI 与全局都没有 Key）
                this.texts.add(new Object[]{Component.literal("L"), inner + 112, ry, UiTheme.DANGER, false});
            }
            String team = p.team == null || p.team.isBlank() ? "-" : p.team;
            this.texts.add(new Object[]{Component.literal("Lv" + p.levelId + "  " + team),
                    inner + 130, ry, teamColor(p.team), false});
            String owner = p.ownerName == null || p.ownerName.isBlank() ? "-" : p.ownerName;
            Component extra = Component.translatable("aiplayer.gui.list.owner", owner);
            this.texts.add(new Object[]{extra, inner + 210, ry, UiTheme.TEXT_MUTED, false});
            shown++;
        }
        if (shown == 0) {
            this.texts.add(new Object[]{Component.translatable(this.config.profiles.isEmpty()
                    ? "aiplayer.gui.list.empty" : "aiplayer.gui.list.noMatch"),
                    inner, row(40), UiTheme.TEXT_MUTED, false});
        }

        int btnY = 40 + Math.max(1, shown) * 20 + 16;
        put(this.listEditButton, inner, row(btnY));
        put(this.listTpButton, inner + 78, row(btnY));
        put(this.listKeepButton, inner + 156, row(btnY));
        put(this.listRemoveButton, inner + 258, row(btnY));
        put(this.listRemoveAllButton, inner + innerW - 96, row(btnY));
        if (this.listKeepButton != null) {
            this.listKeepButton.setMessage(Component.translatable(current().autoKeep
                    ? "aiplayer.gui.button.autoKeepOn" : "aiplayer.gui.button.autoKeep"));
            this.listKeepButton.accent(current().autoKeep);
        }
        if (this.listRemoveAllButton != null) {
            boolean armed = System.currentTimeMillis() - this.removeAllArmedAt < 3000L;
            this.listRemoveAllButton.setMessage(Component.translatable(armed
                    ? "aiplayer.gui.button.removeAllConfirm" : "aiplayer.gui.button.removeAll"));
            this.listRemoveAllButton.accent(armed);
        }
        this.contentTotalH = btnY + 30;
    }

    /** 搜索词命中：AI 名 / 队伍 / 主人 / 皮肤任意一项包含即可 */
    private boolean matchesQuery(AiProfile p) {
        if (this.listQuery == null || this.listQuery.isEmpty()) {
            return true;
        }
        String q = this.listQuery;
        return contains(p.name, q) || contains(p.team, q)
                || contains(p.ownerName, q) || contains(p.skinName, q);
    }

    private static boolean contains(String text, String q) {
        return text != null && text.toLowerCase(java.util.Locale.ROOT).contains(q);
    }

    /** 搜索词命中的档案下标（保持原顺序，选中高亮仍按真实下标判断） */
    private java.util.List<Integer> filteredIndices() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < this.config.profiles.size(); i++) {
            AiProfile p = this.config.profiles.get(i);
            if (p != null && matchesQuery(p)) {
                out.add(i);
            }
        }
        return out;
    }

    private int filteredCount() {
        return filteredIndices().size();
    }

    // ---- 关于页 ----
    private void layoutAbout() {
        int inner = contentInset();
        card(0, 152);
        title(6, "aiplayer.gui.about.title");
        this.texts.add(new Object[]{Component.translatable("aiplayer.gui.about.desc1"),
                inner, row(30), UiTheme.TEXT_DIM, false});
        this.texts.add(new Object[]{Component.translatable("aiplayer.gui.about.desc2"),
                inner, row(44), UiTheme.TEXT_DIM, false});
        title(66, "aiplayer.gui.about.license");
        this.texts.add(new Object[]{Component.literal("GPL-3.0-only"), inner, row(84), UiTheme.TEXT_DIM, false});
        this.texts.add(new Object[]{Component.literal(GITHUB_URL), inner + 84, row(84), UiTheme.LINK, false});
        put(this.githubButton, inner, row(110));
        put(this.modrinthButton, inner + 158, row(110));
        put(this.tutorialButton, inner, row(126));
        this.contentTotalH = 156;
    }

    // ---- 外观页：强调色 / 面板底色 / 圆角 / 动画（纯客户端，改动即时落盘）----
    private void layoutStyle() {
        int inner = contentInset();
        int y = 0;

        card(y, 80);
        title(y + 6, "aiplayer.gui.section.accent");
        for (int i = 0; i < this.accentSwatches.length; i++) {
            put(this.accentSwatches[i], inner + i * 24, row(y + 26));
        }
        label(y + 54, "aiplayer.gui.label.accentHex");
        put(this.accentHexBox, inner + LABEL_W + 30, row(y + 52));
        put(this.randomAccentButton, inner + LABEL_W + 122, row(y + 52));
        y += 88;

        card(y, 56);
        title(y + 6, "aiplayer.gui.section.panel");
        for (int i = 0; i < this.panelSwatches.length; i++) {
            put(this.panelSwatches[i], inner + i * 24, row(y + 28));
        }
        y += 64;

        // 渐变颜色 + 速度：面板 / 顶栏本身就是这条渐变，改色板当帧生效
        card(y, 96);
        title(y + 6, "aiplayer.gui.section.gradient");
        label(y + 26, "aiplayer.gui.label.gradientA");
        for (int i = 0; i < this.gradientASwatches.length; i++) {
            put(this.gradientASwatches[i], inner + LABEL_W + 4 + i * 22, row(y + 24));
        }
        put(this.gradientAHexBox, inner + LABEL_W + 142, row(y + 24));
        label(y + 48, "aiplayer.gui.label.gradientB");
        for (int i = 0; i < this.gradientBSwatches.length; i++) {
            put(this.gradientBSwatches[i], inner + LABEL_W + 4 + i * 22, row(y + 46));
        }
        put(this.gradientBHexBox, inner + LABEL_W + 142, row(y + 46));
        label(y + 70, "aiplayer.gui.label.gradientSpeed");
        put(this.gradientSpeedMinusButton, inner + LABEL_W + 24, row(y + 68));
        put(this.gradientSpeedPlusButton, inner + LABEL_W + 46, row(y + 68));
        this.texts.add(new Object[]{Component.literal(gradientSpeedText()),
                inner + LABEL_W + 72, row(y + 72), UiTheme.ACCENT, false});
        y += 104;

        card(y, 56);
        title(y + 6, "aiplayer.gui.section.radius");
        for (int i = 0; i < this.radiusButtons.length; i++) {
            put(this.radiusButtons[i], inner + i * 62, row(y + 28));
        }
        y += 64;

        card(y, 172);
        title(y + 6, "aiplayer.gui.section.motion");
        put(this.animButton, inner, row(y + 28));
        put(this.blurButton, inner, row(y + 56));
        put(this.glassButton, inner, row(y + 84));
        put(this.roundButton, inner, row(y + 112));
        put(this.glassFpsButton, inner, row(y + 140));
        y += 180;

        // 遮罩浓度 / 面板不透明度：直接影响"本该透明的地方有没有被颜色盖住"
        card(y, 78);
        title(y + 6, "aiplayer.gui.section.transparency");
        label(y + 26, "aiplayer.gui.label.backdrop");
        put(this.backdropMinusButton, inner + LABEL_W + 24, row(y + 24));
        put(this.backdropPlusButton, inner + LABEL_W + 46, row(y + 24));
        this.texts.add(new Object[]{Component.literal(ClientThemeConfig.get().backdrop + "%"),
                inner + LABEL_W + 72, row(y + 28), UiTheme.ACCENT, false});
        label(y + 46, "aiplayer.gui.label.panelOpacity");
        put(this.opacityMinusButton, inner + LABEL_W + 24, row(y + 44));
        put(this.opacityPlusButton, inner + LABEL_W + 46, row(y + 44));
        this.texts.add(new Object[]{Component.literal(ClientThemeConfig.get().panelOpacity + "%"),
                inner + LABEL_W + 72, row(y + 48), UiTheme.ACCENT, false});
        y += 86;

        // 界面缩放 / 阴影强度 / 卡片亮度：整体观感的三个总开关
        card(y, 108);
        title(y + 6, "aiplayer.gui.section.layout");
        label(y + 26, "aiplayer.gui.label.uiScale");
        put(this.uiScaleMinusButton, inner + LABEL_W + 24, row(y + 24));
        put(this.uiScalePlusButton, inner + LABEL_W + 46, row(y + 24));
        this.texts.add(new Object[]{Component.literal(ClientThemeConfig.get().uiScale + "%"),
                inner + LABEL_W + 72, row(y + 28), UiTheme.ACCENT, false});
        label(y + 46, "aiplayer.gui.label.shadow");
        put(this.shadowMinusButton, inner + LABEL_W + 24, row(y + 44));
        put(this.shadowPlusButton, inner + LABEL_W + 46, row(y + 44));
        this.texts.add(new Object[]{Component.literal(ClientThemeConfig.get().shadow + "%"),
                inner + LABEL_W + 72, row(y + 48), UiTheme.ACCENT, false});
        label(y + 66, "aiplayer.gui.label.cardTint");
        put(this.cardMinusButton, inner + LABEL_W + 24, row(y + 64));
        put(this.cardPlusButton, inner + LABEL_W + 46, row(y + 64));
        this.texts.add(new Object[]{Component.literal(ClientThemeConfig.get().cardTint + "%"),
                inner + LABEL_W + 72, row(y + 68), UiTheme.ACCENT, false});
        y += 116;

        put(this.themeResetButton, inner, row(y + 4));
        this.contentTotalH = y + 28;
    }

    /**
     * 界面缩放：布局尺寸跟着变，所以必须重建控件（{@code rebuildWidgets} 会走一遍 init，
     * 画布大小与控件位置就都按新比例重算），不能只 relayout。
     */
    private void stepUiScale(int delta) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        cfg.uiScale = Math.max(60, Math.min(140, cfg.uiScale + delta));
        cfg.save();
        UiTheme.refresh();
        this.rebuildWidgets();
    }

    /** 阴影强度：只影响绘制，重排一次就能把百分比文本刷新 */
    private void stepShadow(int delta) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        cfg.shadow = Math.max(0, Math.min(100, cfg.shadow + delta));
        cfg.save();
        UiTheme.refresh();
        relayout();
    }

    /** 卡片亮度：0 只剩描边，100 是默认，200 明显发亮 */
    private void stepCardTint(int delta) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        cfg.cardTint = Math.max(0, Math.min(200, cfg.cardTint + delta));
        cfg.save();
        UiTheme.refresh();
        relayout();
    }

    /** 遮罩浓度 / 面板不透明度都以 10 为步进，范围 0~100 */
    private void stepBackdrop(int delta) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        cfg.backdrop = Math.max(0, Math.min(100, cfg.backdrop + delta));
        cfg.save();
        UiTheme.refresh();
        relayout();
    }

    private void stepPanelOpacity(int delta) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        cfg.panelOpacity = Math.max(0, Math.min(100, cfg.panelOpacity + delta));
        cfg.save();
        UiTheme.refresh();
        relayout();
    }

    // ---- 主题配色操作：改完立刻落盘 + 重算颜色 token（全界面当帧换肤）----

    private void setAccent(int rgb) {
        setAccentRaw(ClientThemeConfig.toHex(rgb));
    }

    private void setPanel(int rgb) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        cfg.panel = ClientThemeConfig.toHex(rgb);
        cfg.save();
        UiTheme.refresh();
    }

    /** 改渐变端点色：{@code a=true} 改起始色，否则改终止色 */
    private void setGradient(boolean a, int rgb) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        if (a) {
            cfg.gradientA = ClientThemeConfig.toHex(rgb);
        } else {
            cfg.gradientB = ClientThemeConfig.toHex(rgb);
        }
        cfg.save();
        UiTheme.refresh();
        relayout();
    }

    /**
     * HEX 输入框改渐变端点色：只写文件 + 刷新 token，不重排界面。
     * 重排会把输入框的值从配置回填，玩家正在输入时会被打断。
     */
    private void setGradientRaw(boolean a, String hex) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        String h = hex.trim().replace("#", "").toUpperCase();
        if (a) {
            cfg.gradientA = h;
        } else {
            cfg.gradientB = h;
        }
        cfg.save();
        UiTheme.refresh();
    }

    /** 渐变流动速度：0 = 静止，100 = 最快；只影响绘制，重排一次刷新百分比文本 */
    private void stepGradientSpeed(int delta) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        cfg.gradientSpeed = Math.max(0, Math.min(100, cfg.gradientSpeed + delta));
        cfg.save();
        UiTheme.refresh();
        relayout();
    }

    private static String gradientSpeedText() {
        int s = ClientThemeConfig.get().gradientSpeed;
        return s <= 0 ? "OFF" : s + "%";
    }

    private void setRadius(int value) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        cfg.radius = value;
        cfg.save();
        UiTheme.refresh();
        refresh();
    }

    private void setAccentRaw(String hex) {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        cfg.accent = hex.trim().replace("#", "").toUpperCase();
        cfg.save();
        UiTheme.refresh();
        refresh();
    }

    /** 随机一个饱和度和明度都够用的强调色（保证任何底色下文字都够亮） */
    private void randomAccent() {
        setAccent(UiTheme.hsvToRgb((float) Math.random(), 0.55f, 0.95f));
    }

    private void resetTheme() {
        ClientThemeConfig cfg = ClientThemeConfig.get();
        cfg.reset();
        cfg.save();
        UiTheme.refresh();
        // 界面缩放也一起被复位了，重建控件让画布按新比例重算
        this.rebuildWidgets();
    }

    /** 动画开关按钮的文案 */
    private static Component animLabel() {
        return Component.translatable(ClientThemeConfig.get().animations
                ? "aiplayer.gui.button.animOn" : "aiplayer.gui.button.animOff");
    }

    /** 背景模糊开关按钮的文案 */
    private static Component blurLabel() {
        return Component.translatable(ClientThemeConfig.get().blurBackground
                ? "aiplayer.gui.button.blurOn" : "aiplayer.gui.button.blurOff");
    }

    /** 液态玻璃开关按钮的文案 */
    private static Component glassLabel() {
        return Component.translatable(ClientThemeConfig.get().liquidGlass
                ? "aiplayer.gui.button.glassOn" : "aiplayer.gui.button.glassOff");
    }

    /** 圆角开关按钮的文案 */
    private static Component roundLabel() {
        return Component.translatable(ClientThemeConfig.get().roundedCorners
                ? "aiplayer.gui.button.roundOn" : "aiplayer.gui.button.roundOff");
    }

    /** 玻璃刷新帧率按钮的文案（0 表示不限制） */
    private static Component glassFpsLabel() {
        int fps = ClientThemeConfig.get().glassFps;
        return Component.translatable("aiplayer.gui.button.glassFps",
                Component.literal(fps <= 0 ? "∞" : String.valueOf(fps)));
    }

    /** 帧率循环：10 → 15 → 20 → 25 → 30 → 45 → 60 → 不限制 */
    private static int nextGlassFps(int cur) {
        int[] values = {10, 15, 20, 25, 30, 45, 60, 0};
        for (int v : values) {
            if (v > cur) {
                return v;
            }
        }
        return values[0];
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    private AiProfile current() {
        List<AiProfile> list = this.config.profiles;
        if (list.isEmpty()) {
            list.add(new AiProfile());
        }
        if (this.index < 0 || this.index >= list.size()) {
            this.index = 0;
        }
        return list.get(this.index);
    }

    private void selectProfile(int i) {
        if (i < 0 || i >= this.config.profiles.size() || i == this.index) {
            return;
        }
        this.index = i;
        this.scroll = 0;
        refresh();
        relayout();
    }

    private void newProfile() {
        List<AiProfile> list = this.config.profiles;
        String name = "AI1";
        int n = 1;
        while (this.config.find(name) != null) {
            name = "AI" + (++n);
        }
        AiProfile p = new AiProfile();
        p.name = name;
        p.levelId = this.config.defaultLevelId;
        p.personality = AiProfile.randomPersonality();
        list.add(p);
        this.index = list.size() - 1;
        this.config.save();
        refresh();
        relayout();
    }

    private void deleteProfile() {
        List<AiProfile> list = this.config.profiles;
        if (list.size() <= 1) {
            return;
        }
        list.remove(this.index);
        this.index = Math.max(0, this.index - 1);
        this.config.save();
        refresh();
        relayout();
    }

    /**
     * 清空当前 AI 的记忆。
     * <p>
     * 本地先清一遍配置里的持久记忆并落盘（界面上立刻生效），
     * 再发一条服务端指令清掉正在运行的大脑对话上下文，两边都忘干净。
     */
    private void clearMemory() {
        AiProfile p = current();
        p.clearMemory();
        this.config.save();
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        String name = p.name == null ? "" : p.name.trim();
        if (mc != null && mc.player != null && !name.isEmpty()) {
            mc.player.connection.sendCommand("aiplayer memory " + name + " clear");
        }
    }

    // ---- 嘲讽列表编辑器 ----

    /** 把输入框里的内容写回 {@code customTaunts}（仍用 | 分隔，数据模型不变） */
    private void applyTaunts() {
        if (this.sync) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < this.tauntCount; i++) {
            String v = this.tauntBoxes[i].getValue().trim();
            if (v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(v);
        }
        current().customTaunts = sb.toString();
        markDirty();
    }

    private void addTaunt() {
        if (this.tauntCount >= MAX_TAUNT_ROWS) {
            return;
        }
        applyTaunts();
        this.tauntCount++;
        refreshTauntRows();
        markDirty();
        relayout();
    }

    private void removeTaunt(int idx) {
        applyTaunts();
        List<String> lines = current().tauntLines();
        if (idx >= 0 && idx < lines.size()) {
            lines.remove(idx);
        }
        current().customTaunts = String.join("|", lines);
        this.tauntCount = Math.min(lines.size(), MAX_TAUNT_ROWS);
        refreshTauntRows();
        markDirty();
        relayout();
    }

    /** 把 profile 的嘲讽文案摊到各行输入框上 */
    private void refreshTauntRows() {
        this.sync = true;
        fillTauntRows();
        this.sync = false;
    }

    private void fillTauntRows() {
        List<String> lines = current().tauntLines();
        for (int i = 0; i < MAX_TAUNT_ROWS; i++) {
            this.tauntBoxes[i].setValue(i < this.tauntCount && i < lines.size() ? lines.get(i) : "");
        }
    }

    /** 把当前档案的值刷到控件上（不触发 responder 写回） */
    private void refresh() {
        AiProfile p = current();
        this.sync = true;
        if (this.llmButton != null) {
            this.llmButton.setMessage(Component.translatable(
                    this.config.llmEnabled ? "aiplayer.gui.llm.on" : "aiplayer.gui.llm.off"));
            this.llmButton.accent(this.config.llmEnabled);
        }
        if (this.dualApiButton != null) {
            this.dualApiButton.setMessage(dualApiLabel());
            this.dualApiButton.accent(this.config.dualApi);
        }
        set(this.chatUrlBox, this.config.chatApiBaseUrl);
        set(this.chatKeyBox, this.config.chatApiKey);
        set(this.chatModelBox, this.config.chatModel);
        if (this.levelButton != null) {
            this.levelButton.setMessage(Component.translatable("aiplayer.gui.label.levelValue",
                    p.levelId, Component.translatable("aiplayer.level." + p.levelId)));
        }
        if (this.bridgeButton != null) {
            this.bridgeButton.setMessage(Component.translatable("aiplayer.gui.label.bridgeValue",
                    Component.translatable("aiplayer.bridge." + p.bridgeMode)));
        }
        set(this.nameBox, p.name);
        set(this.followBox, p.follow);
        set(this.teamBox, p.team);
        set(this.speedBox, String.valueOf(p.bridgeSpeed));
        set(this.kitBox, p.kitItems);
        set(this.personalityBox, p.personality);
        if (this.skinBox != null) {
            this.skinBox.setValue(p.skinName == null ? "" : p.skinName);
            this.skinAppliedValue = p.skinName == null ? "" : p.skinName;
            this.skinPendingTicks = 0;
        }
        this.tauntCount = Math.min(p.tauntLines().size(), MAX_TAUNT_ROWS);
        fillTauntRows();
        if (this.ttsButton != null) {
            this.ttsButton.setMessage(ttsLabel());
            this.ttsButton.accent(ClientTtsConfig.get().ttsEnabled);
        }
        if (this.ttsVoiceButton != null) {
            this.ttsVoiceButton.setMessage(ttsVoiceLabel());
        }
        for (ToggleChip chip : this.chips) {
            chip.setValue(p.getFeature(chip.key()));
        }
        // 外观页：色值输入框回显当前强调色
        set(this.accentHexBox, ClientThemeConfig.toHex(ClientThemeConfig.get().accentRgb()));
        for (int i = 0; i < this.radiusButtons.length; i++) {
            if (this.radiusButtons[i] != null) {
                this.radiusButtons[i].accent(ClientThemeConfig.get().radius == RADIUS_PRESETS[i]);
            }
        }
        if (this.animButton != null) {
            this.animButton.setMessage(animLabel());
        }
        if (this.blurButton != null) {
            this.blurButton.setMessage(blurLabel());
            this.blurButton.accent(ClientThemeConfig.get().blurBackground);
        }
        if (this.glassButton != null) {
            this.glassButton.setMessage(glassLabel());
            this.glassButton.accent(ClientThemeConfig.get().liquidGlass);
        }
        if (this.roundButton != null) {
            this.roundButton.setMessage(roundLabel());
            this.roundButton.accent(ClientThemeConfig.get().roundedCorners);
        }
        if (this.glassFpsButton != null) {
            this.glassFpsButton.setMessage(glassFpsLabel());
            this.glassFpsButton.accent(ClientThemeConfig.get().glassFps > 0);
        }
        this.sync = false;
    }

    private void set(EditBox box, String value) {
        if (box != null) {
            box.setValue(value == null ? "" : value);
        }
    }

    // ------------------------------------------------------------------
    // 存档 / 同步
    // ------------------------------------------------------------------

    /** 任意改动：置脏，停 0.8 秒后在 tick() 里落盘 */
    private void markDirty() {
        this.dirtyAt = System.currentTimeMillis();
    }

    /** 立刻落盘 + 同步给服务端（底栏「存盘」按钮用） */
    private void saveNow() {
        this.config.save();
        this.dirtyAt = 0;
        syncToServer();
    }

    /**
     * 联机到专用服务器时，把配置同步过去。
     * <p>
     * 单人 / 局域网主机：客户端与服务端同进程、同一份配置对象，不用发。
     */
    private void syncToServer() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null || mc.getSingleplayerServer() != null) {
            return;
        }
        AiProfile p = current();
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new com.aiplayer.net.ProfileSyncPayload(this.config.apiKey, this.config.apiBaseUrl, this.config.model,
                        this.config.dualApi, this.config.chatApiBaseUrl, this.config.chatApiKey,
                        this.config.chatModel));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new com.aiplayer.net.AiProfileSyncPayload(p.name, p.skinName, p.personality, p.customTaunts));
    }

    /**
     * 皮肤热更的防抖：输入框每敲一个字符都会回调，不能立刻重建 AI，
     * 所以停下来 1 秒（20 tick）没有再输入才应用一次。
     * <p>
     * 顺带处理自动存盘：改动后 0.8 秒没有新输入就落盘一次。
     */
    @Override
    public void tick() {
        super.tick();
        // 平滑滚动：每 tick 向目标逼近一截，到位即停（避免滚轮那种"瞬移"的硬感）
        boolean moved = false;
        if (Math.abs(this.scrollTarget - this.scroll) > 0.4f) {
            this.scroll += (this.scrollTarget - this.scroll) * 0.38f;
            if (Math.abs(this.scrollTarget - this.scroll) <= 0.4f) {
                this.scroll = this.scrollTarget;
            }
            moved = true;
        }
        if (Math.abs(this.listScrollTarget - this.listScroll) > 0.4f) {
            this.listScroll += (this.listScrollTarget - this.listScroll) * 0.38f;
            if (Math.abs(this.listScrollTarget - this.listScroll) <= 0.4f) {
                this.listScroll = this.listScrollTarget;
            }
            moved = true;
        }
        if (moved) {
            relayout();
        }
        if (this.scrollTimer > 0) {
            this.scrollTimer--;
        }
        if (this.skinPendingTicks > 0 && --this.skinPendingTicks == 0) {
            applyPendingSkin();
        }
        if (this.dirtyAt != 0 && System.currentTimeMillis() - this.dirtyAt > 800L) {
            this.dirtyAt = 0;
            this.config.save();
            syncToServer();
        }
    }

    /**
     * 真正把皮肤应用到世界里的 AI。
     * <p>
     * 装了 CustomSkinLoader 的客户端：CSL 是按<b>玩家名</b>解析皮肤的，会忽略服务端随
     * PlayerInfo 下发的 textures 属性，所以先把皮肤 PNG 落到
     * {@code CustomSkinLoader/LocalSkin/skins/<AI名>.png}（见 {@link CslSkinWriter}），
     * AI 保留自己的名字也能穿上指定皮肤。
     * <p>
     * 同时通知服务端：26.2 客户端把 PlayerInfo 缓存在实体私有字段里且只在为 null 时懒加载，
     * 改 GameProfile 再重播 PlayerInfo 换不掉皮肤，只能由服务端用新 profile 重新生成实体，
     * 位置 / 朝向 / 血量 / 背包由 {@link AiPlayerManager#applySkin} 全部保留。
     */
    private void applyPendingSkin() {
        String name = this.skinPendingName;
        String value = this.skinPendingValue;
        this.skinPendingName = null;
        this.skinPendingValue = null;
        if (name == null || value == null || value.equals(this.skinAppliedValue)) {
            return;
        }
        this.skinAppliedValue = value;
        this.config.save();
        CslSkinWriter.install(name, value);
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            // 远程服：本地那份文件已经写好了，服务端档案由 syncToServer 同步
            syncToServer();
            return;
        }
        String aiName = name;
        server.execute(() -> AiPlayerManager.get().applySkin(server, aiName, value));
    }

    /**
     * 把当前档案的 AI 生成到世界里。
     * <p>
     * 单人 / 局域网主机：客户端与服务端同进程，直接排队到服务端线程生成，
     * 不走 {@code ClientPacketListener.sendCommand}（带参数的命令要先在客户端镜像命令树上
     * parse，还牵扯命令签名，任何一环没对上就完全静默 —— 没反应、不报错、服务端也没日志）。
     * 连别人的服务器时才退回发指令。
     */
    private void spawnProfile() {
        AiProfile p = current();
        this.config.save();
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        String name = p.name == null ? "" : p.name.trim();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            mc.player.sendSystemMessage(Component.translatable("aiplayer.gui.spawnBadName"));
            return;
        }
        // 生成前先把 CSL 本地皮肤写好，AI 一进世界就能穿上（没装 CSL 时是空操作）
        CslSkinWriter.install(name, p.skinName);
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            UUID owner = mc.player.getUUID();
            server.execute(() -> AiPlayerManager.get().spawnNear(server, owner, name));
        } else {
            mc.player.connection.sendCommand("aiplayer spawn " + name);
        }
    }

    // ------------------------------------------------------------------
    // AI 列表页的整体操作
    // ------------------------------------------------------------------

    /** 对当前选中的 AI 发一条服务端指令（前缀 + 名字，例如 {@code aiplayer tp }） */
    private void aiCommand(String prefix) {
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        String name = current().name == null ? "" : current().name.trim();
        if (name.isEmpty()) {
            return;
        }
        mc.player.connection.sendCommand(prefix + name);
    }

    /** 切换"开服自动恢复"并立即落盘 */
    private void toggleAutoKeep() {
        AiProfile p = current();
        p.autoKeep = !p.autoKeep;
        this.config.save();
        if (this.listKeepButton != null) {
            this.listKeepButton.setMessage(Component.translatable(p.autoKeep
                    ? "aiplayer.gui.button.autoKeepOn" : "aiplayer.gui.button.autoKeep"));
            this.listKeepButton.accent(p.autoKeep);
        }
    }

    /** 移除世界里所有 AI（档案保留，下次还能重新生成）；第一次点击只是"上膛" */
    private void removeAllAi() {
        long now = System.currentTimeMillis();
        if (now - this.removeAllArmedAt > 3000L) {
            this.removeAllArmedAt = now;
            this.removeAllLabelArmed = true;
            if (this.listRemoveAllButton != null) {
                this.listRemoveAllButton.setMessage(Component.translatable("aiplayer.gui.button.removeAllConfirm"));
                this.listRemoveAllButton.accent(true);
            }
            return;
        }
        this.removeAllArmedAt = 0;
        this.removeAllLabelArmed = false;
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        mc.player.connection.sendCommand("aiplayer removeall");
    }

    /** 跟随目标建议：在 "无 → 在线玩家…" 之间循环，避免手打名字打错 */
    private void cycleFollow() {
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || this.followBox == null) {
            return;
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add("");
        if (mc.level != null) {
            for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
                if (p != mc.player) {
                    names.add(p.getScoreboardName());
                }
            }
        }
        int idx = names.indexOf(this.followBox.getValue().trim());
        String next = names.get((idx + 1) % names.size());
        this.followBox.setValue(next);
        current().follow = next;
        markDirty();
    }

    /** 把命名模板（{@code 红队{n}}）套到所有行，{n} 从 1 开始 */
    private void applyNameTemplate() {
        if (this.batchTemplateBox == null) {
            return;
        }
        String tpl = this.batchTemplateBox.getValue().trim();
        if (tpl.isEmpty()) {
            return;
        }
        for (int i = 0; i < this.batchRows; i++) {
            String v = tpl.contains("{n}") ? tpl.replace("{n}", String.valueOf(i + 1)) : tpl + (i + 1);
            if (v.length() > 16) {
                v = v.substring(0, 16);
            }
            this.batchNameBoxes[i].setValue(v);
        }
    }

    /** 队伍颜色：同一队名永远同一个颜色，方便在侧栏 / 列表里一眼分阵营 */
    private static final int[] TEAM_COLORS = {
            0xFF6FA8FF, 0xFFFFA94D, 0xFF7ADFA0, 0xFFFF7A7A, 0xFFC792EA, 0xFF5FD6D6,
    };

    private static int teamColor(String team) {
        if (team == null || team.isBlank()) {
            return UiTheme.TEXT_MUTED;
        }
        return TEAM_COLORS[(team.trim().toLowerCase(java.util.Locale.ROOT).hashCode() & 0x7FFFFFFF)
                % TEAM_COLORS.length];
    }

    /** 这只 AI 能不能说话：全局开着 LLM，且它自己或全局有 Key */
    private boolean llmReady(AiProfile p) {
        if (!this.config.llmEnabled) {
            return false;
        }
        String key = this.config.keyFor(p);
        return key != null && !key.isBlank();
    }

    // ------------------------------------------------------------------
    // 批量生成
    // ------------------------------------------------------------------

    private void moreBatchRows() {
        this.batchRows = Math.min(MAX_BATCH_ROWS, this.batchRows + 1);
        relayout();
    }

    private void lessBatchRows() {
        this.batchRows = Math.max(1, this.batchRows - 1);
        relayout();
    }

    /** 强度循环 1 → 2 → 3 → 4 → 1 */
    private void cycleBatchLevel(int idx) {
        this.batchLevels[idx] = this.batchLevels[idx] % 4 + 1;
        this.batchLevelButtons[idx].setMessage(Component.literal("Lv" + this.batchLevels[idx]));
    }

    /**
     * 一键生成：把每一行的"名称 / 强度 / 皮肤"打包成一份清单发给服务端。
     * <p>
     * 名称留空时由服务端自动补 AI1、AI2……（{@code AiPlayerManager} 里查重配置档案 + 活体）。
     */
    private void spawnBatch() {
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        java.util.List<AiPlayerManager.BatchSpec> specs = new java.util.ArrayList<>();
        for (int i = 0; i < this.batchRows; i++) {
            AiPlayerManager.BatchSpec spec = new AiPlayerManager.BatchSpec();
            spec.name = this.batchNameBoxes[i].getValue().trim();
            spec.skin = this.batchSkinBoxes[i].getValue().trim();
            spec.team = this.batchTeamBox == null ? "" : this.batchTeamBox.getValue().trim();
            spec.level = this.batchLevels[i];
            specs.add(spec);
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            UUID owner = mc.player.getUUID();
            server.execute(() -> {
                int made = AiPlayerManager.get().spawnBatchNear(server, owner, specs);
                net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(owner);
                if (sp != null) {
                    sp.sendSystemMessage(Component.translatable("aiplayer.cmd.spawnManyOk", made));
                }
                mc.execute(this::refresh);
            });
        } else {
            // 联机：名称 / 强度 / 皮肤没法塞进原版命令参数，走专用网络包发给服务端
            java.util.List<com.aiplayer.net.BatchSpawnPayload.Entry> entries = new java.util.ArrayList<>();
            for (AiPlayerManager.BatchSpec spec : specs) {
                entries.add(new com.aiplayer.net.BatchSpawnPayload.Entry(spec.name, spec.skin, spec.level, spec.team));
            }
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new com.aiplayer.net.BatchSpawnPayload(entries));
            mc.player.sendSystemMessage(Component.translatable("aiplayer.gui.batchSent"));
        }
    }

    /**
     * 打开当前档案的装备包箱子 GUI。
     * <p>
     * 单人 / 局域网主机：客户端本地玩家和服务端玩家是两个实例，
     * 必须用 UUID 去 {@code PlayerList} 拿服务端那个 {@link net.minecraft.server.level.ServerPlayer}
     * 才能开容器菜单；连别人的服务器时退回发 {@code /aiplayer kit} 指令。
     */
    private void openKitChest() {
        AiProfile p = current();
        this.config.save();
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            UUID owner = mc.player.getUUID();
            server.execute(() -> {
                net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(owner);
                if (sp != null) {
                    com.aiplayer.gui.KitChestGui.open(sp, p);
                }
            });
        } else {
            mc.player.connection.sendCommand("aiplayer kit " + p.name);
        }
    }

    /** 全局默认装备包（JSON 完整快照）：AI 档案没配自己的 kit 时用这一套 */
    private void openDefaultKit() {
        this.config.save();
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            UUID owner = mc.player.getUUID();
            server.execute(() -> {
                net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(owner);
                if (sp != null) {
                    com.aiplayer.gui.KitChestGui.open(sp,
                            Component.translatable("aiplayer.kit.defaultTitle"),
                            this.config.defaultKitNbt, json -> this.config.defaultKitNbt = json);
                }
            });
        } else {
            // 远程服务器：转发到服务端指令，由服务端开 GUI、写服务端配置
            mc.player.connection.sendCommand("aiplayer defaultkit");
        }
    }

    private void openUrl(String url) {
        try {
            com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(url));
        } catch (Exception e) {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Component.literal(url));
            }
        }
    }

    /** 语音开关按钮的文案 */
    private static Component ttsLabel() {
        return Component.translatable("aiplayer.gui.button.tts")
                .append(Component.translatable(ClientTtsConfig.get().ttsEnabled
                        ? "aiplayer.gui.tts.on" : "aiplayer.gui.tts.off"));
    }

    /** 混战开关文案：显示当前是开还是关 */
    private Component duelLabel() {
        return Component.translatable("aiplayer.gui.button.duel")
                .append(Component.literal(this.config.aiDuel ? "：开" : "：关"));
    }

    /** 语音包按钮的文案：没选就显示「系统默认」，选了就显示语音名 */
    private static Component ttsVoiceLabel() {
        String voice = ClientTtsConfig.get().ttsVoice;
        return Component.translatable("aiplayer.gui.button.ttsVoice").append(": ")
                .append(voice == null || voice.isBlank()
                        ? Component.translatable("aiplayer.gui.tts.voiceDefault")
                        : Component.literal(voice));
    }

    /**
     * 在系统已安装的语音之间循环切换。
     * <p>
     * 语音列表要问系统（跑一次 PowerShell 列举 {@code System.Speech} 的语音），
     * 第一次查询要 0.3~3 秒 —— 必须放后台线程，否则点一下整个游戏卡住。
     * 结果有缓存，后续点击是瞬时的（{@code requestVoices} 会同步立刻回调）。
     */
    private void cycleVoice() {
        ClientTtsConfig cfg = ClientTtsConfig.get();
        com.aiplayer.client.tts.TtsSpeaker.get().requestVoices(voices -> {
            List<String> options = new ArrayList<>();
            options.add("");
            options.addAll(voices);
            int cur = options.indexOf(cfg.ttsVoice == null ? "" : cfg.ttsVoice);
            int next = (cur < 0 ? 0 : cur) + 1;
            if (next >= options.size()) {
                next = 0;
            }
            cfg.ttsVoice = options.get(next);
            cfg.save();
            if (this.ttsVoiceButton != null) {
                this.ttsVoiceButton.setMessage(ttsVoiceLabel());
            }
            if (cfg.ttsEnabled) {
                com.aiplayer.client.tts.TtsSpeaker.get().preview();
            }
        });
    }

    private void closeScreen() {
        // 关界面前把还没落地的皮肤改动 + 未存盘的改动一起落地
        if (this.skinPendingTicks > 0) {
            this.skinPendingTicks = 0;
            applyPendingSkin();
        }
        this.config.save();
        this.dirtyAt = 0;
        syncToServer();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    @Override
    public void onClose() {
        closeScreen();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // Ctrl+F（GLFW 键码 70）：跳到列表页并聚焦搜索框
        if (event.hasControlDown() && event.key() == 70) {
            switchTab(6);
            if (this.listSearchBox != null) {
                this.listSearchBox.setFocused(true);
                this.setFocused(this.listSearchBox);
            }
            return true;
        }
        // Enter（257）/ 小键盘 Enter（335）：在档案页 = 生成，在批量页 = 批量生成
        boolean editing = this.getFocused() instanceof EditBox;
        if (!editing && (event.key() == 257 || event.key() == 335)) {
            if (this.tab == 5) {
                spawnBatch();
                return true;
            }
            if (this.tab == 0) {
                spawnProfile();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        event = scaleEvent(event);
        double mx = event.x();
        double my = event.y();
        // 左侧 AI 列表：点一下切换选中的 AI
        for (int[] r : this.listRows) {
            if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                selectProfile(r[4]);
                return true;
            }
        }
        // 列表页的行：点一下选中这只 AI，下面的按钮就作用在它身上
        for (int[] r : this.aiRows) {
            if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                selectProfile(r[4]);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** 是否需要缩放：放大档（>100%）同样要乘 pose，不能只判 &lt; 1 */
    private boolean scaled() {
        return Math.abs(this.uiScale - 1.0F) > 0.001F;
    }

    /** 缩放生效时把鼠标坐标从真实屏幕换算到布局用的虚拟坐标 */
    private net.minecraft.client.input.MouseButtonEvent scaleEvent(net.minecraft.client.input.MouseButtonEvent e) {
        if (scaled()) {
            return new net.minecraft.client.input.MouseButtonEvent(
                    e.x() / this.uiScale, e.y() / this.uiScale, e.buttonInfo());
        }
        return e;
    }

    /** 悬停高亮：原版按真实坐标分发，控件在虚拟坐标里，必须换算后再交给 super */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (scaled()) {
            super.mouseMoved(mouseX / this.uiScale, mouseY / this.uiScale);
        } else {
            super.mouseMoved(mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        if (scaled()) {
            return super.mouseDragged(scaleEvent(event), dragX / this.uiScale, dragY / this.uiScale);
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        return super.mouseReleased(scaleEvent(event));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scaled()) {
            mouseX /= this.uiScale;
            mouseY /= this.uiScale;
        }
        if (inside(mouseX, mouseY, this.contentX - 6, this.contentY,
                this.contentX + this.contentW, this.contentY + this.contentH)) {
            int max = Math.max(0, this.contentTotalH - this.contentH);
            this.scrollTarget = clampF(this.scrollTarget - (float) scrollY * 24f, 0, max);
            this.scrollTimer = 10;
            return true;
        }
        if (inside(mouseX, mouseY, this.left, this.top + TOP_H,
                this.left + SIDEBAR_W, this.sidebarBottom)) {
            int viewH = Math.max(0, this.sidebarBottom - 46 - (this.top + TOP_H + 26));
            int max = Math.max(0, this.listTotalH - viewH);
            this.listScrollTarget = clampF(this.listScrollTarget - (float) scrollY * 18f, 0, max);
            this.scrollTimer = 10;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static boolean inside(double x, double y, int x1, int y1, int x2, int y2) {
        return x >= x1 && x < x2 && y >= y1 && y < y2;
    }

    private static float clampF(float v, int min, int max) {
        if (max < min) {
            return min;
        }
        return v < min ? min : Math.min(v, max);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 每次绘制前重算主题 token：改配色 / 圆角后当帧就换肤
        UiTheme.refresh();
        boolean glass = com.aiplayer.client.gui.glass.GlassLayer.usable();
        if (glass) {
            com.aiplayer.client.gui.glass.GlassLayer.beginFrame();
        }
        // 原版 super 在游戏内会对整个画面跑一次全屏模糊后处理；关掉后背景保持清晰，帧率明显回升
        boolean inGame = this.minecraft != null && this.minecraft.level != null;
        if (glass) {
            // 玻璃通道：遮罩先画、再要求"在此分层模糊"。这样模糊阶段拷贝到的画面
            // 已经包含压暗的世界，玻璃面板叠在它上面，面板外仍是正常的暗背景。
            if (!inGame) {
                extractPanorama(g, partialTick);
            }
            float fadeIn = this.openFade.to(1f);
            g.fill(0, 0, this.width, this.height, UiTheme.alpha(UiTheme.BACKDROP, fadeIn));
            g.blurBeforeThisStratum();
        } else if (!inGame || ClientThemeConfig.get().blurBackground) {
            super.extractBackground(g, mouseX, mouseY, partialTick);
        }
        // 自动缩放：整个面板（背景 + 全部控件）按 uiScale 缩小 / 放大绘制，鼠标坐标反向换算
        if (scaled()) {
            g.pose().pushMatrix();
            g.pose().scale(this.uiScale, this.uiScale);
            mouseX = Math.round(mouseX / this.uiScale);
            mouseY = Math.round(mouseY / this.uiScale);
        }
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        // 入场动画：遮罩随面板淡入
        float fade = this.openFade.to(1f);
        if (!glass) {
            // 玻璃通道里遮罩已经在模糊分层之前画过了
            g.fill(0, 0, this.layoutW, this.layoutH, UiTheme.alpha(UiTheme.BACKDROP, fade));
        }
        int x2 = this.left + PANEL_W;
        int y2 = this.top + this.panelH;
        UiTheme.panel(g, this.left, this.top, x2, y2);
        UiTheme.header(g, this.left, this.top, x2, this.top + TOP_H);
        drawSheen(g, fade);

        drawHeader(g);
        drawSidebar(g);
        drawTabStrip(g);
        drawContent(g);
        drawBottom(g);
        drawScrollbar(g);
        if (scaled()) {
            g.pose().popMatrix();
        }
    }

    /** 控件层（按钮 / 输入框等）也要跟着整体缩放，悬停判定用换算后的虚拟坐标 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 二次确认有 3 秒时限：过了就恢复按钮文案，不然玩家会以为还处在"已上膛"状态
        if (this.removeAllLabelArmed && System.currentTimeMillis() - this.removeAllArmedAt >= 3000L) {
            this.removeAllLabelArmed = false;
            if (this.listRemoveAllButton != null) {
                this.listRemoveAllButton.setMessage(Component.translatable("aiplayer.gui.button.removeAll"));
                this.listRemoveAllButton.accent(false);
            }
        }
        if (scaled()) {
            g.pose().pushMatrix();
            g.pose().scale(this.uiScale, this.uiScale);
            super.extractRenderState(g, Math.round(mouseX / this.uiScale), Math.round(mouseY / this.uiScale), partialTick);
            g.pose().popMatrix();
        } else {
            super.extractRenderState(g, mouseX, mouseY, partialTick);
        }
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        int cx = this.left + 14;
        int ty = this.top + 10;
        // logo 按顶栏高度自动取边长（28px 顶栏 → 16px 图标），居中留 6px 边距
        int logoSize = Math.max(8, Math.min(18, TOP_H - 12));
        UiTheme.brand(g, cx, this.top + (TOP_H - logoSize) / 2, logoSize);
        Component title = UiTheme.ui(Component.translatable("aiplayer.gui.title"));
        UiTheme.text(g, this.font, title, cx + logoSize + 6, ty, UiTheme.TEXT);
        int titleRight = cx + logoSize + 6 + this.font.width(title) + 8;
        Component ver = UiTheme.ui(Component.literal(modVersion()));
        int verW = this.font.width(ver) + 8;
        UiTheme.badge(g, titleRight, ty - 1, titleRight + verW, ty + 10, true);
        UiTheme.text(g, this.font, ver, titleRight + 4, ty, UiTheme.ACCENT);
    }

    private void drawTabStrip(GuiGraphicsExtractor g) {
        int y1 = this.top + TOP_H;
        int y2 = y1 + TAB_H;
        g.fill(this.left + SIDEBAR_W, y1, this.left + PANEL_W, y2, 0x40000000);
        UiTheme.divider(g, this.left + SIDEBAR_W, y2 - 1, this.left + PANEL_W);
        // 活动标签下的滑动指示条：切页时从一个标签平滑滑到另一个。
        // 改成胶囊（半圆两端）后再加光晕，滑动时不会露出直角端头。
        float p = this.tabPos.to(this.tab);
        int w = this.tabW;
        int x = this.left + SIDEBAR_W + 4 + Math.round(p * (w + TAB_GAP));
        UiTheme.glow(g, x, y2 - 3, x + w, y2, 1, UiTheme.ACCENT, 0.85f);
        UiTheme.pill(g, x + 1, y2 - 3, x + w - 1, y2, UiTheme.ACCENT);
    }

    /** 入场时从顶栏扫过的一道柔光（纯装饰；动画关闭时直接跳过） */
    private void drawSheen(GuiGraphicsExtractor g, float fade) {
        if (fade >= 0.999f) {
            return;
        }
        int y1 = this.top + 2;
        int y2 = this.top + TOP_H - 1;
        int bandW = 72;
        int cx = this.left + (int) (fade * 1.15f * PANEL_W);
        int step = bandW / 12;
        // 每列单独算"上沿能从哪开始"：以前是整体让出 4+RADIUS_PANEL 的左右内边距，
        // 扫光还没到面板边缘就断掉；现在按面板圆角的圆弧逐列裁剪，光线能一直扫到边框上，
        // 又不会糊到圆角外侧（本该透出背景的地方）。
        int r = UiTheme.RADIUS_PANEL;
        for (int i = 0; i < 12; i++) {
            int px = cx - bandW / 2 + i * step;
            int px2 = px + step;
            if (px < this.left || px2 > this.left + PANEL_W) {
                continue;
            }
            int top = Math.max(y1, this.top + arcTop(px, px2, r));
            if (top >= y2) {
                continue;
            }
            float d = Math.abs(i - 5.5f) / 5.5f;
            int a = (int) ((1f - d) * 34f * (1f - fade));
            if (a > 0) {
                g.fill(px, top, px2, y2, (a << 24) | 0xFFFFFF);
            }
        }
    }

    /**
     * 面板顶部圆角在 {@code [px, px2]} 这段横向区间内的上沿偏移。
     * <p>
     * 取区间内最深的那一点，保证整列都落在面板形状内部（宁可少画一像素，也不越出圆角）。
     */
    private int arcTop(int px, int px2, int r) {
        if (px2 <= this.left + r) {
            return arcTopAt(px, this.left + r, r);
        }
        if (px >= this.left + PANEL_W - r) {
            return arcTopAt(px2, this.left + PANEL_W - r, r);
        }
        return 0;
    }

    /** 圆心在 {@code cx}、半径 {@code r} 的左下 / 右下圆弧，在横坐标 {@code x} 处距顶边的偏移 */
    private static int arcTopAt(int x, int cx, int r) {
        int dx = Math.abs(cx - x);
        if (dx >= r) {
            return 0;
        }
        return r - (int) Math.sqrt((double) (r * r - dx * dx));
    }

    private void drawSidebar(GuiGraphicsExtractor g) {
        int x1 = this.left;
        int y1 = this.top + TOP_H;
        int x2 = this.left + SIDEBAR_W;
        int y2 = this.sidebarBottom;
        g.fill(x1, y1, x2, y2, 0x33000000);
        g.fill(x2 - 1, y1, x2, y2, UiTheme.CARD_BORDER);

        UiTheme.text(g, this.font, Component.translatable("aiplayer.gui.list.title"),
                x1 + 10, y1 + 7, UiTheme.TEXT_DIM);

        // 侧栏同样只画可见的行：AI 多到几十只时，屏外那些行不必每帧提交圆角四边形
        int listTopY = this.top + TOP_H + 26;
        int listBottomY = this.sidebarBottom - 46;
        for (int[] r : this.listRows) {
            if (r[3] < listTopY || r[1] > listBottomY) {
                continue;
            }
            boolean selected = r[4] == this.index;
            boolean hovered = inside(this.mouseX, this.mouseY, r[0], r[1], r[2], r[3]);
            if (selected || hovered) {
                UiTheme.glow(g, r[0], r[1], r[2], r[3], UiTheme.RADIUS_CARD,
                        selected ? UiTheme.ACCENT : UiTheme.ACCENT_DIM, selected ? 0.55f : 0.32f);
            }
            UiTheme.fillRounded(g, r[0], r[1], r[2], r[3], UiTheme.RADIUS_CARD,
                    selected ? UiTheme.ACCENT_SOFT : (hovered ? 0x1CFFFFFF : 0x14FFFFFF));
            if (selected) {
                UiTheme.outlineRounded(g, r[0], r[1], r[2], r[3], UiTheme.RADIUS_CARD, 1, UiTheme.ACCENT_DIM);
            }
            AiProfile p = this.config.profiles.get(r[4]);
            String name = p.name;
            drawDot(g, r[0] + 5, r[1] + (r[3] - r[1] - 4) / 2, statusOf(name));
            UiTheme.text(g, this.font, Component.literal(name == null ? "AI" : name),
                    r[0] + 14, r[1] + (r[3] - r[1] - 8) / 2,
                    selected || hovered ? UiTheme.TEXT : UiTheme.TEXT_DIM);
            // 右侧：队伍色条（同一支队伍颜色固定）+ LLM 就绪指示
            int teamColor = teamColor(p.team);
            if (p.team != null && !p.team.isBlank()) {
                UiTheme.fillRounded(g, r[2] - 6, r[1] + 3, r[2] - 3, r[3] - 3, 1, teamColor);
            }
            if (!llmReady(p)) {
                UiTheme.text(g, this.font, Component.literal("L"), r[2] - 17, r[1] + (r[3] - r[1] - 8) / 2,
                        UiTheme.DANGER);
            }
        }

        int status = statusOf(current().name);
        int statusY = this.sidebarBottom - 46;
        drawDot(g, x1 + 10, statusY + 2, status);
        UiTheme.text(g, this.font, Component.translatable("aiplayer.gui.status." + statusKey(status)),
                x1 + 20, statusY, UiTheme.TEXT_DIM);
        UiTheme.text(g, this.font, Component.translatable("aiplayer.gui.label.skin"),
                x1 + 10, this.sidebarBottom - 28, UiTheme.TEXT_DIM);
    }

    private static String statusKey(int status) {
        return switch (status) {
            case 0 -> "alive";
            case 1 -> "dead";
            default -> "offline";
        };
    }

    /** 存活状态：0 存活（绿）/ 1 阵亡（红）/ 2 未生成（灰） */
    private int statusOf(String name) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.level == null || name == null || name.isBlank()) {
            return 2;
        }
        for (AbstractClientPlayer p : mc.level.players()) {
            if (name.equals(p.getName().getString())) {
                return p.isAlive() && p.getHealth() > 0f ? 0 : 1;
            }
        }
        return 2;
    }

    private static void drawDot(GuiGraphicsExtractor g, int x, int y, int status) {
        UiTheme.fillRounded(g, x, y, x + 4, y + 4, 2, statusColor(status));
    }

    /** 状态色：绿=存活 / 红=阵亡 / 灰=未生成（头像外框也复用它） */
    private static int statusColor(int status) {
        return switch (status) {
            case 0 -> 0xFF5FD16B;
            case 1 -> UiTheme.DANGER;
            default -> UiTheme.TEXT_MUTED;
        };
    }

    /**
     * 裁剪区域。
     * <p>
     * {@code enableScissor} 内部会用自己的 pose 变换这个矩形（见 GuiGraphicsExtractor：
     * {@code new ScreenRectangle(...).transformAxisAligned(this.pose)}），所以这里必须传
     * <b>虚拟坐标</b>，不能再乘一次 uiScale —— 否则缩放 0.8 时裁剪框会被压成 0.64 倍，
     * 内容大片被裁掉，看起来就是"界面缺一块 / 有脏边"。
     */
    private void scissor(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.enableScissor(x1, y1, x2, y2);
    }

    private void drawContent(GuiGraphicsExtractor g) {
        this.scissor(g, this.contentX - 6, this.contentY - 2,
                this.contentX + this.contentW + 2, this.contentY + this.contentH + 2);
        // 玻璃通道不受 scissor 影响，这里手动给它同一套裁剪框，避免滚动的卡片溢出视口
        com.aiplayer.client.gui.glass.GlassLayer.setClip(g,
                this.contentX - 6, this.contentY - 2,
                this.contentX + this.contentW + 2, this.contentY + this.contentH + 2);
        // 只画与内容视口相交的元素：滚动到屏外的卡片 / 文字 / 输入框不再提交绘制
        int top = this.contentY - 2;
        int bottom = this.contentY + this.contentH + 2;
        for (int[] c : this.cards) {
            if (c[3] < top || c[1] > bottom) {
                continue;
            }
            UiTheme.card(g, c[0], c[1], c[2], c[3]);
        }
        // AI 列表页：每行左侧一个 4px 状态点（绿=存活 / 红=阵亡 / 灰=未生成）
        if (this.tab == 6) {
            for (int[] r : this.aiRows) {
                if (r[3] < top || r[1] > bottom) {
                    continue;
                }
                AiProfile p = this.config.profiles.get(r[4]);
                if (p == null) {
                    continue;
                }
                drawDot(g, r[0] + 6, r[1] + (r[3] - r[1] - 4) / 2, statusOf(p.name));
            }
        }
        for (Object[] t : this.texts) {
            int y = (int) t[2];
            if (y + 12 < top || y > bottom) {
                continue;
            }
            Component text = (Component) t[0];
            int x = (int) t[1];
            int color = (int) t[3];
            if ((boolean) t[4]) {
                UiTheme.section(g, this.font, text, x, y);
            } else {
                UiTheme.text(g, this.font, text, x, y, color);
            }
        }
        for (EditBox box : this.drawFields) {
            if (box != this.skinBox && box.visible
                    && box.getBottom() + 2 >= top && box.getY() - 2 <= bottom) {
                UiTheme.field(g, box.getX() - 3, box.getY() - 1,
                        box.getRight() + 3, box.getBottom() + 1, box.isFocused());
            }
        }
        com.aiplayer.client.gui.glass.GlassLayer.clearClip();
        g.disableScissor();
        // 上下边缘渐隐：内容像被面板裁掉，而不是硬切一刀
        int max = this.contentTotalH - this.contentH;
        int fx1 = this.contentX - 6;
        int fx2 = this.contentX + this.contentW + 2;
        if (this.scroll > 1f) {
            g.fillGradient(fx1, this.contentY - 2, fx2, this.contentY + 8, 0xAA000000, 0x00000000);
        }
        if (this.scroll < max - 1f) {
            int by = this.contentY + this.contentH;
            g.fillGradient(fx1, by - 8, fx2, by + 2, 0x00000000, 0xAA000000);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor g) {
        int max = this.contentTotalH - this.contentH;
        if (max <= 0) {
            return;
        }
        // 滚动时完整显示，停下后淡成一条淡淡的指示条
        float fade = this.scrollbarFade.to(this.scrollTimer > 0 ? 1f : 0.30f);
        int trackX = this.contentX + this.contentW + 4;
        int trackH = this.contentH;
        int barH = Math.max(16, trackH * this.contentH / Math.max(1, this.contentTotalH));
        int barY = this.contentY + (int) ((trackH - barH) * (this.scroll / (float) max));
        // 轨道与滑块都改成胶囊（半径 = 半宽），滚动时不会露出直角端头；
        // 滑块带一点竖直渐变 + 顶部高光，看起来是根实体小棍而不是一条色带。
        UiTheme.pill(g, trackX, this.contentY, trackX + 4, this.contentY + trackH,
                UiTheme.alpha(0x33000000, fade));
        int barBottomColor = UiTheme.alpha(UiTheme.darken(UiTheme.ACCENT, 0.25f), fade);
        int barTopColor = UiTheme.alpha(UiTheme.ACCENT, fade);
        UiTheme.fillRoundedGradient(g, trackX, barY, trackX + 4, barY + barH, 2, barTopColor, barBottomColor);
        if (fade > 0.5f) {
            UiTheme.innerHairline(g, trackX, barY, trackX + 4, barY + barH, 2,
                    UiTheme.alpha(0xFFFFFFFF, 0.30f * fade), true);
        }
    }

    private void drawBottom(GuiGraphicsExtractor g) {
        int y1 = this.top + this.panelH - BOTTOM_H;
        // 用"只圆下面两个角"的填充：普通方角 fill 会把面板底部的圆角盖住，
        // 那块本该透出背景的地方就被涂了一片颜色。
        UiTheme.fillBottomRounded(g, this.left, y1, this.left + PANEL_W, this.top + this.panelH,
                UiTheme.RADIUS_PANEL, 0x40000000);
        UiTheme.divider(g, this.left + 8, y1, this.left + PANEL_W - 8);
        Component tip = UiTheme.ui(Component.translatable("aiplayer.gui.autosaved"));
        UiTheme.text(g, this.font, tip, this.left + PANEL_W - 14 - this.font.width(tip),
                y1 + 11, UiTheme.ACCENT);
    }

    /** 模组版本号：查一次缓存下来，别每帧都问一次 FabricLoader */
    private static String modVersion() {
        if (VERSION_CACHE != null) {
            return VERSION_CACHE;
        }
        try {
            VERSION_CACHE = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("aiplayer")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("dev");
        } catch (Exception e) {
            VERSION_CACHE = "dev";
        }
        return VERSION_CACHE;
    }

    private static String VERSION_CACHE;

    // ------------------------------------------------------------------
    // 图标 / 芯片
    // ------------------------------------------------------------------

    /**
     * 每个功能开关配一枚原版物品图标（{@link Items} 里现成的贴图，不用额外资源）。
     * 语义对应：挖矿→镐、搭路→木板、水晶 PvP→末地水晶、锚 PvP→重生锚、
     * 风弹重锤→重锤、鞘翅长矛→鞘翅、远程→弓、逃生→盾、投掷→珍珠、钓鱼→鱼竿、
     * 种田→小麦、驯服→拴绳、探索→指南针、末影龙→龙蛋。
     */
    private static ItemStack featureIcon(String key) {
        return switch (key) {
            case "mine" -> icon(Items.IRON_PICKAXE);
            case "place" -> icon(Items.COBBLESTONE);
            case "combat" -> icon(Items.IRON_SWORD);
            case "chat" -> icon(Items.PAPER);
            case "command" -> icon(Items.COMMAND_BLOCK);
            case "eat" -> icon(Items.COOKED_BEEF);
            case "equip" -> icon(Items.DIAMOND_CHESTPLATE);
            case "hunt" -> icon(Items.BONE);
            case "bridge" -> icon(Items.OAK_PLANKS);
            case "autoBridge" -> icon(Items.SCAFFOLDING);
            case "crystalPvp" -> icon(Items.END_CRYSTAL);
            case "anchorPvp" -> icon(Items.RESPAWN_ANCHOR);
            case "windMace" -> icon(Items.MACE);
            case "elytraSpear" -> icon(Items.ELYTRA);
            case "ranged" -> icon(Items.BOW);
            case "escape" -> icon(Items.SHIELD);
            case "throwables" -> icon(Items.ENDER_PEARL);
            case "taunt" -> icon(Items.SKELETON_SKULL);
            case "fishing" -> icon(Items.FISHING_ROD);
            case "farm" -> icon(Items.WHEAT);
            case "tame" -> icon(Items.LEAD);
            case "explore" -> icon(Items.COMPASS);
            case "boss" -> icon(Items.DRAGON_EGG);
            default -> ItemStack.EMPTY;
        };
    }

    /**
     * 图标栈只用来绘制，不会被修改。
     * <p>
     * <b>必须延迟到「有世界」时才创建</b>：物品的组件是在进游戏后才绑定的，
     * 主菜单 / 标题界面里 {@code new ItemStack(item)} 会抛
     * {@code NullPointerException: Components not bound yet}
     * （{@code Holder.Reference.components()} 未绑定即报错）。
     * 这条路径正是 ModMenu 的「设置」按钮：它从主菜单打开本界面，
     * init() 里构造图标就炸，异常又发生在渲染阶段 → 整个游戏崩溃。
     * 所以没世界时返回空栈，图标直接不画（见 {@link UiTheme#canDrawItemIcons()}）。
     */
    private static ItemStack icon(Item item) {
        return UiTheme.canDrawItemIcons() ? new ItemStack(item) : ItemStack.EMPTY;
    }

    /** 把 23 个功能开关芯片建出来（宽度依赖窗口尺寸，所以在 init() 里建） */
    private void buildChips(AiProfile p) {
        int chipW = (this.contentW - 16 - (CHIP_COLS - 1) * 6) / CHIP_COLS;
        for (String[] group : GROUPS) {
            for (int i = 1; i < group.length; i++) {
                String key = group[i];
                ToggleChip chip = new ToggleChip(0, 0, chipW, CHIP_H, key, featureIcon(key),
                        Component.translatable("aiplayer.feature." + key), p.getFeature(key),
                        v -> {
                            current().setFeature(key, v);
                            markDirty();
                        });
                chip.setTooltip(Tooltip.create(Component.translatable("aiplayer.gui.chip.tip")));
                this.chips.add(content(chip));
            }
        }
    }
}
