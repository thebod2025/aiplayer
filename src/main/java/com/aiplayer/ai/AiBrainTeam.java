package com.aiplayer.ai;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.ai.bridge.BridgeController;
import com.aiplayer.ai.bridge.BridgeMode;
import com.aiplayer.ai.chat.ChatController;
import com.aiplayer.ai.combat.CombatController;
import com.aiplayer.ai.combat.CrystalPvpController;
import com.aiplayer.ai.combat.BlockTacticsController;
import com.aiplayer.ai.combat.AnchorPvpController;
import com.aiplayer.ai.combat.WindChargeMaceController;
import com.aiplayer.ai.combat.ElytraSpearController;
import com.aiplayer.ai.combat.RangedController;
import com.aiplayer.ai.gather.ChopController;
import com.aiplayer.ai.gather.MiningController;
import com.aiplayer.ai.inv.EquipmentManager;
import com.aiplayer.ai.interact.InteractController;
import com.aiplayer.ai.path.PathExecutor;
import com.aiplayer.ai.survival.EscapeController;
import com.aiplayer.ai.survival.ExploreController;
import com.aiplayer.ai.survival.FoodManager;
import com.aiplayer.api.LlmClient;
import com.aiplayer.compat.CompatManager;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.entity.AiPlayerManager;
import com.aiplayer.util.ItemIdUtil;
import com.aiplayer.util.PlaceUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 队伍相关决策：后勤分享 / 队形 / 队内闲聊。 */
class AiBrainTeam extends AiBrainCombat {
    AiBrainTeam(AiPlayer player, AiPlayerManager manager, AiProfile profile) {
        super(player, manager, profile);
    }

