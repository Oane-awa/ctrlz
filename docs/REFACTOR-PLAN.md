# 活动历史与撤销系统 —— 重构前置调研与设计（阶段 0 交付物）

> 本文档只做调研、设计与提问。**未写任何实现代码。** 等你确认第 6 节的提问后再进入阶段一。

---

## 1. 调研结论：现有代码的形态

现有实现是**事件逆向式**：用 Fabric 回调 + Mixin 捕获"玩家做了什么"，把世界变化记成
`ActivityRecord`（活动）+ `ConsequenceRecord`（连锁反应，before/after 字节），撤销时把这些
before/after 逆序应用。

它与新规格的根本差异：

| 维度 | 现有实现 | 新规格要求 |
|---|---|---|
| 记录单位 | 活动（可跨 tick、可合并）+ 连锁反应 | `Operation`（一次根操作）+ `Snapshot`（修改前状态） |
| 撤销目标 | 活动（含移动） | 有效操作（移动默认跳过） |
| 完整性 | 无完整性校验，`coverage` 不存在 | 提交前必须校验 P0 齐全，否则 NONE 不记账 |
| 原子性 | 冲突检查 + 失败重放 | 暂存区预演（含守恒审计、实体存在性）再一次性写入 |
| 归因 | 因果窗口（距离 + 时间） | 线程活跃 `Transaction` 上下文，禁止用时间做因果判据 |
| 持久化 | 自定义二进制分片日志 | `SavedData` + schemaVersion + 世界指纹 |
| 幂等 | `status == ROLLED_BACK` | `operationId + appliedSeq` |
| 时间基准 | `world.getGameTime()` | 自维护 `playTick` |
| GUI | 历史/详情/配置三个界面 | 只保留调试命令，无 GUI |

结论：**必须整体替换**，无法在旧模型上补 `coverage`/`Transaction`/守恒审计。

---

## 2. 26.1.2 实际 API 核对（已用 javap 逐个验证）

| 规格中的名字 | 26.1.2 实际签名 | 结论 |
|---|---|---|
| `PersistentState` | 不存在；Mojang 名是 `net.minecraft.world.level.saveddata.SavedData`（`setDirty/isDirty`） | **用 `SavedData`**；注册用 `SavedDataType<T>(Identifier, Supplier<T>, Codec<T>, DataFixTypes)` |
| 存档数据容器 | `net.minecraft.world.level.storage.SavedDataStorage`，`ServerLevel.getDataStorage()`，`computeIfAbsent(SavedDataType<T>)/get/set` | 可用 |
| 版本号 | `SharedConstants.getCurrentVersion()` → `WorldVersion.id()/name()/dataVersion()` | 可用于 MC 版本指纹 |
| GameMode | `net.minecraft.world.level.GameType`（`isCreative()`）、`ServerPlayer.gameMode()` | 可用 |
| **GameTest 注解** | vanilla `net.minecraft.gametest.framework.GameTest` **在 26.1.2 已不存在**；Fabric 0.155.2 用 `net.fabricmc.fabric.api.gametest.v1.GameTest`（`structure/maxTicks/setupTicks/required/rotation/…`） | 必须用 Fabric 注解 |
| GameTest 运行 | `fabric-gametest-api-v1 4.0.17`；属性 `fabric-api.gametest`、`fabric-api.gametest.report-file`、`fabric-api.gametest.filter`、`fabric-api.gametest.verify` | 已加 `loom.runs.gametest`，`gradlew runGametest` 可用 |
| **GameTest 发现方式** | **实测**：`TestAnnotationLocator` 只反射 `fabric-gametest` entrypoint 类的声明方法，**不扫描 mod classpath**；`structure()` 会走 `Identifier.parse`，**空字符串直接抛错** | 必须在 `fabric.mod.json` 写 `"fabric-gametest": [...]`，且 `structure` 用真实结构（`minecraft:empty`） |
| `GameTestHelper` | 完整可用：`setBlock/assertBlockState/assertBlockEntityData/assertItemEntityCountIs/assertContainerContains/makeMockPlayer/runAfterDelay/startSequence/succeed/fail/assertTrue` | 验收断言全部可写 |
| Payload | `PayloadTypeRegistry.serverboundPlay()/clientboundPlay()`、`CustomPacketPayload.Type`、`StreamCodec.composite`、`Identifier.fromNamespaceAndPath` | 与现有代码一致，可复用 |
| 方块实体 NBT | `BlockEntity.saveWithFullMetadata(HolderLookup.Provider)`；`loadWithComponents(TagValueInput.create(ProblemReporter, Provider, tag))` | 可用 |
| 实体定位 | `ServerLevel.getEntityInAnyDimension(UUID)` | P1 实体恢复可直接用 |
| 玩家传送 | `ServerPlayer.teleportTo(ServerLevel, x, y, z, Set<Relative>, yaw, pitch, boolean)` | 可用 |
| 物品序列化 | `ItemStack.OPTIONAL_STREAM_CODEC` + `RegistryFriendlyByteBuf`（本项目已验证可用） | 采用二进制 blob（见 §4.3） |

