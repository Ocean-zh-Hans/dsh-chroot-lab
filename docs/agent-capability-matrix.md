# Agent 能力矩阵：什么进 APK，什么留在 Ubuntu

> 状态：2026-10-29 定稿（与 docs/agent-engine-v3.md §12 同源；冲突时以本表 + §12 为准）
> 范围：dsh-chroot-lab 的 agent-app、agent-root-helper、chroot 内 shell、未来的 MCP facade
> 读者：实现者、验收者、以及 chroot 里的 DSH agent（唯一消费者）

## 0. 一句话结论

**APK 只做“眼 + 手”：读 UI 树、语义动作、事件流、多指手势、通知动作、剪贴板、IME 兜底、Toast/ANR/权限弹窗识别。**
截图、应用管理、系统设置、文件、联系人/日历/SMS、OCR、传感器/位置、应用内数据一律走 Ubuntu shell/root；坐标点击只在无树场景兜底。
判据不是“能不能做”，而是“是否只有 APK 拿得到、是否比 Ubuntu 更准、是否比轮询更省、失败是否能解释”。纯延迟不算理由（input 启动实测 13–17ms）。

## 1. 判据与决策规则

任一能力进入 APK 前，依次回答四问：

1. **只有 APK 拿得到？** 无障碍树/窗口/事件、NotificationListener、IME、多指手势、节点级语义动作——Ubuntu 没有对应系统调用。
2. **比 Ubuntu 更准？** 节点语义 vs 坐标；ACTION_SET_TEXT vs keymap；容器滚动 vs 盲滑。
3. **比轮询更省？** 事件驱动 wait/diff vs 每 250ms 重建全树。
4. **失败能解释？** STALE / AMBIGUOUS / BLOCKED 来自树和句柄校验，而不是“点了没反应”。

补充规则：

- 同一能力两条路都能做时，选**调用次数少、可脚本化、失败可诊断**的一侧；不按实现难度选。
- 纯延迟不构成 APK 理由：shell input 13–17ms、dumpsys activity top 63–90ms；只有 screencap -p 约 700ms（raw 135ms 可优化）。
- 需要 root 的能力优先放 root helper/chroot，不把 root 逻辑塞进普通 App。
- 任何“读屏”能力都必须默认脱敏密码字段；任何“写”能力都必须可被 policy.readonly 拦截。

## 2. 三个执行面

| 执行面 | 位置 | 能力 | 约束 |
|---|---|---|---|
| APK / AccessibilityService | agent-app，端口 17321 | 树、语义动作、事件、手势、通知动作、剪贴板、IME commit | 需要用户启用无障碍；不能读应用私有数据；密码必须脱敏 |
| root helper | agent-root-helper，端口 17322 | UiAutomation 第二发、input 注入、screencap 兜底、shell.exec、screenrecord | root；uia 是全局单槽，空闲 60s 释放；shell 仅 ASCII 文本 |
| Ubuntu / chroot | shell、Python、tesseract、content providers | 截图、应用生命周期、设置、文件、联系人/日历/SMS、OCR、传感器/位置、应用数据 | 需要 adb/root 或 content 权限；不产生 UI 语义 |

MCP facade 在 chroot 内做路由，调用方只看见电话工具名，不感知后端。

## 3. 能力矩阵

状态说明：**已实现** = M2 代码可用；**过渡** = 当前有接口但 M4 要删/改名；**计划** = M3/M4 实现；**不做** = 明确留给 shell/root。

### 3.1 读（眼）

| ID | 能力 | 典型场景 | 目标入口 | 执行面 | 状态 | 依据/备注 |
|---|---|---|---|---|---|---|
| R1 | 当前页面文本视图 | 看屏幕、找按钮 | ui.view | APK a11y | 已实现 | 默认 maxChars=2000，按视觉顺序，@ref |
| R2 | 结构化节点 JSON | 程序化筛选、调试 | ui.nodes | APK a11y | 已实现 | where/fields/limit/cursor |
| R3 | 增量 diff | 动作后确认变化 | ui.diff | APK a11y | 已实现 | 按 ref 连续性算 added/removed/text/desc/checked/enabled/bounds |
| R4 | 阻塞等待 | 等文本出现/消失/空闲 | ui.wait | APK a11y | 已实现 | for.text/textGone/idle/ref；默认 8s |
| R5 | 断言 | 验收“应该看到 X” | ui.expect | APK a11y | 计划 M3 | 等价于 timeout=0 的 wait；失败带候选 |
| R6 | 页面元信息 | 判断前台包/Activity/键盘/尺寸 | ui.view 头部、sys.status | APK a11y | 已实现（activity 待修） | pkg/activity/kb/WxH/g/seq；activity 目前可能为空 |
| R7 | 多窗口/分屏/悬浮窗/系统弹窗 | 弹窗遮挡、分屏操作 | Capture 内部 | APK a11y | 部分实现 | 需按 research/01 修 root resolver、预算、去重、recycle |
| R8 | 截图 | 视觉复核、OCR | phone_shot / shell | shell screencap | 不做进 APK | ui.shot 当前在 M2，M4 移除；raw 135ms 可优化 |
| R9 | 屏幕尺寸/密度/旋转 | 坐标换算 | shell wm size/density | shell | 不做 | 一次调用可缓存 |
| R10 | 通知列表/内容 | 读通知 | shell cmd notification | shell | 不做 | 只有通知“动作”进 APK |
| R11 | 前台应用/Activity | 确认当前 App | sys.status / dumpsys | 混合 | 已实现（部分） | a11y 事件可靠；shell dumpsys 兜底 |

