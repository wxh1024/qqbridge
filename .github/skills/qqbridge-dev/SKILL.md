---
name: qqbridge-dev
description: QQ聊天桥（Fabric 1.21.11 纯客户端模组，仓库根目录即工程根）的开发与发版手册。当需要修改或扩展该模组（游戏聊天同步、视野进入提醒、QQ 群 @机器人 入站、Markdown 出站、配置项）、排查 QQ 官方机器人协议问题（access_token、WS 网关、主动/被动消息、频控、群 OpenID）、或调整 Gradle/CI 构建与发布产物时使用。
agent_created: true
---

# QQ聊天桥 开发手册

## 用途

仓库**根目录自身就是工程根**（Fabric 1.21.11 **纯客户端**模组），把游戏内聊天与「玩家进入视野」提醒推送到 QQ 官方机器人所在的群，并把群内 @机器人 的消息转回游戏内聊天。本 skill 用于在该工程上做增量开发、排障和发版，避免重新推导构建配置、1.21.11 映射名与 QQ 协议细节。

本 skill 随仓库一起版本化，位于 `.github/skills/qqbridge-dev/`；`.github/workflows/build.yml` 是同仓库的 CI 定义。

## 项目边界（先确认再动手）

- **单可信客户端桥接**：`appId`/`clientSecret` 明文存在 `config/qq_bridge.json`，只跑在服主/房管自己的机器上。任何改动都不得要求把凭证分发给普通玩家，也不得引入服务端组件。
- **纯客户端**：`fabric.mod.json` 的 `environment` 固定为 `client`；不注册物品/方块/网络包，**不使用 Mixin**。
- **零第三方运行时依赖**：HTTP/WebSocket 用 JDK `java.net.http`，JSON 用游戏自带的 Gson。新增依赖前先想清楚能否用 JDK 内置能力实现。

## 代码地图

| 文件 | 职责 | 改它会波及 |
|---|---|---|
| `QqBridgeClient.java` | 入口，装配所有组件并注册 Fabric 事件 | 所有链路 |
| `config/QqBridgeConfig.java` | `config/qq_bridge.json` 读写、字段默认值、格式串 | README 配置表必须同步 |
| `bridge/ChatRelay.java` | 游戏→QQ 出站：两道限速、失败分流（退避重试／降级纯文本／入队等 @）、被动回复优先 | 出站消息表现 |
| `bridge/ProximityAlert.java` | tick 级玩家集合 diff + 冷却去重 | 视野提醒频率 |
| `bridge/QqIngress.java` | QQ→游戏 入站：群绑定（**不受任何开关影响**）、`send`/`local` 两模式、`/l` 指令分流 | 游戏内可见性、出站能否拿到 groupOpenid |
| `bridge/PearlTug.java` | 拉珍珠状态机：扫描告示牌 → 选目标 → 寻路 → 转头 → 右键 → 提示 | 拉珍珠行为与文案 |
| `bridge/BaritoneNavigator.java` | 男中音 API 薄封装（`GoalNear` / `isPathing` / `cancelEverything`） | 自动寻路是否可用 |
| `qq/QqAuth.java` | `getAppAccessToken`，7200s 生命周期、到期前 60s 刷新 | 401 类问题 |
| `qq/QqApi.java` | 发群消息（`msg_type=0` 文本 / `msg_type=2` Markdown）、返回 `Result`（含平台错误码）、`msg_seq` 仅被动回复带、401 自愈重试 | 发送失败排查 |
| `qq/QqGateway.java` | `/gateway` + WebSocket：Hello/Identify/Heartbeat/Resume、指数退避重连 | 收不到事件 |
| `qq/QqGroupMessage.java` | `GROUP_AT_MESSAGE_CREATE` 事件解析、剥离 `<@!...>` 提及标记 | 入站内容 |
| `util/MarkdownUtil.java` | 动态值转义 + 引用块包装 | QQ 端排版 |
| `util/RateLimiter.java` | 单线程限速：最小间隔 + 每分钟滚动窗口配额 | 平台频控 |

