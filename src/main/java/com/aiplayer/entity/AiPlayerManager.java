package com.aiplayer.entity;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.aiplayer.util.InvUtil;
import com.aiplayer.util.SkinFetcher;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** AI 玩家的生成 / 移除 / 管理 */
public class AiPlayerManager {
    private static final AiPlayerManager INSTANCE = new AiPlayerManager();

    private final Map<String, AiPlayer> byName = new LinkedHashMap<>();
    private final Map<UUID, AiPlayer> byUuid = new HashMap<>();
    /**
     * 还在等自己皮肤的 AI（AI 名 -> 正版玩家名）。
     * <p>
     * 皮肤是后台异步拉的：生成 AI 时绝不联网（否则服务端主线程会被 Mojang 接口卡死）。
     * 拉取完成后由 {@link #tickAll} 发现并热更一次实体，玩家最多晚一两秒看到皮肤。
     */
    private final Map<String, String> pendingSkin = new HashMap<>();
    /**
     * 开服待恢复的 AI 档案。每 tick 只恢复一只：
     * 一次性 spawn 几十只会在开服瞬间卡住服务端（每只都要走完整加入流程 + 读区块）。
     */
    private final java.util.Deque<AiProfile> restoreQueue = new java.util.ArrayDeque<>();
    /** 队列所属存档指纹：玩家中途换存档时不能把上一张图的 AI 撒过来 */
    private String restoreWorld = "";

    private AiPlayerManager() {
    }

    public static AiPlayerManager get() {
        return INSTANCE;
    }

    public Collection<AiPlayer> all() {
        return this.byName.values();
    }

    public AiPlayer get(String name) {
        return name == null ? null : this.byName.get(name.toLowerCase(Locale.ROOT));
    }

    public AiPlayer get(UUID uuid) {
        return this.byUuid.get(uuid);
    }

    public int count() {
        return this.byName.size();
    }

