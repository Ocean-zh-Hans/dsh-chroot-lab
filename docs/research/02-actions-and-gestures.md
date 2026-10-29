# 02 · 动作注入与手势：语义动作/坐标/多指/遮挡

> 子代理 420e12f7 调研稿的精简落盘版（2026-10-29）。证据等级：A 本地源码逐行；B 仓库/文件级；C 推测/待真机。
> 完整论证与测试矩阵见子代理原始消息；本文件保留结论、接口建议和可核对引用。

## 0. 结论（10 条）

1. ACTION_CLICK/ACTION_LONG_CLICK/ACTION_SCROLL_* 是视图级语义动作；performAction 返回 true 只代表被接受，不代表界面变化。不可点击/不可长按/不可滚动/不可见/窗口非活动/未实现 action/节点失效，都会失败或静默无效。
2. dispatchGesture 是触摸级注入；能触发按压计时、惯性、多指、长按；受遮挡、FLAG_NOT_TOUCHABLE、系统手势区影响；GestureDescription 最多 20 条 stroke，单次最长 60s。
3. input tap/swipe 是 shell 进程级注入；兼容性最好（无障碍关闭、WebView、Flutter、游戏），但每次启动 /system/bin/input，延迟约 100–300ms，不能多指，长按用 input swipe x y x y 700 模拟。
4. 可靠性 ACTION_CLICK > dispatchGesture > input tap；兼容性相反；延迟 input > dispatchGesture > 语义动作。策略：语义优先、坐标兜底、shell 最后，响应保留 via。
5. 点击点不能直接用 bounds.center；先与窗口/display 求交得 visibleBounds，再取可见中心；中心被遮挡时在可见矩形内网格采样；完全覆盖才 BLOCKED，并带 blocker ref。
6. 遮挡必须二维：同窗口比较 (windowLayer, drawingOrder)，跨窗口比较 AccessibilityWindowInfo.getLayer()；同时看 isTouchable/isFocused/窗口类型。当前 z = w.layer 无法发现同窗口兄弟遮挡（DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:147；UiEngine.kt:532-550）。
7. 长按/双击/拖拽/滑动差异主要在时序：长按超过 ViewConfiguration.getLongPressTimeout()（通常 500ms）；双击间隔小于 getDoubleTapTimeout()（通常 300ms）；拖拽先长按再移动；fling 是短时高速滑动，不能直接注入惯性。
8. RootHelper.gesture 支持 1–10 条 pointer path，但拒绝 startMs != 0（DSH/agent-root-helper/src/main/kotlin/dev/dsh/agent/RootHelper.kt:812），没有 willContinue；UiEngine.Gestures 只有单 stroke（EngineSupport.kt:169-205）。多指有通道、无产品接口。
9. 动作后 settle 不能只等固定 300ms；应等无新事件 120ms 且两次 capture 的 signature 稳定，上限 1.5s；无事件不等于稳定（动画可能不产生无障碍事件），400ms 无 diff 只能作降级信号。
10. ui.act 做成“单动作 + 明确后置条件”；ui.gesture 做成“原始 strokes + 预设”；ui.batch 限制 20 op、默认 stopOnError；BLOCKED/DISABLED/STALE 分别对应遮挡/禁用/引用失效，不能混成 UNKNOWN。

## 1. 四条注入路径

| 层 | API | 前提 | 延迟 | 多指 | 副作用 |
|---|---|---|---|---|---|
| 语义动作 | AccessibilityNodeInfo.performAction | 无障碍连接、节点可见、窗口活动 | 20–80ms | 不支持 | 绕过触摸分发，可能命中遮挡下的节点 |
| 无障碍手势 | AccessibilityService.dispatchGesture | canPerformGestures，API 24+ | 40–150ms + 手势时长 | ≤20 stroke | 真实触摸，受遮挡/系统手势区影响 |
| shell 注入 | /system/bin/input | shell/root，INJECT_EVENTS | 100–300ms | 不支持 | 真实触摸，进程启动开销 |
| 特权注入 | UiAutomation.injectInputEvent | UiAutomation，INJECT_EVENTS | 5–30ms | 可多 pointer | 真实触摸，需 root/特权 |

accepted / effective / stable 必须分开：accepted 是 performAction true 或手势回调完成；effective 是目标状态实际改变；stable 是连续两次 capture 的 signature 一致且无新事件。当前 UiEngine.act 只保证 accepted，change 是事后 diff（UiEngine.kt:173-199），会出现“ok 但没生效”。

## 2. 当前实现与缺口

