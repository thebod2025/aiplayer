package com.aiplayer.gui;

import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.entity.AiPlayerManager;
import com.aiplayer.util.InvUtil;
import com.aiplayer.util.ItemIdUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 装备包（kitItems）的箱子 GUI 配置器。
 * <p>
 * 原理：开一个 54 格（6 行）的假大箱子给主人，预先填入当前 kitItems 解析出来的物品，
 * 主人随意增删；关箱子的瞬间把箱子内容序列化回 {@code profile.kitItems}
 * （格式 {@code id x数量, id, ...}，和 InvUtil.giveKit 共用一套解析）。
 * 前 27 格对应 AI 主背包，最下一行 9 格对应 AI 快捷栏（AI 战斗时只用快捷栏物品）。
 * 纯服务端实现，不需要注册任何客户端 Screen。
 */
public final class KitChestGui {
    /** 6 行 = 主背包 27 格 + 快捷栏 9 格 */
    private static final int SIZE = 54;

    private KitChestGui() {
    }

    public static void open(ServerPlayer owner, AiPlayer ai) {
        open(owner, ai.profile());
    }

    /** 直接按 profile 打开（配置界面用：AI 还没生成也能先编辑装备包） */
    public static void open(ServerPlayer owner, AiProfile profile) {
        SimpleContainer container = new SimpleContainer(SIZE) {
            @Override
            public void stopOpen(ContainerUser user) {
                // 统一走 JSON 完整快照：AI 在线时序列化它的真实背包（含盔甲 / 副手 / 附魔 NBT），
                // 离线时从箱子内容构造完整快照。kitItems 字符串仍同步写一份作兼容兜底。
                // 注意：GUI 传进来的 profile 是编辑副本，必须写回 config.profiles 里的真对象才会落盘！
                AiProfile real = AiPlayerConfig.get().profiles.stream()
                        .filter(p -> p.name.equals(profile.name)).findFirst().orElse(profile);
                AiPlayer ai = AiPlayerManager.get().get(profile.name);
                if (ai != null && ai.isAlive()) {
                    applyToInventory(ai, this);
                    real.kitNbt = InvUtil.snapshotInventory(ai);
                } else {
                    real.kitNbt = buildJsonFromBox(owner, this);
                }
                real.kitItems = serialize(this);
                AiPlayerConfig.get().save();
                if (ai != null && ai.isAlive()) {
                    if (user instanceof ServerPlayer p) {
                        p.sendSystemMessage(Component.literal(
                                "[aiplayer] 已替换 " + profile.name + " 的背包"), false);
                    }
                } else if (user instanceof ServerPlayer p) {
                    p.sendSystemMessage(Component.literal(
                            "[aiplayer] " + profile.name + " 的装备包已保存（下次生成时生效）"), false);
                }
                super.stopOpen(user);
            }
        };
        // AI 在线：预填它当前的真实背包（所见即所得）；离线：预填 JSON 快照（旧 kitItems 兜底）
        AiPlayer online = AiPlayerManager.get().get(profile.name);
        if (online != null && online.isAlive()) {
            fillFromInventory(container, online);
        } else if (profile.kitNbt != null && !profile.kitNbt.isBlank()) {
            fillFromJson(container, profile.kitNbt, owner);
        } else {
            fill(container, profile.kitItems);
        }
        decorate(container);
        owner.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> ChestMenu.sixRows(id, inv, container),
                Component.literal(profile.name + " 的装备包")));
    }

    /** 编辑任意 JSON 背包快照（全局默认 kit 用）：关箱时把盒子内容回调给调用方保存 */
    public static void open(ServerPlayer owner, String title, String json, java.util.function.Consumer<String> onSave) {
        SimpleContainer container = new SimpleContainer(SIZE) {
            @Override
            public void stopOpen(ContainerUser user) {
                onSave.accept(buildJsonFromBox(owner, this));
                AiPlayerConfig.get().save();
                if (user instanceof ServerPlayer p) {
                    p.sendSystemMessage(Component.literal("[aiplayer] " + title + "已保存"), false);
                }
                super.stopOpen(user);
            }
        };
        fillFromJson(container, json, owner);
        decorate(container);
        owner.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> ChestMenu.sixRows(id, inv, container),
                Component.literal(title)));
    }

    /**
     * 箱子 54 格 -> JSON 快照（41 格完整背包）：
     * 行1: 0-3=盔甲(头胸腿脚) 5=副手 | 行2-4: 9-35=主背包 | 行6: 45-53=快捷栏
     */
    private static String buildJsonFromBox(ServerPlayer owner, SimpleContainer box) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops =
                owner.level().registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        // 盔甲 4 + 副手 1
        for (int i = 0; i < 4; i++) {
            addSlot(arr, ops, 36 + i, box.getItem(i));
        }
        addSlot(arr, ops, 40, box.getItem(5));
        // 主背包 27（box 行2-4 = 9-35）
        for (int i = 9; i < 36; i++) {
            addSlot(arr, ops, i, box.getItem(i));
        }
        // 快捷栏 9
        for (int i = 0; i < 9; i++) {
            addSlot(arr, ops, i, box.getItem(45 + i));
        }
        return new com.google.gson.Gson().toJson(arr);
    }

    private static void addSlot(com.google.gson.JsonArray arr,
                                com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops,
                                int slot, ItemStack s) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("slot", slot);
        if (!s.isEmpty() && !isMarker(s)) {
            ItemStack.CODEC.encodeStart(ops, s).result().ifPresent(el -> o.add("item", el));
        }
        arr.add(o);
    }

    /** 路标物品（屏障）不写进快照 */
    private static boolean isMarker(ItemStack s) {
        return s.is(net.minecraft.world.item.Items.BARRIER);
    }

    /** JSON 快照 -> 箱子：行1=盔甲/副手，行2-4=主背包，行6=快捷栏 */
    private static void fillFromJson(SimpleContainer box, String json, ServerPlayer owner) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (com.google.gson.JsonElement e : arr) {
                com.google.gson.JsonObject o = e.getAsJsonObject();
                if (!o.has("item")) {
                    continue;
                }
                int slot = o.get("slot").getAsInt();
                ItemStack st = InvUtil.decodeItemJson(owner.level().registryAccess(), o.get("item"));
                if (st.isEmpty()) {
                    continue;
                }
                if (slot >= 36 && slot < 40) {
                    box.setItem(slot - 36, st);          // 盔甲 -> 行1 左4格
                } else if (slot == 40) {
                    box.setItem(5, st);                  // 副手 -> 行1 第6格
                } else if (slot >= 9 && slot < 36) {
                    box.setItem(slot, st);               // 主背包 -> 行2-4
                } else if (slot >= 0 && slot < 9) {
                    box.setItem(45 + slot, st);          // 快捷栏 -> 行6
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 把箱子内容写进 AI 真实背包（41 格全量：盔甲 / 副手 / 主背包 / 快捷栏） */
    private static void applyToInventory(com.aiplayer.entity.AiPlayer ai, SimpleContainer box) {
        net.minecraft.world.entity.player.Inventory inv = ai.getInventory();
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
        }
        // 盔甲（box 0-3 -> inv 36-39）与副手（box 5 -> inv 40）
        for (int i = 0; i < 4; i++) {
            ItemStack s = box.getItem(i);
            inv.setItem(36 + i, isMarker(s) ? net.minecraft.world.item.ItemStack.EMPTY : s.copy());
        }
        ItemStack off = box.getItem(5);
        inv.setItem(40, isMarker(off) ? net.minecraft.world.item.ItemStack.EMPTY : off.copy());
        // 主背包（box 行2-4 -> inv 9-35）与快捷栏（box 行6 -> inv 0-8）
        for (int i = 9; i < 36; i++) {
            inv.setItem(i, box.getItem(i).copy());
        }
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, box.getItem(45 + i).copy());
        }
    }

    /** AI 当前背包 -> 箱子（和 applyToInventory 方向相反） */
    private static void fillFromInventory(SimpleContainer box, com.aiplayer.entity.AiPlayer ai) {
        net.minecraft.world.entity.player.Inventory inv = ai.getInventory();
        for (int i = 0; i < 4; i++) {
            box.setItem(i, inv.getItem(36 + i).copy());
        }
        box.setItem(5, inv.getItem(40).copy());
        for (int i = 9; i < 36; i++) {
            box.setItem(i, inv.getItem(i).copy());
        }
        for (int i = 0; i < 9; i++) {
            box.setItem(45 + i, inv.getItem(i).copy());
        }
    }

    /**
     * 路标：行1 的 4/6/7/8 格与行5（36-44）插命名玻璃板。
     * 只碰分隔格，不覆盖盔甲（0-3）、副手（5）、主背包（9-35）、快捷栏（45-53）。
     */
    private static void decorate(SimpleContainer box) {
        ItemStack armor = marker("盔甲 & 副手（按名字对应格子）");
        ItemStack down = marker("↓ ↓ ↓  快捷栏（9格）↓ ↓ ↓");
        box.setItem(4, armor.copy());
        box.setItem(6, armor.copy());
        box.setItem(7, armor.copy());
        box.setItem(8, armor.copy());
        for (int i = 36; i <= 44; i++) {
            box.setItem(i, down.copy());
        }
    }

    private static ItemStack marker(String name) {
        ItemStack s = new ItemStack(net.minecraft.world.item.Items.BARRIER);
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    /** 把 kitItems 字符串解析进箱子：前 27 项 → 行2-4（主背包），第 28-36 项 → 行6（快捷栏） */
    private static void fill(SimpleContainer container, String kitItems) {
        if (kitItems == null || kitItems.isBlank()) {
            return;
        }
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        for (String part : kitItems.split("[,;\\n]")) {
            ItemStack stack = ItemIdUtil.parseStack(part);
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        int n = Math.min(stacks.size(), 27);
        for (int i = 0; i < n; i++) {
            container.setItem(9 + i, stacks.get(i));
        }
        for (int i = 27; i < Math.min(stacks.size(), 36); i++) {
            // 底行 = 槽 45-53（6 行箱子的最后一行）
            container.setItem(45 + (i - 27), stacks.get(i));
        }
    }

    /** 箱子内容 -> "id x数量, ..."：行2-4 顺序在前，行6 顺序在后；盔甲/副手/玻璃板不含（JSON 快照才有） */
    private static String serialize(SimpleContainer container) {
        StringBuilder sb = new StringBuilder();
        for (int i = 9; i < 36; i++) {
            appendStack(sb, container.getItem(i));
        }
        for (int i = 45; i < 54; i++) {
            appendStack(sb, container.getItem(i));
        }
        return sb.toString();
    }

    private static void appendStack(StringBuilder sb, ItemStack stack) {
        if (stack.isEmpty() || isMarker(stack)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(ItemIdUtil.idOf(stack.getItem()));
        if (stack.getCount() > 1) {
            sb.append(" x").append(stack.getCount());
        }
    }
}
