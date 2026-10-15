package com.aiplayer.ai.survival;

import com.aiplayer.ai.AiInput;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InvUtil;
import com.aiplayer.util.InventoryLock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 生存：饥饿进食、食物优先级、识别可食用掉落物、熔炉烹饪。
 */
public class FoodManager {
    /** 饥饿阈值：低于该值就找东西吃（默认 7，也就是满饥饿 20 的 1/3） */
    public static final int HUNGER_THRESHOLD = 7;
    /**
     * 战斗中的饥饿阈值。
     * 饥饿 ≤7 时不能自然回血，PvP 里会越打越亏，所以打起来要求更高（≥15 才出手回血）。
     */
    public static final int HUNGER_THRESHOLD_COMBAT = 15;
    private static final int MAX_EAT_TICKS = 80;
    /** 烤好东西之后回来取的时间（毫秒） */
    private static final long COOK_COLLECT_DELAY_MS = 10_000L;
    /** 超过这个时间还没取到就放弃（熔炉可能没了 / 走不过去） */
    private static final long PENDING_FURNACE_TIMEOUT_MS = 120_000L;

    private final AiPlayer player;
    private boolean eating;
    private int eatTicks;
    /** 是否处于战斗/危险中（由 AiBrain 每 tick 设置） */
    private boolean combat;
    /** 上次生火的位置（用来过一会儿回来取成品） */
    private BlockPos pendingFurnace;
    private long pendingFurnaceAt;

    public FoodManager(AiPlayer player) {
        this.player = player;
    }

    public boolean isEating() {
        return this.eating;
    }

    public void setCombat(boolean combat) {
        this.combat = combat;
    }

    /** 当前饥饿阈值（战斗中更高） */
    public int hungerThreshold() {
        if (this.combat) {
            return HUNGER_THRESHOLD_COMBAT;
        }
        return this.player.getHealth() < 12.0F ? 11 : HUNGER_THRESHOLD;
    }

    public boolean hungry() {
        return this.player.getFoodData().getFoodLevel() < hungerThreshold();
    }

    /** 生火后还没取走成品的熔炉（AiBrain 会安排一次回程） */
    public BlockPos pendingFurnace() {
        if (this.pendingFurnace == null) {
            return null;
        }
        long age = System.currentTimeMillis() - this.pendingFurnaceAt;
        if (age < COOK_COLLECT_DELAY_MS) {
            return null;
        }
        if (age > PENDING_FURNACE_TIMEOUT_MS) {
            // 拖太久了，放弃这趟回程，免得一直往一个到不了的地方跑
            this.pendingFurnace = null;
            return null;
        }
        return this.pendingFurnace;
    }

    public void clearPendingFurnace() {
        this.pendingFurnace = null;
    }

    /** @return true 表示正在进食（占用输入） */
    public boolean tick() {
        if (this.eating) {
            this.eatTicks++;
            ItemStack held = this.player.getInventory().getSelectedItem();
            boolean stillFood = !held.isEmpty() && foodPriority(held) > 0;
            boolean full = this.player.getFoodData().getFoodLevel() >= 20;
            // 吃饱了 / 手上不再是食物 / 超时兜底：收手
            if (full || !stillFood || this.eatTicks > MAX_EAT_TICKS) {
                if (this.player.isUsingItem()) {
                    this.player.stopUsingItem();
                }
                this.eating = false;
                this.eatTicks = 0;
                return false;
            }
            // 中途被打断（换槽 / 别的控制器动了右键）：重新按住右键接着吃，
            // 之前一发现 isUsingItem 变 false 就放弃，表现为"咬一口就不吃了"
            if (!this.player.isUsingItem()) {
                this.player.gameMode.useItem(this.player, this.player.level(), held, InteractionHand.MAIN_HAND);
            }
            // 吃东西时站定：真人是走不动的，这里把所有移动 / 跳跃输入清零（否则边跑边吃、还边吃边卡原地）
            AiInput in = this.player.input();
            in.forward = false;
            in.backward = false;
            in.left = false;
            in.right = false;
            in.jump = false;
            in.sprint = false;
            return true;
        }
        if (!hungry() || this.player.isUsingItem()) {
            return false;
        }
        int slot = bestFoodSlot();
        if (slot < 0) {
            return false;
        }
        if (!this.player.selectSlot("food", InventoryLock.PRIO_FOOD, slot)) {
            return false;
        }
        ItemStack stack = this.player.getInventory().getSelectedItem();
        // 金苹果留给战斗，普通饥饿用普通食物
        this.player.gameMode.useItem(this.player, this.player.level(), stack, InteractionHand.MAIN_HAND);
        if (this.player.isUsingItem()) {
            this.eating = true;
            this.eatTicks = 0;
            return true;
        }
        return false;
    }

