# CtrlZ 1.0.0 — 撤销一致性修复说明

环境不变：Minecraft 26.1.2 / Fabric Loader 0.19.3 / Fabric API 0.155.2+26.1.2 / Java 25。
数据结构、存储格式、GUI、按键、配置项均保持兼容（只新增了一个配置项 `movementStopTicks`）。

---

## 一、根因分析

四个表面问题背后是 **三个底层缺陷**，它们互相放大：

### 缺陷 A：一次玩家操作被拆进两个活动记录（物品增多 / 特殊方块无响应的主因）

`UseBlockCallback` 与 `UseItemCallback` 都会为"右键放方块"触发：

- `onBlockPlaced` 打开 `PLACE_BLOCK`
- 紧接着 `onItemUsed` 打开 `USE_ITEM`（不同 kind → `closeAction` 立刻封存 `PLACE_BLOCK`）

于是同一次点击产生了两个活动：

| 活动 | 内容 |
|---|---|
| `PLACE_BLOCK` | 方块变化（`Level#setBlock` 归因） |
| `USE_ITEM` | 物品消耗（下一 tick 的背包差异归因） |

`rollbackLast` 取"最新的有连锁反应的活动" = `USE_ITEM`，于是：

- **只撤销 `USE_ITEM`**：物品返还了，方块还立着 → 表现为"物品凭空增加"（正是问题 2）。
- **床/门等**：两半方块被分散到两个活动里，任何一半单独恢复都不完整，`checkBlock` 校验失败 → 表现为"按 Z 无响应"（问题 3、4）。

### 缺陷 B：归因只按"距离最近"，不区分"玩家当前正在做什么"

`findCausal` 在所有仍有效的因果窗口里按距离挑最近的。上一个活动（甚至已封存的）窗口还活着时，
新的方块变化可能被判给旧活动。窗口是"距离 + 时间"的模糊匹配，缺少"当前活动优先"这条硬规则，
导致活动内容错位、结构被拆散。

### 缺陷 C：撤销目标选择不区分"动作"和"移动"

`rollbackLast` 取"最新的有连锁反应的活动"，不区分类型。玩家挖/放方块后再走动（或站着等一会儿，
移动记录被判定结束），最新的活动就变成带 `PLAYER_STATE` 的 `MOVE`：

- 按 Z → 传送回行走起点 → 表现为"方块撤销导致玩家坐标回退"（问题 1）。
- 等待时间不同 → 最新活动不同 → 同一个按键行为变化（违反"等待时间不得改变撤销目标"）。

### 附带问题

- `ActivitySession` 只有一个活动槽，移动与动作互相干扰（上一轮已改双槽，但生命周期规则仍不严）。
- 移动记录没有"真正停止"的判据，只靠固定的合并窗口。
- 快速连按 Z 没有重入保护；一次冲突会让按键反复命中同一条记录（表现得像按键失效）。
- 撤销失败的反馈只在 GUI 里显示，快捷键按下去没有任何提示 → 看起来"无响应"。

---

## 二、修改文件清单

| 文件 | 修改要点 |
|---|---|
| `core/activity/ActivityKind.java` | 新增 `samePlayerActionAs()`（`PLACE_BLOCK`↔`USE_ITEM`、`MOVE_ITEM`↔`CRAFT` 视为同一次点击）、`isMovementKind()` |
| `core/activity/ActivitySession.java` | 停止窗口 + 速度判据、跨类结束（新动作结束移动、新移动结束动作）、`actionForConsequences()` 宽限期、封存记录保留 |
| `core/rollback/ConsequenceCollapser.java` | 新增（从 `RollbackService` 抽出为纯逻辑，可单测）：同一目标的多次变化折叠为"原始→最终" |
| `server/CtrlZServer.java` | 每 tick 传水平速度；移动只在真实位移时采样；背包差异只归给"动作"；`findCausal` 优先未封存活动并按活动 id 决胜；冲突记忆、回退重入锁 |
| `server/RollbackService.java` | 快捷键只撤销"动作"；重入保护；服务端线程校验；多方块结构完整性预检；冲突标记；失败反馈 |
| `server/ServerRestorer.java` | 玩家位置恢复改为显式 `teleportTo`（仅 `PLAYER_STATE`） |
| `server/Snapshots.java` | 新增 `propertyText`、`siblingPosition`（按 `part`/`half` 属性识别床/门/高植株的另一半） |
| `server/NetHandler.java` | 撤销失败时在动作栏提示（快捷键按下不再是"无响应"） |
| `config/CtrlZConfig.java` | 新增 `movementStopTicks`（默认 10，2..100） |
| `client/.../ConfigScreen.java` | 配置界面暴露 `movementStopTicks` |
| `assets/ctrlz/lang/{en_us,zh_cn}.json` | 新增 `movementStopTicks`、`incomplete_structure`、`busy` 等文案 |
| `test/.../ActivitySessionTest.java` | 重写为按新生命周期断言（12 项） |
| `test/.../ConsequenceCollapserTest.java` | 新增（3 项） |

