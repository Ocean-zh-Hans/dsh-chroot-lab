# 05 · 通知 / 权限 / Toast / ANR / 系统弹窗

> 只读调研，未改任何代码、未碰真机。日期：2026-10-29。
> 范围：NotificationListenerService、cmd notification、权限弹窗/ANR/Toast/系统对话框识别、模态遮挡、notif.* 与 ui.act 接口建议。
> 引用缩写：
>
> - DSH/ = /root/workspace/projects/dsh-chroot-lab/
> - APP/ = /root/workspace/projects/_research/appium-uiautomator2-server/
> - POR/ = /root/workspace/projects/_research/portal/
> - U2/ = /root/workspace/projects/_research/uiautomator2/
> - SDK35/ = /opt/android-sdk/platforms/android-35/android-stubs-src.jar!/（Android 15/API 35 公开 stub 源码，行号按 jar 内文件计）
>
> 证据等级：A = 本地源码逐行；B = 本地 SDK/文档或仓库级；C = 静态推导/待真机。本文没有把 C 写成确定事实。

## 0. 结论（10 条）

1. **NotificationListenerService 是 APK 内唯一能同时做“列出/读取 + 取消 + RemoteInput 回复”的公开面。** API 35 仍保留 getActiveNotifications()、cancelNotification(key)、cancelNotifications(keys)、snoozeNotification()、getSnoozedNotifications()、getCurrentRanking() 和 RemoteInput.addResultsToIntent()；这些不是 Android 15 删除项。见 SDK35/android/service/notification/NotificationListenerService.java:56-78、SDK35/android/app/RemoteInput.java:45。
2. **dismiss 只对 clearable 通知成立。** StatusBarNotification.isOngoing()/isClearable() 是公开的（SDK35/android/service/notification/StatusBarNotification.java:40-42）；ongoing、FLAG_NO_CLEAR、系统锁定的通知不能当成普通通知清掉，必须先判 isClearable，失败时返回 BLOCKED（reason=not_clearable），不要返回 dismissed=true。
3. **cmd notification 能 list/get/post/snooze/unsnooze，不能 dismiss/reply/click 既有通知。** 它没有 StatusBarNotification 对象图、没有 Notification.Action.actionIntent、也没有 RemoteInput 结果注入入口；这三样只有已获用户授权的 NotificationListenerService 进程拿得到。项目文档已给出同一结论：DSH/docs/agent-engine-v3.md:301。
4. **权限弹窗通常不是 TYPE_SYSTEM，不能只靠窗口类型判断。** AOSP 10+ 的运行时权限框是 com.android.permissioncontroller 的 GrantPermissionsActivity（Activity 窗口，常见 TYPE_APPLICATION）；ANR 才是 system_server 的 TYPE_SYSTEM 窗口。识别顺序应是包名 → Activity/class → 资源 id/文本 → 窗口 type/focused/layer，而不是反过来。见 §4.2。
5. **ANR、状态栏、导航栏都可能是 TYPE_SYSTEM，TYPE_SYSTEM 本身没有语义。** 01 号文档已指出“状态栏、导航栏也是 TYPE_SYSTEM，不能无条件全收”（DSH/docs/research/01-tree-and-refs.md:40）；状态栏/导航栏应再按包名 com.android.systemui、贴边 bounds、是否 focused、是否有对话框文本过滤。
6. **Toast 只能靠事件捕获，不能靠树。** AccessibilityWindowInfo 的公开 type 只有 APPLICATION/INPUT_METHOD/SYSTEM/ACCESSIBILITY_OVERLAY/SPLIT_SCREEN_DIVIDER/MAGNIFICATION_OVERLAY（SDK35/android/view/accessibility/AccessibilityWindowInfo.java:87-97），没有 TYPE_TOAST；Toast 的可靠信号是 TYPE_NOTIFICATION_STATE_CHANGED 事件里的 text/package/class。当前 DSH 把所有 TYPE_NOTIFICATION_STATE_CHANGED 都当 toast（DSH/agent-app/src/dev/dsh/agent/AgentServer.kt:515-517），会把通知发布误报成 toast。
7. **AccessibilityWindowInfo 没有公开的 isTouchable()。** API 35 只有 getType/getLayer/getBoundsInScreen/isActive/isFocused/isAccessibilityFocused/getTitle 等（SDK35/android/view/accessibility/AccessibilityWindowInfo.java:27-66）。所谓 isTouchable 只能作为推断字段；要知道 FLAG_NOT_TOUCHABLE 只能 root dumpsys window 解析，不能写成直接 API。
8. **模态必须表达成“窗口级 blocksBelow”，不能只靠几何覆盖。** 当前 UiEngine.tapPoint() 只在更高 z 且 bounds 完全覆盖时找 blocker，并允许从 1/4 位置网格找缝（DSH/agent-app/src/dev/dsh/agent/UiEngine.kt:532-550）；权限弹窗/ANR 即使没有盖满全屏，也会让下层 App 不可交互，必须直接 BLOCKED。更危险的是 ACTION_CLICK 绕过触摸分发，语义点击也要做同样的 modal 检查。
9. **不要自动点 ANR 的“等待/关闭”，也不要默认点权限“允许”。** 这两个动作分别是“丢失现场/终止应用”和“授予敏感权限”，属于策略决定，不是识别问题。U2 的 builtin watcher 直接 when("同意").click()、when("^(好的|确定)$").click()、when("ALLOW").click()（U2/uiautomator2/watcher.py:36-45），只能作为测试环境的反例，不能进生产 agent。
10. **接口应收敛为 notif.list/get/dismiss/reply、ui.act 的 dismiss/allow/deny，以及 sys.status 的 notification/modal/blockers/conflictDetails。** DENIED 表示“没有通知使用权/策略拒绝”，UNAVAILABLE 表示“服务未连接/没有 RemoteInput/系统不支持”，BLOCKED 表示“被模态窗口或不可清除状态挡住”；不要把三者混成 UNKNOWN。详见 §7。

