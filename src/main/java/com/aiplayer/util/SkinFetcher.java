package com.aiplayer.util;

import com.aiplayer.AiPlayerMod;
import com.google.common.collect.HashMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * “正版玩家名 → 皮肤”解析器。
 * <p>
 * 客户端渲染其他玩家时，是用 {@code PlayerInfo} 里 GameProfile 的 {@code textures} 属性去查皮肤的，
 * 所以只要在生成 AI 之前把该属性写进 profile，并让它随 PlayerInfo 广播出去，AI 就会穿上对应皮肤。
 * <p>
 * 解析走 Mojang 官方接口（离线或超时则静默跳过，不影响生成）。
 */
public final class SkinFetcher {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final long FAIL_TTL_MS = 5 * 60 * 1000L;

    /** 后台皮肤拉取线程：只有一个，且是 daemon，不阻止游戏退出 */
    private static final java.util.concurrent.ExecutorService FETCHER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "aiplayer-skin-fetcher");
                thread.setDaemon(true);
                return thread;
            });

    private record Entry(Property property, long expireAt) {
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    /** 正在后台拉取中的名字，避免同一个名字被重复拉 */
    private static final java.util.Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private SkinFetcher() {
    }

    /**
     * 构造带指定正版玩家皮肤的 GameProfile（skinName 为空或解析失败时返回普通 profile）。
     * <p>
     * authlib 9 起 GameProfile 是 record，{@code new GameProfile(uuid, name)} 内部用的是
     * 不可变的 {@code PropertyMap.EMPTY}，所以要自带皮肤必须走三参构造。
     */
    public static GameProfile withSkin(UUID uuid, String name, String skinName) {
        if (skinName == null || skinName.isBlank()) {
            return new GameProfile(uuid, name);
        }
        // 只读缓存：这里绝不能联网。withSkin 是在生成 AI 时调用的，而生成跑在服务端主线程上，
        // 一旦 Mojang 接口慢/被墙，整个服务器都会跟着卡住（玩家看到的就是"点了生成没反应"）。
        Property property = cached(skinName);
        if (property == null) {
            prefetch(skinName);
            return new GameProfile(uuid, name);
        }
        // 26.x 的 PropertyMap(Multimap) 构造后内部变成不可变包装，直接 put 会抛
        // UnsupportedOperationException —— 先在可变 HashMultimap 上放好，再包一层
        com.google.common.collect.HashMultimap<String, Property> raw =
                com.google.common.collect.HashMultimap.create();
        raw.put("textures", property);
        PropertyMap map = new PropertyMap(raw);
        return new GameProfile(uuid, name, map);
    }

    /** 只读缓存：命中返回皮肤属性，未命中返回 null。任何线程都可安全调用，不做 IO */
    public static Property cached(String skinName) {
        if (skinName == null || skinName.isBlank()) {
            return null;
        }
        Entry entry = CACHE.get(skinName.trim().toLowerCase(Locale.ROOT));
        return entry != null && entry.expireAt() > System.currentTimeMillis() ? entry.property() : null;
    }

    /** 缓存里是否已经有结论（拉到 / 确认没有 / 拉取失败都算）。false 表示还没尝试过 */
    public static boolean isResolved(String skinName) {
        if (skinName == null || skinName.isBlank()) {
            return true;
        }
        Entry entry = CACHE.get(skinName.trim().toLowerCase(Locale.ROOT));
        return entry != null && entry.expireAt() > System.currentTimeMillis();
    }

    /** 后台拉取皮肤并写入缓存；同一名字不会重复拉。调用线程立即返回，绝不阻塞 */
    public static void prefetch(String skinName) {
        if (skinName == null || skinName.isBlank()) {
            return;
        }
        String name = skinName.trim();
        String key = name.toLowerCase(Locale.ROOT);
        if (isResolved(name) || !IN_FLIGHT.add(key)) {
            return;
        }
        FETCHER.execute(() -> {
            try {
                Property property = fetch(name);
                if (property == null) {
                    AiPlayerMod.LOGGER.info("[aiplayer] 玩家 {} 没有可用的皮肤，使用默认皮肤", name);
                }
                CACHE.put(key, property == null
                        ? new Entry(null, System.currentTimeMillis() + FAIL_TTL_MS)
                        : new Entry(property, Long.MAX_VALUE));
            } catch (Throwable t) {
                AiPlayerMod.LOGGER.warn("[aiplayer] 获取皮肤 {} 失败（将使用默认皮肤）: {}", name, t.toString());
                CACHE.put(key, new Entry(null, System.currentTimeMillis() + FAIL_TTL_MS));
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    private static Property fetch(String name) throws Exception {
        String uuidBody = get("https://api.mojang.com/users/profiles/minecraft/" + name);
        if (uuidBody == null || uuidBody.isBlank()) {
            return null;
        }
        JsonObject user = JsonParser.parseString(uuidBody).getAsJsonObject();
        if (!user.has("id")) {
            return null;
        }
        String id = user.get("id").getAsString();
        String profileBody = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
        JsonObject profile = JsonParser.parseString(profileBody).getAsJsonObject();
        JsonArray properties = profile.getAsJsonArray("properties");
        if (properties == null) {
            return null;
        }
        for (JsonElement element : properties) {
            JsonObject o = element.getAsJsonObject();
            if (!o.has("name") || !"textures".equals(o.get("name").getAsString())) {
                continue;
            }
            String value = o.get("value").getAsString();
            return o.has("signature")
                    ? new Property("textures", value, o.get("signature").getAsString())
                    : new Property("textures", value);
        }
        return null;
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "aiplayer-mod")
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 204) {
            // Mojang 对不存在的玩家名返回 204 No Content —— 正常情况，不算错误
            return "";
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }
}