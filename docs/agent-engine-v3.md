# APK 引擎 v3 设计（面向"我自己"这个消费者）

状态：草案（2026-10-29，feat/session-core）。作者视角：chroot 里的 dsh agent 是本 APK 的唯一消费者。
结论先行：**不追求"通用自动化框架"，追求"LLM 一轮对话就能可靠驱动手机"**。

---

## 1. 消费者模型：我实际怎么用这台手机

我的每一次"操作手机"都是一次工具调用循环：

```
读屏（要便宜） → 决定 → 动作（要一次成功） → 确认（要免费） → 下一步
```

我在 chroot 里跑 bash/TS，一次工具调用有固定开销（进程 + 我的思考），所以**减少往返次数比减少 CPU 重要**；
我的上下文是有限预算，所以**读屏的 token 成本比 JSON 完整度重要**；
我不能"看着屏幕点"，所以**出错必须自带修复线索**。

### 现有 v2 API（69 个方法）的六个痛点

| # | 痛点 | 后果 |
|---|---|---|
| P1 | 读屏返回全量树（默认 2000 节点，几十 KB） | 一次读屏烧掉几千 token，被迫写 jq 过滤 |
| P2 | 读和写之间没有原子性，ref/matchKey 一变就 NOT_FOUND | 一次点击常要"重读→重点"两三轮 |
| P3 | 动作后不知道界面变没变，只能 sleep 或轮询 | 慢，且不稳 |
| P4 | 69 个方法各有各的参数形状 | 我记不住，容易调错 |
| P5 | 错误只有 code，没有候选和下一步 | 我只能整屏重读 |
| P6 | 没有批处理，连续动作要 N 次往返 | 填表这种操作要 10+ 次调用 |

---

## 2. 业界三派与我们的位置

| 派别 | 代表 | 身份 | 缺点（对我们） |
|---|---|---|---|
| 句柄派 | Appium | 稳定 uuid + 元素缓存 + 失效重找 | 面向测试，API 重，树每次重建 |
| 坐标派 | Portal / droidrun | 250ms 轮询维护 index→rect，动作全坐标 | index 每次重建即失效，服务端不理解语义 |
| 流程派 | Maestro | YAML + 相对选择器 + 等待语义 | 面向人写脚本，不是给 agent 单步调用 |

**我们要的第四种：ref 句柄 + 服务端校验 + 动作后 settle/diff。**
- ref 是"可失效但可修复"的弱句柄（Appium 的 uuid 太硬，Portal 的 index 太软）。
- 动作 = 解析 → 校验 → 执行 → 静默等待 → 差分回报（Maestro 有"等动画"，但没人把 diff 作为动作返回值）。
- 读屏输出是**给人/给模型看的文本**，不是给测试框架看的 XML（mobile-mcp 的 @ref 思路，但补上层级与稳定 ref）。

---

## 3. 核心对象

```
Page      = 某一时刻的窗口集合快照（一次树遍历）
  g       = 代数，窗口/Activity/IME 变化时 +1
  seq     = 全局事件序号（单调，跨会话）
Node      = 快照里的一个无障碍节点（不可变拷贝，含 bounds/文本/标志位）
Ref       = "@e123"，会话内唯一，绑定 nativeId + 指纹
Session   = 一个 Page 序列 + ref 表 + 事件缓冲（默认 1 个活跃会话，可命名多个）
```

### 3.1 Ref 解析（写路径的核心）

```
resolve(ref):
  1. nativeId 命中（windowId + sourceNodeId/uniqueId）且 refresh() 成功  → relocated=false
  2. 指纹精确命中（cls+viewId+text+desc+bounds）                          → relocated=false
  3. 指纹打分（权重：viewId .35 / text .25 / cls .15 / bounds 距离 .15 / 兄弟路径 .10）
     唯一且 ≥0.72                                                       → relocated=true
  4. 多个候选 → AMBIGUOUS_MATCH（带 candidates + 各自分数）
  5. 无候选   → NOT_FOUND（带最接近的 3 个 + why）
  最终必须过 verify()：可见、enabled、pkg 未变、bounds 与屏幕相交、未被完全遮挡
```

写死的规则：**ref 永不"猜"**。分数不够就报错并给候选，宁可让我重读一次，也不要在错误元素上执行。

### 3.2 遮挡检测（借 droidrun）

同一窗口内，若存在 z 序更高（后绘制 / 更大 index）、矩形覆盖目标中心的节点，则把点击点挪到未被覆盖的位置；
整块都被盖住 → `BLOCKED`，附遮挡者 ref。这是 Android 上"点了没反应"的头号原因。

---

## 4. API v3（对外的全部方法）

> 原则：**5 个读、6 个写、4 个运维**。其余 69 个旧方法全部保留为兼容层（内部走新引擎）。

### 4.1 读

| 方法 | 作用 | 关键参数 |
|---|---|---|
| `ui.view` | **主要读屏**：文本视图，带 @ref，预算内 | `maxChars`(2000) `mode`(auto/all/text) `where` |
| `ui.nodes` | 机器可读：筛过的节点 JSON | `where` `fields` `limit` `cursor` |
| `ui.diff` | 自 `since` 起的增量 | `since`(seq) |
| `ui.wait` | 服务端阻塞等待（文本/ref 出现或消失/空闲） | `for` `timeoutMs` |
| `shot` | 截图（可标注 ref 框） | `ref`/`region`/`annotate` |

`ui.view` 输出示例（这才是给我看的东西）：