    /** 快捷栏里最合适的食物槽位（熟肉 > 面包/烤马铃薯 > 金苹果 > 生肉 > 水果） */
    public int bestFoodSlot() {
        Inventory inv = this.player.getInventory();
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            int score = foodPriority(stack);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** 背包里最好的食物（任意槽位） */
    public int bestFoodSlotAnywhere() {
        Inventory inv = this.player.getInventory();
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i >= 36) {
                continue;
            }
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            int score = foodPriority(stack);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** 把背包里的食物挪到快捷栏，方便饿的时候立刻吃 */
    public void organizeFood() {
        if (bestFoodSlot() >= 0) {
            return;
        }
        int from = bestFoodSlotAnywhere();
        if (from < 0) {
            return;
        }
        Inventory inv = this.player.getInventory();
        int target = 8;
        ItemStack a = inv.getItem(from);
        inv.setItem(from, inv.getItem(target));
        inv.setItem(target, a);
    }

    public static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && foodPriority(stack) > 0;
    }

    /** 食物优先级；同时用于判断“是否可食用” */
    public static int foodPriority(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();
        if (item == Items.COOKED_BEEF || item == Items.COOKED_PORKCHOP || item == Items.COOKED_CHICKEN
                || item == Items.COOKED_MUTTON || item == Items.COOKED_RABBIT) {
            return 6;
        }
        if (item == Items.BREAD || item == Items.BAKED_POTATO || item == Items.COOKED_COD
                || item == Items.COOKED_SALMON || item == Items.PUMPKIN_PIE || item == Items.MUSHROOM_STEW) {
            return 5;
        }
        if (item == Items.GOLDEN_APPLE) {
            return 4;
        }
        if (item == Items.BEEF || item == Items.PORKCHOP || item == Items.CHICKEN || item == Items.MUTTON
                || item == Items.RABBIT || item == Items.COD || item == Items.SALMON) {
            return 3;
        }
        if (item == Items.APPLE || item == Items.CARROT || item == Items.MELON_SLICE
                || item == Items.SWEET_BERRIES || item == Items.GLOW_BERRIES || item == Items.POTATO) {
            return 2;
        }
        if (item == Items.ENCHANTED_GOLDEN_APPLE) {
            return 1;
        }
        return 0;
    }

    /** 生肉（需要烤） */
    public static boolean isRawMeat(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item == Items.BEEF || item == Items.PORKCHOP || item == Items.CHICKEN
                || item == Items.MUTTON || item == Items.RABBIT || item == Items.COD || item == Items.SALMON;
    }

