package com.aiplayer.client.gui;

import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayerManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * AI Player 配置界面（默认按 K 打开）。
 * <p>
 * 一个页面同时管全局 API 设置和单个 AI 的档案：强度、搭路方式、跟随对象、皮肤、14 个功能开关。
 * 所有控件都是自绘的（{@link FlatButton} / {@link ToggleChip}），配置直接读写
 * {@link AiPlayerConfig}，单人 / 局域网下与服务端是同一份内存对象，改完立即生效。
 * <p>
 * 文本全部走翻译键（{@code aiplayer.gui.*}），语言文件在
 * {@code assets/aiplayer/lang/} 下。
 * <p>
 * 26.2 注意：Screen 不再有 {@code render(...)}，改画在
 * {@code extractBackground(...)}（保证在所有控件底下）与控件的 {@code extractWidgetRenderState(...)} 里。
 */
public class AiConfigScreen extends Screen {
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 406;
    /** 表单标签宽度 */
    private static final int LABEL_W = 58;
    /** 表单可用总宽 */
    private static final int CONTENT_W = PANEL_W - 28;
    /** 顶栏高度：所有行坐标都从这里往下排，改这一处就能整体下移 */
    private static final int HDR = 18;
    /** 项目地址（「关于」面板里放的） */
    private static final String GITHUB_URL = "https://github.com/thebod2025/aiplayer";

    /** 功能开关（顺序即界面顺序，名字取翻译键 {@code aiplayer.feature.<key>}） */
    private static final String[] FEATURES = {
            "mine", "place", "combat", "chat", "command", "eat", "equip", "hunt",
            "bridge", "autoBridge", "crystalPvp", "anchorPvp", "windMace", "elytraSpear",
            "ranged", "escape", "throwables", "taunt",
            "fishing", "farm", "tame", "explore",
    };

    /** 相对面板顶部的行坐标 */
    private static final int Y_TITLE = 10;
    private static final int Y_API = 28;
    private static final int Y_BASE_URL = 45;
    private static final int Y_API_KEY = 65;
    private static final int Y_MODEL = 85;
    private static final int Y_AI = 111;
    private static final int Y_SELECT = 126;
    private static final int Y_LEVEL = 146;
    private static final int Y_FOLLOW = 166;
    private static final int Y_TEAM = 186;
    private static final int Y_SKIN = 206;
    private static final int Y_KIT = 226;
    private static final int Y_TOGGLES = 251;
    private static final int Y_CHIPS = 264;
    private static final int CHIP_H = 16;
    private static final int CHIP_ROW = 18;
    private static final int Y_BUTTONS = 366;

    /** 分区卡片左右内缩（相对面板） */
    private static final int CARD_INSET = 7;

    private static final int ROW_H = 16;

    private final Screen parent;

    private AiPlayerConfig config;
    private int index;
    /** 同步 UI 时置位，避免 setValue 反过来把值写进别的档案 */
    private boolean sync;

    private int left;
    private int top;

    private EditBox baseUrlBox;
    private EditBox apiKeyBox;
    private EditBox modelBox;
    private EditBox nameBox;
    private EditBox followBox;
    private EditBox teamBox;
    private EditBox skinBox;
    private EditBox kitBox;
    private EditBox speedBox;

    /**
     * 皮肤热更的防抖状态：输入框是逐字符回调的，每敲一个字都重建一次 AI 太浪费，
     * 所以停下 1 秒后再应用一次。
     */
    private String skinPendingName;
    private String skinPendingValue;
    private int skinPendingTicks;
    /** 已应用的皮肤值，避免没改动也触发重建 */
    private String skinAppliedValue = "";

    private FlatButton llmButton;
    private FlatButton levelButton;
    private FlatButton bridgeButton;
    private final List<ToggleChip> chips = new ArrayList<>();
    /** 主界面的所有控件：打开「关于」面板时统一隐藏，别让它们压在弹窗上 */
    private final List<AbstractWidget> mainWidgets = new ArrayList<>();

