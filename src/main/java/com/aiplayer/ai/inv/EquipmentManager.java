package com.aiplayer.ai.inv;

import com.aiplayer.compat.TaczCompat;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import com.aiplayer.util.ItemIdUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动装备：每 0.5 秒评估一次盔甲 / 武器 / 工具 / 副手 / 物品栏整理。
 * 评分来自原版属性（攻击力、护甲、护甲韧性），所以附魔、模组装备都能正确比较。
 * <p>
 * 顺带做"扔垃圾"：同类里更差的重复装备、重复的消耗品、纯垃圾材料会被丢出去。
 */
public class EquipmentManager {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final String[] TOOL_KEYS = {"Pickaxe", "Axe", "Shovel"};
    /** 主背包 + 快捷栏的槽位上限（36 以上是盔甲 36~39 与副手 40，装备中的东西不动） */
    private static final int MAIN_SLOTS = 36;
    /** 扔东西后的冷却：4 秒。丢东西有动画和声音，太频繁看起来很毛躁 */
    private static final int DROP_COOLDOWN = 80;
    /** 绝对不丢的东西：AI 的吃饭家伙（装备包 / 各玩法控制器都要用） */
    private static final Set<Item> PROTECTED = Set.of(
            Items.WATER_BUCKET, Items.BUCKET, Items.MACE, Items.WIND_CHARGE, Items.ELYTRA,
            Items.ENDER_PEARL, Items.END_CRYSTAL, Items.OBSIDIAN, Items.TOTEM_OF_UNDYING,
            Items.SHIELD, Items.BOW, Items.CROSSBOW, Items.ARROW, Items.SPECTRAL_ARROW,
            Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.NETHERITE_SPEAR
    );
    /** 纯垃圾（按物品 ID 的 path 判断）：腐肉、蛛眼、线、骨头、燧石、铃薯、各种种子树苗 */
    private static final Set<String> JUNK_IDS = Set.of(
            "rotten_flesh", "spider_eye", "string", "bone", "flint", "poisonous_potato",
            "gunpowder", "feather", "dead_bush", "vine", "clay_ball", "egg", "kelp", "dried_kelp"
    );
    private static final String[] TOOL_CLASSES = {
            "Pickaxe", "Shovel", "Hoe", "Shears", "Axe", "Sword", "Mace", "Trident", "Spear"
    };

    private final AiPlayer player;
    private int cooldown = 10;
    private boolean suppressed;
    /** 扔东西的冷却（tick） */
    private int dropCooldown;

    public EquipmentManager(AiPlayer player) {
        this.player = player;
    }

