package com.aiplayer.entity;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Consumer;

/**
 * 假的网络连接：AI 玩家没有真实 socket，所有出站数据全部丢弃、协议切换全部跳过。
 * <p>
 * 为什么必须把这些方法都覆写掉：原版 {@link Connection} 的协议切换
 * （{@code setupInboundProtocol} / {@code setupOutboundProtocol}）是
 * {@code this.channel.writeAndFlush(配置任务)}，而我们的连接没有 netty channel，
 * 直接走原版实现会抛 {@code NullPointerException: this.channel is null}，
 * 于是 {@code PlayerList.placeNewPlayer} 中途失败、AI 根本进不来。
 * <p>
 * 因此这里把 channel 相关的动作全部变成空操作，并自己记住协议监听器。
 */
public class AiNetConnection extends Connection {
    private static final SocketAddress FAKE_ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    /** 原版把 packetListener 存在私有字段里，我们没有 channel 也就没法调 super，所以自己存一份 */
    private PacketListener listener;

    public AiNetConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    // ------------------------------------------------------------------
    // 协议切换：不碰 channel
    // ------------------------------------------------------------------

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
        this.listener = listener;
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
        this.listener = listener;
    }

    @Override
    public PacketListener getPacketListener() {
        return this.listener;
    }

    @Override
    public void setupCompression(int threshold, boolean validateDecompressed) {
    }

    @Override
    public void setEncryptionKey(javax.crypto.Cipher decrypt, javax.crypto.Cipher encrypt) {
    }

    // ------------------------------------------------------------------
    // 出站：直接丢弃
    // ------------------------------------------------------------------

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
    }

    @Override
    public void runOnceConnected(Consumer<Connection> action) {
        action.accept(this);
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public void tick() {
    }

    @Override
    public void setReadOnly() {
    }

    // ------------------------------------------------------------------
    // 连接状态
    // ------------------------------------------------------------------

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public boolean isMemoryConnection() {
        return true;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return FAKE_ADDRESS;
    }

    /** 断开请求直接吞掉：AI 的生命周期由 {@link AiPlayerManager} 管，客户端说了不算 */
    @Override
    public void disconnect(Component reason) {
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
    }
}