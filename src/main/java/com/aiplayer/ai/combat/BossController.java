package com.aiplayer.ai.combat;

import com.aiplayer.ai.AiInput;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.entity.AiPlayerManager;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.InvUtil;
import com.aiplayer.util.PlaceUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 末影龙团队战控制器。
 * <p>
 * AI 一旦身处末地且场上还有存活末影龙，本控制器就接管它的输入，按真人团队的打法行动：
 * <ol>
 *   <li><b>掉虚空自救</b>：y 掉到 -30 以下时用鞘翅滑回来，没鞘翅就朝主岛中心扔末影珍珠；</li>
 *   <li><b>残血回撤</b>：血量低于 30% 吃金苹果并远离龙与水晶，回到 55% 再参战；</li>
 *   <li><b>先拆水晶</b>：末影水晶会给龙回血，必须先清掉 —— 有弓弩就抛物线射，
 *       够不到就沿黑曜石柱垫高爬上去近战拆；拆完被爆炸弹飞先退回柱子背侧缓一下；</li>
 *   <li><b>集火打头</b>：龙落下栖息时从侧面接近龙头，攻击 {@code dragon.head} 这个零件 ——
 *       原版 {@code EnderDragon.hurt} 里非头零件伤害要 ÷4，所以打头就是 4 倍伤害；</li>
 *   <li><b>飞行跟打</b>：龙起飞后用弓弩预判射击，没远程就保持 8~16 格盘旋躲龙息；</li>
 *   <li>末影人一律不主动招惹。</li>
 * </ol>
 * 同队按名字排序 %3 稳定分工：0=输出、1=拆水晶、2=补给（残血时给队友送食物）。
 * <p>
 * 所有实体范围查询都做了 10 tick 缓存（i3 + 11GB 的机器上，多 AI 同屏每 tick 遍历实体是大开销）。
 */
public class BossController {
    /** 龙/水晶/龙息的扫描间隔（tick） */
    private static final int SCAN_INTERVAL = 10;
    /** 找龙的范围（格） */
    private static final double DRAGON_RANGE = 128.0D;
    /** 找水晶的范围（格） */
    private static final double CRYSTAL_RANGE = 96.0D;
    /** 单根柱子垫高的超时（tick）：超了就放弃近战拆水晶，交回打龙 */
    private static final int PILLAR_TIMEOUT_TICKS = 700;
    /** 残血阈值 / 回撤到多少血才回战场 */
    private static final float LOW_HEALTH_RATIO = 0.30F;
    /** 珍珠朝主岛抛的最小间隔（tick），避免一口气把一叠珍珠扔光 */
    private static final int VOID_PEARL_COOLDOWN = 20;

    private final AiPlayer player;
    /** 正在打龙的独立远程控制器：BossController 在 tick 链里优先于 AiBrain 的战斗分支，
     *  所以这里自己持一个实例，不去抢 AiBrain 那个（避免依赖它的公开接口）。 */
    private final RangedController ranged;

    private boolean engaged;
    private int stageTicks;

    // ---- 扫描缓存 ----
    private EnderDragon dragonCache;
    private int dragonCacheTicks;
    private EndCrystal crystalCache;
    private int crystalCacheTicks;
    private Entity breathCache;
    private int breathCacheTicks;

    // ---- 垫高拆水晶 ----
    private BlockPos pillarColumn;
    private int pillarBaseY;
    private int pillarTicks;

    // ---- 机会主义移动 ----
    private int strafeDir = 1;
    private int strafeTicks;
    private int voidCooldown;

    public BossController(AiPlayer player) {
        this.player = player;
        this.ranged = new RangedController(player);
    }

    public void reset() {
        this.engaged = false;
        this.stageTicks = 0;
        this.dragonCache = null;
        this.dragonCacheTicks = 0;
        this.crystalCache = null;
        this.crystalCacheTicks = 0;
        this.breathCache = null;
        this.breathCacheTicks = 0;
        this.pillarColumn = null;
        this.pillarTicks = 0;
        this.voidCooldown = 0;
        this.ranged.release();
        this.player.inventoryLock().release("boss");
    }

