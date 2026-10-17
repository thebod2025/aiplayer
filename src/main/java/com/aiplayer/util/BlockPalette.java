package com.aiplayer.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/** 搭路 / 放置用的方块调色板（名字 -> 物品）。不需要访问注册表，安全可靠。 */
public final class BlockPalette {
    private static final Map<String, Item> BLOCKS = new LinkedHashMap<>();

    static {
        BLOCKS.put("wool", Items.WOOL.white());
        BLOCKS.put("white_wool", Items.WOOL.white());
        BLOCKS.put("oak_planks", Items.OAK_PLANKS);
        BLOCKS.put("planks", Items.OAK_PLANKS);
        BLOCKS.put("cobblestone", Items.COBBLESTONE);
        BLOCKS.put("stone", Items.STONE);
        BLOCKS.put("obsidian", Items.OBSIDIAN);
        BLOCKS.put("crying_obsidian", Items.CRYING_OBSIDIAN);
        BLOCKS.put("netherrack", Items.NETHERRACK);
        BLOCKS.put("dirt", Items.DIRT);
        BLOCKS.put("end_stone", Items.END_STONE);
        BLOCKS.put("scaffolding", Items.SCAFFOLDING);
        BLOCKS.put("sandstone", Items.SANDSTONE);
        BLOCKS.put("terracotta", Items.TERRACOTTA);
        BLOCKS.put("glass", Items.GLASS);
    }

    private BlockPalette() {
    }

    public static Item resolve(String name) {
        if (name == null) {
            return Items.WOOL.white();
        }
        Item item = BLOCKS.get(name.toLowerCase());
        return item != null ? item : Items.WOOL.white();
    }

    public static Map<String, Item> all() {
        return BLOCKS;
    }
}