    /** 附近掉落的食物（用于主动去捡） */
    public ItemEntity nearestFoodDrop(double range) {
        AABB box = this.player.getBoundingBox().inflate(range);
        List<ItemEntity> drops = this.player.level().getEntitiesOfClass(ItemEntity.class, box);
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity e : drops) {
            if (!e.isAlive() || !isFood(e.getItem())) {
                continue;
            }
            double d = this.player.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /** 附近可以用的熔炉 */
    public BlockPos nearestFurnace(int radius) {
        BlockPos origin = this.player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius), origin.offset(radius, 2, radius))) {
            String name = this.player.level().getBlockState(pos).getBlock().getClass().getSimpleName();
            if (!name.contains("Furnace") && !name.contains("Smoker") && !name.contains("Campfire")) {
                continue;
            }
            double d = this.player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * 把生肉和燃料塞进附近熔炉（靠近后调用）。
     * 用右键打开原版熔炉菜单，再直接往输入槽 / 燃料槽放东西 —— 不绕过原版逻辑。
     * <p>
     * 会先把输出槽（2）里已经烤好的东西收进背包，再放新的生肉，
     * 并记录 {@link #pendingFurnace}，过一会儿由 AiBrain 安排回程取成品。
     */
    public boolean cookAt(BlockPos furnace) {
        AbstractContainerMenu menu = openFurnaceMenu(furnace);
        if (menu == null) {
            return false;
        }
        try {
            // 1) 先收成品，别让烤好的肉烂在熔炉里
            boolean collected = collectOutput(menu);

            Inventory inv = this.player.getInventory();
            int rawSlot = -1;
            int fuelSlot = -1;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (i >= 36) {
                    continue;
                }
                ItemStack stack = inv.getItem(i);
                if (rawSlot < 0 && isRawMeat(stack)) {
                    rawSlot = i;
                }
                if (fuelSlot < 0 && isFuel(stack)) {
                    fuelSlot = i;
                }
            }
            if (rawSlot < 0 || fuelSlot < 0) {
                // 没有可烤的东西：这趟就当是来取成品的
                this.clearPendingFurnace();
                return collected;
            }
            ItemStack raw = inv.getItem(rawSlot);
            ItemStack fuel = inv.getItem(fuelSlot);
            if (!raw.isEmpty()) {
                menu.setItem(0, 0, raw.split(Math.min(raw.getCount(), 16)));
            }
            if (!fuel.isEmpty()) {
                menu.setItem(1, 0, fuel.split(Math.min(fuel.getCount(), 16)));
            }
            menu.broadcastChanges();
            this.pendingFurnace = furnace.immutable();
            this.pendingFurnaceAt = System.currentTimeMillis();
            return true;
        } finally {
            this.player.closeContainer();
        }
    }

    /** 回来取烤好的东西（靠近熔炉后调用） */
    public boolean takeFurnaceOutput(BlockPos furnace) {
        AbstractContainerMenu menu = openFurnaceMenu(furnace);
        if (menu == null) {
            this.clearPendingFurnace();
            return false;
        }
        try {
            boolean took = collectOutput(menu);
            menu.broadcastChanges();
            if (took) {
                this.clearPendingFurnace();
            }
            return took;
        } finally {
            this.player.closeContainer();
        }
    }

    /** 打开熔炉菜单并把视角对准它；不是熔炉就返回 null */
    private AbstractContainerMenu openFurnaceMenu(BlockPos furnace) {
        if (!this.player.blockPosition().closerThan(furnace, 3.5D)) {
            return null;
        }
        Vec3 eye = this.player.getEyePosition();
        // 26.2 的 Direction.getNearest 只接受 int 分量，取整后求主方向
        Direction face = Direction.getNearest(
                (int) Math.round(furnace.getX() + 0.5D - eye.x),
                (int) Math.round(furnace.getY() + 0.5D - eye.y),
                (int) Math.round(furnace.getZ() + 0.5D - eye.z),
                Direction.UP);
        Vec3 hitPoint = new Vec3(
                furnace.getX() + 0.5D + face.getStepX() * 0.5D,
                furnace.getY() + 0.5D + face.getStepY() * 0.5D,
                furnace.getZ() + 0.5D + face.getStepZ() * 0.5D);
        this.player.lookAt(hitPoint, 60.0F);
        BlockHitResult hit = new BlockHitResult(hitPoint, face, furnace, false);
        this.player.gameMode.useItemOn(this.player, this.player.level(),
                this.player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);

        AbstractContainerMenu menu = this.player.containerMenu;
        if (menu == null || !menu.getClass().getSimpleName().contains("Furnace") || menu.slots.size() < 3) {
            return null;
        }
        return menu;
    }

    /** 把输出槽（索引 2）里的东西搬进玩家背包 */
    private boolean collectOutput(AbstractContainerMenu menu) {
        ItemStack out = menu.getSlot(2).getItem();
        if (out.isEmpty()) {
            return false;
        }
        // 原版 shift 点击：把输出槽的东西快速移动到玩家背包
        menu.quickMoveStack(this.player, 2);
        if (menu.getSlot(2).getItem().isEmpty()) {
            return true;
        }
        // 兜底：直接搬
        ItemStack stack = menu.getSlot(2).getItem().copy();
        if (this.player.getInventory().add(stack)) {
            menu.setItem(2, 0, ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    private static boolean isFuel(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item == Items.COAL || item == Items.CHARCOAL || item == Items.OAK_PLANKS
                || item == Items.OAK_LOG || item == Items.STICK || item == Items.LAVA_BUCKET
                || item == Items.BLAZE_ROD || item == Items.DRIED_KELP_BLOCK;
    }

    /** 是否应该去捡这个掉落物（食物、或武器/盔甲/有价值的方块） */
    public static boolean worthPicking(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (isFood(stack)) {
            return true;
        }
        String name = stack.getItem().getClass().getSimpleName();
        return name.contains("Sword") || name.contains("Axe") || name.contains("Pickaxe")
                || name.contains("Helmet") || name.contains("Chestplate") || name.contains("Leggings")
                || name.contains("Boots") || name.contains("Shield") || name.contains("Totem");
    }

    /** 需要吃金苹果时（低血）用哪一个槽位 */
    public int goldenAppleSlot() {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            Item item = inv.getItem(i).getItem();
            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                return i;
            }
        }
        return InvUtil.findHotbarSlot(this.player, Items.GOLDEN_APPLE);
    }
}