---

## 1. 问题定义：我们到底要解决什么

通知与弹窗是两类不同问题，不能混在一个“读屏”接口里：

| 问题 | 目标 | 正确入口 | 失败应可解释 |
|---|---|---|---|
| 读通知 | 知道谁发了什么、是否 ongoing/group、有哪些动作 | notif.list / notif.get（APK listener） | 没有通知使用权 → DENIED；服务没连上 → UNAVAILABLE |
| 清通知 | 清掉某条可清除通知 | notif.dismiss（APK listener） | key 不在了 → NOT_FOUND；不可清除 → BLOCKED |
| 回通知 | 对带 RemoteInput 的动作填入文本并发送 | notif.reply（APK listener） | 没有 RemoteInput → UNAVAILABLE；动作不存在 → NOT_FOUND |
| 权限弹窗 | 识别当前是哪个 App 的哪个权限，允许/拒绝/跳过 | ui.act {op:allow/deny/dismiss} + 弹窗分类 | 策略不允许 → DENIED；不是权限框 → BAD_REQUEST |
| ANR/崩溃 | 判断哪个进程无响应、让调用方决定等待/关闭 | ui.view/ui.wait 暴露 blocker + events 通知 | 不能静默点“等待”后假装页面正常 |
| Toast | 读一次性提示，作为证据而不是页面内容 | events.subscribe("toast") | 事件丢失只能标记 missed，不能回读树补 |
| 系统栏 | 不把状态栏/导航栏当 App 内容 | 窗口分类 + 默认排除 | 用户明确要看通知栏时才纳入 |

边界（与 docs/agent-engine-v3.md §12 一致）：

- 通知“读取”仍可由 shell 的 cmd notification list/get 承担；只有“动作”（dismiss/reply/click）必须进 APK。见 DSH/docs/agent-engine-v3.md:301、315。
- 权限弹窗/ANR/Toast 识别属于 APK 的“眼”：事件与窗口树是 Ubuntu 没有的。见 DSH/docs/agent-engine-v3.md:304。
- 所有“允许/拒绝/等待/关闭”都是策略动作，不是识别动作；默认只报告，不自动执行。

---

## 2. NotificationListenerService：能做什么，Android 15 还剩下什么

### 2.1 公开能力表（以 API 35 stub 为准）

| 能力 | API | Android 15 状态 | 说明 |
|---|---|---|---|
| 列出当前通知 | getActiveNotifications() | 可用 | 返回 StatusBarNotification[]；可为空数组 |
| 按 key 批量取 | getActiveNotifications(String[] keys) | 可用 | 比全量再过滤更准；key 不存在时结果里没有该项 |
| 取已 snooze | getSnoozedNotifications() | 可用 | 与 active 分开，不要混在 list 里 |
| 取消单条 | cancelNotification(String key) | 可用 | 2 参重载未废弃；3 参 pkg/tag/id 已废弃 |
| 批量取消 | cancelNotifications(String[] keys) | 可用 | 逐条校验；不要用 cancelAll 代替 |
| 全部取消 | cancelAllNotifications() | 可用 | 高风险；默认不暴露给 agent |
| snooze | snoozeNotification(String key, long ms) | 可用 | 不是 dismiss；到时恢复 |
| 当前 ranking | getCurrentRanking() | 可用 | 可读 importance/channel/ambient 等；不是内容主体 |
| 监听 posted/removed | onNotificationPosted/Removed | 可用 | 事件驱动；removed 带 reason（API 26+ 三参重载） |
| RemoteInput 回复 | Notification.Action.getRemoteInputs() + actionIntent.send() | 可用 | 见 §2.3 |

证据：SDK35/android/service/notification/NotificationListenerService.java:27-35、53-78、90；SDK35/android/service/notification/StatusBarNotification.java:28-66。

关键限制：

1. **只能看“当前用户、当前 listener 可见”的通知。** 工作资料/多用户/私有空间是不同 user；getActiveNotifications() 不会跨 user 合并。需要跨 user 时只能 root/shell 另行查询，不能假装 listener 全知。
2. **ongoing 不一定可取消。** StatusBarNotification.isClearable() 明确存在（SDK35/.../StatusBarNotification.java:42）；系统锁定的通知、正在通话/音乐/导航的 ongoing 通知通常不可清。实现必须先判 isClearable，再决定是否调用 cancelNotification。
3. **group/summary 要区分。** isGroup()/isAppGroup()/getGroupKey() 可用（SDK35/.../StatusBarNotification.java:28-30、66）；group summary 的 key 与子通知不同。不要只按 title 去重；同一标题可能有多条。
4. **通知内容可能是 null/Spannable/多段。** title/text/bigText 都是 extras 的 CharSequence；DSH 当前直接 .toString()（NotificationRelay.kt:51-52），null 会变成字符串 "null"，Spannable 会丢掉样式但保留文字；多段要用 CharSequence 拼接而不是数组 toString。
5. **listener 可能被系统解绑。** onListenerDisconnected() 后 instance 置空（DSH NotificationRelay.kt:13-15）；重新授权/进程重启后要 requestRebind（SDK35/.../NotificationListenerService.java:96），不能只靠 onListenerConnected 的偶然回调。

