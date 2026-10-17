package com.aiplayer.client;

import com.aiplayer.client.gui.AiConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 集成：装了 Mod Menu 时，模组列表里的 AI Player 会多出一个设置按钮，
 * 点开就是本模组自己的配置界面（和按 K 打开的是同一个界面）。
 * <p>
 * 没装 Mod Menu 也完全不影响：这个类只会在 Mod Menu 存在时被它通过
 * {@code fabric.mod.json} 的 {@code modmenu} entrypoint 加载。
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // 把 Mod Menu 传进来的父界面接着用，按 ESC 能退回模组列表
        return parent -> new AiConfigScreen(parent);
    }
}
