package com.aiplayer.ai.combat;

import com.aiplayer.ai.AiInput;
import com.aiplayer.ai.AiLevel;
import com.aiplayer.ai.path.AStarPathFinder;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.InvUtil;
import com.aiplayer.util.PlaceUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

/**
 * 水晶 PvP（CPvP）：放黑曜石 -> 放末地水晶 -> 打爆。
 * <p>
 * 全程使用原版放置/攻击流程（{@code useItemOn} / {@code attack}），爆炸伤害、护甲减伤、
 * 盾牌与图腾都由原版结算，所以不会出现“无敌连爆”这种外挂行为。
 * <p>
 * 本版本的关键优化：
 * <ul>
 *   <li>所有槽位切换都走 {@link InventoryLock}，不再和其他控制器抢快捷栏；</li>
 *   <li>残血且没有图腾时直接放弃水晶 PvP，交回近战/逃生，不会自杀式连爆；</li>
 *   <li>离水晶太近会先后退并举盾，再引爆（拟人化的“拉开距离”）；</li>
 *   <li>爆完立刻找新锚点（强度 ≥3 才连爆），强度 4 无冷却；</li>
 *   <li>锚点被补掉 / 材料用完会自动重选或退出，不会卡死。</li>
 * </ul>
 */
public class CrystalPvpController {
    private static final Random RNG = new Random();
    private static final double MAX_RANGE = 6.5D;
    /** 太近引爆会把自己炸成重伤的距离 */
    private static final double SAFE_BLAST_DISTANCE = 3.0D;
    /** 残血且没有图腾时不做水晶 PvP */
    private static final float MIN_SELF_HEALTH = 7.0F;

    private enum Stage {
        IDLE, OBSIDIAN, CRYSTAL, DETONATE
    }

    private final AiPlayer player;
    private Stage stage = Stage.IDLE;
    private BlockPos anchor;
    private int stageTicks;
    private int cooldown;

    public CrystalPvpController(AiPlayer player) {
        this.player = player;
    }

    public void reset() {
        this.stage = Stage.IDLE;
        this.anchor = null;
        this.stageTicks = 0;
        // 退出时主动还锁：否则别的控制器得等锁超时（40 tick）才能动快捷栏
        this.player.inventoryLock().release("crystal");
    }

    public boolean isActive() {
        return this.stage != Stage.IDLE;
    }

    public BlockPos anchor() {
        return this.anchor;
    }

    /** @return true 表示正在打水晶 PvP（输入已被接管） */
    public boolean tick(Entity target) {
        AiProfile profile = this.player.profile();
        if (profile == null) {
            return false;
        }
        AiLevel level = profile.level();
        AiInput in = this.player.input();
        if (!level.crystalPvp || !profile.crystalPvp || target == null || !target.isAlive()) {
            reset();
            return false;
        }
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        double dist = Math.sqrt(this.player.distanceToSqr(target));
        if (dist > MAX_RANGE) {
            reset();
            return false;
        }
        // 保命优先：残血又没图腾就别玩火了
        if (!selfSafe()) {
            reset();
            this.cooldown = 40;
            return false;
        }
        this.stageTicks++;

        // 1) 附近已经有水晶 -> 直接打爆（真人 CPvP 的第一步也是打现成的）
        Entity crystal = nearbyCrystal(target);
        if (crystal != null) {
            this.stage = Stage.DETONATE;
            return detonate(crystal, in, level, target);
        }

        switch (this.stage) {
            case IDLE -> {
                this.anchor = findAnchor(target);
                if (this.anchor == null) {
                    // 找不到落脚点，先交回近战，过一会儿再试
                    this.cooldown = level.id >= 3 ? 10 : 25;
                    return false;
                }
                this.stage = Stage.OBSIDIAN;
                this.stageTicks = 0;
            }
            case OBSIDIAN -> {
                if (this.anchor == null || !PlaceUtil.isReplaceable(this.player.level(), this.anchor)) {
                    // 黑曜石已经放好了（或被人补掉）
                    this.stage = this.anchor == null ? Stage.IDLE : Stage.CRYSTAL;
                    this.stageTicks = 0;
                    return true;
                }
                if (placeObsidian(in)) {
                    this.stage = Stage.CRYSTAL;
                    this.stageTicks = 0;
                } else if (this.stageTicks > 20) {
                    this.anchor = null;
                    this.stage = Stage.IDLE;
                    this.cooldown = 10;
                }
            }
            case CRYSTAL -> {
                if (this.anchor == null) {
                    this.stage = Stage.IDLE;
                    return false;
                }
                BlockPos crystalPos = this.anchor.above();
                if (!placeCrystal(in, crystalPos)) {
                    this.stage = Stage.IDLE;
                    this.anchor = null;
                    this.cooldown = 10;
                    return false;
                }
                this.stage = Stage.DETONATE;
                this.stageTicks = 0;
            }
            case DETONATE -> {
                Entity c = nearbyCrystal(target);
                if (c == null) {
                    // 水晶没了（被自己/别人打掉了）：高强度立刻接着来一组
                    this.anchor = null;
                    this.stage = Stage.IDLE;
                    this.cooldown = level.id >= 3 ? 2 : 8;
                    return true;
                }
                return detonate(c, in, level, target);
            }
        }
        return this.stage != Stage.IDLE;
    }

