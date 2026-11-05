package com.aiplayer.client.skin;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.util.SkinFetcher;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CustomSkinLoader 本地皮肤写入器。
 * <p>
 * 为什么需要它：CSL 是**按玩家名**解析皮肤的，它会直接忽略服务端随 PlayerInfo 下发的
 * {@code textures} 属性。所以"给 AI 指定一个正版玩家的皮肤"这条路在装了 CSL 的客户端上
 * 是走不通的 —— AI 名字是 {@code Steve-1}，CSL 就去找 {@code Steve-1} 的皮肤，找不到就默认皮肤。
 * <p>
 * 解法：AI 保留自己的名字，客户端把目标的皮肤 PNG 写到
 * {@code CustomSkinLoader/LocalSkin/skins/<AI名>.png}。CSL 的默认配置里
 * {@code LocalSkin} 这一源的模板正是 {@code LocalSkin/skins/{USERNAME}.png}，
 * 于是 CSL 在解析 {@code Steve-1} 时会命中这个本地文件，AI 就穿上了指定皮肤。
 * <p>
 * 皮肤来源支持四种写法（{@code AiProfile.skinName}）：
 * <ol>
 *   <li>正版玩家名（走 Mojang 接口解析，复用服务端的 {@link SkinFetcher} 缓存）</li>
 *   <li>64 / 32 位纹理哈希（{@code https://textures.minecraft.net/texture/<hash>}）</li>
 *   <li>http(s) 直链 PNG</li>
 *   <li>玩家自己丢在 {@code LocalSkin/skins/} 里的文件名（直接复制一份）</li>
 * </ol>
 * 全部流程跑在独立后台线程上，绝不阻塞渲染线程；失败只记日志，不影响游戏。
 */
public final class CslSkinWriter {
    private static final String CSL_MOD_ID = "customskinloader";
    private static final String TEXTURE_HOST = "https://textures.minecraft.net/texture/";
    /** 正版名解析最长等待（毫秒）：Mojang 接口被墙时也不能一直挂着 */
    private static final long RESOLVE_TIMEOUT_MS = 15_000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 单线程 daemon：写皮肤是低频动作，串行跑即可，也不阻止游戏退出 */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "aiplayer-csl-skin");
        thread.setDaemon(true);
        return thread;
    });

    /** 同名 AI 只允许一个任务在跑，避免连点导致重复下载 */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private CslSkinWriter() {
    }

    /** 客户端有没有装 CustomSkinLoader */
    public static boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded(CSL_MOD_ID);
    }

    /** CSL 本地皮肤目录：{@code CustomSkinLoader/LocalSkin/skins} */
    private static Path skinsDir() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("CustomSkinLoader").resolve("LocalSkin").resolve("skins");
    }

    /** CSL 本地披风目录：{@code CustomSkinLoader/LocalSkin/capes} */
    private static Path capesDir() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("CustomSkinLoader").resolve("LocalSkin").resolve("capes");
    }

    public static Path skinFileOf(String aiName) {
        return skinsDir().resolve(aiName == null ? "" : aiName.trim() + ".png");
    }

    public static Path capeFileOf(String aiName) {
        return capesDir().resolve(aiName == null ? "" : aiName.trim() + ".png");
    }

    /**
     * 让 AI {@code aiName} 穿上 {@code skinSource} 指定的皮肤（后台执行，立即返回）。
     *
     * @param skinSource 空字符串表示清除本地皮肤文件（AI 回到默认皮肤）
     */
    public static void install(String aiName, String skinSource) {
        if (aiName == null || aiName.isBlank()) {
            return;
        }
        String name = aiName.trim();
        String key = name.toLowerCase(Locale.ROOT);
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        String source = skinSource == null ? "" : skinSource.trim();
        POOL.execute(() -> {
            try {
                if (!isAvailable()) {
                    // 没装 CSL 时写这个文件没有任何意义，直接跳过
                    return;
                }
                Path target = skinsDir().resolve(name + ".png");
                Path capeTarget = capesDir().resolve(name + ".png");
                if (source.isEmpty()) {
                    Files.deleteIfExists(target);
                    Files.deleteIfExists(capeTarget);
                    AiPlayerMod.LOGGER.info("[aiplayer] CSL 本地皮肤：已清除 {}", target.getFileName());
                    return;
                }
                // 披风：CSL 也支持 LocalSkin/capes/{USERNAME}.png。
                // 正版玩家的 SKIN 和 CAPE 在同一个 textures base64 里，走正版名解析时
                // 顺手把 CAPE 直链也取出来写上 —— 否则"AI 穿上皮肤但没有披风"。
                byte[] cape = null;
                if (isPlainName(source) && !Files.isRegularFile(skinsDir().resolve(source + ".png"))) {
                    String capeUrl = capeUrlOf(source);
                    if (capeUrl != null) {
                        try {
                            cape = download(capeUrl);
                        } catch (Exception ignored) {
                            cape = null;
                        }
                    }
                }
                byte[] png = resolve(source);
                if (png == null || png.length < 64) {
                    AiPlayerMod.LOGGER.warn("[aiplayer] CSL 本地皮肤：解析不出 {} 的皮肤 PNG", source);
                    return;
                }
                writeIfChanged(target, png);
                if (cape != null && cape.length >= 64) {
                    writeIfChanged(capeTarget, cape);
                }
                AiPlayerMod.LOGGER.info("[aiplayer] CSL 本地皮肤：{} <- {}（{} 字节，披风{}）",
                        name, source, png.length, cape != null && cape.length >= 64 ? "已写入" : "无");
            } catch (Throwable t) {
                AiPlayerMod.LOGGER.warn("[aiplayer] 写 CSL 本地皮肤失败（{}）: {}", name, t.toString());
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private static byte[] resolve(String source) throws Exception {
        // 1) 玩家自己放在 LocalSkin/skins/ 里的同名文件：直接复用
        if (isPlainName(source)) {
            Path local = skinsDir().resolve(source + ".png");
            if (Files.isRegularFile(local)) {
                return Files.readAllBytes(local);
            }
        }
        // 2) 直链
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return download(source);
        }
        // 3) 纹理哈希
        if (source.matches("[0-9a-fA-F]{32,64}")) {
            return download(TEXTURE_HOST + source);
        }
        // 4) 正版玩家名：等 SkinFetcher 后台线程解析出 textures 属性，再取里面的皮肤直链
        String url = textureUrlOf(source);
        return url == null ? null : download(url);
    }

    /** 文件名白名单：挡掉 {@code ../} 这类路径穿越 */
    private static boolean isPlainName(String s) {
        return s.matches("[A-Za-z0-9_.\\-]{1,64}");
    }

    /**
     * 等 {@link SkinFetcher} 把玩家名解析成皮肤直链。
     * <p>
     * {@code prefetch} 是异步的（内部只有一条 daemon 线程），这里轮询缓存等它出结果；
     * {@code isResolved} 为 true 且缓存为空说明"该玩家确实没有皮肤"，不用再等。
     */
    private static String textureUrlOf(String name) {
        SkinFetcher.prefetch(name);
        long deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Property property = SkinFetcher.cached(name);
            if (property != null) {
                return skinUrl(property.value());
            }
            if (SkinFetcher.isResolved(name)) {
                return null;
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** textures 属性是 base64 的 JSON，取里面 {@code textures.SKIN.url} */
    private static String skinUrl(String base64) {
        return textureUrl(base64, "SKIN");
    }

    /**
     * 从 textures base64 里取指定纹理（SKIN / CAPE / ELYTRA）的直链。
     * 正版玩家的皮肤和披风是同一份 textures 属性，所以披风能直接复用这份缓存。
     */
    private static String textureUrl(String base64, String kind) {
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null) {
                return null;
            }
            JsonObject entry = textures.getAsJsonObject(kind);
            if (entry == null || !entry.has("url")) {
                return null;
            }
            return entry.get("url").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 正版玩家名对应的披风直链（没有披风返回 null） */
    private static String capeUrlOf(String name) {
        SkinFetcher.prefetch(name);
        long deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Property property = SkinFetcher.cached(name);
            if (property != null) {
                return textureUrl(property.value(), "CAPE");
            }
            if (SkinFetcher.isResolved(name)) {
                return null;
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 内容和目标文件一样就跳过，避免每次进游戏都重写同一个文件。
     *
     * @return true 表示文件现在已经是想要的内容
     */
    private static boolean writeIfChanged(Path target, byte[] data) throws Exception {
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), data)) {
            return true;
        }
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        return true;
    }

    private static byte[] download(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "aiplayer-mod")
                .GET()
                .build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return response.statusCode() == 200 ? response.body() : null;
    }
}
