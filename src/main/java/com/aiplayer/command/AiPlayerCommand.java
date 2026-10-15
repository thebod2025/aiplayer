package com.aiplayer.command;

import com.aiplayer.ai.AiBrain;
import com.aiplayer.ai.AiState;
import com.aiplayer.ai.bridge.BridgeMode;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.entity.AiPlayerManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * /aiplayer 指令族：生成 / 移除 / 查看、以及给单个 AI 下发任务。
 * 想用 GUI 的地方在客户端界面里也有对应开关，两边改的是同一份配置。
 */
public final class AiPlayerCommand {
    private AiPlayerCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aiplayer")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> spawn(ctx, 0, true))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 4))
                                        .executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "level"), true)))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    boolean ok = AiPlayerManager.get().remove(name);
                                    return reply(ctx, ok ? "已移除 " + name : "找不到 " + name);
                                })))
                .then(Commands.literal("removeall")
                        .executes(ctx -> {
                            int n = AiPlayerManager.get().count();
                            AiPlayerManager.get().removeAll();
                            return reply(ctx, "已移除 " + n + " 个 AI 玩家");
                        }))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            if (AiPlayerManager.get().count() == 0) {
                                return reply(ctx, "当前没有 AI 玩家");
                            }
                            for (AiPlayer ai : AiPlayerManager.get().all()) {
                                ctx.getSource().sendSuccess(() -> Component.literal(brain(ai).describe()), false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                    return ai == null ? reply(ctx, "找不到该 AI")
                                            : reply(ctx, brain(ai).describe());
                                })))
                .then(Commands.literal("level")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 4))
                                        .executes(ctx -> {
                                            AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                            if (ai == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            int level = IntegerArgumentType.getInteger(ctx, "level");
                                            ai.profile().levelId = level;
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, ai.profile().name + " 强度 -> " + ai.profile().level().display);
                                        }))))
                .then(Commands.literal("goto")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            AiBrain brain = brain(ctx);
                                                            if (brain == null) {
                                                                return reply(ctx, "找不到该 AI");
                                                            }
                                                            brain.walkTo(pos(ctx));
                                                            return reply(ctx, "前往 " + pos(ctx).toShortString());
                                                        }))))))
                .then(Commands.literal("mine")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            AiBrain brain = brain(ctx);
                                                            if (brain == null) {
                                                                return reply(ctx, "找不到该 AI");
                                                            }
                                                            brain.mineAt(pos(ctx));
                                                            return reply(ctx, "开始挖 " + pos(ctx).toShortString());
                                                        }))))))
                .then(Commands.literal("place")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            AiBrain brain = brain(ctx);
                                                            if (brain == null) {
                                                                return reply(ctx, "找不到该 AI");
                                                            }
                                                            brain.placeAt(pos(ctx));
                                                            return reply(ctx, "前往放置 " + pos(ctx).toShortString());
                                                        }))))))
                .then(Commands.literal("attack")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(ctx -> {
                                            AiBrain brain = brain(ctx);
                                            AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                            if (brain == null || ai == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            String targetName = StringArgumentType.getString(ctx, "target");
                                            if (ai.serverLevel().getServer() != null) {
                                                Entity target = ai.serverLevel().getServer().getPlayerList().getPlayerByName(targetName);
                                                if (target != null) {
                                                    brain.attack(target);
                                                    return reply(ctx, ai.profile().name + " 开始攻击 " + targetName);
                                                }
                                            }
                                            return reply(ctx, "找不到目标 " + targetName);
                                        }))))
                .then(Commands.literal("say")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            AiBrain brain = brain(ctx);
                                            if (brain == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            brain.say(StringArgumentType.getString(ctx, "text"));
                                            return reply(ctx, "已让 AI 说话");
                                        }))))
                .then(Commands.literal("cmd")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            AiBrain brain = brain(ctx);
                                            if (brain == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            String command = StringArgumentType.getString(ctx, "command");
                                            if (!com.aiplayer.ai.chat.ChatController.isSafe(command)) {
                                                return reply(ctx, "该指令被安全策略拦截");
                                            }
                                            brain.runCommand(command);
                                            return reply(ctx, "已让 AI 执行：" + command);
                                        }))))
                .then(Commands.literal("follow")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(ctx -> {
                                            AiBrain brain = brain(ctx);
                                            if (brain == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            String target = StringArgumentType.getString(ctx, "target");
                                            brain.setFollow("-".equals(target) ? "" : target);
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, "跟随目标已设置为 " + target);
                                        }))))
                .then(Commands.literal("bridge")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("mode", IntegerArgumentType.integer(0, 4))
                                        .executes(ctx -> {
                                            AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                            if (ai == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            int mode = IntegerArgumentType.getInteger(ctx, "mode");
                                            ai.profile().bridgeMode = mode;
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, ai.profile().name + " 搭路方式 -> " + BridgeMode.byId(mode).display);
                                        }))))
                .then(Commands.literal("feature")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> {
                                                    AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                                    if (ai == null) {
                                                        return reply(ctx, "找不到该 AI");
                                                    }
                                                    String key = StringArgumentType.getString(ctx, "key");
                                                    boolean value = BoolArgumentType.getBool(ctx, "value");
                                                    if (!ai.profile().setFeature(key, value)) {
                                                        return reply(ctx, "未知开关：" + key);
                                                    }
                                                    AiPlayerConfig.get().save();
                                                    return reply(ctx, key + " -> " + value);
                                                })))))
                .then(Commands.literal("pvp")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("enable", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                            if (ai == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            boolean on = BoolArgumentType.getBool(ctx, "enable");
                                            ai.brain().setAllowPlayerPvp(on);
                                            ai.profile().alwaysPvp = on;
                                            if (on) ai.profile().crystalPvp = true;
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, "始终 PvP -> " + on + (on ? "（自动打玩家/AI/怪物/水晶 PvP）" : ""));
                                        }))))
                .then(Commands.literal("team")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("team", StringArgumentType.word())
                                        .executes(ctx -> {
                                            AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                            if (ai == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            String team = StringArgumentType.getString(ctx, "team");
                                            // "-" / "clear" = 退队
                                            ai.profile().team = ("clear".equalsIgnoreCase(team) || "-".equals(team)) ? "" : team;
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, ai.profile().name + " 队伍 -> "
                                                    + (ai.profile().team.isBlank() ? "（无）" : ai.profile().team));
                                        }))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    AiBrain brain = brain(ctx);
                                    if (brain == null) {
                                        return reply(ctx, "找不到该 AI");
                                    }
                                    brain.stop();
                                    return reply(ctx, "已停止当前任务");
                                })))
                .then(Commands.literal("kit")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                    if (ai == null) {
                                        return reply(ctx, "找不到该 AI");
                                    }
                                    Entity source = ctx.getSource().getEntity();
                                    if (!(source instanceof ServerPlayer owner)) {
                                        return reply(ctx, "只有玩家能打开装备包 GUI");
                                    }
                                    com.aiplayer.gui.KitChestGui.open(owner, ai);
                                    return 1;
                                })))
                // 全局默认装备包：AI 档案没配自己的 kit 时用这套。服务端上也能用（联机客户端 GUI 转发到这里）
                .then(Commands.literal("defaultkit")
                        .executes(ctx -> {
                            Entity source = ctx.getSource().getEntity();
                            if (!(source instanceof ServerPlayer owner)) {
                                return reply(ctx, "只有玩家能打开装备包 GUI");
                            }
                            AiPlayerConfig cfg = AiPlayerConfig.get();
                            com.aiplayer.gui.KitChestGui.open(owner, "默认装备包（底行=快捷栏）",
                                    cfg.defaultKitNbt, json -> cfg.defaultKitNbt = json);
                            return 1;
                        }))
                .then(Commands.literal("apiurl")
                        .then(Commands.literal("global")
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            AiPlayerConfig.get().apiBaseUrl = StringArgumentType.getString(ctx, "url").trim();
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, "全局 API 地址已保存");
                                        })))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                            if (ai == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            String u = StringArgumentType.getString(ctx, "url").trim();
                                            ai.profile().apiBaseUrl = u;
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, ai.profile().name + " API 地址 -> " + (u.isEmpty() ? "（清空，退回全局）" : u));
                                        }))))
                .then(Commands.literal("apikey")
                        .then(Commands.literal("global")
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            AiPlayerConfig.get().apiKey = StringArgumentType.getString(ctx, "key").trim();
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, "全局 API Key 已保存");
                                        })))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                            if (ai == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            String k = StringArgumentType.getString(ctx, "key").trim();
                                            ai.profile().apiKey = k;
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, ai.profile().name + " API Key -> " + (k.isEmpty() ? "（清空，退回全局）" : "已设置"));
                                        }))))
                .then(Commands.literal("model")
                        .then(Commands.literal("global")
                                .then(Commands.argument("model", StringArgumentType.word())
                                        .executes(ctx -> {
                                            AiPlayerConfig.get().model = StringArgumentType.getString(ctx, "model").trim();
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, "全局模型已设置");
                                        })))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("model", StringArgumentType.word())
                                        .executes(ctx -> {
                                            AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
                                            if (ai == null) {
                                                return reply(ctx, "找不到该 AI");
                                            }
                                            String m = StringArgumentType.getString(ctx, "model").trim();
                                            ai.profile().model = m;
                                            AiPlayerConfig.get().save();
                                            return reply(ctx, ai.profile().name + " 模型 -> " + (m.isEmpty() ? "（清空，退回全局）" : m));
                                        }))))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            AiPlayerConfig.get().load();
                            return reply(ctx, "配置已重载");
                        })));
    }

    // ------------------------------------------------------------------

    private static AiBrain brain(CommandContext<CommandSourceStack> ctx) {
        AiPlayer ai = AiPlayerManager.get().get(StringArgumentType.getString(ctx, "name"));
        return ai == null ? null : ai.brain();
    }

    private static AiBrain brain(AiPlayer ai) {
        return ai.brain();
    }

    private static BlockPos pos(CommandContext<CommandSourceStack> ctx) {
        return new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, int level, boolean kit) {
        String name = StringArgumentType.getString(ctx, "name");
        AiPlayerManager manager = AiPlayerManager.get();
        if (manager.get(name) != null) {
            return reply(ctx, "已经有同名的 AI 玩家了");
        }
        ServerLevel level0 = ctx.getSource().getLevel();
        Vec3 position = ctx.getSource().getPosition();
        Entity source = ctx.getSource().getEntity();
        float yaw = source == null ? 0.0F : source.getYRot();
        float pitch = source == null ? 0.0F : source.getXRot();
        AiPlayer ai = manager.spawn(level0, name, position, yaw, pitch, kit);
        if (ai == null) {
            return reply(ctx, "生成失败（名字非法或已被占用）");
        }
        if (level >= 1 && level <= 4) {
            ai.profile().levelId = level;
        }
        AiProfile profile = ai.profile();
        // 由玩家执行 /aiplayer spawn 时，执行者就是主人（控制台执行则沿用默认主人 The_bodboy_）
        if (source instanceof Player owner) {
            profile.ownerName = owner.getScoreboardName();
        }
        AiPlayerConfig.get().save();
        return reply(ctx, "已生成 AI 玩家 " + profile.name + "（强度 " + profile.level().display + "）");
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal("[aiplayer] " + message), false);
        return 1;
    }

    /** 给其它地方（GUI/网络）复用：改变状态并回显 */
    public static String describeState(AiPlayer ai) {
        AiState state = ai.brain() == null ? AiState.IDLE : ai.brain().state();
        return ai.profile().name + " | " + state.display;
    }
}