### 2.2 权限与用户授权流程

Android 没有“通知读取”的运行时权限对话框；它是特殊访问（Notification access）：

1. Manifest 声明 service，并加 android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" 与 android.service.notification.NotificationListenerService intent-filter。DSH 已声明：DSH/agent-app/AndroidManifest.xml:50-57。
2. 用户在系统设置里打开“通知使用权”，勾选本应用。DSH 首页用 Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS 打开该页：DSH/agent-app/src/dev/dsh/agent/MainActivity.kt:110-112。
3. App 可用 NotificationManager.isNotificationListenerAccessGranted(ComponentName) 检查（SDK35/android/app/NotificationManager.java:105）；这不是 areNotificationsEnabled()（后者是 POST_NOTIFICATIONS，控制本 App 能否发通知，见 Manifest:13 与 MainActivity:74,127）。
4. 授权后系统绑定服务，onListenerConnected() 回调；撤销授权或 force-stop 会触发 onListenerDisconnected()/解绑。App 被 force-stop 后不能自行恢复，必须用户重新打开 App 或系统重新绑定（项目已在 docs/verification.md:575 记录通知监听保留情况）。
5. Android 13+ 侧载应用可能处于“受限设置”（Restricted settings），通知使用权开关可能灰掉；需要用户在应用信息里先“允许受限设置”。这是 OEM/安装方式相关，待真机验证，不能假设一装就有权。
6. Android 15 的“通知冷却”（notification cooldown）会降低连续通知的音量/提醒，不改变 listener 的 getActiveNotifications/cancel 接口；但用户可能感知为“通知没响”，不要把它误判为 listener 失效。参考官方 Android 15 行为变更页（见 §10）。
7. 锁屏/私密通知可能被系统按 visibility 脱敏；listener 拿到的 extras 是否已脱敏随 ROM/锁屏状态而变。不要把“读不到正文”当成解析失败；应返回 redacted=true 或 text=""，待真机验证。

### 2.3 RemoteInput 回复的完整链路

标准链路（与 DSH MediaBridge 现有实现一致）：

1. 从 StatusBarNotification.notification.actions 取 Notification.Action[]（SDK35/android/app/Notification.java:363）。
2. 对每个 action 调 getRemoteInputs()；只挑 inputs 非空且 getAllowFreeFormInput() 为 true 的 action（SDK35/android/app/RemoteInput.java:35）。
3. 选择 resultKey；如果要支持 choices，先校验 text 在 getChoices() 内，或走 choice 结果（SOURCE_CHOICE）。
4. 构造 Bundle，putCharSequence(resultKey, text)；RemoteInput.addResultsToIntent(inputs, intent, results)（SDK35/android/app/RemoteInput.java:45）。
5. action.actionIntent.send(context, 0, intent)（actionIntent 字段见 SDK35/android/app/Notification.java:404）。
6. 返回 requested=true、confirmed=false；如果要 confirmed，等到 onNotificationRemoved 或同一 key 的 onNotificationPosted 更新，不能同步假定成功。

Android 15 兼容性判断：

- 这些公开 API 在 API 35 stub 中都存在，没有标记 deprecated（除 3 参 cancelNotification 外）。所以“Android 15 不能用 RemoteInput 回复”不成立；真正的限制是通知本身有没有 RemoteInput action、action 是否允许自由文本、PendingIntent 是否仍有效。
- 但 Notification.Action.getRemoteInputs() 只返回“直接回复”输入；数据型输入在 getDataOnlyRemoteInputs()（SDK35/android/app/Notification.java:369）。只检查前者会把图片/文件回复误判为“没有 RemoteInput”。
- 如果 action 的 getAllowFreeFormInput() 为 false 且只有 choices，传任意 text 会被系统/应用拒绝；应先按 choices 校验。
- PendingIntent.send() 可能抛 CanceledException（通知已撤销/包被禁用）；必须捕获并映射 NOT_FOUND/UNAVAILABLE，不能让线程崩。
- 回复是“外部副作用”，不能自动重试；应带 requestId/idempotencyKey，超时标 UNKNOWN，不标成功。

### 2.4 DSH 当前实现审计（只读）

DSH 的 NotificationRelay 是一个最小监听器：

