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
 * 开启 = 强调色描边 + 强调色状态点，关闭 = 灰色，比原生复选框紧凑，23 个开关能在一屏放下。
 * 图标用原版物品贴图（{@code g.item(...)}），不需要额外的贴图资源。
 * <p>
 * 开关切换、hover 提亮、点击闪动都是逐帧平滑的，文字统一走 {@link UiTheme#ui} 套上界面字体。
 */
public class ToggleChip extends AbstractWidget {
    /** 图标区占的宽度（16 图标 + 左右留白），文字从这里之后开始画 */
    private static final int ICON_W = 20;

    private final String key;
    private final ItemStack icon;
    private final Consumer<Boolean> onChange;
    private boolean value;
    private final UiTheme.Anim hover = new UiTheme.Anim(0f);
    private final UiTheme.Anim on = new UiTheme.Anim(0f);
    private final UiTheme.Anim flash = new UiTheme.Anim(0f);

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
        this.flash.set(1f);
        if (this.onChange != null) {
            this.onChange.accept(this.value);
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (!this.visible) {
            return;
        }
        boolean hovered = this.active && this.isHoveredOrFocused();
        float hv = this.hover.to(hovered ? 1f : 0f);
        float ov = this.on.to(this.value ? 1f : 0f);
        float fv = this.flash.to(0f);

        int x1 = this.getX();
        int y1 = this.getY();
        int x2 = this.getRight();
        int y2 = this.getBottom();
        int r = UiTheme.RADIUS_FIELD;
        // 底色：先按 hover 在"关/开"两组底色里各取一档，再按开关进度混合
        int off = UiTheme.mix(UiTheme.CHIP_BG, UiTheme.CHIP_BG_HOVER, hv);
        int onBg = UiTheme.mix(UiTheme.CHIP_ON_BG, UiTheme.CHIP_ON_BG_HOVER, hv);
        int bg = UiTheme.argb(0xFF, UiTheme.mix(off, onBg, ov));
        if (hovered) {
            UiTheme.glow(g, x1, y1, x2, y2, r, UiTheme.ACCENT, 0.42f * hv * (0.35f + 0.65f * ov));
        }
        int chipTop = UiTheme.mix(bg, 0xFFFFFFFF, 0.06f);
        // 芯片也走液态玻璃；登记失败（形状满了 / 管线不可用）才退回普通渐变底
        boolean glass = com.aiplayer.client.gui.glass.GlassLayer.add(g, x1, y1, x2, y2,
                r, r, r, r, chipTop, bg, 0.45f, 0.5f, 2.5f, 0.74f);
        if (!glass) {
            UiTheme.fillRoundedGradient(g, x1, y1, x2, y2, r, chipTop, bg);
            UiTheme.outlineRounded(g, x1, y1, x2, y2, r, 1,
                    hovered ? UiTheme.ACCENT : UiTheme.mix(UiTheme.CHIP_BORDER, UiTheme.ACCENT_DIM, ov));
        }
        // 顶部细高光：跟着圆角走（以前只画中间直边段，两端被圆角剪断）
        UiTheme.innerHairline(g, x1, y1, x2, y2, r,
                UiTheme.alpha(0xFFFFFFFF, 0.05f + 0.13f * ov), true);

        Font font = Minecraft.getInstance().font;
        // 功能图标：原版物品贴图，画在芯片左侧。
        // 没有世界（标题界面）时不画：部分物品模型会读 level/玩家，null 时 NPE
        int textX = x1 + ICON_W;
        if (this.icon != null && !this.icon.isEmpty() && UiTheme.canDrawItemIcons()) {
            g.item(this.icon, x1 + 3, y1 + (this.getHeight() - 16) / 2);
        } else {
            textX = x1 + 5;
        }
        int textColor = UiTheme.mix(UiTheme.TEXT_MUTED, UiTheme.TEXT, ov);
        UiTheme.text(g, font, this.getMessage(), textX, y1 + (this.getHeight() - 8) / 2, textColor);
        // 右上角状态点：用胶囊画成真圆（以前 radius=2 的圆角方块在缩放后会看成方点）
        int dotY = y1 + (this.getHeight() - 4) / 2;
        if (ov > 0.05f) {
            UiTheme.glow(g, x2 - 8, dotY, x2 - 4, dotY + 4, 2, UiTheme.ACCENT, 0.65f * ov);
        }
        UiTheme.pill(g, x2 - 8, dotY, x2 - 4, dotY + 4, UiTheme.mix(0xFF39484E, UiTheme.ACCENT, ov));
        if (ov < 0.95f) {
            // 关闭态：中心挖空成环，与"点亮"形成明确对比
            UiTheme.pill(g, x2 - 6, dotY + 1, x2 - 6 + 2, dotY + 3, bg);
        }
        // 点击瞬间的整块闪动
        if (fv > 0.01f) {
            UiTheme.fillRounded(g, x1, y1, x2, y2, r, UiTheme.alpha(0xFFFFFFFF, fv * 0.25f));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
