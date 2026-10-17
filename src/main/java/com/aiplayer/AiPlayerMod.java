package com.aiplayer;

import com.aiplayer.ai.chat.ChatListener;
import com.aiplayer.command.AiPlayerCommand;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.entity.AiPlayerManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组入口（服务端 / 通用侧）。
 * <p>
 * AI 玩家是标准的 {@code ServerPlayer} 子类，挂在服务器上由 {@link AiPlayerManager} 每 tick 驱动，
 * 所以单人存档、局域网、专用服务器都能用，也不需要客户端装模组（装了就多个配置界面）。
 */
public class AiPlayerMod implements ModInitializer {
    public static final String MOD_ID = "aiplayer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        AiPlayerConfig.get().load();

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> AiPlayerCommand.register(dispatcher));

        ServerTickEvents.END_SERVER_TICK.register(server -> AiPlayerManager.get().tickAll(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> AiPlayerManager.get().shutdown());
        // 玩家公屏说话 -> 转给附近的 AI（AI 才会“听得见”、才能接话）
        ChatListener.register();

        // 联机客户端 GUI 的“保存” -> 同步 apiKey/apiBaseUrl/model 到服务端配置（需 OP）
        // Fabric 要求先注册 payload 类型，否则 registerGlobalReceiver 直接抛异常崩溃
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(
                com.aiplayer.net.ProfileSyncPayload.TYPE, com.aiplayer.net.ProfileSyncPayload.STREAM_CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.aiplayer.net.ProfileSyncPayload.TYPE, (payload, context) -> {
                    net.minecraft.server.MinecraftServer server = context.server();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer player = context.player();
                        // 26.x 没有 hasPermissions(int)，走 PermissionSet 判断
                        if (player == null || !player.permissions().hasPermission(
                                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
                            return;   // 只有 OP 能改服务器配置
                        }
                        AiPlayerConfig cfg = AiPlayerConfig.get();
                        cfg.apiKey = payload.apiKey();
                        cfg.apiBaseUrl = payload.apiBaseUrl();
                        cfg.model = payload.model();
                        cfg.save();
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "§a[aiplayer] 服务端配置已更新（apiKey/apiBaseUrl/model）"));
                    });
                });

        LOGGER.info("[aiplayer] 已成功加载！！！！！！！！！！！！！！！！！！！和ai玩吧awa!");
    }
}