- 只在 onListenerConnected 保存 instance，onNotificationPosted/Removed 转发 key/package/postTime；没有保存 SBN 快照，没有 ranking、group、actions。见 DSH/agent-app/src/dev/dsh/agent/NotificationRelay.kt:8-23。
- snapshot() 只输出 key/package/posted/ongoing/title/text；没有 isClearable、groupKey、bigText、actions。见 DSH/agent-app/src/dev/dsh/agent/NotificationRelay.kt:41-56。
- title/text 的 .toString() 对 null 不安全（会得到 "null"）；也没有截断/脱敏。见 NotificationRelay.kt:51-52。
- dismiss() 调 cancelNotification(key) 后立刻读 activeNotifications 判断“不在了”；cancel 是异步的，这个返回值可能是 false 即使实际已清除。见 NotificationRelay.kt:59-65。应改为等 onNotificationRemoved 或短轮询 getActiveNotifications(keys)。
- find(key) 用 activeNotifications.firstOrNull；key 为空直接 null，行为正确；但没有处理 activeNotifications 为 null 的并发窗口。见 NotificationRelay.kt:33-38。
- MediaBridge 里已有 notifications.list/dismiss/click/reply 和完整 RemoteInput 逻辑（DSH/agent-app/src/dev/dsh/agent/MediaBridge.kt:24-27、101-137），但 **v3 分发没有接线**：AgentServer.dispatchInternal 只走 EngineRouter 和固定 switch，没有 MediaBridge 分支（DSH/agent-app/src/dev/dsh/agent/AgentServer.kt:328-357）；AgentMethods 的 WRITES 也不含 notifications.*（DSH/agent-app/src/dev/dsh/agent/AgentMethods.kt:8-11）。因此这些方法目前是死代码；docs/agent-engine-v3.md:252 也写明 MediaBridge “先留文件、不注册方法”。
- AgentServer.summary 只把“通知使用权：已连接/未连接”作为人读字符串（DSH/agent-app/src/dev/dsh/agent/AgentServer.kt:156-161）；sys.status 没有 notification 字段（UiEngine.kt:596-615），agent 无法程序化判断通知能力。

结论：不是“NotificationListener 不能做”，而是 DSH 目前只做了事件转发和最小列表；dismiss/reply 的实现存在但未接线，且校验/错误映射不完整。

---

## 3. cmd notification：能做什么、不能做什么

### 3.1 命令面

AOSP 的 NotificationShellCmd 提供的命令（不同版本略有差异，最终以设备上 cmd notification help 为准）：

| 命令 | 作用 | 能否替代 APK 动作 |
|---|---|---|
| list | 列出当前通知（文本） | 只能读，不能动 |
| get <key> | 打印某条通知的字段/extras | 只能读，输出非稳定 JSON |
| post | 以 shell 身份发一条通知 | 只能新建，不能操作既有通知 |
| snooze / unsnooze | 暂停/恢复某条通知 | 不是 dismiss；到时会回来 |
| allow_listener / disallow_listener | 授予/撤销 listener 访问（shell/root 特权） | 是“授权管理”，不是通知动作 |
| allow_assistant / disallow_assistant | 助手访问 | 与本项目无关 |

项目已实测 cmd notification help 返回 rc=0（DSH/docs/verification.md:99），但该测试只证明命令存在，没有证明 list/get 的结构化字段或所有子命令在目标 ROM 上一致。

### 3.2 为什么 dismiss/reply 只能进 APK

1. **没有对象。** dismiss 需要 NotificationListenerService.cancelNotification(key)，这个 key 来自 StatusBarNotification；cmd notification 的 list/get 只输出文本，shell 进程没有 SBN 对象，也没有 listener 的 Binder token。
2. **没有 PendingIntent。** 通知的点击/回复动作是 Notification.Action.actionIntent（一个 PendingIntent）。shell 无法从文本里还原 PendingIntent，也无法调用它的 send()；只有持有 SBN 的 listener 进程可以。
3. **没有 RemoteInput 结果通道。** 回复需要 RemoteInput.addResultsToIntent(inputs, intent, results) 再 send actionIntent；cmd notification 没有“对既有通知注入结果”的子命令，post 只能新建通知。
4. **权限模型不同。** cmd notification 是 shell/root 特权命令；APK 的 listener 是用户明确授予的“通知使用权”。把 dismiss/reply 塞进 shell 等于要求 root 或 shell，既不符合最小权限，也无法解释“用户是否授权”。
5. **可验证性。** APK 可以在 cancel 后监听 onNotificationRemoved、在 reply 后等 posted/removed；shell 只能看下一次 list 的文本差异，容易把“没清掉”误报成成功。

因此能力矩阵把“通知读取”归 shell、把“dismiss/reply”归 APK 是合理的（DSH/docs/agent-capability-matrix.md:93、109）。补充：notif.click（发送 contentIntent）也只能进 APK；如果只需要“打开通知对应的 App”，可用 shell am start 作为降级，但那不是点击通知本身。

### 3.3 建议的 shell 兜底

- notif.list 在 listener 不可用时，可降级到 cmd notification list，但响应必须标 via="shell"、degraded=true、schema="text"，并明确“没有 actions/RemoteInput，不能 dismiss/reply”。
- 不要在 shell 层解析 title/text 后冒充结构化通知；字段缺失时返回 text 原文与 truncated=true。
- 任何 dismiss/reply 请求在 listener 不可用时返回 UNAVAILABLE（hint: 打开通知使用权），不要偷偷改用 shell snooze。

---
## 4. 权限弹窗 / ANR / Toast / 系统对话框：怎么识别

### 4.1 先建立窗口分类，不要先看 type

AccessibilityWindowInfo 的 type 只有六种（SDK35/android/view/accessibility/AccessibilityWindowInfo.java:87-97）：

