package com.aiplayer.ai.combat;

import com.aiplayer.ai.AiInput;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.PlaceUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * 战斗中的主动放方块（Java 版打法，不是无脑乱放）：
 * <ul>
 *   <li>堵路：被近身 / 血量偏低时，在自身与敌人之间立一面 2 格高的墙，挡住近战追击</li>
 *   <li>搭塔：被近战敌人缠住时，脚下垫 1-2 格抢高点（居高临下打头，敌人够不着）</li>
 * </ul>
 * 有严格的频率限制（冷却 + 概率 + 场景门槛），不会变成"放置狂魔"。
 */
public class BlockTacticsController {
    private enum Mode { IDLE, WALL, TOWER }

    private static final Random RANDOM = new Random();

    /** 两次战术之间的最小冷却（tick） */
    private static final int COOLDOWN_MIN = 80;
    private static final int COOLDOWN_MAX = 160;
    /** 塔最多垫几格 */
    private static final int MAX_TOWER = 2;
    /** 单个战术超时（tick），防卡死 */
    private static final int TACTIC_TIMEOUT = 60;

    private final AiPlayer player;
    private Mode mode = Mode.IDLE;
    /** 堵路待放的方块队列 */
    private final Deque<BlockPos> plan = new ArrayDeque<>();
    private int cooldown = 60;
    private int tacticTicks;

    // 搭塔状态
    private BlockPos basePos;
    private boolean jumped;
    private int towerPlaced;

    public BlockTacticsController(AiPlayer player) {
        this.player = player;
    }

    public boolean isActive() {
        return this.mode != Mode.IDLE;
    }

    public void reset() {
        this.mode = Mode.IDLE;
        this.plan.clear();
        this.jumped = false;
        this.towerPlaced = 0;
        this.tacticTicks = 0;
    }

