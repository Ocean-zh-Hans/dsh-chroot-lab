# Agent APK 下一阶段计划（2026-10-30）

状态：M3 代码与 targetSdk 29 平台档已落盘；Phase 1 `DeviceSession` 纯逻辑、Phase 2 APK 状态协议已落盘并通过单测。`:agent-core:test`、`:agent-app:testDebugUnitTest` 通过；未做真机验收。本文件是下一阶段的执行计划；API/错误契约以 `agent-engine-v3.md` 为准，本文件补充**跨后端连贯性**与**人类接口**。

## 0. 不可违背的前提

1. **AI 操控手机时，目标 App 必须在前台。** APK 只能是后台的 `AccessibilityService` / `NotificationListenerService`，不能启动自己的 Activity 参与操作流程。
2. **除接管页外，APK 的 Activity 一出现，就视为人类接管。** `MainActivity` / `HandoffActivity` 在前台时，`DeviceSession` 必须暂停 agent 动作；`ui.view` 发现前台包名是 `dev.dsh.agent` 时直接拒绝。交接页是唯一例外，且它出现前 agent 已进入 `PAUSED_FOR_HUMAN`。
3. **默认不使用悬浮窗。** 悬浮层会污染无障碍树、进入截图，并触发目标 App 的 `FLAG_WINDOW_IS_OBSCURED` 检测；银行类 App 会直接拒绝触摸。只允许 opt-in 的极简状态指示器，不承载交接或回执。
4. **人类接口是系统表面，不是 App 内的实时面板。** 常驻通知、通知动作、快捷设置磁贴、锁屏通知、半透明接管页，是主要载体。

## 1. 目标与非目标

### 目标

- 一个 `DeviceSession` 把 APK（读屏/手势/输入）与 Ubuntu shell（`am/pm/content/文件/包管理`）合成**同一会话**，模型只面对语义动作，不面对后端选择。
- 跨后端动作不丢页面状态：A（APK）→ B（Ubuntu）→ C（APK）后仍能继续，不要求模型手动重读屏。
- APK 对人的存在方式是“安静的器官”：平时不可见，需要人时用通知叫回来，事后给回执。
- 保持 APK 的 `ui.shot` / `ui.press` / `ui.ime` 作为 fallback，不因 Ubuntu 能做就删除。

### 非目标

- 不再做 App 内的实时 dashboard、交接收件箱、实时回执流。
- 不做另一个 DSH 面板、聊天页、文件管理器或终端。
- 不让 APK 申请 root；root helper 仍是显式安装、独立授权的可选件。
- 不把“跨后端编排”做成宏语言；只做带检查点的步骤序列。

## 2. 架构

```text
DSH（chroot 内的头脑）
  └─ phone.* MCP facade
       └─ DeviceSession
            ├─ UiBackend      APK JSONL socket（无障碍、手势、IME、通知、剪贴板）
            ├─ ShellBackend  Ubuntu shell（am/pm/content/包/文件/系统设置）
            └─ StateStore    g/seq、前台、键盘、剪贴板、lease、handoff
```

### 2.1 核心契约

- `Observation`
  - `sessionId`, `page {g, seq}`, `pkg`, `activity`, `keyboard`, `nodes`, `shot?`, `at`, `source`
  - `page` 是后续动作的期望代次；旧 `ref` 只能在同一 `page` 内使用。
- `Action`
  - `op`, `ref?`, `where?`, `text?`, `via`, `uiEffect`, `timeoutMs`, `idempotencyKey?`
  - `uiEffect`: `none` / `possible` / `certain`。Shell 动作若可能改变 UI，必须标 `possible` 或 `certain`。
- `StateStore`
  - 记录 `g/seq`、前台包/Activity、键盘状态、剪贴板摘要、当前 lease、handoff 状态。
  - 任何 `uiEffect != none` 的动作完成后：invalidate `page` → 重新 capture → 重映射 `ref` → 返回新 `Observation`。