| type | 值 | 典型内容 | 对 agent 的含义 |
|---|---|---|---|
| TYPE_APPLICATION | 1 | 普通 App、分屏、freeform、PiP、很多系统 App 的 Activity | 默认内容来源 |
| TYPE_INPUT_METHOD | 2 | 软键盘 | 只报 keyboard=true，默认不遍历 |
| TYPE_SYSTEM | 3 | 状态栏、导航栏、ANR、系统错误、通知栏面板 | 必须再分：系统栏 vs 系统对话框 vs 面板 |
| TYPE_ACCESSIBILITY_OVERLAY | 4 | GKD/Portal 等无障碍悬浮层 | 默认排除，不当 App 内容 |
| TYPE_SPLIT_SCREEN_DIVIDER | 5 | 分屏分割条 | 跳过 |
| TYPE_MAGNIFICATION_OVERLAY | 6 | 放大镜 | 跳过 |

关键：**权限弹窗不一定是 TYPE_SYSTEM。** 现代 AOSP 的运行时权限框是 permissioncontroller 的 Activity（GrantPermissionsActivity），窗口类型常见 TYPE_APPLICATION；ANR 是 system_server 的 TYPE_SYSTEM。把“权限/ANR”都写成 TYPE_SYSTEM 是 01 号文档表格的简化，落地时必须用包名/class/资源 id 再确认（DSH/docs/research/01-tree-and-refs.md:40）。

### 4.2 识别表

| 对象 | 事件 | 窗口 type | 包名 | Activity/class | 树/文本特征 | 处理 |
|---|---|---|---|---|---|---|
| 运行时权限框 | TYPE_WINDOW_STATE_CHANGED，常伴 TYPE_WINDOWS_CHANGED | 常见 TYPE_APPLICATION，也可能 OEM 定制为 TYPE_SYSTEM | com.android.permissioncontroller / com.google.android.permissioncontroller / 旧版 com.android.packageinstaller | GrantPermissionsActivity；OEM 可能是 PermissionActivity/PermissionDialog | 资源 id permission_message、permission_allow_button、permission_deny_button、permission_allow_foreground_only_button、permission_allow_one_time_button；文本“允许/拒绝/仅在使用时允许/仅此一次” | ui.act {op:allow|deny}；默认不自动 allow |
| ANR | TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED | TYPE_SYSTEM | android（system_server），OEM 可能 com.android.systemui | AppNotRespondingDialog / AppErrorDialog（不同版本 class 名不同） | 文本“应用无响应”“isn't responding”“Close app/Wait”；资源 id aerr_close/aerr_wait/aerr_report 等 | 只报告 BLOCKED，带 pid/package/reason；等待/关闭由调用方决定 |
| 崩溃框 | 同上 | TYPE_SYSTEM | android | AppErrorDialog | “屡次停止运行/keeps stopping”；按钮“关闭应用/应用信息” | 报告 crash，不自动点关闭 |
| Toast | TYPE_NOTIFICATION_STATE_CHANGED | 无可靠窗口（a11y type 无 TYPE_TOAST） | 发 Toast 的 App | className 常见 android.widget.Toast | event.getText() 是文本；持续约 2 秒；无按钮 | 只发事件 toast；不要读树 |
| 通知栏面板 | TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED | TYPE_SYSTEM | com.android.systemui | StatusBar/NotificationShade 相关类 | 全屏或大半屏、含“通知/快捷设置”、focused=true | 归 SYSTEM_PANEL；默认不作为 App 内容 |
| 状态栏 | 通常无单独 WINDOW_STATE_CHANGED | TYPE_SYSTEM | com.android.systemui | StatusBarWindowView 等 | 顶部窄条，高度约 24–48dp，通常不 focused | 默认排除 |
| 导航栏 | 通常无单独事件 | TYPE_SYSTEM | com.android.systemui | NavigationBarView 等 | 底部窄条，手势导航更窄 | 默认排除 |
| IME | TYPE_WINDOWS_CHANGED | TYPE_INPUT_METHOD | 输入法包名 | 输入法窗口 | 底部区域，app 窗口仍 focused | 只置 keyboard=true；默认不遍历 |
| 其他 App 悬浮窗 | TYPE_WINDOWS_CHANGED | TYPE_APPLICATION 或 TYPE_SYSTEM（ROM 差异） | 第三方包 | 悬浮窗类 | 小窗口、可拖动、可能覆盖目标 | 只按几何遮挡；不自动当模态 |
| 无障碍悬浮层 | TYPE_WINDOWS_CHANGED | TYPE_ACCESSIBILITY_OVERLAY | GKD/Portal 等 | overlay 类 | 浮动按钮/提示 | 默认排除；覆盖目标时只做几何 blocker |

说明：

- 资源 id 的包名前缀随 ROM 变化（com.android.permissioncontroller:id/... 或 com.google.android.permissioncontroller:id/...）；匹配时用 :id/permission_allow_button 后缀，不要硬编码完整包名。Appium 的 AlertHelpers 就是这么做的：permission_message、permission_\w+_button 正则（APP/app/src/main/java/io/appium/uiautomator2/utils/AlertHelpers.java:43-57），并区分 REGULAR/PERMISSION 两类（:63-76）。
- Appium 只处理 regular alert 和 permission alert（AlertHelpers.java:63-76），**没有 ANR 分支**；这说明“照搬 Appium 的 alert API”不够，ANR/崩溃框要自己识别。
- 权限框的按钮顺序/文案不能作为唯一依据；Android 12+ 有“仅此一次/使用期间允许”，Android 11 有“仅此一次”，OEM 有“始终允许”。必须按资源 id/role 匹配，并把实际按钮文本回传。
- ANR 的 class 名在不同版本/OEM 可能是 android.app.AppNotRespondingDialog 或 com.android.server.am.AppNotRespondingDialog；不要只匹配一个字符串，匹配“包名 android + TYPE_SYSTEM + focused + 文本包含 responding/无响应 + 按钮 aerr_*”组合。

