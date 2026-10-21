# 1.21.11 Yarn API 速查（本模组用到的部分）

映射来源：`net.fabricmc:yarn:1.21.11+build.6:v2`。下表每一项都已在 `mappings.tiny` 里核对过（类名 + 方法名），可直接照抄。

## 1. 类名对照

| 用途 | Yarn 名（import） |
|---|---|
| 客户端主类/单例 | `net.minecraft.client.MinecraftClient` |
| 文本组件 | `net.minecraft.text.Text`（可变版 `MutableText`） |
| 玩家实体（客户端/服务端通用） | `net.minecraft.entity.player.PlayerEntity` |
| 客户端网络处理器 | `net.minecraft.client.network.ClientPlayNetworkHandler` |
| HUD 根节点 | `net.minecraft.client.gui.hud.InGameHud` |
| 聊天栏 | `net.minecraft.client.gui.hud.ChatHud` |
| 世界 | `net.minecraft.world.World`（客户端实现 `ClientWorld`） |
| 方块坐标系 | `net.minecraft.util.math.BlockPos` |
| 方块状态 | `net.minecraft.block.BlockState`（`getBlock()` 声明在 `AbstractBlock$AbstractBlockState` 上） |
| 方块实体 | `net.minecraft.block.entity.BlockEntity`（`getPos()` / `getWorld()`） |
| 告示牌方块实体 | `net.minecraft.block.entity.SignBlockEntity`：`getFrontText()` / `getBackText()` → `SignText` |
| 告示牌文本 | `net.minecraft.block.entity.SignText`：`getMessages(boolean filtered)` → `Text[]` |
| 活板门/按钮方块 | `net.minecraft.block.TrapdoorBlock` / `net.minecraft.block.ButtonBlock`（1.21.11 已无 `AbstractButtonBlock`） |
| 方块命中结果 | `net.minecraft.util.hit.BlockHitResult(Vec3d, Direction, BlockPos, boolean)` |
| 交互管理器 | `net.minecraft.client.network.ClientPlayerInteractionManager` |

## 2. 方法/字段对照（旧 Mojang 名 → 现在用的 Yarn 名）

| 旧（Mojang） | 新（Yarn） | 说明 |
|---|---|---|
| `Minecraft.getInstance()` | `MinecraftClient.getInstance()` | 单例 |
| `mc.execute(runnable)` | `client.execute(runnable)` | 来自 `java.util.concurrent.Executor`，实现类 `ThreadExecutor` |
| `mc.getConnection()` | `client.getNetworkHandler()` | 返回 `ClientPlayNetworkHandler`，离线/未进服时为 `null` |
| `connection.sendChat(line)` | `connection.sendChatMessage(line)` | `sendChatMessage(String)`，全服可见 |
| `mc.gui.getChat().addMessage(...)` | `client.inGameHud.getChatHud().addMessage(...)` | `getChatHud()` 在 `InGameHud` 上 |
| `Component.literal(s)` | `Text.literal(s)` | 返回 `MutableText`（`Text` 的子接口），可直接传 `addMessage(Text)` |
| `component.getString()` | `text.getString()` | 在 `Text` 上可用（javadoc 有 `getString()`） |
| `mc.level` | `client.world` | 未进世界时为 `null` |
| `mc.level.players()` | `client.world.getPlayers()` | 声明在 `net.minecraft.world.EntityView` |
| `player.getUUID()` | `player.getUuid()` | 声明在 `net.minecraft.world.entity.UniquelyIdentifiable` |
| `player.getName()` | `player.getName()` | 声明在 `net.minecraft.util.Nameable`，返回 `Text`，再 `.getString()` |
| `player.blockPosition()` | `player.getBlockPos()` | 声明在接口 `EntityLike`（`Entity` 里搜不到属正常） |
| `level.getBlockEntity(p)` | `world.getBlockEntity(p)` | 声明在 `net.minecraft.world.BlockView` |
| `level.getBlockState(p)` | `world.getBlockState(p)` | 同上 |
| `pos.below(n)` | `pos.down(n)` | `BlockPos.down()` / `down(int)` |
| `BlockPos.betweenClosed(a,b)` | `BlockPos.iterate(a,b)` | 返回 `Iterable<BlockPos>`，**闭区间** |
| `Vec3.atCenterOf(pos)` | `Vec3d.ofCenter(pos)` | 另有 `ofCenter(Vec3i, double)` |
| `Vec3.atLowerCornerOf(v)` | `Vec3d.of(Vec3i)` | |
| `Direction.getNearest(dx,dy,dz)` | `Direction.getFacing(double,double,double)` | 返回距离向量最近的轴向 |
| `Mth.atan2` | `MathHelper.atan2` | |
| `player.connection` | `player.networkHandler` | 字段（`ClientPlayNetworkHandler`） |
| `mc.gameMode.useItemOn(...)` | `client.interactionManager.interactBlock(ClientPlayerEntity, Hand, BlockHitResult)` | 返回 `ActionResult`；`isAccepted()` 判断是否受理 |
| `player.swing(hand)` | `player.swingHand(Hand)` | 声明在 `LivingEntity` |
| `player.blockInteractionRange()` | `player.getBlockInteractionRange()` | 返回 `double` |
| 转头封包 | `new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, onGround, horizontalCollision)` | 包名 `net.minecraft.network.packet.c2s.play`；服务端按最后收到的视线判定方块交互，**交互前必须显式发** |

