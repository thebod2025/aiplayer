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
        // 指令里的目标选择器参数类型：必须先登记，客户端同步指令树时才认得
        com.aiplayer.command.AiTargets.register();
        AiPlayerConfig.get().load();
        // 更新日志 / 版本更新说明 -> logs/aiplayer/（与是否开调试日志无关）
        com.aiplayer.util.ChatLog.startup();

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> AiPlayerCommand.register(dispatcher));

        ServerTickEvents.END_SERVER_TICK.register(server -> AiPlayerManager.get().tickAll(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> AiPlayerManager.get().shutdown());
        // 存档重新加载时把上次的 AI 原地恢复（否则退出再进，AI 就全没了）
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // 放到下一 tick 再生成：SERVER_STARTED 时出生点区块刚加载，直接 spawn 偶尔会
            // 因为区块未就绪把实体顶到区块边界上。延迟一 tick 不影响玩家感知。
            server.execute(() -> AiPlayerManager.get().restoreAll(server));
        });
        // 玩家公屏说话 -> 转给附近的 AI（AI 才会“听得见”、才能接话）
        ChatListener.register();

        // 联机客户端 GUI 的“保存” -> 同步 apiKey/apiBaseUrl/model 到服务端配置（需 OP）
        // Fabric 要求先注册 payload 类型，否则 registerGlobalReceiver 直接抛异常崩溃
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(
                com.aiplayer.net.ProfileSyncPayload.TYPE, com.aiplayer.net.ProfileSyncPayload.STREAM_CODEC);
        // 单个 AI 的档案字段（皮肤 / 性格 / 嘲讽）同步：客户端 GUI 改的是本地配置，
        // 连专用服务器时必须送到服务端才能生效（否则"改了皮肤还是默认皮肤"）
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(
                com.aiplayer.net.AiProfileSyncPayload.TYPE, com.aiplayer.net.AiProfileSyncPayload.STREAM_CODEC);
        // 批量生成（名称 / 强度 / 皮肤）：联机时一次性把清单发给服务端，由服务端按清单生成
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(
                com.aiplayer.net.BatchSpawnPayload.TYPE, com.aiplayer.net.BatchSpawnPayload.STREAM_CODEC);
        // AI 说话 -> 顺带把可翻译组件发给客户端做语音合成（客户端自己决定读不读）
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(
                com.aiplayer.net.TtsSpeakPayload.TYPE, com.aiplayer.net.TtsSpeakPayload.STREAM_CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.aiplayer.net.ProfileSyncPayload.TYPE, (payload, context) -> {
                    net.minecraft.server.MinecraftServer server = context.server();
                    // 长度闸门：编解码层（writeUtf/readUtf 带 maxLength）已经拦了一道，
                    // 这里再挡一道，防止将来有人改了 codec 就出现"客户端发包构造超长字符串撑爆服务端内存"。
                    if (tooLong(payload.apiKey(), 512) || tooLong(payload.apiBaseUrl(), 256)
                            || tooLong(payload.model(), 128)
                            || tooLong(payload.chatApiBaseUrl(), 256) || tooLong(payload.chatApiKey(), 512)
                            || tooLong(payload.chatModel(), 128)) {
                        LOGGER.warn("[aiplayer] 丢弃超长的配置同步包（来自 {}）",
                                context.player() == null ? "?" : context.player().getScoreboardName());
                        return;
                    }
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
                        cfg.dualApi = payload.dualApi();
                        cfg.chatApiBaseUrl = payload.chatApiBaseUrl();
                        cfg.chatApiKey = payload.chatApiKey();
                        cfg.chatModel = payload.chatModel();
                        cfg.save();
                        player.sendSystemMessage(net.minecraft.network.chat.Component
                                .translatable("aiplayer.mod.configSynced")
                                .withStyle(net.minecraft.ChatFormatting.GREEN));
                    });
                });
        // 单个 AI 的档案字段同步（皮肤 / 性格 / 嘲讽）：同样只有 OP 能改
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.aiplayer.net.AiProfileSyncPayload.TYPE, (payload, context) -> {
                    net.minecraft.server.MinecraftServer server = context.server();
                    if (tooLong(payload.name(), 64) || tooLong(payload.skinName(), 64)
                            || tooLong(payload.personality(), 256) || tooLong(payload.customTaunts(), 1024)) {
                        LOGGER.warn("[aiplayer] 丢弃超长的档案同步包（来自 {}）",
                                context.player() == null ? "?" : context.player().getScoreboardName());
                        return;
                    }
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer player = context.player();
                        if (player == null || !player.permissions().hasPermission(
                                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
                            return;
                        }
                        if (AiPlayerManager.get().applyProfileSync(server, payload.name(),
                                payload.skinName(), payload.personality(), payload.customTaunts(), true)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component
                                    .translatable("aiplayer.mod.configSynced")
                                    .withStyle(net.minecraft.ChatFormatting.GREEN));
                        }
                    });
                });
        // 批量生成：只允许 OP 发包，避免有人靠它刷出成百上千只 AI 把服务器拖垮
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.aiplayer.net.BatchSpawnPayload.TYPE, (payload, context) -> {
                    net.minecraft.server.MinecraftServer server = context.server();
                    if (payload.entries() == null || payload.entries().isEmpty()) {
                        return;
                    }
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer player = context.player();
                        if (player == null || !player.permissions().hasPermission(
                                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
                            return;
                        }
                        java.util.List<com.aiplayer.entity.AiPlayerManager.BatchSpec> specs =
                                new java.util.ArrayList<>();
                        for (com.aiplayer.net.BatchSpawnPayload.Entry e : payload.entries()) {
                            com.aiplayer.entity.AiPlayerManager.BatchSpec spec =
                                    new com.aiplayer.entity.AiPlayerManager.BatchSpec();
                            spec.name = e.name();
                            spec.skin = e.skin();
                            spec.team = e.team();
                            spec.level = e.level();
                            specs.add(spec);
                        }
                        int made = com.aiplayer.entity.AiPlayerManager.get()
                                .spawnBatchNear(server, player.getUUID(), specs);
                        player.sendSystemMessage(
                                net.minecraft.network.chat.Component.translatable("aiplayer.cmd.spawnManyOk", made));
                    });
                });

        LOGGER.info("[aiplayer] 已成功加载！！！！！！！！！！！！！！！！！！！和ai玩吧awa!");
    }

    /** 网络包字段长度闸门：null 视为合法（0 长度），超长一律拒收 */
    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }
}