package com.aiplayer.bot;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.RemoteBotProfile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

/**
 * 外部 bot 进程管理：解压 bridge 脚本、按需安装 Node.js / npm 依赖、拉起 mineflayer 进程。
 * <p>
 * 进程本身不感知模组，只通过 {@link BotBridge} 的 TCP 桥接与模组通信。
 */
public final class BotProcessManager {
    private static final Map<String, BotProcess> PROCESSES = new ConcurrentHashMap<>();
    private static final String BRIDGE_SCRIPT = "/bridge/mineflayer-bot.js";
    private static final String PACKAGE_JSON = "/bridge/package.json";
    /** 自动安装的 Node.js 版本（与 mineflayer 4.x 兼容） */
    private static final String NODE_VERSION = "20.18.1";

    private BotProcessManager() {
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static BotProcess state(RemoteBotProfile profile) {
        String name = profile.safeName();
        return PROCESSES.computeIfAbsent(key(name), k -> new BotProcess(name));
    }

    public static Status status(String name) {
        BotProcess proc = PROCESSES.get(key(name));
        return proc == null ? Status.STOPPED : proc.status;
    }

    public static boolean isRunning(String name) {
        BotProcess proc = PROCESSES.get(key(name));
        return proc != null && proc.status == Status.RUNNING;
    }

    public static String detail(String name) {
        BotProcess proc = PROCESSES.get(key(name));
        return proc == null ? "" : proc.detail;
    }

    /** 首次启动：允许走微软登录 */
    public static void start(RemoteBotProfile profile) {
        start(profile, false);
    }

    public static void start(RemoteBotProfile profile, boolean freshLogin) {
        if (profile != null) {
            state(profile).retried = false;
        }
        start(profile, freshLogin, false);
    }

    public static void start(RemoteBotProfile profile, boolean freshLogin, boolean forceProxy) {
        if (profile == null) {
            return;
        }
        BotProcess proc = state(profile);
        String name = profile.safeName();
        String host = profile.host;
        int port = profile.port;
        String username = profile.username;
        String version = profile.version;
        String password = profile.password;
        String authMode = profile.authMode;
        String mcAuth = profile.mcAuth;
        synchronized (proc) {
            if (proc.starting || (proc.process != null && proc.process.isAlive())) {
                return;
            }
            proc.starting = true;
            proc.stopping = false;
            proc.status = Status.STARTING;
            proc.detail = "";
        }

        AiPlayerConfig cfg = AiPlayerConfig.get();
        if (!cfg.botBridge) {
            cfg.botBridge = true;
            cfg.save();
        }
        BotBridge.applyConfig();
        if (!BotBridge.isListening()) {
            BotBridge.notify(Component.translatable("aiplayer.bot.bridgeDown"));
        }

        Thread thread = new Thread(() -> run(proc, name, host, port, username, version,
                password, authMode, mcAuth, freshLogin, forceProxy), "aiplayer-bot-proc-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(BotProcess proc, String name, String host, int port, String username,
                            String version, String password, String authMode, String mcAuth,
                            boolean freshLogin, boolean forceProxy) {
        try {
            if (!"microsoft".equals(mcAuth)) {
                // 离线登录时清掉上一个账号留下的验证码，避免误弹窗
                BotBridge.unregisterMsa(name);
            }
            Path workDir = prepareDir();
            Path node = resolveNode(workDir, proc);
            if (node == null) {
                if (proc.stopping) {
                    proc.status = Status.STOPPED;
                    return;
                }
                fail(proc, Component.translatable("aiplayer.bot.nodeMissing").getString());
                return;
            }
            if (!depsInstalled(workDir)) {
                proc.status = Status.INSTALLING;
                proc.detail = "";
                if (!npmInstall(workDir, proc, name, node) || !depsInstalled(workDir)) {
                    if (proc.stopping) {
                        proc.status = Status.STOPPED;
                        return;
                    }
                    fail(proc, Component.translatable("aiplayer.bot.npmFailed").getString());
                    return;
                }
            }
            if (proc.stopping) {
                proc.status = Status.STOPPED;
                return;
            }

            String targetHost = host;
            int targetPort = port;
            String targetVersion = version;
            String detected = ServerPing.versionOf(host, port);
            boolean needProxy = forceProxy || (detected != null && !ServerPing.supported(detected));
            proc.proxyUsed = needProxy;
            if (needProxy) {
                if (!AiPlayerConfig.get().viaProxy) {
                    fail(proc, Component.translatable("aiplayer.bot.unsupported",
                            detected == null ? "?" : detected).getString());
                    return;
                }
                proc.status = Status.STARTING;
                proc.detail = Component.translatable("aiplayer.bot.proxyStarting").getString();
                BotBridge.notify(Component.translatable("aiplayer.bot.proxyStarting"));
                targetPort = ViaProxy.start(host, port, name, "microsoft".equals(mcAuth));
                targetHost = "127.0.0.1";
                targetVersion = ViaProxy.VERSION;
                BotBridge.notify(Component.translatable("aiplayer.bot.proxyStarted",
                        detected == null ? ViaProxy.VERSION : detected));
            }

            List<String> args = new ArrayList<>();
            args.add(node.toString());
            args.add("mineflayer-bot.js");
            args.add("--name");
            args.add(name);
            args.add("--host");
            args.add(targetHost);
            args.add("--port");
            args.add(String.valueOf(targetPort));
            if (username != null && !username.isBlank()) {
                args.add("--username");
                args.add(username.trim());
            }
            if (targetVersion != null && !targetVersion.isBlank()) {
                args.add("--version");
                args.add(targetVersion.trim());
            }
            boolean microsoft = !needProxy && "microsoft".equals(mcAuth);
            args.add("--auth");
            args.add(microsoft ? "microsoft" : "offline");
            if (freshLogin && microsoft) {
                args.add("--msa-fresh");
                args.add("true");
            }
            args.add("--bridge-host");
            args.add(bridgeHost());
            args.add("--bridge-port");
            args.add(String.valueOf(AiPlayerConfig.get().botBridgePort));

            proc.status = Status.STARTING;
            ProcessBuilder builder = new ProcessBuilder(args).directory(workDir.toFile()).redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            if (isInside(workDir, node)) {
                String nodePath = workDir.resolve("node").toAbsolutePath().toString();
                String path = env.getOrDefault("PATH", "");
                env.put("PATH", nodePath + File.pathSeparator + path);
            }
            if (password != null && !password.isEmpty()) {
                env.put("AI_BOT_PASSWORD", password);
            }
            if (authMode != null && !authMode.isBlank()) {
                env.put("AI_BOT_AUTH_MODE", authMode);
            }

            Process process = builder.start();
            proc.process = process;
            proc.status = Status.RUNNING;
            proc.detail = "pid " + process.pid();
            proc.starting = false;
            AiPlayerMod.LOGGER.info("[aiplayer] 外部 bot 进程已启动：{}（pid {}）", name, process.pid());
            BotBridge.notify(Component.translatable("aiplayer.bot.started", name));
            watchConnection(proc, name, process);
            pumpOutput(process, proc, name, true);
            int code = process.waitFor();
            proc.process = null;
            if (proc.stopping) {
                proc.status = Status.STOPPED;
                proc.detail = "";
            } else {
                proc.status = Status.ERROR;
                proc.detail = Component.translatable("aiplayer.bot.exit", code).getString();
                BotBridge.notify(Component.translatable("aiplayer.bot.exited", name, code));
            }
        } catch (Throwable t) {
            fail(proc, t.getMessage() == null ? t.toString() : t.getMessage());
        } finally {
            proc.starting = false;
        }
    }

    public static void stop(String name) {
        BotProcess proc = PROCESSES.get(key(name));
        if (proc == null) {
            return;
        }
        proc.stopping = true;
        ViaProxy.cancel();
        BotBridge.unregisterMsa(proc.name);
        Process process = proc.process;
        if (process != null && process.isAlive()) {
            kill(process);
            Thread thread = new Thread(() -> {
                try {
                    if (!process.waitFor(3L, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "aiplayer-bot-stop");
            thread.setDaemon(true);
            thread.start();
        }
        proc.status = Status.STOPPED;
        proc.detail = "";
        BotBridge.notify(Component.translatable("aiplayer.bot.stopped", proc.name));
    }

    public static void stopAll() {
        for (String name : new ArrayList<>(PROCESSES.keySet())) {
            stop(name);
        }
        ViaProxy.stop();
    }

    /** 一键启动所有已启用的远程 bot（远程批量） */
    public static void startAll() {
        for (RemoteBotProfile profile : AiPlayerConfig.get().remoteBots) {
            if (profile.enabled) {
                start(profile);
            }
        }
    }

    /** 配置改了之后：在跑就重启，没跑就启动 */
    public static void toggle(RemoteBotProfile profile, boolean freshLogin) {
        if (profile == null) {
            return;
        }
        if (busy(profile.name)) {
            restartNow(profile, freshLogin);
        } else {
            start(profile, freshLogin);
        }
    }

    /** 如果当前在跑就按新配置重启（GUI 保存 / 开关改动时用） */
    public static void restartIfRunning(RemoteBotProfile profile, boolean freshLogin) {
        if (profile != null && busy(profile.name)) {
            restartNow(profile, freshLogin);
        }
    }

    public static void restart(RemoteBotProfile profile) {
        if (profile != null) {
            if (busy(profile.name)) {
                restartNow(profile, false);
            } else {
                start(profile);
            }
        }
    }

    private static boolean busy(String name) {
        Status status = status(name);
        return status == Status.RUNNING || status == Status.STARTING
                || status == Status.INSTALLING || status == Status.DOWNLOADING;
    }

    private static void restartNow(RemoteBotProfile profile, boolean freshLogin) {
        BotProcess proc = state(profile);
        stop(profile.name);
        Thread thread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 6000L;
            for (Process process = proc.process; process != null && process.isAlive()
                    && System.currentTimeMillis() < deadline; process = proc.process) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            start(profile, freshLogin, false);
        }, "aiplayer-bot-relogin-" + profile.safeName());
        thread.setDaemon(true);
        thread.start();
    }

    /** 目标服版本过新被 mineflayer 拒绝：改用 ViaProxy 重试一次 */
    public static void retryWithProxy(String name) {
        BotProcess proc = PROCESSES.get(key(name));
        if (proc != null && !proc.proxyUsed && !proc.retried && !proc.stopping) {
            proc.retried = true;
            RemoteBotProfile profile = null;
            for (RemoteBotProfile candidate : AiPlayerConfig.get().remoteBots) {
                if (candidate.safeName().equalsIgnoreCase(name)) {
                    profile = candidate;
                    break;
                }
            }
            if (profile != null) {
                AiPlayerMod.LOGGER.info("[aiplayer] {} 的服务器版本过新，改用 ViaProxy 重试", name);
                RemoteBotProfile finalProfile = profile;
                Thread thread = new Thread(() -> start(finalProfile, false, true), "aiplayer-bot-proxy-" + name);
                thread.setDaemon(true);
                thread.start();
            }
        }
    }

    /** 进入某个服务器后，让所有「跟随我」的 bot 也连过去 */
    public static void startFollowing(String host, int port) {
        if (host == null || host.isBlank()) {
            return;
        }
        for (RemoteBotProfile profile : AiPlayerConfig.get().remoteBots) {
            if (profile.followMe && profile.enabled) {
                start(profile, host, port);
            }
        }
    }

    public static void start(RemoteBotProfile profile, String host, int port) {
        if (profile == null || host == null || host.isBlank()) {
            return;
        }
        BotProcess proc = state(profile);
        if (!proc.starting && (proc.process == null || !proc.process.isAlive())
                && proc.status != Status.DOWNLOADING && proc.status != Status.INSTALLING) {
            profile.host = host;
            profile.port = port;
            AiPlayerConfig.get().save();
            start(profile);
        }
    }

    private static void kill(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroy);
        } catch (Throwable ignored) {
        }
        process.destroy();
    }

    private static void watchConnection(BotProcess proc, String name, Process process) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(15000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (proc.process == process && process.isAlive() && !proc.stopping) {
                if (!BotBridge.isOnline(name)) {
                    String hint = Component.translatable("aiplayer.bot.notConnected").getString();
                    proc.detail = hint;
                    AiPlayerMod.LOGGER.warn("[aiplayer] 外部 bot {} 进程在运行，但 15 秒内没有连上桥接（{}:{}）",
                            name, bridgeHost(), AiPlayerConfig.get().botBridgePort);
                }
            }
        }, "aiplayer-bot-watch-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private static String bridgeHost() {
        String bind = AiPlayerConfig.get().botBridgeBind;
        if (bind != null && !bind.isBlank()) {
            String host = bind.trim();
            return !host.equals("0.0.0.0") && !host.equals("::") && !host.equals("[::]")
                    && !host.equalsIgnoreCase("localhost") ? host : "127.0.0.1";
        }
        return "127.0.0.1";
    }

    // ---- 工作目录 / 脚本 ----

    private static Path prepareDir() throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("aiplayer-bot");
        Files.createDirectories(dir);
        copyResource(BRIDGE_SCRIPT, dir.resolve("mineflayer-bot.js"));
        copyResource(PACKAGE_JSON, dir.resolve("package.json"));
        return dir;
    }

