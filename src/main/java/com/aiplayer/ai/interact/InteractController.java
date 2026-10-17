package com.aiplayer.ai.interact;

import com.aiplayer.entity.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * 和世界里的“非战斗”对象互动：翻箱子搬物资、和村民交易。
 * <p>
 * 全部沿用真人路径：先走到跟前 -> 右键打开界面 -> 用原版菜单 API 搬运 / 成交 -> 关界面。
 * 不凭空塞东西，所以原版逻辑（经验、村民补货、音效、双箱合并）都照常触发。
 */
public class InteractController {
    /** 够得着容器的最大距离（比原版伸手略留余量） */
    private static final double REACH = 4.0D;
    /** 一次交易最多成交几笔，别把村民一次刷爆 */
    private static final int MAX_TRADES = 8;

    private final AiPlayer player;

    public InteractController(AiPlayer player) {
        this.player = player;
    }

    /**
     * 打开一个容器（箱子 / 木桶 / 潜影盒 / 漏斗 / 熔炉…），把里面的东西搬进背包。
     *
     * @return 实际搬走的物品数量（0 表示没打开、或者里面是空的）
     */
    public int lootContainer(BlockPos pos) {
        if (!openBlock(pos)) {
            return 0;
        }
        AbstractContainerMenu menu = this.player.containerMenu;
        int moved = 0;
        try {
            Container container = containerOf(pos, menu);
            if (container == null) {
                return 0;
            }
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                int before = stack.getCount();
                // 原版 add 会把塞不下的部分留在原 stack 里
                this.player.getInventory().add(stack);
                int left = stack.getCount();
                container.setItem(i, left <= 0 ? ItemStack.EMPTY : stack);
                moved += before - left;
            }
            container.setChanged();
            if (menu != null) {
                menu.broadcastChanges();
            }
        } finally {
            closeIfOpen(menu);
        }
        return moved;
    }

    /**
     * 把背包里符合条件的物品存进容器（箱子 / 木桶 / 潜影盒…）。
     * 先和已有堆合并，再放空位；塞不下的留在背包里。
     *
     * @return 实际存入的物品数量（0 表示没打开 / 背包里没有匹配的）
     */
    public int depositInto(BlockPos pos, java.util.function.Predicate<ItemStack> filter) {
        if (!openBlock(pos)) {
            return 0;
        }
        AbstractContainerMenu menu = this.player.containerMenu;
        int moved = 0;
        try {
            Container container = containerOf(pos, menu);
            if (container == null) {
                return 0;
            }
            Inventory inv = this.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (i == Inventory.SLOT_OFFHAND) {
                    continue;
                }
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || !filter.test(stack)) {
                    continue;
                }
                // 先和容器里已有的堆合并
                for (int c = 0; c < container.getContainerSize() && !stack.isEmpty(); c++) {
                    ItemStack cur = container.getItem(c);
                    if (cur.isEmpty() || !ItemStack.isSameItemSameComponents(stack, cur)) {
                        continue;
                    }
                    int can = Math.min(stack.getCount(), cur.getMaxStackSize() - cur.getCount());
                    if (can > 0) {
                        cur.grow(can);
                        container.setItem(c, cur);
                        stack.shrink(can);
                        moved += can;
                    }
                }
                // 再占空位
                if (!stack.isEmpty()) {
                    for (int c = 0; c < container.getContainerSize(); c++) {
                        if (container.getItem(c).isEmpty()) {
                            container.setItem(c, stack.copy());
                            moved += stack.getCount();
                            inv.setItem(i, ItemStack.EMPTY);
                            stack = ItemStack.EMPTY;
                            break;
                        }
                    }
                }
                if (!stack.isEmpty()) {
                    inv.setItem(i, stack);
                }
            }
            container.setChanged();
            if (menu != null) {
                menu.broadcastChanges();
            }
        } finally {
            closeIfOpen(menu);
        }
        return moved;
    }

    /**
     * 和村民交易：挑买得起的报价成交若干笔。
     *
     * @return 成交笔数
     */
    public int trade(Villager villager) {
        if (villager == null || !villager.isAlive()) {
            return 0;
        }
        this.player.lookAt(villager.getX(), villager.getEyeY(), villager.getZ(), 40.0F);
        this.player.interactOn(villager, InteractionHand.MAIN_HAND, villager.position());
        AbstractContainerMenu menu = this.player.containerMenu;
        if (!(menu instanceof MerchantMenu merchant)) {
            closeIfOpen(menu);
            return 0;
        }
        int done = 0;
        try {
            int offers = merchant.getOffers().size();
            for (int round = 0; round < MAX_TRADES; round++) {
                boolean traded = false;
                for (int i = 0; i < offers; i++) {
                    // 照原版客户端的做法：先选中这一档报价，再把付款从背包挪到交易槽
                    merchant.setSelectionHint(i);
                    merchant.tryMoveItems(i);
                    if (!merchant.getSlot(0).hasItem()) {
                        continue;   // 这一档买不起，换下一档
                    }
                    // 取走结果槽 = 真正成交（原版会扣付款、给经验、累积村民熟客）
                    ItemStack got = merchant.quickMoveStack(this.player, 2);
                    if (!got.isEmpty()) {
                        done++;
                        traded = true;
                    }
                    break;
                }
                if (!traded) {
                    break;   // 所有报价都买不起 / 背包满了，收工
                }
            }
        } finally {
            closeIfOpen(menu);
        }
        return done;
    }

    /** 熔炉 / 高炉 / 烟熏炉的槽位：0 原料，1 燃料，2 成品 */
    private static final int FURNACE_INPUT = 0;
    private static final int FURNACE_FUEL = 1;
    private static final int FURNACE_RESULT = 2;
    /** 酿造台槽位：0~2 药水瓶，3 原料，4 燃料 */
    private static final int BREW_INGREDIENT = 3;
    private static final int BREW_FUEL = 4;
    /** 单次"干活"最多做几次，别把容器里的东西一次掏空 */
    private static final int MAX_OPS = 16;

    /**
     * 熔炉 / 高炉 / 烟熏炉：先收走已经烧好的成品，再把背包里能烧的原料和燃料塞进去。
     * <p>
     * 全程走原版菜单，所以烧炼进度、经验、音效都照常。
     *
     * @return 从输出槽收走的物品数量
     */
    public int smelt(BlockPos pos) {
        if (!openBlock(pos)) {
            return 0;
        }
        AbstractContainerMenu menu = this.player.containerMenu;
        if (!(menu instanceof AbstractFurnaceMenu)) {
            closeIfOpen(menu);
            return 0;
        }
        int moved = 0;
        try {
            moved += quickTake(menu, FURNACE_RESULT);
            // 空槽才填：槽里已经有东西就别乱动（可能正在烧 / 还有燃料）
            if (menu.getSlot(FURNACE_INPUT).getItem().isEmpty()) {
                ItemStack raw = takeFromInventory(this::isSmeltable, 16);
                if (!raw.isEmpty()) {
                    menu.getSlot(FURNACE_INPUT).set(raw);
                }
            }
            if (menu.getSlot(FURNACE_FUEL).getItem().isEmpty()) {
                ItemStack fuel = takeFromInventory(this::isFuel, 8);
                if (!fuel.isEmpty()) {
                    menu.getSlot(FURNACE_FUEL).set(fuel);
                }
            }
            menu.broadcastChanges();
        } finally {
            closeIfOpen(menu);
        }
        return moved;
    }

    /**
     * 熔炉类：只把烧好的成品收进背包（回程取货用，不动原料和燃料）。
     *
     * @return 收走的物品数量
     */
    public int collectFurnace(BlockPos pos) {
        if (!openBlock(pos)) {
            return 0;
        }
        AbstractContainerMenu menu = this.player.containerMenu;
        if (!(menu instanceof AbstractFurnaceMenu)) {
            closeIfOpen(menu);
            return 0;
        }
        int moved = 0;
        try {
            moved += quickTake(menu, FURNACE_RESULT);
            menu.broadcastChanges();
        } finally {
            closeIfOpen(menu);
        }
        return moved;
    }

    /**
     * 酿造台：先收走酿好的药水，再放水瓶 / 原料 / 烈焰粉。
     *
     * @return 收走的成品数量
     */
    public int brew(BlockPos pos) {
        if (!openBlock(pos)) {
            return 0;
        }
        AbstractContainerMenu menu = this.player.containerMenu;
        if (!(menu instanceof BrewingStandMenu)) {
            closeIfOpen(menu);
            return 0;
        }
        int moved = 0;
        try {
            // 收成品：0~2 号槽里的药水（空玻璃瓶留着当原料，不算成品）
            for (int i = 0; i <= 2; i++) {
                ItemStack s = menu.getSlot(i).getItem();
                if (s.isEmpty() || s.is(Items.GLASS_BOTTLE)) {
                    continue;
                }
                moved += quickTake(menu, i);
            }
            for (int i = 0; i <= 2; i++) {
                if (!menu.getSlot(i).getItem().isEmpty()) {
                    continue;
                }
                ItemStack bottle = takeFromInventory(InteractController::isBottle, 1);
                if (!bottle.isEmpty()) {
                    menu.getSlot(i).set(bottle);
                }
            }
            if (menu.getSlot(BREW_INGREDIENT).getItem().isEmpty()) {
                ItemStack ing = takeFromInventory(this::isBrewIngredient, 1);
                if (!ing.isEmpty()) {
                    menu.getSlot(BREW_INGREDIENT).set(ing);
                }
            }
            if (menu.getSlot(BREW_FUEL).getItem().isEmpty()) {
                ItemStack fuel = takeFromInventory(s -> s.is(ItemTags.BREWING_FUEL), 1);
                if (!fuel.isEmpty()) {
                    menu.getSlot(BREW_FUEL).set(fuel);
                }
            }
            menu.broadcastChanges();
        } finally {
            closeIfOpen(menu);
        }
        return moved;
    }

    /**
     * 工作台：按目标物品找一条合成配方，用原版"配方书自动摆放"逻辑把材料填进 3x3 格子，再取走成品。
     * <p>
     * 材料从背包里真扣、成品真进背包，鉴定 / 经验 / 音效都走原版。
     *
     * @return 实际合成出来的物品数量（0 表示没材料 / 找不到配方）
     */
    public int craft(BlockPos pos, Item want, int times) {
        if (want == null || times <= 0) {
            return 0;
        }
        if (!openBlock(pos)) {
            return 0;
        }
        AbstractContainerMenu menu = this.player.containerMenu;
        if (!(menu instanceof AbstractCraftingMenu crafting)) {
            closeIfOpen(menu);
            return 0;
        }
        ServerLevel level = (ServerLevel) this.player.level();
        int done = 0;
        try {
            RecipeHolder<CraftingRecipe> recipe = findCraftingRecipe(level, want);
            if (recipe == null) {
                return 0;
            }
            int rounds = Math.min(times, MAX_OPS);
            for (int i = 0; i < rounds; i++) {
                // useMaxItems=false：一次只摆一份材料（等于"单击合成一次"）。
                // 传 true 会按材料量一次做满整叠，再叠上这个循环就会超量合成、多吃材料。
                crafting.handlePlacement(false, false, recipe, level, this.player.getInventory());
                if (crafting.getResultSlot().getItem().isEmpty()) {
                    break;   // 材料不够了，收工
                }
                ItemStack got = crafting.quickMoveStack(this.player, 0);
                if (got.isEmpty()) {
                    break;
                }
                done += got.getCount();
            }
        } finally {
            closeIfOpen(menu);
        }
        return done;
    }

    /** 在配方表里找一条产出目标物品的普通合成配方（跳过特殊配方） */
    private RecipeHolder<CraftingRecipe> findCraftingRecipe(ServerLevel level, Item want) {
        RecipeManager rm = level.getServer().getRecipeManager();
        ContextMap ctx = SlotDisplayContext.fromLevel(level);
        for (RecipeHolder<?> holder : rm.getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe cr) || cr.isSpecial()) {
                continue;
            }
            for (RecipeDisplay display : cr.display()) {
                ItemStack result = display.result().resolveForFirstStack(ctx);
                if (!result.isEmpty() && result.is(want)) {
                    @SuppressWarnings("unchecked")
                    RecipeHolder<CraftingRecipe> typed = (RecipeHolder<CraftingRecipe>) holder;
                    return typed;
                }
            }
        }
        return null;
    }

    /** 把某个槽位的东西 shift 点击搬进玩家背包，返回搬走的数量 */
    private int quickTake(AbstractContainerMenu menu, int slot) {
        if (!menu.getSlot(slot).hasItem()) {
            return 0;
        }
        return menu.quickMoveStack(this.player, slot).getCount();
    }

    /** 从背包里按条件拆出一小堆（真的扣背包），找不到返回空 */
    private ItemStack takeFromInventory(Predicate<ItemStack> filter, int amount) {
        Inventory inv = this.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == Inventory.SLOT_OFFHAND) {
                continue;   // 副手的东西（盾 / 图腾）留着防身
            }
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !filter.test(s)) {
                continue;
            }
            return s.split(Math.min(amount, s.getCount()));
        }
        return ItemStack.EMPTY;
    }

    /** 能不能放进熔炉烧（原版熔炉可烧物品集合） */
    private boolean isSmeltable(ItemStack s) {
        return this.player.level().recipeAccess().propertySet(RecipePropertySet.FURNACE_INPUT).test(s);
    }

    /** 是不是燃料（原版燃料表） */
    private boolean isFuel(ItemStack s) {
        return this.player.level().fuelValues().isFuel(s);
    }

    /** 酿造台原料（原版药水酿造配方认的原料） */
    private boolean isBrewIngredient(ItemStack s) {
        return this.player.level().potionBrewing().isIngredient(s);
    }

    private static boolean isBottle(ItemStack s) {
        return s.is(Items.POTION) || s.is(Items.SPLASH_POTION)
                || s.is(Items.LINGERING_POTION) || s.is(Items.GLASS_BOTTLE);
    }

    // ------------------------------------------------------------------

    /** 走到够得着的距离后右键打开方块（箱子和熔炉都靠这一步） */
    private boolean openBlock(BlockPos pos) {
        if (!this.player.blockPosition().closerThan(pos, REACH)) {
            return false;
        }
        Vec3 eye = this.player.getEyePosition();
        // 26.2 的 Direction.getNearest 只接受 int 分量，取整后求主方向
        Direction face = Direction.getNearest(
                (int) Math.round(pos.getX() + 0.5D - eye.x),
                (int) Math.round(pos.getY() + 0.5D - eye.y),
                (int) Math.round(pos.getZ() + 0.5D - eye.z),
                Direction.UP);
        Vec3 hitPoint = new Vec3(
                pos.getX() + 0.5D + face.getStepX() * 0.5D,
                pos.getY() + 0.5D + face.getStepY() * 0.5D,
                pos.getZ() + 0.5D + face.getStepZ() * 0.5D);
        this.player.lookAt(hitPoint, 60.0F);
        BlockHitResult hit = new BlockHitResult(hitPoint, face, pos, false);
        this.player.gameMode.useItemOn(this.player, this.player.level(),
                this.player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
        return true;
    }

    /** 双箱要靠菜单里的容器才是完整的一半，其它方块直接读方块实体 */
    private Container containerOf(BlockPos pos, AbstractContainerMenu menu) {
        if (menu instanceof ChestMenu chest) {
            return chest.getContainer();
        }
        BlockEntity be = this.player.level().getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    private void closeIfOpen(AbstractContainerMenu menu) {
        if (menu != null && menu != this.player.inventoryMenu) {
            this.player.closeContainer();
        }
    }

    /** 给 AI 判断用：这个位置是不是一个能翻的容器 */
    public static boolean isContainerBlock(AiPlayer player, BlockPos pos) {
        return player.level().getBlockEntity(pos) instanceof Container;
    }
}