    /** 找一个值得送的队友：冷却已过且我正好有他能用的东西（16 格内取最近） */
    AiPlayer neediestTeammate(String team) {
        AiPlayer best = null;
        double bestDist = 256.0D;   // 16 格
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate == this.player || !mate.isAlive()) {
                continue;
            }
            if (!team.equals(mate.profile().team)) {
                continue;
            }
            double d2 = this.player.distanceToSqr(mate);
            if (d2 > bestDist) {
                continue;
            }
            Long last = this.lastShareAt.get(mate.getUUID());
            if (last != null && this.player.tickCount - last < SHARE_COOLDOWN_TICKS) {
                continue;
            }
            if (shareGiftFor(mate) == null) {
                continue;
            }
            bestDist = d2;
            best = mate;
        }
        return best;
    }

    /**
     * 我能给这个队友什么（只探测不拿走）：他饿了给我多余的食物，他空手给我多余的武器。
     * 返回背包里的来源堆，交付时再从中拆出一份。
     */
    ItemStack shareGiftFor(AiPlayer mate) {
        if (mate.getFoodData().getFoodLevel() < 17) {
            ItemStack spare = spareFood();
            if (spare != null) {
                return spare;
            }
        }
        if (!isWeapon(mate.getMainHandItem())) {
            ItemStack spare = spareWeapon();
            if (spare != null) {
                return spare;
            }
        }
        // 盔甲：他某个部位空着 / 比我身上那件差，而我还穿着更好的，就匀一件给他
        ItemStack armor = spareArmorFor(mate);
        if (armor != null) {
            return armor;
        }
        return null;
    }

    /** 我能匀给队友的盔甲：必须在对应槽位、比他现在那件好、又不比我身上那件好（自己不亏） */
    ItemStack spareArmorFor(AiPlayer mate) {
        Inventory inv = this.player.getInventory();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            int mineTier = armorTier(this.player.getItemBySlot(slot));
            if (mineTier <= 0) {
                continue;   // 我自己这个部位都没穿，不能脱给他
            }
            int mateTier = armorTier(mate.getItemBySlot(slot));
            if (mateTier >= mineTier) {
                continue;   // 他这件不比我差，没必要换
            }
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || armorSlotOf(s) != slot) {
                    continue;
                }
                int t = armorTier(s);
                if (t > mateTier && t <= mineTier) {
                    return s;   // 送出去我不亏，他还能升级
                }
            }
        }
        return null;
    }

    /** 这件物品该穿哪个槽位（不是盔甲返回 null） */
    static EquipmentSlot armorSlotOf(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (path.equals("turtle_helmet") || path.endsWith("_helmet")) {
            return EquipmentSlot.HEAD;
        }
        if (path.endsWith("_chestplate")) {
            return EquipmentSlot.CHEST;
        }
        if (path.endsWith("_leggings")) {
            return EquipmentSlot.LEGS;
        }
        if (path.endsWith("_boots")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    /** 盔甲档位：越大越硬（netherite 6 … leather 1）；不是盔甲返回 0 */
    static int armorTier(ItemStack s) {
        EquipmentSlot slot = armorSlotOf(s);
        if (slot == null) {
            return 0;
        }
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (path.startsWith("netherite")) {
            return 6;
        }
        if (path.startsWith("diamond")) {
            return 5;
        }
        if (path.startsWith("iron")) {
            return 4;
        }
        if (path.startsWith("chainmail")) {
            return 3;
        }
        if (path.startsWith("golden")) {
            return 2;
        }
        return 1;   // 皮革 / 海龟壳 / 其它模组盔甲，统一算最低档
    }

    /** 一组能匀出去的食物（自己总口粮 >= {@value #SPARE_FOOD_MIN} 才算有余粮） */
    ItemStack spareFood() {
        Inventory inv = this.player.getInventory();
        int total = 0;
        ItemStack found = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (FoodManager.isFood(s)) {
                total += s.getCount();
                if (found == null) {
                    found = s;
                }
            }
        }
        return total >= SPARE_FOOD_MIN ? found : null;
    }

    /** 一把能匀出去的武器（我至少要有两把才算多余，自己不能赤手空拳） */
    ItemStack spareWeapon() {
        Inventory inv = this.player.getInventory();
        int count = 0;
        ItemStack found = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isWeapon(s)) {
                count++;
                if (found == null) {
                    found = s;
                }
            }
        }
        return count >= 2 ? found : null;
    }

    /** 武器判定：按物品类名匹配（和 EquipmentManager 同一套关键词，26.2 没有 SwordItem 类） */
    static boolean isWeapon(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String name = s.getItem().getClass().getSimpleName();
        if (name.contains("Pickaxe")) {
            return false;   // "Pickaxe" 也含 "Axe"，但镐不是给队友防身的
        }
        return name.contains("Sword") || name.contains("Axe") || name.contains("Trident")
                || name.contains("Mace") || name.contains("Bow") || name.contains("Spear")
                || name.contains("Katana") || name.contains("Dagger") || name.contains("Scythe")
                || name.contains("Halberd");
    }

    /** 当面把东西丢给对方：轻抛一份过去，像真人按 Q */
    void dropToward(Entity target, ItemStack source) {
        int n = Math.min(source.getCount(), FoodManager.isFood(source) ? 8 : 1);
        ItemStack gift = source.split(n);
        if (gift.isEmpty()) {
            return;
        }
        Vec3 from = this.player.position().add(0.0D, 1.2D, 0.0D);
        Vec3 to = target.position().add(0.0D, 0.8D, 0.0D);
        Vec3 vel = to.subtract(from);
        if (vel.lengthSqr() < 0.001D) {
            vel = new Vec3(0.0D, 0.1D, 0.0D);
        }
        vel = vel.normalize().scale(0.3D).add(0.0D, 0.1D, 0.0D);
        ItemEntity drop = new ItemEntity(this.player.level(), from.x, from.y, from.z, gift);
        drop.setDeltaMovement(vel);
        drop.setPickUpDelay(10);
        this.player.level().addFreshEntity(drop);
    }

    /** 队伍名单缓存间隔（tick）：每 tick 建 List + 排序在 AI 多时是 O(n² log n) */
    private static final int SQUAD_CACHE_INTERVAL = 10;
    private List<AiPlayer> squadCache;
    private String squadCacheTeam;
    private int squadCacheTicks;

    /**
     * 队形跟随：没任务没敌人时，同队按名字排序 0 号是队长，
     * 其他人守在队长身边按序号均分的环形位（2.5 格半径）。
     */
    boolean teamFormationTick(AiInput in) {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank()) {
            return false;
        }
        List<AiPlayer> squad = cachedSquad(mine);
        if (squad.size() < 2) {
            return false;
        }
        int myIdx = squad.indexOf(this.player);
        if (myIdx <= 0) {
            return false;   // 我是队长：站桩定坐标，队员来围
        }
        AiPlayer leader = squad.get(0);
        if (!leader.isAlive()) {
            return false;   // 缓存里的队长刚死，等下一次重算名单
        }
        // 队长带队：队长正在跟随某个玩家（主人）时，全队直接跟到那个玩家身边，
        // 而不是原地守着队长——否则队长一走远就超出跟随距离，队伍当场散伙。
        String leaderFollow = leader == this.player ? null : leader.brain().followTargetName();
        Player anchorPlayer = leaderFollow == null ? null
                : this.player.serverLevel().getServer().getPlayerList().getPlayerByName(leaderFollow);
        boolean led = anchorPlayer != null && anchorPlayer.level() == this.player.level();
        // 集结点：队长带队时用被跟随的玩家，否则用队长本人
        double ax = led ? anchorPlayer.getX() : leader.getX();
        double ay = led ? anchorPlayer.getY() : leader.getY();
        double az = led ? anchorPlayer.getZ() : leader.getZ();
        double anchorDist2 = led ? this.player.distanceToSqr(ax, ay, az) : this.player.distanceToSqr(leader);
        if (anchorDist2 > 2304.0D) {
            return false;   // 集结点在 48 格外（可能在别的区域），各干各的
        }
        // 12 格内就不再逐格纠正站位：队员可以就近干活，只要不脱离队伍。
        // 否则队形每 tick 抢回控制权，和生计任务来回拉锯，队员看起来原地抽搐。
        if (this.player.distanceToSqr(leader) < 144.0D && anchorDist2 > 4.0D) {
            return false;
        }
        double angle = (Math.PI * 2.0D * myIdx) / squad.size();
        // 队长带队时用更大的半径，别把被跟随的玩家挤在中间
        double radius = led ? 4.0D : 2.5D;
        double sx = ax + Math.cos(angle) * radius;
        double sz = az + Math.sin(angle) * radius;
        if (this.player.distanceToSqr(sx, this.player.getY(), sz) < 4.0D) {
            return false;   // 已就位
        }
        this.path.setGoal(BlockPos.containing(sx, ay, sz));
        if (!this.path.tick()) {
            setState(AiState.FOLLOWING);
        }
        return true;
    }

    /**
     * 同队名单（按名字排序，0 号是队长）。
     * 名单每 {@value #SQUAD_CACHE_INTERVAL} tick 重算一次：队形不需要 20Hz 精度，
     * 多 AI 时能省掉每 tick 的建表与排序。
     */
    private List<AiPlayer> cachedSquad(String team) {
        if (this.squadCache != null && this.squadCacheTicks > 0 && team.equals(this.squadCacheTeam)) {
            this.squadCacheTicks--;
            return this.squadCache;
        }
        List<AiPlayer> list = new ArrayList<>();
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate.isAlive() && team.equals(mate.profile().team)) {
                list.add(mate);
            }
        }
        list.sort((a, b) -> a.getName().getString().compareTo(b.getName().getString()));
        this.squadCache = list;
        this.squadCacheTeam = team;
        this.squadCacheTicks = SQUAD_CACHE_INTERVAL;
        return list;
    }

    /**
     * 同队闲聊：附近有队友时偶尔互相搭两句话，让一队人看起来像在配合，而不是各打各的。
     * <p>
     * 纯本地文本、不花 token；只有开了 {@code chat + autoChat} 才说，自己起话头 45~75 秒一句。
     * 队友 {@value #TEAM_CHAT_REPLY_WINDOW_MS} 毫秒内刚说过话就优先接一句（每人 20 秒最多接一次），
     * 否则自己找个话头。战斗 / 打水晶 / 搭路时不插嘴。
     */
    void teamChatTick() {
        AiProfile p = this.profile;
        if (p == null || p.team == null || p.team.isBlank() || !p.chat || !p.autoChat) {
            return;
        }
        if (this.player.tickCount % 20 != 0 || this.player.tickCount < this.teamChatNextTick) {
            return;   // 每秒看一次，且不早于冷却
        }
        if (this.state == AiState.COMBAT || this.state == AiState.CRYSTAL
                || this.state == AiState.BRIDGING || this.bridge.isActive()) {
            return;   // 打架 / 搭路时不闲聊
        }
        AiPlayer mate = nearestTeamMate();
        if (mate == null) {
            this.teamChatNextTick = this.player.tickCount + 200;   // 附近没队友，过会儿再看
            return;
        }
        String mateName = mate.getScoreboardName();
        long now = System.currentTimeMillis();
        // 1) 队友刚说了话 → 接一句
        AiBrain mateBrain = mate.brain();
        if (mateBrain != null && mateBrain.teamChatLastLine != null
                && now - mateBrain.teamChatLastAtMs < TEAM_CHAT_REPLY_WINDOW_MS
                && now - this.teamChatRepliedAtMs > TEAM_CHAT_REPLY_GAP_MS) {
            String key = teamReplyLine();
            if (this.chat.say(Component.translatable(key, mateName))) {
                this.teamChatRepliedAtMs = now;
                this.teamChatLastLine = key;
                this.teamChatLastAtMs = now;
                this.teamChatNextTick = this.player.tickCount + TEAM_CHAT_MIN_GAP_TICKS;
            }
            return;
        }
        // 2) 自己起个话头
        String key = teamOpenLine();
        if (this.chat.say(Component.translatable(key, mateName))) {
            this.teamChatLastLine = key;
            this.teamChatLastAtMs = now;
            this.teamChatNextTick = this.player.tickCount
                    + TEAM_CHAT_MIN_GAP_TICKS + RNG.nextInt(TEAM_CHAT_JITTER_TICKS);
        }
    }

    /** {@value #TEAM_CHAT_RANGE} 格内最近的同队且存活队友，没有返回 null */
    AiPlayer nearestTeamMate() {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank()) {
            return null;
        }
        AiPlayer best = null;
        double bestDist = TEAM_CHAT_RANGE * TEAM_CHAT_RANGE;
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate == this.player || !mate.isAlive() || mate.profile() == null
                    || !mine.equals(mate.profile().team)) {
                continue;
            }
            double d = this.player.distanceToSqr(mate);
            if (d <= bestDist) {
                bestDist = d;
                best = mate;
            }
        }
        return best;
    }

    /** 起个话头的翻译键（{@code %s} 是队友名字，渲染时传入） */
    static String teamOpenLine() {
        String[] keys = {
                "aiplayer.say.team.open.1",
                "aiplayer.say.team.open.2",
                "aiplayer.say.team.open.3",
                "aiplayer.say.team.open.4",
                "aiplayer.say.team.open.5",
                "aiplayer.say.team.open.6",
                "aiplayer.say.team.open.7",
                "aiplayer.say.team.open.8",
                "aiplayer.say.team.open.9",
                "aiplayer.say.team.open.10"
        };
        return keys[RNG.nextInt(keys.length)];
    }

    /** 接队友一句话的翻译键（{@code %s} 是队友名字） */
    static String teamReplyLine() {
        String[] keys = {
                "aiplayer.say.team.reply.1",
                "aiplayer.say.team.reply.2",
                "aiplayer.say.team.reply.3",
                "aiplayer.say.team.reply.4",
                "aiplayer.say.team.reply.5"
        };
        return keys[RNG.nextInt(keys.length)];
    }

    /**
     * 组队后勤：16 格内有队友饿了缺粮、或空手没武器，而我有多余的 —— 走到跟前当面丢过去。
     * 掉落物队友自己会捡（和玩家之间丢东西一个玩法）。
     */
    boolean teamShareTick(AiInput in) {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank() || this.player.tickCount % 20 != 0) {
            return false;   // 低频检查，省性能
        }
        AiPlayer mate = neediestTeammate(mine);
        if (mate == null) {
            return false;
        }
        if (this.player.distanceToSqr(mate) > 9.0D) {
            this.path.setGoal(mate.blockPosition());
            if (!this.path.tick()) {
                setState(AiState.MOVING);
            }
            return true;
        }
        // 到跟前了：把东西丢给队友（真人按 Q 的动作）
        ItemStack gift = shareGiftFor(mate);
        if (gift != null) {
            dropToward(mate, gift);
            this.lastShareAt.put(mate.getUUID(), (long) this.player.tickCount);
        }
        return false;
    }

}