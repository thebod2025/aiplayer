package com.aiplayer.client.gui;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.client.config.ClientThemeConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 新手教学：第一次运行模组时自动弹出，把玩家从"不知道这模组干嘛"带到能自己指挥 AI。
 * <p>
 * 七页：概览 → 填 API → 验证连通 → 生成第一个 AI → 怎么和它说话 → 装备战斗 → 排错。
 * 每页六行正文，第 2/3 页额外给一个「去配置 API」按钮，点了直接跳到配置界面的「全局」页。
 * <p>
 * 只在客户端存一个"看过了"的标记（{@code config/aiplayer-onboarding.json}），
 * 所以删掉这个文件就能再看一次；「关于」页也有按钮可以随时重看。
 */
public class OnboardingScreen extends Screen {
    /** 教程页数 */
    private static final int PAGES = 7;
    /** 每页正文行数 */
    private static final int LINES = 6;

    private static final int PANEL_W = 470;
    private static final int PANEL_H = 232;
    private static final int PAD = 18;
    /** 标题行 / 正文首行 / 行高（相对面板顶边） */
    private static final int HEAD_Y = 36;
    private static final int BODY_Y = 54;
    private static final int LINE_H = 15;

    /** 打开时刻 / 翻页时刻：驱动入场与翻页动画（毫秒） */
    private static final long OPEN_MS = 180L;
    private static final long PAGE_MS = 220L;

    private final Screen parent;
    private int page;
    /** 当前帧的鼠标位置（自绘控件要用） */
    private int mx;
    private int my;
    private int left;
    private int top;
    /** 实际面板尺寸（小窗口下会缩小，避免超出屏幕） */
    private int pw = PANEL_W;
    private int ph = PANEL_H;

    private long openedAt = System.currentTimeMillis();
    private long pageChangedAt = this.openedAt;

    private FlatButton prevButton;
    private FlatButton nextButton;
    private FlatButton skipButton;
    /** 「全局」页的页签下标（教学里"去配置"直接跳这一页填 API） */
    private static final int API_TAB = 3;

