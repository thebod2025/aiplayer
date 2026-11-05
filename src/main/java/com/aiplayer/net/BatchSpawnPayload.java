package com.aiplayer.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量生成 AI（客户端 GUI -> 服务端）。
 * <p>
 * 为什么需要这个包：批量生成里每一只都带"名称 / 强度 / 皮肤"，这些不是原版命令参数能表达的
 * （名字带中文、皮肤名任意、还是一次几十只），靠 {@code sendCommand("aiplayer spawnmany 5")}
 * 只能退化成"按数量随机生成"，玩家在 GUI 里配的名字和皮肤就白填了。
 * <p>
 * 服务端收到后校验 OP 权限，再交给 {@link com.aiplayer.entity.AiPlayerManager#spawnBatchNear}。
 */
public record BatchSpawnPayload(List<Entry> entries) implements CustomPacketPayload {

    /** 一只 AI 的生成参数：名称留空 = 服务端自动取 AI1、AI2……；队伍留空 = 不入队 */
    public record Entry(String name, String skin, int level, String team) {
        /** 兼容旧调用点：不带队伍 */
        public Entry(String name, String skin, int level) {
            this(name, skin, level, "");
        }
    }

    /** 单次最多生成多少只（同时也是条目数上限，防止有人发个几十万条的包把服务端拖死） */
    public static final int MAX_ENTRIES = 32;

    public static final Type<BatchSpawnPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("aiplayer", "batch_spawn"));

    public static final StreamCodec<FriendlyByteBuf, BatchSpawnPayload> STREAM_CODEC =
            CustomPacketPayload.codec(BatchSpawnPayload::write, BatchSpawnPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(BatchSpawnPayload p, FriendlyByteBuf buf) {
        List<Entry> list = p.entries == null ? List.of() : p.entries;
        int n = Math.min(list.size(), MAX_ENTRIES);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Entry e = list.get(i);
            buf.writeUtf(e.name() == null ? "" : e.name(), 64);
            buf.writeUtf(e.skin() == null ? "" : e.skin(), 64);
            buf.writeUtf(e.team() == null ? "" : e.team(), 32);
            buf.writeVarInt(e.level());
        }
    }

    private static BatchSpawnPayload read(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Entry(buf.readUtf(64), buf.readUtf(64), buf.readVarInt(), buf.readUtf(32)));
        }
        return new BatchSpawnPayload(list);
    }
}