## 3. Fabric 事件（类名与注册方式不变，只是类型换成 Yarn）

```java
// 玩家聊天：5 个参数，第 1 个是 Text，第 3 个是 GameProfile（authlib record，用 sender.name()）
ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
    String name = sender != null ? sender.name() : "?";
    chatRelay.onPlayerChat(name, message.getString());
});

// 系统/游戏消息：2 个参数，overlay=true 表示 actionbar（必须跳过，否则每 tick 刷屏）
ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
    if (overlay) return;
    chatRelay.onGameMessage(message.getString());
});

// 每 tick 末尾
ClientTickEvents.END_CLIENT_TICK.register(client -> { /* client 是 MinecraftClient */ });

// 客户端停机清理
ClientLifecycleEvents.CLIENT_STOPPING.register(client -> gateway.shutdown());
```

`GameProfile`、`SignedMessage` 属于 authlib / 游戏自带库，**不受映射影响**，类名不变；只有 `net.minecraft.*` 的类与方法才需要换 Yarn 名。

## 4. 配置目录

```java
FabricLoader.getInstance().getConfigDir().resolve("qq_bridge.json")
```

## 5. 怎么自己核对映射名（改代码前先查，别猜）

1. 看 Yarn javadoc（最快）：
   `https://maven.fabricmc.net/docs/yarn-1.21.11+build.6/net/minecraft/client/MinecraftClient.html`
2. 直接查映射文件：

   ```bash
   curl -o yarn-v2.jar "https://maven.fabricmc.net/net/fabricmc/yarn/1.21.11%2Bbuild.6/yarn-1.21.11%2Bbuild.6-v2.jar"
   unzip -o yarn-v2.jar -d yarnm
   # mappings/mappings.tiny 是 tiny v2：c<TAB>intermediary<TAB>named，
   # 方法行以 <TAB>m<TAB>descriptor<TAB>intermediary<TAB>named 开头
   grep -P "\tgetPlayers$" yarnm/mappings/mappings.tiny
   ```
3. 查可用 build 号：`https://maven.fabricmc.net/net/fabricmc/yarn/maven-metadata.xml`

## 6. 注意事项

- Yarn 里 `player` / `world` 这些字段名与 Mojang 名巧合相同，但**方法名不同**（`getUuid`、`getPlayers`、`sendChatMessage`），这是混用映射最容易踩的坑。
- 部分方法是从接口继承的（`getString`/`getName`/`getUuid`/`getPlayers`/`execute`），在具体类的 mappings 里搜不到属于正常现象，去接口（`Text`、`Nameable`、`UniquelyIdentifiable`、`EntityView`）或 javadoc 的 "Methods inherited from" 里确认。
- 换映射后**必须重新构建验证**：本地不跑 gradle，直接 push 让 CI 编译，读 Actions 结果。