    public static UUID uuidFor(String name) {
        return UUID.nameUUIDFromBytes(("aiplayer:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    /** @return 生成好的 AI；失败返回 null */
    public AiPlayer spawn(ServerLevel level, String name, Vec3 pos, float yaw, float pitch, boolean kit) {
        if (name == null || name.isBlank() || name.length() > 16) {
            return null;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return null;
        }
        PlayerList list = server.getPlayerList();
        String key = name.toLowerCase(Locale.ROOT);
        AiPlayer existing = this.byName.get(key);
        if (existing != null) {
            if (!existing.isRemoved()) {
                // 真的已经有一个活着的 AI 用这个名字
                return null;
            }
            // 残留记录：实体已被外部移除（区块卸载 / 指令清除），但还没轮到 tickAll 清理。
            // 不清掉的话这个名字会被永久占用 —— 表现就是"这个 AI 再也生成不出来"。
            this.byName.remove(key);
            this.byUuid.remove(existing.getUUID());
        }
        // 全服数量上限：批量生成 / autoKeep 恢复 / 连点 GUI 都可能一口气拉起上百只 AI，
        // 服务端 tick 会被拖垮。这里统一在唯一的生成入口兜底。
        int limit = Math.max(1, AiPlayerConfig.get().maxAiPlayers);
        if (this.byName.size() >= limit) {
            AiPlayerMod.LOGGER.warn("[aiplayer] AI 数量已达上限 {}，拒绝生成 {}", limit, name);
            return null;
        }
        UUID uuid = uuidFor(name);
        ServerPlayer stale = list.getPlayer(uuid);
        if (stale == null) {
            stale = list.getPlayerByName(name);
        }
        if (stale != null && !(stale instanceof AiPlayer)) {
            // 名字被一个**真人玩家**占着（哪怕 UUID 不同）时会走到这里。
            // 以前这里直接 return null -> 玩家收到"名字非法或已被占用"，
            // 而 CustomSkinLoader 是按**名字**解析皮肤的，所以想借用"皮肤名 = 自己的名字"
            // 就必然撞上这条 —— 表现就是"皮肤设为 The_bodboy_ 显示已被占用"。
            // 现在不再判重拒绝：AI 用的是自己派生的 UUID，跟真人玩家的 UUID 不冲突，
            // 允许同名共存（Tab 里会看到两个同名，但 AI 的功能都正常）。
            AiPlayerMod.LOGGER.info("[aiplayer] 名字 {} 与在线玩家重名，仍按独立 UUID 生成", name);
        }
        if (stale instanceof AiPlayer ghost) {
            if (ghost.isRemoved()) {
                // 灵魂残留：实体已被移除但 PlayerList 条目还在（历史失败遗留）。
                // 不清掉的话这里 return null 会把名字永久锁死 —— 表现就是"重名，永远生成不出来"。
                list.remove(ghost);
            } else {
                return null;
            }
        }
        // ai 声明在 try 外：catch 里要引用它清理半成品灵魂
        AiPlayer ai = null;
        try {
            AiProfile profile = AiPlayerConfig.get().getOrCreate(name);
            // 自定义皮肤：用指定正版玩家名的皮肤（离线 / 无网络时静默退回默认皮肤）
            GameProfile gameProfile = SkinFetcher.withSkin(uuid, name, profile.skinName);
            ClientInformation info = ClientInformation.createDefault();
            ai = new AiPlayer(server, level, gameProfile, info);
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(gameProfile, false);
            // 假连接：所有出站包丢弃、协议切换空实现（没有真实 socket）
            Connection connection = new AiNetConnection();

            // 关键：必须走原版完整的“玩家加入”流程。
            // placeNewPlayer 会注册 PlayerList、写进 ServerLevel、广播 PlayerInfo，
            // 并且通过 setServerLevel -> chunkMap.move 把玩家登记进 ChunkMap。
            // 只调 level.addNewPlayer(ai) 的话，AI 只会出现在 Tab 列表里，世界里看不到实体。
            list.placeNewPlayer(connection, ai, cookie);

            // 游戏模式必须放在 placeNewPlayer 之后设置。
            // setGameMode -> ServerPlayerGameMode.changeGameModeForPlayer 会广播
            // ClientboundPlayerInfoUpdatePacket，而该包的 Entry 构造器要读
            // player.connection.latency()；player.connection 是 placeNewPlayer 内部
            // 才创建并赋值的（它自己 new 一个 ServerGamePacketListenerImpl）。
            // 提前调用的话 player.connection 还是 null，必然 NPE，AI 完全生成不出来。
            ai.setGameMode(GameType.SURVIVAL);

            if (ai.isRemoved()) {
                AiPlayerMod.LOGGER.warn("[aiplayer] {} 生成后立即被移除（检查出生点区块是否已加载）", name);
                return null;
            }

            // placeNewPlayer 会用 world/playerdata 里的存档数据覆盖位置、朝向和血量，
            // 所以出生点必须在它之后再设一次，否则 AI 会落回上次死亡的地方（还带着空血）。
            setPosSafely(level, ai, pos, yaw, pitch);
            ai.setHealth(ai.getMaxHealth());
            // 兜底：强制把玩家纳入 ChunkMap 的轨迹视图。
            // 没有这一步时会出现“Tab 列表里有名字、世界里看不到实体”的情况 ——
            // 实体虽然进了 ServerLevel，但没有被登记进 ChunkMap 的玩家表，别的客户端就收不到它的生成包。
            level.getChunkSource().move(ai);

            ai.attach(this, profile);
            // 只在背包为空时发装备包。
            // spawn 走的是 placeNewPlayer，它会先把 playerdata 里的旧背包读回来；
            // 以前无条件 giveKit 会让 AI 每重启一次就多一整套装备，背包很快塞满、再被丢一地。
            if (kit && ai.getInventory().isEmpty()) {
                // 玩家可以在 GUI 里自定义装备包（JSON 完整快照优先，其次字符串清单，最后默认套装）
                InvUtil.giveKit(ai, profile);
            }
            // 装了枪械模组却一把枪都没有：补一把「能真的开火」的枪（TaCZ 的枪种存在物品数据里，
            // 手动塞进装备包的枪可能是没有枪种数据的空壳，反查不到就一直打不响）
            if (com.aiplayer.config.AiPlayerConfig.get().aiGunKit) {
                com.aiplayer.compat.TaczCompat.grantStarterGun(ai);
            }
            this.byName.put(key, ai);
            this.byUuid.put(uuid, ai);
            // 皮肤还没在缓存里：交给后台线程去拉，拉到后由 tickAll 热更（这里绝不同步等网络）
            String skin = profile.skinName;
            if (skin != null && !skin.isBlank() && !SkinFetcher.isResolved(skin)) {
                SkinFetcher.prefetch(skin);
                this.pendingSkin.put(key, skin.trim());
            }
            AiPlayerMod.LOGGER.info("[aiplayer] 已生成 AI 玩家 {} @ {}", name, pos);
            return ai;
        } catch (Throwable t) {
            AiPlayerMod.LOGGER.error("[aiplayer] 生成 AI 玩家 {} 失败", name, t);
            // 关键：placeNewPlayer 可能已经成功（聊天里广播了"加入了游戏"），
            // 后续步骤炸掉的话这个半成品会一直挂在 PlayerList / 世界里 ——
            // Tab 有名字、世界没实体（灵魂），还会把名字占死。必须立刻清掉。
            try {
                dispose(ai);
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /**
     * 出生点安全化：把 AI 放到指定位置，并处理“生成点卡在方块里”的情况。
     * <p>
     * 世界出生点 / 床的重生位置经常正好压在方块、半砖或台阶里，直接 {@code setPos} 会让 AI
     * 一出生就被埋住 —— 表现为“看不到人 / 一直窒息掉血 / 原地抽搐出不来”。
     * 这里用原版碰撞检测 {@link ServerLevel#noCollision(net.minecraft.world.entity.Entity)}
     * 判断 AI 的整只碰撞箱是否与方块相交，相交就逐格上移直到腾出空间（最多 16 格兜底）。
     */
    private static void setPosSafely(ServerLevel level, AiPlayer ai, Vec3 pos, float yaw, float pitch) {
        ai.setPos(pos.x, pos.y, pos.z);
        ai.setYRot(yaw);
        ai.setXRot(pitch);
        ai.setYHeadRot(yaw);
        for (int i = 0; i < 16 && !level.noCollision(ai); i++) {
            ai.setPos(ai.getX(), ai.getY() + 1.0D, ai.getZ());
        }
    }

    /** 把已有的 AI 挪到主人身边（同一维度内），并做落点安全化 */
    private void moveNextTo(ServerLevel level, AiPlayer ai, ServerPlayer owner) {
        setPosSafely(level, ai, owner.position(), owner.getYRot(), ai.getXRot());
        ai.setDeltaMovement(Vec3.ZERO);
        ai.resetFallDistance();
        // 和 spawn 一样：靠 ChunkMap 的轨迹视图把新位置同步给客户端
        level.getChunkSource().move(ai);
    }

    /**
     * 在指定玩家身边生成一名 AI，并把结果直接回复给该玩家。
     * <p>
     * 给 GUI 的“生成”按钮用：单人 / 局域网主机时客户端和服务端在同一进程里，
     * 可以直接排队到服务端线程执行；这样就不必依赖
     * {@code ClientPacketListener.sendCommand}（那条路要先在客户端镜像命令树上 parse，
     * 带参数的命令还牵扯命令签名，任何一环没对上都会静默失败）。
     *
     * @return 是否生成成功
     */
    public boolean spawnNear(MinecraftServer server, UUID ownerUuid, String name) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner == null) {
            return false;
        }
        try {
            // 26.2 的 ServerPlayer 没有 serverLevel()，只有返回 Level 的 level()（AiPlayer 里也做了同样的强转）
            ServerLevel level = (ServerLevel) owner.level();
            // 同名 AI 已经活着：直接把它挪到主人身边，而不是回一句"生成失败"。
            // 玩家点"生成"的预期是"我身边要有个叫这个名字的 AI"，报重名会被当成"加不了 AI"。
            AiPlayer existing = get(name);
            if (existing != null && !existing.isRemoved() && existing.isAlive()) {
                moveNextTo(level, existing, owner);
                // 重新“生成”= 认这个玩家为主人（主人可以换）
                AiProfile p = AiPlayerConfig.get().getOrCreate(name);
                p.ownerName = owner.getScoreboardName();
                AiPlayerConfig.get().save();
                owner.sendSystemMessage(Component.translatable("aiplayer.gui.spawnOk", name));
                return true;
            }
            AiProfile profile = AiPlayerConfig.get().getOrCreate(name);
            // 生成者即主人：AI 会听主人的话、绝不攻击主人，主人被打时帮主人反击
            profile.ownerName = owner.getScoreboardName();
            if (profile.personality == null || profile.personality.isBlank()) {
                // 老配置里没有性格字段：生成时补一个，落盘后固定下来（同一只 AI 脾气稳定）
                profile.personality = AiProfile.randomPersonality();
            }
            AiPlayerConfig.get().save();
            boolean kit = profile.giveKit;
            AiPlayer ai = this.spawn(level, name, owner.position(), owner.getYRot(), 0.0F, kit);
            owner.sendSystemMessage(Component.translatable(
                    ai != null ? "aiplayer.gui.spawnOk" : "aiplayer.gui.spawnFail", name));
            return ai != null;
        } catch (Throwable t) {
            // 任何异常都必须让玩家看到反馈。以前异常静默冒泡，玩家侧的表现就是"点了没反应"。
            AiPlayerMod.LOGGER.error("[aiplayer] 在 {} 身边生成 AI {} 失败", owner.getScoreboardName(), name, t);
            owner.sendSystemMessage(Component.translatable("aiplayer.gui.spawnFail", name));
            return false;
        }
    }

    /** 批量生成里每一项的配置：名称留空 = 自动取 AI1、AI2…… */
    public static final class BatchSpec {
        public String name = "";
        public String skin = "";
        /** 队伍名，留空 = 不入队 */
        public String team = "";
        public int level = 2;
    }

    /** 找一个没被占用的自动名字（配置文件档案 + 活体实体都要查） */
    private String nextFreeName() {
        AiPlayerConfig cfg = AiPlayerConfig.get();
        for (int n = 1; n <= 999; n++) {
            String candidate = "AI" + n;
            if (cfg.find(candidate) == null && get(candidate) == null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * GUI 批量生成：按清单逐只建档案（名称 / 强度 / 皮肤）再拉起来。
     * <p>
     * 名称留空自动补 AI1、AI2……；同名且还活着的跳过（不覆盖玩家已有的那只）。
     * 皮肤只写进 profile，由 {@link #spawn} 里的 SkinFetcher 后台拉取并热更。
     *
     * @return 实际生成成功的数量
     */
    public int spawnBatchNear(MinecraftServer server, UUID ownerUuid, List<BatchSpec> specs) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner == null || specs == null || specs.isEmpty()) {
            return 0;
        }
        ServerLevel level = (ServerLevel) owner.level();
        AiPlayerConfig cfg = AiPlayerConfig.get();
        int made = 0;
        for (int i = 0; i < specs.size(); i++) {
            BatchSpec spec = specs.get(i);
            String name = spec.name == null ? "" : spec.name.trim();
            if (name.isEmpty()) {
                name = nextFreeName();
                if (name == null) {
                    break;
                }
            } else {
                AiPlayer alive = get(name);
                if (alive != null && !alive.isRemoved()) {
                    continue;   // 已经活着了，别重复生成
                }
            }
            AiProfile profile = cfg.getOrCreate(name);
            profile.ownerName = owner.getScoreboardName();
            profile.levelId = Math.max(1, Math.min(4, spec.level));
            if (spec.skin != null && !spec.skin.isBlank()) {
                profile.skinName = spec.skin.trim();
            }
            // 批量生成时填了队伍：整批都进同一个队，省得一只只去 GUI 里改
            if (spec.team != null && !spec.team.isBlank()) {
                profile.team = spec.team.trim();
            }
            if (profile.personality == null || profile.personality.isBlank()) {
                profile.personality = AiProfile.randomPersonality();
            }
            // 圆环撒开，避免一次生成好几只在同一格互相挤
            double angle = i * (Math.PI * 2.0D / Math.max(1, specs.size()));
            double radius = 1.5D + (i / 8) * 1.5D;
            Vec3 at = owner.position().add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
            if (this.spawn(level, name, at, owner.getYRot(), 0.0F, profile.giveKit) != null) {
                made++;
            }
        }
        cfg.save();
        return made;
    }

    /**
     * 批量生成：在 {@code owner} 身边一次拉起 {@code count} 只 AI，名字自动取 AI1、AI2……
     * <p>
     * 名字查重要同时看配置档案（{@link AiPlayerConfig#find}）和当前活着的实体，只看一边的话
     * 撞名的那只就生成不出来。出生点按圆环撒开，避免 N 只 AI 挤在同一格。
     *
     * @return 实际生成成功的数量
     */
    public int spawnManyNear(ServerLevel level, Vec3 center, float yaw, int count, String ownerName, int aiLevel) {
        AiPlayerConfig cfg = AiPlayerConfig.get();
        int made = 0;
        for (int i = 0; i < count; i++) {
            String name = nextFreeName();
            if (name == null) {
                break;
            }
            double angle = i * (Math.PI * 2.0D / Math.max(1, count));
            double radius = 1.5D + (i / 8) * 1.5D;
            Vec3 at = center.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
            AiProfile profile = cfg.getOrCreate(name);
            if (ownerName != null) {
                profile.ownerName = ownerName;
            }
            if (aiLevel >= 1 && aiLevel <= 4) {
                profile.levelId = aiLevel;
            }
            AiPlayer ai = spawn(level, name, at, yaw, 0.0F, profile.giveKit);
            if (ai == null) {
                continue;
            }
            made++;
        }
        cfg.save();
        return made;
    }

    /**
     * 热更皮肤：不用重新点“生成”，改完皮肤立刻生效。
     * <p>
     * 为什么不只重播 PlayerInfo：客户端 {@code AbstractClientPlayer} 把 PlayerInfo
     * 缓存在实体字段里（{@code playerInfo == null} 时才去 playerInfoMap 查），
     * 而且没有对外 setter —— 重播 PlayerInfo 只会换掉 map 里的对象，实体仍指向旧的，
     * 皮肤不会变。所以这里按原版流程把实体移除、再带着新 profile 重新加入，
     * 客户端就会重建 RemotePlayer 并重新解析皮肤。
     * <p>
     * 位置、朝向、血量、背包 / 装备全部保留，只是大脑状态会重置。
     *
     * @return 是否成功（AI 不存在或重建失败返回 false）
     */
    public boolean applySkin(MinecraftServer server, String name, String skinName) {
        if (name == null) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT);
        AiPlayer ai = this.byName.get(key);
        if (ai == null) {
            return false;
        }
        String skin = skinName == null ? "" : skinName.trim();
        AiProfile profile = ai.profile();
        if (profile != null) {
            profile.skinName = skin;
        }
        AiPlayerConfig.get().save();

        ServerLevel level = ai.serverLevel();
        Vec3 pos = ai.position();
        float yaw = ai.getYRot();
        float pitch = ai.getXRot();
        float health = ai.getHealth();
        // 用实体自己的名字重建，不要用传进来的参数：调用方可能传的是小写 key，
        // 直接拿来当名字会把 AI 的显示名从 "Bob" 变成 "bob"。
        String displayName = ai.getScoreboardName();
        AiPlayer.GearSnapshot gear = new AiPlayer.GearSnapshot(ai);

        this.byName.remove(key);
        this.byUuid.remove(ai.getUUID());
        this.dispose(ai);

        // kit=false：装备由 gear 快照还原，避免重复发一套
        AiPlayer fresh = this.spawn(level, displayName, pos, yaw, pitch, false);
        if (fresh == null) {
            AiPlayerMod.LOGGER.warn("[aiplayer] 热更 {} 的皮肤失败：重建实体没成功", displayName);
            return false;
        }
        gear.restore(fresh);
        fresh.setHealth(Math.max(1.0F, Math.min(health, fresh.getMaxHealth())));
        AiPlayerMod.LOGGER.info("[aiplayer] 已热更 {} 的皮肤 -> {}", displayName, skin.isEmpty() ? "(默认)" : skin);
        return true;
    }

    /**
     * 专用服务器：把客户端 GUI 改的档案字段（皮肤 / 性格 / 嘲讽）写进服务端 profile 并落盘。
     * <p>
     * 单人世界里客户端和服务端共用同一份 {@code config/aiplayer.json}，改动天然生效；
     * 连到专用服务器时就必须走这个入口，否则客户端只是在改自己本地的文件，服务端一无所知
     * —— 这就是"GUI 里改了皮肤但 AI 还是默认皮肤"的根因。
     *
     * @param skinChange true 表示这次带皮肤变更，需要在 AI 存活时热更（会重建实体）
     */
    public boolean applyProfileSync(net.minecraft.server.MinecraftServer server, String name,
                                    String skinName, String personality, String customTaunts,
                                    boolean skinChange) {
        if (name == null || name.isBlank()) {
            return false;
        }
        AiProfile profile = AiPlayerConfig.get().find(name);
        if (profile == null) {
            return false;
        }
        String skin = skinName == null ? "" : skinName.trim();
        boolean skinChanged = !skin.equals(profile.skinName == null ? "" : profile.skinName.trim());
        profile.skinName = skin;
        if (personality != null) {
            profile.personality = personality;
        }
        if (customTaunts != null) {
            profile.customTaunts = customTaunts;
        }
        AiPlayerConfig.get().save();
        // 皮肤是全服可见的东西，改了就得热更；AI 不在线就等下次生成时随 profile 生效
        if (skinChanged || skinChange) {
            applySkin(server, name, skin);
        }
        return true;
    }

    public boolean remove(String name) {
        String key = name == null ? null : name.toLowerCase(Locale.ROOT);
        AiPlayer ai = this.byName.remove(key);
        if (ai == null) {
            return false;
        }
        this.byUuid.remove(ai.getUUID());
        this.pendingSkin.remove(key);
        this.dispose(ai);
        return true;
    }

    public void removeAll() {
        for (AiPlayer ai : this.byName.values()) {
            this.byUuid.remove(ai.getUUID());
            this.dispose(ai);
        }
        this.byName.clear();
        this.pendingSkin.clear();
    }

    private void dispose(AiPlayer ai) {
        try {
            MinecraftServer server = ai.serverLevel().getServer();
            if (server == null) {
                return;
            }
            // 原版完整移除流程：解绑实体 + 从 ChunkMap 注销 + 移除并广播 PlayerInfo + 保存玩家数据。
            // 之前手工 removePlayerImmediately 只解绑实体，PlayerInfo 会残留在 Tab 列表里。
            server.getPlayerList().remove(ai);
        } catch (Throwable t) {
            AiPlayerMod.LOGGER.warn("[aiplayer] 移除 AI 失败: {}", t.toString());
        }
    }

    /** 服务器每 tick 调用：处理死亡重生 */
    public void tickAll(MinecraftServer server) {
        applyPendingSkins(server);
        restoreOne(server);
        if (this.byName.isEmpty()) {
            return;
        }
        for (AiPlayer ai : this.byName.values().toArray(new AiPlayer[0])) {
            if (ai.isRemoved()) {
                // 实体被外部移除（区块卸载 / 插件踢人 / 指令清除）：清掉记录，
                // 否则这个名字和 UUID 会一直被占着，后面再也生成不出来。
                this.byName.remove(ai.getScoreboardName().toLowerCase(Locale.ROOT));
                this.byUuid.remove(ai.getUUID());
                continue;
            }
            // 新的一轮服务器 tick：允许物理再跑一次
            ai.resetPhysicsGuard();
            if (ai.isDeadOrDying() && ai.deathTime > 60) {
                String name = ai.getScoreboardName();
                ServerLevel level = ai.serverLevel();
                // 击杀者要在 dispose 前取（死亡后引用会被清）
                String killer = ai.getLastHurtByMob() != null
                        ? ai.getLastHurtByMob().getName().getString() : null;
                level.getServer().getPlayerList().broadcastSystemMessage(
                        killer != null
                                ? net.minecraft.network.chat.Component.translatable(
                                        "aiplayer.event.deathKilled", name, killer)
                                : net.minecraft.network.chat.Component.translatable(
                                        "aiplayer.event.deathPlain", name), false);
                // kit 固定：重生一律按 profile.kitItems 重新发放，不继承死亡时的背包。
                // 快照只为防掉落（原版死亡流程会把装备掉一地），不再 restore ——
                // 之前"保留装备"会把残骸背包带进重生，玩家改了 kit 也永远不生效。
                ai.takeDeathGear();
                // 死亡地点：非主世界时用它就近复活（全局重生点是主世界的坐标，直接拿来在
                // 下界 / 末地复活会把 AI 塞进地形里，甚至塞进岩浆）
                Vec3 deathPos = ai.position();
                // 复活后回去捡装备用的坐标（死亡瞬间记录，和复活点无关）
                BlockPos deathPoint = ai.deathPointIn(level);
                this.dispose(ai);
                this.byName.remove(name.toLowerCase(Locale.ROOT));
                this.byUuid.remove(ai.getUUID());
                Vec3 spawn = level.dimension() == Level.OVERWORLD
                        ? Vec3.atLowerCornerOf(level.getLevelData().getRespawnData().pos())
                        : deathPos;
                // kit=true：spawn 内部按 profile.kitItems 发固定装备（清单为空则默认套装）
                AiPlayer respawned = this.spawn(level, name, spawn, ai.getYRot(), 0.0F, true);
                if (respawned == null) {
                    AiPlayerMod.LOGGER.warn("[aiplayer] {} 重生失败", name);
                    continue;
                }
                respawned.setHealth(respawned.getMaxHealth());
                // 死亡回收：记住掉装备的位置，让大脑自己走回去捡（找不到 / 超时会自动放弃）
                if (deathPoint != null && respawned.brain() != null) {
                    respawned.brain().recoverAt(deathPoint);
                }
                AiPlayerMod.LOGGER.info("[aiplayer] {} 已按固定装备包复活", name);
            }
        }
    }

    /** 服务器关闭时清理（顺手把每只 AI 的位置写进档案，下次启动好原地恢复） */
    public void shutdown() {
        // 还没恢复完就关服：队列里剩下的档案不能带到下一张图
        this.restoreQueue.clear();
        savePositions();
        for (AiPlayer ai : this.byName.values()) {
            this.dispose(ai);
        }
        this.byName.clear();
        this.byUuid.clear();
        this.pendingSkin.clear();
    }

    /** 把当前活着的 AI 位置落到配置里（只在关服时调一次，不做定期写盘） */
    private void savePositions() {
        AiPlayerConfig cfg = AiPlayerConfig.get();
        boolean changed = false;
        String world = "";
        for (AiPlayer ai : this.byName.values()) {
            try {
                AiProfile profile = cfg.find(ai.getScoreboardName());
                if (profile == null) {
                    continue;
                }
                if (world.isEmpty()) {
                    MinecraftServer server = ai.serverLevel().getServer();
                    world = server == null ? "" : worldKey(server);
                }
                Vec3 pos = ai.position();
                profile.lastDimension = ((ServerLevel) ai.level()).dimension().identifier().toString();
                profile.lastX = pos.x;
                profile.lastY = pos.y;
                profile.lastZ = pos.z;
                profile.hasLastPos = true;
                profile.lastWorld = world;
                changed = true;
            } catch (Throwable ignored) {
                // 单只 AI 取位置失败不该影响关服
            }
        }
        if (changed) {
            cfg.save();
        }
    }

    /**
     * 存档标识：存档根目录绝对路径的短哈希。
     * <p>
     * 配置是全局的，必须能分辨"这条位置记录属于哪个存档"，否则新建存档会把旧存档的
     * AI 按旧坐标生成出来。取哈希而不是存路径，是为了配置文件干净、也不泄露玩家目录结构。
     */
    private static String worldKey(MinecraftServer server) {
        try {
            String path = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize().toString();
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 启动时把档案里标记了 {@code autoKeep} 的 AI 原地生成回来。
     * <p>
     * 以前退出存档 AI 就全没了：玩家数据一直在硬盘里，但没有任何代码去 spawn 它们。
     * 位置是上次关服时写进档案的；维度已不存在（例如卸载了某个模组维度）就退回主世界，
     * 坐标按该维度的建造高度夹一下，避免落在虚空里一直往下掉。
     */
    public int restoreAll(MinecraftServer server) {
        AiPlayerConfig cfg = AiPlayerConfig.get();
        this.restoreQueue.clear();
        if (cfg.profiles == null || cfg.profiles.isEmpty()) {
            return 0;
        }
        String world = worldKey(server);
        if (world.isEmpty()) {
            // 拿不到存档标识就整批跳过：宁可玩家手动生成，也不能把 AI 撒到错误的坐标上
            return 0;
        }
        this.restoreWorld = world;
        for (AiProfile profile : cfg.profiles) {
            if (profile == null || profile.name == null || profile.name.isBlank()) {
                continue;
            }
            if (!profile.autoKeep || !profile.hasLastPos || !world.equals(profile.lastWorld)) {
                continue;
            }
            if (get(profile.name) != null) {
                continue;
            }
            this.restoreQueue.add(profile);
        }
        if (!this.restoreQueue.isEmpty()) {
            AiPlayerMod.LOGGER.info("[aiplayer] 计划恢复 {} 个 AI 玩家（每 tick 一只，避免开服卡顿）",
                    this.restoreQueue.size());
        }
        return this.restoreQueue.size();
    }

    /** 每 tick 从恢复队列取一只生成：把开服那一次性的生成压力摊开 */
    private void restoreOne(MinecraftServer server) {
        if (this.restoreQueue.isEmpty()) {
            return;
        }
        // 换存档 / 退出重进：队列里的档案不属于当前世界，直接丢弃
        if (!worldKey(server).equals(this.restoreWorld)) {
            this.restoreQueue.clear();
            return;
        }
        AiProfile profile = this.restoreQueue.poll();
        if (profile == null || profile.name == null || profile.name.isBlank()) {
            return;
        }
        if (!profile.autoKeep || get(profile.name) != null) {
            return;
        }
        ServerLevel level = findLevel(server, profile.lastDimension);
        double y = Math.max(level.getMinY() + 2, Math.min(level.getMaxY() - 2, profile.lastY));
        Vec3 at = new Vec3(profile.lastX, y, profile.lastZ);
        if (this.spawn(level, profile.name, at, 0.0F, 0.0F, profile.giveKit) != null) {
            AiPlayerMod.LOGGER.info("[aiplayer] 已恢复 AI 玩家 {} @ {}", profile.name, at);
        }
    }

    /** 按维度 ID 找 ServerLevel；找不到（维度被移除）返回主世界 */
    private static ServerLevel findLevel(MinecraftServer server, String dimensionId) {
        ServerLevel fallback = server.overworld();
        if (dimensionId == null || dimensionId.isBlank()) {
            return fallback;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimensionId)) {
                return level;
            }
        }
        return fallback;
    }

    /**
     * 处理"等皮肤"的 AI：后台拉取一有结论就热更一次实体。
     * <p>
     * 这样生成 AI 的关键路径上完全不碰网络，既不会卡服务端主线程，
     * 又能让皮肤在拉到之后自动出现，不需要玩家再去点一次"生成"。
     */
    private void applyPendingSkins(MinecraftServer server) {
        if (this.pendingSkin.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<String, String>> it = this.pendingSkin.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> entry = it.next();
            if (!SkinFetcher.isResolved(entry.getValue())) {
                // 还在后台拉，下一 tick 再看
                continue;
            }
            it.remove();
            if (!this.byName.containsKey(entry.getKey())) {
                // AI 已经被移除了，不用再管
                continue;
            }
            if (SkinFetcher.cached(entry.getValue()) == null) {
                // 这个正版玩家不存在 / 拉取失败：保持默认皮肤，不反复重试
                continue;
            }
            applySkin(server, entry.getKey(), entry.getValue());
        }
    }
}