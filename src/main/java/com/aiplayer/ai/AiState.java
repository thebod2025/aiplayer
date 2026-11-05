package com.aiplayer.ai;

/** AI 当前状态（仅用于显示与调试） */
public enum AiState {
    IDLE("空闲"),
    SLEEPING("睡觉"),
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
    BOSS("末影龙"),
    DEAD("死亡");

    public final String display;

    AiState(String display) {
        this.display = display;
    }

    /** 翻译键：UI 显示用（{@link #display} 是中文，只用于喂给模型的提示词与日志） */
    public String key() {
        return "aiplayer.state." + this.name().toLowerCase(java.util.Locale.ROOT);
    }
}