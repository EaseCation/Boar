# 网易定制 Geyser 兼容性记录

本文档记录 Boar 本地适配构建时，对 `/Users/fangyizhou/Documents/coding/GeyserWorkspace/Geyser` 与相关 Protocol/SynapseAPI 的源码级对比结论。

## 构建目标

- 本地 Geyser 版本：`2.9.2-SNAPSHOT`。
- 本地 Protocol 版本：`3.0.0.Beta11.NETEASE-SNAPSHOT`。
- Boar 的 `extension.yml` API 版本已降为 `2.9.2`，用于通过 Geyser 扩展加载器的版本检查。
- `settings.gradle.kts` 通过 composite build 引入本地 `GeyserWorkspace` 中的 Geyser、Protocol、Network、authlib-ec 和 igzip，避免编译时引用上游发布版依赖。

## PlayerAuthInputPacket 对比

本地 Protocol 在 `PlayerAuthInputPacket` 中增加了以下网易专有字段：

- `cameraDeparted`
- `thirdPersonPerspective`
- `playerRotationToCamera`
- `readyPosDetalDirty`
- `onGround`
- `resetPosition`

本地 Geyser 对协议 `766` 和 `819` 替换了 `PlayerAuthInputPacket` serializer。对照 `SynapseAPI` 后可确认网易输入 flag 的模型：

- 从协议 `1.21.2` 开始，网易 flag version 为 `1`。
- 从协议 `1.21.93` 开始，网易 flag version 为 `2`。
- 逻辑 flag 在 `ACK_ENTITY_DATA` 及之后的位置，会按网易 flag version 发生偏移。

这与 Geyser 对协议 `766` 的处理意图一致：网易插入的额外 flag 会在解码时被归一化回 Cloudburst 的 `PlayerAuthInputData` 枚举，Boar 看到的是标准化后的对象。

协议 `819` 仍然需要重点观察。Geyser 当前从 `IN_CLIENT_PREDICTED_IN_VEHICLE` 开始做 `-2` 偏移；而 SynapseAPI 的接口语义描述是从 `ACK_ENTITY_DATA` 开始按网易 flag version 偏移。这里不要在 Boar 里盲目二次修正，因为实际解码发生在 Geyser 内部。如果实机测试发现 `1.21.93/1.21.94` 客户端出现异常的 `STOP_FLYING`、`RECEIVED_SERVER_DATA`、载具预测或碰撞 flag，应优先检查并修正本地 Geyser 的 `CodecProcessor`，而不是在 Boar 解码后补偿。

## onGround 处理

本地 Geyser 当前按以下逻辑推导 Java 侧 `onGround`：

```text
packet.isOnGround() || VERTICAL_COLLISION && lastTickEndVelocity.y < 0
```

Boar 原本只会修正 `HORIZONTAL_COLLISION`、`VERTICAL_COLLISION` 和 `delta`。本地适配版现在会在 Geyser 翻译该输入包之前，按 Boar 的预测结果同步覆盖 `packet.onGround`。这样可以避免网易专有 `onGround` 字段绕过 Boar 对碰撞状态的修正。

## Rewind 与移动模式

- Geyser 默认发送 `AuthoritativeMovementMode.SERVER`，并把 `rewindHistorySize` 设为 `0`。
- Boar 会把 `StartGamePacket` 改为 `SERVER_WITH_REWIND`，并写入配置中的 rewind history。
- 协议 `818` 之前会序列化 movement mode 与 rewind history。
- 协议 `818+` 仍会序列化 rewind history，但 movement mode 不再按旧字段序列化。
- 本地 Protocol 仍包含 `CorrectPlayerMovePredictionPacket`，并携带 prediction type、position、delta、vehicle rotation、onGround 和 tick。

## 已加入的运行时诊断

- 管线 hook 成功/失败日志包含玩家名、协议号与 codec 类名。
- `StartGamePacket` 修改日志会输出 movement mode 与 rewind history 的旧值/新值。
- packet listener、编码、解码异常不再静默吞掉，会输出统一日志。
- debug 模式下，如果 Boar 调整了网易 `onGround`，会记录调整前后状态。
- debug 模式下，latency ack 不匹配会记录期望值、实际值与原始 timestamp。
- debug 模式下，预测偏差达到 `player-position-acceptance-threshold` 时，会记录 offset、预测/客户端位置、速度、碰撞、loading、区块和输入 flag。
- 每次实际发送 `CorrectPlayerMovePredictionPacket` 或普通 `MovePlayerPacket` teleport 时，会记录对应的来源数据，便于区分预测 rewind、timer teleport 和区块未加载 teleport。

## 当前实测误判排查策略

实测已经确认 Boar 能进入 `SERVER_WITH_REWIND` 并发送拉回，但在网易定制 Geyser/协议 819 上存在预测误判。当前策略是先保留实际 rewind 行为，同时在每次预测拉回前输出诊断日志。后续应根据日志确认误判是否来自：

- 网易 `PlayerAuthInputData` flag 偏移或解码异常。
- `packet.onGround` 与 Boar 预测地面状态不同步。
- 客户端 `delta`/EOT 与 Boar `velocity` 模型不一致。
- 资源包/大厅出生点/区块加载阶段造成的短时世界状态缺失。
- Geyser 819 的 movement mode 字段行为与旧协议不同。

如果要判断拉回来源，优先看同一时刻是否出现 `[Boar/prediction] Movement discrepancy ... action=rewind` 和 `[Boar/teleport] Sending rewind correction ...`。如果只有普通 `[Boar/teleport] Sending normal teleport ...`，则更可能来自 timer、未加载区块或服务端普通传送。

## 实机测试重点

优先测试协议 `766` 与 `819` 客户端：

- 确认客户端是否接受 rewind correction。
- 重点观察 `STOP_FLYING`、`RECEIVED_SERVER_DATA`、`IN_CLIENT_PREDICTED_IN_VEHICLE`、`HORIZONTAL_COLLISION`、`VERTICAL_COLLISION` 是否出现异常状态。
- 验证覆盖 `packet.onGround` 后，NoFall、跳跃、Step 等场景的误判是否减少，而不是增加。
