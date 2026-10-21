# QQ 官方机器人接口速查

> 面向本模组的实现口径（2026-10 核实）。平台策略会变，涉及「能不能推」「能发多少」的判断以 QQ 开放平台官方文档与后台实测为准。

## 1. 取 access_token

```
POST https://bots.qq.com/app/getAppAccessToken
Content-Type: application/json

{"appId":"<AppID>","clientSecret":"<AppSecret>"}
```

响应：

```json
{"access_token":"xxxx","expires_in":"7200"}
```

要点：

- `expires_in` 官方示例给的是**字符串**，解析要宽容（本模组 `Long.parseLong(json.get("expires_in").getAsString())`）。
- 业务错误时返回 `code` + `message`（`code != 0` 视为失败）。
- 有效期 7200s；到期前 60s 内重新获取会得到新 token，旧 token 仍有约 60s 重叠可用，所以可以提前刷新而不掉线。
- 失败响应也可能没有 `access_token`，取字段前先判 `code`。

## 2. 鉴权方式

所有业务接口统一：

```
Authorization: QQBot <access_token>
```

不是 `Bearer`，也不带 `Bot ` 前缀。

## 3. API 域名

| 环境 | Base |
|---|---|
| 生产 | `https://api.sgroup.qq.com` |
| 沙箱 | `https://sandbox.api.sgroup.qq.com` |

沙箱只收沙箱环境下配置的群事件，填错 `sandbox` 会出现「能发不能收」或反之。

## 4. 发群消息

```
POST {apiBase}/v2/groups/{group_openid}/messages
```

### 4.1 纯文本（msg_type=0）

```json
{"msg_type":0,"content":"正文","msg_id":"<被动回复时必填>","msg_seq":1}
```

### 4.2 自定义 Markdown（msg_type=2）

```json
{"msg_type":2,"markdown":{"content":"> 正文"},"msg_id":"<可选>","msg_seq":2}
```

- **`markdown` 与 `content` 互斥**：填了 `markdown` 后 `content` 必须缺省/为空，反之亦然。本模组在 `QqApi` 里分两个方法各自只填一个字段。
- Markdown 语法子集：标题、加粗/斜体/下划线加粗/删除线、链接、图片、有序/无序列表、引用、水平分割线。群聊与单聊场景对普通机器人开放（2026-04-23 起），无需申请模板。
- 引用块内换行要在行末加**两个空格**，否则 QQ 端不断行。

### 4.3 主动推送 vs 被动回复

| | 主动推送 | 被动回复 |
|---|---|---|
| `msg_id` | 不带 | **必带**事件里的消息 `id` |
| 时效 | 随时 | 收到消息后 **5 分钟**内 |
| 次数 | 受平台频控 | 同一 `msg_id` 最多 **5 次** |
| 前置条件 | 群侧开启**「机器人主动在群聊内发言」**（QQ 里点机器人头像 → 机器人设置页；手机上可能显示为「允许主动发送」）。未开时一律 `40034105 主动消息失败, 无权限` | 无需额外开关 |

**被动回复优先**：收到 @消息 后的 5 分钟内本模组优先用该 `msg_id` 做被动回复（最多 5 条），既不吃主动消息配额，也不受群侧开关限制；窗口过期才退回主动消息。

`msg_seq` 用于同一 `msg_id` 下的多条被动回复去重，**必须递增**，否则会被判重丢弃。本模组用 `AtomicLong` 单调递增。

### 4.4 频控与常见错误码

- 主动消息单关系维度（**每个群**）：**20 条/分**、**1000 条/天**
- Bot 维度（跨群合计）：企业/个人认证 60 qpm，未认证 30 qpm

⚠️ **认证只抬高 Bot 维度，不改单群上限**：单关系维度的 20 条/分/群对认证和未认证一视同仁。本模组只发一个绑定群，所以瓶颈恒为 20 条/分；「我认证了 60 条/分」不等于单群能发 60 条。只有同时向多个群推送时，60 这个总量才有意义。
- 被动回复不吃上述配额，但同一 `msg_id` 5 分钟内最多 5 次

本模组用**两道闸**：`sendIntervalMs = 3200`（≈18 条/分）**加** `maxMessagesPerMinute = 18` 的滚动 60 秒窗口。
⚠️ 只卡间隔是算不对的：`sendIntervalMs = 1200` 等于 **50 条/分**，早就超了 20 条/分，会稳定撞 `40034100`。改任一个值前先把「60000 / 间隔」算一遍。