### 3.2 写（手）

| ID | 能力 | 典型场景 | 目标入口 | 执行面 | 状态 | 依据/备注 |
|---|---|---|---|---|---|---|
| W1 | 点击（按 ref/where） | 点按钮/条目 | ui.act {op:tap} | APK a11y | 已实现 | ACTION_CLICK 优先，失败坐标兜底；自动 settle+diff |
| W2 | 长按 | 菜单/多选 | ui.act {op:longClick} | APK a11y | 已实现 | ACTION_LONG_CLICK；坐标兜底 |
| W3 | 设置文本 | 输入框填值 | ui.act {op:setText} | APK a11y | 已实现 | ACTION_SET_TEXT，中文 OK；失败走 IME commit |
| W4 | 追加/清空文本 | 连续编辑 | ui.act {op:appendText/clearText} | APK a11y | 已实现 | 读原值再 SET_TEXT；clearText 空串 |
| W5 | 聚焦 | 唤起输入法 | ui.act {op:focus} | APK a11y | 已实现 | ACTION_FOCUS |
| W6 | 选中/复制/剪切/粘贴 | 文本编辑 | ui.act {op:setSelection/copy/cut/paste} | APK a11y | 部分/计划 M3 | 节点级 AccessibilityAction；全局剪贴板另见 W13 |
| W7 | 容器滚动 | 列表翻页 | ui.scroll | APK a11y | 已实现 | 默认第一个可见 scrollable；可指定 ref/where |
| W8 | 滚动直到目标 | 找列表项 | ui.scroll {until} | APK a11y | 过渡 | 当前叫 ui.scrollUntil；M4 合并进 ui.scroll |
| W9 | 滑动/拖拽 | 页面切换/拖拽 | ui.act {op:swipe/drag} | APK a11y | 已实现 | dispatchGesture；多指见 W10 |
| W10 | 多指手势/路径 | 缩放、图案、复杂手势 | ui.gesture | APK a11y | 计划 M3 | GestureDescription ≤20 stroke；当前只有 root helper 的单路径实现 |
| W11 | 按键/全局动作 | 返回/Home/最近/通知栏 | ui.act {op:press/global} | APK a11y（shell 兜底） | 已实现 | 保留在 ui.act 是为了同一响应拿 settle+diff；ui.press 退出 |
| W12 | 批量动作 | 多步表单/连点 | ui.batch | APK a11y | 已实现 | ≤20 op，一次往返，op 间复用活树 |
| W13 | 全局剪贴板 | 跨 App 复制粘贴 | ui.clipboard（候选 M3） | APK ClipboardManager | 评估 | shell 无实现；Android 10+ 仅前台/IME 可读，失败要解释 |
| W14 | IME 兜底 | setText 被拒时输入 | ui.act 内部 commit | APK 自建 IME | 计划 M3 | 只影响当前焦点；ui.ime(show/hide) 退出 |
| W15 | 填表 | 多字段一次填完 | ui.fill | APK a11y | 已实现 | where/label 定位，可选 submit |
| W16 | 坐标点击/滑动 | 无树场景/游戏 | shell input | shell | 不做进 APK | 有 ref 时不需要；input 单指实测 13–17ms |

### 3.3 系统与数据（Ubuntu 更好）

