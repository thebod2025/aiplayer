package com.aiplayer.ai;

/** AI 当前状态（仅用于显示与调试） */
public enum AiState {
    IDLE("空闲"),
    MOVING("移动"),
    MINING("挖方块"),
    PLACING("放方块"),
    COMBAT("近战"),
    RANGED("远程"),
    CRYSTAL("水晶PvP"),
    EATING("进食"),
    HUNTING("狩猎"),
    BRIDGING("搭路"),
    ESCAPING("逃生"),
    CHATTING("聊天"),
    COMMANDING("执行命令"),
    FOLLOWING("跟随"),
    INTERACT("交互"),
    DEAD("死亡");

    public final String display;

    AiState(String display) {
        this.display = display;
    }
}