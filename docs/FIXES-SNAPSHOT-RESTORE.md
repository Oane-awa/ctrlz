# CtrlZ 1.0.0 —— 快照恢复机制修复记录

本轮修复三个相互关联的问题：状态效果（Buff/Debuff）从未进入快照、恢复到旧节点后历史不截断、G 界面与 Z 快捷键走两套不同的恢复能力。

## 0. 先确认的实际运行链路

| 入口 | 客户端 | 服务端 | 修复前 |
|---|---|---|---|
| `G` 历史界面点行恢复 | `ClientNet.requestRollback(id)` → `C2S_REQUEST_ROLLBACK` | `RollbackService.rollback(actor, id)` | 只反演**这一条**活动，更晚的记录原地不动 |
| `Z` 快捷键 | `ClientNet.rollbackLast()` → `C2S_ROLLBACK_LAST` | `RollbackService.rollbackLast(actor)` → 同一条 `rollback` | 一次只撤销最新一条，且不清理历史 |
| `ctrlz.engine=v2` | `V2ClientNet` | `V2UndoService` | 只支持破坏方块；G/K 被禁用 → 两入口能力不同 |

结论：两个入口虽然都进了 `RollbackService`，但**语义不同**（单条 vs 恢复正常节点），且 v2 引擎下 G 完全不可用。这正是"同一个目标、两种结果"的来源。

## 1. 状态效果：从"字段存在"到"真正参与全流程"

修复前的真实状态：

- `ConsequenceKind.PLAYER_STATE` 只存 `x;y;z`（`ServerRestorer.applyPlayer` 只做传送）；
- `ConsequenceKind.ENTITY_STATE` 从未被写入，恢复分支是 `default -> true`；
- v2 的 `Snapshot.PlayerState.effects` 从来没有被任何捕获代码写入，也没有任何应用代码读取 —— 死字段。

修复后：

| 环节 | 实现 |
|---|---|
| 编码 | 新增 `server/EffectSnapshots`，使用原版 `MobEffectInstance.STREAM_CODEC`（registry id + 等级 + **剩余时长** + ambient/visible/showIcon + 隐藏效果链与混合状态），模组效果同样走注册表 id，无任何硬编码列表 |
| 捕获（玩家） | `CtrlZServer.tickEffects`：每 tick 对玩家效果集合做差分，变化时写入 `ENTITY_EFFECTS` 连锁（subject `player#<uuid>`）。喝药、牛奶、到期、命令、模组来源全覆盖 |
| 捕获（生物） | `Recorder` 攻击路径在下个 tick 比对目标效果，变化时写入 `ENTITY_EFFECTS`（subject `entity#<uuid>`） |
| 校验 | `ServerRestorer.checkEffects`：目标不在（死亡/卸载/下线）→ `TARGET_MISSING` 拒绝；payload 未捕获 → `NO_SNAPSHOT` |
| 恢复 | `ServerRestorer.applyEffects` → `EffectSnapshots.apply`：**先移除当前全部效果，再按快照逐个重建**。因此多余的被清除、等级/时长不一致的被重建为快照值、缺失的被补回，属性修正只重算一次 |
| 未捕获 vs 捕获为空 | `encode` 始终至少 1 字节；`isCaptured()` 区分 `byte[0]`（未捕获，拒绝恢复）与"捕获到空集合"（合法，必须清空当前效果） |

v2 的 `Snapshot.PlayerState.effects` 也接通了：`SnapshotCapture.playerState` 用同一套编解码捕获，`UndoPlannerImpl` 生成步骤（玩家不在线则拒绝），`RestoreApplier` 应用并在失败时用 `PreviousEffects` 回写。

## 2. 历史：恢复到哪个节点，就截断到哪个节点

`ActivityStore` 的顺序标识就是**单调递增的 activity id**（`allocateActivityId`），与 tick/时间戳无关，因此同一 tick 的多个操作仍然有序。

新增 `core/rollback/RestorePlan`（纯逻辑，可单测）：

```
A → B → C → D → E → F
G 恢复 C（keepTarget=true）  → 撤销 F、E、D，删除 D、E、F，保留 A B C
Z 撤销 F（keepTarget=false） → 撤销 F，删除 F，保留 A B C D E
```

- 只截断**当前玩家**的分支（`RestorePlan.victims` 按 `playerId` 过滤，截断用 `deleteActivitiesAfter(playerId, anchor)`）；
- 目标自身保留（`anchor = keepTarget ? targetId : targetId - 1`）；
- 已是 `ROLLED_BACK` / `NOT_ROLLBACKABLE` / `INCOMPLETE` / `PRUNED` 的记录不再重复反演，但随分支一起删除；
- `Z` 不选移动记录（避免"撤销方块却把玩家传走"），G 的显式恢复会回放移动，因为要复现节点的完整状态。

### 2.1 事务顺序（`RollbackService.restoreNow`）

