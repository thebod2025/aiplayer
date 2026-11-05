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
import java.util.Comparator;
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

/** LLM 链路：提示词、请求、回复解析与动作执行、手动操控。 */
class AiBrainLlm extends AiBrainSocial {
    AiBrainLlm(AiPlayer player, AiPlayerManager manager, AiProfile profile) {
        super(player, manager, profile);
    }

    void maybeAskLlm() {
        AiPlayerConfig config = AiPlayerConfig.get();
        if (!config.llmEnabled) {
            this.llmPhase = LlmPhase.IDLE;
            return;
        }
        boolean noKey = !config.hasApiKey() && (this.profile.apiKey == null || this.profile.apiKey.isBlank());
        long now = System.currentTimeMillis();
        // 间隔下限兜底：手改配置写成 0 也不会把账户的 RPM 打爆（HTTP 429）
        long interval = Math.max(8000L, config.llmIntervalMs);
        // 只有"根本打不出请求"（没配 Key）和"已经有一个请求在路上"才把玩家的消息就地消化掉。
        // 单纯间隔 / 退避还没到，必须让消息**留在 pending 里**等下一轮 ——
        // 以前这里不管是哪种情况都调 consumePendingLocally，而它会把 pendingChatText 清空：
        // 自主行动每 12 秒（llmIntervalMs）就会打一次请求刷新 lastLlmAt，玩家的消息几乎每句
        // 都落在"还没到间隔"的窗口里被本地兜底吃掉，模型压根收不到 ——
        // 表现就是"玩家说话 AI 不回 / 只会回一句废话"。
        if (noKey) {
            this.llmPhase = LlmPhase.NO_KEY;
            consumePendingLocally(false);
            return;
        }
        if (this.llmBusy) {
            this.llmPhase = LlmPhase.REQUESTING;
            return;   // 请求在路上：消息留着，等它回来了下一轮再一起送进模型
        }
        // 有玩家在等回复时别按自主间隔硬等 12 秒：压到 3 秒，让 AI 能接上话
        // （只有 pendingChatText 非空才这样，纯自主行动仍然走完整的 llmIntervalMs）
        long wait = this.pendingChatText != null
                ? Math.min(interval, PENDING_REPLY_WAIT_MS) : interval;
        if (now - this.lastLlmAt < wait + this.llmBackoffMs) {
            // 显式区分「还在等间隔」和「在退避」：出问题时 /aiplayer task 能直接看出是哪一种
            this.llmPhase = this.llmBackoffMs > 0 ? LlmPhase.BACKOFF : LlmPhase.WAITING;
            return;
        }
        this.lastLlmAt = now;
        this.llmBusy = true;
        this.llmPhase = LlmPhase.REQUESTING;
        // 取走待回复的玩家消息（取走即清空，绝不重复带入下一轮，省 token）
        String speaker = this.pendingSpeaker;
        String heard = this.pendingChatText;
        this.pendingSpeaker = null;
        this.pendingChatText = null;
        final boolean replying = speaker != null && heard != null;
        this.replySpeaker = replying ? speaker : null;
        this.replyLocale = replying ? this.pendingLocale : null;
        this.pendingLocale = null;
        java.util.concurrent.CompletableFuture<String> future;
        try {
            // 回复玩家走聊天 API（双 API 开启时），自主行动走主 API；
            // 提示词与对话记忆是同一份，所以两个模型看到的世界完全同步。
            future = LlmClient.ask(this.profile, systemPrompt(targetLocale()), statePrompt(speaker, heard), replying);
        } catch (Throwable t) {
            // LlmClient.ask 理论上不会同步抛异常，但 HttpRequest.header 对非法 HTTP 头
            // （比如从网页复制 API Key 时带进来的换行 / 控制字符）是会抛 IllegalArgumentException 的。
            // 一旦在这里抛出去，下面 whenComplete 就永远不会执行 —— llmBusy 永远停在 true，
            // 之后每一轮都走 consumePendingLocally，表现就是"换了模型也还是只会说兜底废话"。
            // 所以这里必须自己兜住，保证 llmBusy 一定会被复位。
            this.llmBusy = false;
            onLlmFailure(t);
            return;
        }
        if (replying) {
            String usedModel = config.dualApiFor(this.profile)
                    ? config.chatModelFor(this.profile) : config.modelFor(this.profile);
            AiPlayerMod.LOGGER.info("[aiplayer] {} 回话请求 -> 模型 {}{} | 玩家说：{}",
                    this.profile.name, usedModel,
                    config.dualApiFor(this.profile) ? "（聊天 API）" : "",
                    heard.length() > 80 ? heard.substring(0, 80) : heard);
        }
        future.whenComplete((reply, error) -> {
            this.llmBusy = false;
            boolean stale = this.llmStale;
            this.llmStale = false;
            if (error != null) {
                onLlmFailure(error);
                return;
            }
            this.llmFailures = 0;
            this.llmBackoffMs = 0L;
            if (stale) {
                return;   // 这轮已经本地处理过了，丢掉迟到的模型动作
            }
            if (reply != null && !reply.isBlank()) {
                queue(() -> applyLlmReply(reply, replying));
            }
        });
    }

    /** LLM 调用失败：分类提示（tellraw @a）+ 指数退避 */
    void onLlmFailure(Throwable raw) {
        Throwable error = raw;
        if (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        this.llmFailures++;
        this.llmBackoffMs = Math.min(60_000L, 3_000L * this.llmFailures);

        String name = this.profile.name;
        if (error instanceof LlmClient.LlmException le) {
            String key;
            Object[] args;
            if (le.noBalance()) {
                key = "aiplayer.error.noBalance";
                args = new Object[0];
            } else if (le.unauthorized()) {
                key = "aiplayer.error.key";
                args = new Object[]{String.valueOf(le.status)};
            } else if (le.notFound()) {
                key = "aiplayer.error.notFound";
                args = new Object[0];
            } else if (le.rateLimited()) {
                key = "aiplayer.error.rateLimited";
                args = new Object[0];
            } else if (le.status >= 500) {
                key = "aiplayer.error.server";
                args = new Object[]{String.valueOf(le.status)};
            } else {
                key = "aiplayer.error.rejected";
                args = new Object[]{String.valueOf(le.status)};
            }
            // 401/404/402 属于“配置没补就一直错”，只提示第一次，避免刷屏
            boolean configError = le.unauthorized() || le.notFound() || le.noBalance();
            if (configError ? this.llmFailures == 1 : this.llmFailures <= 3) {
                // 回调在 HTTP 线程上，执行指令要回到服务器主线程
                String k = key;
                Object[] a = args;
                queue(() -> this.chat.reportKey(k, "red", a));
            }
            return;
        }
        if (this.llmFailures <= 3) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            queue(() -> this.chat.reportKey("aiplayer.error.generic", "red", name, message));
        }
    }

    /** 持续按住使用物品：吃东西 / 喝药要按满右键时间才生效，点一次会被立刻中断 */
    void applyItemHold(AiInput in) {
        if (this.useHoldTicks <= 0 || this.useHoldSlot < 0) {
            return;
        }
        // 上一 tick 已经按过一次，但物品并没有进入"使用中"状态 —— 说明它是即用即生效的
        // （珍珠 / 雪球 / 水桶 / 放方块）。必须立刻松手：再按下去会每 tick 用一次，
        // 把一叠珍珠扔光、一口气放一整片方块。
        if (this.useHoldApplied > 0 && !this.player.isUsingItem()) {
            stopItemHold();
            return;
        }
        // 槽位被更高优先级（战斗 / 逃生）抢走时立刻放弃，不要跟原版逻辑抢
        if (!this.player.selectSlot("llm", com.aiplayer.util.InventoryLock.PRIO_TASK, this.useHoldSlot)) {
            stopItemHold();
            return;
        }
        in.selectedSlot = this.useHoldSlot;
        in.useItemHold = true;
        this.useHoldTicks--;
        this.useHoldApplied++;
        if (this.useHoldTicks <= 0) {
            // 兜底：按住太久（例如蓄力型物品）强制松手，避免一直保持开弓状态
            stopItemHold();
        }
    }

    void stopItemHold() {
        this.useHoldTicks = 0;
        this.useHoldSlot = -1;
        this.useHoldApplied = 0;
        // 弓 / 三叉戟这类"蓄力型"物品要显式松开，否则会一直僵在开弓状态
        if (this.player.isUsingItem()) {
            this.player.releaseUsingItemNow();
        }
    }

