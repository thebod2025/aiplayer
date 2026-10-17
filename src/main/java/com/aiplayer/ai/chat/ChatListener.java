package com.aiplayer.ai.chat;

import com.aiplayer.entity.AiPlayer;
import com.aiplayer.entity.AiPlayerManager;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;

/**
 * 把玩家在公屏说的话转给附近的 AI。
 * <p>
 * 没有这一层时 AI 是“聋的”——只会按照自己的状态自言自语，玩家喊它、问它都没反应。
 * 这里只负责分发：听不听、回不回、隔多久回，全部由 {@code AiBrain.onPlayerChat} 判断，
 * 避免在每个 AI 里重复注册监听、也避免给远处的 AI 白白触发请求（省 token）。
 */
public final class ChatListener {
    private ChatListener() {
    }

    public static void register() {
        // CHAT_MESSAGE 是“消息已经确定要广播”时触发，拿到的是玩家原话（signedContent）
        ServerMessageEvents.CHAT_MESSAGE.register(ChatListener::onChat);
    }

    private static void onChat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound bound) {
        AiPlayerManager manager = AiPlayerManager.get();
        if (manager.count() == 0) {
            return;   // 世界里没有 AI，直接跳过
        }
        // AI 自己发出的话（LLM 用 /say、AI 之间的对话）绝不能再当成"玩家说的话"喂回来：
        // 否则 AI 会把同伴（或自己）说的"好的主人"当成主人下达的指令去照做。
        if (sender instanceof AiPlayer) {
            return;
        }
        String text = message.signedContent();
        if (text == null || text.isBlank()) {
            // 离线模式 / 未签名消息拿不到 signedContent，退回未签名内容
            Component unsigned = message.unsignedContent();
            text = unsigned == null ? null : unsigned.getString();
        }
        if (text == null || text.isBlank()) {
            return;
        }
        for (AiPlayer ai : manager.all()) {
            if (ai.isAlive() && ai.brain() != null) {
                // 这个方法内部会按距离 / 点名 / 冷却做过滤
                ai.brain().onPlayerChat(sender, text);
            }
        }
    }
}
