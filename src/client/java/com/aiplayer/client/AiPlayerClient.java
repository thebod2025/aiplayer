package com.aiplayer.client;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.client.gui.AiConfigScreen;
import com.aiplayer.client.gui.gpu.RoundedPipelines;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
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
    /** 首启教学是否已经尝试弹出过（一个进程只判断一次，避免进世界瞬间反复触发） */
    private static boolean onboardingChecked;
    /** 正在等微软登录验证码的 bot 名字（弹窗关闭前一直记着） */
    private static String msaBot;

    /** 微软登录弹窗关闭：清掉待弹窗的 bot */
    public static void msaDialogClosed(String bot) {
        if (bot != null && bot.equalsIgnoreCase(msaBot)) {
            msaBot = null;
        }
    }

    @Override
    public void onInitializeClient() {
        // 圆角用的自定义管线必须赶在界面第一次绘制之前注册。
        // 注册失败（驱动 / 资源包把原版着色器改坏）不会抛出来，UiTheme 会自动退回逐行采样实现。
        RoundedPipelines.register();
        // 液态玻璃：走"模糊分层 + 全屏 SDF"的独立通道（ReGlass 的做法），
        // 注册失败或编译失败时界面自动退回普通深色面板。
        com.aiplayer.client.gui.glass.GlassPipelines.register();
        // 外部 bot 桥接：按配置监听，并把桥接提示接到本机聊天栏
        com.aiplayer.bot.BotBridge.applyConfig();
        com.aiplayer.bot.BotBridge.setBroadcaster(message -> Minecraft.getInstance().execute(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendSystemMessage(message);
            }
        }));
        // 彩蛋：名字叫 bodboy 的 AI 头顶飘一面旗
        BodboyFlag.init();

        openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.aiplayer.config",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_K,
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 客户端 tick 里把桥接的“服务端”置空：提示改走本机聊天栏
            com.aiplayer.bot.BotBridge.clearServer();
            Screen screen = client.gui.screen();
            // 验证码过期就不再等这个 bot
            if (msaBot != null && !com.aiplayer.bot.BotBridge.hasFreshMsa(msaBot)) {
                msaBot = null;
            }
            // 已知 bot 有新验证码：直接弹窗（不打断别的界面）
            if (msaBot != null && !(screen instanceof AiConfigScreen.LinkDialog)) {
                com.aiplayer.bot.BotBridge.MsaTicket ticket = com.aiplayer.bot.BotBridge.msa(msaBot);
                if (ticket != null) {
                    client.setScreenAndShow(new AiConfigScreen.LinkDialog(
                            screen, ticket.bot(), ticket.code(), ticket.url()));
                    screen = client.gui.screen();
                }
            }
            // 新出现的验证码：发到聊天栏 + 弹窗 + 直接唤起浏览器
            boolean canPopup = screen == null || screen instanceof AiConfigScreen;
            com.aiplayer.bot.BotBridge.MsaTicket pending =
                    canPopup ? com.aiplayer.bot.BotBridge.pendingMsa() : null;
            if (pending != null) {
                msaBot = pending.bot();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.translatable(
                            "aiplayer.bot.msaCode", pending.code(), pending.url()));
                }
                client.setScreenAndShow(new AiConfigScreen.LinkDialog(
                        screen, pending.bot(), pending.code(), pending.url()));
                try {
                    com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(pending.url()));
                } catch (Throwable ignored) {
                }
            }
            // 新手教学：只在**第一次进入存档 / 服务器**时弹一次，标题界面不弹。
            // 条件要求"已在世界里"且当前没有其它界面，所以不会盖掉死亡界面、暂停菜单。
            if (!onboardingChecked && client.level != null && client.gui.screen() == null) {
                onboardingChecked = true;
                if (com.aiplayer.client.gui.OnboardingScreen.shouldShow()) {
                    client.setScreenAndShow(new com.aiplayer.client.gui.OnboardingScreen(null));
                }
            }
            while (openConfigKey.consumeClick()) {
                // 26.2：当前界面在 gui.screen()，切换界面用 setScreenAndShow()
                client.setScreenAndShow(new AiConfigScreen(client.gui.screen()));
            }
        });

        // 服务端发来的“AI 说话”请求 -> 用系统自带语音朗读（客户端自己决定读不读）。
        // 组件是原样送过来的，所以是按本机语言渲染后再读。
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                com.aiplayer.net.TtsSpeakPayload.TYPE, (payload, context) -> {
                    String text = payload.text() == null ? "" : payload.text().getString();
                    context.client().execute(() ->
                            com.aiplayer.client.tts.TtsSpeaker.get().speak(payload.speaker(), text));
                });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> {
                    com.aiplayer.client.tts.TtsSpeaker.get().shutdown();
                    // 退出游戏时收掉所有外部 bot 进程 / 代理，避免残留 Node 进程
                    com.aiplayer.bot.BotProcessManager.stopAll();
                    com.aiplayer.bot.BotBridge.shutdown();
                });
        // 进入一个远程服务器时，把配置了「跟随进服」的 bot 也拉进去
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    ServerData server = client.getCurrentServer();
                    if (server != null && server.ip != null && !server.ip.isBlank()) {
                        String address = server.ip.trim();
                        String host = address;
                        int port = 25565;
                        int colon = address.lastIndexOf(':');
                        if (colon > 0 && colon < address.length() - 1) {
                            try {
                                port = Integer.parseInt(address.substring(colon + 1));
                                host = address.substring(0, colon);
                            } catch (NumberFormatException ignored) {
                                host = address;
                            }
                        }
                        String finalHost = host;
                        int finalPort = port;
                        client.execute(() -> com.aiplayer.bot.BotProcessManager.startFollowing(finalHost, finalPort));
                    }
                });

        AiPlayerMod.LOGGER.info("[aiplayer] 客户端入口已加载：默认按 K 打开配置界面");
    }
}