### 4.3 如何避免把状态栏/导航栏当内容

按优先级：

1. **包名过滤。** 默认排除 com.android.systemui 的 TYPE_SYSTEM 窗口；只有 includeSystemUi=true 或用户显式要“看通知栏/状态栏”时才纳入。系统对话框的包名是 android 或 permissioncontroller，不在此列。
2. **贴边几何过滤。** 顶部高度 ≤ 96dp 且 y=0 的窗口视为 status bar；底部高度 ≤ 96dp 且 bottom=screen.bottom 的窗口视为 nav bar；左右贴边且宽度 ≤ 96dp 视为侧边手势区。阈值按 density 换算，不要写死像素。
3. **focused 不是内容判据。** 状态栏在用户下拉通知栏时会 focused=true，此时它是 SYSTEM_PANEL，不是 App 内容；ANR 也 focused=true，但它是对话框。必须结合包名/标题/bounds/文本。
4. **title 辅助。** AccessibilityWindowInfo.getTitle() 可能返回 “StatusBar”“NavigationBar”“NotificationShade” 等；OEM 可能为空，所以只能辅助，不能唯一。
5. **不要把 isVisibleToUser 当遮挡判断。** 被弹窗盖住的 App 节点仍可能 isVisibleToUser=true（01 号文档已指出，DSH/docs/research/01-tree-and-refs.md:80 附近讨论模态缺失）；遮挡只能靠窗口层。
6. **不要给系统栏节点发 ref。** 即使纳入树，也标 kind=SYSTEM_BAR、clickable=false、不发 ref；否则模型会点状态栏电池/Wi-Fi 图标。

### 4.4 DSH 当前事件/采集缺口

- onAccessibilityEvent 对任何 TYPE_WINDOW_STATE_CHANGED 都更新 events.foreground(pkg)（AgentServer.kt:503-507），权限框（permissioncontroller）和 ANR（android）会把“前台 App”改成系统包；需要拆成 appForeground（最后一个非系统包）与 topWindow。
- TYPE_NOTIFICATION_STATE_CHANGED 一律发布为 toast（AgentServer.kt:515-517），没有按 className 区分 Toast/Notification；应在 data 里带 className、eventText 数组、windowId，并只把 className 含 Toast 的标为 toast。
- Capture 按 layer 升序遍历全部 windows，包括状态栏/导航栏；pkg 取第一个 root 的 package（EngineSupport.kt:65-85，尤其 :82），所以 page.pkg 往往不是前台 App；预算也可能被背景窗口吃光（01 号文档已指出，DSH/docs/research/01-tree-and-refs.md:106 附近）。
- Capture 把 w.layer 直接写入 Node.z（EngineSupport.kt:147）；同一窗口内所有节点 z 相同，UiEngine.tapPoint 的 it.z > node.z 无法发现同窗口兄弟遮挡（01 号文档 :106）。
- 没有 WindowMeta（type/title/focused/modal/kind），所以 sys.status 无法告诉 agent “当前有权限弹窗/ANR”。UiEngine.status() 只输出 page/backends/conflicts（UiEngine.kt:596-615），conflicts 只列其他无障碍服务（:617-630），没有通知监听、模态、IME。

---

## 5. 模态遮挡：窗口 layer、isTouchable、focused、TYPE_SYSTEM/IME

### 5.1 建议的 WindowMeta

~~~kotlin
enum class WindowKind { APP, APP_DIALOG, SYSTEM_DIALOG, SYSTEM_BAR, SYSTEM_PANEL, IME, OVERLAY, OTHER }

data class WindowMeta(
    val id: Int,
    val displayId: Int,
    val type: Int,             // AccessibilityWindowInfo.type
    val layer: Int,            // 越大越上层
    val bounds: Rect,          // screen 坐标
    val pkg: String,
    val title: String,
    val active: Boolean,
    val focused: Boolean,
    val kind: WindowKind,
    val modal: Boolean,        // 是否让下层不可交互
    val blocksBelow: Boolean,  // 是否阻断所有更低层窗口
    val touchable: Boolean?,   // 推断值；null=未知
)
~~~

不要直接调用 isTouchable()：API 35 没有这个方法（SDK35/android/view/accessibility/AccessibilityWindowInfo.java:27-66）。推断规则：

| 窗口 | touchable 推断 | 依据 |
|---|---|---|
| TYPE_TOAST | false | Toast 不可点（a11y 也没有该 type） |
| TYPE_SYSTEM 状态栏/导航栏 | true（仅 bounds 内） | 系统栏本身可点 |
| TYPE_SYSTEM 对话框 | true | 按钮可点，外部区域通常被 dialog 消费 |
| TYPE_INPUT_METHOD | true（仅键盘 bounds） | 键盘按键可点 |
| TYPE_ACCESSIBILITY_OVERLAY | 未知 | 取决于 FLAG_NOT_TOUCHABLE，API 不可见 |
| TYPE_APPLICATION | true（可见区域） | 普通窗口 |
| 其他 | null | 保守：按阻挡处理 |

如果要精确知道 FLAG_NOT_TOUCHABLE，只能 root dumpsys window windows 解析 mAttrs；这不是主路径，且 OEM 输出不稳定。把它作为 dev 诊断，不进入正常点击判断。

