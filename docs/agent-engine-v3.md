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

## 4. API（全部，共 16 个 + 状态栅栏 `ui.invalidate` + 窗口查询 `ui.windows`）

> 命名原则：读 `ui.*`、写 `ui.*`、运维 `sys/session/events/policy/dev`。
> 参数一律扁平 JSON；目标一律 `ref` 或 `where` 二选一。
> 所有读写方法额外接受 `backend: "auto"|"a11y"|"uia"|"shell"`（见 §8），响应带 `via` 与 `ms`。

### 4.1 读（5）

| 方法 | 作用 | 参数 |
|---|---|---|
| `ui.view` | **主读屏**：文本视图 + @ref，预算内；可选同次调用返回窗口/截图 | `maxChars`(2000) `mode`(auto/all/text) `depth`(3) `where` `windows`(false) `screenshot`(false) |
| `ui.nodes` | 机器可读 JSON | `where` `fields` `limit` `cursor` |
| `ui.diff` | 自 `since` 起的增量 | `since`(seq) |
| `ui.wait` | 服务端阻塞等待 | `for`{text|textGone|idle|ref|package|activity|keyboard} `timeoutMs`(8000) |
| `ui.windows` | 当前窗口栈（弹窗/IME/分屏/遮挡） | 无 |
| `ui.shot` | 截图，可标注 ref 框 | `ref` `region` `annotate` `maxBoxes` |

设备增强（APK only，不改变 `ui.*` 读写边界）：`notif.list / notif.history / notif.dismiss / notif.reply`；`ui.clipboard` 的 `op` 支持 `get / set / clear / history`。历史只存在进程内，不落盘，重启即清空。

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

> 状态栅栏：`ui.invalidate` 不是给模型常规调用的业务方法；它由 DSH facade 在带 `uiEffect` 的 shell 动作后调用，作废 APK 页面缓存与 ref 绑定。

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

## 8. 后端选择（a11y / uia / shell）

原则：**后端不由引擎替调用方决定**。引擎把"能力 + 当前可用性 + 代价"摊开，由 AI 按场景自己挑。

### 8.1 能力表（`sys.status.backends` 返回同款数据）

| 维度 | `a11y` | `uia` | `shell` |
|---|---|---|---|
| 读树 | 进程内，有稳定节点身份（sourceNodeId） | 跨进程 JSON，只有序号无身份 | 不做（需独占 uiautomator） |
| 点击/滑动 | 语义动作 + dispatchGesture | 坐标注入，绕过 a11y 手势限制 | `input tap/swipe`，最慢 |
| 文本输入 | ACTION_SET_TEXT，中文 OK | 点焦点 + 键事件，中文不行 | `input text`，仅 ASCII |
| 按键/全局 | performGlobalAction | injectKeyEvent | `input keyevent` |
| 截图 | takeScreenshot | takeScreenshot | `screencap` |
| 独占资源 | 无 | **全局单槽** | 无 |
| 需要 root | 否 | 是 | 是 |
| 典型延迟 | 20–80ms | 40–150ms | 15–150ms（截图约 700ms） |
| 特殊优势 | 节点身份、语义动作、可用性可探测 | 手势被拒时仍能生效；a11y 关闭也能用 | 前两条都挂时仍可用 |

### 8.2 选择

- 单次调用：`backend: "auto" | "a11y" | "uia" | "shell"`
- 整场会话：`session.open {backend:"uia"}`
- 全局默认：`policy.backend`（默认 `auto`）
- `auto` 的优先链按操作类别：
  - tap/long/swipe/scroll → a11y → uia → shell
  - setText/clear/append → a11y →（uia 点焦点后键注入）→ shell（仅 ASCII）
  - press/global → a11y → uia → shell
  - shot → a11y → uia → shell
  - 读树 → 永远 a11y（uia 无身份，仅用于 a11y 不可用时）

### 8.3 反馈

每个响应带 `via`、`ms`；失败额外带 `alternatives`，让 AI 一次就知道换谁：

```json
{"ok":false,"code":"BLOCKED","via":"a11y","ms":48,
 "alternatives":[{"backend":"uia","why":"手势未被接受，坐标注入可绕过","cost":"+40ms"},
                 {"backend":"shell","why":"input tap 一定能发出去","cost":"+300ms"}]}
```

### 8.4 扩展位

后端接口 = 能力声明 + read/act/query 三个方法。加新后端只需实现接口 + 在能力表加一行：

- `ime`：自建输入法广播，绕开拒绝 ACTION_SET_TEXT 的输入框（Portal 做法）
- `ocr`：树拿不到时（游戏 / Flutter canvas）截图 + OCR 出可点区域
- `shizuku`：root 不可用时用 shell 权限顶替

### 8.5 约束

- `uia` **不常驻**：空闲 60s 主动断开，把 UiAutomation 槽位还回去，避免我变成新的独占者。
- `shell` 只做兜底：`input` 进程启动实测 13–17ms、`dumpsys activity top` 63–90ms、`screencap -p` 约 700ms（raw 135ms）；文本仅 ASCII。延迟不构成把动作塞进 APK 的理由。

