package com.aiplayer.util;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;

/** 物品栏工具 */
public final class InvUtil {
    private InvUtil() {
    }

    /** 找到身上第一个指定物品 */
    public static ItemStack find(Player player, Item item) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    public static boolean has(Player player, Item item) {
        return !find(player, item).isEmpty();
    }

    /** 找到指定物品所在的快捷栏槽位（0-8），没有则返回 -1 */
    public static int findHotbarSlot(Player player, Item item) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把主背包里的某物品搬到快捷栏（优先空格，兜底第 8 格）。
     * <p>
     * 水晶 PvP 的黑曜石 / 末地水晶经常被玩家放进主背包而不是快捷栏底行，
     * 之前 {@code findHotbarSlot} 找不到就永远不放 —— CPvP 全程空转只近战。
     *
     * @return 搬好后的快捷栏格号；主背包里也没有该物品时返回 -1
     */
    public static int pullToHotbar(Player player, Item item) {
        Inventory inv = player.getInventory();
        int from = -1;
        for (int i = Inventory.SELECTION_SIZE; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return -1;
        }
        int to = -1;
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            if (inv.getItem(i).isEmpty()) {
                to = i;
                break;
            }
        }
        if (to < 0) {
            to = 8; // 底行末格兜底（武器占用 0-2 不受影响）
        }
        ItemStack moved = inv.getItem(from);
        inv.setItem(from, inv.getItem(to));
        inv.setItem(to, moved);
        return to;
    }

    /** 把指定物品切到手上；返回是否成功 */
    public static boolean selectItem(Player player, Item item) {
        int slot = findHotbarSlot(player, item);
        if (slot < 0) {
            return false;
        }
        player.getInventory().setSelectedSlot(slot);
        return true;
    }

    /** 背包里任意一个方块（优先快捷栏） */
    public static ItemStack findAnyBlock(Player player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof net.minecraft.world.item.BlockItem) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 把某个物品栈切到手上（先找快捷栏，找不到就和当前槽位互换） */
    public static boolean selectStack(Player player, ItemStack stack) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            if (inv.getItem(i) == stack) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            if (!inv.getItem(i).isEmpty() && inv.getItem(i).getItem() instanceof net.minecraft.world.item.BlockItem) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        for (int i = Inventory.SELECTION_SIZE; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i) == stack) {
                int target = inv.getSelectedSlot();
                ItemStack old = inv.getItem(target);
                inv.setItem(target, stack);
                inv.setItem(i, old);
                return true;
            }
        }
        return false;
    }

    public static boolean selectAny(Player player, Item... items) {
        for (Item item : items) {
            if (selectItem(player, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按配置发放装备包。
     *
     * @param spec 自定义物品清单（逗号 / 分号 / 换行分隔，支持 {@code golden_apple x8} 这种数量写法）；
     *             留空或全部解析失败时退回内置默认套装，避免 AI 空手上场
     */
    public static void giveKit(Player player, String spec) {
        if (spec == null || spec.isBlank()) {
            giveKit(player);
            return;
        }
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        for (String part : spec.split("[,;\\n]")) {
            ItemStack stack = ItemIdUtil.parseStack(part);
            if (stack.isEmpty()) {
                continue;
            }
            stacks.add(stack);
        }
        if (stacks.isEmpty()) {
            // 清单里一个物品都没认出来（多半是 ID 写错了）：退回默认套装
            giveKit(player);
            return;
        }
        // 前 27 项 = 主背包；第 28-36 项 = 快捷栏 0-8（和装备包箱子 GUI 的底行一一对应）。
        // 快捷栏先放，防止后面 add() 把背包物品挤进快捷栏占位。
        // 注意 subList(27, size) 在 size <= 27 时 fromIndex > toIndex 会直接抛异常，
        // 表现就是 AI 生成 / 死亡重建全部失败 —— 必须 size > 27 才取快捷栏段。
        int total = stacks.size();
        if (total > 27) {
            Inventory inv = player.getInventory();
            for (int i = 0; i < Math.min(total - 27, 9); i++) {
                inv.setItem(i, stacks.get(27 + i));
            }
        }
        for (ItemStack stack : stacks.subList(0, Math.min(total, 27))) {
            player.getInventory().add(stack);
        }
        player.setHealth(player.getMaxHealth());
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    /**
     * 完整背包 -> JSON 字符串：41 格逐槽（0-35 背包，36-39 盔甲，40 副手），
     * 物品用原版 {@link ItemStack#CODEC} 编码 —— 附魔 / 耐久 / 药水等全部 NBT 保真。
     */
    public static String snapshotInventory(Player player) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        Inventory inv = player.getInventory();
        com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops =
                player.level().registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("slot", i);
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                ItemStack.CODEC.encodeStart(ops, s).result().ifPresent(el -> o.add("item", el));
            }
            arr.add(o);
        }
        return GSON.toJson(arr);
    }

    /** 单个物品 JSON -> ItemStack（给 GUI 反序列化用） */
    public static ItemStack decodeItemJson(net.minecraft.core.HolderLookup.Provider registries,
                                           com.google.gson.JsonElement el) {
        try {
            com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops =
                    registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            return ItemStack.CODEC.parse(ops, el).result().orElse(ItemStack.EMPTY);
        } catch (Exception ex) {
            return ItemStack.EMPTY;
        }
    }

    /** JSON -> 逐槽写回背包（盔甲 / 副手也恢复）；解析失败静默退回不折腾 */
    public static void applyInventoryJson(Player player, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            Inventory inv = player.getInventory();
            com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops =
                    player.level().registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            for (com.google.gson.JsonElement e : arr) {
                com.google.gson.JsonObject o = e.getAsJsonObject();
                int slot = o.get("slot").getAsInt();
                if (slot < 0 || slot >= inv.getContainerSize()) {
                    continue;
                }
                ItemStack st = ItemStack.EMPTY;
                if (o.has("item")) {
                    st = ItemStack.CODEC.parse(ops, o.get("item")).result().orElse(ItemStack.EMPTY);
                }
                inv.setItem(slot, st);
            }
        } catch (Exception ex) {
            com.aiplayer.AiPlayerMod.LOGGER.warn("[aiplayer] 装备包 JSON 解析失败: {}", ex.toString());
        }
    }

    /**
     * 按档案发装备包，优先级：AI 的 kitNbt（JSON 完整快照）> kitItems（字符串清单）
     * > 全局默认 kit JSON > 内置默认套装。
     */
    public static void giveKit(Player player, com.aiplayer.config.AiProfile profile) {
        if (profile != null && profile.kitNbt != null && !profile.kitNbt.isBlank()) {
            applyInventoryJson(player, profile.kitNbt);
            player.setHealth(player.getMaxHealth());
            return;
        }
        if (profile != null && profile.kitItems != null && !profile.kitItems.isBlank()) {
            giveKit(player, profile.kitItems);
            return;
        }
        String def = com.aiplayer.config.AiPlayerConfig.get().defaultKitNbt;
        if (def != null && !def.isBlank()) {
            applyInventoryJson(player, def);
            player.setHealth(player.getMaxHealth());
            return;
        }
        giveKit(player);
    }

    /** 给 AI 一套基础装备（皮革以上 -> 铁 -> 钻石 -> 下界合金） */
    public static void giveKit(Player player) {
        Inventory inv = player.getInventory();
        inv.add(new ItemStack(Items.DIAMOND_PICKAXE));
        inv.add(new ItemStack(Items.DIAMOND_AXE));
        inv.add(new ItemStack(Items.DIAMOND_SHOVEL));
        inv.add(new ItemStack(Items.DIAMOND_SWORD));
        inv.add(new ItemStack(Items.BOW));
        inv.add(new ItemStack(Items.ARROW, 64));
        inv.add(new ItemStack(Items.WOOL.white(), 192));
        inv.add(new ItemStack(Items.COOKED_BEEF, 32));
        inv.add(new ItemStack(Items.OBSIDIAN, 16));
        inv.add(new ItemStack(Items.END_CRYSTAL, 16));
        inv.add(new ItemStack(Items.WATER_BUCKET));
        inv.add(new ItemStack(Items.ENDER_PEARL, 16));
        inv.add(new ItemStack(Items.GOLDEN_APPLE, 8));
        inv.add(new ItemStack(Items.DIAMOND_HELMET));
        inv.add(new ItemStack(Items.DIAMOND_CHESTPLATE));
        inv.add(new ItemStack(Items.DIAMOND_LEGGINGS));
        inv.add(new ItemStack(Items.DIAMOND_BOOTS));
        inv.add(ItemStack.EMPTY);
        inv.add(new ItemStack(Items.SHIELD));
        inv.add(new ItemStack(Items.TOTEM_OF_UNDYING));
        // 护甲直接穿上，副手放盾牌
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
        player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        player.setHealth(player.getMaxHealth());
    }
}