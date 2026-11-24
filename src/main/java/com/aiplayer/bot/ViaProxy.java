package com.aiplayer.bot;

import com.aiplayer.AiPlayerMod;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

/**
 * ViaProxy 协议代理：mineflayer 不认识新版本协议时，用它把 1.21.11 的会话转到目标服。
 * <p>
 * 同时负责“目标服要求正版验证”时的微软账号登录：把控制台输出的验证码交给桥接层，
 * 客户端会弹窗让玩家去 microsoft.com/link 输入。
 */
public final class ViaProxy {
    private static final String VERSION_TAG = "3.4.14";
    private static final String DOWNLOAD_URL =
            "https://github.com/ViaVersion/ViaProxy/releases/download/v" + VERSION_TAG + "/ViaProxy-" + VERSION_TAG + ".jar";
    private static final String[] MIRRORS = new String[]{
            DOWNLOAD_URL,
            "https://ghfast.top/" + DOWNLOAD_URL,
            "https://gh-proxy.com/" + DOWNLOAD_URL,
            "https://ghproxy.net/" + DOWNLOAD_URL
    };
    /** mineflayer 走代理时统一用的协议版本 */
    public static final String VERSION = "1.21.11";

    private static final String ONLINE_MODE_HINT = "requires a valid authentication mode";
    private static final Pattern CODE_PATTERN = Pattern.compile("(?:otc=|code: )([A-Z0-9]{6,12})");
    private static final Pattern LINK_PATTERN =
            Pattern.compile("https://www\\.microsoft\\.com/link\\?otc=[A-Z0-9]{6,12}");
    private static final Pattern NO_ACCOUNT_PATTERN = Pattern.compile("\"accountsV4\"\\s*:\\s*\\[\\s*\\]");

    private static volatile Process process;
    private static volatile String target;
    private static volatile int localPort;
    private static volatile boolean watchingAuth;
    private static volatile boolean cancelled;