## 9. 断代式重构：删什么、留什么

**删（约 2,300 行）**
- `SessionStore.java`(998) 会话/树/旧 patch 启发式
- `AccessibilityBackend.java`(455) 旧 tier/verify 逻辑 → 由 A11yTreeSource 取代
- `SessionPersistence.java`(251)、`SessionNode.java`(215)
- `UiQuery.java`(240)、`UiSelector.java`(165)、`Where.java`(已迁到 agent-core)
- `AgentServer.java`(946) 旧 69 方法分发 → 重写为 v3-only 分发（现 ~806 行，保留 socket/token/幂等/事件基础设施）

**留（能力，不保留旧接口）**
- `SystemBridge`(469) 只留 `sys.status` 需要的只读字段（a11y 绑定/冲突/版本）；系统操作按 §12 走 Ubuntu shell
- `EventBus`(220) 事件环形缓冲（重新接线到 events.*）
- `RootClient`(194) root helper 客户端（17322）
- `FileBridge/MediaBridge/Jobs/Cron` 先留文件、不注册方法，等真需要再接

**验收（M2）**
`./gradlew :agent-core:test` 全绿 + APK 编译通过 + 真机跑通：
打开应用 → ui.view → ui.act(tap) → 看 diff → ui.batch 两步 → 回到桌面。

---

## 10. 第三方无障碍服务互斥（GKD）

Android 同一时刻允许多个无障碍服务，但两个"控制类"服务会互相打断（GKD 会自动点广告，抢焦点）。
约定：
- 我的测试窗口内，**GKD 临时关闭**（用户已同意）；
- `sys.status` 里暴露 `conflicts:["com.…gkd/…"]`，让我自己知道当前有谁在跑；
- 不做"与 GKD 共存"的设计——那是外壳的事，不是引擎的事。

---

## 11. 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| M1 | agent-core：Model/Snapshot/RefStore/Diff/View/Where + 14 个单测 | ✅ 完成（a5712e6） |
| M2 | A11yTreeSource + ApiServer（16 方法）+ 删旧类 | 🟢 代码完成（0744b2f 起，APK 已编出；待真机验收） |
| M3 | 打磨：遮挡、批量优化、events 接线、dev.selftest 场景脚本 | ⬜ |
| M4 | 能力边界收敛（§12）：ui.shot/ui.press/ui.ime 退出 APK；ui.expect/ui.gesture/notif.* 评估 | ⬜ |
| M5 | MCP facade（9 工具，APK/shell 混合路由）+ DSH `insert:` 补丁 | ⬜ |

---

## 12. 能力边界：什么才配进 APK（2026-10-29 定稿）

本节优先于 §4/§8 的方法清单；冲突以本节为准。判据四问，任一不过就不进 APK：

1. **只有 APK 拿得到？** 无障碍树/窗口/事件、NotificationListener、IME、多指手势——Ubuntu 没有对应系统调用。
2. **比 Ubuntu 更准？** 节点语义 vs 坐标；ACTION_SET_TEXT vs keymap；容器滚动 vs 盲滑。
3. **比轮询更省？** 事件驱动 wait/diff vs 每 250ms 重建。
4. **失败能解释？** STALE / AMBIGUOUS / BLOCKED 来自树，而不是“点了没反应”。

纯延迟不算：实测 `input` 启动 13–17ms、`dumpsys activity top` 63–90ms，只有 `screencap -p` 约 700ms（raw 135ms）例外。

### 12.1 留下（APK 独家）

| 能力 | Ubuntu 现状 | APK 为什么更好 |
|---|---|---|
| 读 UI 树（文本/结构/状态/多窗口/WebView/Compose） | `uiautomator dump` 被 system_server 占用（IllegalStateException）；`dumpsys window` 只有窗口名 | 唯一来源；节点带 text/desc/id/class/bounds/flags/window/depth |
| 语义动作 | 只有 `input tap/swipe` 坐标；文本仅 ASCII | 20+ 个 AccessibilityAction：CLICK / LONG_CLICK / SET_TEXT / SCROLL_FORWARD / SCROLL_BACKWARD / SCROLL_IN_DIRECTION / EXPAND / COLLAPSE / SELECT / SET_SELECTION / COPY / CUT / PASTE / DISMISS / IME_ENTER / FOCUS |
| 事件流 | 轮询 dumpsys/截图 | TYPE_WINDOW_STATE_CHANGED / WINDOW_CONTENT_CHANGED / VIEW_SCROLLED / VIEW_TEXT_CHANGED / NOTIFICATION_STATE_CHANGED / ANNOUNCEMENT，毫秒级，带 seq |
| 多指手势 | `input` 只有单指 tap/swipe/draganddrop/motionevent | GestureDescription 最多 **20 条 stroke**，可画曲线、双指缩放、图案 |
| 通知动作 | `cmd notification` 有 list/get/post/snooze，**没有 dismiss/reply** | NotificationListener：结构化内容、dismiss、RemoteInput 回复 |
| 剪贴板 | `cmd clipboard help` → “No shell command implementation” | ClipboardManager（前台/IME 身份）读写；节点级 COPY/PASTE |
| IME 兜底 | ADBKeyboard 广播要全局切输入法，中文输入会闪 | 自带 IME `commitText`，只影响当前焦点 |
| Toast / ANR / 权限弹窗 | 截图/日志事后发现 | 事件 + 窗口树实时识别 |