数据流四条：

```
游戏聊天 / 系统消息 ──▶ ChatRelay ──▶ QqApi ──▶ QQ 群
玩家进入视野        ──▶ ProximityAlert ──▶ ChatRelay ──▶ QQ 群
QQ 群 @机器人       ──▶ QqGateway ──▶ QqIngress ──▶ 游戏聊天
QQ 群 @机器人 /l    ──▶ QqGateway ──▶ QqIngress ──▶ PearlTug ──▶ BaritoneNavigator ──▶ 方块交互
QQ 群 @机器人 msg_id ──▶ ChatRelay 被动兜底队列冲刷
```

## 硬性约定（违反会直接构建失败或产物不可用）

1. **映射用 Yarn**：`mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"`，版本号在 `gradle.properties` 的 `yarn_mappings`（当前 `1.21.11+build.6`，必须与 `minecraft_version` 对齐）。插件 id 仍是 `net.fabricmc.fabric-loom-remap`。代码里用的是 Yarn 名 —— `MinecraftClient` / `Text` / `PlayerEntity` / `getNetworkHandler().sendChatMessage(...)` / `inGameHud.getChatHud().addMessage(...)` / `getUuid()`。**不要**混入 Mojang 官方名（`Component`、`Minecraft`、`sendChat`、`gui.getChat()`、`getUUID()`），Loom 的 remap 阶段会直接报找不到符号。新增代码拿不准签名时查 `references/minecraft-1.21.11-api.md`。
2. **版本矩阵**：Loom `1.18.4` + Gradle wrapper `9.7.x`（Loom 1.18.4 的插件 API 要求）+ **JDK 25 运行 Gradle**，但 mod 产物编译为 **Java 21**（`options.release = 21`）。CI 与 README 都要保持这套说法。
3. **UTF-8 编码必须保留**：`tasks.withType(JavaCompile) { options.encoding = "UTF-8" }`。源码含大量中文字符串与注释，删掉这行在 GBK 平台会乱码或编译失败。
4. **中文产物名靠 `settings.gradle` 固定**：`rootProject.name = "QQ\u804A\u5929\u6865"`（\uXXXX 转义自带中文，文件保持纯 ASCII）。**不要删这行** —— 目录名可能是任意检出路径，删了 jar 就会变成目录名；GitHub 仓库名不支持中文（用 `qqbridge`）。`gradle.properties` 的 `mod_name` 同理用 `\uXXXX`。
5. **不在本地跑 gradle**：改完直接 commit + push，让 GitHub Actions 构建，再读 CI 结果。本地只做零成本检查（读文件、grep、`git status`）。
6. **男中音的用法**：`libs/baritone-1.21.11-2026.jar` 入库，`build.gradle` 里 `modCompileOnly files(...)`（Loom 会把 intermediary 命名的 mod jar 重映射成 Yarn 名用于编译），**不打包进产物**。`baritone.api` 未混淆，直接 import 用；但引用它的 `BaritoneNavigator` 只能在 `FabricLoader.isModLoaded("baritone")` 为真时被调用，否则会 `NoClassDefFoundError`。`fabric.mod.json` 里用 `suggests`（不是 `depends`），避免强制安装。
7. **交互必须转头**：`client.interactionManager.interactBlock(...)` 前要 `setYaw/setPitch/setHeadYaw` 并**显式发** `PlayerMoveC2SPacket.LookAndOnGround`，否则服务端用旧视线判定，会点空/被拒；同时要先 `BaritoneNavigator.stop()`，否则男中音的 look behavior 会把视角抢回去。
8. **面向用户的文案用中文**，日志前缀统一 `QQ聊天桥：`。

## 线程模型（最容易写错的地方）

