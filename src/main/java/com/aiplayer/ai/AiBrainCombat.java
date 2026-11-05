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

/** 战斗相关决策：选敌 / 还手 / 追击 / 嘲讽 / 队友协战。 */
class AiBrainCombat extends AiBrainWork {
    AiBrainCombat(AiPlayer player, AiPlayerManager manager, AiProfile profile) {
        super(player, manager, profile);
    }

    boolean fightTick(AiInput in) {
        if (!this.profile.combat) {
            this.combat.clear();
            this.ranged.release();
            return false;
        }
        // 挨打才还手：只有被玩家 / 怪主动攻击了，才把攻击者设成目标。
        // AI 不会主动去招惹任何玩家（包括陌生人），更不会攻击主人。
        LivingEntity hurtBy = this.player.getLastHurtByMob();
        if (hurtBy != null && hurtBy != this.player && hurtBy.isAlive()
                && this.player.tickCount - this.player.getLastHurtByMobTimestamp() < 200
                && !isOwner(hurtBy) && !sameTeam(hurtBy)) {
            if (this.attackTarget != hurtBy) {
                this.attackTarget = hurtBy;
                this.combat.setTarget(hurtBy);
            }
        }
        // 主人永远不是目标
        if (this.attackTarget != null && isOwner(this.attackTarget)) {
            this.attackTarget = null;
        }
        // 主人被谁打了 -> 优先帮主人打回去
        Player owner = ownerPlayer();
        if (owner != null) {
            LivingEntity ownerHurt = owner.getLastHurtByMob();
            if (ownerHurt != null && ownerHurt.isAlive() && ownerHurt != this.player
                    && owner.tickCount - owner.getLastHurtByMobTimestamp() < 200
                    && !isOwner(ownerHurt) && !(ownerHurt instanceof AiPlayer)
                    && this.player.distanceToSqr(ownerHurt) <= 576.0D) {
                if (this.attackTarget != ownerHurt) {
                    this.attackTarget = ownerHurt;
                    this.combat.setTarget(ownerHurt);
                }
            }
        }
        // 组队协战：自己没目标时，看队友在打谁 / 谁在打队友，赶去支援
        if (this.attackTarget == null) {
            Entity assist = findTeammateAssist();
            if (assist != null) {
                this.attackTarget = assist;
                this.combat.setTarget(assist);
            }
        }
        Entity enemy = this.attackTarget;
        if (enemy instanceof Player other && (other.isCreative() || other.isSpectator())) {
            // 创造 / 旁观模式的玩家打不动，别把状态机浪费在他们身上
            // （自动索敌已经过滤过，这里是给 /aiplayer attack 和 LLM 指定的目标兜底）
            this.attackTarget = null;
            enemy = null;
        }
        if (enemy != null && (!enemy.isAlive() || enemy.isRemoved())) {
            maybeTauntKill(enemy);
            this.attackTarget = null;
            enemy = null;
        }
        // 只在"根本没有目标"时才重新索敌。
        // 之前是 enemy == null || 看不见 就重新索敌：玩家打了 AI 一下绕到墙后，AI 就会
        // 把锁丢掉、换成附近的僵尸 / 别的怪，然后掉头往反方向去打 —— 看起来就是"往反方向攻击"。
        if (enemy == null) {
            // 省 CPU：没目标时不必每 tick 做一次 16 格实体查询（多 AI 同屏时这是最大的一笔常驻开销）。
            // 挨打（hurtTime>0）立刻扫，保证还手手感；平时每 4 tick 扫一次，最多 0.2 秒延迟，肉眼无感。
            // 有枪时视野放大到 32 格：枪能打 48 格，还按近战的 16 格索敌就永远发现不了远处的对枪对象
            if (this.player.hurtTime > 0 || this.player.tickCount % 4 == 0) {
                enemy = findEnemy(this.ranged.hasGun() ? 32.0D : 16.0D);
            }
        } else if (!this.player.hasLineOfSight(enemy) && this.player.distanceToSqr(enemy) > 256.0D) {
            // 既看不见、又离得很远（>16 格）：这才是真跟丢了，允许换目标
            this.attackTarget = null;
            enemy = findEnemy(this.ranged.hasGun() ? 32.0D : 16.0D);
        }
        if (enemy == null) {
            this.combat.clear();
            this.crystal.reset();
            this.blockTactics.reset();
            // 敌人已经死了 / 找不到了：把弓放下，别继续拉着蓄力（否则烧死的僵尸还会被补一箭）
            this.ranged.release();
            return false;
        }
        double dist = Math.sqrt(this.player.distanceToSqr(enemy));
        // 非混战时对怪物不追远：怪已经跑到 10 格外、又不是刚打过我们的那只，就放它走。
        // 以前会一路追着僵尸/骷髅满地图跑，看起来就像"AI 一直在打架"。
        if (!ffaMode() && enemy instanceof Monster && dist > 10.0D
                && enemy != this.player.getLastHurtByMob()) {
            this.attackTarget = null;
            this.combat.clear();
            this.crystal.reset();
            this.blockTactics.reset();
            this.ranged.release();
            return false;
        }
        // 没有近战武器、只有枪：枪就是主武器，贴身也要开火（不再让近战分支拦下来用拳头）
        boolean gunOnly = this.ranged.hasGun() && !this.ranged.hasMeleeWeapon();

        // 主动放方块：被近身 / 低血时堵路、搭塔抢高点（有冷却和概率限制，不会乱放）。
        // 只对怪物用：和玩家 / AI 玩家对枪时突然掏方块铺墙，看起来像作弊，也挡自己的枪线。
        if (!(enemy instanceof Player) && this.blockTactics.tick(enemy, in)) {
            setState(AiState.COMBAT);
            return true;
        }

        // 水晶 / 锚这些"摆东西"的打法只对**真玩家**开：AI 之间对枪（混战 / 队伍战）不该
        // 突然掏黑曜石和重生锚，既不是玩家想看的枪战，也会把场地炸得乱七八糟。
        boolean realEnemy = enemy instanceof Player && !(enemy instanceof AiPlayer);

        // 水晶 PvP：只对真玩家用 —— 打怪 / 打 AI 不掏水晶，枪和近战就够了。
        if (this.profile.crystalPvp && realEnemy && dist <= 6.5D
                && CrystalPvpController.hasCrystalKit(this.player)) {
            // 水晶 PvP 前自动把不死图腾塞附手（如果背包里有）
            equipTotemOffhand();
            this.combat.clear();
            if (this.crystal.tick(enemy)) {
                setState(AiState.CRYSTAL);
                return true;
            }
        } else {
            this.crystal.reset();
        }

        // 锚 PvP：重生锚 + 萤石，主世界引爆（爆炸毁地形，只对真玩家用）
        if (this.profile.anchorPvp && realEnemy && dist <= 7.0D
                && AnchorPvpController.hasKit(this.player)) {
            this.combat.clear();
            if (this.anchor.tick(enemy)) {
                setState(AiState.CRYSTAL);
                return true;
            }
        } else {
            this.anchor.reset();
        }

        // 风弹重锤：2.5~14 格起手（风弹/珍珠上天 -> 空中每 tick 瞄人 -> 重锤摔劈），命中自动免摔。
        // 条件是 hasKit() || isActive()：连招一旦起手就必须让它跑完 ——
        // 鞘翅一穿到胸甲槽、风弹一扔出去，背包里就没这东西了，
        // 只认 hasKit() 的话下一个 tick 大脑就撒手不管，AI 在半空中失去操控直接掉下来。
        if (this.profile.windMace && (this.windMace.isActive()
                || (dist <= 14.0D && WindChargeMaceController.hasKit(this.player)))) {
            this.combat.clear();
            if (this.windMace.tick(enemy)) {
                setState(AiState.COMBAT);
                return true;
            }
        } else if (!this.windMace.isActive()) {
            this.windMace.reset();
        }

        // 鞘翅长矛：6~24 格突进（鞘翅爬升滑翔俯冲 + 原版长矛贯穿）
        if (this.profile.elytraSpear && (this.elytra.isActive()
                || ElytraSpearController.hasKit(this.player))) {
            this.combat.clear();
            if (this.elytra.tick(enemy)) {
                setState(AiState.COMBAT);
                return true;
            }
        } else if (!this.elytra.isActive()) {
            this.elytra.reset();
        }

        // 组队分工：有队友协同且自己是"拉扯位"时，不硬冲 —— 保持 5~8 格放风筝，只打玩家
        if (enemy instanceof Player && isHarasser() && countNearbyTeammates(24.0D) > 0) {
            harassTick(in, enemy, dist);
            setState(AiState.COMBAT);
            return true;
        }

        // 有枪：优先开火，不再依赖配置里的「远程」开关 —— 背包里有枪就该会扣扳机。
        // 顺序也必须放在近战前面：以前先近战后远程，AI 一冲脸就永远停在 2 格内挥剑，枪一次都打不响。
        if (this.ranged.hasGun() && dist <= this.ranged.maxRange()
                && canShootLine(enemy) && this.ranged.tick(enemy)) {
            setState(AiState.RANGED);
            return true;
        }

        // 近战
        if (dist <= CombatController.ENGAGE_RANGE && !gunOnly) {
            this.combat.setTarget(enemy);
            if (this.combat.tick()) {
                this.ranged.release();
                setState(AiState.COMBAT);
                return true;
            }
        }
        // 远程：必须看得见目标才射（否则会隔着墙 / 对着已经消失的目标拉弓放箭）；
        // 射程按武器算：有枪（TaCZ）能打到 48 格，只有弓弩时 14 格以外箭的抛物线弯得厉害，
        // 多半撞地形浪费箭 —— 干脆追近了再射。
        if ((this.profile.ranged || gunOnly) && dist >= (gunOnly ? 1.5D : 4.0D)
                && dist <= this.ranged.maxRange() && this.ranged.hasRanged()
                && canShootLine(enemy) && this.ranged.tick(enemy)) {
            setState(AiState.RANGED);
            return true;
        }
        this.ranged.release();
        // 追不上就扔末影珍珠直接拉近（隔着墙绕不出去 / 寻路一直卡住时）
        if (this.combat.chasePearl(enemy, dist)) {
            setState(AiState.COMBAT);
            return true;
        }
        // 追
        if (dist > 2.0D) {
            // 末影龙（以及它的头/身体零件）在天上：地板寻路永远走不到 dragon.blockPosition()，
            // 以前这里会把整 tick 交还给底层分支，表现就是"打龙时寻路失效、AI 站着不动/乱逛"。
            // 对飞行目标只做水平接近 + 视线跟随，交给 BossController 的输出位或远程处理垂直方向。
            if (isFlyingTarget(enemy)) {
                moveTowardDirect(in, enemy.getX(), enemy.getZ());
                setState(AiState.MOVING);
                return true;
            }
            this.path.setGoal(enemy.blockPosition());
            if (this.path.tick()) {
                // 到了 / 判定走不到：不要 return false 把控制权交回底层分支 ——
                // 底层的生存 / 生计分支会覆盖输入，看起来就是 AI 发呆。
                // 先朝目标方向硬走，配合卡住检测让搭路分支接手。
                moveTowardDirect(in, enemy.getX(), enemy.getZ());
            }
            setState(AiState.MOVING);
            return true;
        }
        return false;
    }