### 12.2 踢出（Ubuntu 更好）

| 能力 | 归属 | 理由 |
|---|---|---|
| 截图 | shell `screencap` | 文件落 /sdcard，chroot 直读；无需 16MB socket |
| 返回/Home/Recents/通知栏（独立方法） | shell `input keyevent` + `cmd statusbar` | 纯注入 15ms 就够；但保留为 `ui.act` 的 op，为的是同一响应里拿到 settle+diff |
| 启动/停止/安装/卸载/清数据 | shell `am` / `pm` | 支持 extras/flags/-W/多用户 |
| 设置/网络/亮度/音量/旋转/DND/时区 | shell `settings` / `svc` / `cmd` | 一步到位，可脚本 |
| 文件/录屏/媒体 | shell | root 直读；screenrecord 比 MediaProjection 省事 |
| 通知读取 | shell `cmd notification list/get` | 已够；只有“动作”才进 APK |
| 联系人/日历/SMS 数据 | shell `content query/insert` | ContentProvider 直读，不必点 UI |
| OCR | shell tesseract（chi_sim+eng） | 已有；APK 加 ML Kit 只多 20MB |
| 位置/传感器/电池/网络统计 | shell dumpsys | 只读 |
| 应用内数据（DB/SharedPreferences） | shell root | APK 根本做不到 |
| 坐标点击/滑动 | shell `input tap/swipe` | 有 ref 时不需要；无树时（游戏）单指够 |
| 截图标注/拼图 | shell + Python | 服务器侧更灵活 |

### 12.3 研究依据

- **AndroidWorld**：116 tasks / 20 apps；tag 分布 data_entry 34、screen_reading 24、search 22、information_retrieval 19、data_edit 19、complex_ui_understanding 18、repetition 12、multi_app 8、transcription 8、verification 7 → 主循环是“读屏→编辑→验证”，不是“点坐标”。
- **Mobile-Bench**（arXiv:2407.00993）：明确说 UI-only operations 限制效率，额外引入 103 个 API 加速任务 → 能走直连 API 的别走 UI；与本判据同向。
- **Appium**：uuid + stale 重找；**Portal/droidrun**：坐标 + 250ms 轮询 + 全量树；**mobile-mcp**：扁平 @ref 列表；**Maestro**：assertVisible / scrollUntilVisible / waitForAnimationToEnd → 分别对应 refs、diff、expect、scrollUntil、settle。
- **AOSP**：GestureDescription `MAX_STROKE_COUNT=20`；AccessibilityEvent 27 种 `TYPE_*`；AccessibilityService.getWindows / takeScreenshotOfWindow。

### 12.4 对 MCP 8 工具的修订

| 工具 | 后端 | 说明 |
|---|---|---|
| phone_view | APK | 读树（ui.view） |
| phone_act | APK | 语义动作 + settle + diff |
| phone_batch | APK | 多步动作 |
| phone_fill | APK | 多字段 setText / selection |
| phone_scroll | APK | 容器滚动 / scrollUntil |
| phone_wait | APK | 事件等待 |
| phone_shot | **shell** | `screencap` → /sdcard |
| phone_press | APK（act op） | `ui.act {op:"back|home|recents|notifications"}`，顺带 settle+diff；注入本身 shell 也行 |
| phone_launch（新增） | **shell** | `am start` / `pm` |
| phone_expect（M3） | APK | 断言（wait 的 0 超时版） |
| phone_gesture（M3） | APK | 多指 / 路径 |
| phone_notif（M3） | APK | 通知 dismiss/reply |

一个 facade 混后端，调用方不感知。

### 12.5 代码影响

- 不再接线：`SystemBridge`（只留 status 只读）、`FileBridge`、`MediaBridge`、`Jobs`、`Cron`。
- `UiEngine`：`ui.shot` 作为独立方法退出 APK（走 shell）；`ui.press` 不再单独存在，back/home/recents/notifications 变成 `ui.act` 的 op；`ui.ime(show|hide)` 退出，只保留“setText 被拒时的 commit”作为后手。
- 新增（M3）：`ui.expect`、`ui.gesture`、`notif.*`；`ui.act` 增加 scrollIntoView / setSelection / copy/paste / dismiss。
- `AgentServer` 的 `sys.*` 只保留 health（a11y 绑定、冲突、版本、端口），设备信息全部走 shell。