```
# g=7 seq=4213 com.tencent.mm/.ui.LauncherUI  键盘:隐藏  焦点:@e3
@e1  ImageButton "返回" (0,120)-(96,216)
@e2  TextView "张三" (96,120)-(400,216)
@e3  EditText "说点什么" (24,2020)-(980,2130) [editable focused]
@e4  Button "发送" (860,2020)-(1040,2130) [disabled]
# 12 节点未显示（用 where 过滤或 mode:all）
```

规则：视觉顺序（top,left，再树序）；只输出有语义的节点（有文本/描述/可点/可编辑/可滚动/可勾选，或含语义子节点）；
最多 3 层缩进，更深折叠；同文本重复合并成 `"确定" ×3 @e5,@e9,@e11`；超预算截断并给 `omitted` 与建议。

### 4.2 写

| 方法 | 说明 |
|---|---|
| `ui.act` | **统一动作入口**：`op` + `target` + `args`；动作后自动 settle + diff |
| `ui.batch` | 一次往返执行 ≤20 个 op，op 间复用活树 |
| `ui.fill` | 按标签/提示填多个输入框（可选 submit） |
| `ui.scrollUntil` | 服务端循环滚动直到出现目标 |
| `ui.key` / `ui.global` | 按键 / 全局动作（back/home/recents/notification） |
| `ime.*` | 输入法直通（`setText` 失败时的兜底） |

`op` 取值：`tap long double setText append clear scroll swipe drag fling press focus`。

`ui.act` 返回（**这是整个设计的关键**）：

```json
{"ok":true,"ref":"@e4","relocated":false,"op":"tap",
 "page":{"g":7,"seq":4219},
 "change":{"added":0,"removed":0,"changed":2,"text":["消息已发送"],"stable":true,"quietMs":96},
 "ms":212}
```

—— 我点完就知道：界面稳定了、出现了"消息已发送"、只变了 2 个节点。**不需要再读屏。**

### 4.3 运维

`session.open/close/list`、`events.subscribe/wait`、`policy.get/set`、`backend.status`。
开发自用（我编 APK 时的闭环）：`dev.install`（装 APK + 可选拉起）、`log.tail`（logcat 过滤）。

---

## 5. 错误契约

```json
{"ok":false,"code":"NOT_FOUND","retryable":true,
 "message":"找不到 @e12",
 "candidates":[{"ref":"@e31","text":"确定","score":0.61,"why":"同 viewId，bounds 偏移 84px"},
               {"ref":"@e44","text":"确定","score":0.58,"why":"同文本，不同窗口"}],
 "hint":"界面已切换；重新 ui.view 并用 candidates 中最近的 ref"}
```

码表（固定，不再扩张）：`BAD_REQUEST NOT_FOUND AMBIGUOUS_MATCH STALE BLOCKED DISABLED NOT_EDITABLE TIMEOUT UNKNOWN CONFLICT UNAVAILABLE DENIED INTERNAL`。
每个码都带 `retryable` 与一句 `hint`。**hint 是给人看的，不是给日志看的。**

---

## 6. 等待语义（消灭 sleep）

| 场景 | v2 做法 | v3 做法 |
|---|---|---|
| 点击后等界面响应 | 客户端 sleep + 重读 | 动作内置 settle（静默 120ms，上限 1.5s），结果带 diff |
| 等某文本出现 | 轮询 `ui.find` | `ui.wait {for:{text:"成功"}, timeoutMs:5000}` 服务端阻塞 |
| 等列表加载完 | 固定 sleep | `ui.wait {for:{idle:true}}`（连续 2 个 250ms 窗口无内容变化） |
| 等动画 | 不可能可靠 | `settle.quietMs` 调到 300–500ms |

---

## 7. 架构与模块

```
agent-core/        Kotlin JVM，纯逻辑，零 android.* 依赖 → PC 上可单测
  model/    Node.kt Snapshot.kt Ref.kt Errors.kt
  json/     Json.kt（kotlinx.serialization，无 Android 依赖）
  select/   Where.kt Relative.kt Score.kt
  view/     ViewRenderer.kt（文本视图 + 预算）
  ref/      RefStore.kt Resolver.kt
  diff/     Diff.kt MatchKey.kt
  batch/    BatchRunner.kt
  policy/   Gates.kt（只读门/确认门）
agent-app/         Android 侧（现有模块）
  A11yTreeSource.kt   —— 实现 TreeSource 接口，唯一碰 AccessibilityNodeInfo 的地方
  GestureExecutor.kt  —— 点击/滑动/输入
  SocketServer.kt     —— 保留 17321 JSON Lines 协议
  Legacy.kt           —— 旧 69 方法的兼容入口
console-app/       Compose 外壳（不动）
```

**关键接口**（让树逻辑可测）：

```kotlin
interface TreeSource {                 // Android 侧实现；测试用假树
    fun roots(): List<RawNode>
    fun refresh(handle: Long): RawNode?  // 单节点刷新，用于 verify
}
```

---

## 8. 迁移路线（三个阶段，每阶段都可独立交付）

| 阶段 | 内容 | 验收 |
|---|---|---|
| M1 | `agent-core` 落地：Node/Snapshot/Where/Ref/View/Diff + 单测（用真机 dump 的 fixture） | `./gradlew :agent-core:test` 全绿 |
| M2 | Android 侧接新引擎：`ui.view/ui.nodes/ui.diff/ui.wait/ui.act/ui.batch`；旧方法转发 | 真机跑通 12 个典型场景脚本 |
| M3 | 删除死代码（patch 启发式、旧 SessionStore 巨类），补幂等落盘、遮挡检测、ref 打分重定位 | 旧客户端脚本不改一行仍可用 |

不可回退的约束：socket 协议（JSON Lines / 1 MiB / 16 MiB）、token 鉴权、`events.*` 帧格式、`idempotencyKey` 语义。
