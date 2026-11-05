package com.aiplayer.util;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 调试日志：把本模组的日志另存一份到 {@code logs/aiplayer/}。
 * <p>
 * 由 {@link AiPlayerConfig#debugLog} 开关控制（默认关闭）。写入前会把 API Key、Bearer、
 * token 之类的敏感串打码 —— 玩家把日志贴出来排查问题时，不会顺带泄露自己的 Key。
 * <p>
 * 只挂在本模组的 logger（{@code aiplayer}）上，别的模组和原版日志不会混进来；控制台输出不变。
 */
public final class DebugLog {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");
    /** 最多保留几份历史文件（含本次），避免日志文件夹无限膨胀 */
    private static final int KEEP_FILES = 5;

    /** 通用敏感串：Bearer xxx / sk-xxx / "apiKey": "xxx" */
    private static final Pattern P_BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-+/=]{6,}");
    private static final Pattern P_SK = Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{6,}");
    private static final Pattern P_FIELD = Pattern.compile(
            "(?i)((?:api[_-]?key|apikey|x-api-key|authorization|access[_-]?token|secret)['\"]?\\s*[:=]\\s*['\"]?)([^\\s,;\"'}\\])]{4,})");

    private static MaskedAppender appender;
    private static LoggerConfig loggerConfig;
    /** 当前配置里的敏感串：调试日志关闭时也要维护，供对话日志打码复用 */
    private static volatile List<String> SECRETS = List.of();

    private DebugLog() {
    }

    /**
     * 按配置开启 / 关闭调试日志；已开启时只刷新要打码的 Key 列表（配置里换 Key 也能立刻生效）。
     */
    public static synchronized void apply(AiPlayerConfig cfg) {
        SECRETS = cfg == null ? List.of() : secretsOf(cfg);
        if (cfg == null || !cfg.debugLog) {
            stop();
            return;
        }
        List<String> secrets = SECRETS;
        if (appender != null) {
            appender.secrets = secrets;
            return;
        }
        LoggerContext ctx = coreContext();
        if (ctx == null) {
            return;   // 日志实现不是 log4j2（理论上不会发生），静默跳过
        }
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("aiplayer");
            Files.createDirectories(dir);
            prune(dir);
            Path target = dir.resolve("aiplayer-" + LocalDateTime.now().format(STAMP) + ".log");
            MaskedAppender a = new MaskedAppender(target);
            a.secrets = secrets;
            a.start();

            Configuration conf = ctx.getConfiguration();
            LoggerConfig lc = conf.getLoggerConfig(AiPlayerMod.MOD_ID);
            if (!AiPlayerMod.MOD_ID.equals(lc.getName())) {
                // 之前没人单独配过这个 logger：新建一个（additivity=true，控制台照常输出）
                lc = new LoggerConfig(AiPlayerMod.MOD_ID, Level.INFO, true);
                conf.addLogger(AiPlayerMod.MOD_ID, lc);
                lc = conf.getLoggerConfig(AiPlayerMod.MOD_ID);
            }
            lc.setLevel(Level.INFO);
            lc.addAppender(a, Level.INFO, null);
            ctx.updateLoggers();

            appender = a;
            loggerConfig = lc;
            AiPlayerMod.LOGGER.info("[aiplayer] 调试日志已开启 -> {}", target);
        } catch (Throwable t) {
            AiPlayerMod.LOGGER.warn("[aiplayer] 调试日志开启失败: {}", t.toString());
        }
    }

    private static synchronized void stop() {
        if (appender == null) {
            return;
        }
        try {
            if (loggerConfig != null) {
                loggerConfig.removeAppender(appender.getName());
            }
            LoggerContext ctx = coreContext();
            if (ctx != null) {
                ctx.updateLoggers();
            }
        } catch (Throwable ignored) {
            // 关闭失败不影响游戏运行
        }
        appender.stop();
        appender = null;
        loggerConfig = null;
    }

    private static LoggerContext coreContext() {
        org.apache.logging.log4j.spi.LoggerContext ctx = LogManager.getContext(false);
        return ctx instanceof LoggerContext core ? core : null;
    }

    private static List<String> secretsOf(AiPlayerConfig cfg) {
        List<String> out = new ArrayList<>();
        addSecret(out, cfg.apiKey);
        for (AiProfile p : cfg.profiles) {
            if (p != null) {
                addSecret(out, p.apiKey);
            }
        }
        return out;
    }

    private static void addSecret(List<String> out, String value) {
        if (value != null && value.length() >= 8 && !out.contains(value)) {
            out.add(value);
        }
    }

    /** 只留最后 4 位，方便玩家自己确认"是哪个 Key 的问题"而不暴露整串 */
    private static String maskSecret(String secret) {
        return "***" + secret.substring(secret.length() - 4);
    }

    /**
     * 对外打码入口：给对话日志等其它日志复用（即使 {@code debugLog} 关着，Key 列表也是最新的）。
     */
    public static String sanitize(String text) {
        return mask(text, SECRETS);
    }

    static String mask(String text, List<String> secrets) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        if (secrets != null) {
            for (String secret : secrets) {
                out = out.replace(secret, maskSecret(secret));
            }
        }
        out = P_BEARER.matcher(out).replaceAll("$1***");
        out = P_SK.matcher(out).replaceAll("sk-***");
        out = P_FIELD.matcher(out).replaceAll("$1***");
        return out;
    }

    private static void prune(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> old = stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("aiplayer-") && name.endsWith(".log");
                    })
                    .sorted(Comparator.comparingLong(DebugLog::lastModified))
                    .toList();
            // 新文件还没创建，这里最多留 KEEP_FILES - 1 份旧的
            for (int i = 0; i <= old.size() - KEEP_FILES; i++) {
                Files.deleteIfExists(old.get(i));
            }
        } catch (IOException ignored) {
            // 清理失败不影响本次写入
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static final class MaskedAppender extends AbstractAppender {
        private final Writer writer;
        volatile List<String> secrets = List.of();

        MaskedAppender(Path target) throws IOException {
            super("aiplayer-debug", null, null, true, null);
            this.writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        @Override
        public void append(LogEvent event) {
            String line;
            try {
                line = format(event);
            } catch (Throwable t) {
                return;   // 格式化失败就丢掉这条，绝不能让日志把服务器拖崩
            }
            try {
                synchronized (this.writer) {
                    this.writer.write(line);
                    this.writer.write(System.lineSeparator());
                    this.writer.flush();
                }
            } catch (IOException ignored) {
                // 磁盘写满 / 文件被占用：忽略，游戏继续跑
            }
        }

        private String format(LogEvent event) {
            StringBuilder sb = new StringBuilder(192);
            sb.append(TIME.format(LocalDateTime.now()))
                    .append(" [").append(event.getThreadName()).append("] ")
                    .append(event.getLevel().name()).append(' ')
                    .append(event.getLoggerName()).append(" - ")
                    .append(event.getMessage() == null ? "" : event.getMessage().getFormattedMessage());
            Throwable thrown = event.getThrown();
            if (thrown != null) {
                StringWriter sw = new StringWriter();
                thrown.printStackTrace(new PrintWriter(sw));
                sb.append('\n').append(sw);
            }
            return mask(sb.toString(), this.secrets);
        }

        @Override
        public void stop() {
            try {
                synchronized (this.writer) {
                    this.writer.close();
                }
            } catch (IOException ignored) {
                // 关闭失败无所谓
            }
            super.stop();
        }
    }
}
