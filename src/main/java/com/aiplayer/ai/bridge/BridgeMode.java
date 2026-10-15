package com.aiplayer.ai.bridge;

/** 搭路方式（可在 GUI 配置） */
public enum BridgeMode {
    OFF(0, "关闭"),
    SNEAK(1, "蹲起搭"),
    GOD(2, "神桥"),
    TELLY(3, "Telly搭"),
    SPEED_TELLY(4, "SpeedTelly");

    public final int id;
    public final String display;

    BridgeMode(int id, String display) {
        this.id = id;
        this.display = display;
    }

    public static BridgeMode byId(int id) {
        for (BridgeMode m : values()) {
            if (m.id == id) {
                return m;
            }
        }
        return OFF;
    }

    public boolean isActive() {
        return this != OFF;
    }
}