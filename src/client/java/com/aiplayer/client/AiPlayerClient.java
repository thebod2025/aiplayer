package com.aiplayer.client;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.client.gui.AiConfigScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * 客户端入口：注册“打开配置界面”的快捷键并处理按键。
 * <p>
 * 配置界面本身用自绘控件（{@link AiConfigScreen}），不依赖任何第三方 UI 库；
 * 服务端不需要装这个模组，只有想用 GUI 的一侧才需要。
 */
public class AiPlayerClient implements ClientModInitializer {
    /** 26.2 的按键分类改成了 {@code KeyMapping.Category}，自带翻译键 {@code key.categories.aiplayer.main} */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aiplayer", "main"));

    private static KeyMapping openConfigKey;

    @Override
    public void onInitializeClient() {
        openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.aiplayer.config",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_K,
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.consumeClick()) {
                // 26.2：当前界面在 gui.screen()，切换界面用 setScreenAndShow()
                client.setScreenAndShow(new AiConfigScreen(client.gui.screen()));
            }
        });

        AiPlayerMod.LOGGER.info("[aiplayer] 客户端入口已加载：默认按 K 打开配置界面");
    }
}