- `Lease`
  - `agentId`, `scopes`（`ui.read`, `ui.act`, `text`, `notify`, `clipboard`, `shell`）, `expiresAt`, `apps?`, `readonly?`
  - 人类可随时 revoke；敏感 scope 需要解锁确认。
- `Handoff`
  - `RUNNING → PAUSED_FOR_HUMAN → HUMAN_CONTROL → RESUMING → RUNNING`
  - 交接期间保留 `page token`；人完成后先 refresh，再恢复 agent。
- `Receipt`
  - `taskId`, 步骤摘要, before/after, 脱敏字段, 失败点, 时间
  - 写本地 `receipts.jsonl`，任务结束发通知；App 只在 idle 时展示。

### 2.2 连贯性规则

1. **模型不选后端。** `phone.open(pkg)` 内部可以是 shell `am start` + APK `ui.wait`；`phone.attach(path)` 内部可以是 Ubuntu 放文件 + 目标 App 的 picker。
2. **跨后端动作必须带状态栅栏。** Shell 动作返回 `uiEffect`；facade 在下一步 APK 动作前自动 refresh，不把 stale ref 交给模型。
3. **复合动作按语义打包。** 先做 `phone.open`、`phone.attach`、`phone.fill`、`phone.do(steps)` 四个；不做通用宏。
4. **APK 的 Activity 不进入动作链。** `attach` 不能用 APK 自己的文件选择器；只能把文件放到目标 App 可访问的位置，再操作目标 App 的 picker。
5. **fallback 不是洁癖。** 同一语义方法可以有 `preferred` 与 `fallback` 两个后端；当动作处在 UI 回路中间时优先 APK，避免切后端造成页面状态变化。

### 2.3 会话连续性：shell/REPL 不是一次性 exec

AI 反复执行 `r2 -A xxx; axt xxx` 不是模型笨，而是工具接口无状态：每次调用都新起进程，丢失 cwd、环境变量、alias、分析结果、断点、数据库连接和交互式程序状态。APK 侧的 `DeviceSession` 解决的是 UI 页面状态；DSH 的 shell/process 层也必须同样有会话。

最小协议：

- `session.open({kind: "pty" | "pipe", cmd, cwd, env, rows, cols}) -> {sessionId}`
- `session.exec({sessionId, input, timeoutMs, yieldMs}) -> {cursor, output, running, exitCode}`
- `session.read({sessionId, cursor}) -> 增量输出；不要每次回传完整 transcript`
- `session.signal({sessionId, signal})`
- `session.close({sessionId})` / `session.list()`

约束：

- 输出用 cursor + 环形缓冲；大输出转 artifact ref，模型按需读取。
- session 绑定 task/lease；跨任务默认不可见，超时或任务结束清理。
- 交互式工具优先 PTY；r2 用 `r2pipe` 或持久 `r2 -q0`，tmux/abduco 可作为 DSH 重启后的存活层。
- `phone.do` 的跨后端步骤可以组合 `shell session` 与 `DeviceSession`，但两者各自保留自己的 generation/session id，不互相冒充。

## 3. API 面（计划）

| 方法 | 作用 | 后端 |
|---|---|---|
| `phone.observe` | 统一读屏/截图/前台/键盘 | APK 为主，shell 补前台 |
| `phone.act` | tap/longClick/setText/append/clear/focus/scroll/swipe/drag/global/copy/cut/paste/dismiss/scrollIntoView/setSelection | APK 为主，shell fallback |
| `phone.fill` | 聚焦、填值、提交、settle、校验的语义封装 | APK + 目标 App |
| `phone.expect` | 0 超时断言/候选 | APK |
| `phone.gesture` | 多指手势与预设 | APK |
| `phone.notify` | list/dismiss/reply（RemoteInput） | APK |
| `phone.clipboard` | get/set/clear | APK |
| `phone.open` | 启动应用/Activity 并等待页面 | shell + APK |
| `phone.attach` | 目标 App 的附件/文件选择流程 | shell + APK |
| `phone.do` | 带 `expect` 检查点的跨后端步骤序列 | DeviceSession |
| `phone.handoff` | 请求人类接管/查询/恢复 | DeviceSession + 通知 |
| `phone.receipt` | 查询任务回执 | 本地存储 |
| `phone.lease` | 授权/撤销/状态 | DeviceSession |

