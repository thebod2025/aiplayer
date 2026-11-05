package com.aiplayer.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -> 客户端 的“朗读请求”：AI 说话时顺带把内容发给装了客户端的玩家。
 * <p>
 * 为什么走网络包而不是让客户端自己监听聊天栏：AI 的台词都是<b>可翻译组件</b>，
 * 服务端只知道翻译键。把组件原样送到客户端，客户端才能用<b>自己设置的语言</b>渲染出来，
 * 再用系统语音朗读 —— 玩家切成英文，读出来的就是英文。
 * <p>
 * 客户端没装模组 / 没注册这个类型时，服务端用 {@code canSend} 判断后跳过，不会报错。
 */
public record TtsSpeakPayload(String speaker, Component text) implements CustomPacketPayload {

    public static final Type<TtsSpeakPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("aiplayer", "tts_speak"));

    /** clientboundPlay 用的是 RegistryFriendlyByteBuf，正好能直接搬用原版的组件编解码 */
    public static final StreamCodec<RegistryFriendlyByteBuf, TtsSpeakPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TtsSpeakPayload::write, TtsSpeakPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(TtsSpeakPayload p, RegistryFriendlyByteBuf buf) {
        buf.writeUtf(p.speaker == null ? "" : p.speaker, 64);
        ComponentSerialization.STREAM_CODEC.encode(buf, p.text);
    }

    private static TtsSpeakPayload read(RegistryFriendlyByteBuf buf) {
        String speaker = buf.readUtf(64);
        Component text = ComponentSerialization.STREAM_CODEC.decode(buf);
        return new TtsSpeakPayload(speaker, text);
    }
}
