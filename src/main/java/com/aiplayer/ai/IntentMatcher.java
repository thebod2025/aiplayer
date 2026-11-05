package com.aiplayer.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地意图匹配（不依赖大模型）。
 * <p>
 * 玩家说的话和下面这份"行为提示词"表逐条做相似度比较，得分最高且超过阈值的意图
 * 就当成玩家想让它做的事。相比原来的关键词命中，这里能容忍：
 * <ul>
 *   <li>插入字/词 —— "你能去砍点树吗" 命中 "砍树"；</li>
 *   <li>语序/口语变化 —— "帮我盖个房子吧" 命中 "盖房子"；</li>
 *   <li>少量错字 —— 中文按字符二元组 + 最长公共子序列，英文按整词相似度。</li>
 * </ul>
 * 所有提示词统一小写，英文按整词边界判断，避免 "stop" 命中 "stopwatch"。
 */
final class IntentMatcher {
    enum Intent {
        STOP, FOLLOW, COME, ATTACK, PVP_ON, PVP_OFF, BOSS,
        MINE_ORE, MINE_TUNNEL, CHOP, EXPLORE, HOME, BUILD, GIVE, SLEEP
    }

    private record Entry(Intent intent, String phrase) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    /** 相似度阈值：低于这个分数就当没听懂 */
    private static final double MIN_SCORE = 0.78D;

    static {
        add(Intent.STOP,
                "停下来", "停下", "停一下", "别动", "不要动", "别乱动", "别走", "站住",
                "停手", "住手", "别追", "别追了", "别跑了", "别打了", "别打", "不要打了",
                "stop", "halt", "freeze", "stand still", "wait", "hold on");
        add(Intent.FOLLOW,
                "跟着我", "跟着我走", "跟我走", "跟我来", "跟紧我", "跟随我", "跟上来",
                "跟住我", "跟好", "跟我一起", "follow me", "follow", "come with me",
                "stay with me", "join me");
        add(Intent.COME,
                "过来", "来这里", "到我这里", "到我这", "来我这", "过来一下", "快来",
                "靠近点", "到我这儿来", "过来吧", "come here", "come to me", "come",
                "over here", "here");
        add(Intent.ATTACK,
                "去打", "打那个", "攻击那个", "揍他", "干掉他", "杀了他", "帮我打",
                "打怪物", "打怪", "打它", "收拾他", "收拾", "上啊", "干掉", "杀掉",
                "attack that", "attack the", "kill that", "kill the", "fight that", "attack it");
        add(Intent.PVP_ON,
                "允许pvp", "可以pvp", "开pvp", "打开pvp", "开启pvp", "允许打架",
                "可以打架", "开打", "随便打", "可以打人", "开杀", "pvp on",
                "turn pvp on", "enable pvp", "allow pvp");
        add(Intent.PVP_OFF,
                "关闭pvp", "关pvp", "关掉pvp", "停止pvp", "禁止pvp", "不要打架",
                "别打架", "不许打", "pvp off", "turn pvp off", "disable pvp", "stop pvp");
        add(Intent.BOSS,
                "打末影龙", "打龙", "打boss", "去末地", "进末地", "挑战末影龙",
                "杀末影龙", "打末影龙去", "末影龙", "去打龙", "fight the dragon",
                "go to the end", "kill the dragon", "ender dragon");
        add(Intent.MINE_ORE,
                "去挖", "挖矿", "挖点", "挖铁", "挖钻", "挖钻石", "挖煤", "挖金",
                "挖石头", "挖沙", "挖土", "采矿", "下矿", "找矿", "挖矿去", "挖点什么",
                "挖点矿", "go mining", "mine some", "mine ore", "dig some",
                "dig for ore", "mining", "get some ore");
        add(Intent.MINE_TUNNEL,
                "挖矿道", "矿道", "挖条道", "开矿道", "挖条隧道", "挖隧道", "开隧道",
                "挖个矿洞", "打个矿洞", "挖通道", "矿洞", "挖个洞", "挖洞",
                "dig tunnel", "tunnel", "mine shaft");
        add(Intent.CHOP,
                "去砍", "砍树", "砍木头", "砍点木头", "砍点树", "砍些木头", "砍棵树",
                "砍一棵树", "伐木", "木头", "木材", "弄点木头", "找木头", "砍树去",
                "chop tree", "cut tree", "chop wood", "cut some wood", "get wood",
                "gather wood", "lumber");
        add(Intent.EXPLORE,
                "去探索", "探索", "出去转转", "出去看看", "周围转转", "到处走走",
                "出去走走", "逛逛", "侦查", "探路", "巡逻", "探图", "去外面看看",
                "go explore", "explore", "scout", "look around", "patrol");
        add(Intent.HOME,
                "回家", "回基地", "回营地", "回窝", "回家去", "回家吧", "回去吧",
                "回老巢", "go home", "back home", "return home");
        add(Intent.BUILD,
                "建房子", "盖房子", "建个房", "盖个房", "搭个房", "造个房", "建屋",
                "建房", "搭屋", "盖屋", "造屋", "建个", "搭个", "盖个", "造个",
                "建座", "建一", "搭一", "造一", "盖座", "帮我建", "给我建", "给你建",
                "弄个房子", "建一栋", "盖一栋", "建墙", "搭墙", "砌墙", "盖墙",
                "围墙", "修墙", "建塔", "盖塔", "搭塔", "建桥", "搭桥", "铺路",
                "修路", "搭平台", "建平台", "铺地板", "围起来", "围个", "围一圈",
                "build", "build house", "build a house", "shelter", "house", "wall",
                "tower", "platform", "fence", "bridge", "path", "road");
        add(Intent.GIVE,
                "给我", "给点", "丢给", "扔给", "送我", "给我点", "给我些", "来点",
                "拿给我", "给一些", "给我一些", "给我一点", "give me", "bring me",
                "hand me", "give some");
        add(Intent.SLEEP,
                "睡觉", "去睡", "睡一下", "睡一会", "睡会儿", "睡会", "睡一觉",
                "去睡觉", "上床", "躺下", "休息一下", "休息", "困了", "该睡了",
                "晚安", "睡吧", "眯一会", "睡觉去", "sleep", "go to sleep",
                "go to bed", "take a nap", "nap", "rest");
    }