**尚存的技术不确定性**：`SavedData` 的 codec 是否以 registry ops 驱动，我没有逐字节确认；为规避风险，
设计里物品/NBT 一律存**不透明二进制 blob**，不使用 `ItemStack.CODEC`。

---

## 3. 废弃清单

### 3.1 直接删除

| 文件/包 | 原因 | 仍被谁引用（grep 结果） |
|---|---|---|
| `core/activity/*`（`ActivityKind/Record/Query/Session/Stat/Status`） | 活动模型被 `Operation` 取代 | `ActivityQuery` **无任何引用**（死代码）；其余被 server/client 引用，随重构删除 |
| `core/consequence/*` | 被 `Snapshot/CausalChain/Diff` 取代 | server 各记录钩子 |
| `core/rollback/*`（含 `ConsequenceCollapser`） | 被 `UndoPlan/UndoExecutor` 两阶段流程取代 | `RollbackService`、单测 |
| `core/storage/*`（`ActivityStore/JournalReader/Writer/Record/RecordType`） | 改为 `SavedData` + 内存栈 | `CtrlZServer`、单测 |
| `core/trace/*` | 移动改在阶段三用 `MoveSegment` 重做 | `ActivitySession` |
| `server/CtrlZServer`、`Recorder`、`RecorderEvents`、`Hooks`、`ServerRestorer`、`RollbackService`、`HistoryService`、`NetHandler`、`Permissions`、`MineTracerBridge` | 全部由新模块取代 | 相互引用 + `CtrlZMod` |
| `net/CtrlZPayload`、`Messages` | 改为 `UndoRequestPayload` / `UndoResultPayload`（无 JSON） | `CtrlZMod`、`ClientNet`、`NetHandler` |
| `config/CtrlZConfig` | 字段大改（保留 JSON 文件与总开关概念） | `ConfigScreen`、server |
| `client/gui/HistoryScreen`、`DetailScreen`、`ConfigScreen` | 规格 §2 不做 GUI，但你选择保留（Q1=C），改为接新模型 | `CtrlZClient` |
| `test/**`（6 类 30 项） | 断言的是旧模型 | — |

### 3.2 保留并改造

| 文件 | 处理 |
|---|---|
| `mixin/LevelMixin` | **保留**：`Level#setBlock(pos,state,flags,recursion)` 仍是捕获方块变化的唯一可靠入口；改为写入当前 `Transaction`，不再做距离归因 |
| `mixin/ContainerMenuMixin` | 保留 `AbstractContainerMenu#clicked`，改为点击前捕获受影响槽位（阶段一） |
| `mixin/ServerPlayerMixin` | 保留 `openMenu`（容器定位）；`drop` 钩子移到阶段二 |
| `mixin/PlayerMixin`、`mixin/ServerLevelMixin`、`mixin/ItemEntityMixin`、`mixin/LivingEntityMixin` | 阶段二/三使用 |
| `server/Snapshots` | 方法迁入新 `platform/SnapshotCodec`（`encodeBlockState/decodeBlockState/blockEntityData/siblingPosition` 保留复用） |
| `core/util/ByteIO` | 保留（持久化仍需变长编码） |
| `CtrlZMod` | 保留骨架：payload 注册、生命周期、按键；内部改为新服务 |
| `client/CtrlZClient` + `ClientNet` | 保留 Z 键与网络层骨架，去掉 G/K 与三种屏幕 |

### 3.3 残留依赖确认方式

重构完成后 grep `ActivityRecord|ConsequenceRecord|ActivityStore|RollbackService|HistoryService|MineTracerBridge|ConfigScreen|HistoryScreen` 必须 0 命中；同时移除 `fabric.mod.json` 的 `recommends.minetracer`。