    /** 搭路 / 进食 / 开火这类动作进行中时不要抢物品栏 */
    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
    }

    public void tick() {
        if (this.dropCooldown > 0) {
            this.dropCooldown--;
        }
        if (--this.cooldown > 0) {
            return;
        }
        this.cooldown = 10;
        if (this.suppressed || this.player.isUsingItem()) {
            return;
        }
        // 物品栏正被进食 / 水晶 / 搭路 / 战斗占用时别抢
        String owner = this.player.inventoryLock().owner();
        if (owner != null && !"equip".equals(owner)) {
            return;
        }
        evaluate();
    }

    public void evaluate() {
        if (this.player.isFallFlying()) {
            return;   // 滑翔中胸口是保命的鞘翅，别被盔甲评分换下来
        }
        Inventory inv = this.player.getInventory();
        equipArmor(inv);
        equipOffhand(inv);
        holdBestWeapon(inv);
        if (this.player.profile() != null && this.player.profile().level().id >= 2) {
            organizeTools(inv);
        }
        dropJunk(inv);
    }

    // ------------------------------------------------------------------
    // 扔垃圾 / 重复装备
    // ------------------------------------------------------------------

    /**
     * 把没用的东西丢出去。优先级从高到低，每次最多丢 1 件：
     * <ol>
     *   <li>同类里严格更差的重复装备（比如身上是钻石镐，包里还揣着石镐）</li>
     *   <li>同槽位比身上穿得更差的盔甲</li>
     *   <li>同一种不可堆叠装备的多余副本：留下耐久最好的那把</li>
     *   <li>纯垃圾材料（腐肉 / 蛛眼 / 线 / 骨头 / 燧石 / 种子树苗…）</li>
     *   <li>背包快满时，同一种东西攒到 3 组以上，丢掉最小的那组</li>
     * </ol>
     * 附魔过的、能吃的、方块、以及 {@link #PROTECTED} 里的装备包用品一律不丢。
     */
    private void dropJunk(Inventory inv) {
        if (this.dropCooldown > 0) {
            return;
        }
        int selected = inv.getSelectedSlot();
        // 1) 同类工具 / 武器里严格更差的
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (i == selected) {
                continue;
            }
            ItemStack stack = inv.getItem(i);
            String cls = toolClass(stack);
            if (cls == null || !droppable(stack)) {
                continue;
            }
            if (hasBetterSameClass(inv, i, cls)) {
                toss(inv, i);
                return;
            }
        }
        // 2) 背包里同槽位、比身上穿得更差的盔甲
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            double equipped = armorScore(this.player.getItemBySlot(slot), slot);
            if (equipped <= 0.0D) {
                continue;
            }
            for (int i = 0; i < MAIN_SLOTS; i++) {
                if (i == selected) {
                    continue;
                }
                ItemStack stack = inv.getItem(i);
                if (!droppable(stack) || this.player.getEquipmentSlotForItem(stack) != slot) {
                    continue;
                }
                if (armorScore(stack, slot) < equipped - 1.0E-4D) {
                    toss(inv, i);
                    return;
                }
            }
        }
        // 3) 同一种装备（不可堆叠）攒了好几件：只留耐久最好的，其余丢掉
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (i == selected) {
                continue;
            }
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || stack.getMaxStackSize() > 1 || !droppable(stack)) {
                continue;
            }
            int worse = worseCopyOf(inv, i);
            if (worse >= 0) {
                toss(inv, worse);
                return;
            }
        }
        // 4) 纯垃圾材料
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (i == selected) {
                continue;
            }
            ItemStack stack = inv.getItem(i);
            if (droppable(stack) && isJunk(stack)) {
                toss(inv, i);
                return;
            }
        }
        // 5) 背包挤了：同一种东西 3 组以上，丢掉最小的那组腾地方
        if (freeSlots(inv) >= 4) {
            return;
        }
        Map<Item, List<Integer>> byItem = new HashMap<>();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (i == selected || !droppable(inv.getItem(i))) {
                continue;
            }
            byItem.computeIfAbsent(inv.getItem(i).getItem(), k -> new java.util.ArrayList<>()).add(i);
        }
        for (List<Integer> slots : byItem.values()) {
            if (slots.size() < 3) {
                continue;
            }
            int smallest = slots.get(0);
            for (int s : slots) {
                if (inv.getItem(s).getCount() < inv.getItem(smallest).getCount()) {
                    smallest = s;
                }
            }
            toss(inv, smallest);
            return;
        }
    }

    /** 这一类（镐/斧/剑…）里存在另一件严格更好的，那这一件就是多余重复的 */
    private boolean hasBetterSameClass(Inventory inv, int slot, String cls) {
        double mine = itemScore(inv.getItem(slot));
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (i == slot) {
                continue;
            }
            ItemStack other = inv.getItem(i);
            if (other.isEmpty() || other.isEnchanted() || !cls.equals(toolClass(other))) {
                continue;
            }
            if (itemScore(other) > mine + 1.0E-4D) {
                return true;
            }
        }
        // 已经拿在别的槽位上（盔甲槽 / 副手 / 手上）的也算：那些不会被丢，所以背包里的就是多余的
        if (cls.equals(toolClass(this.player.getItemBySlot(EquipmentSlot.MAINHAND)))) {
            return itemScore(this.player.getItemBySlot(EquipmentSlot.MAINHAND)) > mine + 1.0E-4D;
        }
        if (cls.equals(toolClass(inv.getItem(Inventory.SLOT_OFFHAND)))) {
            return itemScore(inv.getItem(Inventory.SLOT_OFFHAND)) > mine + 1.0E-4D;
        }
        return false;
    }

    /**
     * 同一件物品的多个副本里，有没有比第 {@code slot} 个更差的那一件（返回它的槽位，没有返回 -1）。
     * 用来处理"两把一模一样的铁剑"这种纯重复：留耐久好的，丢磨损严重的。
     */
    private int worseCopyOf(Inventory inv, int slot) {
        ItemStack mine = inv.getItem(slot);
        int worse = -1;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (i == slot) {
                continue;
            }
            ItemStack other = inv.getItem(i);
            if (!droppable(other) || other.getItem() != mine.getItem()
                    || !ItemStack.isSameItemSameComponents(mine, other)) {
                continue;
            }
            // 留下一件最好的：别人比我新，那"更差的"就是我
            worse = i;
            break;
        }
        if (worse < 0) {
            return -1;
        }
        return durabilityLeft(mine) < durabilityLeft(inv.getItem(worse)) ? slot : worse;
    }

    private static int durabilityLeft(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

    /** 工具 / 武器 / 盔甲的综合评分：挖得快 + 打得疼（附魔已经由调用方排除） */
    private static double itemScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        return stack.getDestroySpeed(Blocks.STONE.defaultBlockState()) * 2.0D + weaponScore(stack);
    }

    private static String toolClass(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String name = stack.getItem().getClass().getSimpleName();
        for (String key : TOOL_CLASSES) {
            if (name.contains(key)) {
                return key;
            }
        }
        return null;
    }

    /** 可以丢的普通物品：附魔的、能吃的、方块、装备包用品都不动 */
    private static boolean droppable(ItemStack stack) {
        if (stack.isEmpty() || stack.isEnchanted()) {
            return false;
        }
        // 枪械一律不丢：TaCZ 的所有枪共用同一个 Item（枪型存在组件里），
        // 按"同一种物品"判重会把玩家给的第二把枪当重复品直接甩到地上。
        if (TaczCompat.isGunItem(stack)) {
            return false;
        }
        // 弹药 / 弹匣 / 配件同样不丢：它们也不是枪，但同样是 tacz 命名空间的物品。
        // 第 5 步"同一种物品攒了 3 组以上就丢最小的"会把不同口径的弹匣当成重复品，
        // AI 于是把子弹全扔光，枪里没弹 → bestGunSlot 返回 -1 → 一次也打不响。
        if (TaczCompat.isTaczItem(stack)) {
            return false;
        }
        if (PROTECTED.contains(stack.getItem())) {
            return false;
        }
        if (stack.has(DataComponents.FOOD)) {
            return false;   // 食物留着吃
        }
        return !(stack.getItem() instanceof BlockItem);
    }

    private static boolean isJunk(ItemStack stack) {
        String id = ItemIdUtil.idOf(stack.getItem());
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return JUNK_IDS.contains(path) || path.endsWith("_seeds") || path.endsWith("_sapling");
    }

    private static int freeSlots(Inventory inv) {
        int free = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (inv.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** 把某个槽位的东西丢到地上（先清空槽位，再甩出去） */
    private void toss(Inventory inv, int slot) {
        ItemStack stack = inv.getItem(slot);
        if (stack.isEmpty()) {
            return;
        }
        inv.setItem(slot, ItemStack.EMPTY);
        this.player.drop(stack.copy(), false, net.minecraft.util.Prediction.PREDICTED);
        this.dropCooldown = DROP_COOLDOWN;
    }

    /** 逐槽位比较护甲值，换上最好的 */
    private void equipArmor(Inventory inv) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            double current = armorScore(this.player.getItemBySlot(slot), slot);
            int best = -1;
            double bestScore = current;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (i == Inventory.SLOT_OFFHAND) {
                    continue;
                }
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                double score = armorScore(stack, slot);
                if (score > bestScore + 1.0E-4D) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best < 0) {
                continue;
            }
            ItemStack candidate = inv.getItem(best);
            ItemStack old = this.player.getItemBySlot(slot);
            this.player.setItemSlot(slot, candidate.copy());
            inv.setItem(best, old);
        }
    }

    /** 副手：盾牌优先，其次图腾 */
    private void equipOffhand(Inventory inv) {
        ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
        if (off.getItem() == Items.SHIELD || off.getItem() == Items.TOTEM_OF_UNDYING) {
            return;
        }
        int shield = indexOf(inv, Items.SHIELD);
        int totem = indexOf(inv, Items.TOTEM_OF_UNDYING);
        int pick = shield >= 0 ? shield : totem;
        if (pick < 0) {
            return;
        }
        ItemStack stack = inv.getItem(pick);
        inv.setItem(pick, off);
        inv.setItem(Inventory.SLOT_OFFHAND, stack);
    }

    /** 手上换成攻击力最高的武器 */
    private void holdBestWeapon(Inventory inv) {
        int best = -1;
        double bestScore = -1.0D;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == Inventory.SLOT_OFFHAND) {
                continue;
            }
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !isWeapon(stack)) {
                continue;
            }
            double score = weaponScore(stack);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) {
            // 一把近战武器都没有（只有枪 / 空手）：枪就是主武器，拿到手上。
            // 否则 AI 会一直空着手乱逛，打起来也只会用拳头。
            holdGun(inv);
            return;
        }
        if (best == inv.getSelectedSlot() || currentIsAsGood(inv, bestScore)) {
            return;
        }
        if (best < Inventory.SELECTION_SIZE) {
            // 走统一的槽位锁，避免和进食 / 战斗抢快捷栏
            this.player.selectSlot("equip", InventoryLock.PRIO_EQUIP, best);
        } else {
            int target = inv.getSelectedSlot();
            ItemStack weapon = inv.getItem(best);
            ItemStack old = inv.getItem(target);
            inv.setItem(target, weapon);
            inv.setItem(best, old);
        }
    }

    /** 没有近战武器时，把最好的一把枪拿到手上（TaCZ 枪械）；没装 tacz 时什么也不做 */
    private void holdGun(Inventory inv) {
        if (!TaczCompat.loaded()) {
            return;
        }
        int gun = TaczCompat.bestGunSlot(this.player);
        if (gun < 0 || gun == inv.getSelectedSlot()) {
            return;
        }
        if (gun < Inventory.SELECTION_SIZE) {
            this.player.selectSlot("equip", InventoryLock.PRIO_EQUIP, gun);
        } else {
            int target = inv.getSelectedSlot();
            ItemStack weapon = inv.getItem(gun);
            ItemStack old = inv.getItem(target);
            inv.setItem(target, weapon);
            inv.setItem(gun, old);
        }
    }

    private boolean currentIsAsGood(Inventory inv, double bestScore) {
        ItemStack held = inv.getSelectedItem();
        return !held.isEmpty() && isWeapon(held) && weaponScore(held) >= bestScore - 1.0E-4D;
    }

    /** 镐 / 斧 / 铲 放到快捷栏前三位，方便挖矿和战斗切换 */
    private void organizeTools(Inventory inv) {
        for (int t = 0; t < TOOL_KEYS.length; t++) {
            int hotbar = t;
            if (matches(inv.getItem(hotbar), TOOL_KEYS[t])) {
                continue;
            }
            if (TaczCompat.isGunItem(inv.getItem(hotbar))) {
                continue;   // 快捷栏里是枪：别让镐子 / 斧子把枪挤进背包
            }
            for (int i = Inventory.SELECTION_SIZE; i < 36; i++) {
                if (matches(inv.getItem(i), TOOL_KEYS[t])) {
                    ItemStack a = inv.getItem(i);
                    inv.setItem(i, inv.getItem(hotbar));
                    inv.setItem(hotbar, a);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 评分
    // ------------------------------------------------------------------

    private static double armorScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double[] total = new double[1];
        stack.forEachModifier(slot, (attribute, modifier) -> total[0] += weight(attribute, modifier.amount()));
        // 耐久也算一点分，满耐久优先
        if (total[0] > 0.0D && stack.getMaxDamage() > 0) {
            total[0] += (1.0D - (double) stack.getDamageValue() / stack.getMaxDamage()) * 0.5D;
        }
        return total[0];
    }

    private static double weaponScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double[] total = new double[1];
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            Attribute a = attribute.value();
            if (a == Attributes.ATTACK_DAMAGE.value()) {
                total[0] += modifier.amount() * 2.0D;
            } else if (a == Attributes.ATTACK_SPEED.value()) {
                total[0] += modifier.amount() * 0.4D;
            }
        });
        return total[0];
    }

    private static double weight(net.minecraft.core.Holder<Attribute> attribute, double amount) {
        Attribute a = attribute.value();
        if (a == Attributes.ARMOR.value()) {
            return amount;
        }
        if (a == Attributes.ARMOR_TOUGHNESS.value()) {
            return amount * 0.5D;
        }
        if (a == Attributes.KNOCKBACK_RESISTANCE.value()) {
            return amount * 2.0D;
        }
        return 0.0D;
    }

    private static boolean isWeapon(ItemStack stack) {
        String name = stack.getItem().getClass().getSimpleName();
        return name.contains("Sword") || name.contains("Axe") || name.contains("Trident")
                || name.contains("Mace") || name.contains("Spear") || name.contains("Katana")
                || name.contains("Dagger") || name.contains("Scythe") || name.contains("Halberd");
    }

    private static boolean matches(ItemStack stack, String keyword) {
        return !stack.isEmpty() && stack.getItem().getClass().getSimpleName().contains(keyword);
    }

    private static int indexOf(Inventory inv, net.minecraft.world.item.Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i != Inventory.SLOT_OFFHAND && inv.getItem(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }
}