    /** 队长喊"打龙"时由 AiBrain 调用（同队一起） */
    public void engage() {
        this.engaged = true;
    }

    public boolean engaged() {
        return this.engaged;
    }

    public boolean inEnd() {
        return this.player.level().dimension() == Level.END;
    }

    /** 是否正处于（或即将进入）末影龙战：在末地 + 场上还有活龙 */
    public boolean isActive() {
        return inEnd() && dragon() != null;
    }

    public EnderDragon dragon() {
        if (this.dragonCache != null && (this.dragonCache.isRemoved() || this.dragonCache.isDeadOrDying())) {
            this.dragonCache = null;
        }
        if (this.dragonCacheTicks > 0) {
            this.dragonCacheTicks--;
            return this.dragonCache;
        }
        this.dragonCacheTicks = SCAN_INTERVAL;
        AABB box = this.player.getBoundingBox().inflate(DRAGON_RANGE);
        List<EnderDragon> list = this.player.level().getEntitiesOfClass(EnderDragon.class, box);
        EnderDragon best = null;
        double bestDist = Double.MAX_VALUE;
        for (EnderDragon d : list) {
            if (d.isRemoved() || d.isDeadOrDying()) {
                continue;
            }
            if (d.getPhaseManager().getCurrentPhase().getPhase().getId() == EnderDragonPhase.DYING.getId()) {
                continue;   // 已经在播死亡动画，不用打了
            }
            double dist = this.player.distanceToSqr(d);
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        this.dragonCache = best;
        return best;
    }

    /**
     * @return true 表示本 tick 的输入已被末影龙战接管
     */
    public boolean tick(AiInput in) {
        var profile = this.player.profile();
        if (profile == null || !profile.boss || !profile.combat) {
            return false;
        }
        if (!inEnd()) {
            return false;
        }
        EnderDragon dragon = dragon();
        if (dragon == null) {
            this.ranged.release();
            return false;
        }
        this.engaged = true;
        this.stageTicks++;
        if (this.voidCooldown > 0) {
            this.voidCooldown--;
        }

        // 1) 掉虚空自救（最优先，慢一秒就没了）
        if (tryVoidSave(in)) {
            return true;
        }
        // 2) 残血回撤补给
        if (this.player.getHealth() < this.player.getMaxHealth() * LOW_HEALTH_RATIO) {
            if (tryRegroup(in, dragon)) {
                return true;
            }
        }
        // 3) 补给位：给残血队友送吃的
        if (role() == ROLE_SUPPLY && trySupply(in)) {
            return true;
        }
        // 4) 先拆水晶（水晶在给龙回血）
        EndCrystal crystal = nearestCrystal();
        if (crystal != null && crystal.isAlive() && !crystal.isRemoved()) {
            if (tryCrystal(in, crystal)) {
                return true;
            }
        }
        // 5) 打龙
        return fightDragon(in, dragon);
    }

    // ------------------------------------------------------------------
    // 掉虚空自救
    // ------------------------------------------------------------------

    private boolean tryVoidSave(AiInput in) {
        if (this.player.onGround() || this.player.isInWater() || this.player.isInLava()) {
            return false;
        }
        if (this.player.getY() > -30.0D) {
            return false;
        }
        // 有鞘翅就展开滑回来（末地更可靠）
        if (this.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) || equipElytra()) {
            if (!this.player.isFallFlying()) {
                this.player.tryToStartFallFlying();
            }
            if (this.player.isFallFlying()) {
                lookAtPoint(in, 0.5D, 64.0D, 0.5D);
                in.pitch = -10.0F;
                in.forward = true;
                in.sprint = true;
                return true;
            }
        }
        // 没鞘翅就用末影珍珠朝主岛中心扔（飞最远的 32° 仰角）
        if (this.voidCooldown > 0) {
            return true;   // 冷却中：先保持不动等下一颗
        }
        int slot = InvUtil.findHotbarSlot(this.player, Items.ENDER_PEARL);
        if (slot < 0) {
            slot = InvUtil.pullToHotbar(this.player, Items.ENDER_PEARL);
        }
        if (slot < 0) {
            return false;
        }
        if (!this.player.selectSlot("boss", InventoryLock.PRIO_ESCAPE, slot)) {
            return false;
        }
        lookAtPoint(in, 0.5D, 64.0D, 0.5D);
        in.pitch = -32.0F;
        in.useItem = true;
        this.voidCooldown = VOID_PEARL_COOLDOWN;
        return true;
    }