### 5.2 分类规则

~~~text
TYPE_APPLICATION:
  - 同包、focused、bounds 小于屏幕、下面还有同包窗口 -> APP_DIALOG, modal=true, blocksBelow=true
  - 全屏 Activity -> APP, modal=false
  - 分屏两块不同包 -> 都是 APP，不 modal
  - PiP/freeform -> APP，不 modal；只按几何遮挡

TYPE_SYSTEM:
  - pkg in {android, com.android.permissioncontroller, com.google.android.permissioncontroller,
            com.android.packageinstaller, com.google.android.packageinstaller}
    且 focused -> SYSTEM_DIALOG, modal=true, blocksBelow=true
  - pkg == com.android.systemui 且贴边窄条 -> SYSTEM_BAR, modal=false
  - pkg == com.android.systemui 且全屏/大半屏、focused -> SYSTEM_PANEL, modal=true, blocksBelow=true
  - 其他 -> SYSTEM_DIALOG 仅在 focused 且有标题/按钮时；否则 OTHER

TYPE_INPUT_METHOD:
  - IME, modal=false, blocksBelow=false；只阻挡键盘 bounds 内的点

TYPE_ACCESSIBILITY_OVERLAY:
  - OVERLAY, modal=false；只按 bounds 几何阻挡；默认不遍历、不发 ref

TYPE_TOAST / TYPE_MAGNIFICATION_OVERLAY / TYPE_SPLIT_SCREEN_DIVIDER:
  - OTHER，不参与内容与点击
~~~

### 5.3 点击前判断（必须同时用于语义点击和手势）

~~~text
resolve target -> targetWindow
if targetWindow 不存在: BLOCKED(window_missing)
above = windows where layer > targetWindow.layer and displayId 相同
modal = above.firstOrNull { it.modal && it.blocksBelow }
if modal != null and target 不是 modal 自己的节点:
    BLOCKED(reason=modal_above, blocker={id,pkg,title,type,layer,kind,bounds})
    hint: 先处理 blocker（ui.act {op:dismiss|deny|allow} 或 ui.wait dialogGone）
point = chooseVisiblePoint(target)
for w in above sorted by layer desc:
    if w.bounds.contains(point) and w.touchable != false:
        try next point inside target.visibleBounds
if no point: BLOCKED(reason=covered, blockers=[...])
if target 在 scrollable 且 point 需要滚动: 先 scrollIntoView，再重算
return OK
~~~

三条硬规则：

1. **modal 窗口阻断整个下层窗口，不只阻断自己的 bounds。** 因为触摸弹窗外部通常被 dialog 消费（或直接取消），不会落到下层 App。不要用“找缝”绕过。
2. **语义 ACTION_CLICK 也要检查。** performAction 直接调用 View 的 action，不经过 WindowManager 的触摸分发；它可以在弹窗下面“成功”点击，但用户看不到、也不应该发生。所有写动作进入 exec 前统一做 canTap 检查。
3. **IME 不是模态。** 软键盘只挡住自己的 bounds；App 上半部分仍可点。不要把 TYPE_INPUT_METHOD 设 blocksBelow=true，否则所有输入页都会 BLOCKED。

### 5.4 当前实现的具体缺口

- tapPoint() 只找 it.z > node.z && it.bounds.covers(b) 的 blocker（UiEngine.kt:532-550）；没有 WindowMeta、没有 modal、没有 visibleBounds；如果弹窗只盖住按钮的一半，它会从 1/4 位置开始 8px 网格找未被盖住的点，可能点到下层 App。
- Node.z 是窗口 layer（EngineSupport.kt:147），同一窗口所有节点相同；同窗口内的 floating action button、Drawer、PopupWindow 无法被检测。Appium 把 DRAWING_ORDER 作为独立属性（APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:68-85、189-190），我们应加 drawingOrder 字段并比较 (windowLayer, drawingOrder)。
- 没有窗口 type/title/focused 的元数据，无法区分“状态栏”和“ANR 对话框”；Capture 的 pkg 取第一个 root，权限框出现时 page.pkg 仍是底层 App（EngineSupport.kt:82）。
- UiEngine.status() 的 conflicts 只列其他无障碍服务（UiEngine.kt:617-630），没有当前 modal/blockers；agent 看到 BLOCKED 时拿不到“被谁挡”。
- BLOCKED 的 detail 应带 blocker ref（如果 blocker 节点有 ref）+ window 元数据；不要只返回一句 "blocked"。

---

## 6. Toast / ANR 的捕获时机与日志证据

### 6.1 Toast

可靠信号：

- AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED；event.getPackageName() 是发 Toast 的 App；event.getText() 是文本列表；event.getClassName() 常见 android.widget.Toast。
- 不要依赖 service.windows：AccessibilityWindowInfo 没有 TYPE_TOAST；Toast 可能根本不作为可访问窗口出现。
- 不要轮询树：Toast 约 2 秒消失，轮询 250ms 既慢又可能错过；事件回调是毫秒级。

DSH 现状与改法：