---

## 4. 新架构设计

### 4.1 模块划分

```
com.ctrlz
├── CtrlZMod                     入口：payload 注册、生命周期
├── platform/                    MC 适配层（唯一接触世界的地方）
│   ├── SnapshotCodec            BlockState/NBT/ItemStack ⇄ 字节
│   ├── SnapshotCapture          执行前捕获 P0/P1 快照
│   ├── RestoreApplier           应用 Diff（逆序写入 + 失败回滚）
│   └── TransactionContext       ThreadLocal 活跃事务 + RestoreMode 标记
├── history/                     纯逻辑（不依赖 MC，可单测）
│   ├── Operation / OperationType / Coverage
│   ├── Snapshot（sealed interface + 5 个实现）
│   ├── Transaction / CausalChain / Diff
│   ├── UndoPlanner              预演：冲突、守恒、实体存在性
│   ├── UndoExecutor             应用 + 失败回滚
│   └── HistoryStack             保留策略、FIFO、过期、内存统计
├── persistence/
│   ├── HistoryState extends SavedData    schemaVersion + playTick + seq + records
│   ├── WorldFingerprint                 MC 版本 + mod 指纹 + worldStateToken
│   └── PlayTickClock
├── net/  UndoRequestPayload / UndoResultPayload / ServerUndoHandler / ClientUndoHandler
├── command/  UndoHistoryCommand（list / dump / purge）
└── mixin/  LevelMixin / ContainerMenuMixin /（阶段二其余）
```

依赖方向：`platform → history`、`persistence → history`、`net/command → history`；
`history` 不依赖 MC（`Snapshot` 内只存不透明 `byte[]` 与字符串）。

### 4.2 核心数据结构（相对规格的调整已标注）

```java
enum Coverage { FULL, PARTIAL, NONE }
enum OperationType { BREAK_BLOCK, PLACE_BLOCK, CONTAINER_MOVE, CRAFT,
                     DROP_ITEM, ATTACK, FLUID_PLACE, PISTON_PUSH, MOVE }

final class Operation {
  UUID operationId; UUID playerUuid; ResourceKey<Level> worldKey;
  long seq; long createdPlayTick; OperationType type; GameMode gameMode;
  Coverage coverage; List<Snapshot> snapshots; CausalChain chain;
  boolean undone; long appliedSeq;
  List<StateFingerprint> afterFingerprints;   // 见"调整 1"
}

sealed interface Snapshot permits BlockSnapshot, InventorySnapshot,
        ContainerSnapshot, EntitySnapshot, PlayerStateSnapshot {}

record BlockSnapshot(ResourceKey<Level> dim, BlockPos pos, String blockState,
                     byte[] blockEntityNbt, StateFingerprint after) {}
record InventorySnapshot(UUID player, Scope scope, Map<Integer, byte[]> items,
                         StateFingerprint after) {}
record ContainerSnapshot(ResourceKey<Level> dim, BlockPos pos, byte[] blockEntityNbt,
                         Map<Integer, byte[]> items, StateFingerprint after) {}
record EntitySnapshot(UUID uuid, Identifier type, byte[] fullNbt, boolean existedBefore,
                      double x, double y, double z) {}
record PlayerStateSnapshot(ResourceKey<Level> dim, double x, double y, double z,
                           float yaw, float pitch, double vx, double vy, double vz,
                           float health, int food, int xp, byte[] effects, int fireTicks) {}
```

**调整 1（已实施）**：§7.4 要求"对比快照记录的**操作后状态**与当前实际状态"，但 §5 的 `Snapshot`
只有修改前状态。实现上"操作后状态"放在 `Transaction.after()` / `Operation.afterFingerprints()`
里，用 `SnapshotKeys.of(snapshot)` 作键——快照本身保持不可变，持久化时与记录一起写入 `after` 字段。

**调整 2**：`CausalChain` 只是"同一 `Transaction` 内的附加快照集合 + 归因深度"，不持久化 `Diff`；
`Diff` 由 `UndoPlanner` 在预演时按 `Snapshot` 与当前世界计算，避免持久化冗余与漂移。

### 4.3 持久化格式（`SavedData`）

`<存档>/data/ctrlz_history.dat`，通过 overworld 的 `getDataStorage()` 访问：