    /** 把背包里的鞘翅换到胸甲槽（参考 EscapeController 的落地缓冲做法） */
    private boolean equipElytra() {
        Inventory inv = this.player.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(Items.ELYTRA)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return false;
        }
        ItemStack elytra = inv.getItem(slot);
        ItemStack old = this.player.getItemBySlot(EquipmentSlot.CHEST);
        this.player.setItemSlot(EquipmentSlot.CHEST, elytra.copy());
        inv.setItem(slot, old);
        return true;
    }

    // ------------------------------------------------------------------
    // 残血回撤
    // ------------------------------------------------------------------

    private boolean tryRegroup(AiInput in, EnderDragon dragon) {
        // 先吃金苹果（背包里有的话）
        eatGoldenApple(in);
        // 背对龙疾跑脱离，顺手举盾
        double dx = this.player.getX() - dragon.getX();
        double dz = this.player.getZ() - dragon.getZ();
        if (dx * dx + dz * dz < 1.0E-4D) {
            dx = 1.0D;
        }
        in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        in.pitch = 0.0F;
        in.forward = true;
        in.sprint = true;
        if (this.player.getOffhandItem().is(Items.SHIELD)) {
            in.useOffhand = true;
        }
        return true;
    }

    private boolean eatGoldenApple(AiInput in) {
        if (this.player.isUsingItem()) {
            return true;   // 正在吃，别打断
        }
        int slot = InvUtil.findHotbarSlot(this.player, Items.GOLDEN_APPLE);
        if (slot < 0) {
            slot = InvUtil.pullToHotbar(this.player, Items.GOLDEN_APPLE);
        }
        if (slot < 0) {
            return false;
        }
        if (!this.player.selectSlot("boss", InventoryLock.PRIO_FOOD, slot)) {
            return false;
        }
        in.selectedSlot = slot;
        in.useItem = true;
        return true;
    }

    // ------------------------------------------------------------------
    // 补给位：给残血队友送食物
    // ------------------------------------------------------------------

    private boolean trySupply(AiInput in) {
        AiPlayer mate = woundedTeammate();
        if (mate == null) {
            return false;
        }
        if (this.player.distanceToSqr(mate) > 9.0D) {
            moveToward(in, mate.getX(), mate.getZ());
            return true;
        }
        return !giveFoodTo(mate);
    }

    private AiPlayer woundedTeammate() {
        String team = this.player.profile().team;
        if (team == null || team.isBlank()) {
            return null;
        }
        AiPlayer best = null;
        double bestDist = 144.0D;   // 12 格
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate == this.player || !mate.isAlive()) {
                continue;
            }
            if (!team.equals(mate.profile().team)) {
                continue;
            }
            if (mate.getHealth() > mate.getMaxHealth() * 0.5F) {
                continue;
            }
            double d = this.player.distanceToSqr(mate);
            if (d < bestDist) {
                bestDist = d;
                best = mate;
            }
        }
        return best;
    }

    private boolean giveFoodTo(AiPlayer mate) {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            if (!s.is(Items.GOLDEN_APPLE) && !com.aiplayer.ai.survival.FoodManager.isFood(s)) {
                continue;
            }
            ItemStack give = s.copyWithCount(1);
            s.shrink(1);
            if (s.isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            // 朝队友方向丢过去（队友自己会捡）
            this.player.lookAt(mate.getX(), mate.getEyeY(), mate.getZ(), 30.0F);
            this.player.drop(give, false);
            return true;
        }
        return false;
    }

    /** 同队按名字排序 %3 稳定分工 */
    private static final int ROLE_OUTPUT = 0;
    private static final int ROLE_CRYSTAL = 1;
    private static final int ROLE_SUPPLY = 2;

    private int role() {
        String team = this.player.profile().team;
        if (team == null || team.isBlank()) {
            return ROLE_OUTPUT;
        }
        int before = 0;
        for (AiPlayer mate : AiPlayerManager.get().all()) {
            if (mate == this.player || !mate.isAlive()) {
                continue;
            }
            if (!team.equals(mate.profile().team)) {
                continue;
            }
            if (mate.getName().getString().compareTo(this.player.profile().name) < 0) {
                before++;
            }
        }
        return before % 3;
    }

    // ------------------------------------------------------------------
    // 拆水晶
    // ------------------------------------------------------------------

    private EndCrystal nearestCrystal() {
        if (this.crystalCache != null && (this.crystalCache.isRemoved() || !this.crystalCache.isAlive())) {
            this.crystalCache = null;
        }
        if (this.crystalCacheTicks > 0) {
            this.crystalCacheTicks--;
            return this.crystalCache;
        }
        this.crystalCacheTicks = SCAN_INTERVAL;
        AABB box = this.player.getBoundingBox().inflate(CRYSTAL_RANGE);
        List<EndCrystal> list = this.player.level().getEntitiesOfClass(EndCrystal.class, box);
        EndCrystal best = null;
        double bestDist = Double.MAX_VALUE;
        for (EndCrystal c : list) {
            if (c.isRemoved() || !c.isAlive()) {
                continue;
            }
            double dist = this.player.distanceToSqr(c);
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        this.crystalCache = best;
        return best;
    }

    private boolean tryCrystal(AiInput in, EndCrystal crystal) {
        // 刚被水晶爆炸弹飞：先退回柱子背侧躲一下，别贴着柱根挨第二下
        if (this.player.hurtTime > 0 && this.player.distanceToSqr(crystal) < 36.0D) {
            double dx = this.player.getX() - crystal.getX();
            double dz = this.player.getZ() - crystal.getZ();
            if (dx * dx + dz * dz < 1.0E-4D) {
                dx = 1.0D;
            }
            in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            in.forward = true;
            in.sprint = true;
            this.pillarColumn = null;
            return true;
        }
        double dist = Math.sqrt(this.player.distanceToSqr(crystal));
        // 远处用弓弩抛物线射（不用靠近柱子，最省事的拆法）
        if (dist > 5.0D && dist <= 40.0D && this.player.hasLineOfSight(crystal) && this.ranged.hasRanged()) {
            if (this.ranged.tick(crystal)) {
                return true;
            }
        }
        this.ranged.release();
        // 够不到就沿黑曜石柱垫上去近战拆
        return pillarToCrystal(in, crystal);
    }

    private boolean pillarToCrystal(AiInput in, EndCrystal crystal) {
        BlockPos cpos = crystal.blockPosition();
        double dy = cpos.getY() - this.player.getY();

        // 已经爬到位（同高且够得着）：直接砍水晶
        if (dy <= 2.0D && this.player.distanceToSqr(crystal) <= 25.0D) {
            lookAtPoint(in, crystal.getX(), crystal.getY() + crystal.getBbHeight() * 0.5D, crystal.getZ());
            if (this.player.isWithinEntityInteractionRange(crystal, 0.0D)) {
                this.pillarColumn = null;
                in.attackEntity = true;
                in.attackTarget = crystal;
            } else {
                moveToward(in, crystal.getX(), crystal.getZ());
            }
            return true;
        }

        // 锁一根紧挨水晶、朝我这一侧的落脚列（避免把方块塞进黑曜石柱里）
        if (this.pillarColumn == null) {
            this.pillarColumn = pickPillarBase(cpos);
            this.pillarTicks = 0;
            this.pillarBaseY = this.player.blockPosition().getY();
        }
        if (this.pillarColumn == null) {
            // 没找到落脚点：至少先靠近柱子
            moveToward(in, cpos.getX() + 0.5D, cpos.getZ() + 0.5D);
            return true;
        }
        this.pillarTicks++;
        if (this.pillarTicks > PILLAR_TIMEOUT_TICKS) {
            this.pillarColumn = null;
            this.pillarTicks = 0;
            return false;   // 放弃近战拆，交回打龙
        }

        BlockPos col = this.pillarColumn;
        double dx = col.getX() + 0.5D - this.player.getX();
        double dz = col.getZ() + 0.5D - this.player.getZ();
        if (dx * dx + dz * dz > 1.44D) {
            moveToward(in, col.getX() + 0.5D, col.getZ() + 0.5D);
            return true;
        }
        pillarStep(in, col);
        return true;
    }

    /** 在水晶旁、朝玩家一侧找一个可替换的立足列 */
    private BlockPos pickPillarBase(BlockPos crystalPos) {
        int sx = (int) Math.signum(this.player.getX() - crystalPos.getX());
        int sz = (int) Math.signum(this.player.getZ() - crystalPos.getZ());
        if (sx == 0 && sz == 0) {
            sz = 1;
        }
        int y = this.player.blockPosition().getY();
        for (int step = 1; step <= 2; step++) {
            BlockPos pos = new BlockPos(crystalPos.getX() + sx * step, y, crystalPos.getZ() + sz * step);
            if (PlaceUtil.isReplaceable(this.player.level(), pos)) {
                return pos;
            }
        }
        return null;
    }

    /** 沿锁定列往上垫一格（标准的"跳起来往脚下放方块"） */
    private void pillarStep(AiInput in, BlockPos col) {
        if (!PlaceUtil.holdingBlock(this.player)) {
            int slot = findBlockHotbar();
            if (slot < 0) {
                return;
            }
            if (!this.player.selectSlot("boss", InventoryLock.PRIO_BRIDGE, slot)) {
                return;
            }
            in.selectedSlot = slot;
        }
        if (this.player.onGround()) {
            this.pillarBaseY = this.player.blockPosition().getY();
            in.jump = true;
            return;
        }
        // 升到足够高度后，把方块放回原来的立足格（原版搭塔就是这么放的）
        if (this.player.getY() > this.pillarBaseY + 1.2D) {
            BlockPos target = new BlockPos(col.getX(), this.pillarBaseY, col.getZ());
            in.pitch = 80.0F;
            in.yaw = yawTo(target.getX() + 0.5D, target.getZ() + 0.5D);
            if (PlaceUtil.placeAt(this.player, target, Direction.UP)) {
                this.pillarBaseY++;
            }
        }
    }

    private int findBlockHotbar() {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof BlockItem) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 打龙
    // ------------------------------------------------------------------

    private boolean fightDragon(AiInput in, EnderDragon dragon) {
        var phase = dragon.getPhaseManager().getCurrentPhase();
        int phaseId = phase.getPhase().getId();
        boolean perched = phase.isSitting()
                || phaseId == EnderDragonPhase.LANDING.getId()
                || phaseId == EnderDragonPhase.TAKEOFF.getId();
        if (perched) {
            return meleeHead(in, dragon);
        }

        // 躲龙息（龙焰云 / 龙火球）
        if (dodgeBreath(in)) {
            return true;
        }

        double dist = Math.sqrt(this.player.distanceToSqr(dragon));
        // 飞行中用远程预判射击
        if (this.player.profile().ranged && dist >= 5.0D && dist <= 40.0D
                && this.player.hasLineOfSight(dragon) && this.ranged.hasRanged()) {
            if (this.ranged.tick(dragon)) {
                return true;
            }
        }
        this.ranged.release();

        // 跟飞：远了就追，近了就退，甜区里切向盘旋
        if (dist > 16.0D) {
            moveToward(in, dragon.getX(), dragon.getZ());
            lookAtPoint(in, dragon.getX(), dragon.getY() + dragon.getBbHeight() * 0.5D, dragon.getZ());
        } else if (dist < 8.0D) {
            double dx = this.player.getX() - dragon.getX();
            double dz = this.player.getZ() - dragon.getZ();
            if (dx * dx + dz * dz > 1.0E-4D) {
                in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            }
            in.forward = true;
            in.sprint = true;
        } else {
            strafe(in);
            lookAtPoint(in, dragon.getX(), dragon.getY() + dragon.getBbHeight() * 0.5D, dragon.getZ());
        }
        return true;
    }

    /** 栖息时集火龙头：攻击 dragon.head，原版对头零件是 4 倍伤害 */
    private boolean meleeHead(AiInput in, EnderDragon dragon) {
        EnderDragonPart head = dragon.head;
        double dist = Math.sqrt(this.player.distanceToSqr(head));
        lookAtPoint(in, head.getX(), head.getY(), head.getZ());
        if (dist <= 2.8D) {
            in.attackEntity = true;
            in.attackTarget = head;
            return true;
        }
        // 从侧面接近：正对龙头正面会吃龙息和被顶飞
        Vec3 look = dragon.getHeadLookVector(1.0F);
        double toX = this.player.getX() - head.getX();
        double toZ = this.player.getZ() - head.getZ();
        double len = Math.sqrt(look.x * look.x + look.z * look.z);
        double tl = Math.sqrt(toX * toX + toZ * toZ);
        if (len > 1.0E-4D && tl > 1.0E-4D && (look.x * toX + look.z * toZ) / (len * tl) > 0.6D) {
            strafe(in);   // 我站在龙头正前方：先横着挪开
            return true;
        }
        in.forward = true;
        in.sprint = dist > 6.0D;
        return true;
    }

    /** 切向盘旋：保持围绕目标转圈，别固定半径（会被预判） */
    private void strafe(AiInput in) {
        if (--this.strafeTicks <= 0) {
            this.strafeDir = -this.strafeDir;
            this.strafeTicks = 30;
        }
        in.forward = true;
        if (this.strafeDir > 0) {
            in.left = true;
        } else {
            in.right = true;
        }
    }

    private boolean dodgeBreath(AiInput in) {
        Entity cloud = nearestBreath();
        if (cloud == null) {
            return false;
        }
        double dx = this.player.getX() - cloud.getX();
        double dz = this.player.getZ() - cloud.getZ();
        if (dx * dx + dz * dz < 1.0E-4D) {
            dx = 1.0D;
        }
        in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        in.forward = true;
        in.sprint = true;
        if (--this.strafeTicks <= 0) {
            this.strafeDir = -this.strafeDir;
            this.strafeTicks = 20;
        }
        if (this.strafeDir > 0) {
            in.left = true;
        } else {
            in.right = true;
        }
        return true;
    }

    /** 最近的龙息（龙焰云 / 龙火球），10 tick 缓存 */
    private Entity nearestBreath() {
        if (this.breathCache != null && (this.breathCache.isRemoved() || !this.breathCache.isAlive())) {
            this.breathCache = null;
        }
        if (this.breathCacheTicks > 0) {
            this.breathCacheTicks--;
            return this.breathCache;
        }
        this.breathCacheTicks = SCAN_INTERVAL;
        AABB box = this.player.getBoundingBox().inflate(10.0D);
        List<Entity> list = this.player.level().getEntitiesOfClass(Entity.class, box);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : list) {
            String n = e.getClass().getSimpleName();
            if (!n.contains("AreaEffectCloud") && !n.contains("DragonFireball")) {
                continue;
            }
            double d = this.player.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        this.breathCache = best;
        return best;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 朝目标点移动（只写按键输入，不碰寻路，交给原版物理） */
    private void moveToward(AiInput in, double x, double z) {
        double dx = x - this.player.getX();
        double dz = z - this.player.getZ();
        double d2 = dx * dx + dz * dz;
        if (d2 > 1.0E-4D) {
            in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        in.pitch = 0.0F;
        in.forward = true;
        in.sprint = d2 > 36.0D;
    }

    /** 计算指向某点的 yaw / pitch（避免和 player.lookAt 抢朝向） */
    private void lookAtPoint(AiInput in, double x, double y, double z) {
        double dx = x - this.player.getX();
        double dy = y - this.player.getEyeY();
        double dz = z - this.player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        in.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        in.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(1.0E-4D, horiz)));
    }

    private float yawTo(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-(x - this.player.getX()), z - this.player.getZ()));
    }
}