    // ------------------------------------------------------------------
    // 各阶段动作
    // ------------------------------------------------------------------

    private boolean placeObsidian(AiInput in) {
        int slot = InvUtil.findHotbarSlot(this.player, Items.OBSIDIAN);
        if (slot < 0) {
            // 黑曜石在主背包：先搬到快捷栏（优先空格），搬不动才放弃
            slot = InvUtil.pullToHotbar(this.player, Items.OBSIDIAN);
            if (slot < 0) {
                return false;
            }
        }
        if (!this.player.selectSlot("crystal", InventoryLock.PRIO_CRYSTAL, slot)) {
            return false;
        }
        in.selectedSlot = slot;
        this.player.lookAt(center(this.anchor), 40.0F);
        return PlaceUtil.placeAt(this.player, this.anchor, Direction.UP);
    }

    private boolean placeCrystal(AiInput in, BlockPos crystalPos) {
        int slot = InvUtil.findHotbarSlot(this.player, Items.END_CRYSTAL);
        if (slot < 0) {
            slot = InvUtil.pullToHotbar(this.player, Items.END_CRYSTAL);
            if (slot < 0) {
                return false;
            }
        }
        if (!this.player.selectSlot("crystal", InventoryLock.PRIO_CRYSTAL, slot)) {
            return false;
        }
        in.selectedSlot = slot;
        this.player.lookAt(center(crystalPos), 45.0F);
        // 末地水晶只能放在黑曜石/基岩上，这里就是让原版去判定；mustBeBlock=false
        boolean placed = PlaceUtil.placeItemAt(this.player, crystalPos, Direction.UP);
        // 血量不高先举盾，准备吃自己的爆炸
        if (this.player.getHealth() < 14.0F && this.player.getOffhandItem().getItem() == Items.SHIELD) {
            in.useOffhand = true;
        }
        return placed;
    }

