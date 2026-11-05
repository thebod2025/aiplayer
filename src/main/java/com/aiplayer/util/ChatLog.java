package com.aiplayer.util;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * AI 对话日志 + 更新日志：都写在 {@code logs/aiplayer/} 下，和调试日志（{@code aiplayer-*.log}）分开存放。
 * <ul>
 *   <li>{@code chat-YYYY-MM-DD.log}：玩家与 AI 的公屏对话，每行 {@code [时间] [玩家/AI] 名字: 内容}</li>
 *   <li>{@code update.log}：每次启动记一行（版本 / Minecraft / Fabric Loader），换版本时还会把更新日志复制过来</li>
 *   <li>{@code changelog-<版本>.md}：该版本的更新日志（来自打包进 jar 的 CHANGELOG.md），一个版本只写一次</li>
 * </ul>
 * 对话内容同样过 {@link DebugLog#sanitize(String)} 打码：玩家把 Key 贴进聊天时不会被记下来。
 */
public final class ChatLog {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 对话日志按天分文件，最多留 14 天，避免长期游玩把磁盘塞满 */
    private static final int KEEP_DAYS = 14;
    private static final Object LOCK = new Object();

    /** 由配置里的 {@code chatLog} 控制（默认开） */
    private static volatile boolean enabled = true;
    private static BufferedWriter writer;
    private static String writerDay;

    private ChatLog() {
    }

    /** 配置加载 / 保存后调用：开关立即生效（关掉时顺手把文件句柄释放掉） */
    public static void apply(AiPlayerConfig cfg) {
        enabled = cfg == null || cfg.chatLog;
        if (!enabled) {
            synchronized (LOCK) {
                closeWriter();
            }
        }
    }

    /**
     * 启动时记录版本，并把该版本的更新日志复制到 logs 目录（一个版本只复制一次）。
     * <p>
     * 与 debugLog / chatLog 开关无关：更新日志本身不含任何隐私内容。
     */
    public static synchronized void startup() {
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            FabricLoader loader = FabricLoader.getInstance();
            String version = loader.getModContainer(AiPlayerMod.MOD_ID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
            String mc = loader.getModContainer("minecraft")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
            String fl = loader.getModContainer("fabricloader")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
            append(dir.resolve("update.log"), "[" + LocalDateTime.now().format(FULL) + "] 启动 aiplayer "
                    + version + " · Minecraft " + mc + " · Fabric Loader " + fl);

            Path note = dir.resolve("changelog-" + version + ".md");
            if (!Files.exists(note) && copyResource("/aiplayer-changelog.md", note)) {
                append(dir.resolve("update.log"), "本次版本的更新日志 -> " + note.getFileName());
            }
        } catch (Throwable t) {
            // 日志写不进去（只读目录 / 磁盘满）绝不影响游戏启动
            AiPlayerMod.LOGGER.warn("[aiplayer] 更新日志写入失败: {}", t.toString());
        }
    }

    /** 玩家公屏发言 */
    public static void player(String name, String text) {
        write("玩家", name, text);
    }

    /** AI 公屏发言（回话 / 闲聊 / 状态语音都会走到这里） */
    public static void ai(String name, String text) {
        write("AI", name, text);
    }

    private static void write(String kind, String name, String text) {
        if (!enabled || text == null || text.isBlank()) {
            return;
        }
        try {
            String safe = DebugLog.sanitize(text).replace('\n', ' ').replace('\r', ' ');
            String line = "[" + LocalDateTime.now().format(TIME) + "] [" + kind + "] "
                    + (name == null ? "?" : name) + ": " + safe;
            synchronized (LOCK) {
                BufferedWriter out = writer();
                if (out == null) {
                    return;
                }
                out.write(line);
                out.newLine();
                out.flush();
            }
        } catch (Throwable ignored) {
            // 磁盘满 / 文件被占用：丢掉这条日志，游戏继续跑
        }
    }

    /** 按天切文件；调用方已持有 {@link #LOCK} */
    private static BufferedWriter writer() throws IOException {
        String day = LocalDate.now().format(DAY);
        if (writer != null && day.equals(writerDay)) {
            return writer;
        }
        closeWriter();
        Path dir = dir();
        Files.createDirectories(dir);
        prune(dir);
        writerDay = day;
        writer = Files.newBufferedWriter(dir.resolve("chat-" + day + ".log"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return writer;
    }

    private static void closeWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // 关闭失败无所谓
        }
        writer = null;
        writerDay = null;
    }

    private static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("aiplayer");
    }

    private static void append(Path file, String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private static boolean copyResource(String resource, Path target) {
        try (InputStream in = ChatLog.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;   // 构建时没把 CHANGELOG.md 打进 jar
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 只保留最近 {@link #KEEP_DAYS} 份对话日志 */
    private static void prune(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> old = stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("chat-") && name.endsWith(".log");
                    })
                    .sorted(Comparator.comparingLong(ChatLog::lastModified))
                    .toList();
            for (int i = 0; i <= old.size() - KEEP_DAYS; i++) {
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
}