```json
{
  "schemaVersion": 1,
  "mcVersion": "26.1.2",
  "modFingerprint": "fabric-api@0.155.2|ctrlz@1.0.0|…",
  "worldStateToken": "<seed>|<levelName>|<level.dat mtime at last ctrlz save>",
  "playTick": 123456,
  "seq": 42,
  "records": [
    { "id": "…", "type": "BREAK_BLOCK", "coverage": "FULL",
      "createdPlayTick": 123000, "gameMode": "SURVIVAL",
      "undone": false, "appliedSeq": 0,
      "snapshots": [
        { "kind": "block", "dim": "minecraft:overworld", "pos": [10, 64, 10],
          "state": "minecraft:chest[facing=north,type=single]",
          "nbt": "base64:…", "afterHash": "…" } ] } ]
}
```

- 物品与 NBT 一律 base64 的**不透明 blob**（`ItemStack.OPTIONAL_STREAM_CODEC` / `NbtIo`），
  规避 registry-ops 差异。
- `schemaVersion` / `modFingerprint` / `mcVersion` / `worldStateToken` 任一不匹配 → **整个历史栈作废**
  并明确提示玩家（不猜迁移）。
- 保存时机：`setDirty()` 由本 mod 在每次 Commit/撤销后调用，落盘交给存档流程；**不只在退出时写**。

### 4.4 关键流程时序

```
[tick N] 玩家挖掘
  LevelMixin(HEAD)  → TransactionContext.current() == null ?
                      → PlayTickClock.now()
                      → SnapshotCapture.beforeBlock(pos)        // P0 快照
                      → new Transaction(operationId, BREAK_BLOCK, snapshots)
                      → TransactionContext.open(tx)
  LevelMixin(RETURN) → 变化已应用；vanilla 继续（掉落物等）
  同 tick 的相邻 setBlock / spawnItem → tx.chain（同样由上下文判定）
  事务封口：显式结束 / 20 tick 超时 / 退出 / 换维度 / 区块卸载
    → Commit：P0 校验 → coverage → NONE 丢弃并提示 → 压栈 → 淘汰过期与超限

[Z 键] Client（去抖 200ms） → UndoRequestPayload
  → ServerUndoHandler（主线程）
    → 频率校验（200ms）
    → 取最近一条有效操作（跳过 undone / 过期 / coverage=NONE / 移动）
    → RestoreMode.on()
    → UndoPlanner.plan(op)          // 暂存区：Diff + 冲突 + 守恒 + 实体存在性
        ├ 冲突 → 中止，ActionBar "该区域已发生其他变化"
        └ 通过 → UndoExecutor.apply(plan)   // 逆序写入；异常 → 回滚已写入部分
    → op.undone = true; appliedSeq++
    → RestoreMode.off()（finally）
    → UndoResultPayload → Client ActionBar
```

`RestoreMode` 实现：`TransactionContext.restoring` 标志，`LevelMixin`/`ContainerMenuMixin`
入口第一行检查并直接返回（满足 I4）。

---

## 5. 阶段计划与验收映射

| 阶段 | 交付内容 | 验收（§15 编号） |
|---|---|---|
| 一 | `Transaction`/`RestoreMode`/两阶段撤销/冲突检测/守恒审计/客户端按键链路/`/undohistory`/持久化 + §10.2 校验 | 1,2,3,4,11,12,13,14,15,16,17,18,19 |
| 二 | 丢弃/合成/掉落物/实体死亡/玩家属性/创造模式 | 5,6,9 + 创造模式专项 |
| 三 | 活塞/流体/红石归因 + 移动区间合并（Z 不选移动） | 7,8,10 |

**阶段一第 0 步**：先写一个最小 GameTest（空结构 + 一个 `assertTrue`）并跑通
`gradlew runGametest`，确认 Loom 1.19.4 + Fabric API 0.155.2 的链路可用。
若跑不通，我会带着完整报错回来问你如何验收，**不会**擅自改成"手工测试"充数。

---

## 6. 需要你确认的问题

> 每条都给了我的推荐默认值；你可以直接回"按推荐"或逐条改。

