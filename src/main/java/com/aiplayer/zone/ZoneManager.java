package com.aiplayer.zone;

import com.aiplayer.config.AiPlayerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 领地保护区：玩家用 {@code /aiplayer zone add} 标记的立方体区域。
 * <p>
 * 区域内的方块 AI 一律不挖、不放、不点火 —— 主要是防止 AI 在玩家家里挖矿、
 * 搭路时把墙拆了、或者随手放方块占地方。判断只看配置里的坐标，不扫世界，开销很小。
 * 数据存在 {@code config/aiplayer.json} 的 {@code zones} 字段里，重启后仍然有效。
 */
public final class ZoneManager {
    private ZoneManager() {
    }

    /** 一个受保护的立方体区域（坐标已规范化：min <= max） */
    public static final class Zone {
        public String name = "";
        /** 维度 id，例如 minecraft:overworld / the_nether / the_end */
        public String dimension = "minecraft:overworld";
        public int minX;
        public int minY;
        public int minZ;
        public int maxX;
        public int maxY;
        public int maxZ;

        public boolean contains(Level level, BlockPos pos) {
            if (level == null || pos == null) {
                return false;
            }
            if (this.dimension != null && !this.dimension.isBlank()
                    && !this.dimension.equals(level.dimension().identifier().toString())) {
                return false;
            }
            return pos.getX() >= this.minX && pos.getX() <= this.maxX
                    && pos.getY() >= this.minY && pos.getY() <= this.maxY
                    && pos.getZ() >= this.minZ && pos.getZ() <= this.maxZ;
        }

        /** 给指令/日志用的一行描述 */
        public String rangeText() {
            return this.minX + " " + this.minY + " " + this.minZ
                    + " ~ " + this.maxX + " " + this.maxY + " " + this.maxZ;
        }

        /** 区域中心（守卫巡逻回撤 / 巡逻点选不到时的兜底目标） */
        public BlockPos center() {
            return new BlockPos((this.minX + this.maxX) >> 1,
                    (this.minY + this.maxY) >> 1,
                    (this.minZ + this.maxZ) >> 1);
        }

        public int volume() {
            long v = (long) (this.maxX - this.minX + 1) * (this.maxY - this.minY + 1) * (this.maxZ - this.minZ + 1);
            return (int) Math.min(Integer.MAX_VALUE, v);
        }
    }

    private static List<Zone> list() {
        return AiPlayerConfig.get().zones;
    }

    /** 该位置所在的保护区；没被保护返回 null */
    public static Zone protecting(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        for (Zone zone : list()) {
            if (zone != null && zone.contains(level, pos)) {
                return zone;
            }
        }
        return null;
    }

    /** 该位置是否在保护区内（AI 不能挖 / 放 / 点火） */
    public static boolean isProtected(Level level, BlockPos pos) {
        return protecting(level, pos) != null;
    }

    public static List<Zone> all() {
        return new ArrayList<>(list());
    }

    public static Zone find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim();
        for (Zone zone : list()) {
            if (zone != null && zone.name != null && zone.name.equalsIgnoreCase(key)) {
                return zone;
            }
        }
        return null;
    }

    /**
     * 添加或更新一个保护区（同名视为修改范围）。
     *
     * @param name  区域名（玩家自己取，便于 remove）
     * @param level 执行指令时所在的维度
     * @param a     坐标 1
     * @param b     坐标 2
     * @return 创建好（或更新后）的区域
     */
    public static Zone add(String name, Level level, BlockPos a, BlockPos b) {
        if (name == null || name.isBlank() || level == null || a == null || b == null) {
            return null;
        }
        String key = name.trim();
        Zone zone = find(key);
        if (zone == null) {
            zone = new Zone();
            zone.name = key;
            list().add(zone);
        }
        zone.dimension = level.dimension().identifier().toString();
        zone.minX = Math.min(a.getX(), b.getX());
        zone.minY = Math.min(a.getY(), b.getY());
        zone.minZ = Math.min(a.getZ(), b.getZ());
        zone.maxX = Math.max(a.getX(), b.getX());
        zone.maxY = Math.max(a.getY(), b.getY());
        zone.maxZ = Math.max(a.getZ(), b.getZ());
        AiPlayerConfig.get().save();
        return zone;
    }

    public static boolean remove(String name) {
        Zone zone = find(name);
        if (zone == null) {
            return false;
        }
        list().remove(zone);
        AiPlayerConfig.get().save();
        return true;
    }

    public static int clear() {
        int n = list().size();
        list().clear();
        if (n > 0) {
            AiPlayerConfig.get().save();
        }
        return n;
    }
}