## 4. 实施阶段

### Phase 0：清账与平台档（本次）

- 归档 v2 Python 客户端与旧协议文档到 `docs/archive/agent-v2/`。
- 新增本计划；更新 `docs/README.md`、`docs/archive/README.md`、`agent-app/README.md`。
- 平台档已落地：`targetSdk=29`；新增 `PlatformProfile` 统一判断通知/无障碍/定位能力；`sys.status` 已接入。
- APK 不再持有共享存储：`FileBridge` 与 `AgentFileProvider` 已归档到 `docs/archive/agent-v2/legacy-apk/`，`MainActivity` 不再请求 `READ/WRITE_EXTERNAL_STORAGE`，文件面交给 DSH shell。`requestLegacyExternalStorage` 已移除。
- 仅编译与单测，不安装真机。

### Phase 1：agent-core 的 DeviceSession 纯逻辑（已完成）

- `agent-core/src/main/kotlin/dev/dsh/agent/core/DeviceSession.kt` 已新增 `Observation`、`Action`、`StateStore`、`Lease`、`Handoff`、`Receipt` 与 `DeviceSession` 状态机。
- `DeviceSessionTest` 覆盖：`uiEffect` 触发 invalidate、旧 `g` 拒绝、跨后端 refresh、handoff 状态迁移、lease 过期/撤销、readonly 拒绝、回执步骤。
- 不依赖 Android；`:agent-core:test` 通过。

### Phase 2：APK 协议补状态（已完成）

- `ui.view` / `ui.act` 以及各写接口返回统一 `page {g, seq}` 与 `uiEffect`（`none/possible/certain`）；`ui.batch` 的每个 op 也带 `uiEffect`。
- 新增 `ui.invalidate`：外部 shell 动作后作废 APK 页面缓存、清空 ref 绑定并推进 `g`；下一次 `ui.view` 重新 capture。
- `sys.status` 暴露内部/当前 `g`、`seq`、`foreground {package, activity}`、通知监听状态、无障碍状态和平台能力。
- 通知监听/剪贴板/手势沿用 M3 实现；不新增 Activity 到动作链。

### Phase 3：DSH 侧 MCP facade

- 重写/新增 v3 客户端（旧 `dsh_agent.py` 只作归档参考），实现 `phone.*`。
- 接入 `DeviceSession`：APK 与 shell 的 action 都经过同一个 session；shell 动作后自动 refresh。
- `phone.open`、`phone.attach`、`phone.do` 先做最小闭环。
- 当前状态：`module/control/dsh/phone-mcp/phone_mcp.py` 已落盘（纯标准库 MCP stdio server），实现 `observe/act/fill/expect/wait/gesture/notify/clipboard/open/status/handoff/receipt/do`；`phone.open` 走 shell 启动 + `ui.invalidate` + `ui.wait`，不创建第二个 APK Activity。`module/control/dsh/runtime.patch.yml` 由 `dsh-run` 无条件叠加，因此模块安装后 `mcp__phone__*` 不依赖 audit hook 是否存在。`phone.attach` 与 `phone.do` 的 shell 步骤尚未实现；未做真机验收。DSH runtime 只改控制面配置，不修改 chroot rootfs。

### Phase 4：人类 ambient surface

