package com.aiplayer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 配置界面的统一视觉规范：深色玻璃底 + 层次卡片 + 青绿品牌色。
 * <p>
 * 26.2 的绘制走 {@link GuiGraphicsExtractor}（只有 {@code fill / fillGradient / text / centeredText}），
 * 所以圆角、阴影、高光这些都用"叠加填充"手工模拟，不依赖任何贴图资源。
 * <p>
 * 视觉层次从上到下依次是：面板外阴影 → 面板底 → 顶栏（品牌带）→ 分区卡片 → 控件。
 * 所有颜色集中在这里定义，别的类不要再写死颜色值。
 */
public final class UiTheme {
    /** 品牌青绿（取自 Logo） */
    public static final int ACCENT = 0xFF3BE8D8;
    public static final int ACCENT_DIM = 0xFF1B8F88;
    public static final int ACCENT_DEEP = 0xFF0E5C58;
    public static final int ACCENT_SOFT = 0x332AD4C8;
    /** 顶栏：比面板更亮一档，让标题"立"起来 */
    public static final int HEADER_TOP = 0xF0142A30;
    public static final int HEADER_BOTTOM = 0xF00D1A1F;
    /** 「关于」面板用的强调色（青绿之外再给一个中性高亮） */
    public static final int LINK = 0xFF7FD8FF;

    /** 面板：微弱渐变 + 细边框 */
    public static final int PANEL_TOP = 0xF00E1A1F;
    public static final int PANEL_BOTTOM = 0xF0091114;
    public static final int PANEL_BORDER = 0xFF24454C;
    /** 面板外的柔和阴影（一层一层往外淡） */
    public static final int SHADOW_1 = 0x50000000;
    public static final int SHADOW_2 = 0x30000000;
    public static final int SHADOW_3 = 0x18000000;
    /** 输入框 */
    public static final int FIELD_BG = 0xFF070C0F;
    public static final int FIELD_BORDER = 0xFF23434A;

    public static final int TEXT = 0xFFEAF6F8;
    public static final int TEXT_DIM = 0xFFA9BEC6;
    public static final int TEXT_MUTED = 0xFF6F858D;
    /** 关闭 / 删除之类的警示色 */
    public static final int DANGER = 0xFFE8635F;
    /** 分区卡片底：在面板上叠一层极淡的白，形成"比面板略亮"的卡片层次 */
    public static final int CARD_BG = 0x12FFFFFF;
    public static final int CARD_TOP = 0x1CFFFFFF;
    public static final int CARD_BOTTOM = 0x36000000;
    public static final int CARD_BORDER = 0xFF1E353C;

    private UiTheme() {
    }

    // ------------------------------------------------------------------
    // 面板 / 卡片
    // ------------------------------------------------------------------