| 错误码 | 含义 | 本模组处理 |
|---|---|---|
| `40034100` | 主动消息超频控 | 退避 **60 秒**后重试，共 3 次 |
| `40034105` | 主动消息无权限（群侧开关没开） | 记中文指引 + 入被动队列，等 @ 补发 |
| `40034101` / `40054003` | 机器人不在群 | 入队 + 提示重新拉群 |
| `40054002` | 机器人被禁言 | 入队 + 提示解禁 |
| `40034011` / `40034124` / `304036` / `40034127` / `22006` | Markdown 被拒 | **自动剥标记降级纯文本重发** |
| `40054007` | 长度超限 | 出站统一按 480 字符截断 |
| HTTP 5xx / 网络异常 | 暂时性故障 | 指数退避 2s → 4s → 8s |

## 5. WebSocket 网关

### 5.1 建立连接

1. `GET {apiBase}/gateway` + `Authorization: QQBot <token>` → `{"url":"wss://api.sgroup.qq.com/websocket"}`（401 时刷新 token 重试一次）。
2. 连接 `url`，服务端下发 **Hello (op=10)**：`{"op":10,"d":{"heartbeat_interval":41250}}`。

### 5.2 鉴权与心跳

| op | 方向 | 说明 |
|---|---|---|
| 2 | 客户端→服务端 | **Identify**：`{"op":2,"d":{"token":"QQBot <token>","intents":33554432,"shard":[0,1],"properties":{"$os":"...","$browser":"qqbridge","$device":"qqbridge"}}}` |
| 10 | 服务端→客户端 | **Hello**，给 `heartbeat_interval`，随即启动定时心跳 |
| 1 | 双向 | **Heartbeat**：`{"op":1,"d":<lastSeq 或 null>}`；服务端有时会主动发 op1 索要心跳，收到立即回一次 |
| 11 | 服务端→客户端 | 心跳 ACK，无需处理 |
| 6 | 客户端→服务端 | **Resume**：`{"op":6,"d":{"token":"...","session_id":"...","seq":<lastSeq>}}` |
| 0 | 服务端→客户端 | **Dispatch**：带 `t`（事件名）、`s`（序号）、`d`（数据） |
| 7 | 服务端→客户端 | 要求重连：正常关闭后重连（带 session 走 op6） |
| 9 | 服务端→客户端 | 会话失效：**清空 session_id**，重新 Identify |

- `intents = 1 << 25 = 33554432` 即「群聊 + 单聊（C2C）事件」。只做群聊也不要随便减这个值，群事件就在这一位里。
- 维护 `lastSeq`（每帧读 `s` 更新），心跳与 Resume 都要带。
- 断线退避：1s 起、翻倍、**60s 封顶**；`READY`/`RESUMED` 后把退避计数清零。

### 5.3 关心的事件

| 事件 `t` | 何时推送 | 关键字段（`d`） |
|---|---|---|
| `READY` | Identify 成功 | `session_id` |
| `RESUMED` | Resume 成功 | — |
| `GROUP_AT_MESSAGE_CREATE` | 群内 **@机器人** 发消息 | `id`（= 被动回复的 `msg_id`）、`content`（含 `<@!123>` 提及标记，需剥离）、`group_openid`（个别环境是 `group_id`）、`author.{username, member_openid, id}` |
| `C2C_MESSAGE_CREATE` | 单聊消息 | 同上，无 `group_openid`，有 `openid` |

默认消息范围是「仅 @ 消息」，所以不 @机器人 的群聊不会产生事件 —— 这是「收不到事件」最常见的原因之一。

## 6. 与本模组实现的对应

| 协议点 | 代码位置 |
|---|---|
| token 获取与提前刷新 | `qq/QqAuth.java` |
| 401 自愈（重取 token 重试一次） | `qq/QqApi#post`、`qq/QqGateway#fetchGatewayUrl` |
| `msg_type` 0/2 双通道 | `qq/QqApi#sendGroupText` / `#sendGroupMarkdown`，返回 `QqApi.Result`（带平台错误码） |
| `msg_seq` 单调递增（**仅**被动回复带） | `qq/QqApi#addReplyFields` |
| 失败分流：频控退避重试 / Markdown 降级 / 权限类入队 | `bridge/ChatRelay#sendActive` / `#send` |
| 主动推送失败 → 被动兜底队列（≤5 条/msg_id） | `bridge/ChatRelay#push` / `#onInboundMsgId` |
| 被动回复优先（@ 后 4 分钟内复用 msg_id） | `bridge/ChatRelay#takePassiveMsgId` |
| 限速两道闸（间隔 + 每分钟配额） | `util/RateLimiter` |
| Hello/Identify/Heartbeat/Resume/退避 | `qq/QqGateway.java` |
| 群自动绑定（首条 @ 消息，**不受任何转发开关影响**） | `bridge/QqIngress#onGroupMessage` |
