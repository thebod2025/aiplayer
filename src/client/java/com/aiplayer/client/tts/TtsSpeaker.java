package com.aiplayer.client.tts;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.client.config.ClientTtsConfig;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 语音合成（纯客户端，仅本机可听见）。
 * <p>
 * 走 Windows 自带语音：起一个常驻 PowerShell 进程，里面加载 {@code System.Speech} 的
 * {@code SpeechSynthesizer}，之后每一行 stdin 就是一句话，交给系统已安装的语音合成引擎朗读。
 * Win10 / Win11 默认就带中文与英文语音（讲述人用的就是同一套语音包），所以不用装任何东西、
 * 也不用额外的 jar / 本地库 —— 换语言只要换系统语音即可。
 * <p>
 * 朗读在子进程里排队执行，客户端只负责往管道里丢文本（{@link #speak} 永不阻塞渲染线程）。
 */
public final class TtsSpeaker {
    private static final TtsSpeaker INSTANCE = new TtsSpeaker();

    /** 待朗读队列：满了就直接丢新的，宁可漏一句也不能卡住客户端 */
    private static final int QUEUE_LIMIT = 32;

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_LIMIT);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicBoolean hookInstalled = new AtomicBoolean(false);
    private Thread writer;
    /**
     * 常驻 PowerShell 子进程的句柄。
     * <p>
     * 必须留一份在外面：以前只有 {@code run()} 的局部变量持有它，退出时靠 interrupt 让线程
     * 走到 finally 才 destroy —— 可线程多半正阻塞在 {@code queue.take()} 之后的 {@code out.flush()}
     * （管道写满、子进程还在朗读）。{@code Thread.interrupt()} 是<b>打不断</b>阻塞中的 IO 的，
     * 于是 finally 永远到不了，子进程继续把剩下的句子念完 —— 表现就是"MC 都关了 TTS 还在说"。
     */
    private volatile Process process;
    /** 关掉之后不再接新句子（进程正在被销毁） */
    private volatile boolean closing;
    /** 常驻进程当时用的语音：配置里换了语音就重启进程，否则切换永远不生效 */
    private volatile String activeVoice = "";

    private TtsSpeaker() {
    }

    public static TtsSpeaker get() {
        return INSTANCE;
    }

    /** 系统已安装语音名（懒加载 + 缓存；查不到就返回空列表） */
    private volatile java.util.List<String> voiceCache;

    /**
     * 列举系统已安装的语音（Windows 讲述人用的就是同一套语音包）。
     * <p>
     * 跑一次 PowerShell 从 {@code System.Speech} 里读语音名，结果缓存下来，
     * 之后点"语音包"按钮就是瞬时切换，不再起进程。
     * <p>
     * <b>会阻塞调用线程</b>（起进程 + 等它输出，通常 0.3~3 秒）。界面里请改用
     * {@link #requestVoices(java.util.function.Consumer)}，否则第一次点"语音包"按钮时
     * 整个游戏会卡住一下。
     */
    public java.util.List<String> listVoices() {
        java.util.List<String> cached = this.voiceCache;
        if (cached != null) {
            return cached;
        }
        java.util.List<String> voices = new java.util.ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command",
                    "Add-Type -AssemblyName System.Speech;"
                            + "(New-Object System.Speech.Synthesis.SpeechSynthesizer)"
                            + ".GetInstalledVoices() | ForEach-Object {$_.VoiceInfo.Name}");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String name = line.trim();
                    if (!name.isEmpty()) {
                        voices.add(name);
                    }
                }
            }
            p.destroy();
        } catch (Throwable t) {
            AiPlayerMod.LOGGER.warn("[aiplayer] 读取系统语音列表失败：{}", t.toString());
        }
        // 空结果不缓存：第一次查的时候可能进程还没起来/超时了，缓存空列表会导致
        // “语音包”按钮永远只有系统默认一项、怎么点都没反应
        if (!voices.isEmpty()) {
            this.voiceCache = voices;
        }
        return voices;
    }

    /**
     * 异步取语音列表：查询跑在后台线程，结果回到客户端主线程再交给 {@code callback}。
     * <p>
     * 界面里点"语音包"按钮必须走这条 —— {@link #listVoices()} 会起 PowerShell 并等它退出，
     * 放在渲染线程上就是一次肉眼可见的卡顿。已经有缓存时仍然同步立刻回调，保证切换是瞬时的。
     */
    public void requestVoices(java.util.function.Consumer<java.util.List<String>> callback) {
        java.util.List<String> cached = this.voiceCache;
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        Thread thread = new Thread(() -> {
            java.util.List<String> voices = listVoices();
            net.minecraft.client.Minecraft.getInstance().execute(() -> callback.accept(voices));
        }, "aiplayer-tts-voices");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 朗读一句 AI 台词。
     *
     * @param speaker AI 的名字（会读成"某某说：……"，方便分辨是谁在说话）
     * @param text    已经按本地语言渲染好的文本（可能是 null）
     */
    public void speak(String speaker, String text) {
        if (this.closing || !ClientTtsConfig.get().ttsEnabled || text == null || text.isBlank()) {
            return;
        }
        String line = clean(text);
        if (line.isBlank()) {
            return;
        }
        String who = speaker == null || speaker.isBlank() ? "" : clean(speaker);
        String full = who.isEmpty() ? line : who + "：" + line;
        if (line.length() > 300) {
            full = full.substring(0, 300);
        }
        if (this.failed.get()) {
            return;
        }
        syncVoice();
        if (!this.queue.offer(full)) {
            // 队列满了（AI 話太多）：丢最旧的，保证最新的能被读出来
            this.queue.poll();
            this.queue.offer(full);
        }
        ensureWorker();
    }

    /** 试听：GUI 里点"试听"按钮用，跳过队列直接读一句 */
    public void preview() {
        this.failed.set(false);
        this.queue.clear();
        syncVoice();
        this.queue.offer(ClientTtsConfig.get().previewText());
        ensureWorker();
    }

    /**
     * 语音切换生效的关键：PowerShell 进程只在启动那一刻读一次 {@code SelectVoice}，
     * 之后一直常驻 —— 玩家在 GUI 里换语音时进程还活着，永远读的还是旧语音。
     * 这里对比配置与进程的语音，不一致就踢掉旧进程，下一句用新语音重新起进程。
     */
    private void syncVoice() {
        String want = ClientTtsConfig.get().ttsVoice;
        want = want == null ? "" : want.trim();
        if (want.equals(this.activeVoice)) {
            return;
        }
        this.activeVoice = want;
        if (this.started.get()) {
            Thread w = this.writer;
            if (w != null) {
                w.interrupt();   // run() 里会销毁进程并把 started 复位
            }
        }
    }

    public boolean isAvailable() {
        return !this.failed.get();
    }

    /**
     * 关闭子进程（客户端退出时调用）。
     * <p>
     * 直接在这里 {@code destroyForcibly()}，不等朗读线程自己收尾 —— 线程可能正卡在管道写，
     * interrupt 打不断它。同时清空待读队列，免得退出前还补念几句。
     */
    public void shutdown() {
        this.closing = true;
        this.queue.clear();
        Process p = this.process;
        if (p != null) {
            p.destroyForcibly();
            this.process = null;
        }
        try {
            if (this.writer != null) {
                this.writer.interrupt();
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureWorker() {
        if (this.closing) {
            return;
        }
        if (!this.started.compareAndSet(false, true)) {
            return;
        }
        // 兜底：客户端异常崩溃（CLIENT_STOPPING 不触发）时也要带走 PowerShell。
        // 不注册的话，进程孤儿化后会继续把队列里没读完的句子念下去。
        if (!this.hookInstalled.getAndSet(true)) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "aiplayer-tts-hook"));
            } catch (Throwable ignored) {
            }
        }
        this.writer = new Thread(this::run, "aiplayer-tts");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    private void run() {
        try {
            this.process = startProcess();
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
            while (!Thread.currentThread().isInterrupted() && !this.closing) {
                String line = this.queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                out.write(line.replace('\n', ' ').replace('\r', ' '));
                out.write('\n');
                out.flush();
            }
            out.close();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            this.failed.set(true);
            AiPlayerMod.LOGGER.warn("[aiplayer] 系统语音不可用（TTS 已停用）：{}", t.toString());
        } finally {
            Process p = this.process;
            if (p != null) {
                p.destroyForcibly();
                this.process = null;
            }
            this.started.set(false);
        }
    }

    /**
     * 起 PowerShell 子进程：加载 System.Speech 后用 UTF-8 逐行读 stdin 并朗读。
     * <p>
     * stdin 显式包一层 UTF-8 的 {@code StreamReader}（不依赖 {@code Console.InputEncoding}，
     * 管道输入时那个属性会失效，中文会变乱码）；stdout/stderr 直接丢弃，避免子进程写满缓冲区卡死。
     */
    private Process startProcess() throws Exception {
        ClientTtsConfig cfg = ClientTtsConfig.get();
        StringBuilder ps = new StringBuilder();
        ps.append("$ErrorActionPreference='SilentlyContinue';");
        ps.append("Add-Type -AssemblyName System.Speech;");
        ps.append("$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;");
        ps.append("$s.Rate=").append(Math.max(-10, Math.min(10, cfg.ttsRate))).append(';');
        // 指定语音（留空就用系统默认语音）
        if (cfg.ttsVoice != null && !cfg.ttsVoice.isBlank()) {
            ps.append("try{$s.SelectVoice('")
                    .append(cfg.ttsVoice.replace("'", "''"))
                    .append("')}catch{};");
        }
        ps.append("$r=New-Object System.IO.StreamReader([Console]::OpenStandardInput(),"
                + "[System.Text.Encoding]::UTF8);");
        ps.append("while($true){$l=$r.ReadLine();if($null -eq $l){break};");
        ps.append("if($l.Trim().Length -gt 0){$s.Speak($l)}};");

        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-Command", ps.toString());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    /** 去掉原版格式控制符（§x）与多余空白，让朗读不至于念出"和号 a"之类的东西 */
    private static String clean(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7') {
                i++;   // 跳过格式码的第二个字符
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }
}
