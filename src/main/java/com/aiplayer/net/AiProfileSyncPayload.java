package com.aiplayer.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 单个 AI 的档案字段同步（客户端 GUI -> 专用服务器）。
 * <p>
 * 为什么需要这个包：客户端 GUI 读写的是**客户端本地**的 {@code config/aiplayer.json}，
 * 在单人世界（集成服务端同 JVM、同一份配置）里改动直接生效；但连到**专用服务器**时，
 * AI 真正的档案在服务端，客户端改的皮肤 / 性格 / 嘲讽根本传不过去 ——
 * 表现就是"在 GUI 里改了皮肤，游戏里 AI 还是默认皮肤"。
 * <p>
 * 服务端收到后校验 OP 权限，写入服务端 profile 并落盘。
 */
public record AiProfileSyncPayload(String name, String skinName, String personality, String customTaunts)
        implements CustomPacketPayload {

    public static final Type<AiProfileSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("aiplayer", "ai_profile_sync"));

    public static final StreamCodec<FriendlyByteBuf, AiProfileSyncPayload> STREAM_CODEC =
            CustomPacketPayload.codec(AiProfileSyncPayload::write, AiProfileSyncPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(AiProfileSyncPayload p, FriendlyByteBuf buf) {
        buf.writeUtf(p.name == null ? "" : p.name, 64);
        buf.writeUtf(p.skinName == null ? "" : p.skinName, 64);
        buf.writeUtf(p.personality == null ? "" : p.personality, 256);
        buf.writeUtf(p.customTaunts == null ? "" : p.customTaunts, 1024);
    }

    private static AiProfileSyncPayload read(FriendlyByteBuf buf) {
        return new AiProfileSyncPayload(buf.readUtf(64), buf.readUtf(64), buf.readUtf(256), buf.readUtf(1024));
    }
}