- Fabric 事件（`ClientReceiveMessageEvents` / `ClientTickEvents` / `ClientLifecycleEvents`）在**客户端主线程**回调。
- `QqGateway` 的 WS 回调在 `qqbridge-ws-connector` 守护线程，`ChatRelay` 出站走 `qqbridge-outbound` 单线程。
- **任何触碰 `Minecraft` / 世界 / 聊天框的代码都必须包在 `mc.execute(...)` 里**（见 `QqIngress`）。反过来，网络请求不要放主线程。
- 出站统一经 `ChatRelay.push(...)`，不要在业务类里直接调 `QqApi` —— 否则绕过限速与被动兜底队列。
- 阻塞式网络调用（`http.send`、`closed.join()`）只允许出现在上述后台线程。

## 改动配方

### A. 新增一种「游戏 → QQ」事件（例：玩家死亡提醒）

1. 在 `QqBridgeConfig` 加开关字段（默认 `true`）与可选格式串（`{name}` / `{message}` 占位）。
2. 在 `ChatRelay` 加一个 `pushXxx(...)`，内部调 `format(config.xxxFormat, name, message)` 再 `push(...)`（自动获得限速 + 兜底）。
3. 在 `QqBridgeClient` 注册对应 Fabric 事件，**回调里只做取值**，重活交给 `ChatRelay` 的后台线程。
4. README 的功能表 / 配置表各加一行。

### B. 新增配置项（四处都要改）

`QqBridgeConfig` 字段 + 默认值 → `ChatRelay`/`QqIngress` 读取点 → README 配置表 → 视情况写进格式串说明。改完提醒用户：**改配置需重启游戏生效**（没有热重载）。

### C. 新增 QQ 事件类型（例：单聊 `C2C_MESSAGE_CREATE`）

1. `QqGateway.INTENT_GROUP_AND_C2C` 已含 `1 << 25`（群 + C2C），一般无需改 intents。
2. `handleDispatch` 的 `switch (t)` 加分支 → 新解析类（仿 `QqGroupMessage`）→ 交给新的 ingress 方法。
3. 注意被动回复要用事件里的 `id` 作为 `msg_id`，并决定是否接进 `ChatRelay.onInboundMsgId` 的兜底冲刷。

### D. 调整 QQ 端呈现

- 排版只由格式串 + `MarkdownUtil` 决定：模板里的 Markdown 语法原样保留，只有 `{name}`/`{message}` 插入值经 `escape()` 转义。
- `wrap()` 已在外层加 `> ` 引用前缀，**格式串不要再自带 `> `**，否则会出现双重 `>>`。
- 关 `markdownToQq` 即退回 `msg_type=0` 纯文本，此时格式串里的 Markdown 语法会原样显示。

### E. 调整拉珍珠（`PearlTug`）

状态机 `IDLE → SCAN → PATH → AIM → 交互 → IDLE`，全部在 `ClientTickEvents` 回调里按 tick 推进：

- **SCAN**：`BlockPos.iterate(中心±半径)` 遍历，`world.getBlockEntity(pos) instanceof SignBlockEntity` 才算候选；`getFrontText()/getBackText()` 的 `getMessages(false)` 逐行 `plain().equals(输入)`；命中后 `findTrigger()` 在 `y-1..y-depth` 找 `TrapdoorBlock`/`ButtonBlock`；`nearer()` 决定多目标取谁。
- **PATH**：`BaritoneNavigator.goNear(trigger, pearlGoalRadius)`；每 tick 用 `getEyePos().distanceTo(方块中心) <= min(pearlInteractRange, getBlockInteractionRange())` 判定到达；`isPathing()` 连续 `PATH_GRACE_TICKS` 为假 → 重发一次 → 仍不行判失败。
- **AIM**：`aimAt()` 改 yaw/pitch/headYaw + 发 `LookAndOnGround`，稳 `AIM_TICKS` 后 `interactBlock`；成功后 `swingHand`。
- 提示统一走 `feedback(client, 文案, 是否推 QQ)`：游戏内 `[拉珍珠] …` + `relay.push(pearlToQqFormat)`。**文案改动要同步 README「失败/进度提示」表**。

### F. 发版