    private IntentMatcher() {
    }

    private static void add(Intent intent, String... phrases) {
        for (String phrase : phrases) {
            if (phrase == null || phrase.isBlank()) {
                continue;
            }
            ENTRIES.add(new Entry(intent, phrase.trim().toLowerCase(Locale.ROOT)));
        }
    }

    /** 返回最匹配的意图；没听懂返回 null */
    static Intent match(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT).trim();
        String tp = stripPunct(t);
        Intent bestIntent = null;
        double bestScore = 0.0D;
        int bestPrio = -1;
        int bestLen = 0;
        for (Entry entry : ENTRIES) {
            String phrase = entry.phrase();
            double score = score(t, tp, phrase);
            if (score < MIN_SCORE) {
                continue;
            }
            int prio = priority(entry.intent());
            // 分数优先；同分时更"具体"的意图（优先级高、提示词更长）胜出
            if (score > bestScore + 1.0E-9D
                    || (Math.abs(score - bestScore) <= 1.0E-9D
                    && (prio > bestPrio || (prio == bestPrio && phrase.length() > bestLen)))) {
                bestIntent = entry.intent();
                bestScore = score;
                bestPrio = prio;
                bestLen = phrase.length();
            }
        }
        return bestIntent;
    }

    /** 同一句话命中多个意图时的倾向：越具体的动作优先级越高 */
    private static int priority(Intent intent) {
        return switch (intent) {
            case SLEEP, STOP -> 8;
            case BUILD, MINE_TUNNEL -> 7;
            case BOSS, CHOP, MINE_ORE, PVP_ON, PVP_OFF -> 6;
            case ATTACK, FOLLOW, COME, HOME -> 5;
            case EXPLORE -> 4;
            case GIVE -> 2;
        };
    }

    /**
     * 单条提示词的相似度。取值 0~1：
     * <ul>
     *   <li>提示词整段出现在玩家话里 → 1.0；</li>
     *   <li>否则取"连续双字重合度 / 单字重合度 / 最长公共子序列覆盖率"里最好的一个，
     *       对最后一项略打折扣，避免只靠词序松散拼出来的句子得分过高。</li>
     * </ul>
     */
    private static double score(String text, String textStripped, String phrase) {
        if (isAscii(phrase)) {
            if (containsWord(text, phrase)) {
                return 1.0D;
            }
            return tokenSimilarity(text, phrase) * 0.95D;
        }
        String phraseStripped = stripPunct(phrase);
        if (phraseStripped.isEmpty()) {
            return 0.0D;
        }
        if (text.contains(phrase) || textStripped.contains(phraseStripped)) {
            return 1.0D;
        }
        // 子序列覆盖率只在两种情况用：提示词够长，或者玩家这句话本来就短。
        // 否则 "给点" 这种两字提示词会把 "给我点建议" 也算成"要东西"。
        double lcsScore = 0.0D;
        if (phraseStripped.length() >= 3 || textStripped.length() <= phraseStripped.length() + 2) {
            lcsScore = lcs(textStripped, phraseStripped) / (double) phraseStripped.length() * 0.9D;
        }
        double bigram = diceBigrams(textStripped, phraseStripped);
        double unigram = diceUnigrams(textStripped, phraseStripped);
        return Math.max(lcsScore, Math.max(bigram, unigram * 0.82D));
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    /** 英文整词包含（"stop" 不能命中 "stopwatch"） */
    private static boolean containsWord(String text, String phrase) {
        int from = 0;
        while (true) {
            int i = text.indexOf(phrase, from);
            if (i < 0) {
                return false;
            }
            int end = i + phrase.length();
            boolean left = i == 0 || !isWordChar(text.charAt(i - 1));
            boolean right = end >= text.length() || !isWordChar(text.charAt(end));
            if (left && right) {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }

    /** 英文按词比：提示词的每个词有多少出现在玩家话里 */
    private static double tokenSimilarity(String text, String phrase) {
        String[] want = phrase.split("[^a-z0-9']+");
        String[] have = text.split("[^a-z0-9']+");
        int hit = 0;
        for (String w : want) {
            if (w.isEmpty()) {
                continue;
            }
            for (String h : have) {
                if (h.equals(w) || h.length() > 2 && editSimilarity(h, w) >= 0.8D) {
                    hit++;
                    break;
                }
            }
        }
        return want.length == 0 ? 0.0D : hit / (double) want.length;
    }

    /** 归一化编辑距离相似度（1 - 距离/较长长度） */
    private static double editSimilarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0D;
        }
        return 1.0D - levenshtein(a, b) / (double) max;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    /** 最长公共子序列长度（按字符） */
    private static int lcs(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
            java.util.Arrays.fill(cur, 0);
        }
        return prev[b.length()];
    }

    /** 双字组 Dice 系数：容忍中间插字 */
    private static double diceBigrams(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return 0.0D;
        }
        List<String> ga = bigrams(a);
        List<String> gb = bigrams(b);
        int hit = 0;
        for (String g : ga) {
            if (gb.remove(g)) {
                hit++;
            }
        }
        return 2.0D * hit / (ga.size() + b.length() - 1);
    }

    /** 单字重合度（中文口语插入词时兜底） */
    private static double diceUnigrams(String a, String b) {
        List<Character> ca = new ArrayList<>();
        for (char c : a.toCharArray()) {
            ca.add(c);
        }
        List<Character> cb = new ArrayList<>();
        for (char c : b.toCharArray()) {
            cb.add(c);
        }
        int hit = 0;
        for (Character c : ca) {
            if (cb.remove(c)) {
                hit++;
            }
        }
        return 2.0D * hit / (ca.size() + b.length());
    }

    private static List<String> bigrams(String s) {
        List<String> out = new ArrayList<>(Math.max(1, s.length() - 1));
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    /** 去掉空格与常见标点，中英文混写（"允许 pvp" / "允许pvp"）也能比上 */
    private static String stripPunct(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if ("，。！？、；：,.!?;:（）()[]【】<>《》\"'`~～-—_".indexOf(c) >= 0) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
