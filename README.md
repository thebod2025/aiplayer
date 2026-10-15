# AI Player

![Minecraft](https://img.shields.io/badge/Minecraft-26.2-green)
![Fabric](https://img.shields.io/badge/Fabric%20Loader-0.19.5-blue)
![Fabric API](https://img.shields.io/badge/Fabric%20API-0.153.0%2B26.2-informational)
![Java](https://img.shields.io/badge/Java-25-red)
![License](https://img.shields.io/badge/License-GPLv3-blue)
![Version](https://img.shields.io/badge/Version-1.1.3-orange)

在 Minecraft 服务器里生成会聊天、会打架、会自己活下去的 AI 玩家。

> 当前版本：**1.1.3**
> 适用：Minecraft **26.2** + Fabric Loader **0.19.5+** + Fabric API **0.153.0+26.2**
> Java：**25** ｜ 协议：**GNU GPL v3.0（GPL-3.0-only）**

---

## 目录

- [简介](#简介)
- [功能](#功能)
- [环境要求](#环境要求)
- [安装](#安装)
- [使用](#使用)
- [指令](#指令)
- [配置](#配置)
- [AI 行为](#ai-行为)
- [性能建议](#性能建议)
- [常见问题](#常见问题)
- [兼容性](#兼容性)
- [开发](#开发)
- [贡献](#贡献)
- [协议](#协议)

---

## 简介

AI Player 是一个 **Fabric 服务端模组**，让你在服务器里动态生成 AI 玩家。

这些 AI 是标准 `ServerPlayer` 的子类（不是假人实体），由服务端每 tick 驱动，因此：

- 单人存档、局域网、专用服务器都能用
- **客户端不装模组也能进服**（装了会多一个配置界面）
- AI 会像真玩家一样进服、聊天、战斗、挖矿、搭路、死亡、复活
- **完全不使用 Mixin**（纯事件 + 网络包驱动），兼容性较好

AI 的行为由 LLM（大语言模型）驱动，支持自定义 API 地址 / 密钥 / 模型（全局或按 AI 单独设）。
LLM 不可用或超时时，AI 会自动退回**本地规则兜底**，不会站着发呆。

---

## 功能

### 核心功能

- ✅ 动态生成 / 移除 AI 玩家，支持 4 档强度（菜鸟 / 普通 / 高手 / 挂级）
- ✅ 服务端与客户端都能管理：指令 `/aiplayer`，客户端 GUI 配置
- ✅ AI 死亡后按 **Kit 装备包**自动满配复活
- ✅ 组队系统：同队不互打、协战、分工、共享物资、队形跟随
- ✅ 主人系统：听主人的话、绝不攻击主人、主人被打时帮反击
- ✅ 中文聊天：主动打招呼（同一玩家只打一次）、接玩家的话、说谢谢
- ✅ 公屏对话可触发本地指令（如「挖矿」「搭桥」「跟过来」）
- ✅ 支持 TACZ / 机械动力等模组的兼容探测

### AI 能力

| 分类 | 能力 |
|---|---|
| 移动 | 寻路（A* 分片计算）、游泳、爬梯、垫脚上台阶、跟随玩家 |
| 搭路 | 5 种模式：关闭 / 蹲起搭 / 神桥 / Telly / SpeedTelly，可配速度、方向、方块种类 |
| 生存 | 进食（打断可续吃）、自动逃生（落地水 / 撤退 / 举盾）、投掷物逃生 |
| 采集 | 挖方块（自动选最佳工具）、放置方块、批量建造、烹饪（熔炉） |
| 交互 | 翻箱子等容器、与村民交易、存物进箱子 |
| 装备 | 自动识别并切换手持物品、自动装备盔甲、副手图腾保护 |
| 战斗 | 近战 PvP、远程（弓 / 弩 / 三叉戟）、水晶 PvP、锚 PvP、风弹重锤、鞘翅长矛 |
| 战术 | 战斗主动放方块（被近身堵墙 / 被缠住抢高点）、绕背、盾挡时机、跳劈节奏 |
| 被动技能 | 空闲钓鱼、收割并补种庄稼、用诱饵驯服野狼 / 猫 |
| 组队 | 同队不互打、32 格协战、输出位 / 拉扯位分工、共享食物与武器、环形队形 |
| 社交 | 中文聊天、打招呼、道谢、被指定时回应 |
| 世界认知 | 查询种子、定位村庄 / 要塞 / 沉船等结构（供 AI 自己前往） |

---

## 环境要求

| 项目 | 版本 |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.5+ |
| Fabric API | 0.153.0+26.2（必须） |
| Java | 25 |

> 26.1 起 Minecraft 官方不再混淆，因此模组直接使用 Mojang 官方类名，不写 mappings。

可选：任意兼容 Fabric 的客户端模组（Lithium 等性能模组均可）。

---

## 安装

### 服务端

1. 安装 Fabric Loader（Minecraft 26.2）
2. 把 `aiplayer-<日期>-<版本>.jar` 放进 `mods/`
3. 把 `fabric-api` 也放进 `mods/`
4. 启动服务器

### 客户端（可选）

客户端**不装** aiplayer 也能进服。
装了之后会多一个配置界面（`AI Config`），用于编辑并同步服务端 AI 配置。

---

## 使用

### 生成 AI

```text
/aiplayer spawn <名字> [强度]
```

示例：

```text
/aiplayer spawn Dream
/aiplayer spawn Itzrealme 3
```

- 强度取值 `1~4`：`1 菜鸟` / `2 普通`（默认） / `3 高手` / `4 挂级`
- 由玩家执行时，执行者会被自动设为主人；控制台执行则沿用默认主人 `The_bodboy_`

---

## 指令

所有 `/aiplayer` 指令需要 **OP（GM 权限）**。

### 管理

| 指令 | 说明 |
|---|---|
| `/aiplayer spawn <名字> [强度]` | 生成 AI 玩家（可带强度 1~4） |
| `/aiplayer remove <名字>` | 移除指定 AI |
| `/aiplayer removeall` | 移除全部 AI |
| `/aiplayer list` | 列出所有 AI 及其状态 |
| `/aiplayer info <名字>` | 查看某个 AI 的状态 |
| `/aiplayer reload` | 重载配置文件 |

### 控制单个 AI

| 指令 | 说明 |
|---|---|
| `/aiplayer level <名字> <强度>` | 修改强度（1~4） |
| `/aiplayer goto <名字> <x> <y> <z>` | 让 AI 走去指定坐标 |
| `/aiplayer mine <名字> <x> <y> <z>` | 让 AI 去挖指定坐标 |
| `/aiplayer place <名字> <x> <y> <z>` | 让 AI 去放置方块 |
| `/aiplayer attack <名字> <目标玩家>` | 让 AI 攻击指定玩家 |
| `/aiplayer say <名字> <文本>` | 让 AI 说话 |
| `/aiplayer cmd <名字> <指令>` | 让 AI 执行一条游戏指令（有安全拦截） |
| `/aiplayer follow <名字> <玩家名>` | 设置跟随目标（`-` 取消跟随） |
| `/aiplayer bridge <名字> <0~4>` | 设置搭路方式 |
| `/aiplayer stop <名字>` | 停止当前任务 |
| `/aiplayer pvp <名字> <true/false>` | 始终 PvP 开关（开启后自动打附近 AI / 玩家 / 怪物，主人除外） |
| `/aiplayer team <名字> <队伍名>` | 设置队伍（`-` 或 `clear` 退队） |
| `/aiplayer feature <名字> <开关> <true/false>` | 设置单个功能开关（见配置章节） |

### 装备与 API 配置

| 指令 | 说明 |
|---|---|
| `/aiplayer kit <名字>` | 打开该 AI 的 41 格装备包 GUI（含盔甲 / 副手 / 附魔 NBT） |
| `/aiplayer defaultkit` | 打开全局默认装备包 GUI（AI 没配自己的 Kit 时用它） |
| `/aiplayer apiurl global <地址>` | 设置全局 API 地址 |
| `/aiplayer apiurl <名字> <地址>` | 设置某个 AI 的 API 地址（清空则回退全局） |
| `/aiplayer apikey global <密钥>` | 设置全局 API Key |
| `/aiplayer apikey <名字> <密钥>` | 设置某个 AI 的 API Key |
| `/aiplayer model global <模型>` | 设置全局模型 |
| `/aiplayer model <名字> <模型>` | 设置某个 AI 的模型 |

---

## 配置

配置文件：`config/aiplayer.json`

### 全局字段（`AiPlayerConfig`）

| 字段 | 说明 | 默认值 |
|---|---|---|
| `apiBaseUrl` | OpenAI 兼容接口地址（不含 `/chat/completions`） | `https://api.openai.com/v1` |
| `apiKey` | 全局 LLM API 密钥 | `""` |
| `model` | 全局使用的模型 | `gpt-4o-mini` |
| `defaultKitNbt` | 全局默认装备包（41 格 JSON 快照） | `""` |
| `temperature` | 采样温度 | `0.7` |
| `maxTokens` | 单次回复最大 token | `400` |
| `llmEnabled` | 是否允许 AI 调用大模型决策 | `true` |
| `llmIntervalMs` | 两次 LLM 决策的最小间隔（毫秒，防 429） | `12000` |
| `httpTimeoutMs` | HTTP 超时（毫秒） | `15000` |
| `jsonMode` | 请求带 `response_format=json_object`（被 400 会自动关闭重试） | `true` |
| `defaultLevelId` | 新 AI 的默认强度 | `2` |
| `profiles` | 每个 AI 的独立配置列表 | 见下 |

### 单个 AI 字段（`AiProfile`）

| 字段 | 说明 | 默认值 |
|---|---|---|
| `name` | AI 名字 | `AI` |
| `skinName` | 使用哪个正版玩家的皮肤（留空 = 默认皮肤） | `""` |
| `levelId` | 强度 1~4 | `2` |
| `ownerName` | 主人名（留空 = `The_bodboy_`） | `""` |
| `team` | 队伍名（留空 = 无队伍） | `""` |
| `follow` | 跟随的玩家名（留空 = 不跟随） | `""` |
| `apiKey` / `apiBaseUrl` / `model` | 单独指定，留空则用全局 | `""` |
| `customPrompt` | 自定义系统提示词，留空用默认 | `""` |
| `kitItems` | 简易装备文本（如 `diamond_sword, cooked_beef x32`） | `""` |
| `kitNbt` | 完整背包快照 JSON（41 格，优先于 `kitItems`） | `""` |
| `metPlayers` | 已打过招呼的玩家名单（防重复问候） | `[]` |
| `giveKit` | 生成时是否发放装备 | `true` |
| `chatCooldownMs` | 自主说话间隔（毫秒） | `15000` |
| `commandCooldownMs` | 自主执行指令间隔（毫秒） | `8000` |
| `autoChat` | 允许自主聊天（不依赖 API） | `true` |
| `autoCommand` | 允许自主执行指令 | `false` |
| `bridgeMode` | 搭路方式 0~4 | `1` |
| `bridgeSpeed` | 每秒放置方块数 | `4.0` |
| `bridgeDirection` | 搭路方向（forward / backward / left / right / diagonal） | `backward` |
| `bridgeBlock` | 搭路方块（见 BlockPalette） | `wool` |
| `bridgeAttack` | 搭路时是否顺手攻击附近敌人 | `true` |

### 功能开关（`feature` / GUI 复选框）

| 开关 | 说明 | 默认 |
|---|---|---|
| `mine` | 挖方块 | ✅ |
| `place` | 放置方块 | ✅ |
| `combat` | 战斗 | ✅ |
| `chat` | 聊天 | ✅ |
| `command` | 执行指令 | ✅ |
| `eat` | 进食 | ✅ |
| `equip` | 自动装备 | ✅ |
| `hunt` | 狩猎 | ✅ |
| `bridge` | 搭路 | ✅ |
| `autoBridge` | 脚下没方块时自动搭路 | ✅ |
| `crystalPvp` | 水晶 PvP | ✅ |
| `anchorPvp` | 锚 PvP | ✅ |
| `windMace` | 风弹重锤 | ✅ |
| `elytraSpear` | 鞘翅长矛 | ✅ |
| `taunt` | 击败其他 AI 后嘲讽（30 秒冷却） | ✅ |
| `ranged` | 远程武器 | ✅ |
| `escape` | 危险时自动逃生 | ✅ |
| `throwables` | 投掷物逃生（末影珍珠等） | ✅ |
| `fishing` | 空闲钓鱼 | ✅ |
| `farm` | 收割并补种庄稼 | ✅ |
| `tame` | 驯服野狼 / 猫 | ✅ |
| `alwaysPvp` | 始终 PvP（主动攻击，主人除外） | ❌ |

### 配置同步（联机注意）

- 客户端 GUI 读写的是**本地**配置；联机时通过 `ProfileSyncPayload` 网络包（需 OP）把改动同步到服务端，再由服务端校验权限后写入服务端的 `config/aiplayer.json`。
- 服务端也可以直接用 `/aiplayer apiurl|apikey|model` 等指令修改并落盘。

### 配置示例

```json
{
  "apiBaseUrl": "https://api.deepseek.com/v1",
  "apiKey": "sk-xxxxxxxx",
  "model": "deepseek-chat",
  "temperature": 0.7,
  "maxTokens": 400,
  "llmEnabled": true,
  "llmIntervalMs": 12000,
  "jsonMode": true,
  "defaultLevelId": 2,
  "profiles": [
    {
      "name": "Dream",
      "levelId": 3,
      "ownerName": "The_bodboy_",
      "team": "red",
      "bridgeMode": 2,
      "fishing": true,
      "farm": true,
      "tame": true
    }
  ]
}
```

---

## AI 行为

### 聊天

- AI 说**简体中文**；`reason` 只是它自己的备注，不会刷到公屏
- 玩家公屏说话 → 附近 AI 能「听见」→ 被点名或被呼唤时接话
- 只对**第一次见面**的玩家主动打招呼（名单持久化到 `metPlayers`，重启也认得）
- 玩家丢东西给它 → 会道谢（30 秒冷却）

### 战斗

- 默认**不主动攻击玩家**：只有被攻击（`getLastHurtByMob`）才还手，打怪物不受限制
- **主人永远不会被攻击**；主人被打时 AI 会帮忙反击
- 近战 PvP：绕背、挥击瞬间举盾、暴击跳劈节奏、W-tap、走位、按强度控制冷却与失误
- 远程：拉弓前同时检测目标**眼睛与躯干**两条视线，被挡住就不射，并限制最大射程
- 进阶玩法：水晶 PvP（仅对玩家）、锚 PvP（主世界、萤石充能后引爆）、风弹上天 + 重锤砸地、鞘翅俯冲 + 三叉戟
- 战斗放方块战术：被近身（<6 格）在敌人方向放 2 格墙堵路；被缠住（<4.5 格）起跳垫方块抢高点（带冷却与概率门槛）
- 击败其他 AI（最后一击为自己）会嘲讽一句，30 秒冷却，可在 GUI / `feature` 关闭

### 自主生存

- 生存优先：进食、挖矿、采集、合成、烹饪、建造、探索、变强
- 又饿又找不到吃的 → 去找附近箱子翻物资；找不到 → 随机走动找食物，不站桩
- 进食被打断会自动续吃，只在吃饱 / 手上没食物 / 超时才停
- 空闲时：钓鱼、收割并补种庄稼、用诱饵驯服野狼 / 猫、把多余物资存进箱子

### 世界交互

- 翻箱子等容器（双箱走 `ChestMenu`，其它方块走 `BlockEntity instanceof Container`）
- 与村民交易（打开 `MerchantMenu` → 放付款 → 成交，最多 8 笔）
- 内部工具：查种子、定位村庄 / 要塞 / 废弃传送门 / 沉船 / 海底废墟 / 矿井 / 试炼密室 / 埋藏的宝藏，拿到坐标后自己走过去

### 组队

`/aiplayer team <AI名> <队伍名>`

- 同队 AI **永互不攻击**
- 32 格内自动协战：队友被打或开打时赶去支援
- 分工：按名字排序，偶数位偏输出（直冲近战），奇数位偏拉扯（保持 5~8 格放风筝，有弓就射，贴脸才挥刀）
- 共享：队友饿了（饥饿 < 17 且自己有余粮）或空手时，走过去当面丢食物 / 武器（30 秒 / 人冷却）
- 队形：无任务无敌人时，非队长成员守在队长身边按序号均分的环形位

### 移动与搭路

- A* 寻路：直接剪枝岩浆 / 火 / 仙人掌 / 浆果丛 / 细雪 / 蜘蛛网等危险地形，增大下落代价防跳崖抄近路
- 寻路**分片计算**：单 tick 只算约 2ms 预算，算不完下个 tick 接着算，多 AI 同屏不再阻塞主线程
- 搭路：仅在连续多格无落脚点时触发，Java 版倒退姿势；前方有岸自动停止；陆地误搭已修复（未加载区块不再误判为空）

### LLM 决策

- 提示词结构化（【首要目标】/【不要 PvP】/【重要】中文说话），并把附近容器、村民、上次工具结果喂给模型
- 模型可通过字段指挥 AI：移动、跳跃、潜行、疾跑、看向、攻击、开箱、交易、查种子、定位结构等
- `attack` 动作有 `allowedToAttack` 机制兜底，避免模型自作主张挑人 PvP
- 请求节流：两次决策间隔下限 12 秒（`max(8000, llmIntervalMs)`），避免打爆账户 RPM
- LLM 掉线 / 超时 → 自动切本地规则兜底，不原地发呆

---

## 性能建议

AI 开销主要来自：AI 数量、LLM 调用频率、寻路与战斗计算。

| 项 | 建议 |
|---|---|
| 同时在线 AI 数 | 视机器而定，量力而行 |
| `llmIntervalMs` | `12000` 起，机器吃力就调大 |
| `view-distance` | 8 左右 |
| `simulation-distance` | 8 左右 |
| 内存 | `-Xmx4G` 或更高 |

优化建议：

- 用 Lithium 等性能模组优化服务端
- 限制同时活跃的 AI 数量，用 `/aiplayer removeall` 清理
- 关掉用不到的 `feature`（如 `anchorPvp` / `elytraSpear`）
- A* 已是分片计算；若仍然吃紧，可进一步调低 `llmIntervalMs` 的实际调用频率

---

## 常见问题

**Q：客户端需要装模组吗？**
A：不需要。AI Player 是服务端模组，客户端原版就能进服；装了只是多一个配置界面。

**Q：AI 会打主人吗？**
A：不会。主人（默认 `The_bodboy_`，或生成它的那个玩家）有专门保护：不听指令打主人、不主动攻击主人，主人被打时还会帮忙反击。

**Q：AI 会不会主动打玩家？**
A：默认不会。只有被玩家先攻击才还手；打怪物不受限制。想让 AI 主动 PvP，用 `/aiplayer pvp <名字> true`。

**Q：支持哪些 LLM？**
A：任何**兼容 OpenAI 接口格式**的服务，例如 OpenAI、DeepSeek、智谱 GLM、Ollama 本地模型等。地址写到 `/v1` 为止（不含 `/chat/completions`）。

**Q：请求报 HTTP 429 怎么办？**
A：这是 RPM 超限。把 `llmIntervalMs` 调大（默认已是 12 秒），并确认没有同时跑太多 AI。

**Q：`jsonMode` 报 HTTP 400 怎么办？**
A：少数自建网关 / Ollama 兼容层不支持 `response_format`。代码会自动关掉该开关并重试一次，无需手动改。

**Q：为什么 AI 看起来在发呆？**
A：先确认 API Key 有效。API 不通时 AI 会走本地规则兜底，此时行为比正常时保守，属于预期。

**Q：AI 会卡服务器吗？**
A：AI 越多、LLM 调用越频繁，开销越大。按上面的性能建议调整并限制 AI 数量。

**Q：控制台中文乱码？**
A：Windows 控制台编码问题，不影响游戏内聊天。可在启动脚本加 `chcp 65001` 与 `-Dfile.encoding=UTF-8`。

---

## 兼容性

| 模组 | 兼容性 |
|---|---|
| Fabric API | ✅ 必须 |
| Lithium 等性能模组 | ✅ 兼容 |
| TACZ（枪械） | ✅ 探测兼容 |
| 机械动力（Create） | ✅ 探测兼容 |
| 其他 Fabric 模组 | ✅ 一般兼容 |

因为**不使用 Mixin**，兼容性较好。

---

## 开发

### 构建

```bash
./gradlew build
```

Windows 上也可以直接双击项目根目录的 `build.bat`（脚本内已设定 `JAVA_HOME` 与 `GRADLE_USER_HOME`）。

产物：

```text
build/libs/aiplayer-<日期>-<版本>.jar
```

> 版本号在 `gradle.properties` 的 `version` 里；jar 文件名会自动带上构建日期，例如 `aiplayer-20261013-1.1.1.jar`。

### 技术栈

- Minecraft 26.2（官方不再混淆，直接用 Mojang 类名）
- Fabric Loader 0.19.5
- Fabric API 0.153.0+26.2
- Java 25
- Gradle 9.7 + Fabric Loom 1.18.2
- 无 Mixin

### 项目结构

```text
src/main/java/com/aiplayer/
├── AiPlayerMod.java              # 模组入口
├── ai/
│   ├── AiBrain.java              # AI 主脑（tick 调度 / LLM / 行为树）
│   ├── AiInput.java  AiLevel.java  AiState.java
│   ├── bridge/                   # 搭路（BridgeController / BridgeMode）
│   ├── chat/                     # 聊天与指令（ChatController / ChatListener）
│   ├── combat/                   # 战斗（近战 / 远程 / 水晶 / 锚 / 风弹重锤 / 鞘翅长矛 / 方块战术）
│   ├── interact/                 # 容器与村民交互
│   ├── inv/                      # 装备管理（EquipmentManager）
│   ├── path/                     # A* 寻路（AStarPathFinder 分片 + PathExecutor）
│   └── survival/                 # 生存（进食 / 逃生 / 被动技能：钓鱼 / 种田 / 驯服）
├── api/LlmClient.java            # LLM HTTP 客户端
├── command/AiPlayerCommand.java  # /aiplayer 指令族
├── compat/                       # 模组兼容探测（TACZ / 机械动力）
├── config/                       # 配置（AiPlayerConfig / AiProfile）
├── entity/                       # AI 实体与管理（AiPlayer / AiPlayerManager / AiNetConnection）
├── gui/KitChestGui.java          # 41 格装备包 GUI
├── net/ProfileSyncPayload.java   # 客户端 → 服务端配置同步包
└── util/                         # 工具（挖方块 / 方块调色板 / 背包 / 放置 / 皮肤抓取）

src/client/java/com/aiplayer/client/
├── AiPlayerClient.java
└── gui/                          # AiConfigScreen / UiTheme / FlatButton / ToggleChip
```

---

## 贡献

欢迎提交 Issue 和 Pull Request。

1. Fork 本仓库
2. 新建分支：`git checkout -b feature/你的功能`
3. 提交改动：`git commit -m "Add 你的功能"`
4. 推送分支：`git push origin feature/你的功能`
5. 发起 Pull Request

报告问题时请附上：崩溃日志、复现步骤、环境信息（MC / Fabric / Java 版本、AI 数量与配置）。

---

## 协议

本项目采用 **GNU 通用公共许可证第 3 版**（SPDX：`GPL-3.0-only`）。

什么意思，一句话总结：

- ✅ 你可以自由使用、复制、修改、分发本模组，**收钱也可以**
- ✅ 你可以把它放进任何整合包（包括收费整合包）
- ⚠️ 但只要你**分发**（或改了之后再分发）本模组，就必须：
  - 保留协议与版权声明
  - 一并提供**完整可编译的源代码**，且同样以 GPL-3.0 授权
  - 衍生作品**不得闭源**
- 🚫 所以任何人都无法把它改成闭源版本、独占拿去卖

也就是说：GPL 不禁止别人收钱，但**禁止别人把你的代码变成闭源私有财产**——这正是你想要的保护。

协议全文见项目根目录的 [`LICENSE`](LICENSE)，或 <https://www.gnu.org/licenses/gpl-3.0.html>。

```text
AI Player - AI 控制的 Minecraft 玩家实体
Copyright (C) 2026 AIplayer

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 3 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

### 致谢

- Fabric
- Minecraft
- 所有测试与反馈的玩家