1. 同步升 `gradle.properties` 的 `version` 与 `mod_version`（`fabric.mod.json` 用 `${version}` 占位自动取值）。
2. commit + push，等 CI「构建」工作流通过（`gh run watch <id> --exit-status`）。
3. 取产物并上传附件：
   ```
   gh run download <run-id> -D /tmp/art            # → /tmp/art/QQ聊天桥/QQ聊天桥-<version>.jar
   cp "/tmp/art/QQ聊天桥/QQ聊天桥-<version>.jar" /tmp/art/qqbridge-v<version>.jar
   gh release create v<version> /tmp/art/qqbridge-v<version>.jar --title ... --notes-file <utf8.md>
   ```
   **release 附件名必须 ASCII**（`qqbridge-v<version>.jar`）：GitHub 会清洗非 ASCII 资产名，
   `QQ聊天桥-1.3.0.jar` 会变成 `QQ.-1.3.0.jar`。改名用 `gh api -X PATCH repos/<owner>/<repo>/releases/assets/<id> -f name=...`。
   中文名只保留在工程内（`settings.gradle` 的 `rootProject.name`、CI Artifact 名）。
4. 说明/标题含中文时用 `--notes-file <文件>` 传（写 UTF-8 文件），别直接塞进命令行参数——Windows 下 bash → gh.exe 会丢字。
5. 产物获取：仓库 → Actions → 最新「构建」→ Artifacts → `QQ聊天桥`（上传 `build/libs/*.jar`，已排除 sources jar）。

## 排障对照表

| 现象 | 优先怀疑 |
|---|---|
| 日志 `获取QQ access_token失败` / HTTP 401 | `appId`/`clientSecret` 错，或 `sandbox` 与平台环境不匹配；`QqAuth` 会自动重取一次 |
| 日志 `QQ发群消息失败` code=40034105 | 群侧没开「机器人主动在群聊内发言」（QQ 里点机器人头像 → 机器人设置页）。这是**平台侧开关，代码改不动**：插件已把提示弹到游戏内聊天框，并每 5 分钟探测一次（`ChatRelay#probeProactive`），开关打开后自动补发 |
| 日志 `QQ发群消息失败` code=40034100 | 主动消息超频控（每群 20 条/分、1000 条/天）；先看 `sendIntervalMs` 与 `maxMessagesPerMinute`，`60000/间隔` 就是每分钟条数 |
| 完全收不到群消息 | 机器人没拉进群 / 群聊能力没开 / 消息没 @机器人（默认只订阅 @ 消息）；先确认日志有 `READY` |
| 群绑定错乱 | `groupOpenid` 留空时**首条** @ 消息的群会被自动绑定并写回配置；换群要手动改配置 |
| 群里的消息又被刷回 QQ 一遍 | `qqToGameMode = send` 的固有效果（已无回显抑制）：本条被服务器回显 → 聊天钩子再推 QQ。改用 `local`，或关 `syncChatToQq` |
| QQ 端显示 `>>` | 格式串自带 `>` 前缀 |
| 消息被截断 | `ChatRelay.MAX_CONTENT = 480`，超出加 `…` |
| 消息重复/重复发送 | 被动回复同一 `msg_id` 最多 5 次，且 `msg_seq` 必须变化（`QqApi` 已用单调序号） |

## 自检清单（不构建也能做）

- [ ] 新增/改动的 Yarn 映射名在 `references/minecraft-1.21.11-api.md` 里有据可查
- [ ] 所有触碰 `Minecraft` 的代码在 `mc.execute(...)` 内
- [ ] 新配置项在 `QqBridgeConfig` 与 README 配置表双向一致
- [ ] `build.gradle` 的 UTF-8 与 `release = 21` 未被改动
- [ ] 出站仍走 `ChatRelay.push`，未绕过限速
- [ ] push 后 CI「构建」工作流绿色

## 参考

- `references/qqbot-protocol.md` —— QQ 官方机器人接口速查（token、发消息、WS opcode、主动/被动约束、频控）
- `references/minecraft-1.21.11-api.md` —— 本模组用到的 1.21.11 Yarn 映射名与 Fabric 事件签名