    private static void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = BotProcessManager.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("jar 内缺少 " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean depsInstalled(Path dir) {
        return Files.exists(dir.resolve("node_modules").resolve("mineflayer").resolve("package.json"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isInside(Path dir, Path file) {
        return file != null && file.toAbsolutePath().normalize().startsWith(dir.toAbsolutePath().normalize());
    }

    private static Path localNode(Path dir) {
        Path node = isWindows() ? dir.resolve("node").resolve("node.exe")
                : dir.resolve("node").resolve("bin").resolve("node");
        return Files.exists(node) ? node : null;
    }

    private static Path resolveNode(Path dir, BotProcess proc) {
        Path local = localNode(dir);
        if (local != null && verifyNode(local.toString(), dir)) {
            return local;
        }
        if (verifyNode("node", dir)) {
            return Path.of("node");
        }
        try {
            return installNode(dir, proc);
        } catch (Throwable t) {
            AiPlayerMod.LOGGER.warn("[aiplayer] 自动安装 Node.js 失败：{}", t.toString());
            proc.detail = Component.translatable("aiplayer.bot.nodeDownloadFailed",
                    t.getMessage() == null ? t.toString() : t.getMessage()).getString();
            return null;
        }
    }

    private static boolean verifyNode(String exe, Path dir) {
        Process process = null;
        try {
            process = new ProcessBuilder(exe, "--version")
                    .directory(dir.toFile()).redirectErrorStream(true).start();
            boolean ok;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String first = reader.readLine();
                if (!process.waitFor(8L, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return false;
                }
                ok = process.exitValue() == 0 && first != null && !first.isBlank();
            }
            return ok;
        } catch (Throwable t) {
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    // ---- 下载 / 解压 Node.js ----

    private static Path installNode(Path dir, BotProcess proc) throws IOException, InterruptedException {
        boolean windows = isWindows();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        arch = !arch.contains("aarch64") && !arch.contains("arm64") ? "x64" : "arm64";
        String base;
        String ext;
        if (windows) {
            base = "node-v" + NODE_VERSION + "-win-" + arch;
            ext = "zip";
        } else if (os.contains("mac")) {
            base = "node-v" + NODE_VERSION + "-darwin-" + arch;
            ext = "tar.gz";
        } else {
            if (!os.contains("linux")) {
                throw new IOException("当前系统不支持自动安装 Node.js，请先安装 Node.js 18+");
            }
            base = "node-v" + NODE_VERSION + "-linux-" + arch;
            ext = "tar.xz";
        }
        String url = "https://nodejs.org/dist/v" + NODE_VERSION + "/" + base + "." + ext;
        proc.status = Status.DOWNLOADING;
        proc.detail = "下载 Node.js ...";
        AiPlayerMod.LOGGER.info("[aiplayer] 未找到 Node.js，开始下载 {}", url);
        BotBridge.notify(Component.translatable("aiplayer.bot.nodeInstalling"));
        Path archive = dir.resolve("node." + ext);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15L))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10L)).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("下载失败 HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("content-length").orElse(-1L);
        long written = 0L;
        long lastShown = -1L;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(archive)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (proc.stopping) {
                    Files.deleteIfExists(archive);
                    return null;
                }
                out.write(buffer, 0, read);
                written += read;
                if (total > 0L) {
                    long percent = written * 100L / total;
                    if (percent >= lastShown + 5L) {
                        lastShown = percent;
                        proc.detail = "下载 Node.js " + percent + "%";
                    }
                }
            }
        }
        if (written < 5242880L) {
            Files.deleteIfExists(archive);
            throw new IOException("下载文件不完整（" + written + " 字节）");
        }
        proc.detail = "解压 Node.js ...";
        Path nodeDir = dir.resolve("node");
        deleteRecursively(nodeDir);
        if (windows) {
            // 先校验 zip 魔数：下载被劫持/只写了一半时，ZipInputStream 会抛
            // "invalid LOC header (bad signature)"，以前还会把坏包留在磁盘上反复失败
            byte[] magic = new byte[4];
            try (InputStream in = Files.newInputStream(archive)) {
                if (in.read(magic) < 4) {
                    Files.deleteIfExists(archive);
                    throw new IOException("下载的 Node.js 压缩包为空，请重试");
                }
            }
            if (magic[0] != 'P' || magic[1] != 'K') {
                Files.deleteIfExists(archive);
                throw new IOException("下载的 Node.js 压缩包损坏（不是 zip），请重试");
            }
        }
        try {
            if (!windows) {
                extractTar(dir, archive, base);
            } else {
                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (proc.stopping) {
                            Files.deleteIfExists(archive);
                            return null;
                        }
                        String name = entry.getName().replace('\\', '/');
                        int slash = name.indexOf('/');
                        if (slash < 0) {
                            continue;
                        }
                        String relative = name.substring(slash + 1);
                        if (relative.isEmpty()) {
                            continue;
                        }
                        Path target = nodeDir.resolve(relative).normalize();
                        if (!target.startsWith(nodeDir)) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Files.deleteIfExists(archive);
        Path node = localNode(dir);
        if (!windows && node != null) {
            node.toFile().setExecutable(true, false);
            Path npm = dir.resolve("node").resolve("bin").resolve("npm");
            if (Files.exists(npm)) {
                npm.toFile().setExecutable(true, false);
            }
        }
        if (node != null && verifyNode(node.toString(), dir)) {
            AiPlayerMod.LOGGER.info("[aiplayer] Node.js 已安装到 {}", nodeDir);
            BotBridge.notify(Component.translatable("aiplayer.bot.nodeInstalled"));
            proc.status = Status.INSTALLING;
            proc.detail = "";
            return node;
        }
        throw new IOException("解压后仍找不到可用的 node 可执行文件");
        } catch (IOException | InterruptedException e) {
            // 解压到一半失败：把坏包和半成品一起清掉，下次启动重新下载，避免反复读同一份坏 zip
            Files.deleteIfExists(archive);
            deleteRecursively(nodeDir);
            throw e;
        }
    }

    private static void extractTar(Path dir, Path archive, String folderName)
            throws IOException, InterruptedException {
        Path extract = dir.resolve("node-extract");
        deleteRecursively(extract);
        Files.createDirectories(extract);
        Process process = new ProcessBuilder("tar", "-xf", archive.toAbsolutePath().toString(),
                "-C", extract.toAbsolutePath().toString()).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (process.waitFor(120L, TimeUnit.SECONDS) && process.exitValue() == 0) {
            Path root = extract.resolve(folderName);
            if (!Files.isDirectory(root)) {
                try (Stream<Path> stream = Files.list(extract)) {
                    root = stream.filter(Files::isDirectory).findFirst().orElse(null);
                }
            }
            if (root != null && Files.isDirectory(root)) {
                Path nodeDir = dir.resolve("node");
                Files.createDirectories(nodeDir);
                try (Stream<Path> stream = Files.list(root)) {
                    for (Path child : stream.toList()) {
                        Files.move(child, nodeDir.resolve(child.getFileName().toString()),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                deleteRecursively(extract);
            } else {
                deleteRecursively(extract);
                throw new IOException("tar 包内没有解出 node 目录");
            }
        } else {
            process.destroyForcibly();
            deleteRecursively(extract);
            throw new IOException("tar 解压失败：" + output);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            try (Stream<Path> stream = Files.walk(path)) {
                for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            }
        }
    }

    // ---- npm install / 进程输出 ----

    private static boolean npmInstall(Path dir, BotProcess proc, String name, Path node) {
        Process process = null;
        try {
            boolean local = isInside(dir, node);
            List<String> command;
            if (local) {
                Path npmCli = dir.resolve("node").resolve("node_modules").resolve("npm")
                        .resolve("bin").resolve("npm-cli.js");
                command = List.of(node.toString(), npmCli.toString(), "install", "--no-audit", "--no-fund");
            } else if (isWindows()) {
                command = List.of("cmd.exe", "/c", "npm install --no-audit --no-fund");
            } else {
                command = List.of("npm", "install", "--no-audit", "--no-fund");
            }
            ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
            if (local) {
                String nodePath = dir.resolve("node").toAbsolutePath().toString();
                builder.environment().put("PATH",
                        nodePath + File.pathSeparator + builder.environment().getOrDefault("PATH", ""));
            }
            process = builder.start();
            proc.process = process;
            pumpOutput(process, proc, name, true);
            return process.waitFor() == 0;
        } catch (Throwable t) {
            proc.detail = t.getMessage() == null ? t.toString() : t.getMessage();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                kill(process);
            }
            proc.process = null;
        }
    }

    /** 把子进程输出写进 logs/aiplayer/bot-<名字>.log，并顺带更新界面上的状态行 */
    private static void pumpOutput(Process process, BotProcess proc, String name, boolean updateDetail) {
        Path log = FabricLoader.getInstance().getGameDir()
                .resolve("logs").resolve("aiplayer").resolve("bot-" + safeFileName(name) + ".log");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                appendLog(log, line);
                if (updateDetail) {
                    proc.detail = line.length() > 70 ? line.substring(0, 70) : line;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void appendLog(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static String safeFileName(String name) {
        String safe = name == null ? "bot" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "bot" : safe;
    }

    private static void fail(BotProcess proc, String message) {
        proc.status = Status.ERROR;
        proc.detail = message == null ? "unknown" : message;
        proc.process = null;
        // 启动失败可能被界面/事件反复触发：10 秒内只播报一次，避免刷屏和刷日志
        long now = System.currentTimeMillis();
        if (now - proc.lastFailAt < 10_000L) {
            return;
        }
        proc.lastFailAt = now;
        AiPlayerMod.LOGGER.warn("[aiplayer] 外部 bot {} 启动失败：{}", proc.name, proc.detail);
        BotBridge.notify(Component.translatable("aiplayer.bot.error", proc.name, proc.detail));
    }

    /** 一个 bot 的进程状态 */
    private static final class BotProcess {
        final String name;
        volatile Status status = Status.STOPPED;
        volatile String detail = "";
        volatile Process process;
        volatile boolean stopping;
        volatile boolean starting;
        /** 这次启动是否走了 ViaProxy */
        volatile boolean proxyUsed;
        /** 是否已经为“版本过新”重试过一次 */
        volatile boolean retried;
        /** 上次启动失败的时间，用于限制失败播报频率 */
        volatile long lastFailAt;

        BotProcess(String name) {
            this.name = name;
        }
    }

    public enum Status {
        STOPPED,
        DOWNLOADING,
        INSTALLING,
        STARTING,
        RUNNING,
        ERROR
    }
}