    /**
     * 引爆：站得太近会先后退 + 举盾，然后再打。
     * 攻击本身走原版 {@code attack()}，伤害由原版结算。
     */
    private boolean detonate(Entity crystal, AiInput in, AiLevel level, Entity target) {
        // 中途血量掉下来（被弓箭/近战打了）也要立刻收手，不能进了 DETONATE 就硬炸
        if (!selfSafe()) {
            reset();
            this.cooldown = 40;
            return false;
        }
        Vec3 point = crystal.position().add(0.0D, crystal.getBbHeight() * 0.5D, 0.0D);
        double selfDist = this.player.distanceToSqr(crystal);
        boolean risky = selfDist < SAFE_BLAST_DISTANCE * SAFE_BLAST_DISTANCE;

        // 有盾就先举盾：高强度会先拉开距离再引爆，低强度至少别裸吃自己的爆炸
        if (risky && this.player.getOffhandItem().getItem() == Items.SHIELD) {
            in.useOffhand = true;
        }
        if (risky && level.id >= 2) {
            // 拉开距离：背对水晶后退
            double dx = this.player.getX() - crystal.getX();
            double dz = this.player.getZ() - crystal.getZ();
            if (dx * dx + dz * dz > 1.0E-4D) {
                in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            }
            in.backward = true;
            // 还没走出爆炸范围就继续退
            if (selfDist < 4.0D) {
                return true;
            }
        }

        this.player.lookAt(point, 60.0F);
        double dist = Math.sqrt(this.player.distanceToSqr(crystal));
        if (dist > 3.0D) {
            // 打不到就先跑近
            in.forward = true;
            return true;
        }
        in.attackEntity = true;
        in.attackTarget = crystal;
        if (this.player.getAttackStrengthScale(1.0F) >= 0.9F) {
            // 命中判定通过才进入下一轮
            this.stage = Stage.IDLE;
            this.anchor = null;
            this.cooldown = level.id >= 4 ? 0 : (level.id >= 3 ? 3 : 6);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /** 血量健康，或者有图腾兜底，才允许继续玩水晶 */
    private boolean selfSafe() {
        return this.player.getHealth() >= MIN_SELF_HEALTH || hasTotem();
    }

    private boolean hasTotem() {
        return this.player.getMainHandItem().getItem() == Items.TOTEM_OF_UNDYING
                || this.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING
                || InvUtil.has(this.player, Items.TOTEM_OF_UNDYING);
    }

    /** 在目标脚下（或紧邻）找一块可以换成黑曜石的位置 */
    private BlockPos findAnchor(Entity target) {
        BlockPos base = target.blockPosition();
        // 平地 CPvP 的标准锚点是「目标脚部层的邻居」：空气格（可替换）、下面是地面（有支撑）。
        // 注意不能选 base（目标身体所在格）—— 黑曜石放进实体身体必被原版阻挡，放置失败
        // 会让 OBSIDIAN 阶段空转 20 tick，期间 AI 只 lookAt 不移动，看起来就是"卡住不动"。
        BlockPos[] candidates = {
                base.relative(Direction.NORTH),
                base.relative(Direction.SOUTH),
                base.relative(Direction.WEST),
                base.relative(Direction.EAST),
                // 兜底：目标悬空时脚下那格
                base.below()
        };
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : candidates) {
            if (!PlaceUtil.isReplaceable(this.player.level(), pos)) {
                continue;
            }
            if (!AStarPathFinder.solid(this.player.level(), pos.below())) {
                continue;
            }
            // 必须在放置距离内（4.4 格）
            double d = this.player.getEyePosition().distanceToSqr(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D);
            if (d > 19.0D) {
                continue;
            }
            // 越靠近目标越好（水晶离得近伤害才足）
            double score = d + target.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }
        return best;
    }

    private Entity nearbyCrystal(Entity target) {
        double range = 4.0D + (this.player.profile() != null ? this.player.profile().levelId : 0) * 0.4D;
        // 同时覆盖“目标周围”和“自己周围”：贴脸时水晶常放在自己脚下，
        // 只按 target 的包围盒搜会漏掉自己刚放的那颗，导致水晶永远引爆不了
        AABB fromTarget = target.getBoundingBox().inflate(range);
        AABB fromSelf = this.player.getBoundingBox().inflate(range);
        AABB box = new AABB(
                Math.min(fromTarget.minX, fromSelf.minX),
                Math.min(fromTarget.minY, fromSelf.minY),
                Math.min(fromTarget.minZ, fromSelf.minZ),
                Math.max(fromTarget.maxX, fromSelf.maxX),
                Math.max(fromTarget.maxY, fromSelf.maxY),
                Math.max(fromTarget.maxZ, fromSelf.maxZ));
        List<Entity> list = this.player.level().getEntitiesOfClass(Entity.class, box);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : list) {
            if (e == this.player || e == target) {
                continue;
            }
            if (!e.getClass().getSimpleName().contains("Crystal")) {
                continue;
            }
            if (!this.player.isWithinEntityInteractionRange(e, 1.0D)) {
                continue;
            }
            double d = this.player.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        // 小概率“看走眼”，低强度偶尔漏掉一颗水晶
        if (best != null && this.player.profile() != null
                && RNG.nextFloat() < this.player.profile().level().mistakeChance * 0.1F) {
            return null;
        }
        return best;
    }

    /** 手上是不是水晶 PvP 装备 */
    public static boolean hasCrystalKit(AiPlayer player) {
        ItemStack crystals = InvUtil.find(player, Items.END_CRYSTAL);
        ItemStack obsidian = InvUtil.find(player, Items.OBSIDIAN);
        return !crystals.isEmpty() && !obsidian.isEmpty();
    }
}