- act：resolve→exec→settle→capture→Diff.compute（UiEngine.kt:173-199）；batch 最多 20 op（:201-252）；没有“预期效果”参数，exec 成功后不检查 change.changed。
- click（:419-434）：enabled→tapPoint→viaBackends(tap)；ACTION_CLICK 返回 true 时短路，不再坐标兜底；WebView/Flutter/游戏可能“接受但忽略”。
- longClick（:436-454）：ACTION_LONG_CLICK 失败后 Gestures.tap(700ms)；对需要 1000ms 的自定义长按不够。
- scrollNode（:471-498）：dy=±b.h/2；ACTION_SCROLL_* 失败后 Gestures.swipe(260ms)；固定半屏滚动不适合按比例接口。
- tapPoint（:532-550）：blocker = it.z > node.z && covers；从 1/4 位置 8px 网格扫描；没有 visibleBounds/drawingOrder/modal/isTouchable。EngineSupport.kt:147 的 z=w.layer 使同窗口节点 z 全相同，兄弟遮挡永远检测不到。
- settle（:703-719）：24ms + 16ms 轮询，UiClock.quietMs>=120 或 1.5s；UiClock 只在无障碍事件时更新（EngineSupport.kt:207-232）；无事件动画会被误判稳定。
- Gestures（EngineSupport.kt:169-205）：tap 60ms 单 stroke；swipe 300ms；dispatchGesture 等 2s；无多 stroke/willContinue。
- RootHelper（RootHelper.kt:525-582,742-847,917-949）：input tap/longClick/swipe/drag/fling；gesture 1–10 path、startMs 非 0 → UNAVAILABLE、16ms 步进；tap DOWN 后立即 UP。
- 已知 bug：keyEnter 只对 root 做 ACTION_FOCUS，没发 ENTER（UiEngine.kt:521-529）；Capture.pkg 取第一个 root 包名（EngineSupport.kt:82）；walk 无环检测（:157-165）。

## 3. 同类实现要点

- APP：semantic 与 motion 两路；UiObject2.click()/longClick() 优先语义动作，失败 InteractionController 注入触摸；clickGesture/longClickGesture/swipeGesture/dragGesture/flingGesture/multiPointerGesture 走 GestureController。UiElementSnapshot.java:68-85,189-190 把 drawingOrder 作为独立字段导出；动作后 waitForIdle。
- U2：d.click(x,y) 坐标，d.click(selector) 解析元素取 bounds 中心；d.long_click(duration=0.5)、d.swipe(steps=10)、d.gesture(points)、d.drag/fling/pinch 上层封装；服务端等待 wait_idle/wait_activity。
- POR：设备端无障碍服务 + 本地 HTTP；树含 bounds/window/drawing order；动作 semantic 优先，失败 dispatchGesture；多指用 GestureDescription 多 stroke；树查询与动作分离，动作只带 node id/坐标。
- MCP：坐标优先工具 mobile_click_on_screen_at_coordinates、mobile_long_press_on_screen_at_coordinates、mobile_swipe_on_screen、mobile_drag_and_drop、mobile_press_button；无 ref/节点身份。
- DROID：Python agent + Portal 驱动，动作经 Portal，元素索引来自树。
- MAE：YAML tapOn/longPressOn/swipe/scroll/dragAndDrop/fling/waitForAnimationToEnd；waitForAnimationToEnd 用连续截图比较，成本高。

## 4. 可借鉴清单

P0：drawingOrder 独立字段（APP）；visibleBounds 与窗口/display 求交；blocker ref + 原因；动作后两次 capture；via/attempts 可观测。
P1：doubleTap 预设（80–250ms）；drag 预设（hold 500ms）；fling 预设（距离/速度算 duration）；多 stroke 原始手势；a11y→uia→shell 降级链。
P2：willContinue 续接 >60s 手势；双指捏合/旋转预设；压感/尺寸/工具类型参数；速度/加速度曲线。

## 5. 不适用/反例

1. 语义 click 成功但点到遮挡节点：ACTION_CLICK 绕过窗口分发；BLOCKED 必须在动作前判。
2. 只用 z > node.z：同窗口 z 相同；drawingOrder 才是同窗口顺序；drawingOrder 也不等于 View.getZ()/elevation（C 级）。
3. isVisibleToUser=true 不等于可点：alpha=0、FLAG_NOT_TOUCHABLE、父容器裁剪；需 isTouchable + bounds 求交。
4. input tap 做双击：进程启动常超过 300ms，双击会失败。
5. dispatchGesture 点边缘会触发系统返回/主页手势；避开边缘 20–30dp。
6. 零长度 Path 做长按：StrokeDescription 要求 path 非空且长度>0（C 级）。
7. 多指 startMs：RootHelper 拒绝非 0；真正交错多指要用 StrokeDescription startTime 或自建 MotionEvent。
8. UiAutomation.injectInputEvent 需 INJECT_EVENTS，普通 App 不稳定；只用在 root-helper。
9. ACTION_SET_TEXT 不经过 IME，不触发候选词/composing text；中文/密码/WebView 见 03。
10. 固定 sleep 代替 settle：120ms 对 300–500ms 动画太短，1.5s 对无动画页面太慢。

## 6. 接口建议

### ui.act

请求：op、ref/where/point 三选一、args、options：
- backend: auto|a11y|uia|shell
- pointStrategy: visibleCenter|center|grid|firstTouchable
- avoidOcclusion: true
- settle: quietMs=120, maxMs=1500
- verify.mode: none|diff|expect
- retry: max=1, on=[STALE,UNAVAILABLE]
- timeoutMs=5000