| ID | 能力 | 典型场景 | 目标入口 | 执行面 | 状态 | 依据/备注 |
|---|---|---|---|---|---|---|
| S1 | 启动/停止/安装/卸载/清数据 | 应用管理 | am/pm | shell/root | 不做 | 支持 extras/flags/-W/多用户 |
| S2 | 设置/网络/亮度/音量/旋转/DND/时区 | 系统配置 | settings/svc/cmd | shell | 不做 | 一步到位、可脚本 |
| S3 | 文件读写/分享 | 传文件、导出 | fs.* / shell | shell/root | 不做进 APK | APK 只能 scoped storage；root 直读更全 |
| S4 | 录屏/媒体控制 | 录制、播放 | shell/root | shell/root | 不做 | screenrecord 比 MediaProjection 省事 |
| S5 | 联系人/日历/SMS | 结构化数据 | content query/insert | shell | 不做 | ContentProvider 直读，不必点 UI |
| S6 | OCR | 图片转文字 | tesseract | shell | 不做 | 已有 chi_sim+eng；ML Kit 只多 20MB |
| S7 | 位置/传感器/电池/网络统计 | 设备状态 | dumpsys | shell | 不做 | 只读，避免常驻监听 |
| S8 | 应用内 DB/SharedPreferences | 深度检查 | root shell | root | 不做 | APK 根本做不到 |
| S9 | 截图标注/拼图 | 视觉报告 | shell + Python | shell | 不做 | 服务器侧更灵活 |
| S10 | 通知 dismiss/reply | 清通知/回消息 | notif.dismiss/reply | APK NotificationListener | 计划 M3 | cmd notification 无 dismiss/reply；这是 APK 独有 |
| S11 | Toast/ANR/权限弹窗识别 | 处理阻塞弹窗 | events + Capture | APK a11y | 部分 | 事件已发 toast；ANR/权限弹窗需规则和窗口类型 |
| S12 | 状态栏展开/收起 | 看通知/快捷开关 | cmd statusbar | shell | 不做 | 只读/简单动作 |

## 4. 目标 API 面（M4 收敛后）

不把“16 个方法”当硬指标；以本表为准。当前 M2 代码已有 14 个用户方法，M4 做减法并把动作收进 ui.act。

| 类别 | 方法 | 状态 |
|---|---|---|
| 读 | ui.view, ui.nodes, ui.diff, ui.wait | 已实现 |
| 读 | ui.expect | 计划 M3 |
| 写 | ui.act（op: tap/longClick/setText/appendText/clearText/focus/scroll/swipe/drag/press/global/copy/cut/paste/setSelection） | 已实现（部分 op 待补） |
| 写 | ui.batch, ui.fill, ui.scroll | 已实现（ui.scrollUntil 合并中） |
| 写 | ui.gesture | 计划 M3 |
| 写 | ui.clipboard（候选） | 评估 |
| 通知 | notif.dismiss, notif.reply | 计划 M3 |
| 运维 | sys.status, session.*, events.*, policy.*, dev.* | 已实现 |
| 移除 | ui.shot, ui.press, ui.ime(show/hide), ui.refresh（公开面） | M4 |

## 5. 后端选择（a11y / uia / shell）

原则：后端不由引擎替调用方决定；引擎给出能力、可用性、代价，AI 按场景选。

| 维度 | a11y | uia | shell |
|---|---|---|---|
| 读树 | 进程内，有稳定节点身份（sourceNodeId） | 跨进程 JSON，只有序号无身份 | 不做 |
| 点击/滑动 | 语义动作 + dispatchGesture | 坐标注入，绕过 a11y 手势限制 | input tap/swipe |
| 文本输入 | ACTION_SET_TEXT，中文 OK | 点焦点 + 键事件，中文不行 | input text，仅 ASCII |
| 按键/全局 | performGlobalAction | injectKeyEvent | input keyevent |
| 截图 | takeScreenshot | takeScreenshot | screencap |
| 独占资源 | 无 | 全局单槽 | 无 |
| 需要 root | 否 | 是 | 是 |
| 典型延迟 | 20–80ms | 40–150ms | 15–150ms（截图约 700ms） |

选择方式：单次 backend 参数、session.open 的 backend、policy.backend（默认 auto）。
auto 链：tap/long/swipe/scroll → a11y → uia → shell；setText/clear/append → a11y → IME commit → shell（仅 ASCII）；press/global → a11y → uia → shell；读树永远 a11y；shot → shell（或 uia）。
uia 不常驻，空闲 60s 释放；每次响应带 via/ms；失败带 alternatives（why + cost）。

## 6. 错误契约（固定 13 码）

BAD_REQUEST, NOT_FOUND, AMBIGUOUS_MATCH, STALE, BLOCKED, DISABLED, NOT_EDITABLE, TIMEOUT, UNKNOWN, CONFLICT, UNAVAILABLE, DENIED, INTERNAL。

每个错误至少带：code、message、retryable、hint；能给出候选时带 candidates（ref/score/why）。示例：

