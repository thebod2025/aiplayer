package com.aiplayer.bot;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.api.LlmClient;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.aiplayer.config.RemoteBotProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 远程 bot 的大脑：把桥接上报的聊天 / 状态交给大模型，再把模型给的 JSON 转成指令下发。
 * <p>
 * 所有方法都在服务端线程调用；LLM 请求是异步的，回调里只做下发，不碰世界状态。
 */
final class BotBrain {
    /** 私聊 / 点名的回复冷却 */
    private static final long DIRECT_COOLDOWN_MS = 12000L;
    /** 公开聊天的主动接话冷却 */
    private static final long PUBLIC_COOLDOWN_MS = 60000L;
    /** 没人点名时随机接话的概率（百分比） */
    private static final int RANDOM_REPLY_PERCENT = 10;
    /** 状态超过这个时间就不做决策（外部进程可能已经卡住） */
    private static final long STATE_FRESH_MS = 8000L;
    /** 刚回过话的 6 秒内不做行动决策，避免“边说话边乱跑” */
    private static final long BRAIN_AFTER_REPLY_MS = 6000L;
    /** 两次决策之间的硬性下限 */
    private static final long BRAIN_MIN_GAP_MS = 20000L;
    /** 主动发言的最小间隔 */
    private static final long SAY_GAP_MS = 15000L;

    private BotBrain() {
    }

    // ---- 聊天回复 ----

    static void onChat(BotSession session, String speaker, String text, boolean whisper) {
        RemoteBotProfile profile = session.profile();
        if (profile == null || !profile.autoReply) {
            return;
        }
        AiPlayerConfig config = AiPlayerConfig.get();
        if (!config.llmEnabled) {
            return;
        }
        // 自己说的话不接
        if (equalsName(speaker, session.name()) || equalsName(speaker, session.username())) {
            return;
        }
        if (whisper) {
            session.setLastSpeaker(speaker);
        } else if (!profile.listenAll && !addressed(profile, session, speaker)) {
            return;
        }
        boolean direct = whisper || mentionsMe(session, text);
        if (!direct && !randomReply(text)) {
            return;
        }
        long now = System.currentTimeMillis();
        long since = now - session.lastLlmReplyAt();
        if (direct) {
            // 连着私聊时不要每个字都回一遍
            if (since < DIRECT_COOLDOWN_MS && session.lastLlmOwnerChannel()) {
                return;
            }
        } else if (since < PUBLIC_COOLDOWN_MS) {
            return;
        }
        session.markLlmReply(direct);
        String prompt = "远程服务器聊天记录：\n" + join(session.chatHistory()) + "\n\n"
                + speaker + " 对你说：" + text
                + "\n请只输出 JSON：{\"say\":\"你的回复\"}。回复要短，1~2 句，用中文，不要复述对方原话。";
        LlmClient.ask(profileOf(profile), chatSystemPrompt(profile), prompt, true).whenComplete((reply, error) -> {
            if (error != null) {
                String detail = describe(error);
                session.setError(detail);
                AiPlayerMod.LOGGER.warn("[aiplayer] 远程 bot {} 回话失败：{}", session.name(), detail);
                if (direct && now - session.lastFallbackAt() >= PUBLIC_COOLDOWN_MS) {
                    String fallback = "我这边有点卡，等下再说";
                    if (session.canSay(fallback)) {
                        session.markFallback();
                        session.say(fallback);
                        session.addChat("我：" + fallback);
                    }
                }
            } else {
                String say = parseSay(reply);
                if (say.isEmpty()) {
                    AiPlayerMod.LOGGER.warn("[aiplayer] 远程 bot {} 的模型输出里没有可用的 say：{}",
                            session.name(), shorten(reply));
                } else if (session.canSay(say)) {
                    session.clearError();
                    session.say(say);
                    session.addChat("我：" + say);
                }
            }
        });
    }

    /** 短句随机接话（不处理以 / 开头的指令） */
    private static boolean randomReply(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.length() >= 6 && !trimmed.startsWith("/")
                && ThreadLocalRandom.current().nextInt(100) < RANDOM_REPLY_PERCENT;
    }