- 常驻通知：`DSH 正在操作 <app> · 时长` + `暂停` / `停止` / `查看`。
- 交接：高优先级通知 → 半透明 `HandoffActivity`（`excludeFromRecents`）→ 人处理 → 恢复。
- 快捷设置磁贴：暂停/继续；App 打开时自动进入 `PAUSED_FOR_HUMAN`。
- 回执：`receipts.jsonl` + 任务完成通知 + App 内事后查看。
- 在场胶囊是 opt-in 的极简状态指示器：`TYPE_ACCESSIBILITY_OVERLAY`、`FLAG_NOT_TOUCHABLE`、无焦点、不进入 Capture；不承载交接/回执，也不接收点击；随 `AgentRuntimeService` 前台保活开启。
- 出现/隐藏语义：首次 `ui.*` / `notif.*` / `handoff.*` 请求进入“任务会话”；会话期间一直显示，AI 思考时不因单个动作结束而消失；`phone.done` 后显示完成态 8 秒再隐藏；空闲/停止时隐藏。打开 App 查看状态不暂停 AI，只有明确的交接/暂停才暂停。
- 状态栏使用 edge-to-edge 沉浸式布局；胶囊只位于状态栏中心，不覆盖操作区域。
- 当前状态：`PresenceState` / `PresenceOverlay` / `PresenceNotification` / `PresenceReceiver` / `HandoffActivity` / `PresenceTileService` / `ReceiptStore` 已落盘；`AgentRuntimeService` 与 Manifest 已接线，`:agent-app:compileDebugKotlin`、单测、`assembleDebug` 通过；未做真机验收。

### Phase 5：真机验收

- 通知回复（RemoteInput）、通知 dismiss、双指缩放、密码框脱敏。
- `phone.open`：shell 启动 + APK 等待，返回同一页。
- `phone.attach`：Ubuntu staging + 目标 App picker，APK 全程后台。
- 跨后端 A→B→C：同一 session，旧 ref 返回 `STALE_PAGE` 并自动刷新。
- 人打开 App：100ms 内暂停；人离开后显式继续才恢复。
- 失败时回执保留，不丢最后一张好页面。

## 5. 验收标准

- 模型调用 `phone.*` 时不需要知道 APK/Ubuntu 的边界。
- 任一 shell 动作改变 UI 后，下一步 APK 动作不会拿到过期 ref。
- `phone.open` 一次调用返回可用页面，而不是“已请求启动”。
- `phone.attach` 不把 APK 的 Activity 带到前台。
- 密码字段在 `ui.view` / `ui.diff` / `ui.nodes` 中均为 `••••`。
- 打开 `MainActivity` 后 agent 停止动作；无 `dev.dsh.agent` 自操作。
- 交接通知可从锁屏看到；敏感操作要求解锁。
- 默认只启用不可触摸的在场胶囊；所有需要点击的人类交互仍在通知/锁屏/接管页完成。

## 6. 风险与待定

- 跨后端 `am start` 的页面恢复可能重置目标 App 任务栈；需要 `phone.open` 的 task/flag 策略实测。
- 目标 App 的文件 picker 可能使用系统 DocumentsUI 或自绘 UI；`phone.attach` 需要分别处理。
- 通知回复 RemoteInput 在不同 ROM/通知样式下兼容性未验证。
- 无障碍截图与 shell 截图各有时间差；`phone.observe` 如需同帧 tree+shot，优先 APK 同次 capture。
- 多 agent lease 的冲突仲裁先做单 agent + 人优先，不先做复杂调度。

### 6.1 平台与权限契约（Phase 0 gate）

Android 权限不是一张静态清单，而是 `(capability, sdkInt, targetSdk, grantState)` 四元组；同一个“存储权限”在 API 29、30–32、33–35 上是三种不同实现。

