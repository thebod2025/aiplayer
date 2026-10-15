package com.aiplayer.ai.inv;

import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 自动装备：每 0.5 秒评估一次盔甲 / 武器 / 工具 / 副手 / 物品栏整理。
 * 评分来自原版属性（攻击力、护甲、护甲韧性），所以附魔、模组装备都能正确比较。
 */
public class EquipmentManager {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final String[] TOOL_KEYS = {"Pickaxe", "Axe", "Shovel"};

    private final AiPlayer player;
    private int cooldown = 10;
    private boolean suppressed;

    public EquipmentManager(AiPlayer player) {
        this.player = player;
    }

    /** 搭路 / 进食 / 开火这类动作进行中时不要抢物品栏 */
    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
    }

    public void tick() {
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