    private static boolean mentionsMe(BotSession session, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : new String[]{session.name(), session.username()}) {
            if (!name.isBlank() && lower.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsName(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.equalsIgnoreCase(b);
    }

    /** 公开聊天里是否在跟“主人 / 最后私聊的人”说话 */
    private static boolean addressed(RemoteBotProfile profile, BotSession session, String speaker) {
        if (speaker == null || speaker.isBlank()) {
            return false;
        }
        String owner = session.lastSpeaker();
        return owner != null && speaker.equalsIgnoreCase(owner)
                ? true
                : profile != null && profile.ownerName != null && !profile.ownerName.isBlank()
                        && speaker.equalsIgnoreCase(profile.ownerName);
    }

    private static String ownerName(RemoteBotProfile profile, BotSession session) {
        String owner = session.lastSpeaker();
        if (owner != null && !owner.isBlank()) {
            return owner;
        }
        return profile != null && profile.ownerName != null && !profile.ownerName.isBlank()
                ? profile.ownerName : null;
    }

    /** 只看主人 / 最后私聊者相关的聊天，减少无关上下文 */
    private static List<String> filterChat(List<String> lines, String owner) {
        if (owner == null || owner.isBlank()) {
            return lines;
        }
        String prefix = owner + "：";
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(prefix) || line.startsWith("我：")) {
                out.add(line);
            }
        }
        return out;
    }

    private static String chatSystemPrompt(RemoteBotProfile profile) {
        String personality = profile.personality != null && !profile.personality.isBlank()
                ? profile.personality : "随和、爱帮忙";
        String owner = profile.ownerName != null && !profile.ownerName.isBlank()
                ? "你的主人是 " + profile.ownerName + "，优先听主人的话。" : "";
        return "你是 Minecraft 服务器里的 AI 玩家「" + profile.safeName() + "」。" + owner
                + "\n性格：" + personality
                + "\n你现在在一个远程服务器里，通过真实客户端角色行动。\n只输出 JSON：{\"say\":\"回复\"}。不要解释、不要加 markdown。"
                + (profile.listenAll ? "\n公开聊天里谁跟你说话都可以接。" : "\n公开聊天里只回应主人或最后私聊你的人，其他人的话不要接。")
                + "\n回复要像真人玩家：简短口语化，不照抄对方原话，不重复自己说过的话。";
    }

    // ---- 行动决策 ----

    static void tick(BotSession session) {
        RemoteBotProfile profile = session.profile();
        if (profile == null || !profile.autoBrain) {
            return;
        }
        AiPlayerConfig config = AiPlayerConfig.get();
        if (!config.llmEnabled) {
            return;
        }
        JsonObject state = session.state();
        if (state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - session.stateAt() > STATE_FRESH_MS) {
            return;
        }
        if (now - session.lastLlmReplyAt() < BRAIN_AFTER_REPLY_MS) {
            return;
        }
        if (now - session.busyAt() < Math.max(BRAIN_MIN_GAP_MS, config.llmIntervalMs)) {
            return;
        }
        session.markBusy();
        String owner = profile.listenAll ? null : ownerName(profile, session);
        String chats = profile.listenAll ? join(session.chatHistory()) : join(filterChat(session.chatHistory(), owner));
        String rule = profile.listenAll
                ? "可以听从任何玩家的公开聊天指令。"
                : "只服从「" + (owner == null ? "主人" : owner) + "」的指令，公开聊天里其他人的话不要当命令。";
        String prompt = "状态：" + describeState(state)
                + "\n最近聊天：" + chats
                + "\n最近事件：" + join(session.events())
                + "\n" + rule
                + "\n只输出 JSON：{\"say\":\"\",\"action\":\"none|goto|follow|attack|attack_nearest|mine|place|eat|equip|pickup|look|use|interact|click|close_window|stop|jump\",\"target\":\"\",\"x\":0,\"y\":0,\"z\":0}"
                + "\n先看状态里的事实再决定；有人优先 follow，有怪先 equip 再打，缺东西且确认安全才 mine/pickup；上一步失败就换目标或先 goto 靠近，不要原样重试；不要为了不闲着而乱走乱挖。";
        LlmClient.ask(profileOf(profile), brainSystemPrompt(profile), prompt, false).whenComplete((reply, error) -> {
            if (error != null) {
                String detail = describe(error);
                session.setError(detail);
                AiPlayerMod.LOGGER.warn("[aiplayer] 远程 bot {} 行动决策失败：{}", session.name(), detail);
            } else {
                session.clearError();
                applyDecision(session, reply);
            }
        });
    }

