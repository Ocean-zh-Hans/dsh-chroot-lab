# APK 引擎 v3.1（断代版）

状态：定稿草案（2026-10-29，feat/session-core）
决策：**本项目未上线，不做任何历史兼容。** 旧 69 个方法、旧 SessionStore 体系整体废弃，
只作为"方向建议"保留在 git 历史里。APK 的唯一消费者是 chroot 里的 dsh agent（我本人）。

---

## 0. 断代声明

- 传输层重来一遍也可以，但**保留** TCP 127.0.0.1:17321 + JSON Lines + token：这三样不是"兼容负担"，
  而是最省事的实现（一行一请求、一行一响应、本机回环、任意语言可写客户端）。
- 协议帧、方法名、参数形状**全部重新定义**，不迁就旧方法。
- 旧代码里唯一值得留的是"能力"（无障碍取树、手势、系统桥、事件源），命名与分层全部重写。

---

## 1. 消费者模型：我实际怎么用这台手机

```
读屏（要便宜） → 决定 → 动作（要一次成功） → 确认（要免费） → 下一步
```

三条硬约束：
1. **减少往返次数 > 省 CPU**：一次工具调用有固定开销。
2. **读屏 token 成本 > JSON 完整度**：上下文是有限预算。
3. **出错必须自带修复线索**：我看不见屏幕，只能读返回值。

旧 API 的六个痛点（作为设计反面清单）：全量树烧 token / 读写无原子性 / 动作后无反馈只能 sleep /
69 个方法参数各异 / 错误只有 code 没有候选 / 没有批处理。

---

## 2. 业界三派与我们

| 派别 | 代表 | 身份模型 | 缺点 |
|---|---|---|---|
| 句柄派 | Appium | 稳定 uuid + 元素缓存 + 失效重找 | uuid 太硬，文字一变就 STALE；树每次重建 |
| 坐标派 | Portal / droidrun | 250ms 轮询维护 index→rect，动作全坐标 | index 每次重建即失效，服务端不懂语义 |
| 流程派 | Maestro | YAML + 相对选择器 + 等待语义 | 给人写脚本，不是给 agent 单步调用 |

**我们的第四种：ref 弱句柄 + 服务端校验 + 动作后 settle/diff。**
ref 比 uuid 软（可打分重定位）、比 index 硬（不随重建失效）；动作直接把 diff 当返回值。

---

## 3. 核心对象

```
Page    一次树遍历得到的窗口快照
  g     代数：窗口/Activity/IME/包名变化时 +1
  seq   全局事件序号（单调，跨会话）
Node    不可变拷贝（bounds/文本/标志位/层级/nativeId）
Ref     "@e123"，会话内唯一，绑定 nativeId + 指纹
Session Page 序列 + ref 表 + 事件缓冲（默认一个 default 会话）
```

### 3.1 ref 解析（写路径核心）

```
1. nativeId 命中（windowId + sourceNodeId）且 refresh() 成功   → relocated=false
2. 指纹精确命中（cls+viewId+text+desc+bounds）                → relocated=false
3. 指纹打分（乘性证据：viewId/text/cls/距离/兄弟路径）
   唯一且 ≥ 0.72                                             → relocated=true
4. 多个候选 → AMBIGUOUS_MATCH（带 candidates + 分数 + why）
5. 无候选   → NOT_FOUND（给最近的 3 个候选 + why）
最终必须过 verify()：可见 / enabled / 包名一致 / bounds 相交 / 未被完全遮挡
```

铁律：**ref 永不猜。** 分数不够就报错给候选，宁可重读一次，也不在错元素上执行。

### 3.2 遮挡检测

同窗口内若有 z 序更高、矩形覆盖目标中心的节点：把点击点挪到未被覆盖的位置；
整块被盖 → `BLOCKED` + 遮挡者 ref。"点了没反应"的头号原因。

---

## 4. API（全部，共 16 个）

> 命名原则：读 `ui.*`、写 `ui.*`、运维 `sys/session/events/policy/dev`。
> 参数一律扁平 JSON；目标一律 `ref` 或 `where` 二选一。

### 4.1 读（5）

| 方法 | 作用 | 参数 |
|---|---|---|
| `ui.view` | **主读屏**：文本视图 + @ref，预算内 | `maxChars`(2000) `mode`(auto/all/text) `depth`(3) `where` |
| `ui.nodes` | 机器可读 JSON | `where` `fields` `limit` `cursor` |
| `ui.diff` | 自 `since` 起的增量 | `since`(seq) |
| `ui.wait` | 服务端阻塞等待 | `for`{text|textGone|idle|ref} `timeoutMs`(8000) |
| `ui.shot` | 截图，可标注 ref 框 | `ref` `region` `annotate` |

`ui.view` 输出（给人/模型看，不是 XML）：

```
# g=7 seq=4213 com.tencent.mm kb:off 1440x3168
@e1  ImageButton "返回" (0,120,96,216)
@e3  Input "说点什么" (24,2020,956,2130) [editable,focused]
@e4  Button "发送" (860,2020,1040,2130) [disabled]
# 另有 12 个节点未显示（超出预算，可加 where 过滤）
```

### 4.2 写（6）