    /**
     * 是否是"混战"模式：全局 AI 混战（{@code aiDuel}）、所在队伍在参战名单（{@code aiWarTeams}）、
     * 或自己开了 PvP。只有这种模式下 AI 才会高频主动找人打架；平时以自保、还手为主。
     */
    boolean ffaMode() {
        AiPlayerConfig cfg = AiPlayerConfig.get();
        return cfg.aiDuel || cfg.teamAtWar(this.profile.team)
                || this.allowPlayerPvp || this.profile.alwaysPvp;
    }

    /** 末影龙本体 / 龙头等零件：地板寻路对它们无效 */
    private static boolean isFlyingTarget(Entity enemy) {
        return enemy instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                || enemy instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
    }

    /** 朝水平坐标直线走（不经过 A*）：用于飞行目标 / 寻路判定失败时的兜底 */
    private void moveTowardDirect(AiInput in, double x, double z) {
        double dx = x - this.player.getX();
        double dz = z - this.player.getZ();
        if (dx * dx + dz * dz > 1.0E-4D) {
            in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        in.pitch = 0.0F;
        in.forward = true;
        in.sprint = true;
    }

    /** 目标死了之后调用：确实是我们的最后一击、且对方是 AI 玩家时，嘲讽一句 */
    void maybeTauntKill(Entity victim) {
        if (!this.profile.taunt || !(victim instanceof AiPlayer victimAi)) {
            return;
        }
        // 最后一击必须是我们打的，抢人头不算
        if (victimAi.getLastHurtByMob() != this.player) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastTauntAt < TAUNT_COOLDOWN_MS) {
            return;
        }
        this.lastTauntAt = now;
        // 玩家自定义台词优先（GUI 里可以改）：%s 替换成被击败者名字
        java.util.List<String> custom = this.profile.tauntLines();
        if (!custom.isEmpty()) {
            String line = custom.get(this.RNG.nextInt(custom.size()));
            String victimName = victimAi.getName().getString();
            this.chat.sayEvent(Component.literal(line.contains("%s") ? line.replace("%s", victimName)
                    : line + " " + victimName));
            return;
        }
        String key = TAUNT_KEYS[this.RNG.nextInt(TAUNT_KEYS.length)];
        // 走 sayEvent 而不是 say：say 有 8 秒硬冷却，战斗中状态语音刚说过话就把嘲讽吃掉了
        this.chat.sayEvent(Component.translatable(key, victimAi.getName().getString()));
    }

