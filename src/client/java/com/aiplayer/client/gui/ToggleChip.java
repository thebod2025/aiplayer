package com.aiplayer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * 功能开关芯片：左边一枚原版物品图标当功能图标，中间名字，右边一个状态点。
 * <p>
 * 开启 = 青绿描边 + 青绿点，关闭 = 灰色，比原生复选框紧凑，22 个开关能在一屏放下。
 * 图标用原版物品贴图（{@code g.item(...)}），不需要额外的贴图资源。
 */
public class ToggleChip extends AbstractWidget {
    /** 图标区占的宽度（16 图标 + 左右留白），文字从这里之后开始画 */
    private static final int ICON_W = 20;

    private final String key;
    private final ItemStack icon;
    private final Consumer<Boolean> onChange;
    private boolean value;

    public ToggleChip(int x, int y, int width, int height, String key, ItemStack icon, Component label, boolean value, Consumer<Boolean> onChange) {
        super(x, y, width, height, label);
        this.key = key;
        this.icon = icon;
        this.value = value;
        this.onChange = onChange;
    }

    /** 对应的功能名（与 {@code AiProfile.features()} 的键一致） */
    public String key() {
        return this.key;
    }

    public boolean value() {
        return this.value;
    }

    public void setValue(boolean newValue) {
        this.value = newValue;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        this.value = !this.value;
        if (this.onChange != null) {
            this.onChange.accept(this.value);
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (!this.visible) {
            return;
        }
        boolean hover = this.active && this.isHoveredOrFocused();
        int x1 = this.getX();
        int y1 = this.getY();
        int x2 = this.getRight();
        int y2 = this.getBottom();
        int bg = this.value
                ? (hover ? 0xFF16302F : 0xFF112423)
                : (hover ? 0xFF161F24 : 0xFF0F1519);
        g.fill(x1, y1, x2, y2, bg);
        int border = this.value ? UiTheme.ACCENT_DIM : 0xFF1E2A2F;
        if (hover) {
            border = UiTheme.ACCENT;
        }
        g.fill(x1, y1, x2, y1 + 1, border);
        g.fill(x1, y2 - 1, x2, y2, border);
        g.fill(x1, y1, x1 + 1, y2, border);
        g.fill(x2 - 1, y1, x2, y2, border);
        Font font = Minecraft.getInstance().font;
        // 功能图标：原版物品贴图，画在芯片左侧。
        // 没有世界（标题界面）时不画：部分物品模型会读 level/玩家，null 时 NPE
        int textX = x1 + ICON_W;
        if (this.icon != null && !this.icon.isEmpty() && UiTheme.canDrawItemIcons()) {
            g.item(this.icon, x1 + 2, y1 + (this.getHeight() - 16) / 2);
        } else {
            textX = x1 + 4;
        }
        int textColor = this.value ? UiTheme.TEXT : UiTheme.TEXT_MUTED;
        g.text(font, this.getMessage(), textX, y1 + (this.getHeight() - 8) / 2, textColor);
        // 右上角状态点
        int dotColor = this.value ? UiTheme.ACCENT : 0xFF39484E;
        g.fill(x2 - 6, y1 + 4, x2 - 3, y1 + 7, dotColor);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}