- 当前把所有 TYPE_NOTIFICATION_STATE_CHANGED 都发成 toast（AgentServer.kt:515-517），会把通知发布也当 toast。应判断 className 含 Toast，或同时对照 NotificationRelay 的 posted 事件去重。
- event.text 是多段 CharSequence 时，toString() 会得到 [a, b]；应逐段 trim、用换行/空格连接，限制 512 字符。
- 事件里应带 time、package、className、text、windowId（可能 -1）、seq；事件总线已有 seq/bootId（EventBus.kt:71-96），可直接复用。
- 若事件丢失（队列满），EventBus 会发 events.dropped（EventBus.kt:172-189）；调用方应知道 toast 可能 missed，不要用“没有 toast 事件”推断“没有 toast”。

### 6.2 ANR / 崩溃

实时识别（APK）：

1. TYPE_WINDOW_STATE_CHANGED 或 TYPE_WINDOWS_CHANGED；
2. 窗口 pkg=android（或 OEM systemui）、type=TYPE_SYSTEM、focused=true；
3. class/text 含 AppNotRespondingDialog / "isn't responding" / "应用无响应" / "无响应"；
4. 按钮资源 id aerr_close/aerr_wait/aerr_report（AOSP）；OEM 可能不同。
5. 同时记录 top window 的 title、bounds、pid（如果树里有）、底层 App 的 package（从最近一个非系统 foreground 取）。

事后证据（shell/root）：

| 证据 | 命令/API | 说明 |
|---|---|---|
| event log | logcat -b events，tag am_anr | AOSP EventLogTags；字段含 pid/package/flags/reason；比文本 "ANR in" 稳定 |
| main/system log | logcat -b system/main，grep "ANR in" | ActivityManager 输出，含 reason/CPU 信息；需要 READ_LOGS 或 root |
| last ANR | dumpsys activity lastanr | 设备上最后一次 ANR 的完整记录 |
| dropbox | dumpsys dropbox --print system_app_anr | 历史 ANR 文件；受 dropbox 保留策略影响 |
| exit info | ActivityManager.getHistoricalProcessExitReasons(pkg, 0, n) + ApplicationExitInfo.REASON_ANR | API 30+；应用自身可查自己的历史，跨包需要权限（SDK35/android/app/ActivityManager.java:74、SDK35/android/app/ApplicationExitInfo.java:73） |
| root helper | RootHelper 的 crash.anr | 当前只读 logcat -b crash 并过滤 "ANR in"（DSH/agent-root-helper/src/main/kotlin/dev/dsh/agent/RootHelper.kt:143、181）；存在漏报风险，见下 |

RootHelper 的问题：

- logcat -b crash 主要收 FATAL EXCEPTION/Fatal signal；AOSP 的 "ANR in" 通常在 system/main buffer，不在 crash buffer。当前过滤条件虽然包含 "ANR in"（RootHelper.kt:181），但输入流可能根本没有这行；应在真机上对比 logcat -b events 的 am_anr 与 -b crash 的输出。
- 事件只带 line 前 512 字符（RootHelper.kt:187），没有 pid/package/reason 的结构化字段；agent 只能做文本匹配。
- 订阅入口在 AgentServer.kt:716-742，只有 events.subscribe types 含 crash.anr 时才连 root；没有订阅时 ANR 不会进事件总线。建议默认把 ANR 检测放在 APK 侧（窗口事件），root 只做补充证据。

### 6.3 是否自动点击“等待/关闭”？

默认：**不自动点。**

理由：

1. “等待”只是把 ANR 对话框收起，应用可能仍然无响应；agent 会误以为页面恢复，后续动作全部超时。
2. “关闭应用”会杀掉目标进程，丢失未保存状态；这是破坏性动作，必须由调用方/用户决定。
3. ANR 可能来自 system_server/SystemUI/输入法，不是当前 App；点错会重启系统 UI。
4. 按钮文案/资源 id 随 OEM/语言变化；自动匹配容易点到“应用信息”或“上报”。
5. 权限弹窗的“允许”是敏感授权，自动点等于绕过用户同意；U2 builtin watcher 的 when("同意").click()/when("ALLOW").click()（U2/uiautomator2/watcher.py:36-45）是测试便利，不是产品策略。

建议：

- ANR：返回 BLOCKED，detail 带 anr={package, pid?, reason?, buttons:[{text,role}]}；由调用方显式发 ui.act {op:"dismiss", action:"wait"|"close"} 或 ui.act {op:"click", where:{text:"等待"}}。
- 权限：默认只报告 dialog；只有 policy 明确 allowlist（package+permission）且请求带 confirm:"YES" 时才执行 allow；deny/dismiss 可直接执行。
- 测试环境：可以提供 opt-in 的 watcher（只匹配明确 allowlist 的测试包），但必须在响应里标 auto=true、reason=test_policy，不能默认开启。

### 6.4 捕获时序建议

| 场景 | 何时抓 | 保留多久 | 证据 |
|---|---|---|---|
| Toast | onAccessibilityEvent 立即 | 最近 20 条/进程生命周期 | event text/package/class/time/seq |
| 权限框 | TYPE_WINDOW_STATE_CHANGED + 每次 capture 的 WindowMeta | 对话框存续期间 | pkg/class/title/按钮/资源 id |
| ANR | 窗口事件 + capture 的 TYPE_SYSTEM focused | 对话框存续 + 最近一次事后日志 | 窗口元数据 + am_anr/exit info |
| 崩溃 | 同上 | 同上 | AppErrorDialog + crash buffer |
| 系统栏 | 每次 capture | 不入事件 | WindowMeta kind=SYSTEM_BAR |

---
