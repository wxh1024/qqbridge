# QQ聊天桥

Minecraft Java 版 **Fabric 1.21.11 客户端模组**——把游戏内聊天与「玩家进入视野」提醒同步到 **QQ 官方机器人** 所在的群聊，并把群内 @官方机器人 的消息转发为游戏内聊天消息。

> ⚠️ 本模组是**单可信客户端**桥接方案：QQ 机器人的 `AppSecret` 存在本机配置里，请只在服主 / 房管自己的机器上运行，**不要分发给普通玩家**。

## 功能

| # | 方向 | 说明 |
|---|---|---|
| 1 | 游戏 → QQ | 监听游戏内聊天框消息（玩家聊天 + 系统消息，各有开关），格式化后发到绑定的 QQ 群 |
| 2 | QQ → 游戏 | 群内 **@官方机器人** 发消息后，内容转发为游戏内聊天消息（两种落地模式，见下） |
| 3 | 视野提醒 | 玩家进入视野距离（客户端追踪范围）时，把进入者名称推送到 QQ；同一玩家带冷却防刷屏 |

**QQ → 游戏 的两种模式**（配置项 `qqToGameMode`）：

- `send`（默认）：作为你本人的聊天消息发给服务器，**全服可见**（内容带 `[QQ]` 前缀标记，回显不会再被转发回 QQ，无死循环）；
- `local`：仅在桥接端本机聊天框显示。

**出站消息用 Markdown 格式**（配置项 `markdownToQq`，默认 `true`）：游戏→QQ 的聊天/系统/视野提醒消息走 QQ 官方自定义 Markdown（`msg_type=2`），以引用块样式呈现，玩家名可加粗、格式串里可写 Markdown 语法；`{name}`/`{message}` 插入值自动转义，防止游戏内文字意外触发 Markdown 解析。关掉开关即退回纯文本。

## QQ 平台注意事项

- **主动推送需要群主授权**：QQ 官方机器人 2025-04 曾下线主动消息，2025-06 回归的版本要求群主在手机 QQ 的「机器人设置页」手动打开**推送开关**（群内默认关闭）。不开的话，需求 1/3 的主动推送会被拒。
- **被动回复兜底**：主动推送失败的消息会进本地队列，等下一条 @机器人 消息（5 分钟窗口、每条最多 5 次回复）自动补发到群里。
- 平台频控参考：约 20 条/分钟/群、1000 条/天/群；本模组出站默认限速 ≥1.2 秒/条，远低于上限。
- 收到群消息事件的前提：机器人具备群聊能力，且有人 **@机器人**（默认消息范围即「仅@消息」）。

## 获取构建产物

每个 push 都会跑 GitHub Actions（`构建` 工作流），产物保留 120 天：

**仓库 → Actions → 最新「构建」运行 → Artifacts → `QQ聊天桥`**（含 `QQ聊天桥-<版本>.jar`）

> 下载 Artifact 需要登录 GitHub 账号。

### 本地构建

需要 **JDK 25** 运行 Gradle（Loom 1.18+ 的要求；mod 产物本身仍是 Java 21，玩家用 Java 21 即可运行）：

```bash
./gradlew build
# 产物在 build/libs/QQ聊天桥-<版本>.jar
```

## 安装

1. 客户端安装 **Fabric Loader 0.19.5+** 与 **Fabric API**（1.21.11 对应版本）；
2. 把 `QQ聊天桥-<版本>.jar` 放进客户端 `mods/`；
3. 启动一次游戏，生成 `config/qq_bridge.json`，填入机器人凭证后重启。

## 配置（config/qq_bridge.json）

| 字段 | 默认 | 说明 |
|---|---|---|
| `appId` / `clientSecret` | 空 | QQ 开放平台机器人的 AppID / AppSecret（⚠️ 只放可信机器） |
| `sandbox` | `false` | `true` = 沙箱环境（只收沙箱配置的群事件） |
| `groupOpenid` | 空 | 绑定群；**留空则收到首条 @机器人 消息时自动绑定** |
| `syncChatToQq` | `true` | 玩家聊天 → QQ |
| `syncGameMessagesToQq` | `true` | 系统消息 → QQ |
| `forwardQqToGame` | `true` | QQ → 游戏 |
| `notifyPlayerEnter` | `true` | 视野进入提醒 |
| `qqToGameMode` | `"send"` | `send` 全服可见 / `local` 仅本机 |
| `playerEnterCooldownSeconds` | `60` | 同一玩家重复提醒冷却 |
| `sendIntervalMs` | `1200` | 出站最小间隔 |
| `maxPendingMessages` | `50` | 被动兜底队列上限 |
| `markdownToQq` | `true` | 出站到 QQ 用 Markdown 格式（`msg_type=2`，自定义 Markdown，群聊/单聊已开放）；`false` = 退回纯文本 |
| `chatToQqFormat` 等 | 见文件 | 文案格式，`{name}` / `{message}` 占位；`markdownToQq=true` 时可写 Markdown 语法（`**加粗**`、`## 标题`），插入值自动转义。⚠️ 若格式串本身以 `>` 开头，会再被外层引用块包裹（双重 `>>`），建议格式串不要自带 `>` 前缀 |
| `qqPrefixMarker` | `"[QQ]"` | 回环抑制标记 |

改完配置需重启游戏生效。

## 使用流程

1. 在 [QQ 开放平台](https://q.qq.com/) 创建机器人，拿到 AppID / AppSecret，开通群聊能力；
2. 把机器人拉进目标 QQ 群，**让群主在机器人设置页打开「推送开关」**（主动消息必需）；
3. 填好 `config/qq_bridge.json` 后进游戏；
4. 在群里 **@机器人** 随便说一句——模组会自动绑定该群并把消息转发进游戏；
5. 之后游戏内聊天、有人进入视野，都会自动同步到该群。

联调排查：看游戏日志（`logs/latest.log`）里 `QQ聊天桥` / `qqbridge` 关键字；401 = 凭证错，推送被拒 = 群主没开推送开关，收不到事件 = 机器人没 @ 消息权限或没拉进群。

## 技术栈

- Fabric 1.21.11 · Java 21（产物）· Loom `fabric-loom-remap` + Yarn 映射（`1.21.11+build.6`）
- 零第三方运行时依赖：QQ 协议走 JDK 内置 `java.net.http`（HTTP + WebSocket），JSON 用游戏自带 Gson
- 事件全部走 Fabric API（无 Mixin）：`ClientReceiveMessageEvents` / `ClientTickEvents` / `ClientLifecycleEvents`

## 协议

[AGPL-3.0](LICENSE) © 2026 QQ聊天桥

网络服务条款：本模组以网络服务形式向玩家提供桥接能力时（如你运营的服务器/群与 QQ 群互通），按 AGPL 第 5 条的精神，请在服务界面或说明中注明本项目来源与协议。