op：tap（durationMs 40–100）、longClick（500–1500，默认 700）、doubleTap（80–250）、scroll（direction/amount）、swipe（from/to/duration/steps）、drag（holdMs 500）、fling（velocity 500–10000）、press（back/home/recents/notifications）、setText、appendText、clearText、focus。

响应：ok、op、ref、via、point、relocated、page、change、attempts、ms、warnings。ok=true 只表示动作被接受；是否生效看 change.changed/verify。BLOCKED 返回 details.blockerRef、blockerWindow、reason。

### ui.gesture

strokes 1–20、每条 2–100 点、duration 1–60000ms、startMs 0–59000、总时长 ≤60000；预设 pinchOpen/pinchClose/rotate/twoFingerSwipe/threeFingerSwipe；预设与 strokes 二选一。RootHelper 当前 startMs≠0 返回 UNAVAILABLE。返回 performed/completed/cancelled/via/ms。

### ui.batch

最多 20 op；禁止嵌套；每个 op 独立 resolve；stopOnError=true 默认，返回 failedIndex、已完成 op、最终 change；settle=each；不承诺事务/回滚。

### 错误码触发

| 码 | 条件 | 可重试 |
|---|---|---|
| BLOCKED | visibleBounds 空/找不到未覆盖点/modal 在前/窗口不可触摸 | 否 |
| DISABLED | node.enabled=false/祖先 disabled/窗口非活动 | 否 |
| STALE | ref 不在 capture/g 不符/nativeId 回收/重绑不唯一 | 是（一次，先 ui.view） |
| NOT_FOUND | where 无匹配/ref 消失 | 否 |
| AMBIGUOUS_MATCH | 多匹配/多候选 | 否 |
| NOT_EDITABLE | isEditable=false | 否 |
| TIMEOUT | settle/wait/gesture 回调超时 | 是（有限） |
| UNAVAILABLE | a11y 未连接/uia busy/root-helper 不可用/手势被拒 | 是（换后端） |
| DENIED | 权限/包可见性/受保护窗口 | 否 |
| CONFLICT | 并发动作/另一 batch 未结束 | 是（串行化） |
| BAD_REQUEST | schema/坐标/stroke 数非法 | 否 |
| INTERNAL | 引擎 bug/未知异常 | 视情况 |

状态机：resolve→visible/offscreen→enabled→occlusion→backend a11y→uia→shell→settle→diff/expect；语义动作无变化时坐标降级一次；仍无变化 ok=true+warning NO_CHANGE 或 expect TIMEOUT。BLOCKED/DISABLED 不重试后端；STALE 只重试一次且重新 ui.view；UNAVAILABLE 每层最多一次。

## 7. 测试清单

单测：U1 中心无遮挡；U2 同窗口兄弟遮挡；U3 完全覆盖；U4 跨窗口弹窗；U5 分屏；U6 半屏外；U7 零尺寸；U8 ACTION_CLICK true 无 diff 触发坐标降级；U9 false 降级；U10 settle 连续事件到 1.5s stable=false；U11 无事件两次 capture 一致才 stable=true；U12 batch 第二个失败；U13 gesture 21 stroke；U14 startMs=100。

集成：I1 a11y→uia→shell；I2 uia busy；I3 BLOCKED 不重试；I4 STALE 重试一次；I5 batch 20 tap；I6 多指 path；I7 input 长按参数；I8 dispatchGesture 取消。

真机：D1 手势导航边缘；D2 三星/小米 drawingOrder；D3 120Hz 时长；D4 分屏/自由窗口；D5 权限/ANR 弹窗；D6 IME 遮挡；D7 WebView/Flutter/Unity；D8 长按 500/700/1000ms；D9 双击 80/200/300ms；D10 fling；D11 双指/三指；D12 无障碍关闭/root-helper；D13 旋转/刘海；D14 100 次 tap 泄漏。

## 8. 参考索引

A 级：DSH/agent-app/src/dev/dsh/agent/UiEngine.kt:41-43,173-199,201-252,368-383,419-434,436-454,456-469,471-498,500-519,521-529,532-550,703-719,723-748；EngineSupport.kt:57-89,91-166,169-205,207-232；RootHelper.kt:525-582,684-685,690-740,742-801,803-847,917-927,929-949；APP UiElementSnapshot.java:68-85,189-190。

B 级：docs/agent-engine-v3.md §4/§5/§6/§8/§12；docs/agent-capability-matrix.md W1/W2/W9/W10/W12/W16；docs/research/01-tree-and-refs.md §1.4.5/§1.6；APP 56bf27a；U2 3adba4a；POR/mobilerun 0.7.4；MCP 6c94597；DROID 0.7.4；MAE 3fc4ef3。

C 级：Android GestureDescription、AccessibilityNodeInfo、AccessibilityWindowInfo、ViewConfiguration、adb input 官方文档（访问 2026-10-29）。待补 POR/U2/MCP/DROID/MAE 逐行行号。