    /** 「关于」面板是否打开 */
    private boolean aboutOpen;
    private FlatButton aboutLinkButton;
    private FlatButton aboutCloseButton;
    /** 「关于」面板的位置（居中固定，init 时算一次） */
    private int aboutX;
    private int aboutY;
    private static final int ABOUT_W = 306;
    private static final int ABOUT_H = 122;

    private long savedAt;

    public AiConfigScreen(Screen parent) {
        super(Component.translatable("aiplayer.gui.title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        this.config = AiPlayerConfig.get();
        if (this.config.profiles.isEmpty()) {
            this.config.profiles.add(new AiProfile());
        }
        if (this.index < 0 || this.index >= this.config.profiles.size()) {
            this.index = 0;
        }
        this.left = (this.width - PANEL_W) / 2;
        this.top = Math.max(4, (this.height - PANEL_H) / 2);
        this.chips.clear();
        this.mainWidgets.clear();
        this.aboutOpen = false;

        int cx = this.left + 14;
        int fx = cx + LABEL_W;

        this.sync = true;
        this.baseUrlBox = field(fx, row(Y_BASE_URL), CONTENT_W - LABEL_W, this.config.apiBaseUrl,
                Component.literal("https://api.openai.com/v1"), v -> this.config.apiBaseUrl = v.trim());
        this.apiKeyBox = field(fx, row(Y_API_KEY), CONTENT_W - LABEL_W, this.config.apiKey,
                Component.translatable("aiplayer.gui.hint.apiKey"), v -> this.config.apiKey = v.trim());
        // 输入框里显示成 * 号（防偷窥），但 EditBox 内部保存的仍是真实 Key ——
        // addFormatter 只替换渲染出来的文本，getValue() 拿到的还是原文，所以"隐藏但仍可用"。
        this.apiKeyBox.addFormatter((text, offset) ->
                FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        this.modelBox = field(fx, row(Y_MODEL), 176, this.config.model,
                Component.literal("gpt-4o-mini"), v -> this.config.model = v.trim());
        AiProfile profile = current();
        // 档案名（= 世界里 AI 玩家的名字）：可以直接改，生成时用它
        this.nameBox = field(fx + 22, row(Y_SELECT), 108, profile.name,
                Component.literal("AI"), v -> current().name = v.trim());
        this.nameBox.setMaxLength(16);
        this.followBox = field(fx, row(Y_FOLLOW), 150, profile.follow,
                Component.translatable("aiplayer.gui.hint.follow"), v -> current().follow = v.trim());
        this.speedBox = field(fx + 158, row(Y_FOLLOW), 90, String.valueOf(profile.bridgeSpeed),
                Component.translatable("aiplayer.gui.hint.speed"), v -> {
                    try {
                        current().bridgeSpeed = Double.parseDouble(v.trim());
                    } catch (NumberFormatException ignored) {
                        // 输入到一半的非法数字直接忽略，不要写进配置
                    }
                });
        // 队伍：同队的 AI 互不攻击、协战、分工、共享、队形跟随
        this.teamBox = field(fx, row(Y_TEAM), CONTENT_W - LABEL_W, profile.team,
                Component.translatable("aiplayer.gui.hint.team"), v -> current().team = v.trim());
        this.skinBox = field(fx, row(Y_SKIN), CONTENT_W - LABEL_W, profile.skinName,
                Component.translatable("aiplayer.gui.hint.skin"), v -> {
                    String skin = v.trim();
                    AiProfile cur = current();
                    cur.skinName = skin;
                    // 防抖：先在 tick() 里等 1 秒无输入，再真正热更
                    this.skinPendingName = cur.name;
                    this.skinPendingValue = skin;
                    this.skinPendingTicks = 20;
                });
        this.skinAppliedValue = profile.skinName == null ? "" : profile.skinName;
        // 装备包行：左边物品 ID 输入框（兼容手敲），右边大号高亮按钮 = 打开箱子 GUI
        // （可视化拖放编辑，AI 存活时所见即所得，关箱立即替换背包）
        this.kitBox = field(fx, row(Y_KIT), CONTENT_W - LABEL_W - 150, profile.kitItems,
                Component.translatable("aiplayer.gui.hint.kit"), v -> current().kitItems = v.trim());
        this.reg(new FlatButton(fx + CONTENT_W - LABEL_W - 146, row(Y_KIT), 142, ROW_H,
                Component.literal("装备包 ▸ 箱子编辑"), this::openKitChest).accent(true));
        this.sync = false;

        // 全局：LLM 开关
        this.llmButton = this.reg(new FlatButton(fx + 184, row(Y_MODEL), 130, ROW_H,
                Component.translatable("aiplayer.gui.llm.on"), () -> {
                    this.config.llmEnabled = !this.config.llmEnabled;
                    refresh();
                }));

        // 档案选择：◀ / 名字（画在背景层）/ ▶ / 新建 / 删除
        int selX = fx;
        this.reg(new FlatButton(selX, row(Y_SELECT), 18, ROW_H,
                Component.literal("<"), () -> move(-1)));
        this.reg(new FlatButton(selX + 22 + 108, row(Y_SELECT), 18, ROW_H,
                Component.literal(">"), () -> move(1)));
        this.reg(new FlatButton(selX + 162, row(Y_SELECT), 50, ROW_H,
                Component.translatable("aiplayer.gui.button.new"), this::newProfile));
        this.reg(new FlatButton(selX + 218, row(Y_SELECT), 50, ROW_H,
                Component.translatable("aiplayer.gui.button.delete"), this::deleteProfile));
        // 生成：把这名 AI 真正放进世界（配置先存盘，再让服务器按配置生成）
        // 主操作按钮，用品牌色实心，和底部的“保存”区分开
        this.reg(new FlatButton(selX + 272, row(Y_SELECT), 52, ROW_H,
                Component.translatable("aiplayer.gui.button.spawn"), this::spawnProfile).accent(true));

        // 强度 / 搭路方式：点一下点到下一个档位
        this.levelButton = this.reg(new FlatButton(fx, row(Y_LEVEL), 160, ROW_H,
                Component.translatable("aiplayer.gui.label.level"), () -> {
                    AiProfile p = current();
                    p.levelId = p.levelId % 4 + 1;
                    refresh();
                }));
        this.bridgeButton = this.reg(new FlatButton(fx + 168, row(Y_LEVEL), 140, ROW_H,
                Component.translatable("aiplayer.gui.label.bridge"), () -> {
                    AiProfile p = current();
                    p.bridgeMode = (p.bridgeMode + 1) % 5;
                    refresh();
                }));

        // 功能开关：4 列
        int gap = 6;
        int chipW = (CONTENT_W - gap * 3) / 4;
        AiProfile p = current();
        for (int i = 0; i < FEATURES.length; i++) {
            String key = FEATURES[i];
            int col = i % 4;
            int line = i / 4;
            int x = cx + col * (chipW + gap);
            int y = row(Y_CHIPS + line * CHIP_ROW);
            this.chips.add(this.reg(new ToggleChip(x, y, chipW, CHIP_H, key,
                    Component.translatable("aiplayer.feature." + key),
                    p.getFeature(key), v -> current().setFeature(key, v))));
        }

        // 底部：保存 / 关闭 / 默认装备包 / 关于（四个按钮正好排满一行，不超出面板）
        int bx = cx;
        this.reg(new FlatButton(bx, row(Y_BUTTONS), 104, 18,
                Component.translatable("aiplayer.gui.button.save"), this::save).accent(true));
        this.reg(new FlatButton(bx + 110, row(Y_BUTTONS), 78, 18,
                Component.translatable("aiplayer.gui.button.close"), this::closeScreen));
        this.reg(new FlatButton(bx + 194, row(Y_BUTTONS), 106, 18,
                Component.translatable("aiplayer.gui.button.defaultKit"), this::openDefaultKit));
        this.reg(new FlatButton(bx + 306, row(Y_BUTTONS), 66, 18,
                Component.translatable("aiplayer.gui.button.about"), this::toggleAbout));

        // 「关于」弹窗：两个按钮平时隐藏，打开弹窗时才显示（它们不在 mainWidgets 里，不会被自己藏掉）
        this.aboutX = (this.width - ABOUT_W) / 2;
        this.aboutY = Math.max(8, (this.height - ABOUT_H) / 2);
        this.aboutLinkButton = this.addRenderableWidget(new FlatButton(
                this.aboutX + 20, this.aboutY + ABOUT_H - 28, 140, 18,
                Component.translatable("aiplayer.gui.about.open"), this::openGithub).accent(true));
        this.aboutCloseButton = this.addRenderableWidget(new FlatButton(
                this.aboutX + ABOUT_W - 112, this.aboutY + ABOUT_H - 28, 92, 18,
                Component.translatable("aiplayer.gui.button.close"), this::toggleAbout));
        this.aboutLinkButton.visible = false;
        this.aboutCloseButton.visible = false;

        refresh();
    }

    /** 打开 / 关闭「关于」面板：主界面控件跟着一起藏起来 */
    private void toggleAbout() {
        this.aboutOpen = !this.aboutOpen;
        this.aboutLinkButton.visible = this.aboutOpen;
        this.aboutCloseButton.visible = this.aboutOpen;
        for (AbstractWidget w : this.mainWidgets) {
            w.visible = !this.aboutOpen;
            if (this.aboutOpen) {
                w.setFocused(false);
            }
        }
    }

    /** 在系统浏览器里打开项目的 GitHub 仓库 */
    private void openGithub() {
        try {
            net.minecraft.util.Util.getPlatform().openUri(GITHUB_URL);
        } catch (Exception e) {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Component.literal(GITHUB_URL));
            }
        }
    }

    /** 模组版本号：直接从 fabric 的模组元数据里读，免得手工维护两份 */
    private static String modVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("aiplayer")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("dev");
        } catch (Exception e) {
            return "dev";
        }
    }

    /**
     * 注册一个主界面控件：同时记进 {@link #mainWidgets}，这样打开「关于」弹窗时能把它们一起藏起来。
     * （弹窗自己的两个按钮不走这里，永远可见。）
     */
    private <T extends AbstractWidget> T reg(T widget) {
        this.mainWidgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private EditBox field(int x, int y, int width, String value, Component hint, Consumer<String> setter) {
        EditBox box = new EditBox(this.font, x, y, width, ROW_H, Component.empty());
        box.setBordered(false);
        box.setTextColor(UiTheme.TEXT);
        box.setMaxLength(512);
        box.setHint(hint);
        box.setValue(value == null ? "" : value);
        box.setResponder(v -> {
            if (!this.sync) {
                setter.accept(v);
            }
        });
        return this.reg(box);
    }

    private int row(int offset) {
        return this.top + HDR + offset;
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

    private void move(int delta) {
        List<AiProfile> list = this.config.profiles;
        if (list.isEmpty()) {
            return;
        }
        this.index = (this.index + delta + list.size()) % list.size();
        refresh();
    }

    private void newProfile() {
        List<AiProfile> list = this.config.profiles;
        String name = "AI";
        int n = 1;
        while (this.config.find(name) != null) {
            name = "AI" + (++n);
        }
        AiProfile p = new AiProfile();
        p.name = name;
        p.levelId = this.config.defaultLevelId;
        list.add(p);
        this.index = list.size() - 1;
        this.config.save();
        refresh();
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
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            UUID owner = mc.player.getUUID();
            server.execute(() -> AiPlayerManager.get().spawnNear(server, owner, name));
        } else {
            mc.player.connection.sendCommand("aiplayer spawn " + name);
        }
    }

    /**
     * 打开当前档案的装备包箱子 GUI。
     * <p>
     * 单人 / 局域网主机：客户端本地玩家和服务端玩家是两个实例，
     * 必须用 UUID 去 {@code PlayerList} 拿服务端那个 {@link net.minecraft.server.level.ServerPlayer}
     * 才能开容器菜单；连别人的服务器时退回发 {@code /aiplayer kit} 指令。
     */
    /** 全局默认装备包（JSON 完整快照）：AI 档案没配自己的 kit 时用这一套 */
    private void openDefaultKit() {
        this.config.save();
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            java.util.UUID owner = mc.player.getUUID();
            server.execute(() -> {
                net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(owner);
                if (sp != null) {
                    com.aiplayer.gui.KitChestGui.open(sp, "默认装备包（底行=快捷栏）",
                            this.config.defaultKitNbt, json -> this.config.defaultKitNbt = json);
                }
            });
        } else {
            // 远程服务器：转发到服务端指令，由服务端开 GUI、写服务端配置
            mc.player.connection.sendCommand("aiplayer defaultkit");
        }
    }

    private void openKitChest() {
        AiProfile p = current();
        this.config.save();
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            java.util.UUID owner = mc.player.getUUID();
            AiProfile profile = p;
            server.execute(() -> {
                net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(owner);
                if (sp != null) {
                    com.aiplayer.gui.KitChestGui.open(sp, profile);
                }
            });
        } else {
            mc.player.connection.sendCommand("aiplayer kit " + p.name);
        }
    }

    /** 把当前档案的值刷到控件上（不触发 responder 写回） */
    private void refresh() {
        AiProfile p = current();
        this.sync = true;
        this.llmButton.setMessage(Component.translatable(
                this.config.llmEnabled ? "aiplayer.gui.llm.on" : "aiplayer.gui.llm.off"));
        this.llmButton.accent(this.config.llmEnabled);
        this.levelButton.setMessage(Component.translatable("aiplayer.gui.label.levelValue",
                p.levelId, Component.translatable("aiplayer.level." + p.levelId)));
        this.bridgeButton.setMessage(Component.translatable("aiplayer.gui.label.bridgeValue",
                Component.translatable("aiplayer.bridge." + p.bridgeMode)));
        if (this.followBox != null) {
            this.followBox.setValue(p.follow == null ? "" : p.follow);
        }
        if (this.teamBox != null) {
            this.teamBox.setValue(p.team == null ? "" : p.team);
        }
        if (this.nameBox != null) {
            this.nameBox.setValue(p.name == null ? "" : p.name);
        }
        if (this.skinBox != null) {
            this.skinBox.setValue(p.skinName == null ? "" : p.skinName);
            this.skinAppliedValue = p.skinName == null ? "" : p.skinName;
            this.skinPendingTicks = 0;
        }
        if (this.speedBox != null) {
            this.speedBox.setValue(String.valueOf(p.bridgeSpeed));
        }
        if (this.kitBox != null) {
            this.kitBox.setValue(p.kitItems == null ? "" : p.kitItems);
        }
        for (ToggleChip chip : this.chips) {
            chip.setValue(p.getFeature(chip.key()));
        }
        this.sync = false;
    }

    private void save() {
        this.config.save();
        this.savedAt = System.currentTimeMillis();
        // 联机到专用服务器时，AI 真正用的配置在服务端 —— 把 GUI 里的
        // apiKey/apiBaseUrl/model 同步过去（服务端校验 OP 后写进 aiplayer.json）。
        // 单人世界不用发：集成服务端直接读这份 config。
        Minecraft mc = this.minecraft;
        if (mc != null && mc.player != null && mc.getSingleplayerServer() == null) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new com.aiplayer.net.ProfileSyncPayload(
                            this.config.apiKey, this.config.apiBaseUrl, this.config.model));
        }
    }

    /**
     * 皮肤热更的防抖计时：输入框每敲一个字符都会回调，不能立刻重建 AI，
     * 所以停下来 1 秒（20 tick）没有再输入才应用一次。
     */
    @Override
    public void tick() {
        super.tick();
        if (this.skinPendingTicks > 0 && --this.skinPendingTicks == 0) {
            applyPendingSkin();
        }
    }

    /**
     * 真正把皮肤应用到世界里的 AI。
     * <p>
     * 26.2 客户端把 {@code PlayerInfo} 缓存在 {@code AbstractClientPlayer} 的私有字段里，
     * 只在为 null 时懒加载一次，也没有 public setter —— 所以改 GameProfile 再重播 PlayerInfo
     * 是换不掉皮肤的。服务端只能把实体移除后用新 profile 重新生成，
     * 位置 / 朝向 / 血量 / 背包装备由 {@link AiPlayerManager#applySkin} 全部保留。
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
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            // 远程服：皮肤在下次点“生成”时随 profile 一起应用
            return;
        }
        String aiName = name;
        server.execute(() -> AiPlayerManager.get().applySkin(server, aiName, value));
    }

    private void closeScreen() {
        // 关界面前先把还没落地的皮肤改动立刻应用，别让玩家还要等那 1 秒防抖
        if (this.skinPendingTicks > 0) {
            this.skinPendingTicks = 0;
            applyPendingSkin();
        }
        this.config.save();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // 「关于」弹窗开着时，ESC 先关弹窗，再按一次才关界面
        if (this.aboutOpen && event.isEscape()) {
            toggleAbout();
            return true;
        }
        return super.keyPressed(event);
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
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        // 压暗游戏画面，视觉焦点落在面板上
        g.fill(0, 0, this.width, this.height, 0x66000000);
        int x2 = this.left + PANEL_W;
        int y2 = this.top + PANEL_H;
        UiTheme.panel(g, this.left, this.top, x2, y2);
        // 顶栏品牌带：标题区独立成一条，和下面的表单拉开层次
        UiTheme.header(g, this.left, this.top, x2, row(Y_TITLE) + 13);

        int cx = this.left + 14;
        // 顶栏：品牌环 + 标题 + 版本徽章 + 配置路径提示
        UiTheme.brand(g, cx, row(Y_TITLE) - 1, 9);
        g.text(this.font, Component.translatable("aiplayer.gui.title"), cx + 14, row(Y_TITLE), UiTheme.TEXT);
        int titleRight = cx + 14 + this.font.width(Component.translatable("aiplayer.gui.title")) + 8;
        Component ver = Component.literal(modVersion());
        int verW = this.font.width(ver) + 8;
        UiTheme.badge(g, titleRight, row(Y_TITLE) - 1, titleRight + verW, row(Y_TITLE) + 10, true);
        g.text(this.font, ver, titleRight + 4, row(Y_TITLE), UiTheme.ACCENT);
        UiTheme.hint(g, this.font, Component.translatable("aiplayer.gui.configPath"), titleRight + verW + 8, row(Y_TITLE) + 1);
        if (this.height < PANEL_H + 12) {
            // 逻辑分辨率太小时面板会超出屏幕，提示玩家调小 GUI 缩放
            UiTheme.hint(g, this.font, Component.translatable("aiplayer.gui.smallWindow"), cx + 150, row(Y_TITLE) + 11);
        }

        // 三个分组卡片（先画，作为控件和标签的底）
        int cardL = this.left + CARD_INSET;
        int cardR = this.left + PANEL_W - CARD_INSET;
        int chipRows = (FEATURES.length + 3) / 4;
        UiTheme.card(g, cardL, row(Y_API) - 4, cardR, row(Y_MODEL) + ROW_H + 4);
        UiTheme.card(g, cardL, row(Y_AI) - 4, cardR, row(Y_KIT) + ROW_H + 4);
        UiTheme.card(g, cardL, row(Y_TOGGLES) - 4, cardR,
                row(Y_CHIPS + (chipRows - 1) * CHIP_ROW) + CHIP_H + 4);

        UiTheme.section(g, this.font, Component.translatable("aiplayer.gui.section.api"), cx, row(Y_API));

        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.label.baseUrl"), cx, row(Y_BASE_URL) + 4);
        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.label.apiKey"), cx, row(Y_API_KEY) + 4);
        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.label.model"), cx, row(Y_MODEL) + 4);
        drawField(g, this.baseUrlBox);
        drawField(g, this.apiKeyBox);
        drawField(g, this.modelBox);

        UiTheme.section(g, this.font, Component.translatable("aiplayer.gui.section.profile"), cx, row(Y_AI));

        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.label.selected"), cx, row(Y_SELECT) + 4);
        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.label.level"), cx, row(Y_LEVEL) + 4);
        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.label.follow"), cx, row(Y_FOLLOW) + 4);
        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.label.team"), cx, row(Y_TEAM) + 4);
        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.label.skin"), cx, row(Y_SKIN) + 4);
        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.label.kit"), cx, row(Y_KIT) + 4);

        UiTheme.section(g, this.font, Component.translatable("aiplayer.gui.section.features"), cx, row(Y_TOGGLES));
        UiTheme.hint(g, this.font, Component.translatable("aiplayer.gui.featuresHint"), cx + 70, row(Y_TOGGLES) + 1);

        drawField(g, this.nameBox);
        drawField(g, this.followBox);
        drawField(g, this.teamBox);
        drawField(g, this.speedBox);
        drawField(g, this.skinBox);
        drawField(g, this.kitBox);

        // 保存反馈
        if (System.currentTimeMillis() - this.savedAt < 2000L) {
            UiTheme.hint(g, this.font, Component.translatable("aiplayer.gui.saved"),
                    this.left + PANEL_W - 60, row(Y_BUTTONS) + 5);
        }

        if (this.aboutOpen) {
            drawAbout(g);
        }
    }

    /** 「关于」面板：简介 + 版本 + 开源地址 + 两个按钮 */
    private void drawAbout(GuiGraphicsExtractor g) {
        int x1 = this.aboutX;
        int y1 = this.aboutY;
        int x2 = x1 + ABOUT_W;
        int y2 = y1 + ABOUT_H;
        // 先把整个界面再压暗一层，让弹窗浮起来
        g.fill(0, 0, this.width, this.height, 0x88000000);
        UiTheme.panel(g, x1, y1, x2, y2);
        UiTheme.header(g, x1, y1, x2, y1 + 16);
        UiTheme.brand(g, x1 + 8, y1 + 4, 9);
        g.text(this.font, Component.translatable("aiplayer.gui.about.title"), x1 + 22, y1 + 5, UiTheme.TEXT);
        Component ver = Component.literal(modVersion());
        int verW = this.font.width(ver) + 8;
        UiTheme.badge(g, x2 - 10 - verW, y1 + 4, x2 - 10, y1 + 15, true);
        g.text(this.font, ver, x2 - 6 - verW, y1 + 5, UiTheme.ACCENT);

        UiTheme.section(g, this.font, Component.translatable("aiplayer.gui.about.descTitle"), x1 + 12, y1 + 24);
        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.about.desc1"), x1 + 14, y1 + 38);
        UiTheme.label(g, this.font, Component.translatable("aiplayer.gui.about.desc2"), x1 + 14, y1 + 50);
        UiTheme.divider(g, x1 + 12, y1 + 64, x2 - 12);
        UiTheme.section(g, this.font, Component.translatable("aiplayer.gui.about.license"), x1 + 12, y1 + 70);
        g.text(this.font, Component.literal("GPL-3.0-only"), x1 + 14, y1 + 84, UiTheme.TEXT_DIM);
        // 地址：用链接色，和普通说明文字区分开
        g.text(this.font, Component.literal(GITHUB_URL), x1 + 108, y1 + 84, UiTheme.LINK);
    }

    private void drawField(GuiGraphicsExtractor g, EditBox box) {
        if (box == null) {
            return;
        }
        UiTheme.field(g, box.getX() - 3, box.getY() - 2, box.getRight() + 3, box.getBottom() + 2, box.isFocused());
    }
}