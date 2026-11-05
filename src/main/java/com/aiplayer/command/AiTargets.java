package com.aiplayer.command;

import com.aiplayer.entity.AiPlayer;
import com.aiplayer.entity.AiPlayerManager;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AI 目标选择器解析（指令里的名字参数都走这里）。
 *
 * <ul>
 *   <li>{@code @all} / {@code @a} / {@code *}：所有 AI</li>
 *   <li>{@code @team:红队}：同队（大小写不敏感）</li>
 *   <li>{@code @near} / {@code @p}：离执行者最近的一只</li>
 *   <li>{@code @r}：随机一只</li>
 *   <li>{@code AI*} / {@code AI?}：名字通配</li>
 *   <li>{@code AI1,AI2}：多个名字 / 选择器用逗号分隔</li>
 * </ul>
 */
public final class AiTargets {

    private AiTargets() {
    }

    /** 允许 {@code @}、{@code ,}、{@code *} 等选择器字符（原版 word() 不接受 @）。 */
    public static ArgumentType<String> argument() {
        return new SelectorArgument();
    }

    /**
     * 向原版指令注册表登记选择器参数类型。
     * <p>
     * 自定义参数类型不登记的话，服务端给客户端同步指令树时会认不出它，
     * 轻则该指令不可用，重则整棵指令树发送失败。
     */
    public static void register() {
        net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry.registerArgumentType(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("aiplayer", "target_selector"),
                SelectorArgument.class,
                net.minecraft.commands.synchronization.SingletonArgumentInfo.contextFree(AiTargets::argument));
    }

    public static List<AiPlayer> resolve(CommandSourceStack source, String selector) {
        Set<AiPlayer> out = new LinkedHashSet<>();
        if (selector == null) {
            return new ArrayList<>();
        }
        for (String token : selector.split(",")) {
            add(source, token.trim(), out);
        }
        return new ArrayList<>(out);
    }

    private static void add(CommandSourceStack source, String token, Set<AiPlayer> out) {
        if (token.isEmpty()) {
            return;
        }
        String key = token.toLowerCase(Locale.ROOT);
        Collection<AiPlayer> all = AiPlayerManager.get().all();
        switch (key) {
            case "@all", "@a", "*", "all" -> {
                out.addAll(all);
                return;
            }
            case "@near", "@p" -> {
                Vec3 at = source.getPosition();
                AiPlayer best = null;
                for (AiPlayer ai : all) {
                    if (ai.level() != source.getLevel()) {
                        continue;
                    }
                    if (best == null || ai.position().distanceToSqr(at) < best.position().distanceToSqr(at)) {
                        best = ai;
                    }
                }
                if (best != null) {
                    out.add(best);
                }
                return;
            }
            case "@r" -> {
                List<AiPlayer> pool = new ArrayList<>(all);
                if (!pool.isEmpty()) {
                    out.add(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
                }
                return;
            }
            default -> {
            }
        }
        if (key.startsWith("@team:") || key.startsWith("@t:")) {
            String team = token.substring(token.indexOf(':') + 1).trim();
            if (team.isEmpty()) {
                return;
            }
            for (AiPlayer ai : all) {
                if (ai.profile() == null || ai.profile().team == null) {
                    continue;
                }
                if (ai.profile().team.equalsIgnoreCase(team)) {
                    out.add(ai);
                }
            }
            return;
        }
        if (token.indexOf('*') >= 0 || token.indexOf('?') >= 0) {
            String regex = globToRegex(key);
            for (AiPlayer ai : all) {
                if (nameOf(ai).toLowerCase(Locale.ROOT).matches(regex)) {
                    out.add(ai);
                }
            }
            return;
        }
        AiPlayer exact = AiPlayerManager.get().get(token);
        if (exact != null) {
            out.add(exact);
        }
    }

    public static String nameOf(AiPlayer ai) {
        return ai.profile() != null && ai.profile().name != null ? ai.profile().name : ai.getName().getString();
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 读取到空白为止的一整段文本，交给 {@link #resolve} 解析。 */
    private static final class SelectorArgument implements ArgumentType<String> {
        private static final Collection<String> EXAMPLES =
                List.of("@all", "@team:红队", "@near", "@r", "AI1,AI2", "AI*");

        @Override
        public String parse(StringReader reader) throws CommandSyntaxException {
            int start = reader.getCursor();
            while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
                reader.skip();
            }
            if (reader.getCursor() == start) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
            }
            return reader.getString().substring(start, reader.getCursor());
        }

        @Override
        public Collection<String> getExamples() {
            return EXAMPLES;
        }
    }
}