**Q1（最影响废弃清单）旧 GUI 与 MineTracer 如何处理？**
规格 §2 说"不做历史记录的 GUI 浏览界面"，但项目现有 `G` 历史界面、详情界面、`K` 配置界面，
以及 MineTracer 交叉审计。三个选项：
- A（推荐）删除历史/详情 GUI 与 MineTracer；保留一个**极简配置屏**（只有"总开关 + 关键参数"）。
- B 全部删除，配置只保留 JSON 文件 + 命令。
- C 保留三个界面并改接新模型（工作量显著增加，且与"不做 GUI"冲突）。

**Q2 `Snapshot` 增加"操作后状态"指纹是否允许？**
§7.4 要求用"操作后状态"做冲突检测，但 §5 的 `Snapshot` 只定义修改前状态。推荐：允许
`StateFingerprint after`（提交时写入）。

**Q3 P2 的判定方式与降级时机**（"触及 P2"缺少可执行判据）
- A（推荐）封口后开 20 tick 观察窗：若同一影响区域出现"非本事务、非玩家操作"的方块变化，
  则把该记录降级为 `PARTIAL`；观察窗结束前该记录不可被 Z 选中（Z 最多延迟 1 秒）。
- B 简单粗暴：所有方块操作一律 `PARTIAL`。
- C 不降级（只记录日志），与 §6 要求冲突，我不建议。

**Q4 多方块结构的完整性判据**
推荐：`BlockState` 含 `part`/`half` 属性时，要求"另一半"也在本事务内（床按 `facing+part`，
门/高植株按上下），双箱按 `type`+邻居查找；无法判定结构的方块（只有 BlockEntity 无 part/half）
按单方块处理。**不接受**用"半径 2 格"作为完整性依据。确认？

**Q5 容器操作的事务边界**
推荐：一次 `clicked` = 一个 `Operation`（快照含容器 + 背包受影响槽位）；同一菜单内间隔
<200ms 的连续点击合并为一个 `Operation`（封口条件：菜单关闭 / 200ms 无点击 / 20 tick 超时）。
拖动分配会因此成为一条记录。确认？

**Q6 Z 的查找规则**
推荐：跳过"已撤销 / 过期 / coverage=NONE / 移动"的记录继续向下找；**遇到冲突则整体中止并提示**，
不自动跳到更早的记录（严格按 §7.5 第一条）。确认？

**Q7 撤销失败后的记录状态**
推荐：冲突 → 保持可重试；守恒审计不通过 → 记 ERROR 并**冻结该记录**（`blocked=true`），
之后 Z 不再选它，提示玩家。确认？

**Q8 持久化指纹强度（§10.2 反刷物品）**
难点："存档被本 mod 之外修改过"没有可靠判据；用 `level.dat` mtime 会导致**每次正常保存都作废**。
- A（推荐）只比对 `schemaVersion + mcVersion + modFingerprint + seed + levelName`；外部改动靠
  "读档后首次撤销强制执行完整预演校验"来兜底。
- B 额外做世界文件哈希（`level.dat` + `region/` 抽样），成本高、可能误判。

**Q9 旧单测如何处理**
推荐：删除与旧模型绑定的 30 项测试；把"折叠/压缩"等仍有效的**思想**迁移到新实现并重写对应测试。
（不做旧测试的兼容层。）

**Q10 GameTest 的接入与调用方式**
推荐：允许我修改 `build.gradle` 增加 `loom.runs.gametest`（`-Dfabric-api.gametest` 等），
GameTest 放在 `src/test`；测试里**直接调用内部撤销 API**（`UndoService.undoForTest`），
按键链路另用一条 payload 往返用例覆盖。确认？

**Q11 移动记录是否入历史栈（阶段三）**
推荐：记录（`type=MOVE`, `coverage=NONE`），参与过期与 32MB FIFO，但**不参与 Z 的选择**。
确认？

**Q12 版本号与产物**
本次是架构替换，建议把 mod 版本升到 `1.1.0`（jar 名变化，便于区分）。是否同意？还是保持 `1.0.0`？

---

**下一步**：请你回复 Q1–Q12（或"按推荐"）。我收到后立刻按阶段一开工，
第 0 步先验证 GameTest 链路并把结果发给你，再写核心实现。

---

## 7. 执行记录（阶段一进行中）

**你的决定**：Q1 = **C（保留三个 GUI 并接新模型）**；Q2–Q12 按推荐。
因此 `client/gui/*` 从"删除"改为"改造"，**MineTracer 集成按 Q1 推荐删除**（如要保留请告知，
它需要另行适配新模型）。