    private ViaProxy() {
    }

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("aiplayer-bot").resolve("viaproxy");
    }

    /**
     * 启动（或复用）代理，返回本地端口。
     *
     * @param host          目标服地址
     * @param port          目标服端口
     * @param bot           bot 名字（只用于提示和验证码归属）
     * @param microsoftAuth 目标服要求正版时是否自动走微软登录
     */
    public static synchronized int start(String host, int port, String bot, boolean microsoftAuth)
            throws IOException, InterruptedException {
        String wanted = host + ":" + port;
        cancelled = false;
        if (!microsoftAuth) {
            BotBridge.unregisterMsa(bot);
        }
        if (process != null && process.isAlive() && wanted.equals(target) && reachable(localPort)) {
            watchAuth(process, bot, microsoftAuth);
            return localPort;
        }
        stop();
        Path dir = dir();
        Files.createDirectories(dir);
        Path jar = download(dir);
        int port2 = freePort();
        Path log = FabricLoader.getInstance().getGameDir()
                .resolve("logs").resolve("aiplayer").resolve("viaproxy.log");
        Files.createDirectories(log.getParent());
        List<String> args = new ArrayList<>();
        args.add(javaExecutable());
        args.add("-Xmx384M");
        args.add("-jar");
        args.add(jar.toAbsolutePath().toString());
        args.add("cli");
        args.add("--bind-address");
        args.add("127.0.0.1:" + port2);
        args.add("--target-address");
        args.add(wanted);
        args.add("--log-ips");
        args.add("false");
        args.add("--auth-method");
        args.add("ACCOUNT");
        args.add("--proxy-online-mode");
        args.add("false");
        AiPlayerMod.LOGGER.info("[aiplayer] 启动协议代理：{}:{} -> {}", "127.0.0.1", port2, wanted);
        ProcessBuilder builder = new ProcessBuilder(args).directory(dir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process started = builder.start();
        process = started;
        target = wanted;
        localPort = port2;
        long deadline = System.currentTimeMillis() + 90000L;
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive()) {
                throw new IOException("ViaProxy 进程已退出，详见 logs/aiplayer/viaproxy.log");
            }
            if (reachable(port2)) {
                watchAuth(started, bot, microsoftAuth);
                return port2;
            }
            Thread.sleep(500L);
        }
        stop();
        throw new IOException("ViaProxy 启动超时（90 秒），详见 logs/aiplayer/viaproxy.log");
    }

    /** 取消正在等待的微软登录 */
    public static void cancel() {
        cancelled = true;
    }

    public static synchronized void stop() {
        Process running = process;
        process = null;
        target = null;
        localPort = 0;
        watchingAuth = false;
        if (running != null) {
            try {
                running.descendants().forEach(ProcessHandle::destroy);
                running.destroy();
                if (!running.waitFor(3L, TimeUnit.SECONDS)) {
                    running.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                running.destroyForcibly();
            }
        }
    }

    public static boolean isRunning() {
        Process running = process;
        return running != null && running.isAlive();
    }

    // ---- 微软登录 ----

    private static void watchAuth(Process running, String bot, boolean microsoftAuth) {
        if (watchingAuth || running == null || !running.isAlive()) {
            return;
        }
        watchingAuth = true;
        Thread thread = new Thread(() -> {
            Path log = FabricLoader.getInstance().getGameDir()
                    .resolve("logs").resolve("aiplayer").resolve("viaproxy.log");
            long[] offset = new long[]{0L};
            try {
                offset[0] = Files.exists(log) ? Files.size(log) : 0L;
            } catch (IOException ignored) {
            }
            long deadline = System.currentTimeMillis() + 900000L;
            while (running.isAlive() && System.currentTimeMillis() < deadline) {
                String tail = readFrom(log, offset);
                if (tail.contains(ONLINE_MODE_HINT)) {
                    if (!microsoftAuth) {
                        AiPlayerMod.LOGGER.info("[aiplayer] 目标服要求正版验证，但当前配置为离线登录，跳过微软登录");
                        BotBridge.notify(Component.translatable("aiplayer.bot.onlineModeNeedsAuth", bot == null ? "" : bot));
                        return;
                    }
                    AiPlayerMod.LOGGER.info("[aiplayer] 目标服要求正版验证，开始 ViaProxy 微软账号登录");
                    try {
                        loginMicrosoft(running, bot);
                    } catch (Exception e) {
                        AiPlayerMod.LOGGER.warn("[aiplayer] ViaProxy 微软账号登录失败：{}", e.toString());
                    }
                    return;
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "aiplayer-viaproxy-auth");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loginMicrosoft(Process running, String bot) throws IOException, InterruptedException {
        if (running == null || !running.isAlive()) {
            return;
        }
        Path dir = dir();
        Path log = FabricLoader.getInstance().getGameDir()
                .resolve("logs").resolve("aiplayer").resolve("viaproxy.log");
        long[] offset = new long[]{Files.exists(log) ? Files.size(log) : 0L};
        boolean hasAccount = BotBridge.msa(bot) != null;
        if (!hasAccount) {
            try {
                running.getOutputStream().write("account add microsoft\n".getBytes(StandardCharsets.US_ASCII));
                running.getOutputStream().flush();
            } catch (IOException e) {
                throw new IOException("ViaProxy 控制台不可用，无法登录微软账号", e);
            }
            BotBridge.notify(Component.translatable("aiplayer.bot.msaLogin", bot == null ? "" : bot));
        }
        long deadline = System.currentTimeMillis() + 300000L;
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) {
                BotBridge.unregisterMsa(bot);
                throw new IOException("已取消微软账号登录");
            }
            if (!running.isAlive()) {
                throw new IOException("ViaProxy 在微软账号登录时退出，详见 logs/aiplayer/viaproxy.log");
            }
            String tail = readFrom(log, offset);
            if (!hasAccount) {
                Matcher code = CODE_PATTERN.matcher(tail);
                if (code.find()) {
                    Matcher link = LINK_PATTERN.matcher(tail);
                    String url = link.find() ? link.group() : "https://www.microsoft.com/link";
                    BotBridge.registerMsa(bot, code.group(1), url);
                    hasAccount = true;
                }
            }
            if (hasSavedAccount(dir)) {
                BotBridge.unregisterMsa(bot);
                AiPlayerMod.LOGGER.info("[aiplayer] ViaProxy 微软账号已登录");
                return;
            }
            Thread.sleep(500L);
        }
        BotBridge.unregisterMsa(bot);
        throw new IOException("微软账号登录超时（5 分钟），请重试");
    }

    private static String readFrom(Path file, long[] offset) {
        try {
            if (!Files.exists(file)) {
                return "";
            }
            long size = Files.size(file);
            if (size <= offset[0]) {
                return "";
            }
            String text;
            try (InputStream in = Files.newInputStream(file)) {
                in.skipNBytes(offset[0]);
                byte[] buffer = new byte[(int) Math.min(size - offset[0], 1048576L)];
                int read = in.readNBytes(buffer, 0, buffer.length);
                if (read <= 0) {
                    return "";
                }
                offset[0] += read;
                text = new String(buffer, 0, read, StandardCharsets.UTF_8);
            }
            return text;
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean hasSavedAccount(Path dir) {
        Path saves = dir.resolve("saves.json");
        try {
            if (!Files.exists(saves)) {
                return false;
            }
            String json = Files.readString(saves, StandardCharsets.UTF_8);
            return !NO_ACCOUNT_PATTERN.matcher(json).find();
        } catch (IOException e) {
            return false;
        }
    }

    // ---- 下载 / 进程工具 ----

    private static Path download(Path dir) throws IOException, InterruptedException {
        Path jar = dir.resolve("ViaProxy.jar");
        if (Files.exists(jar) && Files.size(jar) > 10485760L) {
            return jar;
        }
        Path tmp = dir.resolve("ViaProxy.jar.tmp");
        StringBuilder errors = new StringBuilder();
        IOException last = null;
        for (SSLContext context : trustContexts()) {
            HttpClient client = httpClient(context);
            for (String url : MIRRORS) {
                try {
                    Files.deleteIfExists(tmp);
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMinutes(5L)).GET().build();
                    HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
                    if (response.statusCode() == 200 && Files.size(tmp) > 10485760L) {
                        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
                        AiPlayerMod.LOGGER.info("[aiplayer] ViaProxy 已下载：{}（{}）", jar, url);
                        return jar;
                    }
                    errors.append(url).append(" -> HTTP ").append(response.statusCode()).append('\n');
                } catch (IOException e) {
                    last = e;
                    errors.append(url).append(" -> ").append(e.getMessage()).append('\n');
                }
            }
        }
        Files.deleteIfExists(tmp);
        throw new IOException("下载 ViaProxy 失败，可手动下载后放到 " + jar + "\n" + DOWNLOAD_URL + "\n" + errors, last);
    }

    private static HttpClient httpClient(SSLContext context) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15L))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (context != null) {
            builder.sslContext(context);
        }
        return builder.build();
    }

    /** 部分 Windows 环境的 Java 信任库缺 GitHub 证书，额外用系统根证书试一次 */
    private static List<SSLContext> trustContexts() {
        List<SSLContext> contexts = new ArrayList<>();
        contexts.add(null);
        try {
            KeyStore store = KeyStore.getInstance("Windows-ROOT");
            store.load(null, null);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            contexts.add(context);
        } catch (Exception ignored) {
        }
        return contexts;
    }

    private static String javaExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String exe = os.contains("win") ? "java.exe" : "java";
        Path path = Path.of(System.getProperty("java.home"), "bin", exe);
        return Files.exists(path) ? path.toString() : "java";
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static boolean reachable(int port) {
        if (port <= 0) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 700);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