    /** 搭路时顺手打一下旁边的敌人 */
    void attackNearbyWhileBridging(AiInput in) {
        Entity enemy = findEnemy(3.0D);
        if (enemy == null) {
            return;
        }
        in.attackEntity = true;
        in.attackTarget = enemy;
        Vec3 point = enemy.position().add(0.0D, enemy.getBbHeight() * 0.7D, 0.0D);
        this.player.lookAt(point, 25.0F);
    }

    /** 水晶 PvP 前自动把背包里的不死图腾塞到附手；附手已有图腾则不动 */
    void equipTotemOffhand() {
        Inventory inv = this.player.getInventory();
        int offhandSlot = 40; // Inventory.SLOT_OFFHAND
        ItemStack off = inv.getItem(offhandSlot);
        if (off != null && !off.isEmpty() && off.getItem() == net.minecraft.world.item.Items.TOTEM_OF_UNDYING) {
            return; // 已经在附手了
        }
        // 找图腾（整个背包，包括快捷栏）
        int totemSlot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && !s.isEmpty() && s.getItem() == net.minecraft.world.item.Items.TOTEM_OF_UNDYING) {
                totemSlot = i;
                break;
            }
        }
        if (totemSlot < 0) return;
        // 交换：图腾 ↔ 附手当前物品
        ItemStack totem = inv.getItem(totemSlot);
        inv.setItem(totemSlot, off);
        inv.setItem(offhandSlot, totem);
    }

    Entity findEnemy(double range) {
        // 只有混战（aiDuel）或所在队伍参战（aiWarTeams）时才算真正的 FFA：
        // 这时 AI 才会高频主动交战；平时对怪物只还手、不主动远征。
        boolean ffa = ffaMode();
        // 不饿就不打猎：动物是食物来源，不该变成"路过就杀"的日常打架
        boolean hungry = this.food.bestFoodSlotAnywhere() < 0;
        AABB box = this.player.getBoundingBox().inflate(range);
        List<LivingEntity> list = this.player.level().getEntitiesOfClass(LivingEntity.class, box);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity e : list) {
            if (e == this.player || !e.isAlive() || e.isInvulnerable()) {
                continue;
            }
            if (this.player.isAlliedTo(e)) {
                continue;
            }
            // 组队系统：同队的 AI 永远不打
            if (sameTeam(e)) {
                continue;
            }
            boolean pvp = this.allowPlayerPvp || this.profile.alwaysPvp;
            if (e instanceof AiPlayer otherAi) {
                // AI 对 AI：
                // - 自己开过 PvP（allowPlayerPvp / alwaysPvp）就可以还手 / 主动打
                // - 全局 aiDuel 打开时所有 AI 互相视为敌人
                // - 自己所在的队伍在「参战队伍」名单里时，其他队伍（同队的上面已排除）都是敌人
                boolean duel = ffa;
                if (!pvp && !duel && otherAi != this.attackTarget) {
                    continue;
                }
            } else if (e instanceof Player other) {
                // 注意这里是 else if：AiPlayer 也是 Player，上面的分支已经放行过它了，
                // 再走这里会被 !pvp 判掉（aiDuel 打开也打不起来）
                if (other.isCreative() || other.isSpectator()) {
                    continue;
                }
                // 主人永远不打；其他玩家只有明确开过 PvP 才打
                if (isOwner(e) || !pvp) {
                    continue;
                }
            } else if (e instanceof net.minecraft.world.entity.monster.Monster) {
                // 敌对怪物（僵尸 / 骷髅 / 苦力怕）—— FFA 时主动清场；
                // 平时只在它先动手、或者已经贴到脸上时才还手，不再满地图追怪。
                if (!ffa && e != this.player.getLastHurtByMob()
                        && this.player.distanceToSqr(e) > 8.0D * 8.0D) {
                    continue;
                }
            } else if (e instanceof net.minecraft.world.entity.npc.villager.Villager
                    || e instanceof net.minecraft.world.entity.animal.golem.IronGolem) {
                // 村民系 + 铁傀儡 — 不能主动杀（玩家需要交易和保护）
                continue;
            } else if (e instanceof net.minecraft.world.entity.animal.Animal) {
                // 被动动物（牛 / 羊 / 猪 / 鸡）：只有 hunt=true 且真的缺食物时才打
                if (!this.profile.hunt || !hungry) {
                    continue;
                }
            } else {
                // 其他杂项（Ambient / WaterCreature 等）默认不主动打
                continue;
            }






























            double score = this.player.distanceToSqr(e);
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    /** 当前攻击目标（队友协战时读取） */
    public Entity currentTarget() {
        return this.attackTarget;
    }

    /** 对方是否与我同队（双方配置了相同且非空的 team） */
    boolean sameTeam(Entity e) {
        if (!(e instanceof AiPlayer other) || other == this.player) {
            return false;
        }
        String mine = this.profile.team;
        return mine != null && !mine.isBlank() && mine.equals(other.profile().team);
    }

    /** 队友协战：队友正在打谁 / 谁在打队友（32 格内），就帮着打谁 */
    Entity findTeammateAssist() {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank()) {
            return null;
        }
        boolean pvp = this.allowPlayerPvp || this.profile.alwaysPvp;
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate == this.player || !mate.isAlive() || mate.brain() == null) {
                continue;
            }
            if (!mine.equals(mate.profile().team)) {
                continue;
            }
            if (this.player.distanceToSqr(mate) > 1024.0D) {
                continue; // 32 格外的不支援，各打各的
            }
            // 队友在打谁就跟上（同队 / 主人 / 创造模式的不碰；打 AI 还要求自己开了 PvP）
            Entity mateTarget = mate.brain().currentTarget();
            if (mateTarget != null && mateTarget.isAlive() && !sameTeam(mateTarget) && !isOwner(mateTarget)) {
                if (mateTarget instanceof Player p && (p.isCreative() || p.isSpectator())) {
                    // 跳过
                } else if (!(mateTarget instanceof AiPlayer) || pvp) {
                    return mateTarget;
                }
            }
            // 谁在打队友就还击谁
            LivingEntity mateHurtBy = mate.getLastHurtByMob();
            if (mateHurtBy != null && mateHurtBy.isAlive() && mateHurtBy != this.player
                    && mate.tickCount - mate.getLastHurtByMobTimestamp() < 100
                    && !sameTeam(mateHurtBy) && !isOwner(mateHurtBy)
                    && (!(mateHurtBy instanceof AiPlayer) || pvp)) {
                return mateHurtBy;
            }
        }
        return null;
    }

    /**
     * 分工：同队 AI 按名字排序，排名偶数位当"输出位"（直冲近战），奇数位当"拉扯位"（放风筝）。
     * 保证一队至少有一个输出，不会全员绕圈没人砍。
     */
    boolean isHarasser() {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank()) {
            return false;
        }
        int before = 0;
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate == this.player || !mate.isAlive() || !mine.equals(mate.profile().team)) {
                continue;
            }
            if (mate.getName().getString().compareTo(this.profile.name) < 0) {
                before++;
            }
        }
        return before % 2 == 1;
    }

    /** range 格内同队且活着的队友数（不含自己） */
    int countNearbyTeammates(double range) {
        String mine = this.profile.team;
        if (mine == null || mine.isBlank()) {
            return 0;
        }
        int n = 0;
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate != this.player && mate.isAlive() && mine.equals(mate.profile().team)
                    && this.player.distanceToSqr(mate) <= range * range) {
                n++;
            }
        }
        return n;
    }

    /**
     * 拉扯位：保持 {@value #HARASS_NEAR}~{@value #HARASS_FAR} 格放风筝。
     * 有弓就拉远了射；贴太近面朝敌人倒退（真人放风筝也是这样，不背对目标挨刀）；
     * 甜区里切向绕圈，方向定期随机换。
     */
    void harassTick(AiInput in, Entity enemy, double dist) {
        this.combat.clear();
        if (this.profile.ranged && dist >= 4.0D && dist <= 14.0D && this.ranged.hasRanged()
                && canShootLine(enemy) && this.ranged.tick(enemy)) {
            return;
        }
        this.ranged.release();
        double dx = enemy.getX() - this.player.getX();
        double dz = enemy.getZ() - this.player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) {
            len = 1.0D;
        }
        // 一直面向敌人：侧移绕圈也盯着对方
        this.player.lookAt(enemy.getX(), enemy.getEyeY(), enemy.getZ(), 25.0F);
        in.sprint = dist > HARASS_FAR;
        if (dist < HARASS_NEAR) {
            in.backward = true;
            if (dist < 3.2D) {
                // 贴脸了才挥一刀，平时不硬砍
                in.attackEntity = true;
                in.attackTarget = enemy;
            }
        } else if (dist > HARASS_FAR) {
            in.forward = true;
        } else {
            if (--this.harassDirTicks <= 0) {
                this.harassDir = RNG.nextBoolean() ? 1 : -1;
                this.harassDirTicks = 30 + RNG.nextInt(30);
            }
            if (this.harassDir > 0) {
                in.right = true;
            } else {
                in.left = true;
            }
        }
        // 绕圈 / 后撤撞墙时跳一下脱困
        if (this.stuckTicks > 10 && this.player.onGround()) {
            in.jump = true;
        }
    }


    /**
     * 允许攻击吗？玩家只有在"先动手打过 AI"（已成为 {@code attackTarget}）时才允许还击，
     * 主人永远不行；怪物 / 动物不受限制。
     * <p>
     * LLM 有时会自作主张挑人打架，这里兜一道底，从机制上保证不主动 PvP。
     */
    boolean allowedToAttack(Entity target) {
        if (target instanceof Player p) {
            return target == this.attackTarget && !isOwner(p);
        }
        return true;
    }

    /** 弓箭能不能真射到：从 AI 眼部对目标眼睛和躯干各打一条实心射线，两条全通才放箭。
     *  hasLineOfSight 只查中心线，半格/台阶/树叶下面能"看见"但箭就是撞方块 —— 表现为一直射不到。 */
    boolean canShootLine(net.minecraft.world.entity.Entity enemy) {
        net.minecraft.world.phys.Vec3 from = this.player.getEyePosition();
        net.minecraft.world.phys.Vec3 chest = enemy.position().add(0, enemy.getBbHeight() * 0.5D, 0);
        net.minecraft.world.phys.Vec3[] points = { enemy.getEyePosition(), chest };
        for (net.minecraft.world.phys.Vec3 to : points) {
            net.minecraft.world.phys.BlockHitResult hit = this.player.level().clip(
                    new net.minecraft.world.level.ClipContext(from, to,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, this.player));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                return false;
            }
        }
        return true;
    }

}