    /**
     * 把物品挪到手上（不在快捷栏就和当前槽位互换），返回所在槽位；没有则 -1。
     * <p>
     * <b>调用前必须已经持有 {@code InventoryLock}</b>：这里的"互换"换掉的是
     * {@code getSelectedSlot()}，也就是别的控制器（搭路 / 战斗 / 水晶 PvP）可能正在使用的槽位。
     * 不持锁就换，会把它们手上的方块 / 武器 / 水桶换进背包，表现为搭路突然中断、
     * 攻击打空、水晶放不出来。
     */
    int moveToHand(Item item) {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            if (inv.getItem(i).is(item)) {
                return i;
            }
        }
        if (!this.player.inventoryLock().heldBy("llm")
                && !this.player.inventoryLock().acquire("llm", com.aiplayer.util.InventoryLock.PRIO_TASK)) {
            // 锁被更高优先级（搭路 / 战斗 / 进食）占着：这次放弃，绝不抢槽位。
            // 锁空闲时自己拿一下 —— 这样背包里的物品也能被取出来用，不必依赖调用方先加锁。
            return -1;
        }
        for (int i = Inventory.SELECTION_SIZE; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) {
                int target = inv.getSelectedSlot();
                ItemStack old = inv.getItem(target);
                inv.setItem(target, inv.getItem(i));
                inv.setItem(i, old);
                return target;
            }
        }
        return -1;
    }

    /** LLM "use"：使用一次（吃 / 喝 / 投掷 / 放置） */
    boolean useItem(String spec) {
        Item item = ItemIdUtil.resolve(spec);
        if (item == null) {
            return false;
        }
        // 先拿锁再动背包：拿不到说明别的控制器（搭路 / 战斗 / 逃生）正在用这个槽位，
        // 这次"使用物品"直接放弃，绝不抢槽位。
        if (!this.player.inventoryLock().acquire("llm", com.aiplayer.util.InventoryLock.PRIO_TASK)) {
            return false;
        }
        int slot = moveToHand(item);
        if (slot < 0) {
            return false;
        }
        this.useHoldSlot = slot;
        this.useHoldTicks = USE_HOLD_MAX_TICKS;
        this.useHoldApplied = 0;
        return true;
    }

    /** LLM "equip"：拿到主手上（护甲由 EquipmentManager 自动穿，不需要在这里处理） */
    boolean holdItem(String spec) {
        Item item = ItemIdUtil.resolve(spec);
        if (item == null) {
            return false;
        }
        if (!this.player.inventoryLock().acquire("llm", com.aiplayer.util.InventoryLock.PRIO_TASK)) {
            return false;
        }
        int slot = moveToHand(item);
        return slot >= 0
                && this.player.selectSlot("llm", com.aiplayer.util.InventoryLock.PRIO_TASK, slot);
    }

    /** LLM "drop"：丢弃物品，count <= 0 表示整叠扔掉 */
    boolean dropItem(String spec, int count) {
        Item item = ItemIdUtil.resolve(spec);
        if (item == null) {
            return false;
        }
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(item)) {
                continue;
            }
            int n = count <= 0 ? stack.getCount() : Math.min(count, stack.getCount());
            ItemStack dropped = stack.copyWithCount(n);
            stack.shrink(n);
            if (stack.isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            this.player.drop(dropped, false, net.minecraft.util.Prediction.PREDICTED);
            return true;
        }
        return false;
    }

    String systemPrompt(String locale) {
        AiProfile p = this.profile;
        String custom = p.customPrompt == null ? "" : p.customPrompt;
        // 每只 AI 的性格（留空时随机原型，但用 personalityText() 保持同一只 AI 每次都一致）
        String persona = p.personalityText();
        // 只把“当前开着”的功能写进提示词：关掉的字段模型不该再输出，
        // 否则它会一直尝试被忽略的动作，看起来就像“AI 不听话”。
        StringBuilder fields = new StringBuilder();
        if (p.chat) {
            fields.append("  \"say\": \"要在聊天栏说的话\",\n");
        }
        fields.append("  \"goto\": [x, y, z],  走到这个坐标（会自动寻路）,\n");
        // 移动类动作：API Key 可以像“遥控器”一样直接操控 AI 走位
        fields.append("  \"move\": \"forward|back|left|right|stop（持续约 2 秒的移动）\",\n");
        fields.append("  \"moveTicks\": 40,  和 move 一起用：这次移动持续多少 tick（默认 60，范围 1~200）,\n");
        fields.append("  \"jump\": true,\n");
        fields.append("  \"sneak\": true,\n");
        fields.append("  \"sprint\": true,\n");
        fields.append("  \"look\": [yaw, pitch],\n");
        fields.append("  \"lookAt\": [x, y, z] 或 \"生物/玩家名\",  看向这个坐标或这个生物（放置、挖掘、对视时用）,\n");
        fields.append("  \"sleep\": true,  去最近的床睡觉（天黑了 / 下雨才有用；白天或没床会先说明）,\n");
        fields.append("  \"wake\": true,  从床上起来,\n");
        fields.append("  \"sethome\": true,  把当前位置记成“家”,\n");
        fields.append("  \"home\": true,  回家（回到 sethome 记下的位置）,\n");
        if (p.eat) {
            fields.append("  \"eat\": true,  吃背包里最合适的食物（不饿也能补，饥饿值满时吃不下）,\n");
        }
        fields.append("  \"offhand\": \"物品ID\",  把物品放到副手（盾牌 / 不死图腾等）,\n");
        fields.append("  \"useOn\": [x, y, z],  看向并右键使用这个方块（开箱子 / 按按钮 / 开门 / 点床 / 使用工具方块）,\n");
        fields.append("  \"giveTo\": {\"player\": \"玩家名\", \"item\": \"物品ID\", \"count\": 8},  当面把东西丢给某个玩家,\n");
        fields.append("  \"scan\": true,  扫描附近玩家 / 生物 / 掉落物，结果会写进下一轮状态「工具查询结果」,\n");
        fields.append("  \"probe\": [x, y, z],  查这个位置是什么方块、能不能站 / 能不能挖，结果同样在下一轮状态里,\n");
        if (p.mine) {
            fields.append("  \"mine\": [x, y, z],\n");
            fields.append("  \"chop\": [x, y, z],  把这块原木所在的整棵树砍掉（会自动连树干树冠一起收）,\n");
            fields.append("  \"mineVein\": true,  自己就近找矿脉挖（附近有矿才会动手）,\n");
            fields.append("  \"digTunnel\": true,  附近没矿时开一条 1x2 的矿道找矿,\n");
        }
        if (p.explore) {
            fields.append("  \"explore\": true,  自己出门探索（会记路，走远了或受伤了就回家）,\n");
        }
        if (p.place) {
            fields.append("  \"place\": [x, y, z],  在指定位置放一个方块（手上没方块会自动从快捷栏切一块过来）,\n");
            fields.append("  \"fill\": [\"fill x1 y1 z1 x2 y2 z2 方块\", ...],  建筑用：一次给 2~6 条 fill 指令，按地基→墙→门窗→屋顶顺序建（需要有方块）,\n");
        }
        if (p.combat) {
            fields.append("  \"attack\": \"要打的怪物名字（只在自保或获取资源时用，别主动打玩家）\",\n");
            fields.append("  \"pvp\": \"on|off\",  开关「主动打玩家」；默认关闭，只有玩家明确要求时才打开,\n");
        }
        if (p.boss) {
            fields.append("  \"boss\": true,  开始打末影龙（只有在末地、场上有龙时才用；会自动带同队一起去）,\n");
        }
        fields.append("  \"follow\": \"要跟随的玩家名，空字符串取消\",\n");
        if (p.eat) {
            fields.append("  \"cook\": true,\n");
        }
        fields.append("  \"open\": [x, y, z],  打开这个坐标的箱子/容器并把里面的物资收进背包,\n");
        fields.append("  \"trade\": \"附近村民的名字\",  和村民交易（买得起就成交）,\n");
        fields.append("  \"craft\": \"想合成的物品 ID（如 oak_planks），需要旁边有工作台，会自动摆材料、扣背包\",\n");
        fields.append("  \"craftCount\": 8,  合成几份（默认 1）,\n");
        fields.append("  \"smelt\": true,  去附近的熔炉烧炼（会自动放原料和燃料、先收成品）,\n");
        fields.append("  \"brew\": true,  去附近的酿造台酿药水（会先收成品，再放玻璃瓶/原料/烈焰粉）,\n");
        fields.append("  \"seed\": true,  查询世界种子,\n");
        fields.append("  \"locate\": \"village|stronghold|ruined_portal|shipwreck|ocean_ruin|mineshaft|trial_chambers|buried_treasure\",  查找最近的结构并拿到坐标,\n");
        if (p.bridge) {
            fields.append("  \"bridge\": \"sneak|god|telly|speed_telly|off（严禁乱用：只有真的要走过去、脚下又是深坑/虚空时才开，平时一律别碰）\",\n");
        }
        fields.append("  \"use\": \"要用一次的物品 ID（吃 / 喝 / 投掷 / 放置），如 golden_apple\",\n");
        fields.append("  \"equip\": \"要拿到主手上的物品 ID，如 diamond_sword\",\n");
        fields.append("  \"drop\": \"丢弃物品 ID（想把自己背包里的东西给玩家，就用这个，配 dropCount 指定数量）\",\n");
        fields.append("  \"dropCount\": 8,\n");
        if (p.command) {
            fields.append("  \"command\": \"要真正执行的服务器指令，不带 / 也行，例：give The_bodboy_ wool 8、time set day、weather clear\",\n");
        }
        fields.append("  \"plan\": [ {动作1}, {动作2}, ... ],  需要连着做 2~4 件事时，用 plan 一次把步骤给全（每步就是一个上面的动作对象），系统会按顺序依次执行；被打断（打架/逃跑）后会自动接着做,\n");
        fields.append("  \"stop\": true,\n  \"reason\": \"一句话说明你的想法\"\n");
        return """
                你是一个 Minecraft 生存服里的 AI 玩家，名字叫 "%s"，当前强度等级 %d/4（1 菜鸟 2 普通 3 高手 4 挂级）。
                你的主人是玩家 "%s"：要听主人的话，绝不攻击主人；主人被攻击时优先帮主人反击。
                你的性格设定是：%s —— 说话语气、用词、做事倾向都要始终带上这个性格，
                同一只 AI 前后性格不能飘；玩家问你怎么想的，就照这个性格回答。
                【首要目标】像真人一样"活下去并推进游戏"：找食物填饱肚子、挖矿、收集资源、合成、建造、
                探索地图、变强。把大部分精力放在生存与发展上，而不是打架。
                【活下去】饥饿低于 14 就先吃；血量低于四成且附近没敌人时先撤退、吃东西回血；
                天黑了优先回床睡觉（没床就用背包里的床铺一张），别为了探索或打架硬撑。
                背包里没有基础工具时，先挖石头做镐 / 剑 / 斧，再谈造建筑。
                【你所在的模组】你是 AIPlayer 模组生成的 AI 玩家：可以被 /aiplayer 指令和
                @all、@team:队伍、@near、@r、名字通配符批量管理；支持睡觉/起床、设置家并返回、
                进食、副手切换、给玩家物品、方块交互、区域扫描、坐标探测、PVP 开关、视角控制、精确移动；
                支持领地保护区（保护区内不挖不放）、死亡掉落回收、守卫巡逻、队伍对战和 TaCZ 枪械
                （TaCZ 枪靠物品 NBT 区分枪种，拿到后不要丢，也不要换成空壳枪）。
                玩家可以在 GUI 里给每只 AI 单独配置 API Key / 地址 / 模型，也能一键清空你的记忆。
                这些能力你心里有数即可，不用向玩家复述。
                【不要 PvP】除非有人先动手打你，否则绝不要主动攻击任何玩家、也不要追着人打；
                打怪只是为了自保或获取资源。想打架的冲动要克制，优先做上面那些生存的事。
                【找资源】想知道种子或找村庄 / 要塞 / 沉船等结构时，用 seed / locate 查；
                查到坐标后下一轮用 goto 过去，别瞎逛。
                注意 locate 只给 x 和 z —— 结构的 Y 是不可信的（原版显示为 ~），
                所以 goto 的 y 请填你自己的当前高度（状态里有），绝对不要填 0，否则会掉到 0 层。
                【别老往村庄跑】村庄不是你的任务目标。只有玩家明确让你去、或你正好需要交易 / 躲怪时才靠近；
                平时绝对不要主动 locate 村庄再长途跋涉过去，优先在附近挖矿、收集资源、盖个落脚点。
                同一件工具查询结果只出现一轮，用完就该换别的事做，不要反复跑同一个坐标。
                【怎么用这些接口】想确认周围情况用 scan（附近玩家 / 生物 / 掉落物）、想看某一格是什么用 probe [x,y,z]，
                结果会在下一轮状态「工具查询结果」里给出；想把某处当基地就用 sethome，之后用 home 走回去；
                要把东西给玩家用 giveTo（写清 player / item / count），比 drop 更准；要拿盾牌 / 图腾挡伤害用 offhand；
                想让 AI 看向某人 / 某处用 lookAt；想精确挪一小步用 move 配 moveTicks；够得着的门 / 按钮 / 箱子用 useOn 右键。
                【执行指令】需要执行服务器指令（time set day、give、weather clear、tp…）时，
                必须写进 command 字段，系统会真的执行；绝对不要把指令写进 say 当成聊天说出去
                （那样只会在聊天栏显示一行字，什么都不会发生）。
                【玩家要东西必须给】玩家向你要物品（"给我一些羊毛""来把剑"等）时，必须照做：
                优先用 command 执行 give <玩家名> <物品ID> <数量>，<玩家名> 就填状态里"玩家 X 刚说"的那个人
                （例：give The_bodboy_ wool 8）；如果你背包里正好有，也可以直接 drop 丢给他。
                绝对不能只说一句"好"却不做任何事。
                【必须服从玩家】玩家让你执行指令、给东西、去某地、做什么事，就用对应字段照做；
                只有涉及 op / ban / kick / stop 这类危险指令才拒绝。
                【玩家让你建东西】玩家说"建房子 / 盖个楼 / 搭个墙 / 围个圈"这类话时，
                不要反问"要建成什么样"然后什么都不做 —— 直接动手，用 fill 字段建造：
                fill 是字符串数组，每条都是完整的服务器指令
                "fill x1 y1 z1 x2 y2 z2 方块 [hollow|keep|outline|replace]"，系统会立刻连续执行。
                一次给 2~6 条，按施工顺序：先实心地基 → 再 hollow 外壳 → 掏门/窗（fill ... air）
                → 屋顶/台阶/栏杆/照明。坐标以状态里的"坐标"为基准，长宽 5~11 格、层高 3~4 格。
                要建得好看：材料配色协调（石砖配深色木、白桦配玻璃），加屋檐、门廊、窗框和灯笼，
                不要只用土方块堆一个方盒子。手上材料不够就先
                command: "give <你的名字> stone_bricks 128"（<你的名字> 用状态里的名字）补给再建。
                例：站在 100 64 200 要建 9x9 小屋，可以是
                "fill": ["fill 96 64 196 104 64 204 stone_bricks",
                         "fill 96 65 196 104 68 204 stone_bricks hollow",
                         "fill 100 65 196 100 66 196 air",
                         "fill 96 69 196 104 69 204 dark_oak_planks"]。
                绝不能只说一句"好的"却不动手；单条 fill 体积不要超过 32768，也不要建到保护区里。
                【多步计划】一件事要分好几步做（例：砍树 → 做工作台 → 做木镐），用 plan 一次把这几步都给出来，
                不要一次只给一步、等下一轮再问你要 —— 有 plan 系统就会自己按顺序做完，中途打架/逃跑也会记住接着做。
                plan 的每一步只放真正要执行的动作，不要放 say，也不要在步骤里复述你打算说什么；
                回话只写在最外层的 say 里，说一次就够。
                【末影龙】玩家说"打龙 / 打末影龙 / 去末地"时用 boss 字段（会自动把同队一起带过去）；
                在末地时你会自动按团队打法行动（先拆水晶 → 龙落下时打头 → 残血回撤补给 → 掉虚空用珍珠自救），
                不需要你一步步指挥，安静打就行。
                你的行为必须像一个真人玩家：正常走路、正常速度、不穿墙、不瞬移、不违反服务器规则。
                【重要】say 字段必须用 %s（%s）书写，任何情况下都不要输出别的语言；
                JSON 的字段名保持英文原样、不要翻译。
                【少说话】别每轮都说话：没有玩家在等你回话时，绝大多数轮次根本不要写 say，安静做事就行。
                只有"确实值得一提的大事"（比如发现村庄/要塞、被打、拿到好东西）才说一句话，
                而且一次只说一句、别碎碎念、别自言自语、别汇报你每个动作。
                注意：上面这条"少说话"不适用于"有玩家刚跟你说过话"的情况 —— 那种情况必须回话，见本文最后一条规则。
                物品 ID 直接写背包清单里的名字（可省略 minecraft: 前缀，如 diamond_sword、cooked_beef）。
                每轮你必须只输出一个 JSON 对象，不要输出多余文字。可用字段（全部可选）：
                {
                %s}
                只做当前局势最合理的一件事。reason 只是你自己的备注，不会发到聊天栏。
                有玩家说话时（状态里出现"玩家 X 刚说"），你必须先用 say 回他一句 —— 这是硬性要求，say 字段不能省略，语气自然、口语化、一句话。
                回复必须言之有物：回答他问的问题、确认你要做的具体动作（说清楚做什么）、或者针对他说的内容给出你自己的看法。
                严禁空洞应付：单独的"嗯""哦""好的""哈哈""我知道了""？？"这类没有信息量的回复一律不要；
                复读对方的话、答非所问、每轮都换着花样说"有什么可以帮你的"也算废话，同样禁止。
                你自己已经说过的话不要再说一遍（对话记忆里以"你说："开头的都是你的旧回复，不是新任务）；
                同一句回复只发一次，玩家这句话已经回过了就不要再回第二次，也不要把旧回复当成新指令重做一遍。
                不确定就直说不确定并说明原因（比如"我包里没铁了，得先去挖"）。
                不要用引号把整句包起来；对方如果让你做什么，再顺便用对应字段照做。%s
                """.formatted(p.name, p.levelId, ownerName(), persona, fields, localeName(locale), locale, custom);
    }

    /**
     * 周围环境：脚下 / 正前方方块、附近方块直方图、有没有水 / 树 / 矿、天光与昼夜。
     * <p>
     * 以前提示词里一个方块信息都没有，模型没法知道"旁边有没有树""脚下是不是水"，
     * 只能瞎编。这个扫描只在真正要问模型时跑一次（≥8 秒一次），不占每 tick 的开销。
     */
    void appendEnvironment(StringBuilder sb) {
        Level level = this.player.level();
        BlockPos feet = this.player.blockPosition();
        BlockPos front = BlockPos.containing(this.player.position().add(this.player.getLookAngle().scale(1.5D)));
        sb.append("脚下方块：").append(blockName(level.getBlockState(feet.below())))
                .append("，站立处：").append(blockName(level.getBlockState(feet)))
                .append("，正前方（看的方向 1.5 格）：").append(blockName(level.getBlockState(front)))
                .append("，头顶：").append(blockName(level.getBlockState(feet.above()))).append('\n');
        sb.append("光线：天光 ").append(level.getBrightness(LightLayer.SKY, feet.above()))
                .append("，方块光 ").append(level.getBrightness(LightLayer.BLOCK, feet.above()))
                .append("，").append(level.getSkyDarken() >= 5 ? "夜晚" : "白天").append('\n');

        Map<String, Integer> histogram = new java.util.LinkedHashMap<>();
        boolean water = false;
        boolean tree = false;
        boolean ore = false;
        boolean lava = false;
        // 水平半径 5、上下 -4~+4：既是"身边的地形"，开销也可控（约 1000 次方块查询）
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    if (!level.hasChunkAt(p)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(p);
                    if (state.isAir()) {
                        continue;
                    }
                    String name = blockName(state);
                    histogram.merge(name, 1, Integer::sum);
                    var fluid = state.getFluidState();
                    if (fluid.is(net.minecraft.tags.FluidTags.WATER)) {
                        water = true;
                    }
                    if (fluid.is(net.minecraft.tags.FluidTags.LAVA)) {
                        lava = true;
                    }
                    if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                        tree = true;
                    }
                    if (name.contains("_ore") || name.endsWith("ore")) {
                        ore = true;
                    }
                }
            }
        }
        sb.append("周围辨别：").append(water ? "附近有水 " : "")
                .append(lava ? "附近有岩浆（危险） " : "")
                .append(tree ? "附近有树 " : "")
                .append(ore ? "附近有矿石 " : "");
        if (!water && !lava && !tree && !ore) {
            sb.append("附近没有水 / 岩浆 / 树 / 矿石");
        }
        sb.append('\n');
        // 方块直方图：只报最常见的几种，足够模型判断"这是在树林/山地/洞穴/沙漠"
        List<Map.Entry<String, Integer>> top = new ArrayList<>(histogram.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (!top.isEmpty()) {
            sb.append("附近方块（半径 5 内最常见的）：");
            for (int i = 0; i < top.size() && i < 8; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(top.get(i).getKey()).append('x').append(top.get(i).getValue());
            }
            sb.append('\n');
        }
    }

    /** 家的坐标文本：没设过时直接告诉模型怎么设 */
    String homeText() {
        BlockPos home = this.explore.home();
        return home == null ? "未设置（想固定基地可以用 sethome）"
                : home.getX() + " " + home.getY() + " " + home.getZ();
    }

    /** 方块的注册名（原版省略命名空间）：给模型看的数据不带本地化，免得中英混杂 */
    static String blockName(BlockState state) {
        if (state.isAir()) {
            return "air";
        }
        net.minecraft.resources.Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            return "unknown";
        }
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    String statePrompt(String speaker, String heard) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前状态：").append(this.state.display).append('\n');
        sb.append("坐标：").append(fmt(this.player.getX())).append(' ')
                .append(fmt(this.player.getY())).append(' ').append(fmt(this.player.getZ())).append('\n');
        sb.append("血量：").append(fmt(this.player.getHealth())).append('/')
                .append(fmt(this.player.getMaxHealth()))
                .append("，饥饿：").append(this.player.getFoodData().getFoodLevel()).append('\n');
        sb.append("主手：").append(ItemIdUtil.describeStack(this.player.getMainHandItem()))
                .append("，副手：").append(ItemIdUtil.describeStack(this.player.getOffhandItem())).append('\n');
        sb.append("是否在地面：").append(this.player.onGround())
                .append("，是否在水中：").append(this.player.isInWater()).append('\n');
        appendEnvironment(sb);
        Entity enemy = this.combat.target();
        if (enemy != null) {
            sb.append("正在交战：").append(enemy.getName().getString())
                    .append("，距离 ").append(fmt(Math.sqrt(this.player.distanceToSqr(enemy)))).append('\n');
        }
        List<Player> nearby = this.player.level().getEntitiesOfClass(Player.class, this.player.getBoundingBox().inflate(24.0D));
        if (!nearby.isEmpty()) {
            sb.append("附近玩家：");
            for (Player p : nearby) {
                if (p == this.player || p.isSpectator()) {
                    continue;   // 旁观者不在局内：不列进上下文，AI 就不会对他做反应
                }
                sb.append(p.getName().getString()).append('(').append(fmt(Math.sqrt(this.player.distanceToSqr(p)))).append("格) ");
            }
            sb.append('\n');
        }
        sb.append("目标：").append(this.target() == null ? "无" : this.target().toShortString()).append('\n');
        sb.append("家：").append(homeText())
                .append("，是否在睡觉：").append(this.player.isSleeping() ? "是" : "否").append('\n');
        if (!this.planQueue.isEmpty()) {
            sb.append("计划队列：还有 ").append(this.planQueue.size()).append(" 步没做\n");
        }
        // 玩家的最新发言：放在最后、字数有限，模型不用理解上下文就能接话（省 token）
        if (speaker != null && heard != null) {
            sb.append("玩家 ").append(speaker).append(" 刚说：\"").append(heard).append("\"\n")
                    .append("请先用 say 回他一句（口语化、一句话，不要用引号包起来），再决定要不要顺带做别的动作。\n")
                    .append("如果这句话是在让你做事（建/挖/给东西/去某地/执行指令），")
                    .append("这一轮必须同时带上对应的动作字段真正去做，绝不能只 say 一句就结束。\n");
            // 智能③：把最近几轮对话（含它自己说过的话）一起带上，对话才连贯。
            // 只带"上一句"时，模型几轮之后就会把刚答应过的事忘干净，反过来反对自己。
            if (!this.dialogMemory.isEmpty()) {
                sb.append("最近对话（“你说”就是你自己说过的话）：\n");
                // 上面已经单独写过"刚说"的那句，这里跳过，免得模型以为玩家说了两遍
                String justSaid = "玩家 " + speaker + "：" + heard;
                for (String line : this.dialogMemory) {
                    if (line.equals(justSaid)) {
                        continue;
                    }
                    sb.append("  ").append(line).append('\n');
                }
                sb.append("要求：顺着上文接话，不要否认或推翻你自己刚说过的话")
                        .append("（比如刚答应的称呼、刚做过的承诺都要沿用），除非玩家明确让你改口。\n")
                        .append("“你说：...”是你自己以前发过的回复，不是新任务：不要把它再复述一遍，")
                        .append("同一句回复只发一次；玩家这句话如果已经回过了，就不要再回第二次。\n");
            }
        }
        // 把背包里的物品 ID 列给模型，它才能“认出”自己手头有什么，而不是瞎猜物品名
        String bag = ItemIdUtil.describe(this.player, 600);
        if (!bag.isBlank()) {
            sb.append("背包物品（item 相关字段直接用这些 ID）：").append(bag).append('\n');
        }
        // 附近的箱子 / 工位 / 村民：模型才知道有没有东西可翻、能不能合成、有没有村民可交易
        String spots = nearbyInteractables();
        if (!spots.isBlank()) {
            sb.append("附近可交互（open 用坐标、craft 用物品 ID、smelt/brew 用坐标、trade 用名字）：")
                    .append(spots).append('\n');
        }
        // 上一轮工具查询结果：模型要知道村庄/要塞坐标才能 goto 过去
        if (!this.lastToolResult.isBlank()) {
            sb.append("工具查询结果（可据此 goto，或据此回答玩家）：").append(this.lastToolResult).append('\n');
            // 只喂一次：否则这条旧坐标会在每一轮提示词里反复出现，
            // 模型就会把"去那个坐标"当成常驻任务，一直往村庄之类的方向跑。
            this.lastToolResult = "";
        }
        // 智能② 持久记忆：把最近玩家让你干过的事、以及你经历过的关键事件带上，
        // 这样不用玩家重复交代，也不会把"上次答应的事"忘掉。各限 5 条，省 token。
        if (this.profile != null) {
            if (!this.profile.recentCommands.isEmpty()) {
                sb.append("记忆·玩家最近让你做的事：")
                        .append(String.join(" / ", this.profile.recentCommands)).append('\n');
            }
            if (!this.profile.keyEvents.isEmpty()) {
                sb.append("记忆·你最近经历的事：")
                        .append(String.join(" / ", this.profile.keyEvents)).append('\n');
            }
            if (!this.profile.playerHabits.isEmpty()) {
                // 玩家习性：从日常对话里沉淀出来的短句，比每轮重新猜玩家想干什么有用得多
                sb.append("记忆·玩家习性：");
                int n = 0;
                for (var e : this.profile.playerHabits.entrySet()) {
                    if (n++ >= 5) {
                        break;
                    }
                    sb.append(e.getKey()).append('=').append(e.getValue()).append("；");
                }
                sb.append('\n');
            }
            if (!this.profile.recentCommands.isEmpty() || !this.profile.keyEvents.isEmpty()) {
                // 记忆是"以前已经做过 / 说过的"，不是待办清单：不写这句模型会反复重做、反复汇报
                sb.append("注意：以上记忆只代表已经做过或说过的事，不是新任务。")
                        .append("不要重新执行，也不要在聊天里把同一件事再汇报一遍。\n");
            }
        }
        return sb.toString();
    }

    /** 附近可交互的东西：容器坐标 + 工位（工作台/熔炉/酿造台）+ 村民名字（各限制几条，省 token） */
    String nearbyInteractables() {
        StringBuilder sb = new StringBuilder();
        int containers = 0;
        int stations = 0;
        BlockPos origin = this.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, -6, -12), origin.offset(12, 6, 12))) {
            if (this.player.level().getBlockEntity(pos) instanceof Container) {
                sb.append(pos.toShortString()).append(' ');
                containers++;
            }
            if (stations < 3) {
                String label = stationLabel(pos);
                if (label != null) {
                    sb.append(label).append('(').append(pos.toShortString()).append(") ");
                    stations++;
                }
            }
            if (containers >= 4 && stations >= 3) {
                break;
            }
        }
        int villagers = 0;
        List<Villager> list = this.player.level().getEntitiesOfClass(Villager.class,
                this.player.getBoundingBox().inflate(16.0D));
        for (Villager v : list) {
            if (!v.isAlive()) {
                continue;
            }
            sb.append("村民[").append(v.getName().getString()).append(']');
            if (++villagers >= 3) {
                break;
            }
        }
        return sb.toString().trim();
    }


    /** 解析 LLM 的 JSON 动作并执行。{@code replyingToPlayer} 表示这一轮是在回玩家的话 */
    void applyLlmReply(String reply, boolean replyingToPlayer) {
        try {
            String json = reply.trim();
            // 不再假设 JSON 一定从第一个字符开始：模型经常先来一句"好的，这是动作："
            // 或者用 ```json 包起来。以前只有代码块开头才截取，导致前面带一句话的回复
            // 被 JsonParser 直接判失败、整轮静默丢弃 —— 表现就是"AI 又不会说话了"。
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                if (replyingToPlayer) {
                    AiPlayerMod.LOGGER.warn("[aiplayer] {} 模型返回的不是 JSON 对象：{}",
                            this.profile.name, shortenForLog(reply));
                }
                return;
            }
            JsonObject obj = root.getAsJsonObject();
            if (replyingToPlayer) {
                // 排查"AI 不回话"的关键日志：模型到底返回了什么、有没有 say 字段
                AiPlayerMod.LOGGER.info("[aiplayer] {} 模型回复：{}", this.profile.name,
                        shortenForLog(json));
            }
            // 智能①：模型一次给 2~4 步的 plan 数组 → 入队，空闲时逐条执行
            if (obj.has("plan") && obj.get("plan").isJsonArray()) {
                this.planQueue.clear();
                // plan 只保留"动作"，把 say / reason 摘掉：
                // 模型常把回话也塞进计划步骤里，结果这一轮已经回过一次，
                // 过几秒执行计划时又把同一句话念一遍 —— 玩家看到的就是"同一句回两遍"。
                // 计划里的重复步骤也一并跳过，免得同一个动作被排队做两次。
                java.util.Set<String> seenSteps = new java.util.HashSet<>();
                for (JsonElement el : obj.getAsJsonArray("plan")) {
                    if (!el.isJsonObject() || this.planQueue.size() >= PLAN_MAX_STEPS) {
                        continue;
                    }
                    JsonObject step = el.getAsJsonObject().deepCopy();
                    step.remove("say");
                    step.remove("reason");
                    if (step.size() == 0) {
                        continue;
                    }
                    if (seenSteps.add(step.toString())) {
                        this.planQueue.add(step);
                    }
                }
                this.planStepTicks = 0;
                this.planStallTicks = 0;
            }
            this.spokeToPlayer = false;
            applyAction(obj, replyingToPlayer);
            // 模型只给动作没给 say 时不再硬补"嗯我在听"——它已经在按指令干活了，
            // 再来一句空洞应答反而是废话。
            this.replySpeaker = null;
        } catch (Throwable t) {
            // 模型输出不是合法 JSON 就忽略这一轮，但留下日志：
            // 玩家反馈"AI 不说话"时，这条日志能直接看出是模型没按格式回，还是解析器的问题。
            if (replyingToPlayer) {
                AiPlayerMod.LOGGER.warn("[aiplayer] {} 模型输出解析失败：{}（原文：{}）",
                        this.profile.name, t.toString(), shortenForLog(reply));
            }
        }
    }

    /** 日志里只保留前 300 字符，避免把整段模型输出刷进日志 */
    private static String shortenForLog(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace('\n', ' ').trim();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** 执行一个动作对象（单步回复 / plan 里的一步都走这里） */
    void applyAction(JsonObject obj, boolean replyingToPlayer) {
        boolean did = false;

            if (obj.has("bridge") && this.profile.bridge) {
                String mode = obj.get("bridge").getAsString();
                if ("off".equalsIgnoreCase(mode)) {
                    this.bridge.stop();
                } else {
                    BridgeMode byName = switch (mode.toLowerCase()) {
                        case "god", "godbridge" -> BridgeMode.GOD;
                        case "telly" -> BridgeMode.TELLY;
                        case "speed_telly", "speedtelly", "telly_speed" -> BridgeMode.SPEED_TELLY;
                        case "sneak", "eagle" -> BridgeMode.SNEAK;
                        default -> null;
                    };
                    if (byName != null) {
                        AiInput in = this.player.input();
                        this.bridge.start(byName == BridgeMode.SNEAK ? BridgeController.yawToDirection(in.yaw) : null);
                    }
                }
                did = true;
            }
            if (obj.has("stop") && obj.get("stop").getAsBoolean()) {
                stop();
                did = true;
            }
            if (obj.has("follow")) {
                setFollow(obj.get("follow").getAsString());
                did = true;
            }
            if (obj.has("sleep") && obj.get("sleep").getAsBoolean()) {
                sleepCommand();
                did = true;
            }
            if (obj.has("wake") && obj.get("wake").getAsBoolean()) {
                queue(() -> {
                    if (this.player.isSleeping()) {
                        this.player.stopSleeping();
                    }
                    this.sleep.reset();
                });
                did = true;
            }
            if (obj.has("sethome") && obj.get("sethome").getAsBoolean()) {
                BlockPos here = this.player.blockPosition().immutable();
                queue(() -> this.explore.setHome(here));
                this.lastToolResult = "已把当前位置记为家：" + here.getX() + " " + here.getY() + " " + here.getZ();
                did = true;
            }
            if (obj.has("home") && obj.get("home").getAsBoolean()) {
                BlockPos home = this.explore.home();
                if (home == null) {
                    // 结果是喂给下一轮模型的：没设家时直接告诉它怎么设，别让它一直原地打转
                    this.lastToolResult = "还没有设置家的位置，可以先在当前位置用 sethome 记一个";
                } else {
                    walkTo(home);
                    did = true;
                }
            }
            if (obj.has("eat") && obj.get("eat").getAsBoolean() && this.profile.eat && eatBestFood()) {
                did = true;
            }
            if (obj.has("offhand") && holdOffhandItem(obj.get("offhand").getAsString())) {
                did = true;
            }
            if (obj.has("giveTo") && obj.get("giveTo").isJsonObject()) {
                JsonObject gift = obj.getAsJsonObject("giveTo");
                String playerName = gift.has("player") ? gift.get("player").getAsString() : null;
                String itemSpec = gift.has("item") ? gift.get("item").getAsString() : null;
                int count = gift.has("count") ? gift.get("count").getAsInt() : 0;
                if (giveToPlayer(playerName, itemSpec, count)) {
                    did = true;
                }
            }
            if (obj.has("scan") && obj.get("scan").getAsBoolean()) {
                this.lastToolResult = scanNearby();
                did = true;
            }
            if (obj.has("probe")) {
                BlockPos pos = readPos(obj.get("probe"));
                if (pos != null) {
                    this.lastToolResult = probeBlock(pos);
                    did = true;
                }
            }
            if (obj.has("pvp") && this.profile.combat) {
                String mode = obj.get("pvp").getAsString().toLowerCase(Locale.ROOT);
                boolean on = "on".equals(mode) || "true".equals(mode) || "打开".equals(mode) || "允许".equals(mode);
                queue(() -> {
                    this.allowPlayerPvp = on;
                    if (!on && this.attackTarget instanceof Player) {
                        this.attackTarget = null;
                    }
                });
                did = true;
            }
            if (obj.has("cook") && obj.get("cook").getAsBoolean()) {
                cook();
                did = true;
            }
            if (obj.has("goto")) {
                BlockPos pos = readPos(obj.get("goto"));
                if (pos != null) {
                    walkTo(pos);
                    did = true;
                }
            }
            if (obj.has("mine") && this.profile.mine) {
                BlockPos pos = readPos(obj.get("mine"));
                if (pos != null) {
                    mineAt(pos);
                    did = true;
                }
            }
            if (obj.has("chop") && this.profile.mine) {
                BlockPos pos = readPos(obj.get("chop"));
                if (pos != null) {
                    // 给的是原木坐标：让它自己把整棵树连冠一起收
                    this.chop.chopAt(pos);
                    did = true;
                }
            }
            if (obj.has("mineVein") && this.profile.mine && obj.get("mineVein").getAsBoolean()) {
                this.mining.startOre();
                did = true;
            }
            if (obj.has("digTunnel") && this.profile.mine && obj.get("digTunnel").getAsBoolean()) {
                this.mining.startBranch();
                did = true;
            }
            if (obj.has("explore") && this.profile.explore && obj.get("explore").getAsBoolean()) {
                this.explore.start();
                did = true;
            }
            if (obj.has("place") && this.profile.place) {
                BlockPos pos = readPos(obj.get("place"));
                if (pos != null) {
                    placeAt(pos);
                    did = true;
                }
            }
            if (obj.has("fill") && this.profile.place) {
                // 建筑统一走 fill：模型一次给 2~6 条，地基/墙/窗/屋顶连续成型。
                // 体积、保护区、坐标合法性都在 runFillCommand 里校验，不合规直接跳过。
                JsonElement f = obj.get("fill");
                List<String> cmds = new ArrayList<>();
                if (f.isJsonArray()) {
                    for (JsonElement el : f.getAsJsonArray()) {
                        if (cmds.size() >= 12) {
                            break;
                        }
                        if (el.isJsonPrimitive()) {
                            cmds.add(el.getAsString());
                        }
                    }
                } else if (f.isJsonPrimitive()) {
                    cmds.add(f.getAsString());
                }
                for (String c : cmds) {
                    if (runFillCommand(c)) {
                        did = true;
                    }
                }
            }
            if (obj.has("build") && this.profile.place && obj.get("build").isJsonArray()) {
                // 连续建造：一次给一串坐标（搭墙 / 小屋），AI 会逐块走过去放
                List<BlockPos> plan = new ArrayList<>();
                for (JsonElement el : obj.getAsJsonArray("build")) {
                    BlockPos pos = readPos(el);
                    if (pos != null) {
                        plan.add(pos);
                        if (plan.size() >= 64) {
                            break; // 上限 64 块，别让一轮回复塞进几千个坐标
                        }
                    }
                }
                if (!plan.isEmpty() && this.placeTarget == null && this.buildQueue.isEmpty()) {
                    // 正在建就别被模型的新方案顶掉：本地兜底已经开工时，模型的坐标常常是错的，
                    // 覆盖过去反而会让 AI 站着不动。
                    if (plan.size() >= 12) {
                        // 坐标一大片 = 模型想盖房子。逐块放又慢坐标又容易歪，
                        // 直接取包围盒用 /fill 实心一灌再挖空，一秒成型。
                        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                        for (BlockPos p : plan) {
                            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
                            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
                        }
                        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
                        if (volume > 4096) {
                            // 模型给的坐标可能散得很开（比如跨了半个峡谷），直接 fill 会把整片地形灌成木板。
                            // 体积超过 16x16x16 就退回逐块建造，宁可慢也不能毁地形。
                            AiPlayerMod.LOGGER.warn("[aiplayer] {} 的建造范围过大（{} 格），改为逐块放置",
                                    this.profile.name, volume);
                            buildAt(plan);
                        } else {
                            String wall = this.profile.bridgeBlock == null || this.profile.bridgeBlock.isBlank()
                                    ? "oak_planks" : this.profile.bridgeBlock;
                            this.chat.forceCommand("fill " + minX + " " + minY + " " + minZ + " "
                                    + maxX + " " + maxY + " " + maxZ + " " + wall);
                            if (maxX - minX >= 2 && maxZ - minZ >= 2 && maxY > minY) {
                                this.chat.forceCommand("fill " + (minX + 1) + " " + (minY + 1) + " " + (minZ + 1)
                                        + " " + (maxX - 1) + " " + maxY + " " + (maxZ - 1) + " air");
                            }
                        }
                        did = true;
                    } else {
                        buildAt(plan);
                        did = true;
                    }
                }
            }
            if (obj.has("attack") && this.profile.combat) {
                Entity target = findByName(obj.get("attack").getAsString());
                if (target != null && allowedToAttack(target)) {
                    attack(target);
                    did = true;
                }
            }
            if (obj.has("open")) {
                BlockPos pos = readPos(obj.get("open"));
                if (pos != null) {
                    openContainer(pos);
                    did = true;
                }
            }
            if (obj.has("trade")) {
                Entity target = findByName(obj.get("trade").getAsString());
                if (target instanceof Villager) {
                    tradeWith(target);
                    did = true;
                }
            }
            if (obj.has("craft")) {
                Item want = ItemIdUtil.resolve(obj.get("craft").getAsString());
                BlockPos table = want == null ? null : nearestStation(16, "CraftingTable");
                if (table != null) {
                    int times = obj.has("craftCount") ? Math.max(1, Math.min(8, obj.get("craftCount").getAsInt())) : 1;
                    openCraft(table, want, times);
                    did = true;
                }
            }
            if (obj.has("smelt")
                    && (obj.get("smelt").isJsonArray() || obj.get("smelt").getAsBoolean())) {
                BlockPos furnace = obj.get("smelt").isJsonArray()
                        ? readPos(obj.get("smelt"))
                        : nearestStation(16, "Furnace", "Smoker");
                if (furnace != null) {
                    openSmelt(furnace);
                    did = true;
                }
            }
            if (obj.has("brew")
                    && (obj.get("brew").isJsonArray() || obj.get("brew").getAsBoolean())) {
                BlockPos stand = obj.get("brew").isJsonArray()
                        ? readPos(obj.get("brew"))
                        : nearestStation(16, "BrewingStand");
                if (stand != null) {
                    openBrew(stand);
                    did = true;
                }
            }
            if (obj.has("seed") && obj.get("seed").getAsBoolean()) {
                toolSeed();
                did = true;
            }
            if (obj.has("locate")) {
                toolLocate(obj.get("locate").getAsString());
                did = true;
            }
            if (obj.has("boss") && obj.get("boss").getAsBoolean() && this.profile.boss) {
                startBossFight();
                did = true;
            }
            if (obj.has("say") && this.profile.chat && obj.get("say").isJsonPrimitive()) {
                // 必须判 isJsonPrimitive：模型有时会给 "say": null 或者对象，
                // 直接 getAsString() 会抛异常，被下面的 catch 吞掉 —— 连带把后面
                // use/equip/command 这些动作也一起丢掉。
                String line = obj.get("say").getAsString();
                // 兜底：模型经常把指令当聊天说出去（结果聊天栏只显示一行字、什么都没发生）。
                // 只要整句就是一条安全的指令（以 / 开头，或 aiplayer ...），就直接执行、不广播；
                // 不安全（op/ban/@a 之类）则照常当聊天发出去，免得整句凭空消失。
                if (this.profile.command && isCommandLine(line) && ChatController.isSafe(line)) {
                    runCommand(line);
                } else if (replyingToPlayer) {
                    // 回话走 sayReply：只受 1 秒最低间隔限制，不会被业务冷却（默认 3 秒）吃掉
                    this.spokeToPlayer = this.chat.sayReply(line);
                    if (!this.spokeToPlayer) {
                        // 说出口失败要能看见原因：多半是 1 秒最低间隔把这句话吃掉了
                        // （同一只 AI 连着被搭话时很容易撞上），或者 GUI 里"聊天"开关是关的。
                        AiPlayerMod.LOGGER.warn("[aiplayer] {} 的回话被丢弃（chat={}）：「{}」",
                                this.profile.name, this.profile.chat, line);
                    } else {
                        // 对话记忆：把自己答应过的事也记下来，下一轮才不会反口
                        rememberDialog("你说：" + line);
                    }
                } else {
                    say(line);
                    rememberDialog("你说：" + line);
                }
                did = true;
            }
            if (obj.has("use") && useItem(obj.get("use").getAsString())) {
                did = true;
            }
            if (obj.has("equip") && holdItem(obj.get("equip").getAsString())) {
                did = true;
            }
            if (obj.has("drop")) {
                int count = obj.has("dropCount") && obj.get("dropCount").isJsonPrimitive()
                        ? obj.get("dropCount").getAsInt() : 0;
                if (dropItem(obj.get("drop").getAsString(), count)) {
                    did = true;
                }
            }
            if (obj.has("command") && this.profile.command) {
                runCommand(obj.get("command").getAsString());
                did = true;
            }
            if (obj.has("move") || obj.has("jump") || obj.has("sneak") || obj.has("sprint") || obj.has("look")
                    || obj.has("lookAt") || obj.has("moveTicks") || obj.has("useOn")) {
                applyManual(obj);
                did = true;
            }
            // 注意：reason 只是模型给自己的备注，绝不当作聊天发出去
            //（以前这里会把 reason 当 say 广播，玩家看到的就是“AI 莫名其妙乱说话”）。
    }

    /**
     * 整句是不是一条指令。
     * <p>
     * 模型经常把指令塞进 say（聊天栏只显示一行字、什么都没发生），这里做兜底识别：
     * 以 / 开头，或者形如 {@code aiplayer ...} 的裸指令名，都当作指令直接执行。
     */
    static boolean isCommandLine(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return t.startsWith("/") || t.toLowerCase(Locale.ROOT).startsWith("aiplayer ");
    }

    /** 解析 LLM 的移动类字段（move/jump/sneak/sprint/look/lookAt/useOn），进入短暂的手动操控状态 */
    void applyManual(JsonObject obj) {
        this.mForward = false;
        this.mBack = false;
        this.mLeft = false;
        this.mRight = false;
        this.mJump = false;
        this.mSneak = false;
        this.mSprint = false;
        this.mYaw = null;
        this.mPitch = null;
        if (obj.has("move") && obj.get("move").isJsonPrimitive()) {
            switch (obj.get("move").getAsString().toLowerCase(Locale.ROOT)) {
                case "forward", "w", "up" -> this.mForward = true;
                case "back", "backward", "s" -> this.mBack = true;
                case "left", "a" -> this.mLeft = true;
                case "right", "d" -> this.mRight = true;
                default -> {
                    // stop / 其它：保持原地
                }
            }
        }
        if (obj.has("jump")) {
            this.mJump = obj.get("jump").getAsBoolean();
        }
        if (obj.has("sneak")) {
            this.mSneak = obj.get("sneak").getAsBoolean();
        }
        if (obj.has("sprint")) {
            this.mSprint = obj.get("sprint").getAsBoolean();
        }
        if (obj.has("look") && obj.get("look").isJsonArray()) {
            var arr = obj.getAsJsonArray("look");
            if (arr.size() >= 2) {
                this.mYaw = arr.get(0).getAsFloat();
                this.mPitch = arr.get(1).getAsFloat();
            }
        }
        if (obj.has("lookAt")) {
            Vec3 point = lookTarget(obj.get("lookAt"));
            if (point != null) {
                float[] angles = lookAngles(point.x, point.y, point.z);
                this.mYaw = angles[0];
                this.mPitch = angles[1];
            }
        }
        if (obj.has("useOn")) {
            // 交给 useOnBlock 直接执行：光靠准星射线空手是打不开门 / 按钮 / 箱子的
            BlockPos pos = readPos(obj.get("useOn"));
            if (pos != null) {
                useOnBlock(pos);
            }
        }
        int hold = MANUAL_HOLD_TICKS;
        if (obj.has("moveTicks") && obj.get("moveTicks").isJsonPrimitive()) {
            hold = Math.max(1, Math.min(200, obj.get("moveTicks").getAsInt()));
        }
        this.manualTicks = hold;
    }

    /** lookAt 的目标：坐标数组取方块中心；字符串按生物 / 玩家名找当前眼睛位置 */
    Vec3 lookTarget(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            BlockPos pos = readPos(element);
            return pos == null ? null : Vec3.atCenterOf(pos);
        }
        if (element.isJsonPrimitive()) {
            Entity entity = findByName(element.getAsString());
            return entity == null ? null : entity.getEyePosition();
        }
        return null;
    }

    /** 看向某点需要的 (yaw, pitch)，公式与原版实体朝向一致 */
    float[] lookAngles(double targetX, double targetY, double targetZ) {
        double dx = targetX - this.player.getX();
        double dy = targetY - this.player.getEyeY();
        double dz = targetZ - this.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(1.0E-4D, horizontal)));
        return new float[]{yaw, pitch};
    }

    /** LLM "useOn"：看向并右键使用这个方块（空手也能触发门 / 按钮 / 箱子 / 床等方块交互） */
    boolean useOnBlock(BlockPos pos) {
        if (this.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > 4.5D * 4.5D) {
            // 够不着：给下一轮模型留一句可执行的提示，别让它反复"点了却没反应"
            this.lastToolResult = "useOn 的目标太远（超过 4.5 格）：先用 goto 靠近，下一轮再用 useOn";
            return false;
        }
        if (!this.player.useItemOnBlock(pos)) {
            this.lastToolResult = "右键这个方块没有生效（可能手里拿着的东西不能用在这里）";
            return false;
        }
        float[] angles = lookAngles(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        this.mYaw = angles[0];
        this.mPitch = angles[1];
        return true;
    }

    /** 把手动操控状态写进本 tick 的输入 */
    void applyManual(AiInput in) {
        in.forward = this.mForward;
        in.backward = this.mBack;
        in.left = this.mLeft;
        in.right = this.mRight;
        in.jump = this.mJump;
        in.sneak = this.mSneak;
        in.sprint = this.mSprint;
        if (this.mYaw != null) {
            in.yaw = this.mYaw;
        }
        if (this.mPitch != null) {
            in.pitch = this.mPitch;
        }
    }

    /** LLM "eat"：吃背包里最合适的食物（不受饥饿阈值限制，但饥饿值满时吃不下） */
    boolean eatBestFood() {
        if (this.player.getFoodData().getFoodLevel() >= 20) {
            this.lastToolResult = "现在饥饿值是满的，吃不下东西";
            return false;
        }
        Inventory inv = this.player.getInventory();
        int slot = this.food.bestFoodSlot();
        if (slot < 0) {
            int anywhere = this.food.bestFoodSlotAnywhere();
            if (anywhere < 0) {
                this.lastToolResult = "背包里没有食物";
                return false;
            }
            slot = moveToHand(inv.getItem(anywhere).getItem());
        }
        if (slot < 0 || !this.player.selectSlot("llm", com.aiplayer.util.InventoryLock.PRIO_TASK, slot)) {
            return false;
        }
        this.useHoldSlot = slot;
        this.useHoldTicks = USE_HOLD_MAX_TICKS;
        this.useHoldApplied = 0;
        return true;
    }

    /** LLM "offhand"：把物品换到副手（盾牌 / 不死图腾） */
    boolean holdOffhandItem(String spec) {
        Item item = ItemIdUtil.resolve(spec);
        if (item == null) {
            return false;
        }
        Inventory inv = this.player.getInventory();
        ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
        if (!off.isEmpty() && off.is(item)) {
            return true;
        }
        if (!this.player.inventoryLock().acquire("llm", com.aiplayer.util.InventoryLock.PRIO_TASK)) {
            return false;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == Inventory.SLOT_OFFHAND) {
                continue;
            }
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                inv.setItem(i, off);
                inv.setItem(Inventory.SLOT_OFFHAND, stack);
                return true;
            }
        }
        this.lastToolResult = "背包里没有 " + spec;
        return false;
    }

    /** LLM "giveTo"：把指定数量的物品当面丢给某个玩家 */
    boolean giveToPlayer(String playerName, String itemSpec, int count) {
        if (itemSpec == null || itemSpec.isBlank()) {
            return false;
        }
        Item item = ItemIdUtil.resolve(itemSpec);
        if (item == null) {
            this.lastToolResult = "不认识物品 " + itemSpec;
            return false;
        }
        Player target = resolvePlayer(playerName);
        if (target == null) {
            this.lastToolResult = "附近没有找到玩家"
                    + (playerName == null || playerName.isBlank() ? "" : " " + playerName);
            return false;
        }
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int n = count <= 0 ? stack.getCount() : Math.min(count, stack.getCount());
            ItemStack gift = stack.copyWithCount(n);
            stack.shrink(n);
            if (stack.isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            throwGift(target, gift);
            return true;
        }
        this.lastToolResult = "背包里没有 " + itemSpec;
        return false;
    }

    /** 按名字找人；找不到名字就退而求其次，用最近的玩家 */
    Player resolvePlayer(String name) {
        if (name != null && !name.isBlank()) {
            MinecraftServer server = this.player.serverLevel().getServer();
            Player byName = server == null ? null : server.getPlayerList().getPlayerByName(name);
            if (byName != null) {
                return byName;
            }
            Entity entity = findByName(name);
            if (entity instanceof Player p) {
                return p;
            }
        }
        return this.player.level().getEntitiesOfClass(Player.class,
                        this.player.getBoundingBox().inflate(24.0D), p -> p != this.player && !p.isSpectator())
                .stream().min(Comparator.comparingDouble(this.player::distanceToSqr)).orElse(null);
    }

    /** 轻抛物品给对方，落点与真人按 Q 接近 */
    void throwGift(Entity target, ItemStack gift) {
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

    /** LLM "scan"：把附近的玩家 / 生物 / 掉落物写成一段简报，下一轮状态会带上 */
    String scanNearby() {
        Level level = this.player.level();
        AABB box = this.player.getBoundingBox().inflate(24.0D);
        StringBuilder sb = new StringBuilder("附近扫描（24 格内）：");
        List<Player> players = level.getEntitiesOfClass(Player.class, box,
                p -> p != this.player && !p.isSpectator());
        players.sort(Comparator.comparingDouble(this.player::distanceToSqr));
        sb.append("玩家 ");
        if (players.isEmpty()) {
            sb.append("无");
        } else {
            for (int i = 0; i < players.size() && i < 4; i++) {
                Player p = players.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(p.getName().getString()).append(' ')
                        .append(fmt(Math.sqrt(this.player.distanceToSqr(p)))).append("格");
            }
        }
        List<LivingEntity> mobs = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != this.player && !(e instanceof Player));
        mobs.sort(Comparator.comparingDouble(this.player::distanceToSqr));
        sb.append("；生物 ");
        if (mobs.isEmpty()) {
            sb.append("无");
        } else {
            for (int i = 0; i < mobs.size() && i < 6; i++) {
                LivingEntity e = mobs.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(entityTypeName(e)).append(' ')
                        .append(fmt(Math.sqrt(this.player.distanceToSqr(e)))).append("格");
                String custom = e.getName().getString();
                if (!custom.equalsIgnoreCase(entityTypeName(e))) {
                    sb.append('(').append(custom).append(')');
                }
                if (e instanceof Monster) {
                    sb.append("[敌对]");
                } else if (e instanceof Animal) {
                    sb.append("[动物]");
                }
            }
        }
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, box, Entity::isAlive);
        drops.sort(Comparator.comparingDouble(this.player::distanceToSqr));
        sb.append("；掉落物 ");
        if (drops.isEmpty()) {
            sb.append("无");
        } else {
            for (int i = 0; i < drops.size() && i < 4; i++) {
                ItemEntity d = drops.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(ItemIdUtil.describeStack(d.getItem())).append(' ')
                        .append(fmt(Math.sqrt(this.player.distanceToSqr(d)))).append("格");
            }
        }
        return sb.toString();
    }

    /** LLM "probe"：查一个坐标的方块信息（是什么、能不能站、硬度、上下相邻） */
    String probeBlock(BlockPos pos) {
        Level level = this.player.level();
        String at = "(" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ")";
        if (!level.hasChunkAt(pos)) {
            return at + " 还没加载（离你太远），需要先走过去";
        }
        BlockState state = level.getBlockState(pos);
        boolean solid = com.aiplayer.ai.path.AStarPathFinder.solid(level, pos);
        boolean floor = com.aiplayer.ai.path.AStarPathFinder.solid(level, pos.below());
        boolean water = state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        boolean lava = state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        double hardness = state.getDestroySpeed(level, pos);
        return at + " 方块=" + blockName(state)
                + "，可站立=" + (state.isAir() && floor)
                + "，实心=" + solid
                + "，流体=" + (water ? "水" : lava ? "岩浆" : "无")
                + "，硬度=" + (hardness < 0 ? "不可破坏" : String.format(Locale.ROOT, "%.1f", hardness))
                + "，上方=" + blockName(level.getBlockState(pos.above()))
                + "，下方=" + blockName(level.getBlockState(pos.below()));
    }

    /** 实体类型 ID（scan 用，省略 minecraft: 前缀） */
    static String entityTypeName(Entity e) {
        net.minecraft.resources.Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        if (id == null) {
            return e.getType().toString();
        }
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    /**
     * 执行一条 LLM 给出的 {@code fill} 指令。
     * <p>
     * 校验三件事再放行：真的是 fill、体积不超过原版上限、起止两角与中心不在保护区
     * （fill 是直接改方块，会绕过 BlockBreaker / PlaceUtil 的单点保护，必须在这里补一刀）。
     */
    private boolean runFillCommand(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String cmd = raw.trim();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1).trim();
        }
        String[] t = cmd.split("\\s+");
        if (t.length < 8 || !"fill".equalsIgnoreCase(t[0])) {
            return false;
        }
        int x1;
        int y1;
        int z1;
        int x2;
        int y2;
        int z2;
        try {
            x1 = Integer.parseInt(t[1]);
            y1 = Integer.parseInt(t[2]);
            z1 = Integer.parseInt(t[3]);
            x2 = Integer.parseInt(t[4]);
            y2 = Integer.parseInt(t[5]);
            z2 = Integer.parseInt(t[6]);
        } catch (NumberFormatException ignored) {
            return false;
        }
        long volume = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
        if (volume > 32768L) {
            AiPlayerMod.LOGGER.info("[aiplayer] {} 拒绝过大的 fill（{} 格）", this.profile.name, volume);
            return false;
        }
        var level = this.player.level();
        BlockPos a = new BlockPos(x1, y1, z1);
        BlockPos b = new BlockPos(x2, y2, z2);
        BlockPos mid = new BlockPos((x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2);
        if (com.aiplayer.zone.ZoneManager.isProtected(level, a)
                || com.aiplayer.zone.ZoneManager.isProtected(level, b)
                || com.aiplayer.zone.ZoneManager.isProtected(level, mid)) {
            AiPlayerMod.LOGGER.info("[aiplayer] {} 拒绝保护区内的 fill：{}", this.profile.name, cmd);
            return false;
        }
        this.chat.forceCommand(cmd);
        return true;
    }

    /**
     * 解析坐标。除了 [x, y, z]，还兼容 y 写成 "~"（原版 /locate 的写法）或干脆缺省的情况 ——
     * 这时用该列地表高度补上，避免模型把 ~ 当成 0、把 AI 带到 y=0 层去。
     */
    BlockPos readPos(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        var array = element.getAsJsonArray();
        if (array.size() < 3) {
            return null;
        }
        Integer x = readInt(array.get(0));
        Integer y = readInt(array.get(1));
        Integer z = readInt(array.get(2));
        if (x == null || z == null) {
            return null;
        }
        int yy = y == null ? surfaceY(x, z) : y;
        return new BlockPos(x, yy, z);
    }

    /** 把 "~"、空串、null 一律当作“没有这个数”，返回 null */
    static Integer readInt(JsonElement element) {
        try {
            if (element == null || element.isJsonNull()) {
                return null;
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt();
            }
            String text = element.getAsString().trim();
            if (text.isEmpty() || text.startsWith("~")) {
                return null;
            }
            return (int) Math.round(Double.parseDouble(text));
        } catch (Throwable ignored) {
            return null;
        }
    }

}