## 三、每个问题的修复方案

### 问题 1：方块操作撤销导致玩家坐标回退

1. `rollbackLast` **不再选择移动类活动**，只撤销最近的动作（挖掘/放置/使用/容器/合成/丢弃）。移动的撤销仍保留在历史界面中，由玩家显式点选。
2. 方块类活动永远不携带 `PLAYER_STATE`（该连锁反应仅在 `MOVE` 封存时产生），因此撤销方块不会触及坐标、视角、速度或姿态。
3. 等待时间不再改变目标类型：快捷键的目标集合固定为"动作"，等待只会让更早的动作成为"最近的动作"。
4. 归因改为"未封存活动优先 → 距离 → 活动 id"，避免把新变化记到旧活动上。

### 问题 2：快速撤销导致物品凭空增加

1. **一次点击 = 一个活动**：`PLACE_BLOCK` 与 `USE_ITEM` 合并（`samePlayerActionAs`），方块变化与物品消耗必然在同一事务里。
2. 背包差异只归给"动作"，并保留 3 tick 宽限期，解决"放置已封存、消耗下一 tick 才可见"的错位。
3. 折叠保证同一槽位只留一条"原始→最终"，同一条恢复记录不可能被执行两次。
4. 物品比对使用 `ItemStack.matches`（26.1.2 中它比较数量 + 数据组件），堆叠数、耐久、附魔、自定义名称都参与判断。
5. 事务由 `RollbackEngine` 统一执行：冲突预检 → 全部恢复 → 任一失败则正向重放；不会出现"方块恢复成功、物品失败"的半成品。
6. 快速连按 Z：`tryBeginRollback` 重入锁 + 冷却，同一事务不会重复执行。

### 问题 3 / 4：床、门等特殊方块按 Z 无响应

1. 一次点击产生一个活动后，两半方块都在同一活动的因果窗口内（窗口随每次记录的位置"生长"，覆盖相邻半块）。
2. 恢复前做**结构完整性预检**（`checkStructures`）：若某个带 `part`/`half` 属性的方块，其另一半不在本次事务里，直接拒绝并说明原因（`ctrlz.result.incomplete_structure`），绝不恢复半个结构。
3. 方块修复顺序：`Level#setBlock`（四参数版本）记录"原始→最终"，`BLOCK_ENTITY_DATA` 先入队，回退时按逆序先恢复方块、再装载方块实体，方向、连接、上下半等属性都由完整 `BlockState` 还原。
4. 挖掘掉落物在 `ServerLevel#addFreshEntity` 归因到同一活动；回退时先按 UUID 清除掉落物，再恢复方块，不会重复生成。
5. 某个方块校验失败只影响它所属的那次事务，并被记为"暂时冲突"（200 tick），按 Z 会继续处理更早的动作，不会让按键失效。

## 四、生命周期与事务一致性