    /**
     * 战斗中调用。返回 true 表示本 tick 被放方块战术占用。
     */
    public boolean tick(Entity enemy, AiInput in) {
        if (this.mode != Mode.IDLE) {
            this.tacticTicks++;
            boolean done = switch (this.mode) {
                case WALL -> wallTick(in);
                case TOWER -> towerTick(in);
                default -> true;
            };
            if (done || this.tacticTicks > TACTIC_TIMEOUT) {
                reset();
                this.cooldown = COOLDOWN_MIN + RANDOM.nextInt(COOLDOWN_MAX - COOLDOWN_MIN);
            }
            return this.mode != Mode.IDLE;
        }

        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        if (enemy == null || !enemy.isAlive() || this.player.hurtTime > 0) {
            return false;
        }
        double dist = Math.sqrt(this.player.distanceToSqr(enemy));
        if (!hasBlock()) {
            return false;
        }

        // 堵路：敌人近身（<6 格）且（血量低 或 概率触发）
        if (dist < 6.0D && (this.player.getHealth() < 14.0F || RANDOM.nextFloat() < 0.3F)) {
            if (startWall(enemy)) {
                this.mode = Mode.WALL;
                this.tacticTicks = 0;
                return true;
            }
        }
        // 搭塔：被近战缠住（<4.5 格）时概率垫高
        if (dist < 4.5D && this.player.onGround() && RANDOM.nextFloat() < 0.25F) {
            this.mode = Mode.TOWER;
            this.tacticTicks = 0;
            this.towerPlaced = 0;
            this.jumped = false;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 堵路
    // ------------------------------------------------------------------

    private boolean startWall(Entity enemy) {
        Vec3 toEnemy = enemy.position().subtract(this.player.position());
        double dx = toEnemy.x;
        double dz = toEnemy.z;
        // 取主导水平方向：墙立在自身靠敌人一侧
        Direction dir;
        if (Math.abs(dx) >= Math.abs(dz)) {
            dir = dx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            dir = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
        BlockPos front = this.player.blockPosition().relative(dir);
        Level level = this.player.level();
        this.plan.clear();
        if (PlaceUtil.isReplaceable(level, front)) {
            this.plan.add(front);
        }
        if (PlaceUtil.isReplaceable(level, front.above())) {
            this.plan.add(front.above());
        }
        return !this.plan.isEmpty();
    }

    private boolean wallTick(AiInput in) {
        if (this.plan.isEmpty()) {
            return true;
        }
        BlockPos pos = this.plan.peek();
        if (!PlaceUtil.isReplaceable(this.player.level(), pos)) {
            // 被别人补上了 / 已经放好：跳过这格
            this.plan.poll();
            return this.plan.isEmpty();
        }
        // 站太近会被自己放的方块卡住：先离开一格再放
        if (this.player.blockPosition().equals(pos)) {
            in.backward = true;
            return false;
        }
        if (!holdBlock(in)) {
            return false;
        }
        // 点的是支撑面朝自己那面
        this.player.lookAt(Vec3.atCenterOf(pos), 40.0F);
        if (PlaceUtil.placeAt(this.player, pos, Direction.UP)) {
            this.plan.poll();
        }
        return this.plan.isEmpty();
    }

    // ------------------------------------------------------------------
    // 搭塔（Java 版节奏：起跳 -> 上升到脚底离地一格 -> 往脚下放方块 -> 落回去站上去）
    // ------------------------------------------------------------------

    private boolean towerTick(AiInput in) {
        if (this.towerPlaced >= MAX_TOWER) {
            return true;
        }
        // 垫脚时站定，别一边跳一边乱跑
        in.forward = false;
        in.backward = false;
        in.left = false;
        in.right = false;
        in.sprint = false;

        if (this.player.onGround()) {
            if (this.jumped) {
                // 落回了地面：上一轮 placement 成败已定，重置跳状态
                this.jumped = false;
                return this.towerPlaced >= 1 && !hasBlock();
            }
            if (!hasBlock()) {
                return true;
            }
            // 起跳
            in.jump = true;
            this.basePos = this.player.blockPosition();
            this.jumped = true;
            return false;
        }
        // 空中：等脚抬到 1 格以上再往起跳位置放方块（放早了会和自身碰撞被原版拒绝）
        if (this.jumped && this.basePos != null
                && this.player.getY() >= this.basePos.getY() + 1.0D) {
            if (!holdBlock(in)) {
                return false;
            }
            this.player.lookAt(Vec3.atCenterOf(this.basePos), 60.0F);
            if (PlaceUtil.placeAt(this.player, this.basePos, Direction.UP)) {
                this.towerPlaced++;
            }
            this.jumped = false;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 方块来源
    // ------------------------------------------------------------------

    /** 快捷栏里有没有可放置的方块 */
    private boolean hasBlock() {
        return findBlockInHotbar() >= 0 || findBlockInBackpack() >= 0;
    }

    private int findBlockInHotbar() {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            if (isPlaceable(inv.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private int findBlockInBackpack() {
        Inventory inv = this.player.getInventory();
        for (int i = Inventory.SELECTION_SIZE; i < 36; i++) {
            if (isPlaceable(inv.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 能放、且不是带容器的方块（箱子 / 潜影盒 / 熔炉这类 EntityBlock，别当路砖扔了） */
    private static boolean isPlaceable(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) {
            return false;
        }
        return !(bi.getBlock() instanceof net.minecraft.world.level.block.EntityBlock);
    }

    /**
     * 把方块切到手上：先找快捷栏，没有就从主背包换一格过来。
     * 用 CRYSTAL 优先级 —— 战斗放方块和水晶 PvP 同级，高于普通战斗换武器。
     */
    private boolean holdBlock(AiInput in) {
        int slot = findBlockInHotbar();
        if (slot < 0) {
            int from = findBlockInBackpack();
            if (from < 0) {
                return false;
            }
            Inventory inv = this.player.getInventory();
            int to = -1;
            for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
                if (inv.getItem(i).isEmpty()) {
                    to = i;
                    break;
                }
            }
            if (to < 0) {
                to = 8;
            }
            ItemStack moved = inv.getItem(from);
            inv.setItem(from, inv.getItem(to));
            inv.setItem(to, moved);
            slot = to;
        }
        if (!this.player.selectSlot("tactics", InventoryLock.PRIO_CRYSTAL, slot)) {
            return false;
        }
        in.selectedSlot = slot;
        return true;
    }
}
