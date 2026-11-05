package com.aiplayer.command;

import com.aiplayer.ai.AiBrain;
import com.aiplayer.ai.bridge.BridgeMode;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.config.AiProfile;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.entity.AiPlayerManager;
import com.aiplayer.zone.ZoneManager;
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

import java.util.List;

/**
 * /aiplayer 指令族：生成 / 移除 / 查看、以及给 AI 下发任务。
 * 想用 GUI 的地方在客户端界面里也有对应开关，两边改的是同一份配置。
 * <p>
 * 所有指向 AI 的名字参数都支持目标选择器（见 {@link AiTargets}）：
 * {@code @all}、{@code @team:队伍}、{@code @near}、{@code @r}、名字通配 {@code AI*}，
 * 一条指令可同时作用于多只 AI。
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
                // 批量生成：一次拉起 N 只随机性格的 AI，名字自动取 AI1、AI2……
                .then(Commands.literal("spawnmany")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> spawnMany(ctx, IntegerArgumentType.getInteger(ctx, "count"), 0))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 4))
                                        .executes(ctx -> spawnMany(ctx, IntegerArgumentType.getInteger(ctx, "count"),
                                                IntegerArgumentType.getInteger(ctx, "level"))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> {
                                    List<AiPlayer> list = targets(ctx);
                                    int n = 0;
                                    for (AiPlayer ai : list) {
                                        if (AiPlayerManager.get().remove(AiTargets.nameOf(ai))) {
                                            n++;
                                        }
                                    }
                                    if (n == 0) {
                                        return replyKey(ctx, "aiplayer.cmd.selectorNone",
                                                StringArgumentType.getString(ctx, "name"));
                                    }
                                    return n == 1 ? replyKey(ctx, "aiplayer.cmd.removeOk", AiTargets.nameOf(list.get(0)))
                                            : replyKey(ctx, "aiplayer.cmd.removeAll", n);
                                })))
                .then(Commands.literal("removeall")
                        .executes(ctx -> {
                            int n = AiPlayerManager.get().count();
                            AiPlayerManager.get().removeAll();
                            return replyKey(ctx, "aiplayer.cmd.removeAll", n);
                        }))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            if (AiPlayerManager.get().count() == 0) {
                                return replyKey(ctx, "aiplayer.cmd.listNone");
                            }
                            for (AiPlayer ai : AiPlayerManager.get().all()) {
                                ctx.getSource().sendSuccess(() -> brain(ai).describe(), false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("task")
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> each(ctx, ai -> {
                                    AiBrain aiBrain = ai.brain();
                                    ctx.getSource().sendSuccess(aiBrain::taskSummary, false);
                                    // 模型链路单独一行：不配 Key / 等间隔 / 请求在途 / 退避，一眼可辨
                                    ctx.getSource().sendSuccess(() -> Component.translatable(
                                            aiBrain.llmPhaseKey(),
                                            Component.literal(ai.getName().getString())), false);
                                    return 1;
                                }))))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> each(ctx, ai -> reply(ctx, brain(ai).describe())))))
                // 调试面板：每只 AI 的状态 / 目标 / 睡眠 / 卡住 / 模型链路 / 决策耗时
                .then(Commands.literal("stats")
                        .executes(ctx -> stats(ctx, null))
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> stats(ctx, StringArgumentType.getString(ctx, "name")))))
                // 记忆管理：清空对话 + 持久习性（GUI 的“清空记忆”按钮也发这条指令）
                .then(Commands.literal("memory")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.literal("clear")
                                        .executes(ctx -> {
                                            int r = each(ctx, ai -> {
                                                brain(ai).clearMemory();
                                                return replyKey(ctx, "aiplayer.cmd.memoryCleared",
                                                        ai.profile().name);
                                            });
                                            AiPlayerConfig.get().save();
                                            return r;
                                        }))))
                // 枪械诊断：为什么不开枪（装没装 / 有没有枪 / 有没有子弹 / 有没有敌人）
                // 第二个参数写 test 会直接试射一次，打印 TaCZ 的原始结果
                .then(Commands.literal("gun")
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> each(ctx, ai -> {
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(com.aiplayer.compat.TaczCompat.debug(ai)), false);
                                    return 1;
                                }))
                                .then(Commands.literal("test")
                                        .executes(ctx -> each(ctx, ai -> {
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "试射：" + com.aiplayer.compat.TaczCompat.testShoot(ai)), false);
                                            return 1;
                                        })))
                                .then(Commands.literal("give")
                                        .executes(ctx -> each(ctx, ai -> {
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    com.aiplayer.compat.TaczCompat.grantStarterGun(ai)), false);
                                            return 1;
                                        })))))
                .then(Commands.literal("level")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 4))
                                        .executes(ctx -> {
                                            int level = IntegerArgumentType.getInteger(ctx, "level");
                                            int r = each(ctx, ai -> {
                                                ai.profile().levelId = level;
                                                return replyKey(ctx, "aiplayer.cmd.levelSet", ai.profile().name,
                                                        Component.translatable("aiplayer.level." + level));
                                            });
                                            AiPlayerConfig.get().save();
                                            return r;
                                        }))))
                .then(Commands.literal("goto")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> each(ctx, ai -> {
                                                            ai.brain().walkTo(pos(ctx));
                                                            return replyKey(ctx, "aiplayer.cmd.gotoOk",
                                                                    pos(ctx).toShortString());
                                                        })))))))
                .then(Commands.literal("mine")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> each(ctx, ai -> {
                                                            ai.brain().mineAt(pos(ctx));
                                                            return replyKey(ctx, "aiplayer.cmd.mineOk",
                                                                    pos(ctx).toShortString());
                                                        })))))))
                .then(Commands.literal("place")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> each(ctx, ai -> {
                                                            ai.brain().placeAt(pos(ctx));
                                                            return replyKey(ctx, "aiplayer.cmd.placeOk",
                                                                    pos(ctx).toShortString());
                                                        })))))))
                .then(Commands.literal("attack")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String targetName = StringArgumentType.getString(ctx, "target");
                                            return each(ctx, ai -> {
                                                if (ai.serverLevel().getServer() != null) {
                                                    Entity target = ai.serverLevel().getServer().getPlayerList()
                                                            .getPlayerByName(targetName);
                                                    if (target != null) {
                                                        ai.brain().attack(target);
                                                        return replyKey(ctx, "aiplayer.cmd.attackOk",
                                                                ai.profile().name, targetName);
                                                    }
                                                }
                                                return replyKey(ctx, "aiplayer.cmd.attackNoTarget", targetName);
                                            });
                                        }))))
                .then(Commands.literal("say")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String text = StringArgumentType.getString(ctx, "text");
                                            return each(ctx, ai -> {
                                                ai.brain().say(text);
                                                return replyKey(ctx, "aiplayer.cmd.sayOk");
                                            });
                                        }))))
                .then(Commands.literal("cmd")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String command = StringArgumentType.getString(ctx, "command");
                                            if (!com.aiplayer.ai.chat.ChatController.isSafe(command)) {
                                                return replyKey(ctx, "aiplayer.cmd.cmdBlocked");
                                            }
                                            return each(ctx, ai -> {
                                                ai.brain().runCommand(command);
                                                return replyKey(ctx, "aiplayer.cmd.cmdOk", command);
                                            });
                                        }))))
                .then(Commands.literal("follow")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String target = StringArgumentType.getString(ctx, "target");
                                            int r = each(ctx, ai -> {
                                                ai.brain().setFollow("-".equals(target) ? "" : target);
                                                return replyKey(ctx, "aiplayer.cmd.followOk", target);
                                            });
                                            AiPlayerConfig.get().save();
                                            return r;
                                        }))))
                .then(Commands.literal("bridge")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("mode", IntegerArgumentType.integer(0, 4))
                                        .executes(ctx -> {
                                            int mode = IntegerArgumentType.getInteger(ctx, "mode");
                                            int r = each(ctx, ai -> {
                                                ai.profile().bridgeMode = mode;
                                                return replyKey(ctx, "aiplayer.cmd.bridgeSet", ai.profile().name,
                                                        Component.translatable("aiplayer.bridge." + mode));
                                            });
                                            AiPlayerConfig.get().save();
                                            return r;
                                        }))))
                .then(Commands.literal("feature")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> {
                                                    String key = StringArgumentType.getString(ctx, "key");
                                                    boolean value = BoolArgumentType.getBool(ctx, "value");
                                                    int r = each(ctx, ai -> {
                                                        if (!ai.profile().setFeature(key, value)) {
                                                            return replyKey(ctx, "aiplayer.cmd.featureUnknown", key);
                                                        }
                                                        return reply(ctx, Component.literal(key + " -> " + value));
                                                    });
                                                    AiPlayerConfig.get().save();
                                                    return r;
                                                })))))
                .then(Commands.literal("pvp")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("enable", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            boolean on = BoolArgumentType.getBool(ctx, "enable");
                                            int r = each(ctx, ai -> {
                                                ai.brain().setAllowPlayerPvp(on);
                                                ai.profile().alwaysPvp = on;
                                                if (on) {
                                                    ai.profile().crystalPvp = true;
                                                }
                                                return replyKey(ctx, on ? "aiplayer.cmd.pvpOn"
                                                        : "aiplayer.cmd.pvpOff");
                                            });
                                            AiPlayerConfig.get().save();
                                            return r;
                                        }))))
                // 整队指令：只对同队 AI 生效（队伍名大小写不敏感）。
                // 放在单只 team 前面，否则 "team 红队 stop" 会被后注册的 "给 AI 红队设队伍 stop" 抢走。
                .then(Commands.literal("team")
                        .then(Commands.argument("team", StringArgumentType.word())
                                .then(Commands.literal("stop")
                                        .executes(ctx -> batchTeam(ctx, teamName(ctx), AiBrain::stop)))
                                .then(Commands.literal("home")
                                        .executes(ctx -> batchTeam(ctx, teamName(ctx),
                                                b -> goHome(b, ctx.getSource().getPosition())))
                                .then(Commands.literal("follow")
                                        .then(Commands.argument("target", StringArgumentType.word())
                                                .executes(ctx -> batchTeam(ctx, teamName(ctx),
                                                        b -> b.setFollow(StringArgumentType.getString(ctx, "target"))))))
                                .then(Commands.literal("guard")
                                        .then(Commands.argument("zone", StringArgumentType.word())
                                                .executes(ctx -> batchTeam(ctx, teamName(ctx),
                                                        b -> b.guardZone(StringArgumentType.getString(ctx, "zone"))))))
                                .then(Commands.literal("goto")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(ctx -> batchTeam(ctx, teamName(ctx),
                                                                        b -> b.walkTo(pos(ctx)))))))))))
                .then(Commands.literal("team")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("team", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String team = StringArgumentType.getString(ctx, "team");
                                            int r = each(ctx, ai -> {
                                                // "-" / "clear" = 退队
                                                ai.profile().team = ("clear".equalsIgnoreCase(team) || "-".equals(team))
                                                        ? "" : team;
                                                return replyKey(ctx, "aiplayer.cmd.teamSet", ai.profile().name,
                                                        ai.profile().team.isBlank()
                                                                ? Component.translatable("aiplayer.cmd.teamNone")
                                                                : Component.literal(ai.profile().team));
                                            });
                                            AiPlayerConfig.get().save();
                                            return r;
                                        }))))
                .then(Commands.literal("boss")
                        .executes(ctx -> {
                            // 无敌名：让所有 AI 一起进末影龙战
                            int n = 0;
                            for (AiPlayer ai : AiPlayerManager.get().all()) {
                                AiBrain b = ai.brain();
                                if (b == null) {
                                    continue;
                                }
                                b.startBossFight();
                                n++;
                            }
                            return n == 0 ? replyKey(ctx, "aiplayer.cmd.bossNone")
                                    : replyKey(ctx, "aiplayer.cmd.bossAll", n);
                        })
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> each(ctx, ai -> {
                                    ai.brain().startBossFight();
                                    return replyKey(ctx, "aiplayer.cmd.bossOne");
                                }))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> each(ctx, ai -> {
                                    ai.brain().stop();
                                    return replyKey(ctx, "aiplayer.cmd.stopOk");
                                }))))
                .then(Commands.literal("kit")
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> {
                                    Entity source = ctx.getSource().getEntity();
                                    if (!(source instanceof ServerPlayer owner)) {
                                        return replyKey(ctx, "aiplayer.cmd.playerOnly");
                                    }
                                    return each(ctx, ai -> {
                                        com.aiplayer.gui.KitChestGui.open(owner, ai);
                                        return 1;
                                    });
                                })))
                // 全局默认装备包：AI 档案没配自己的 kit 时用这套。服务端上也能用（联机客户端 GUI 转发到这里）
                .then(Commands.literal("defaultkit")
                        .executes(ctx -> {
                            Entity source = ctx.getSource().getEntity();
                            if (!(source instanceof ServerPlayer owner)) {
                                return replyKey(ctx, "aiplayer.cmd.playerOnly");
                            }
                            AiPlayerConfig cfg = AiPlayerConfig.get();
                            com.aiplayer.gui.KitChestGui.open(owner,
                                    Component.translatable("aiplayer.kit.defaultTitle"),
                                    cfg.defaultKitNbt, json -> cfg.defaultKitNbt = json);
                            return 1;
                        }))
                .then(Commands.literal("apiurl")
                        .then(Commands.literal("global")
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            AiPlayerConfig.get().apiBaseUrl = StringArgumentType.getString(ctx, "url").trim();
                                            AiPlayerConfig.get().save();
                                            return replyKey(ctx, "aiplayer.cmd.apiurlGlobal");
                                        })))
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String u = StringArgumentType.getString(ctx, "url").trim();
                                            int r = each(ctx, ai -> {
                                                ai.profile().apiBaseUrl = u;
                                                return replyKey(ctx, "aiplayer.cmd.apiurlSet", ai.profile().name,
                                                        u.isEmpty() ? Component.translatable("aiplayer.cmd.cleared")
                                                                : Component.literal(u));
                                            });
                                            AiPlayerConfig.get().save();
                                            return r;
                                        }))))
                .then(Commands.literal("apikey")
                        .then(Commands.literal("global")
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            AiPlayerConfig.get().apiKey = StringArgumentType.getString(ctx, "key").trim();
                                            AiPlayerConfig.get().save();
                                            return replyKey(ctx, "aiplayer.cmd.apikeyGlobal");
                                        })))
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String k = StringArgumentType.getString(ctx, "key").trim();
                                            int r = each(ctx, ai -> {
                                                ai.profile().apiKey = k;
                                                return replyKey(ctx, "aiplayer.cmd.apikeySet", ai.profile().name,
                                                        k.isEmpty() ? Component.translatable("aiplayer.cmd.cleared")
                                                                : Component.translatable("aiplayer.cmd.apikeyDone"));
                                            });
                                            AiPlayerConfig.get().save();
                                            return r;
                                        }))))
                .then(Commands.literal("model")
                        .then(Commands.literal("global")
                                .then(Commands.argument("model", StringArgumentType.word())
                                        .executes(ctx -> {
                                            AiPlayerConfig.get().model = StringArgumentType.getString(ctx, "model").trim();
                                            AiPlayerConfig.get().save();
                                            return replyKey(ctx, "aiplayer.cmd.modelGlobal");
                                        })))
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("model", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String m = StringArgumentType.getString(ctx, "model").trim();
                                            int r = each(ctx, ai -> {
                                                ai.profile().model = m;
                                                return replyKey(ctx, "aiplayer.cmd.modelSet", ai.profile().name,
                                                        m.isEmpty() ? Component.translatable("aiplayer.cmd.cleared")
                                                                : Component.literal(m));
                                            });
                                            AiPlayerConfig.get().save();
                                            return r;
                                        }))))
                // 连通性自检：只发一个最小请求，把状态码 / 超时 / 模型名直接回显，
                // 省得玩家靠"AI 不说话"去猜是 Key、地址还是模型的问题。
                .then(Commands.literal("testllm")
                        .executes(ctx -> testLlm(ctx, null))
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> testLlm(ctx, StringArgumentType.getString(ctx, "name")))))
                // 把 AI 传送到执行者（或指定玩家）身边
                .then(Commands.literal("tp")
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> teleport(ctx, null))
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(ctx -> teleport(ctx,
                                                StringArgumentType.getString(ctx, "target"))))))
                // 开关"开服自动在原地恢复这只 AI"
                .then(Commands.literal("autokeep")
                        .then(Commands.argument("name", AiTargets.argument())
                                .then(Commands.argument("enable", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            boolean on = BoolArgumentType.getBool(ctx, "enable");
                                            int r = each(ctx, ai -> {
                                                ai.profile().autoKeep = on;
                                                return replyKey(ctx, on ? "aiplayer.cmd.autokeepOn"
                                                        : "aiplayer.cmd.autokeepOff", ai.profile().name);
                                            });
                                            AiPlayerConfig.get().save();
                                            return r;
                                        }))))
                // 家（基地）：指定后夜间回家 / 自主回撤都走这里，并且写入档案，重启后还在
                .then(Commands.literal("home")
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> each(ctx, ai -> homeInfo(ctx, ai)))
                                .then(Commands.literal("set")
                                        // 不带坐标 = 用执行者脚下这个点当基地
                                        .executes(ctx -> each(ctx, ai -> homeSet(ctx, ai, null)))
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(ctx -> each(ctx,
                                                                        ai -> homeSet(ctx, ai, pos(ctx))))))))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> each(ctx, ai -> homeClear(ctx, ai))))))
                // 守卫 / 巡逻：AI 在指定保护区里来回走，看到怪交给战斗逻辑；被推出区域会自己走回来
                .then(Commands.literal("guard")
                        .then(Commands.argument("name", AiTargets.argument())
                                .executes(ctx -> each(ctx, ai -> guardStatus(ctx, ai)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> each(ctx, ai -> guardOff(ctx, ai)))
                                .then(Commands.argument("zone", StringArgumentType.word())
                                        .executes(ctx -> guardSet(ctx, targets(ctx),
                                                StringArgumentType.getString(ctx, "zone")))))))
                // 全员指令：一次给所有 AI 下同样的任务，不用一个个敲
                .then(Commands.literal("all")
                        .then(Commands.literal("stop")
                                .executes(ctx -> batchAll(ctx, AiBrain::stop)))
                        .then(Commands.literal("home")
                                .executes(ctx -> batchAll(ctx, b -> goHome(b, ctx.getSource().getPosition())))
                        .then(Commands.literal("follow")
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(ctx -> batchAll(ctx,
                                                b -> b.setFollow(StringArgumentType.getString(ctx, "target"))))))
                        .then(Commands.literal("guard")
                                .then(Commands.argument("zone", StringArgumentType.word())
                                        .executes(ctx -> batchAll(ctx,
                                                b -> b.guardZone(StringArgumentType.getString(ctx, "zone"))))))
                        .then(Commands.literal("goto")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> batchAll(ctx, b -> b.walkTo(pos(ctx))))))))))
                .then(Commands.literal("zone")
                        // 领地保护区：区域内的方块 AI 不挖、不放、不点火
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        // 半径写法：以执行者脚下为中心
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                                .executes(ctx -> zoneAddRadius(ctx,
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"))))
                                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                                .then(Commands.argument("y1", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                                        .executes(ctx -> zoneAdd(ctx,
                                                                                                StringArgumentType.getString(ctx, "name"),
                                                                                                new BlockPos(
                                                                                                        IntegerArgumentType.getInteger(ctx, "x1"),
                                                                                                        IntegerArgumentType.getInteger(ctx, "y1"),
                                                                                                        IntegerArgumentType.getInteger(ctx, "z1")),
                                                                                                new BlockPos(
                                                                                                        IntegerArgumentType.getInteger(ctx, "x2"),
                                                                                                        IntegerArgumentType.getInteger(ctx, "y2"),
                                                                                                        IntegerArgumentType.getInteger(ctx, "z2"))))))))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> zoneRemove(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("list")
                                .executes(AiPlayerCommand::zoneList))
                        .then(Commands.literal("clear")
                                .executes(ctx -> zoneClear(ctx))))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            AiPlayerConfig.get().load();
                            return replyKey(ctx, "aiplayer.cmd.reloadOk");
                        })));
    }

    // ------------------------------------------------------------------
    // 目标选择器 / 家 / 守卫 / 批量指令
    // ------------------------------------------------------------------

    /** 读取 name 参数并按选择器展开成所有目标 */
    private static List<AiPlayer> targets(CommandContext<CommandSourceStack> ctx) {
        return AiTargets.resolve(ctx.getSource(), StringArgumentType.getString(ctx, "name"));
    }

    /** 按 name 选择器逐只执行（每只 AI 自己回一行） */
    private static int each(CommandContext<CommandSourceStack> ctx,
                            java.util.function.Function<AiPlayer, Integer> action) {
        return eachOf(ctx, targets(ctx), action);
    }

    private static int eachOf(CommandContext<CommandSourceStack> ctx, String selector,
                              java.util.function.Function<AiPlayer, Integer> action) {
        return eachOf(ctx, AiTargets.resolve(ctx.getSource(), selector), action);
    }

    private static int eachOf(CommandContext<CommandSourceStack> ctx, List<AiPlayer> list,
                              java.util.function.Function<AiPlayer, Integer> action) {
        if (list.isEmpty()) {
            return replyKey(ctx, "aiplayer.cmd.selectorNone", StringArgumentType.getString(ctx, "name"));
        }
        int n = 0;
        for (AiPlayer ai : list) {
            if (ai.brain() == null) {
                continue;
            }
            action.apply(ai);
            n++;
        }
        return n == 0 ? replyKey(ctx, "aiplayer.cmd.notFound") : 1;
    }

    private static int homeInfo(CommandContext<CommandSourceStack> ctx, AiPlayer ai) {
        AiBrain brain = ai.brain();
        BlockPos home = brain.homePos();
        if (home == null) {
            return replyKey(ctx, "aiplayer.cmd.homeNone", ai.profile().name);
        }
        return replyKey(ctx, brain.hasPinnedHome() ? "aiplayer.cmd.homeInfo" : "aiplayer.cmd.homeTemp",
                ai.profile().name, posText(home));
    }

    /** @param at 为 null 时用执行者脚下这个点 */
    private static int homeSet(CommandContext<CommandSourceStack> ctx, AiPlayer ai, BlockPos at) {
        Vec3 p = ctx.getSource().getPosition();
        BlockPos home = at != null ? at : BlockPos.containing(p.x, p.y, p.z);
        ai.brain().setHome(home);
        return replyKey(ctx, "aiplayer.cmd.homeSet", ai.profile().name, posText(home));
    }

    private static int homeClear(CommandContext<CommandSourceStack> ctx, AiPlayer ai) {
        ai.brain().clearHome();
        return replyKey(ctx, "aiplayer.cmd.homeClear", ai.profile().name);
    }

    private static int guardStatus(CommandContext<CommandSourceStack> ctx, AiPlayer ai) {
        AiBrain brain = ai.brain();
        return brain.isGuarding()
                ? replyKey(ctx, "aiplayer.cmd.guardOk", ai.profile().name, brain.guardZoneName())
                : replyKey(ctx, "aiplayer.cmd.guardNone", ai.profile().name);
    }

    private static int guardSet(CommandContext<CommandSourceStack> ctx, List<AiPlayer> list, String zoneName) {
        if (ZoneManager.find(zoneName) == null) {
            return replyKey(ctx, "aiplayer.cmd.zoneNotFound", zoneName);
        }
        return eachOf(ctx, list, ai -> {
            ai.brain().guardZone(zoneName);
            return replyKey(ctx, "aiplayer.cmd.guardOk", ai.profile().name, zoneName);
        });
    }

    private static int guardOff(CommandContext<CommandSourceStack> ctx, AiPlayer ai) {
        ai.brain().stopGuard();
        return replyKey(ctx, "aiplayer.cmd.guardOff", ai.profile().name);
    }

    private static String teamName(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "team");
    }

    /** 对当前所有 AI 执行同一个动作 */
    private static int batchAll(CommandContext<CommandSourceStack> ctx,
                                java.util.function.Consumer<AiBrain> action) {
        int n = 0;
        for (AiPlayer ai : AiPlayerManager.get().all()) {
            AiBrain b = ai.brain();
            if (b != null) {
                action.accept(b);
                n++;
            }
        }
        return n == 0 ? replyKey(ctx, "aiplayer.cmd.listNone")
                : replyKey(ctx, "aiplayer.cmd.batchOk", n);
    }

    /** 只对同队 AI 执行同一个动作（队伍名大小写不敏感） */
    private static int batchTeam(CommandContext<CommandSourceStack> ctx, String team,
                                 java.util.function.Consumer<AiBrain> action) {
        int n = 0;
        for (AiPlayer ai : AiPlayerManager.get().all()) {
            AiProfile profile = ai.profile();
            if (profile == null || profile.team == null || !profile.team.equalsIgnoreCase(team)) {
                continue;
            }
            AiBrain b = ai.brain();
            if (b != null) {
                action.accept(b);
                n++;
            }
        }
        return n == 0 ? replyKey(ctx, "aiplayer.cmd.teamEmpty", team)
                : replyKey(ctx, "aiplayer.cmd.batchOk", n);
    }

    /** 全员回家：没指定过家时把执行者脚下设为家（顺手写进档案） */
    private static void goHome(AiBrain brain, Vec3 fallback) {
        BlockPos home = brain.homePos();
        if (brain.hasPinnedHome() && home != null) {
            brain.walkTo(home);
            return;
        }
        BlockPos at = BlockPos.containing(fallback.x, fallback.y, fallback.z);
        brain.setHome(at);
        brain.walkTo(at);
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    // ------------------------------------------------------------------

    /** /aiplayer zone add <名字> <半径>：以执行者脚下为中心建一个立方体保护区 */
    private static int zoneAddRadius(CommandContext<CommandSourceStack> ctx, String name, int radius) {
        Vec3 p = ctx.getSource().getPosition();
        BlockPos center = BlockPos.containing(p.x, p.y, p.z);
        return zoneAdd(ctx, name,
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius));
    }

    private static int zoneAdd(CommandContext<CommandSourceStack> ctx, String name, BlockPos a, BlockPos b) {
        ZoneManager.Zone zone = ZoneManager.add(name, ctx.getSource().getLevel(), a, b);
        if (zone == null) {
            return replyKey(ctx, "aiplayer.cmd.zoneFail");
        }
        return reply(ctx, Component.translatable("aiplayer.cmd.zoneAdded",
                Component.literal(zone.name), Component.literal(zone.dimension),
                Component.literal(zone.rangeText())));
    }

    private static int zoneRemove(CommandContext<CommandSourceStack> ctx, String name) {
        return ZoneManager.remove(name) ? replyKey(ctx, "aiplayer.cmd.zoneRemoved", name)
                : replyKey(ctx, "aiplayer.cmd.zoneNotFound", name);
    }

    private static int zoneList(CommandContext<CommandSourceStack> ctx) {
        List<ZoneManager.Zone> zones = ZoneManager.all();
        if (zones.isEmpty()) {
            return replyKey(ctx, "aiplayer.cmd.zoneNone");
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[aiplayer] ")
                .append(Component.translatable("aiplayer.cmd.zoneList", zones.size())), false);
        for (ZoneManager.Zone zone : zones) {
            ctx.getSource().sendSuccess(() -> Component.literal(" - ")
                    .append(Component.literal(zone.name)).append(Component.literal(" · "))
                    .append(Component.literal(zone.dimension)).append(Component.literal(" · "))
                    .append(Component.literal(zone.rangeText())), false);
        }
        return 1;
    }

    private static int zoneClear(CommandContext<CommandSourceStack> ctx) {
        return replyKey(ctx, "aiplayer.cmd.zoneCleared", ZoneManager.clear());
    }

    /**
     * /aiplayer stats：一行概况 + 一行任务 + 一行细节。
     * 全读内存字段，随时调用都不会卡服；不带名字时列出全部 AI。
     */
    private static int stats(CommandContext<CommandSourceStack> ctx, String name) {
        AiPlayerManager manager = AiPlayerManager.get();
        if (name != null) {
            return eachOf(ctx, name, ai -> {
                printStats(ctx, ai);
                return 1;
            });
        }
        if (manager.count() == 0) {
            return replyKey(ctx, "aiplayer.cmd.listNone");
        }
        for (AiPlayer ai : manager.all()) {
            printStats(ctx, ai);
        }
        return 1;
    }

    private static void printStats(CommandContext<CommandSourceStack> ctx, AiPlayer ai) {
        AiBrain brain = ai.brain();
        if (brain == null) {
            return;
        }
        ctx.getSource().sendSuccess(brain::describe, false);
        ctx.getSource().sendSuccess(brain::taskSummary, false);
        ctx.getSource().sendSuccess(brain::statsDetail, false);
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
            return replyKey(ctx, "aiplayer.cmd.spawnDup");
        }
        ServerLevel level0 = ctx.getSource().getLevel();
        Vec3 position = ctx.getSource().getPosition();
        Entity source = ctx.getSource().getEntity();
        float yaw = source == null ? 0.0F : source.getYRot();
        float pitch = source == null ? 0.0F : source.getXRot();
        AiPlayer ai = manager.spawn(level0, name, position, yaw, pitch, kit);
        if (ai == null) {
            return replyKey(ctx, "aiplayer.cmd.spawnFail");
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
        return replyKey(ctx, "aiplayer.cmd.spawnOk", profile.name,
                Component.translatable("aiplayer.level." + profile.level().id));
    }

    /** 批量生成：实现体在 {@link AiPlayerManager#spawnManyNear}（GUI 的批量按钮共用同一套逻辑） */
    private static int spawnMany(CommandContext<CommandSourceStack> ctx, int count, int level) {
        Entity source = ctx.getSource().getEntity();
        float yaw = source == null ? 0.0F : source.getYRot();
        String ownerName = source instanceof Player owner ? owner.getScoreboardName() : null;
        int made = AiPlayerManager.get().spawnManyNear(ctx.getSource().getLevel(),
                ctx.getSource().getPosition(), yaw, count, ownerName, level);
        return made == 0 ? replyKey(ctx, "aiplayer.cmd.spawnFail")
                : replyKey(ctx, "aiplayer.cmd.spawnManyOk", made);
    }

    /**
     * LLM 连通性自检：发一个最小请求，把结果异步回显。
     * <p>
     * 这是发布后最省支持成本的一个入口：401（Key 错）、404（地址错）、429（限流）、
     * 超时、返回非 JSON，全都能一眼看出来，不必再让玩家反复换模型试。
     */
    private static int testLlm(CommandContext<CommandSourceStack> ctx, String name) {
        if (name != null) {
            return eachOf(ctx, name, ai -> runLlmTest(ctx, ai.profile()));
        }
        AiProfile profile = null;
        for (AiProfile p : AiPlayerConfig.get().profiles) {
            if (p != null) {
                profile = p;
                break;
            }
        }
        if (profile == null) {
            return replyKey(ctx, "aiplayer.cmd.noProfile");
        }
        return runLlmTest(ctx, profile);
    }

    private static int runLlmTest(CommandContext<CommandSourceStack> ctx, AiProfile profile) {
        AiPlayerConfig cfg = AiPlayerConfig.get();
        CommandSourceStack source = ctx.getSource();
        long start = System.currentTimeMillis();
        com.aiplayer.api.LlmClient.ask(profile,
                        "你是连接测试助手。只输出 JSON，不要解释。",
                        "只回复 {\"say\":\"ok\"}")
                .whenComplete((reply, error) -> source.getServer().execute(() -> {
                    long ms = System.currentTimeMillis() - start;
                    if (error != null) {
                        Throwable cause = error instanceof java.util.concurrent.CompletionException
                                && error.getCause() != null ? error.getCause() : error;
                        source.sendFailure(Component.translatable("aiplayer.cmd.testllmFail",
                                String.valueOf(cause.getMessage())));
                    } else {
                        source.sendSuccess(() -> Component.translatable("aiplayer.cmd.testllmOk",
                                String.valueOf(cfg.modelFor(profile)), String.valueOf(ms)), false);
                    }
                }));
        return 1;
    }

    /** 把 AI 传送到执行者身边；带 target 时传送到指定在线玩家身边 */
    private static int teleport(CommandContext<CommandSourceStack> ctx, String targetName) {
        ServerPlayer dest;
        if (targetName == null) {
            dest = ctx.getSource().getEntity() instanceof ServerPlayer self ? self : null;
            if (dest == null) {
                return replyKey(ctx, "aiplayer.cmd.playerOnly");
            }
        } else {
            dest = ctx.getSource().getServer().getPlayerList().getPlayerByName(targetName);
            if (dest == null) {
                return replyKey(ctx, "aiplayer.cmd.playerNotFound");
            }
        }
        ServerLevel level = (ServerLevel) dest.level();
        return each(ctx, ai -> {
            ai.teleportTo(level, dest.getX(), dest.getY(), dest.getZ(), java.util.Set.of(),
                    dest.getYRot(), dest.getXRot(), false);
            ai.setDeltaMovement(Vec3.ZERO);
            ai.resetFallDistance();
            // 和生成时一样：靠 ChunkMap 的轨迹视图把新位置同步给客户端
            level.getChunkSource().move(ai);
            return reply(ctx, Component.translatable("aiplayer.cmd.tpOk", ai.profile().name,
                    dest.getScoreboardName()));
        });
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, Component message) {
        ctx.getSource().sendSuccess(() -> Component.literal("[aiplayer] ").append(message), false);
        return 1;
    }

    /** 反馈一条可翻译文案（{@code [aiplayer]} 是品牌前缀，不翻译） */
    private static int replyKey(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        return reply(ctx, Component.translatable(key, args));
    }
}
