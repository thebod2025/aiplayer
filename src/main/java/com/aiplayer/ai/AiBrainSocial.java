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

/** 社交与本地指令：玩家发言解析、点名判断、招呼、建造指令解析。 */
class AiBrainSocial extends AiBrainTeam {
    AiBrainSocial(AiPlayer player, AiPlayerManager manager, AiProfile profile) {
        super(player, manager, profile);
    }

    /** 当前世界里所有 AI 的名字（含自己）：用来判断玩家在 @ 谁 */
    java.util.Set<String> aiNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        String self = this.player.getScoreboardName();
        if (self != null && !self.isBlank()) {
            names.add(self);
        }
        try {
            for (AiPlayer other : AiPlayerManager.get().all()) {
                if (other != null && !other.isRemoved()) {
                    names.add(other.getScoreboardName());
                }
            }
        } catch (Throwable ignored) {
            // 管理器还没初始化完 / 中途出错都不影响"至少知道自己叫什么"
        }
        return names;
    }

    /** 玩家这条消息有没有 @ 到这只 AI */
    boolean mentionsMe(String text) {
        String name = this.player.getScoreboardName();
        return name != null && !name.isBlank() && containsToken(text, "@" + name);
    }

    /** 玩家这条消息 @ 的是别的 AI（那就不该我抢答） */
    boolean mentionsOtherAi(String text) {
        String me = this.player.getScoreboardName();
        for (String name : aiNames()) {
            if (name == null || name.isBlank() || name.equalsIgnoreCase(me)) {
                continue;
            }
            if (containsToken(text, "@" + name)) {
                return true;
            }
        }
        return false;
    }

    /** 剥掉所有 "@名字" 前缀，只留真正要执行的内容（"@小爱 帮我砍树" → "帮我砍树"） */
    String stripMention(String text) {
        String out = text;
        for (String name : aiNames()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            out = replaceIgnoreCase(out, "@" + name, " ");
        }
        return out.trim().replaceAll("^[，,：:、\\s]+", "").trim();
    }

    /**
     * 这句话是不是"让 AI 建东西"（建房 / 搭墙 / 围个圈 / build a wall…）。
     * <p>
     * 只认最明确的说法，避免把"搭个桥"之类交给这条本地兜底（桥要走 bridge 字段，不是围墙）。
     */
    static boolean isBuildRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : BUILD_WORDS) {
            if (containsToken(lower, word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 大小写无关的“整词”包含判断。
     * <p>
     * 英文关键词要求两侧是非字母数字（否则 {@code "come"} 会命中 {@code welcome}、
     * {@code "stop"} 会命中 {@code stopwatch}，AI 就会莫名其妙地乱回应）；
     * 中文没有词边界概念，按子串匹配即可。
     */
    static boolean containsToken(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        String k = token.toLowerCase(Locale.ROOT);
        boolean ascii = true;
        for (int i = 0; i < k.length(); i++) {
            if (k.charAt(i) > 127) {
                ascii = false;
                break;
            }
        }
        int from = 0;
        while (true) {
            int i = t.indexOf(k, from);
            if (i < 0) {
                return false;
            }
            if (!ascii) {
                return true;
            }
            int end = i + k.length();
            // 边界只看 ASCII 字母数字：中文紧跟在名字后面（"@AI砍树"）也算边界
            boolean left = i == 0 || !isWordChar(t.charAt(i - 1));
            boolean right = end >= t.length() || !isWordChar(t.charAt(end));
            if (left && right) {
                return true;
            }
            from = i + 1;
        }
    }

    static boolean isWordChar(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }

    /** 忽略大小写地把 {@code target} 替换成 {@code replacement}（目标按字面量处理，不当正则） */
    static String replaceIgnoreCase(String text, String target, String replacement) {
        return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(target),
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(replacement);
    }

    /**
     * 本地指令兜底 —— LLM API 失败 / 限流 / 额度耗尽时也能响应玩家。
     * 返回 true 表示已经本地处理，onPlayerChat 里直接 return 不走 LLM。
     * 只处理高频指令：停下来 / 跟着我 / 过来 / 打。
     */
    boolean tryLocalCommand(String text, Player speaker) {
        String lower = text.toLowerCase(Locale.ROOT);

        // 停下来 / stop
        for (String word : new String[]{"停下来", "停下", "stop", "别打了", "别打", "别动", "wait"}) {
            if (containsToken(lower, word)) {
                queue(this::stop);
                return true;
            }
        }

        // 跟着我 / follow me
        for (String word : new String[]{"跟着我", "跟我走", "follow me", "follow", "跟着我走"}) {
            if (containsToken(lower, word)) {
                String name = speaker == null ? null : speaker.getName().getString();
                queue(() -> setFollow(name));
                return true;
            }
        }

        // 过来 / come here —— 走到说话者身边
        for (String word : new String[]{"过来", "来这里", "come here", "come", "到我这", "过来一下"}) {
            if (containsToken(lower, word)) {
                if (speaker != null) {
                    queue(() -> walkTo(speaker.blockPosition()));
                }
                return true;
            }
        }

        // 攻击 / 打 —— 打最近的敌人
        for (String word : new String[]{"打那个", "去打", "攻击那个", "attack that", "kill that", "打怪物"}) {
            if (containsToken(lower, word)) {
                queue(() -> {
                    Entity enemy = findEnemy(16.0D);
                    if (enemy != null) {
                        this.attackTarget = enemy;
                        this.combat.setTarget(enemy);
                    }
                });
                return true;
            }
        }

        // PvP 开关：主人说"允许 PvP"就开，"关掉 PvP"就关
        if (containsToken(lower, "允许 pvp") || containsToken(lower, "可以 pvp")
                || containsToken(lower, "开 pvp") || containsToken(lower, "turn pvp on")) {
            queue(() -> { this.allowPlayerPvp = true; });
            return true;
        }
        if (containsToken(lower, "关掉 pvp") || containsToken(lower, "停止 pvp")
                || containsToken(lower, "关 pvp") || containsToken(lower, "turn pvp off")) {
            queue(() -> {
                this.allowPlayerPvp = false;
                if (this.attackTarget instanceof Player) {
                    this.attackTarget = null;
                }
            });
            return true;
        }

        // 打末影龙 / 去末地 —— 把自己和同队一起拉进末影龙战
        for (String word : new String[]{"打末影龙", "打龙", "打boss", "打 boss", "去末地", "进末地",
                "fight the dragon", "go to the end", "kill the dragon"}) {
            if (containsToken(lower, word)) {
                queue(this::startBossFight);
                return true;
            }
        }

        // 砍树 / 挖矿 / 探索 / 给东西 —— 玩家明确下令就本地确定性执行，不等模型。
        // 之前这些只在 LLM 不可用时才跑，模型经常只回一句"收到"却不给动作字段，表现就是"AI 不听话"。
        return localIntent(text, speaker);
    }

    /**
     * 本地意图兜底：砍树 / 挖矿 / 探索 / 给东西这类高频说法，用固定动作直接响应。
     * <p>
     * 现在它也被 {@link #tryLocalCommand} 调用（玩家明确下令就本地确定性执行、不等模型），
     * 因此返回 true/false 表示"是否已经处理"。
     */
    boolean localIntent(String heard, Player speaker) {
        String lower = heard.toLowerCase(Locale.ROOT);
        IntentMatcher.Intent intent = IntentMatcher.match(heard);
        if (intent == null) {
            return false;
        }
        switch (intent) {
            case STOP -> {
                queue(this::stop);
                return true;
            }
            case FOLLOW -> {
                String name = speaker == null ? null : speaker.getName().getString();
                queue(() -> setFollow(name));
                return true;
            }
            case COME -> {
                if (speaker != null) {
                    queue(() -> walkTo(speaker.blockPosition()));
                }
                return true;
            }
            case ATTACK -> {
                queue(() -> {
                    Entity enemy = findEnemy(16.0D);
                    if (enemy != null) {
                        this.attackTarget = enemy;
                        this.combat.setTarget(enemy);
                    }
                });
                return true;
            }
            case PVP_ON -> {
                queue(() -> this.allowPlayerPvp = true);
                return true;
            }
            case PVP_OFF -> {
                queue(() -> {
                    this.allowPlayerPvp = false;
                    if (this.attackTarget instanceof Player) {
                        this.attackTarget = null;
                    }
                });
                return true;
            }
            case BOSS -> {
                queue(this::startBossFight);
                return true;
            }
            case MINE_TUNNEL -> {
                ensureFeature("mine", () -> this.profile.mine = true);
                suspendFollowForJob();
                this.mining.startBranch();
                this.chat.sayReply(Component.translatable("aiplayer.say.tunnelStart"));
                return true;
            }
            case MINE_ORE -> {
                ensureFeature("mine", () -> this.profile.mine = true);
                suspendFollowForJob();
                this.mining.startOre();
                this.chat.sayReply(Component.translatable("aiplayer.say.mineStart"));
                return true;
            }
            case CHOP -> {
                ensureFeature("mine", () -> this.profile.mine = true);
                startChopNow();
                this.chat.sayReply(Component.translatable("aiplayer.say.chopStart"));
                return true;
            }
            case EXPLORE -> {
                ensureFeature("explore", () -> this.profile.explore = true);
                suspendFollowForJob();
                this.explore.start();
                this.chat.sayReply(Component.translatable("aiplayer.say.exploreStart"));
                return true;
            }
            case HOME -> {
                BlockPos home = this.explore.home();
                if (home == null) {
                    this.chat.sayReply(Component.translatable("aiplayer.say.homeUnknown"));
                    return true;
                }
                queue(() -> walkTo(home));
                return true;
            }
            case GIVE -> {
                return giveSomething(speaker);
            }
            case SLEEP -> {
                return sleepCommand();
            }
            default -> {
                return false;
            }
        }
    }

    /** "给我东西"：优先丢食物，其次丢多于一个的非武器物品 */
    private boolean giveSomething(Player speaker) {
        Inventory inv = this.player.getInventory();
        ItemStack best = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            if (FoodManager.isFood(s)) {
                best = s;
                break;
            }
            if (best == null && s.getCount() >= 2 && !isWeapon(s)) {
                best = s;
            }
        }
        if (best != null) {
            dropToward(speaker != null ? speaker : this.player, best);
            this.chat.sayReply(Component.translatable("aiplayer.say.giveOk"));
        } else {
            this.chat.sayReply(Component.translatable("aiplayer.say.giveEmpty"));
        }
        return true;
    }

    /** "去睡觉"：停掉手头的活，走到最近的床躺下；白天 / 没床会直接说明 */
    boolean sleepCommand() {
        if (!this.sleep.canSleepNow()) {
            this.chat.sayReply(Component.translatable("aiplayer.say.sleepDay"));
            return true;
        }
        if (!this.sleep.hasBedAvailable()) {
            this.chat.sayReply(Component.translatable("aiplayer.say.sleepNoBed"));
            return true;
        }
        queue(() -> {
            this.walkTarget = null;
            this.mineTarget = null;
            this.placeTarget = null;
            this.buildQueue.clear();
            this.cookTarget = null;
            this.mining.reset();
            this.chop.reset();
            this.explore.stop();
            this.boss.reset();
            this.path.clear();
            this.sleep.request();
        });
        this.chat.sayReply(Component.translatable("aiplayer.say.sleepStart"));
        return true;
    }

    /**
     * 玩家明确下令时，功能开关关着也照样执行（"听指令优先"）。
     * <p>
     * 之前这里只回一句"我这边把挖矿关了，先开一下再说"就结束 —— 在玩家看来就是"AI 不听话"。
     * 现在改为自动打开开关并真的去做，动作照旧落到 {@code if (!this.profile.xxx)} 的下游逻辑。
     */
    void ensureFeature(String feature, Runnable open) {
        if (this.profile == null) {
            return;
        }
        boolean already;
        switch (feature) {
            case "mine" -> already = this.profile.mine;
            case "explore" -> already = this.profile.explore;
            case "place" -> already = this.profile.place;
            default -> already = true;
        }
        if (!already) {
            open.run();
        }
    }

    // 本地模板建房已删除：建房统一交给 LLM，用 fill 指令按现场地形设计建筑。

    /** 任一句出现即算命中（关键词用边界匹配，避免"建"和"溅"这种子串误判） */
    static boolean anyToken(String text, String... words) {
        for (String word : words) {
            if (containsToken(text, word)) {
                return true;
            }
        }
        return false;
    }

    /** 这句话是不是冲着这名 AI 说的（点名 / @名字 / 明显在使唤它） */
    boolean isAddressed(String text) {
        String name = this.player.getScoreboardName();
        // 以前这里还有 lower.startsWith("ai")：任何以 ai 开头的词都会被当成点名，
        // "aim 别乱动""air 好多" 都能把 AI 叫起来，早就删掉了。
        if (name != null && !name.isBlank() && containsToken(text, name)) {
            return true;
        }
        if (name != null && !name.isBlank() && containsToken(text, "@" + name)) {
            return true;
        }
        for (String keyword : REQUEST_KEYWORDS) {
            if (containsToken(text, keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 主动和走近的玩家打招呼。纯本地文本、不花任何 token，但玩家能明显感觉到“它在理我”。
     * 战斗 / 搭路 / 打水晶中不打扰，同一个玩家 4 分钟内只招呼一次。
     */
    void greetNearby() {
        AiProfile p = this.profile;
        if (p == null || !p.chat || !p.autoChat) {
            return;
        }
        if (this.chat.hasLlm()) {
            // 有 API 时打招呼交给模型（它会在玩家说话时自然回应），
            // 本地模板问候会把 AI 变成只会重复固定台词的复读机。
            return;
        }
        if (this.state == AiState.COMBAT || this.state == AiState.CRYSTAL || this.bridge.isActive()) {
            return;
        }
        if (this.player.tickCount % 20 != 0) {
            return;   // 每秒查一次就够
        }
        long now = System.currentTimeMillis();
        for (Player other : this.player.level().getEntitiesOfClass(Player.class,
                this.player.getBoundingBox().inflate(GREET_RANGE))) {
            if (other == this.player || other.isSpectator()) {
                continue;
            }
            // 只在第一次见面时打招呼：见过的名字记进 profile（持久化），重启也还认得
            String otherName = other.getName().getString();
            if (p.metPlayers.contains(otherName)) {
                continue;
            }
            Long last = this.greetedAt.get(other.getUUID());
            if (last != null && now - last < GREET_COOLDOWN_MS) {
                continue;
            }
            if (this.greetedAt.size() > 64) {
                // 只是招呼记录，涨到上限直接清空，避免无上限增长
                this.greetedAt.clear();
            }
            // 只有真的说出口才算招呼过，否则会被 1 秒间隔静默吃掉、玩家再也等不到招呼
            if (this.chat.sayReply(Component.translatable("aiplayer.say.greet", otherName))) {
                this.greetedAt.put(other.getUUID(), now);
                // 名单会写进配置，长期玩下去只增不减：到上限就丢掉最早的记录
                if (p.metPlayers.size() >= 128) {
                    p.metPlayers.remove(0);
                }
                p.metPlayers.add(otherName);
                com.aiplayer.config.AiPlayerConfig.get().save();
            }
            return;   // 一次只招呼一个，免得一群人围上来时刷屏
        }
    }


    /**
     * 模型忙 / 退避中 / 没配 Key 时不干等：把还没送进模型的玩家消息用本地规则消化掉。
     * {@code dropInflight} 表示还有一个请求在路上——那种情况下这轮就当本地处理完，
     * 迟到的模型回复作废（llmStale），免得本地动作和模型动作前后打架。
     */
    void consumePendingLocally(boolean dropInflight) {
        String speaker = this.pendingSpeaker;
        String heard = this.pendingChatText;
        this.pendingSpeaker = null;
        this.pendingChatText = null;
        this.pendingLocale = null;
        if (dropInflight) {
            this.llmStale = true;
        }
        if (speaker == null || heard == null) {
            return;
        }
        final String sp = speaker;
        final String hd = heard;
        queue(() -> {
            Player pl = findByName(sp) instanceof Player p ? p : null;
            // tryLocalCommand 末尾已经把 localIntent 走完了（localIntent 返回"是否已处理"）。
            // 以前这里又调了一次 localIntent，结果一句话被处理两遍 —— 既double烧 token，
            // 又可能让 AI 对着同一句话回两次。现在只调一次。
            tryLocalCommand(hd, pl);
            // 本地规则一条都没命中、模型又没接上话：不要再拿"嗯我在听"这种空话敷衍 ——
            // 直接说明没听懂并复读玩家的话，顺带提示怎么下指令，这样至少言之有物。
            if (pl != null) {
                this.chat.sayReply(Component.translatable("aiplayer.say.confused", hd));
            }
        });
    }

}