    /** 主面板：外阴影 + 深色渐变底 + 细边框 + 圆角 + 顶部青绿高光 */
    public static void panel(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        // 外阴影：三层由内向外逐渐变淡，面板就像浮在游戏画面上
        g.fill(x1 - 3, y1 - 3, x2 + 3, y2 + 3, SHADOW_3);
        g.fill(x1 - 2, y1 - 2, x2 + 2, y2 + 2, SHADOW_2);
        g.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, SHADOW_1);
        // 边框底（圆角处露出来的就是它）
        g.fill(x1, y1, x2, y2, PANEL_BORDER);
        g.fillGradient(x1 + 1, y1 + 1, x2 - 1, y2 - 1, PANEL_TOP, PANEL_BOTTOM);
        // 四周 1px 边框
        g.fill(x1, y1, x2, y1 + 1, PANEL_BORDER);
        g.fill(x1, y2 - 1, x2, y2, PANEL_BORDER);
        g.fill(x1, y1, x1 + 1, y2, PANEL_BORDER);
        g.fill(x2 - 1, y1, x2, y2, PANEL_BORDER);
        // 四角 2×2 切角：视觉上是圆角
        roundCorners(g, x1, y1, x2, y2, PANEL_BORDER, PANEL_TOP);
        // 顶部青绿细线（品牌色）+ 底部渐隐
        g.fillGradient(x1 + 1, y1 + 1, x2 - 1, y1 + 2, ACCENT, ACCENT_SOFT);
        g.fillGradient(x1 + 1, y2 - 2, x2 - 1, y2 - 1, ACCENT_SOFT, ACCENT_DIM);
    }

    /**
     * 顶栏：面板最上面那条品牌带（品牌环 + 标题 + 右侧版本号），底边压一条青绿分隔线。
     * 标题和版本号的文字由调用方画（要用到 Font）。
     */
    public static void header(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fillGradient(x1 + 1, y1 + 1, x2 - 1, y2 - 1, HEADER_TOP, HEADER_BOTTOM);
        // 顶栏内的两个圆角
        g.fill(x1 + 1, y1 + 1, x1 + 2, y1 + 2, PANEL_TOP);
        g.fill(x2 - 2, y1 + 1, x2 - 1, y1 + 2, PANEL_TOP);
        // 底边：青绿渐变细线（左侧亮、右侧淡出），做出品牌感
        g.fillGradient(x1 + 1, y2 - 1, x2 - 1, y2, ACCENT_DIM, ACCENT_SOFT);
    }

    /** 分区卡片底：比面板略亮的一层 + 顶部高光 + 底部暗边 + 左侧品牌竖条 */
    public static void card(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, CARD_BG);
        g.fill(x1, y1, x2, y1 + 1, CARD_TOP);
        g.fill(x1, y2 - 1, x2, y2, CARD_BOTTOM);
        // 左右细边框，卡片边界更清楚
        g.fill(x1, y1, x1 + 1, y2, CARD_BORDER);
        g.fill(x2 - 1, y1, x2, y2, CARD_BORDER);
        // 左侧品牌竖条（渐变），给每个分区一个视觉锚点
        g.fillGradient(x1 + 1, y1 + 2, x1 + 3, y2 - 2, ACCENT, ACCENT_DEEP);
        // 上下各切掉一像素角，视觉上圆一点
        g.fill(x1, y1, x1 + 1, y1 + 1, PANEL_TOP);
        g.fill(x2 - 1, y1, x2, y1 + 1, PANEL_TOP);
        g.fill(x1, y2 - 1, x1 + 1, y2, PANEL_BOTTOM);
        g.fill(x2 - 1, y2 - 1, x2, y2, PANEL_BOTTOM);
    }

    /** 一条横向分隔线（左端青绿，向右淡出） */
    public static void divider(GuiGraphicsExtractor g, int x1, int y, int x2) {
        g.fillGradient(x1, y, x2, y + 1, ACCENT_DIM, CARD_BORDER);
    }

    /** 圆角小徽章（版本号之类）：底 + 描边 */
    public static void badge(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, boolean accent) {
        int border = accent ? ACCENT_DIM : CARD_BORDER;
        g.fill(x1, y1, x2, y2, accent ? 0x331BE8D8 : 0x22FFFFFF);
        g.fill(x1, y1, x2, y1 + 1, border);
        g.fill(x1, y2 - 1, x2, y2, border);
        g.fill(x1, y1, x1 + 1, y2, border);
        g.fill(x2 - 1, y1, x2, y2, border);
        g.fill(x1, y1, x1 + 1, y1 + 1, PANEL_TOP);
        g.fill(x2 - 1, y2 - 1, x2, y2, PANEL_TOP);
    }

    /** 品牌标识：青绿描边圆环 + 中心亮点（AI 的"瞳孔"） */
    public static void brand(GuiGraphicsExtractor g, int x, int y, int size) {
        g.fill(x, y, x + size, y + size, ACCENT);
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, ACCENT_DIM);
        g.fill(x + 2, y + 2, x + size - 2, y + size - 2, PANEL_TOP);
        int c = size / 2;
        g.fill(x + c - 1, y + c - 1, x + c + 1, y + c + 1, ACCENT);
    }

    // ------------------------------------------------------------------
    // 输入框 / 文字
    // ------------------------------------------------------------------

    /** 输入框外框，聚焦时描边换成品牌青绿并带一圈光晕 */
    public static void field(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, boolean focused) {
        g.fill(x1, y1, x2, y2, FIELD_BG);
        int border = focused ? ACCENT : FIELD_BORDER;
        g.fill(x1, y1, x2, y1 + 1, border);
        g.fill(x1, y2 - 1, x2, y2, border);
        g.fill(x1, y1, x1 + 1, y2, border);
        g.fill(x2 - 1, y1, x2, y2, border);
        if (focused) {
            // 聚焦时外围一圈更淡的光晕
            g.fill(x1 - 1, y1 - 1, x2 + 1, y1, ACCENT_SOFT);
            g.fill(x1 - 1, y2, x2 + 1, y2 + 1, ACCENT_SOFT);
            g.fill(x1 - 1, y1, x1, y2, ACCENT_SOFT);
            g.fill(x2, y1, x2 + 1, y2, ACCENT_SOFT);
        }
    }

    /**
     * 菜单 / 标题界面里 {@code Minecraft.level} 和 {@code player} 都是 null，而部分物品模型的
     * 属性要从世界或玩家身上取值（例如指南针 {@code CompassAngleState} 第一行就是
     * {@code level.getGameTime()}），这时 {@code g.item(...)} 会直接 NPE 把游戏打崩。
     * 所以**没有世界时一律不画物品图标**，只留文字。
     */
    public static boolean canDrawItemIcons() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null;
    }

    /** 分组标题：青绿竖条 + 原版物品图标 + 青绿标签 */
    public static void section(GuiGraphicsExtractor g, Font font, ItemStack icon, Component text, int x, int y) {
        g.fill(x, y, x + 2, y + 9, ACCENT);
        g.fill(x + 2, y + 2, x + 3, y + 7, ACCENT_DIM);
        int tx = x + 6;
        if (icon != null && !icon.isEmpty() && canDrawItemIcons()) {
            // 图标比标题带高，往上挪 3px 让它在视觉上和文字居中对齐
            g.item(icon, x + 6, y - 3);
            tx = x + 26;
        }
        g.text(font, text, tx, y + 1, ACCENT);
    }

    /** 没有图标的分组标题 */
    public static void section(GuiGraphicsExtractor g, Font font, Component text, int x, int y) {
        section(g, font, ItemStack.EMPTY, text, x, y);
    }

    /** 普通标签 */
    public static void label(GuiGraphicsExtractor g, Font font, Component text, int x, int y) {
        g.text(font, text, x, y, TEXT_DIM);
    }

    /** 弱化的说明文字 */
    public static void hint(GuiGraphicsExtractor g, Font font, Component text, int x, int y) {
        g.text(font, text, x, y, TEXT_MUTED);
    }

    /** 居中文字（画在指定矩形里） */
    public static void centered(GuiGraphicsExtractor g, Font font, Component text, int x1, int x2, int y, int color) {
        int w = font.width(text);
        g.text(font, text, (x1 + x2 - w) / 2, y, color);
    }

    // ------------------------------------------------------------------

    /** 四角各切一个 2×2 的方块，配合边框形成圆角 */
    private static void roundCorners(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int border, int inner) {
        g.fill(x1, y1, x1 + 1, y1 + 1, inner);
        g.fill(x2 - 1, y1, x2, y1 + 1, inner);
        g.fill(x1, y2 - 1, x1 + 1, y2, inner);
        g.fill(x2 - 1, y2 - 1, x2, y2, inner);
    }
}