| 能力 | API 29 | API 30–32 | API 33–35 |
|---|---|---|---|
| 共享存储读写 | 不属于 APK；由 DSH shell 处理 | 不属于 APK；由 DSH shell 处理 | 不属于 APK；由 DSH shell 处理 |
| 通知 | 无运行时权限 | 无运行时权限 | `POST_NOTIFICATIONS`（33+） |
| 前台服务类型 | 两参 `startForeground` | 两参 `startForeground` | API 34+ 使用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` |
| 包可见性 | 默认可见 | `QUERY_ALL_PACKAGES` | `QUERY_ALL_PACKAGES` |
| 截图 | `UiAutomation`/shell | `AccessibilityService.takeScreenshot`（30+） | 同 API 30 |
| 剪贴板后台读 | Android 10+ 默认限制 | 默认限制 | 默认限制；声明权限也未必可用 |

规则：

1. **APK 不申请共享存储。** `FileBridge` / `AgentFileProvider` 已归档；`MainActivity` 不再请求 `READ/WRITE_EXTERNAL_STORAGE`。文件读写、分享、媒体由 DSH shell 处理；平台矩阵只用于 shell/attach 侧判断。
2. **运行时只认 `PlatformProfile`，不要把 `Build.VERSION.SDK_INT` 散落在业务代码里。** 每个能力注册 `minSdk/maxSdk`、`check()`、`fixIntent()`、`fallback`；`sys.status.capabilities` 返回 `available/denied/unsupported` 与修复入口。
3. **APK 只使用应用私有目录，不内置 FileProvider，不申请 `MANAGE_EXTERNAL_STORAGE`。** 需要把文件交给其他 App 时，由 DSH shell 放到目标 App 可读的位置，再走目标 App 的 picker。
4. **权限缺失必须是结构化错误，不是崩溃。** `checkSelfPermission`、`Environment.isExternalStorageManager`、`NotificationManager.areNotificationsEnabled`、`AccessibilityManager`、`Settings.Secure` 的检查统一走 `PermissionGate`，返回 `PERMISSION_REQUIRED` / `UNSUPPORTED`，并带 `fix`（Settings action + package）。
5. **人类授权向导按 API 生成。** Android 10 显示读写存储；Android 11–12 显示“所有文件访问”；Android 13+ 不能只显示“媒体权限”；Android 14+ 的前台服务类型单独检查。通知权限被拒时，presence 改用快捷设置磁贴或明确的“无通知”状态，不能静默假装在线。
6. **验收矩阵至少覆盖 API 29 / 30 / 31 / 33 / 34 / 35 模拟器 + 目标机 Android 15**，并测试授权、拒绝、运行中撤销、重启后恢复。

### 6.2 targetSdk 选择：私有兼容档

`targetSdkVersion` 不是商店合规问题，而是 Android 的行为契约。既然本项目是私有侧载，可以显式选择 **`targetSdk=29`** 作为行为兼容档（APK 不申请共享存储，因此不使用 `requestLegacyExternalStorage`），换取：

- 降低通知权限、前台服务类型、包可见性等行为差异；不改变“APK 不持有共享存储”的结论。
- 旧的通知权限、前台服务类型、包可见性、`PendingIntent` 可变性等行为。

但这不是“绕过版本”的银弹：

- `targetSdk` 只改变行为兼容，不改变 API 是否存在。`Environment.isExternalStorageManager()`、`AccessibilityService.takeScreenshot()` 等仍要按 `SDK_INT` 判断；target 29 不会把 Android 30+ API 带回 Android 10。
- Android 15 与 OEM 是否仍完整尊重 legacy 存储必须真机验证；若不尊重，文件面仍然只能走 DSH shell/SAF。
- 不因 target 29 恢复 `FileBridge` / `AgentFileProvider` 或共享存储权限；APK 仍只做后台器官。
- 如果未来切回 `targetSdk=35`，只需调整平台行为判断，不改变文件面归 shell 的架构。
- 已落地：`agent-app/build.gradle.kts` 与 `AndroidManifest.xml` 均为 target 29；`PlatformProfile` 统一能力状态；`sys.status.platform` 返回 `sdk/targetSdk/storageMode/capabilities`。

**当前推荐**：默认 target 29 只作为 Android 行为档；APK 不持有共享存储，`fs.*` 全部由 DSH shell 处理。这样 target 29 不会把“全盘文件访问”重新变成 APK 依赖。

## 7. 文件映射

- `docs/agent-engine-v3.md`：v3 API、错误码、后端能力表。
- `docs/agent-capability-matrix.md`：APK/Ubuntu 能力边界，本文在其上补“交互回路”。
- `docs/agent-plan.md`：本计划。
- `agent-app/src/dev/dsh/agent/`：APK 实现；`PlatformProfile.kt` 是平台能力/权限的唯一判断入口。
- `docs/archive/agent-v2/`：v2 客户端与旧协议，只作历史参考。