| 方法 | 作用 |
|---|---|
| `ui.act` | 单动作：`op` + ref/where + args；自动 settle + diff |
| `ui.batch` | ≤20 个 op，一次往返，op 间复用活树 |
| `ui.fill` | 批量填表（where/label 定位，可选 submit） |
| `ui.scroll` | 滚动直到目标出现（`until` + `maxTimes`） |
| `ui.press` | 按键/全局：back/home/recents/notifications/enter |
| `ui.ime` | 输入法直通（setText 失败兜底） |

`op` 取值：`tap longClick setText appendText clearText focus scroll swipe drag press`

`ui.act` 返回（**设计核心**）：

```json
{"ok":true,"ref":"@e4","relocated":false,"op":"tap",
 "page":{"g":7,"seq":4219},
 "change":{"added":0,"removed":0,"changed":2,"text":["消息已发送"],"stable":true,"quietMs":96},
 "ms":212}
```

### 4.3 运维（5）

| 方法 | 作用 |
|---|---|
| `sys.status` | 健康状态：无障碍连接、前台包、g/seq、权限、冲突服务、版本 |
| `session.*` | `open/use/list/drop`（命名会话，默认 default） |
| `events.*` | `subscribe/poll/unsubscribe`（seq + bootId + resetRequired） |
| `policy.*` | `get/set`（只读门、确认门、危险动作白名单） |
| `dev.*` | `install/log.tail/selftest`（我自用闭环） |

---

## 5. 错误契约（13 码，固定）

`BAD_REQUEST NOT_FOUND AMBIGUOUS_MATCH STALE BLOCKED DISABLED NOT_EDITABLE TIMEOUT UNKNOWN CONFLICT UNAVAILABLE DENIED INTERNAL`

```json
{"ok":false,"code":"NOT_FOUND","retryable":true,
 "candidates":[{"ref":"@e31","text":"确定","score":0.61,"why":"同 viewId，bounds 偏移 84px"}],
 "hint":"界面已切换；重新 ui.view 并用 candidates 里最近的 ref"}
```

每个码带 `retryable` 与一句人话 `hint`。

---

## 6. 等待语义（消灭 sleep）

| 场景 | 旧做法 | v3 |
|---|---|---|
| 点击后等响应 | sleep + 重读 | settle（静默 120ms，上限 1.5s）+ diff |
| 等文本出现 | 轮询 find | `ui.wait {for:{text}}` |
| 等列表加载 | 固定 sleep | `ui.wait {for:{idle:true}}` |
| 等动画 | 不可靠 | settle 阈值 300–500ms |

---

## 7. 模块

```
agent-core/   纯 Kotlin JVM，零 android.* 依赖，PC 可单测
  Model / Snapshot / RefStore / Diff / View / Where / Query
agent-app/    Android 适配层
  A11yTreeSource.kt   唯一碰 AccessibilityNodeInfo 的地方（实现 TreeSource）
  GestureExecutor.kt  点击/滑动/输入
  ApiServer.kt        v3 协议（socket/token/幂等/事件）
  SystemBridge/EventBus/RootClient  保留能力，重新接线
console-app/  Compose 外壳（不动）
```

```kotlin
interface TreeSource {
    fun roots(): List<RawNode>
    fun refresh(handle: Long): RawNode?   // 单节点刷新，用于 verify
}
```

---

## 8. 断代式重构：删什么、留什么

**删（约 2,300 行）**
- `SessionStore.java`(998) 会话/树/旧 patch 启发式
- `AccessibilityBackend.java`(455) 旧 tier/verify 逻辑 → 由 A11yTreeSource 取代
- `SessionPersistence.java`(251)、`SessionNode.java`(215)
- `UiQuery.java`(240)、`UiSelector.java`(165)、`Where.java`(已迁到 agent-core)
- `AgentServer.java`(946) 旧 69 方法分发 → 重写为 ApiServer(~400)

**留（能力，不保留旧接口）**
- `SystemBridge`(469) 系统状态/电源/网络/设置
- `EventBus`(220) 事件环形缓冲（重新接线到 events.*）
- `RootClient`(194) root helper 客户端（17322）
- `FileBridge/MediaBridge/Jobs/Cron` 先留文件、不注册方法，等真需要再接

**验收（M2）**
`./gradlew :agent-core:test` 全绿 + APK 编译通过 + 真机跑通：
打开应用 → ui.view → ui.act(tap) → 看 diff → ui.batch 两步 → 回到桌面。

---

## 9. 第三方无障碍服务互斥（GKD）

Android 同一时刻允许多个无障碍服务，但两个"控制类"服务会互相打断（GKD 会自动点广告，抢焦点）。
约定：
- 我的测试窗口内，**GKD 临时关闭**（用户已同意）；
- `sys.status` 里暴露 `conflicts:["com.…gkd/…"]`，让我自己知道当前有谁在跑；
- 不做"与 GKD 共存"的设计——那是外壳的事，不是引擎的事。

---

## 10. 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| M1 | agent-core：Model/Snapshot/RefStore/Diff/View/Where + 14 个单测 | ✅ 完成（a5712e6） |
| M2 | A11yTreeSource + ApiServer（16 方法）+ 删旧类 | 🟡 进行中 |
| M3 | 打磨：遮挡、批量优化、events 接线、dev.selftest 场景脚本 | ⬜ |