### 7.1 已完成并验证

| 项 | 证据 |
|---|---|
| GameTest 链路打通 | `gradlew runGametest` → `build/gametest.xml`；`ctrlz 1.0.0` 加载、`FabricGameTestRunner` 启动、报告生成 |
| GameTest 发现方式已定位并修正 | 读 `TestAnnotationLocator` 源码 + 两次实跑对比：用例必须在 `fabric-gametest` entrypoint 中且 `structure` 非空 |
| `history/` 纯逻辑内核 | `Coverage`/`OperationType`/`Snapshot`/`SnapshotKeys`/`StateFingerprint`/`Transaction`/`Operation`/`HistoryStack`/`UndoResult`/`ConservationAudit` |
| `platform/` 适配层（部分） | `SnapshotCodec`（方块状态字符串、BE NBT、`ItemStack.OPTIONAL_STREAM_CODEC`、坐标打包、`siblingPosition`）、`TransactionContext`（ThreadLocal + RestoreMode）、`PlayTickClock` |
| `persistence/`（部分） | `HistoryState extends SavedData`（`SavedDataType` + `Codec.STRING.xmap`，schemaVersion/playTick/seq/指纹/记录）、`WorldFingerprint` |
| 单元测试 | 新增 13 项（`HistoryStackTest`、`TransactionTest`），全仓 **43 项 0 失败** |
| 构建 | `gradlew build` → BUILD SUCCESSFUL，`ctrlz-1.0.0.jar` 232,685 字节 |

### 7.2 尚未完成（阶段一剩余）

1. `SnapshotCapture`（方块/容器/背包快照 + 多方块完整性 + P2 标记）
2. `UndoPlannerImpl` / `RestoreApplier` / `ConservationAuditImpl` / `UndoService`（两阶段撤销）
3. `ActorContext` + `LevelMixin`/`ContainerMenuMixin` 改造、`PlayerBlockBreakEvents`/`UseBlockCallback` 接线
4. `net/` 两类 payload + 服务端处理器 + 客户端 Z 键去抖
5. `/undohistory list|dump|purge` 命令
6. `HistoryPersistence`（读档加载、指纹校验、作废提示）
7. 三个 GUI 改接新模型；旧 `core/*`、`server/*`、`config`、`net` 整体拆除
8. §15 的 13 条阶段一 GameTest 用例

## 8. 切片 0 交付记录

### 8.1 A 节（运行时互斥引擎开关）逐条落实

| 要求 | 实现 |
|---|---|
| 单一判定点 | `engine/EngineRouter` 是唯一读取 `ctrlz.engine` 并裁决的地方。Mixin 里**没有任何** `if (engine == V2)`，全部改为调用 `EngineRouter.routeXxx(...)`（setBlock / addEntity / jump / menu / itemTouch / drop 共 7 类） |
| 禁止热切换 | `EngineRouter.resolve` 只认第一次调用，之后不同值只记 WARN 并忽略；配置仅在启动读取一次 |
| 命名空间隔离 | v2 用 `ctrlz:history` 的 `SavedData`；legacy 用存档内 `ctrlz/history/` journal。两者没有任何交叉读取路径 |
| Z 键唯一处理方 | `CtrlZMod` 只注册生效引擎的 payload 类型与 receiver；`CtrlZClient` 只注册生效引擎的 client receiver，且 Z 键只向 `EngineRouter` 询问一次后转发 |
| 启动打印 | `[ctrlz] Active undo engine: v2 (schemaVersion=1, history=N entries)` / `legacy (journal based, v2 history disabled)`，并额外打印引擎配置的绝对路径 |
| ActionBar 带引擎标识 | 每条撤销反馈都追加 `[v2]` / `[legacy]`（不只是调试模式） |
| `/undohistory` 报引擎 | 首行 `engine=...`；legacy 时明确提示"v2 未启用，下面的计数来自 v2 存储且为空" |

### 8.2 切片 0 实际落地

