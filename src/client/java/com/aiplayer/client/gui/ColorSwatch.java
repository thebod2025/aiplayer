package com.aiplayer.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/**
 * 颜色色块：主题配色用的预设色板。
 * <p>
 * 选中时外面套一圈强调色并在中心点一个小暗点，hover 时用自身颜色向外发光；
 * 点击回调切换配色。hover / 选中 / 点击闪动都是逐帧平滑的。
 */
public class ColorSwatch extends AbstractWidget {
    private final int rgb;
    private final BooleanSupplier selected;
    private final Runnable action;
    private final UiTheme.Anim hover = new UiTheme.Anim(0f);
    private final UiTheme.Anim flash = new UiTheme.Anim(0f);

    public ColorSwatch(int x, int y, int size, int rgb, BooleanSupplier selected, Runnable action) {
        super(x, y, size, size, Component.literal(String.format("#%06X", rgb & 0xFFFFFF)));
        this.rgb = rgb & 0xFFFFFF;
        this.selected = selected;
        this.action = action;
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
        boolean sel = this.selected != null && this.selected.getAsBoolean();
        float hv = this.hover.to(hovered ? 1f : 0f);
        float fv = this.flash.to(0f);

        int x1 = this.getX();
        int y1 = this.getY();
        int x2 = this.getRight();
        int y2 = this.getBottom();
        // 小色块只用 4px 圆角，缩放多少都不会出现阶梯毛边（GPU SDF 抗锯齿）
        int r = Math.min(4, (y2 - y1) / 2);
        if (hv > 0.02f || sel) {
            UiTheme.glow(g, x1, y1, x2, y2, r, sel ? UiTheme.ACCENT : UiTheme.argb(0xFF, this.rgb),
                    0.65f * Math.max(hv, sel ? 1f : 0f));
        }
        // 外框：选中用强调色，其余用卡片描边
        UiTheme.fillRounded(g, x1 - 1, y1 - 1, x2 + 1, y2 + 1, r + 1,
                sel ? UiTheme.ACCENT : UiTheme.mix(UiTheme.CARD_BORDER, UiTheme.ACCENT, 0.5f * hv));
        UiTheme.fillRounded(g, x1, y1, x2, y2, r, UiTheme.argb(0xFF, this.rgb));
        // 顶部高光 + 底部暗边：跟着圆角拐弯
        UiTheme.innerHairline(g, x1, y1, x2, y2, r, 0x38FFFFFF, true);
        UiTheme.innerHairline(g, x1, y1, x2, y2, r, 0x40000000, false);
        if (sel) {
            // 内圈 1px 强调色环，中心一枚 3px 圆点表示"当前选中"
            UiTheme.outlineRounded(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, Math.max(1, r - 1), 1,
                    UiTheme.alpha(0xFFFFFFFF, 0.85f));
            int cx = (x1 + x2) / 2;
            int cy = (y1 + y2) / 2;
            UiTheme.pill(g, cx - 2, cy - 2, cx + 2, cy + 2, 0xCC101418);
        }
        if (fv > 0.01f) {
            UiTheme.fillRounded(g, x1, y1, x2, y2, r, UiTheme.alpha(0xFFFFFFFF, fv * 0.35f));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