~~~json
{"ok":false,"code":"NOT_FOUND","retryable":true,
 "candidates":[{"ref":"@e31","text":"确定","score":0.61,"why":"同 viewId，bounds 偏移 84px"}],
 "hint":"界面已切换；重新 ui.view 并用 candidates 里最近的 ref"}
~~~

## 7. 明确不做（非目标）

- 不做截图/录屏/媒体/文件管理器；这些是 shell 的活。
- 不做应用管理、系统设置、联系人/日历/SMS 数据、OCR、传感器/位置。
- 不做应用内 DB/SharedPreferences 读取；root shell 更直接。
- 不把坐标点击当主路径；ref/where 优先，坐标只在无树时兜底。
- 不保存密码/正文；密码字段必须在 Capture 层脱敏，Diff 不输出 before/after。
- 不做“与 GKD 共存”的调度；同一时刻控制类无障碍互斥，sys.status 只报告 conflicts。
- 不承诺无障碍关闭时还能读树；那时只能走 uia/shell 降级。

## 8. 研究映射（8 组）

| 编号 | 主题 | 交付 | 状态 |
|---|---|---|---|
| 01 | 树与 ref：多窗口/可见性/身份/遍历 | docs/research/01-tree-and-refs.md | 完成（686 行） |
| 02 | 动作注入与手势：语义动作/坐标/多指/遮挡 | docs/research/02-actions-and-gestures.md | 待派 |
| 03 | 文本输入/IME/剪贴板：SET_TEXT/commitText/焦点 | docs/research/03-text-ime-clipboard.md | 待派 |
| 04 | 等待/事件/稳定性/diff：settle/idle/事件合并 | docs/research/04-wait-events-diff.md | 待派 |
| 05 | 通知/权限/Toast/ANR/系统弹窗 | docs/research/05-notifications-dialogs.md | 待派 |
| 06 | 截图/视觉/OCR 边界：screencap/takeScreenshot/ML Kit | docs/research/06-screenshot-ocr.md | 待派 |
| 07 | 协议/会话/幂等/错误码：JSON-RPC vs JSON Lines/W3C | docs/research/07-protocol-session.md | 待派 |
| 08 | MCP 工具面/Agent 可用性：工具命名/schema/批处理 | docs/research/08-mcp-tools.md | 待派 |

研究规则：只读本地 checkout 与公开资料；每条结论带 path:line 或 URL；不修改代码、不碰真机；先给 10 条结论，再展开证据；最后列“可借鉴/不适用/待验证”。

## 9. 验收清单

M4（能力边界收敛）：

- [ ] 移除 ui.shot、ui.press、ui.ime(show/hide)、公开 ui.refresh。
- [ ] ui.act 增加 scrollIntoView、setSelection、copy/cut/paste、dismiss。
- [ ] 增加 ui.expect、ui.gesture、notif.dismiss/reply。
- [ ] sys.status 暴露 backends 能力表、conflicts、g/seq、版本、端口。
- [ ] password 字段在 Capture/View/Diff 全链路脱敏。
- [ ] 单测：agent-core 全绿；agent-app/console 单测全绿；assembleDebug 通过。
- [ ] 真机脚本：ui.view → ui.act tap → diff → ui.batch 两步 → 回桌面（需用户时间窗）。

M5（MCP facade）：

- [ ] phone_view/act/batch/fill/scroll/wait 走 APK；phone_shot/launch 走 shell；press 作为 act op。
- [ ] DSH 侧用 insert: 补丁注册（不是 id: 覆盖不存在的条目）。
- [ ] 端到端：DSH 调用 phone_view 看到当前页，phone_act 点击后拿到 change，phone_wait 等到文本。

## 10. 未决问题（交给研究/真机）

1. 全局剪贴板在 Android 15 的读取窗口：无障碍服务在前台/后台分别能否读？失败是否要自动切 IME？
2. uia 第二发的触发条件：手势 400ms 无 diff 是否足够？uia 槽位被 system_server 占用时如何快速失败？
3. 多窗口下“模态遮挡”如何表达：只靠 z/layer，还是要窗口类型 + 焦点？
4. Compose/WebView 的 ref 稳定性：testTag/uniqueId 的覆盖率，WebView 虚拟节点是否纳入。
5. 自建 IME 的代价：是否需要独立 APK/输入法启用流程，还是只做 commitText 兜底。
6. 通知 reply 的 RemoteInput 在 Android 15 的兼容性；无 RemoteInput 时是否降级为打开通知。
7. MCP 工具的错误如何映射到 DSH 的工具错误；是否需要把 13 码压成 5 类。