| 层 | 文件 |
|---|---|
| 引擎裁决 | `engine/EngineRouter` |
| 配置 | `config/CtrlZConfig`（新增 `engine`，默认 `legacy`，非法值回落 legacy） |
| 捕获 | `platform/SnapshotCapture`（block / blockFingerprint / spawnedEntity） |
| 事务 | `v2/V2Recorder`（破坏开启事务、`addFreshEntity` 捕获掉落物、`setBlock` 记录 after 指纹、超时/退出封口） |
| 撤销 | `v2/V2UndoService`（提交、选择、限频 200ms、重入保护、RestoreMode）、`v2/UndoPlannerImpl`（预演：加载/冲突/实体存在性）、`v2/RestoreApplier`（应用 + 失败回写）、`v2/ConservationAuditImpl`（守恒审计） |
| 网络 | `v2/net/UndoRequestPayload`、`v2/net/UndoResultPayload`、`v2/net/V2ServerNet`、`client/v2/V2ClientNet` |
| 命令 | `command/UndoHistoryCommand`（list / dump / purge） |
| 事件 | `v2/V2Events`（`PlayerBlockBreakEvents.BEFORE/AFTER`） |

### 8.3 证据

```
build/gametest.xml
  ctrlz:pipeline_game_test_pipeline_boots_and_can_place_blocks        PASS
  ctrlz:slice0game_test_breaking_stone_can_be_undone_and_is_idempotent PASS
  ctrlz:slice0game_test_undoing_abreak_removes_the_dropped_item      PASS
  minecraft:always_pass                                             PASS
========= 4 GAME TESTS COMPLETE =========  All 4 required tests passed :)
BUILD SUCCESSFUL in 32s
```

`gradlew build`：BUILD SUCCESSFUL；JUnit **43 项 / 0 失败 0 错误**；产物 `ctrlz-1.0.0.jar`（273,235 字节）。

### 8.4 刻意偏差与已知边界（必须知道，否则会误判）

1. **掉落物回收是 slice 0 加的，不是遗漏。** 规格把"不含掉落物"列为 slice 0 之外，但挖石头→Z 若不删除掉落物，
   就是"方块回来 + 物品还在"的净复制，直接违反 I1（优先级最高）。所以本次记录了本操作产生的 `ItemEntity` 并在撤销时删除它；
   实体快照目前只存 UUID/类型/坐标，完整 NBT 恢复留给 slice 2。
2. **守恒审计是"应用后校验 + 失败回写"。** §7.4 希望审计也在预演内，但"删除实体后的物品总量"不落地就无法观测。
   因此：预演阶段取基线 → 应用 → 实测比对 → 不一致则 `RestoreApplier.revert` 回写并冻结该记录（`blocked`）。
3. **v2 下放置方块不会被记录**（slice 1 才做），所以此时按 Z 会去撤销更早的破坏记录。这是预期行为，不是 bug。
4. **v2 下 G / K 界面暂不可用**（slice 4 迁移），避免展示不属于当前引擎的数据。
5. **v2 历史目前只在内存**（slice 3 才接 `SavedData`），重启即丢；`HistoryState` 代码已就绪但未接线。

### 8.5 需要你在游戏里手动验证一次（B.3 的要求，我无法代替）

1. 关闭游戏，编辑 **`<.minecraft>/config/ctrlz.json`**（全局那个，不是存档里的），把 `"engine"` 改成 `"v2"`。
2. 启动，日志里应出现：
   - `CtrlZ engine source: <绝对路径> (engine=v2)`
   - `[ctrlz] Active undo engine: v2 (schemaVersion=1, history=0 entries)`
3. 进单人存档（**生存模式**），挖掉一块石头。
4. 按 **Z** → 石头应回来，ActionBar 显示 `已撤销 [v2]`。
5. 再按一次 **Z** → ActionBar 显示 `没有可撤销的操作 [v2]`，世界不变。
6. 挖一块泥土，按 Z → 泥土回来，**地上那块泥土掉落物应消失**（这条是防腐的关键）。
7. 挖一块石头，然后用命令/手动在同位置放别的方块，再按 Z → 应提示 `该区域已发生其他变化 [v2]`，世界不变。
8. `/undohistory` → 首行 `engine=v2`，随后列出记录。
9. 改回 `"engine": "legacy"` 重启 → 旧系统照旧工作（G/K 可用），`/undohistory` 会提示 v2 未启用。

请告诉我每条你**实际看到**了什么（尤其是 4/5/6/7），我会按结果修或进入 slice 1。

### 8.6 下一步：切片 1（加宽捕获）

放置方块 → 单箱/双箱 → 床/门 → 玩家背包；每加一类补 `Snapshot` 实现、守恒审计覆盖与 GameTest。