    public OnboardingScreen(Screen parent) {
        super(Component.translatable("aiplayer.onboarding.title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------
    // 首启标记
    // ------------------------------------------------------------------

    private static Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("aiplayer-onboarding.json");
    }

    /** 还没看过教程？读不到文件 / 解析失败都当作"看过"，免得因为权限问题反复弹窗 */
    public static boolean shouldShow() {
        try {
            Path p = file();
            if (!Files.exists(p)) {
                return true;
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            return !text.contains("\"done\":true") && !text.contains("\"done\": true");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记下"看过了"。写失败只记日志，不打扰玩家 */
    public static void markDone() {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            Files.writeString(p, "{\"done\":true}", StandardCharsets.UTF_8);
        } catch (Throwable t) {
            AiPlayerMod.LOGGER.warn("[aiplayer] 教程标记写入失败: {}", t.toString());
        }
    }

    /** 清掉标记（「关于」页的"重看教程"用） */
    public static void reset() {
        try {
            Files.deleteIfExists(file());
        } catch (Throwable ignored) {
            // 删不掉也无所谓：下一次 markDone() 会覆盖内容
        }
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        ClientThemeConfig.get();
        UiTheme.refresh();
        this.pw = Math.min(PANEL_W, this.width - 16);
        this.ph = Math.min(PANEL_H, this.height - 16);
        this.left = (this.width - this.pw) / 2;
        this.top = (this.height - this.ph) / 2;

        int by = this.top + this.ph - PAD - 20;
        this.skipButton = this.addRenderableWidget(new FlatButton(
                this.left + PAD, by, 72, 20,
                Component.translatable("aiplayer.onboarding.skip"), this::finish));
        this.prevButton = this.addRenderableWidget(new FlatButton(
                this.left + this.pw - PAD - 152, by, 68, 20,
                Component.translatable("aiplayer.onboarding.prev"), this::prev));
        this.nextButton = this.addRenderableWidget(new FlatButton(
                this.left + this.pw - PAD - 76, by, 76, 20,
                Component.translatable("aiplayer.onboarding.next"), this::next).accent(true));
        syncButtons();
    }

    private void prev() {
        if (this.page > 0) {
            this.page--;
            this.pageChangedAt = System.currentTimeMillis();
            syncButtons();
        }
    }

    private void next() {
        if (this.page < PAGES - 1) {
            this.page++;
            this.pageChangedAt = System.currentTimeMillis();
            syncButtons();
        } else {
            finish();
        }
    }

    private void syncButtons() {
        if (this.prevButton != null) {
            this.prevButton.active = this.page > 0;
        }
        if (this.nextButton != null) {
            this.nextButton.setMessage(Component.translatable(this.page == PAGES - 1
                    ? "aiplayer.onboarding.done"
                    : "aiplayer.onboarding.next"));
        }
    }

    /** 关闭教程：记标记 + 回到来源界面 */
    private void finish() {
        markDone();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    /** 跳到配置界面的「全局」页，让玩家直接填 API */
    private void openApiConfig() {
        markDone();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(new AiConfigScreen(this.parent, API_TAB));
        }
    }

    @Override
    public void onClose() {
        finish();
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
        UiTheme.refresh();
        com.aiplayer.client.gui.glass.GlassLayer.beginFrame();
        boolean glass = com.aiplayer.client.gui.glass.GlassLayer.usable();
        // 游戏里不跑全屏模糊（和配置界面同一套取舍）；玻璃通道则把遮罩提前画好再分层
        boolean inGame = this.minecraft != null && this.minecraft.level != null;
        if (glass) {
            if (!inGame) {
                extractPanorama(g, partialTick);
            }
            g.fill(0, 0, this.width, this.height, UiTheme.alpha(UiTheme.BACKDROP, 1f));
            g.blurBeforeThisStratum();
        } else if (!inGame || ClientThemeConfig.get().blurBackground) {
            super.extractBackground(g, mouseX, mouseY, partialTick);
        }
        this.mx = mouseX;
        this.my = mouseY;

        // ---- 动画：入场（遮罩 / 标题淡入）+ 翻页（正文从下方滑入并淡入）----
        long now = System.currentTimeMillis();
        float open = easeOutCubic((now - this.openedAt) / (float) OPEN_MS);
        float pg = easeOutCubic((now - this.pageChangedAt) / (float) PAGE_MS);

        if (!glass) {
            g.fill(0, 0, this.width, this.height, UiTheme.alpha(UiTheme.BACKDROP, open));
        }
        int x2 = this.left + this.pw;
        int y2 = this.top + this.ph;
        UiTheme.panel(g, this.left, this.top, x2, y2);
        UiTheme.header(g, this.left, this.top, x2, this.top + 26);
        UiTheme.text(g, this.font, Component.translatable("aiplayer.onboarding.title"),
                this.left + PAD, this.top + 9, UiTheme.alpha(UiTheme.ACCENT, open));
        // 右上角页码：7 页里看到第几页，不用数圆点
        Component step = Component.translatable("aiplayer.onboarding.step",
                Component.literal(String.valueOf(this.page + 1)), Component.literal(String.valueOf(PAGES)));
        UiTheme.text(g, this.font, step, x2 - PAD - this.font.width(step), this.top + 9,
                UiTheme.alpha(UiTheme.TEXT_MUTED, open));

        int slide = (int) Math.round((1.0f - pg) * 8.0f);
        int y = this.top + HEAD_Y + slide;
        UiTheme.text(g, this.font, Component.translatable("aiplayer.onboarding.page." + this.page + ".head"),
                this.left + PAD, y, UiTheme.alpha(UiTheme.TEXT, pg));
        y += BODY_Y - HEAD_Y;
        for (int i = 1; i <= LINES; i++) {
            UiTheme.text(g, this.font, Component.translatable("aiplayer.onboarding.page." + this.page + "." + i),
                    this.left + PAD, y, UiTheme.alpha(UiTheme.TEXT_DIM, pg));
            y += LINE_H;
        }
        // 讲 API 的两页各给一个直达配置的按钮，不用玩家自己去翻页签
        if (hasApiShortcut()) {
            drawApiShortcut(g, apiShortcutY() + slide, pg);
        }
        drawDots(g, y2 - 46, pg);
    }

    private boolean hasApiShortcut() {
        return this.page == 1 || this.page == 2;
    }

    /** 缓出：开头快、结尾慢，比线性更像"滑入" */
    private static float easeOutCubic(float t) {
        float x = Math.max(0.0f, Math.min(1.0f, t));
        float inv = 1.0f - x;
        return 1.0f - inv * inv * inv;
    }

    /** 「去配置 API」：手绘一个强调色小按钮，点击直接跳配置页的「全局」页签 */
    private void drawApiShortcut(GuiGraphicsExtractor g, int y, float alpha) {
        int bw = 132;
        int bh = 18;
        int bx = this.left + PAD;
        boolean hovered = this.mx >= bx && this.mx < bx + bw && this.my >= y && this.my < y + bh;
        UiTheme.fillRounded(g, bx, y, bx + bw, y + bh, UiTheme.RADIUS_BUTTON,
                UiTheme.alpha(hovered ? UiTheme.ACCENT_DIM : UiTheme.ACCENT_DEEP, alpha));
        UiTheme.outlineRounded(g, bx, y, bx + bw, y + bh, UiTheme.RADIUS_BUTTON, 1,
                UiTheme.alpha(UiTheme.ACCENT, alpha));
        Component label = Component.translatable("aiplayer.onboarding.openApi");
        UiTheme.text(g, this.font, label,
                bx + (bw - this.font.width(label)) / 2, y + 5, UiTheme.alpha(UiTheme.TEXT, alpha));
    }

    /** 底部页码：当前页的圆点会随翻页动画由小长到满（半径 2 → 3.5） */
    private void drawDots(GuiGraphicsExtractor g, int y, float progress) {
        for (int i = 0; i < PAGES; i++) {
            boolean current = i == this.page;
            float r = current ? 2.0f + progress * 1.5f : 2.0f;
            int cx = this.left + this.pw / 2 - (PAGES * 9) / 2 + i * 9 + 4;
            int color = current ? UiTheme.alpha(UiTheme.ACCENT, progress) : UiTheme.TEXT_MUTED;
            int ri = Math.round(r);
            UiTheme.fillRounded(g, cx - ri, y - ri, cx + ri, y + ri, ri, color);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // 「去配置 API」没有对应的 widget，命中判定要自己写
        if (hasApiShortcut()) {
            int bw = 132;
            int bh = 18;
            int bx = this.left + PAD;
            int by = apiShortcutY();
            if (event.x() >= bx && event.x() < bx + bw && event.y() >= by && event.y() < by + bh) {
                openApiConfig();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // ←/→（263/262）翻页：看完一页不必去点按钮
        if (event.key() == 263) {
            prev();
            return true;
        }
        if (event.key() == 262) {
            next();
            return true;
        }
        return super.keyPressed(event);
    }

    /** 「去配置」按钮的 y：六行正文之后 */
    private int apiShortcutY() {
        return this.top + BODY_Y + LINES * LINE_H + 8;
    }
}