    private static String brainSystemPrompt(RemoteBotProfile profile) {
        String personality = profile.personality != null && !profile.personality.isBlank()
                ? profile.personality : "谨慎、务实";
        String owner = profile.ownerName != null && !profile.ownerName.isBlank()
                ? "主人：" + profile.ownerName + "，优先跟主人走、帮主人做事。" : "";
        String pvp = profile.pvp
                ? "可以攻击敌对玩家，但只在有把握时动手。"
                : "绝对不要主动攻击任何玩家；只能打僵尸、骷髅、苦力怕等敌对生物。";
        return "你是 Minecraft 服务器里的 AI 玩家「" + profile.safeName()
                + "」，有真实的角色：会走、会挖、会打、会聊天。" + owner
                + "\n性格：" + personality
                + "\n可执行的动作（action 只能填这些）：\n"
                + "- goto：走到 x/y/z 坐标（必须是当前状态附近的位置，不能瞬移）\n"
                + "- follow：跟随玩家，target 填玩家名\n"
                + "- attack：攻击目标，target 填生物/玩家名；target 留空表示打最近的敌对生物\n"
                + "- attack_nearest：打最近的敌对生物\n"
                + "- mine：挖方块，target 填方块名（如 oak_log、stone），也可以给 x/y/z\n"
                + "- place：在 x/y/z 放方块，target 填背包里的物品名\n"
                + "- eat：吃背包里的食物；equip：换上最好的武器/工具；pickup：去捡最近的掉落物\n"
                + "- look：看向某人或坐标；use：右键使用手里的物品；interact：右键按按钮/拉杆/开门/开箱子（附近 4 格或给坐标）；"
                + "click：点击当前界面槽位（target 填槽位号或物品名）；close_window：关掉当前界面；stop：停下；jump：跳一下；none：不动\n"
                + "状态字段：坐标=当前位置；站立方块/下方=脚下和身下的方块；服务端=服务器核心；模式=survival/creative/adventure/spectator；"
                + "手持=主手物品；执行中=正在做的动作；上一步=上次动作结果；界面=打开的容器或菜单。\n"
                + "使用规则：背包为空就别 eat/equip/place；adventure/spectator 模式不能挖方块；creative 模式不需要挖矿；"
                + "mine 只填方块名时会就近查找并自动靠近，报 not found 就是附近没有；执行中已经是 follow:某人 时不要重复 follow 同名，"
                + "除非要换目标或 stop；发言前先看最近聊天，没人跟你说话就保持沉默。\n"
                + "行为准则：\n"
                + "1）生存第一：血量 ≤10 或周围有敌对生物且手里没武器时先 goto 远离；饥饿 ≤14 先 eat。" + pvp + "\n"
                + "2）跟着人：附近有玩家（尤其是主人）时优先 follow，不要自己漫无目的地乱走。\n"
                + "3）做具体的事：确实缺木头/材料才 mine，挖之前先看状态里的 站立方块/下方，不要随手挖脚下或别人家的方块；"
                + "地上有东西就 pickup，有怪就先 equip 再 attack。\n"
                + "4）一次只做一件事；上一步失败（too far / no path / blocked）就换目标或先 goto 靠近，禁止原样重试。\n"
                + "5）不确定这是哪种服务器、或看不到地形时，先 follow 主人 / look 观察，不要乱挖方块、不要往下方是空气、水、岩浆的方向走。\n"
                + "6）没被点名就不要说话；要说也最多一句，15 秒内不要重复开口，不复述状态、不刷屏。\n"
                + "7）实在没事就 follow 主人或原地待命，不要为了「不闲着」而自己跑远或乱挖。\n"
                + "只输出 JSON：{\"say\":\"\",\"action\":\"none\",\"target\":\"\",\"x\":0,\"y\":0,\"z\":0}";
    }