- **独立动作及时结束**：新动作结束旧动作；新动作结束此前的移动；新移动结束此前的动作。不同类型不会因为合并窗口被错误合并。
- **移动结束条件**：连续 `movementStopTicks`（默认 10 tick）无位移**且**水平速度低于阈值才结束；击退/滑行期间速度高，不会误判；长时间静止期间的坐标不会进入之前的移动记录。
- **隔离**：移动记录只含 `PLAYER_STATE`；动作记录只含方块/容器/背包/实体变化。撤销移动不改方块与物品，撤销动作不改玩家位置。
- **事务**：开始记录初始状态 → 收集全部关联变化 → 封存时提交 → 撤销前校验 → 完整恢复 → 失败则正向重放。撤销期间 `suppression` 屏蔽记录，结束后重置背包/位置基线，避免把回退本身记成新活动。
- **线程**：所有世界写入在服务端主线程执行（`isSameThread()` 校验，非主线程自动投递）。

---

## 五、测试结果

### 5.1 自动化（`gradlew build`）

```
BUILD SUCCESSFUL
:test  30 tests, 0 failures, 0 errors
```

| 测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `ActivitySessionTest` | 11 | 新动作结束移动 / 新移动结束动作 / 不同类型不合并 / 一次点击（放置+使用）只产生一个活动 / 连续挖掘合并 / 真正停下才结束移动 / 滑行不误判 / 传送切开活动且不计距离 / 封存后的归因宽限期 / 移动不接收背包变化 / flush 全部封存 |
| `ConsequenceCollapserTest` | 3 | 同一方块多次变化折叠为原始→最终 / 不同方块保持独立 / 不同槽位保持独立 |
| `RollbackEngineTest` | 4 | 严格逆序 / 冲突零写入 / 中途失败正向重放 / 不归因项不回退 |
| `ActivityStoreTest` | 5 | 重启持久化 / 未收尾标记 / 坏尾截断 / 未知 id / 容量限制 |
| `ActivityRecordCodecTest` | 3 | 轨迹与统计往返 / 摘要编码 / 坐标打包 |
| `TraceCompressorTest` | 4 | 直走折叠 / 拐角保留 / 状态点保留 / 静止段时间戳 |

### 5.2 运行时（`gradlew runServer`）

```
Loading Minecraft 26.1.2 with Fabric Loader 0.19.3
\t- ctrlz 1.0.0
(CtrlZ) CtrlZ activity recorder ready
CtrlZ verified 7/7 mixin targets
```

无 `Mixin apply failed` / `InvalidInjection`，无 `com.ctrlz` 异常；世界创建、tick 循环与关机正常。

> 说明：无头环境无法自动完成"带玩家的方块/物品交互"，第 5.3 节是需要人工确认的回归项。

### 5.3 手工回归清单（对应任务书第五节）

| # | 场景 | 期望 |
|---|---|---|
| 1 | 放方块后立即 Z | 方块消失、物品 +1、坐标不变 |
| 2 | 放方块后等 5 秒 Z | 同上（等待不改变目标类型） |
| 3 | 挖方块后等 5 秒 Z | 方块复原、掉落物清除、坐标不变 |
| 4 | 连续放多个方块后逐次 Z | 每次只回退一个，物品逐次返还，无跳变 |
| 5 | 楼梯/栅栏/墙/活板门快速放 + Z | 数量恰好 +1，不出现凭空增加 |
| 6 | 放床 + Z | 两半同时消失，物品 +1 |
| 7 | 挖床 + Z | 两半复原，方向正确，只掉一个床 |
| 8 | 放/挖门 + Z | 上下半同时变化，无残留半门 |
| 9 | 放/挖箱子 + Z | 方块与箱内物品都复原，无复制 |
| 10 | 连续移动后停下 | 历史中移动记录结束（不再"记录中"） |
| 11 | 移动后立刻放方块 | 移动记录结束，放置记录独立 |
| 12 | 放方块后立刻移动 | 放置记录结束，移动记录独立 |
| 13 | 快速连按 Z | 无重复执行、无重复返还（提示"已有一次回退正在执行"或冷却） |
| 14 | 撤销时世界冲突 | 提示冲突且世界零改动；再按 Z 处理更早的活动 |
| 15 | 多种操作混合后逐条 Z | 顺序正确、逐条恢复到各自之前的状态 |

