package com.aiplayer.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 GUI -> 服务端 的配置同步包。
 * <p>
 * 专用服务器上 AI 的配置在服务端的 config/aiplayer.json，客户端 GUI 改的是本地副本，
 * 服务器感知不到 —— 联机时保存就发这个包，由服务端校验 OP 后写进真正的配置。
 */
public record ProfileSyncPayload(String apiKey, String apiBaseUrl, String model)
        implements CustomPacketPayload {

    public static final Type<ProfileSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("aiplayer", "profile_sync"));

    /** 用三个 Utf 字符串的简易编解码（顺序固定：apiKey, apiBaseUrl, model） */
    public static final StreamCodec<FriendlyByteBuf, ProfileSyncPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ProfileSyncPayload::write, ProfileSyncPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(ProfileSyncPayload p, FriendlyByteBuf buf) {
        buf.writeUtf(p.apiKey == null ? "" : p.apiKey, 512);
        buf.writeUtf(p.apiBaseUrl == null ? "" : p.apiBaseUrl, 256);
        buf.writeUtf(p.model == null ? "" : p.model, 128);
    }

    private static ProfileSyncPayload read(FriendlyByteBuf buf) {
        return new ProfileSyncPayload(buf.readUtf(512), buf.readUtf(256), buf.readUtf(128));
    }
}
