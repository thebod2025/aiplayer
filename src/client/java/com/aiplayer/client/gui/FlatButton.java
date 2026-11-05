package com.aiplayer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 自绘扁平按钮（不用原版贴图按钮，配色跟主题走）。
 * <p>
 * 视觉：圆角底 + 顶部高光 + hover 时平滑提亮并外发光；{@code accent=true} 时整体用强调色实心。
 * {@code icon(...)} 可以在左边挂一枚原版物品图标（有图标时文字自动右移并重新居中）。
 * <p>
 * hover 提亮与点击闪动都是逐帧平滑的（{@link UiTheme.Anim}），
 * 关掉主题里的「动画」开关后会自动退化成瞬间切换。
 */
public class FlatButton extends AbstractWidget {
    /** 有图标时，文字区域从这里往后算 */
    private static final int TEXT_LEFT_WITH_ICON = 22;

    private final Runnable action;
    private boolean accent;
    private ItemStack icon;
    private final UiTheme.Anim hover = new UiTheme.Anim(0f);
    private final UiTheme.Anim flash = new UiTheme.Anim(0f);

    public FlatButton(int x, int y, int width, int height, Component message, Runnable action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    /** 强调样式（主操作按钮 / 已开启的开关） */
    public FlatButton accent(boolean value) {
        this.accent = value;
        return this;
    }

    /** 在按钮左侧挂一枚原版物品图标 */
    public FlatButton icon(ItemStack stack) {
        this.icon = stack;
        return this;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        this.flash.set(1f);
        if (this.action != null) {
            this.action.run();
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (!this.visible) {
            return;
        }
        boolean hovered = this.active && this.isHoveredOrFocused();
        float hv = this.hover.to(hovered ? 1f : 0f);
        float fv = this.flash.to(0f);

        int bg;
        int fg;
        if (!this.active) {
            bg = UiTheme.BUTTON_DISABLED;
            fg = UiTheme.TEXT_MUTED;
        } else if (this.accent) {
            bg = UiTheme.argb(0xFF, UiTheme.mix(UiTheme.darken(UiTheme.ACCENT, 0.30f), UiTheme.ACCENT, hv));
            fg = 0xFF06201F;
        } else {
            bg = UiTheme.argb(0xFF, UiTheme.mix(UiTheme.BUTTON_BG, UiTheme.BUTTON_BG_HOVER, hv));
            fg = UiTheme.mix(UiTheme.TEXT_DIM, UiTheme.TEXT, hv);
        }
        int x1 = this.getX();
        int y1 = this.getY();
        int x2 = this.getRight();
        int y2 = this.getBottom();
        int r = UiTheme.RADIUS_BUTTON;
        // hover 光晕（画在主体下面，只往外扩）
        if (hovered && this.active) {
            UiTheme.glow(g, x1, y1, x2, y2, r, this.accent ? UiTheme.ACCENT : UiTheme.ACCENT_DIM, 0.55f * hv);
        }
        // 底色改竖直渐变（上亮下暗）做出体积；纯色时自动退化成单色。
        // 液态玻璃可用时改由玻璃通道在模糊分层处绘制，这里不再铺底（描边 / 高光 / 文字仍然照画）。
        int topColor = UiTheme.mix(bg, 0xFFFFFFFF, 0.10f);
        boolean glass = com.aiplayer.client.gui.glass.GlassLayer.add(g, x1, y1, x2, y2,
                r, r, r, r, topColor, bg, this.accent ? 0.7f : 0.35f, this.accent ? 0.7f : 0.45f,
                3.0f, this.accent ? 0.9f : 0.86f);
        if (!glass) {
            UiTheme.fillRoundedGradient(g, x1, y1, x2, y2, r, topColor, bg);
        }
        // 顶部高光 / 底部暗边：跟着圆角拐弯，不再是一段被圆角剪断的直线。
        // 按下去（flash）时高光减弱、底部压深，做出被按进画面的手感。
        float press = Math.min(1f, fv);
        UiTheme.innerHairline(g, x1, y1, x2, y2, r,
                UiTheme.alpha(0xFFFFFFFF, (this.accent ? 0.40f : 0.14f) * (1f - 0.6f * press)), true);
        UiTheme.innerHairline(g, x1, y1, x2, y2, r,
                UiTheme.alpha(0xFF000000, 0.25f + 0.15f * press), false);
        // 玻璃形态下不再描边：边缘的 rim / 菲涅尔光由着色器画，再叠一圈蓝边会显脏
        if (!glass) {
            if (this.accent) {
                UiTheme.outlineRounded(g, x1, y1, x2, y2, r, 1, UiTheme.alpha(0xFFFFFFFF, 0.30f + 0.25f * hv));
            } else if (hovered) {
                UiTheme.outlineRounded(g, x1, y1, x2, y2, r, 1, UiTheme.ACCENT);
            } else if (this.active) {
                UiTheme.outlineRounded(g, x1, y1, x2, y2, r, 1, UiTheme.BUTTON_BORDER);
            }
        }
        // 点击瞬间的一层白色闪动
        if (fv > 0.01f) {
            UiTheme.fillRounded(g, x1, y1, x2, y2, r, UiTheme.alpha(0xFFFFFFFF, fv * 0.22f));
        }

        Font font = Minecraft.getInstance().font;
        int textY = y1 + (this.getHeight() - 8) / 2;
        // 没有世界（标题界面）时不画物品图标：部分物品模型会读 level/玩家，null 时 NPE
        if (this.icon != null && !this.icon.isEmpty() && UiTheme.canDrawItemIcons()) {
            g.item(this.icon, x1 + 4, y1 + (this.getHeight() - 16) / 2);
            UiTheme.centeredText(g, font, this.getMessage(), (x1 + TEXT_LEFT_WITH_ICON + x2) / 2, textY, fg);
        } else {
            UiTheme.centeredText(g, font, this.getMessage(), (x1 + x2) / 2, textY, fg);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
