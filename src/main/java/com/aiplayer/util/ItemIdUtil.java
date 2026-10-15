package com.aiplayer.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物品 ID 解析：把玩家 / LLM 写的一串文字变成真正的物品。
 * <p>
 * LLM 给的 ID 往往不规范（{@code diamond_sword} / {@code Diamond Sword} / {@code minecraft:bow}
 * / {@code golden_apple x8}），所以这里依次尝试：
 * 规范化写法 → 补 {@code minecraft:} 默认命名空间 → 在注册表里按路径模糊匹配。
 * 模糊匹配出现多个候选时宁可返回 null，也不要选错物品。
 */
public final class ItemIdUtil {
    /** 末尾数量写法："golden_apple x8" / "golden_apple 8" / "minecraft:bow:8" */
    private static final Pattern TRAILING_COUNT =
            Pattern.compile("(?i)(?:\\s+[x*]?\\s*|:)(\\d{1,4})\\s*$");
    /** 单个物品的解析结果缓存（LLM 会反复引用同一批物品，避免每次都扫一遍注册表） */
    private static final Map<String, Item> CACHE = new HashMap<>();
    private static final int CACHE_LIMIT = 512;

    private ItemIdUtil() {
    }

    /** 解析单个物品 ID；找不到返回 null */
    public static Item resolve(String raw) {
        if (raw == null) {
            return null;
        }
        String text = normalize(raw);
        if (text.isEmpty()) {
            return null;
        }
        Item cached = CACHE.get(text);
        if (cached != null) {
            return cached;
        }
        Item found = byId(text);
        if (found == null) {
            found = fuzzy(text);
        }
        if (found != null && CACHE.size() < CACHE_LIMIT) {
            CACHE.put(text, found);
        }
        return found;
    }

    /**
     * 解析带数量的写法，例如 {@code "minecraft:golden_apple"}、{@code "golden_apple x8"}、
     * {@code "diamond_sword 1"}。解析不出物品时返回 {@link ItemStack#EMPTY}。
     */
    public static ItemStack parseStack(String spec) {
        if (spec == null) {
            return ItemStack.EMPTY;
        }
        String text = spec.trim();
        int count = 1;
        Matcher m = TRAILING_COUNT.matcher(text);
        if (m.find()) {
            count = clampCount(m.group(1));
            text = text.substring(0, m.start()).trim();
        }
        Item item = resolve(text);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    /** 背包内容摘要，给 LLM 看的物品 ID 清单（省略 {@code minecraft:} 前缀省 token） */
    public static String describe(Player player, int maxChars) {
        Inventory inv = player.getInventory();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            counts.merge(idOf(stack.getItem()), stack.getCount(), Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String part = e.getKey() + " x" + e.getValue();
            if (sb.length() + part.length() + 2 > maxChars) {
                sb.append(" ...");
                break;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /** 物品的注册名；原版物品省略 {@code minecraft:} 前缀，模组物品保留命名空间（否则解析不回去） */
    public static String idOf(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            return "unknown";
        }
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static int clampCount(String raw) {
        try {
            return Math.max(1, Math.min(6400, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 去掉空格、转小写，并补齐 {@code minecraft:} 前缀 */
    private static String normalize(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return "";
        }
        // "diamond sword" 这种带空格的写法：LLM 常见，直接当路径用（ID 里只允许下划线）
        if (text.indexOf(':') < 0) {
            text = text.replace(' ', '_');
            return "minecraft:" + text;
        }
        return text.replace(' ', '_');
    }

    private static Item byId(String text) {
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            return null;
        }
        try {
            Identifier id = Identifier.fromNamespaceAndPath(text.substring(0, colon), text.substring(colon + 1));
            return BuiltInRegistries.ITEM.getValue(id);
        } catch (RuntimeException e) {
            // 非法字符（LLM 偶尔会写出奇怪的东西）：当作找不到
            return null;
        }
    }

    /** 按路径模糊匹配：{@code stone} / {@code sword} 这种不完整写法也能认出来 */
    private static Item fuzzy(String text) {
        int colon = text.indexOf(':');
        String tail = colon >= 0 ? text.substring(colon + 1) : text;
        if (tail.isEmpty()) {
            return null;
        }
        // 第一轮：路径完全相同优先（"stone" 就该是 stone，而不是和 end_stone / stone_slab 算歧义）
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (tail.equals(id.getPath())) {
                return BuiltInRegistries.ITEM.getValue(id);
            }
        }
        // 第二轮：允许 stone -> end_stone / stone_bricks 这种部分匹配，但必须唯一
        Item found = null;
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            String path = id.getPath();
            if (!path.endsWith("_" + tail) && !path.startsWith(tail + "_")) {
                continue;
            }
            if (found != null) {
                // 多个候选（例如 "sword" 同时命中 diamond_sword / iron_sword）：
                // 宁可不做，也不要让 AI 拿错东西
                return null;
            }
            found = BuiltInRegistries.ITEM.getValue(id);
        }
        return found;
    }
}