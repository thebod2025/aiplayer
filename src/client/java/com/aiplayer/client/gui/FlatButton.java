package com.aiplayer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 自绘扁平按钮（不用原版贴图按钮，配色跟主题统一）。
 * <p>
 * 视觉：深色底 + 1px 顶部高光 + hover 时左右青绿描边；{@code accent=true} 时整体青绿实心。
 */
public class FlatButton extends AbstractWidget {
    private final Runnable action;
    private boolean accent;

    public FlatButton(int x, int y, int width, int height, Component message, Runnable action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    /** 强调样式（主操作按钮 / 已开启的开关） */
    public FlatButton accent(boolean value) {
        this.accent = value;
        return this;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (this.action != null) {
            this.action.run();
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (!this.visible) {
            return;
        }
        boolean hover = this.active && this.isHoveredOrFocused();
        int bg;
        int fg;
        if (!this.active) {
            bg = 0xFF101619;
            fg = UiTheme.TEXT_MUTED;
        } else if (this.accent) {
            bg = hover ? 0xFF35E3D6 : 0xFF1FA79C;
            fg = 0xFF06201F;
        } else {
            bg = hover ? 0xFF1B2A30 : 0xFF121C21;
            fg = hover ? 0xFFEAF6F8 : 0xFFC6D5DB;
        }
        int x1 = this.getX();
        int y1 = this.getY();
        int x2 = this.getRight();
        int y2 = this.getBottom();
        // 切角：先铺 1px 外扩底色再画主体，四角缺一像素形成圆角感
        g.fill(x1 - 1, y1, x2 + 1, y2 + 1, bg);
        g.fill(x1, y1, x2, y2, bg);
        // 顶部高光 + 底部暗边：制造一点层次
        g.fill(x1, y1, x2, y1 + 1, this.accent ? 0x66FFFFFF : 0x22FFFFFF);
        g.fill(x1, y2 - 1, x2, y2, 0x40000000);
        if (hover) {
            g.fill(x1, y1, x1 + 1, y2, UiTheme.ACCENT);
            g.fill(x2 - 1, y1, x2, y2, UiTheme.ACCENT);
        }
        Font font = Minecraft.getInstance().font;
        g.centeredText(font, this.getMessage(), (x1 + x2) / 2, y1 + (this.getHeight() - 8) / 2, fg);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}