1. 校验目标存在、封口当前活动、权限、状态、冷却；
2. 收集锚点之后本玩家的全部活动（新→旧）及其折叠后的连锁反应；
3. **先把所有活动校验一遍**（目标存在性、状态冲突、多方块完整性）——任何一条不通过就不写入；
4. 逐活动反演（新→旧）；
5. 中途失败：把已反演的活动按旧→新**正向重放**回滚，历史原样保留，返回失败原因；
6. 全部成功后：删除锚点之后的记录 → `flush(true)` → 更新冷却时间 → 通知客户端。

截断本身失败（磁盘错误）时**不返回成功**：返回 `truncate_failed` 并说明世界已恢复但历史未更新。

## 3. 统一入口与统一结果

- 唯一恢复入口：`RollbackService.restore(actor, targetId, keepTarget)`；`restoreToActivity`（G）与 `restoreLast`（Z）都只是它的两种锚点选择。
- 唯一结果结构：`RollbackService.Result(success, targetActivityId, activities, restored, skipped, truncated, conflict, messageKey, detail)`，`NetHandler` 把它整包发给客户端（`Messages.RollbackResultDto` 同步扩字段），GUI 与快捷键拿到的字段完全一致，客户端不再自行推断成功。
- v2 引擎不再可作为运行引擎：`EngineRouter.resolve` 把 `ctrlz.engine=v2` 记为 WARN 并落到统一引擎（v2 只有方块、没有历史界面，一旦启用就会出现"G 用一套、Z 用另一套"）。切片到期后随 legacy 一起删除。

## 4. 界面同步

- 恢复成功后 `ClientNet` 用**上一次的查询条件**重新请求历史，GUI 重建列表 —— 被截断的行不会再出现在页面上；
- 详情页若对应记录已被截断，服务端返回 `ctrlz.gui.detail_missing`，页面显示"该记录已被恢复操作截断"，而不是继续展示过期数据；
- 恢复失败时历史保持原样，失败原因显示在列表顶部。

## 5. 安全性

- 全部恢复在服务端主线程执行（非主线程自动投递并立即返回 `busy`）；
- 每个玩家同时只有一个恢复事务（`tryBeginRollback`，`finally` 释放）；
- 恢复期间 `beginSuppression()` 屏蔽记录，结束后 `resyncBaselines()` 重置背包/位置/**效果**基线，恢复本身不会被记成新操作，也不会再次触发效果差分；
- 效果恢复不触发瞬时药水效果（只重建 `MobEffectInstance`，不调用任何一次性副作用）。

## 6. 测试

| 测试 | 覆盖 |
|---|---|
| `EffectsGameTest.playerEffectsComeBackWithTheRecordedDuration` | 速度 II 600 tick 精确恢复、多效果、移除快照外效果、移速属性只加一次 |
| `EffectsGameTest.anEmptySnapshotClearsEveryEffect` | 捕获为空时必须清空；未捕获 payload 必须拒绝 |
| `EffectsGameTest.nonStandardEffectsAndTheirFlagsSurviveTheRoundTrip` | 非硬编码效果 + ambient/visible/icon 标志 + 重新编码字节一致 |
| `EffectsGameTest.theRestorerBringsBackBuffAndDebuffOnAMob` | 生物 Buff/Debuff 经真实 `ServerRestorer.restore/reapply` |
| `EffectsGameTest.aVanishedTargetIsARefusalAndNotASilentSuccess` | 目标消失 → `TARGET_MISSING` 且恢复返回 false |
| `RestorePlanTest`（7 项） | A→F 截断边界、同一 tick 用 id 定序、其他玩家分支不受影响、Z 不回放移动 |
| `ActivityStoreTest.deletingNewerRecordsKeepsTheAnchorAndOtherPlayers` | 截断持久化、锚点保留、他人记录保留 |
| 既有 46 项 JUnit + 5 项 GameTest | 无回归 |

### 仍需你手动确认（我无法代替真实按键）

1. 喝速度药水 → 等 10 秒 → 按 Z：速度应恢复成喝下时的剩余时长；
2. 有 Buff + Debuff 时按 Z：正负效果都要回到快照；
3. `G` 选一条较早的记录恢复：其后的记录应从列表消失，世界回到该节点；随后做新操作，历史从该节点继续；
4. 同一个目标分别用 `G` 与 `Z` 恢复，比较世界状态与列表结果是否一致；
5. 让某个目标在恢复前消失（如击退致死/离开区块），确认提示为"目标不可用"且历史未变。

## 7. 尚未覆盖（明确说明，不假装完成）

- 同一活动内"效果变化的合并"沿用既有 `consequence` 合并规则（保留最初的 before、推进 after），因此一次活动里反复喝药只留下最终效果；
- 玩家位置/饥饿/经验/着火状态只在 v2 快照里记录，legacy 恢复仍只做传送（本轮范围是效果）；
- `JUMP_BOOST` 等效果带来的属性修正依赖 `addEffect` 的重算，未单独断言除移速外的属性；
- 效果捕获是每 tick 差分，理论上在"同一 tick 内获得并失去"的效果不会被记录（原版药水不会出现这种时序）。