    private static void applyDecision(BotSession session, String raw) {
        JsonObject decision = parseJson(raw);
        if (decision == null) {
            return;
        }
        String say = str(decision, "say");
        if (!say.isEmpty() && !say.equals("无") && session.allowSay(SAY_GAP_MS) && session.canSay(say)) {
            session.say(say);
            session.addChat("我：" + say);
        }
        String action = str(decision, "action").toLowerCase(Locale.ROOT);
        switch (action) {
            case "goto" -> {
                double x = num(decision, "x");
                double y = num(decision, "y");
                double z = num(decision, "z");
                if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                    session.commandGoto("goto", (int) Math.round(x), (int) Math.round(y), (int) Math.round(z));
                }
            }
            case "attack" -> {
                String target = str(decision, "target");
                if (!target.isEmpty()) {
                    session.commandTarget("attack", target);
                } else {
                    session.commandAction("attack_nearest");
                }
            }
            case "attack_nearest", "equip", "pickup", "close_window", "eat" ->
                    session.commandAction(action);
            case "interact" -> {
                double x = num(decision, "x");
                double y = num(decision, "y");
                double z = num(decision, "z");
                if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                    session.commandTargetPos("interact", "", (int) Math.round(x), (int) Math.round(y), (int) Math.round(z));
                } else {
                    session.commandAction("interact");
                }
            }
            case "click" -> {
                String target = str(decision, "target");
                if (!target.isEmpty()) {
                    session.commandTarget("click", target);
                }
            }
            case "follow", "use" -> {
                String target = str(decision, "target");
                if (!target.isEmpty()) {
                    session.commandTarget(action, target);
                }
            }
            case "mine", "place" -> {
                String target = str(decision, "target");
                double x = num(decision, "x");
                double y = num(decision, "y");
                double z = num(decision, "z");
                if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                    session.commandTargetPos(action, target, (int) Math.round(x), (int) Math.round(y), (int) Math.round(z));
                } else if (!target.isEmpty()) {
                    session.commandTarget(action, target);
                }
            }
            case "look" -> {
                String target = str(decision, "target");
                double x = num(decision, "x");
                double y = num(decision, "y");
                double z = num(decision, "z");
                if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                    session.commandTargetPos("look", target, (int) Math.round(x), (int) Math.round(y), (int) Math.round(z));
                } else if (!target.isEmpty()) {
                    session.commandTarget("look", target);
                }
            }
            case "stop", "jump" -> session.commandAction(action);
            default -> {
            }
        }
    }

    // ---- 状态摘要 ----

    private static String describeState(JsonObject state) {
        StringBuilder sb = new StringBuilder(240);
        if (state.has("dimension")) {
            sb.append("维度=").append(str(state, "dimension")).append(' ');
        }
        sb.append("坐标=(").append(round(num(state, "x"))).append(", ")
                .append(round(num(state, "y"))).append(", ")
                .append(round(num(state, "z"))).append(") ");
        sb.append("血量=").append(round(num(state, "health"))).append("/20 ");
        sb.append("饥饿=").append(round(num(state, "food"))).append("/20 ");
        String gameMode = str(state, "gameMode");
        if (!gameMode.isBlank()) {
            sb.append("模式=").append(gameMode).append(' ');
        }
        String brand = str(state, "serverBrand");
        if (!brand.isBlank()) {
            sb.append("服务端=").append(brand).append(' ');
        }
        String feet = str(state, "feet");
        if (!feet.isBlank() && !"unknown".equals(feet)) {
            sb.append("站立方块=").append(feet).append(' ');
        }
        String below = str(state, "below");
        if (!below.isBlank() && !"unknown".equals(below)) {
            sb.append("下方=").append(below).append(' ');
        }
        String held = str(state, "held");
        if (!held.isBlank()) {
            sb.append("手持=").append(held).append(' ');
        }
        String goal = str(state, "goal");
        if (!goal.isBlank() && !"none".equals(goal)) {
            sb.append("执行中=").append(goal).append(' ');
        }
        if (state.has("pathfinder") && !bool(state, "pathfinder")) {
            sb.append("无寻路插件 ");
        }
        if (state.has("isDay")) {
            sb.append(bool(state, "isDay") ? "白天 " : "夜晚 ");
        }
        appendArray(sb, state, "players", "附近玩家", 4);
        appendArray(sb, state, "hostiles", "附近敌对", 3);
        appendArray(sb, state, "inventory", "背包", 8);
        JsonElement window = state.get("window");
        if (window != null && window.isJsonObject()) {
            JsonObject win = window.getAsJsonObject();
            String title = str(win, "title");
            sb.append("界面=").append(title.isBlank() ? "已打开" : title).append(' ');
            appendArray(sb, win, "slots", "槽位", 8);
        }
        String lastAction = str(state, "lastAction");
        if (!lastAction.isBlank()) {
            sb.append("上一步=").append(lastAction).append(' ');
        }
        String text = sb.toString();
        return text.length() > 700 ? text.substring(0, 700) : text;
    }

    private static void appendArray(StringBuilder sb, JsonObject obj, String field, String label, int max) {
        JsonElement element = obj.get(field);
        if (element == null || !element.isJsonArray()) {
            return;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            return;
        }
        sb.append(label).append("=[");
        for (int i = 0; i < array.size() && i < max; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(array.get(i).isJsonPrimitive() ? array.get(i).getAsString() : "?");
        }
        sb.append("] ");
    }

    private static String join(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String round(double value) {
        return !Double.isFinite(value) ? "?" : String.valueOf(Math.round(value));
    }

    // ---- 工具 ----

    private static AiProfile profileOf(RemoteBotProfile remote) {
        AiProfile profile = new AiProfile();
        profile.name = remote.safeName();
        profile.personality = remote.personality;
        profile.ownerName = remote.ownerName;
        return profile;
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof LlmClient.LlmException http) {
            return "HTTP " + http.status + " " + http.getMessage();
        }
        String detail = cause.getMessage();
        return detail != null && !detail.isBlank() ? detail : cause.getClass().getSimpleName();
    }

    private static String shorten(String text) {
        if (text == null) {
            return "（空）";
        }
        String line = text.replace('\n', ' ').trim();
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    private static JsonObject parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonElement element = JsonParser.parseString(text.substring(start, end + 1));
                return element.isJsonObject() ? element.getAsJsonObject() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** 从模型输出里取一句能发的话 */
    private static String parseSay(String raw) {
        if (raw == null) {
            return "";
        }
        JsonObject json = parseJson(raw);
        if (json != null) {
            String say = str(json, "say");
            if (!say.isEmpty()) {
                return cleanSay(say);
            }
        }
        String text = raw.trim();
        return !text.startsWith("{") && !text.startsWith("[") ? cleanSay(text) : "";
    }

    private static String cleanSay(String text) {
        String line = text.replace('\n', ' ').replace('\r', ' ').trim();
        while (line.startsWith("\"") && line.length() > 1) {
            line = line.substring(1);
        }
        if (line.endsWith("\"")) {
            line = line.substring(0, line.length() - 1);
        }
        return line.length() > 180 ? line.substring(0, 180) : line;
    }

    private static String str(JsonObject obj, String field) {
        if (obj != null && obj.has(field) && obj.get(field).isJsonPrimitive()) {
            try {
                return obj.get(field).getAsString().trim();
            } catch (RuntimeException e) {
                return "";
            }
        }
        return "";
    }

    private static double num(JsonObject obj, String field) {
        if (obj != null && obj.has(field) && obj.get(field).isJsonPrimitive()) {
            try {
                return obj.get(field).getAsDouble();
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static boolean bool(JsonObject obj, String field) {
        if (obj != null && obj.has(field) && obj.get(field).isJsonPrimitive()) {
            try {
                return obj.get(field).getAsBoolean();
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }
}
