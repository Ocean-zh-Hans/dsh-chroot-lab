# 04 · 等待 / 事件 / 稳定性 / diff

> 子代理调研稿（2026-10-29）。主题：动作之后怎样判断“稳定”，事件流怎样合并与背压，diff 怎样按 ref 连续性给出可信增量。
> 证据等级：**A** 本地源码逐行核对；**B** 同类实现/官方文档可核对；**C** 推测或必须真机验证。文中“建议”均为设计建议，不代表当前已实现。
> 引用约定：DSH/ = /root/workspace/projects/dsh-chroot-lab/；UE = DSH/agent-app/src/dev/dsh/agent/UiEngine.kt；ES = DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt；EB = DSH/agent-app/src/dev/dsh/agent/EventBus.kt；AS = DSH/agent-app/src/dev/dsh/agent/AgentServer.kt；DF = DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Diff.kt；RS = DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt。行号以本 checkout 为准，引用时写“缩写:行号”。

## 0. 结论（10 条）

1. **“稳定”不是全局布尔，而是相对于后置条件的判断。** 至少要区分四层：L0 相关事件静默、L1 连续两次 capture 的树签名一致、L2 像素/画面一致、L3 业务后置条件成立。ui.act 能自动保证的只有 L0+L1；L2 需要截图（见 06 号文档），L3 必须由调用方用 ui.wait/ui.expect 声明。把 stable 当成“动作成功”是当前最大的语义错误。
2. **当前 settle 会过早返回，根因不是阈值太小，而是没有动作起点基线。** UiClock.lastEventAt 初始为 0（ES:215-231），settle() 只等 quietMs>=120（UE:703-719）；只要动作前 120ms 没有事件，quietMs 早就满足，24ms 后即返回。即使动作前刚有事件，也不会检查动作后是否真的发生过 UI 变化，更没有第二次 capture。
3. **正确的 settle 应至少包含：动作起点、两次 capture、签名比较、相关事件静默、上限。** 建议默认 quietMs=120、minObserveMs=120、captureGapMs=50、maxMs=1500；有窗口转场/弹窗动画时 minObserveMs=350（必要时 500）；到达上限返回 stable=false，不要抛 TIMEOUT，也不要用固定 sleep 冒充等待。
4. **无事件页面必须靠“两次 capture”而不是“事件静默”兜底。** 视频、Canvas、Flutter/WebView、游戏、部分 Compose 动画可能完全不发无障碍事件；但两次树签名相同也不等于像素稳定。建议 stable 只声明“树稳定”，另设 visualStable:unknown/true/false，不要把树稳定写成画面稳定。
5. **事件流现在是一条“泛化 ui.changed”而不是可用的语义流。** AS:494-517 对 TYPE_WINDOW_STATE_CHANGED/TYPE_WINDOWS_CHANGED/前台包事件只更新 generation，随后对所有事件以 100ms 全局节流发 ui.changed{package,windowId,eventType}；TYPE_WINDOW_CONTENT_CHANGED 的 changeTypes、TYPE_VIEW_SCROLLED 的滚动量、TYPE_VIEW_TEXT_CHANGED 的前后文本全部丢失。TYPE_ANNOUNCEMENT 未处理，ANR 只靠 root helper 的 crash.anr（AS:716-729）。
6. **seq 必须统一，否则 ui.diff(since) 无从实现。** page.seq 当前是 UiClock.events（无障碍事件累计数，ES:219,226-231），EventBus.sequence 是发布事件序号（EB:27-28,71-84），二者不是一回事；ui.diff 还完全忽略 since（UE:123-126）。建议 page.seq 取 capture 时刻的 EventBus.sequence()，g 保留为 capture 代数；since 采用“最后已见 seq，排他”的语义，缺口返回 STALE+resetRequired。
7. **事件合并/去重应按“控制事件”和“噪声事件”分层。** WINDOW_STATE_CHANGED、WINDOWS_CHANGED、app.foreground、toast、crash.anr、通知 posted/removed 绝不合并、绝不丢弃；CONTENT_CHANGED/VIEW_SCROLLED/TEXT_CHANGED/泛化 ui.changed 可按 (package,windowId,type,changeTypes) 做 50–100ms 合并，保留 count/firstSeq/lastSeq。当前订阅队列满时无差别丢最旧事件，并把 events.dropped 计数在插入后清零（EB:172-189），会静默丢窗口切换。
8. **diff 的粒度应按“ref 连续性 + 字段类型 + 窗口”分三层，而不是只报 text。** 当前 DF:41-47 只比较 text/desc（二者用 else-if，同时变化只报 text）、checked、enabled、bounds；selected/focused/clickable/scrollable/visible、窗口变化、子节点集合都不比较。changeJson 又把 changed 明细丢弃，只留数量与前 5 条 text（UE:769-781）。建议输出 items[{ref,field,before,after,identity,recycled}]，并保留 changed 计数兼容旧客户端。
9. **列表复用导致的误报必须在 diff 层显式标注，不能靠“看起来像同一行”猜。** RS:12 的 structuralKey=cls|viewId|bounds 不含文本/desc/path；RS:64-132 在 nativeId 不可用时会用结构键复用 ref；NodeIds.of 失败返回 -1L，而 -1 != 0 会被当成有效 nativeId（ES:19-41、RS:73,80-82）。建议给每个 changed 项加 identity:native|structural|new 和 recycled:true，结构键匹配的连续性标为 weak，并在可滚动父容器内把“同槽位换数据”单独归类。
10. **ui.wait 与 ui.act 的职责要分开：act 只做局部 settle，跨页面/网络/列表加载用 wait。** 当前 waitFor 只支持 text/textGone/gone/idle，idle 只看 quietMs>=300（UE:149-169），不支持 ref 存在、checked/enabled 状态、expect，且每 100ms 轮询并持有 engine 锁（UE:129-147）。建议把 ui.wait 做成可取消的服务端条件等待：条件命中后至少再确认一次 capture；idle 必须由“静默+两次签名一致”定义；超时返回 TIMEOUT+最后一页+候选；ref 消失返回 STALE 而不是 NOT_FOUND。

## 1. 问题定义：等待的到底是什么

一次动作的典型时间线：

~~~text
t0  ui.act 收到请求
t1  解析 ref / where，执行 click/setText/scroll
t2  应用收到输入，主线程排队，可能 16–300ms 后才 layout
t3  窗口转场/列表加载/网络返回，事件可能持续 0–3000ms
t4  事件停止，但动画/视频/WebView 可能仍在动
t5  业务后置条件成立（文本出现、按钮可点、页面切换）
~~~

“动作被接受”（accepted）发生在 t1；“界面发生变化”（effective）可能发生在 t2–t3；“可以安全读下一棵树”（settled）要求 t4 之后；“业务成功”（verified）要求 t5。当前 ui.act 的 ok:true 只到 t1，change 是 t3 附近的事后 diff，stable 则被硬编码为 true（UE:186,248,272,311,344）。这三件事必须拆开。

| 术语 | 定义 | 谁负责 | 失败时 |
|---|---|---|---|
| accepted | 动作 API 返回 true / 手势回调完成 | ui.act 的执行层 | ok:false，错误码说明拒绝原因 |
| effective | 目标节点的状态或页面实际变化 | ui.act 的 diff | ok:true 但 change.empty=true，附 stable |
| settled | 相关事件静默且连续两次树签名一致 | settle/ui.wait(idle) | stable=false 或 TIMEOUT |
| stable | “我们相信树已经静默”，不是“没有变化” | settle 结果 | stable=false 时必须由调用方决定是否 wait |
| verified | 业务后置条件成立 | ui.wait/ui.expect | TIMEOUT/STALE/BLOCKED |

本文件的范围是“树 + 事件 + 增量”；像素级稳定性（截图 hash、OCR、动画帧）只在 06 号文档展开。本文不重复 01 号文档的遍历/可见性结论，也不重复 03 号文档的 IME/文本输入结论。

## 2. 稳定性：可操作的定义与阈值

### 2.1 四层稳定性

| 层 | 信号 | 能发现 | 发现不了 | 成本 |
|---|---|---|---|---|
| L0 事件静默 | 相关 AccessibilityEvent 停止 ≥ Q | 点击、滚动、文本变化、窗口切换 | 无事件的动画、视频、Canvas | 低 |
| L1 树签名 | 连续两次 capture 的规范化签名相等 | layout 完成、节点集合稳定 | 同签名但像素在动 | 中（一次 capture 10–100ms） |
| L2 画面 | 截图 hash/像素差/OCR | 动画、视频、Flutter/WebView 视觉变化 | 语义状态（checked 但外观未变） | 高（screencap 约 700ms，raw 约 135ms） |
| L3 业务 | 文本/ref 状态/页面谓词 | 业务是否真的成功 | 业务副作用（下单、发送） | 取决于谓词 |

建议对 ui.act 返回 stable 的定义是 **L0+L1**，不是 L2/L3；对 ui.wait(idle) 使用同一套 L0+L1；对“动画结束”才使用 L2 或显式 animation:true 的 L1 延长观察。stable 不是 change.empty：一次成功的点击可能 stable=true 且 changed=2；一次无变化的点击也可能 stable=true 且 changed=0。

### 2.2 判定公式

设动作开始时刻为 t0，capture 时刻为 t，最近一次“相关事件”时刻为 e：

~~~text
quiet(t)   = t - max(t0, e)          // 动作前的事件不算动作后的静默
observed(t)= t - t0
stable(t)  = observed(t) >= minObserveMs
          && quiet(t) >= quietMs
          && captureCount >= 2
          && sig(capture[n]) == sig(capture[n-1])
          && !windowSwitched
          && !capturePartial
~~~

关键点是 quiet 的分母用 max(t0,e)，不是 now-lastEventAt。当前实现用后者，且 lastEventAt 初值为 0，所以只要开机后没有事件，quietMs 就是开机时长；动作前 10 分钟有过一次事件，也是一样。这样 settle() 的 24ms 睡眠一结束就满足 120ms，直接返回。

另一个关键点是 captureCount>=2 与 sig 比较必须真的发生。当前 signature() 只用于判断是否给 g 加一（UE:654-679），settle() 从不 capture，所以 L1 完全没有实现。

### 2.3 阈值与上限

| 参数 | 建议默认 | 允许范围 | 说明 |
|---|---|---|---|
| quietMs | 120ms | 80–200ms | 约 7 帧@60Hz；覆盖点击 ripple/短 debounce，不覆盖 300ms 转场 |
| minObserveMs | 120ms | 80–350ms | 防止“动作前已静默”导致 24ms 返回；无事件页面至少观察这么久 |
| captureGapMs | 50ms | 32–80ms | 两次 capture 之间至少隔 2–3 帧；capture 本身有耗时，实际间隔按开始/结束都记录 |
| maxMs | 1500ms | 1000–2000ms | 设计文档 §6 的上限；到达即 stable=false，不是 TIMEOUT |
| 动画观察 | 350ms | 300–500ms | 页面转场、弹窗、抽屉；不是固定 sleep，仍以两次签名为准 |
| ui.wait 默认 | 8000ms | 500–60000ms | 设计文档 §4 默认 8s；MCP 外层超时应大于它 |
| ui.wait(idle) 静默 | 300ms | 120–500ms | 比 settle 更保守，避免列表分页/图片加载间隙误判 |
| 轮询/唤醒 | 50ms 事件唤醒 | 16–100ms | 优先等事件 generation 变化，超时才轮询；不要 16ms 忙等 |

“动画 300–500ms”不是“睡 300–500ms”。正确做法是：已知有窗口转场/弹窗时把 minObserveMs 提到 350–500，同时仍要求 quiet>=120 和两次签名一致；如果事件持续到 1.5s，返回 stable=false，由 ui.wait(idle) 继续等。

### 2.4 当前实现的失败链（逐条）

1. **基线错误。** UiClock.lastEventAt 初始 0（ES:215-217），quietMs() 直接 uptime-lastEventAt（ES:231）；settle() 先 Thread.sleep(24)，再在 quietMs>=120 时 return（UE:703-719）。动作前无事件时必然 24ms 返回。
2. **动作前事件同样致命。** 若动作前 200ms 有事件，quietMs 已 ≥120，仍 24ms 返回；t0 根本没有参与判断。
3. **没有第二次 capture。** settle() 只读时钟，不抓树、不算签名；signature() 只在 captureNow() 里用于 g += 1（UE:654-679）。
4. **签名不完整。** signature 只含 pkg|activity|keyboard|nodes.size 和每节点 cls.hashCode,text.hashCode,desc.hashCode,bounds（UE:671-679），不含 checked/enabled/selected/focused/viewId；一个 Switch 的 checked 变化可能不 bump g，下一帧页面的 g 也不变。
5. **所有事件都重置 quiet。** onAccessibilityEvent 第一行就 UiClock.note()（AS:494-495），包括 systemui 的状态栏、通知、导航栏、触摸探索等；UiClock.note() 不区分包/类型（ES:226-229）。在系统 UI 频繁发事件的设备上，quiet 可能永远不达标，settle 反而每次跑满 1.5s。
6. **stable 全硬编码。** Diff.compute(..., true) 在 act/batch/fill/scroll 等路径都传 true（UE:125,186,248,272,311,344），Change 默认值也是 true（DF:9,20）。因此客户端无法知道 settle 是否超时、树是否连续。
7. **ui.diff 拿缓存树且忽略 since。** diff() 调 current(params)，后者在 30s 内直接返回缓存 view（UE:123-126,646-651）；previous 与 v 可能是同一个对象，diff 必然空；since 完全没读。
8. **waitFor 不是稳定等待。** 每 100ms captureNow，条件一命中就返回（UE:129-147）；text 用 Where.text 子串匹配（UE:149-154），可能命中旧节点/隐藏节点；idle 只看 quietMs>=300（UE:165-167），不看两次签名；不支持 ref 存在、checked/enabled、expect。
9. **seq 用错。** Capture.capture(svc,g,UiClock.events)（UE:654-657）；page.seq 是 a11y 事件累计数（UE:761-768），不是 EventBus.sequence。客户端拿 page.seq 去 ui.diff(since) 时，两个序号不在同一域。
10. **长等待持有全局锁。** waitFor 是 locked {}（UE:129）；等待 8s 期间 ui.view/ui.act/events 都被串行化。对单会话可接受，对多会话/取消/心跳不可接受；至少应在 sleep 时释放锁，或把 wait 放到独立协程。

### 2.5 哪些场景必须 ui.wait，不能只靠 settle

| 场景 | 为什么 settle 不够 | 应使用 |
|---|---|---|
| 点击后网络请求返回、页面跳转 | 1.5s 上限内可能只有 loading；真实内容在 2–10s 后 | ui.wait(for.text) 或 for.ref |
| 列表滚动到底加载下一页 | 滚动事件停止 ≠ 数据到齐；可能出现 footer/占位 | ui.wait(for.idle) + for.text |
| 登录/提交后等结果 | 业务副作用不在树里；按钮可能先 disabled 再 enabled | ui.wait(for.text)/ui.expect |
| 权限弹窗/ANR 弹窗 | 弹窗是独立窗口，settle 可能只看到 app 树 | ui.wait(for.ref) + 弹窗专用事件/窗口规则 |
| 输入法候选/中文输入 | 键盘动画和候选栏事件持续，但文本已可用 | ui.wait(for.text) 或回读节点；不要等键盘完全静止 |
| Toast | Toast 通常不进入 app 树，且 TYPE_NOTIFICATION_STATE_CHANGED 与通知混用 | events.toast/ui.wait(for.toast)，不要用 ui.wait(text) 猜 |
| 长动画/视频 | 无事件且树签名不变 | 只能 L2 截图或业务后置条件；settle 只能给 stable=false/unknown |

## 3. 事件流：从 AccessibilityEvent 到可消费事件

### 3.1 当前映射与缺口

| Android 事件 | 当前处理（A 级） | 丢失的信息 | 建议 |
|---|---|---|---|
| TYPE_WINDOW_STATE_CHANGED | 更新 UiClock.page/EventBus.foreground，发 app.foreground（包变化时），并进入 ui.changed（AS:498-513） | 窗口类型、窗口层级、是否 Dialog/Activity | 发 window.changed，带 windowId/windowType/className/package；只有应用窗口才更新 app activity |
| TYPE_WINDOWS_CHANGED | 只触发 uiChanged，无 payload（AS:498-501） | 哪个窗口增删/层级变化 | 发 windows.changed，带窗口集合摘要；用于多窗口/分屏/弹窗 |
| TYPE_WINDOW_CONTENT_CHANGED | 只进入泛化 ui.changed（AS:509-513） | changeTypes 位掩码（SUBTREE/TEXT/DESCRIPTION/STATE/PANE/…） | 发 content.changed，带 changeTypes；只在相关包/窗口时重置 quiet |
| TYPE_VIEW_SCROLLED | 只进入泛化 ui.changed，且要求包是前台包（AS:498-513） | scrollX/scrollY/fromIndex/toIndex/itemCount（若事件提供） | 发 scroll.changed，合并为 100ms 一条，带首末位置与 count |
| TYPE_VIEW_TEXT_CHANGED | 只进入泛化 ui.changed | beforeText/text（若可用）、selection | 发 text.changed，只带长度/摘要，不回显密码原文；高频率合并 |
| TYPE_NOTIFICATION_STATE_CHANGED | 一律发 toast{package,text}（AS:515-517） | 通知与 Toast 的区别、Notification 对象、key | 拆 toast/notification.state；按 className/Notification 判定；Toast 文本可能被系统截断 |
| TYPE_ANNOUNCEMENT | 未处理 | 无障碍播报文本/live region | 发 announcement，只作提示，不作为页面内容 |
| TYPE_VIEW_TEXT_SELECTION_CHANGED/FOCUSED/CLICKED/TOUCH_* | 全部经 UiClock.note() 重置 quiet（AS:495） | 无 | 不计入 settle 的相关事件；需要时单独发 focus.changed 等 |
| ANR | 仅 root helper 的 crash.anr 通道（AS:716-729） | 本地 a11y 事件没有 ANR 类型 | 规则识别：AppNotRespondingDialog/窗口标题 + root 事件；C 级待真机 |

官方文档把 TYPE_WINDOW_STATE_CHANGED、TYPE_WINDOW_CONTENT_CHANGED、TYPE_VIEW_SCROLLED、TYPE_VIEW_TEXT_CHANGED、TYPE_NOTIFICATION_STATE_CHANGED、TYPE_ANNOUNCEMENT 列为独立事件类型；TYPE_WINDOW_CONTENT_CHANGED 带 changeTypes 位掩码（CONTENT_CHANGE_TYPE_SUBTREE/TEXT/CONTENT_DESCRIPTION/STATE_DESCRIPTION/PANE_*/...），这是当前实现完全没有利用的语义（B 级，URL 见 §10）。

### 3.2 事件信封与两套序号

建议统一信封：

~~~json
{
  "event": "content.changed",
  "seq": 4219,
  "ts": 1760000000123,
  "bootId": "8f2c...",
  "data": {
    "package": "com.example",
    "windowId": 42,
    "windowType": "application",
    "changeTypes": ["TEXT"],
    "count": 3,
    "firstSeq": 4217,
    "lastSeq": 4219
  }
}
~~~

| 名称 | 当前来源 | 语义 | 问题/建议 |
|---|---|---|---|
| event.seq | EventBus.sequence，发布时 +1（EB:28,71-84） | 全局发布事件序号 | 保留；所有事件共享，含 events.dropped |
| bootId | UUID.randomUUID()（EB:27） | 进程/服务启动代 | 保留；重启必变，客户端据此 reset |
| page.seq | UiClock.events（ES:219,226-229） | 无障碍事件累计计数 | **改为 capture 时刻的 EventBus.sequence()**；若保留旧值，改名 a11ySeq，不要叫 seq |
| page.g | UiEngine.g（UE:654-668） | capture 代数，签名变化才 +1 | 保留；和事件 seq 分开，不要混用 |
| EventBus.generation | uiChanged() 时 +1（EB:53-57） | UI 变化信号，waitForChange 使用 | 目前 waitForChange 无调用者；可作为 settle 的事件唤醒源 |
| baseSeq | replay 返回的当前 sequence（EB:108-122） | 回放基准 | 订阅响应中返回；客户端从 baseSeq+1 开始收 |
| resetRequired | bootId 不匹配、since 超前、落后超过环大小（EB:111-113） | 无法补齐，必须全量刷新 | 保留；ui.diff 应以 STALE 错误暴露，不要假装空 diff |

since 语义必须写死：**since 是“客户端最后已见 seq”，服务端回放 since+1 .. current，排他**。当前 replay 正是从 max(1,since+1) 开始（EB:114-120），但 since<=0 返回空（EB:110），意味着“新订阅从当下开始”，不是“从头回放”。这要在文档和 SDK 中明确，否则客户端会把空回放当成没有变化。

### 3.3 合并与去重

事件合并的目的不是省带宽，而是让 ui.wait(idle) 不被事件风暴拖死。建议按优先级分三层：

| 层 | 事件 | 合并策略 | 丢弃策略 |
|---|---|---|---|
| 控制 | window.changed、windows.changed、app.foreground、toast、crash.anr、notification.posted/removed | 不合并，立即发 | 永不丢；队列满时关订阅并标 reset |
| 结构 | content.changed（SUBTREE/PANE）、ui.changed 泛化 | 50ms 窗口按 (pkg,windowId,type,changeTypes) 合并；保留 count/firstSeq/lastSeq | 可合并、可丢中间，保留首末 |
| 高频 | scroll.changed、text.changed、focus.changed、selection.changed | 100ms 合并；滚动只留首末 scrollX/Y、from/toIndex、count | 可丢中间；文本只留摘要/长度 |

去重键建议：

~~~text
key = package | windowId | eventType | changeTypesMask | sourceRefHash
~~~

- 完全相同 key 且 payload 摘要相同、间隔 <100ms：只累加 count，不重复发。
- 同一 key 的首个事件立即发（leading），窗口结束再发一条 merged（trailing）；不要把所有事件都延迟 50ms。
- 控制事件不参与合并；WINDOW_STATE_CHANGED 连续两次（Activity A→B→A）不能折叠成一次。
- 合并事件保留 firstSeq/lastSeq，客户端可用 lastSeq 作为下一次 since；不要用合并事件的 seq 当唯一事件序号。
- 不在事件总线里做“按文本去重”：文本相同但 checked 变化是两次不同状态，折叠会丢 diff 语义。

### 3.4 背压：当前实现与建议

当前（A 级）：

- 每个订阅 ArrayBlockingQueue(256)（EB:166）；offer 满时 poll() 丢最旧，dropped++，插入 events.dropped{count}，再丢旧插新；最后 dropped=0（EB:172-189）。
- 结果：窗口切换等关键事件可能被丢；events.dropped.count 不是累计值，而是“自上次 notice 后”的计数；notice 本身也可能被后续丢弃。
- 事件生产者在 publish 的 synchronized 内对每个订阅 offer（EB:90-95）；offer 非阻塞，不会卡 a11y 线程，但队列满时静默丢。
- 订阅上限 8（EB:125-130）；无 events.poll，只有流式 take（EB:191-198）。

建议：

1. 每订阅仍保留有界队列（256–1024），但入队按优先级：控制事件单独预留容量（例如 32 个 slot），噪声事件只能使用剩余容量。
2. 噪声满时丢最旧的低优先级事件，并累加 droppedTotal；插入一条 events.dropped{droppedTotal, fromSeq, toSeq, kinds}，该 notice 不占普通队列，或放在控制保留区。
3. 控制事件无法入队时，不要静默丢：关闭该订阅，发送 events.reset{reason:"slow_consumer", baseSeq}，客户端必须重新 ui.view。
4. events.dropped 用累计计数，不在插入后清零；同时给 firstDroppedSeq/lastDroppedSeq，让客户端知道缺口范围。
5. 生产者永不阻塞；如果所有订阅都慢，丢事件优于卡住 AccessibilityService 主线程。
6. 提供 events.poll（见 §5.3）给请求-响应客户端；轮询天然有背压，不需要每客户端队列。

### 3.5 Toast / ANR / 权限弹窗 / IME 的差异

| 场景 | 可观测信号 | 陷阱 | 建议 |
|---|---|---|---|
| Toast | TYPE_NOTIFICATION_STATE_CHANGED，className 常为 Toast，text 为提示 | 与普通通知同类型；文本可能被系统截断；Android 版本/厂商差异；Toast 不一定进窗口树 | 发 toast 事件，带 text 摘要；ui.wait 增加 for.toast，不要用 ui.wait(text) 等树节点 |
| 通知 | NotificationListenerService 的 posted/removed；TYPE_NOTIFICATION_STATE_CHANGED 也可能有 | 通知栏 shade 是 systemui 窗口，展开会改变窗口树 | 通知事件与页面事件分开；通知 shade 打开时不要把 systemui 的 content change 当作 app 变化 |
| ANR | root helper crash.anr；窗口树出现“应用无响应”Dialog；logcat am_anr | 没有 TYPE_ANR 这个 a11y 事件；厂商对话框类名不同 | 规则：窗口标题/类名 + root 事件 + 前台包；命中后 BLOCKED，并暴露 blocker |
| 权限弹窗 | TYPE_WINDOW_STATE_CHANGED，包名 com.android.permissioncontroller/com.google.android.permissioncontroller，窗口类型 system | 不同版本包名/类名不同；弹窗可能覆盖 app 但 app 树仍在 | 捕获所有窗口；modal:true；ui.wait 默认允许等待弹窗出现，目标被盖时 BLOCKED |
| IME | TYPE_INPUT_METHOD 窗口；键盘显示时 Capture.keyboard=true；输入法会发 content/text/selection 事件 | 键盘动画/候选栏造成持续事件；键盘窗口的 content change 会重置 app quiet | UiClock 只把 IME 的 keyboard 状态变化当相关；IME 内部 content/text 不计入 app settle；capture 仍包含键盘窗口并标 kb |

这里的原则是：**页面内容稳定、覆盖层稳定、输入法稳定是三件事**。stable 只能声明当前 capture 的树稳定；一个 Toast 出现不应该让 app 的 settle 多等 2 秒，但一个权限弹窗/ANR 弹窗必须让后续动作返回 BLOCKED。

### 3.6 相关事件过滤：UiClock.note(event) 而不是 note()

建议把 UiClock.note() 改为 note(event, foregroundPackage, targetWindowId)，只对“影响当前页面树”的事件更新时间戳：

| 事件 | 是否重置 settle quiet | 说明 |
|---|---|---|
| WINDOW_STATE_CHANGED / WINDOWS_CHANGED | 是 | 窗口/Activity/Dialog 变化，可能整棵树换 |
| WINDOW_CONTENT_CHANGED | 是，仅当前前台包/目标窗口 | 后台包/状态栏的 content change 不算 |
| VIEW_SCROLLED | 是，仅目标窗口 | 滚动会改变 bounds/可见节点 |
| VIEW_TEXT_CHANGED | 是，仅目标窗口且非 IME | 输入导致布局变化 |
| VIEW_TEXT_SELECTION_CHANGED / FOCUSED / CLICKED | 否 | 可能只是光标/触摸反馈，不应拖满 settle |
| NOTIFICATION_STATE_CHANGED | 否（单独 toast 事件） | 通知/Toast 不是页面内容 |
| ANNOUNCEMENT | 否 | 无障碍播报，不代表视觉变化 |
| TYPE_TOUCH_INTERACTION_* / hover | 否 | 高频噪声 |
| systemui 的状态栏/导航栏/通知 shade | 仅当窗口层级/前台窗口变化 | 不能无条件重置 quiet；当前 AS:495 先 note 再过滤，是反例 |

同时，UiClock.page 不应被 systemui/IME 的 WINDOW_STATE_CHANGED 覆盖：当前 AS:503-507 对所有包更新 UiClock.page(pkg, event.className)，随后 Capture.activity 只在 pkg == UiClock.pkg 时填（ES:86-87），系统弹窗/输入法会把 app activity 冲掉。建议分开 foregroundApp（应用包/Activity）与 topWindow（含 systemui/IME/Dialog）。

## 4. diff：粒度、连续性、误报

### 4.1 契约

建议 ui.act/ui.diff 的 change 结构：

~~~json
{
  "ok": true,
  "page": {"g": 7, "seq": 4219, "pkg": "com.example", "activity": ".Main", "kb": false},
  "change": {
    "added": 0,
    "removed": 0,
    "changed": 2,
    "items": [
      {"ref":"@e4","field":"text","before":"发送","after":"已发送","identity":"native","recycled":false},
      {"ref":"@e4","field":"enabled","before":"true","after":"false","identity":"native","recycled":false}
    ],
    "text": ["已发送"],
    "stable": true,
    "quietMs": 128,
    "fromSeq": 4210,
    "toSeq": 4219,
    "truncated": false
  }
}
~~~

兼容策略：保留 changed 为数量、text 为前 5 条摘要；新增 items 明细。旧客户端可只读计数字段；新客户端不应再从 text 猜字段。added/removed/changed 建议明确为节点条目数，并在 items 中给 ref（当前 added/removed 是带描述的字符串列表，DF:25,35,54-56）。

stable 与 empty 解耦：

- stable=true 且 empty=true：树静默，确实没看到变化（可能是无效动作）。
- stable=true 且 empty=false：正常动作结果。
- stable=false 且 empty=true：没抓到变化，但可能是动画/超时/截断；不要断言“没发生”。
- stable=false 且 empty=false：看到了部分变化，但不完整；调用方应 wait/重读。

### 4.2 字段粒度

| 字段 | 当前 | 建议 | 误报/注意 |
|---|---|---|---|
| text | 比较，报 text（DF:41） | 保留；before/after 截断到 80 字 | 密码框必须 [redacted]；不要把 hint 当 text |
| desc | 比较，但 else if（DF:42） | 与 text 独立比较，各自一条 item | 同时变化时两条都要报，否则丢失语义 |
| checked | 比较（DF:43） | 保留，before/after 用 boolean | 只对 CHECKABLE 节点；不要对容器报 |
| enabled | 比较（DF:44） | 保留 | 按钮从 disabled→enabled 是重要信号，应保留 |
| selected | 未比较 | 比较 Flags.SELECTED | Tab/列表选中态；可与 focused 分开 |
| focused | 未比较 | 比较 Flags.FOCUSED，但默认从 stableSignature 排除 | 光标闪烁/输入焦点会抖动，不能让它阻止 stable |
| clickable/scrollable/editable/visible | 未比较 | 可选 fields 参数开启 | 动态 View 可能频繁变；默认不报，避免噪声 |
| bounds | 同窗口且 next meaningful 才比较（DF:45-47） | 保留；整窗等量位移折叠成 scroll | 列表滚动会让所有节点 bounds 变化；逐条报会淹没 diff |
| window | 不比较 | 窗口变化单独 window.changed，并把旧窗口 ref 标 stale | 跨窗口 ref 不应复用；同 window 才比较 bounds |
| children | 不比较 | 可选 structure:true 时比较子节点集合/顺序 | 容器重排可能无文本变化；默认关闭以免噪声 |
| hint/viewId | 不比较 | 可选 | viewId 变化常意味着控件替换；hint 变化不等于用户文本 |

isMeaningful 当前包含 label/clickable/editable/scrollable/CHECKABLE（DF:59-60），会过滤纯容器。建议 diff 输出同时给 meaningfulOnly 标志；如果调用方要找布局变化，需显式 includeContainers:true，否则容器 bounds 变化永远不会出现。

### 4.3 列表复用：三种身份要分开

列表场景的难点不是“文本变了”，而是“同一个 ref 到底还是不是同一行”。建议把身份分成：

| 身份 | 判定 | diff 行为 | 示例 |
|---|---|---|---|
| native | nativeId 相同且同窗口（RS:73,80-82,150-157） | 同一 View 实例；text/checked 变化报 changed；若父容器可滚动且子项内容整体换，附 recycled:true | RecyclerView 复用同一个 itemView，数据绑定到新条目 |
| structural | nativeId 不可用，cls/viewId/bounds 同窗口唯一（RS:92-106） | 允许复用 ref，但 identity:"structural"、continuity:"weak"；报告 recycledSuspect:true | 旧设备/反射失败；同位置同类型行 |
| new | 无匹配，分配 @eN（RS:108-115） | added；旧 ref 若未出现则 removed | 真正新增一行/换页 |

当前 structuralKey 不含 text/desc/path（RS:12），所以“同 cls、同 viewId、同 bounds、不同数据”的行会被当成同一个 ref；如果 nativeId 也相同（RecyclerView 复用 itemView），那它确实是同一个 View 实例，但业务条目已经换了。此时只报 text changed 会让 agent 以为“原来那条记录的文字变了”。建议：

1. 在 ChangedItem 增加 identity 与 recycled 字段；recycled=true 时客户端必须把该条当成“槽位内容变化”，不能沿用条目标识。
2. 对可滚动父容器（SCROLLABLE 祖先）内的直接子项，比较“可见子项集合”；如果同一槽位的子项文本/desc 大幅变化且 item 数量不变，优先标 recycled:true，同时保留 changed 明细（不要额外制造 removed+added 双计数）。
3. 如果 nativeId 不可用且结构键匹配到多个候选（cands.size != 1），不要复用；分配新 ref，旧 ref 自然 removed（RS:96-105）。这比错误复用更安全。
4. NodeIds.of 失败返回 -1（ES:34-39），RS:73,80 的 nativeId != 0L 会把 -1 当有效；应先规范化为 0/无效，或 RefStore 只接受 >0。这是列表误绑的放大器。
5. RefStore.score 的文本相似度、阈值 0.72、margin 0.08（RS:168-177,208-215）只用于“ref 重定位”，不要用它证明 diff 连续性；diff 只认 bind 给出的 ref。

### 4.4 窗口切换与整树重建

- 当 Capture 的窗口集合变化（Activity 切换、Dialog 出现、分屏、旋转）时，旧 ref 的 window id 不再匹配；RefStore.bind 的 nativeId/结构键都要求同窗口（RS:81,97），因此旧 ref 会 removed、新树 added。这是正确的，但 diff 可能一次输出几十条 added/removed，需要上限与摘要。
- 建议 change 增加 windowChanged:true、fromWindow/toWindow、reset:true；当窗口集合变化超过阈值（例如 >50% refs 失效）时，直接返回 reset:true + 新页面摘要，而不是 20 条 added/20 条 removed 的截断列表。
- 旋转/分屏时 bounds 全变；不要逐节点报 bounds，报 layout.changed{rotation,screen,window}，并提供 boundsShift 统计。
- 整树重建（g 跳变、snapshot seq 不连续）时，stable 不能仅凭两次签名相同就 true；至少要等一次完整 capture 且 capturePartial=false。

### 4.5 stable=false 的判定清单

| 条件 | 说明 | 建议附加字段 |
|---|---|---|
| 到达 maxMs 仍有相关事件 | 事件风暴/持续动画 | reason:"event_storm"、lastEventType、lastEventAgeMs |
| 两次 capture 签名不同 | 树仍在变 | reason:"tree_changing"、changedNodes |
| capture 被预算截断/失败 | 2000 节点上限、窗口 root 为空、recycle 异常 | reason:"capture_partial"、captureMs |
| settle 期间窗口/前台包变化 | 页面已切换，旧 diff 基线失效 | reason:"window_switch"、from/to |
| EventBus bootId 变化/seq 缺口 | 事件流断代 | reason:"event_gap"、resetRequired:true |
| 只有像素动画、树签名不变 | 树稳定但画面不稳定 | visualStable:"unknown"（不要谎报 true） |

## 5. 接口建议

### 5.1 ui.wait

请求：

~~~json
{
  "for": {"text": "登录成功", "match": "contains", "scope": {"ref": "@e1"}},
  "timeoutMs": 8000,
  "requireStable": false,
  "pollMs": 50
}
~~~

条件表：

| 条件 | 参数 | 语义 | 当前 |
|---|---|---|---|
| text | text, match=contains|exact|regex, scope, visible | 在快照中找文本；默认 contains，去首尾空白 | 支持 text，仅子串（UE:149-154） |
| textGone | 同上 | 文本从当前快照消失 | 支持（UE:155-159） |
| idle | quietMs=300, stableCaptures=2 | 相关事件静默 + 两次签名一致 | 仅 quiet>=300（UE:165-167） |
| ref | ref, state=exists|gone|enabled|disabled|checked|unchecked|focused | 按 ref 连续性判断状态 | 仅 gone（UE:160-164） |
| expect | 同 text/ref，timeoutMs=0 | 立即断言，失败给候选，不等待 | 未实现；设计 R5 计划 M3（矩阵:51） |
| toast | text, match | 等 toast 事件，不查树 | 未实现；事件已有 toast |

返回：

~~~json
{"ok":true,"waitedMs":842,"matched":{"ref":"@e12","text":"登录成功"},
 "page":{"g":9,"seq":4301},"stable":true,"polls":17}
~~~

超时：

~~~json
{"ok":false,"code":"TIMEOUT","retryable":true,"elapsedMs":8000,
 "condition":{"text":"登录成功"},
 "last":{"page":{},"observed":{"text":["登录中"],"refs":["@e4"]}},
 "hint":"页面仍在加载；可增大 timeoutMs 或先 ui.wait(idle)"}
~~~

错误语义：

- TIMEOUT：条件在 timeoutMs 内未成立；附最后一帧、观察到的候选、stable；可重试。
- STALE：ref 在等待期间失效（节点被移除/窗口切换），或 since 缺口；附最新 ref/候选和 resetRequired；可重试（重读后）。
- UNKNOWN：重复 capture 失败、引擎内部异常、无法解释的树不一致；附 cause 与诊断；不要用它代替 TIMEOUT/STALE/UNAVAILABLE。
- UNAVAILABLE：无障碍服务断开；BLOCKED：目标被模态窗口遮挡；CONFLICT：前台包/Activity 与预期不符；BAD_REQUEST：条件为空/互斥参数。
- 当前 resolve 对 ref 消失抛 NOT_FOUND（UE:723-734），应在 wait 路径改成 STALE；NOT_FOUND 只用于“where 初次匹配不到”。

实现建议：

1. 进入 wait 时记录 baseSeq=events.sequence()、t0=uptime；条件命中后至少再 capture 一次（可选 requireStable 时等两次签名）。
2. 每次循环先 capture，再判断；不要在 locked 里 sleep；用 EventBus.generation 的 monitor/condition 等待，超时才按 pollMs 轮询。
3. 条件里的 text 匹配要排除不可见节点（isVisibleToUser/VISIBLE）和 password 文本；scope 用 ref 的子树，避免全树子串误命中。
4. idle 不要只等事件；必须两次 capture；若 1.5s 内事件不断，返回 stable:false 而不是无限等。
5. 支持取消：请求带 requestId，服务端 ui.cancel 或连接断开时退出；不要让 8s wait 阻塞其他会话。

### 5.2 ui.diff

请求：

~~~json
{"since": 4210, "fresh": true, "fields": ["text","desc","checked","enabled"], "maxItems": 80}
~~~

- since：最后已见 page.seq，排他；省略时与“上一次 capture”比较，since:0 可返回当前页面的 added 基线（不要返回全量 2000 节点，只返回 meaningful）。
- fresh：默认 true；强制 captureNow()，不能复用 30s 缓存。当前 current() 的缓存是 UE:646-651。
- fields：默认 text,desc,checked,enabled,bounds；可选 selected,focused,clickable,scrollable,visible,structure。
- maxItems：默认 80；超过设 truncated:true，并给 addedTotal/removedTotal/changedTotal。
- since 无法满足（旧于快照环/seq 缺口/bootId 变化）：返回 STALE，附 resetRequired:true、baseSeq、当前 page；不要返回空 diff。
- 无 since 且首次调用：Diff.compute(prev=null) 当前只取有 label 的前 20 个（DF:20-26）；建议改为“有意义的 added 前 20 + 总数”。

### 5.3 events.*

#### subscribe

~~~json
{"types":["window.changed","toast","crash.anr"],"packages":["com.example"],"since":4210,"processId":"..."}
~~~

响应：{subscribed:true,bootId,baseSeq,resetRequired,heartbeatMs}（当前 AS:695-714 已有大部分字段；processId 应改为 bootId 兼容别名）。随后逐条推送事件；服务端每 15s 发 events.heartbeat{seq,processId,time}（AS:759-766）。

#### poll（当前缺失）

~~~json
{"since":4210,"types":["content.changed","scroll.changed"],"limit":100,"timeoutMs":0}
~~~

响应：{events:[...],nextSeq:4310,baseSeq:4310,resetRequired:false}。timeoutMs>0 可做长轮询；since=0 表示从当前开始（不返回历史）。poll 适合 MCP 请求-响应，不占订阅队列；建议优先实现它，而不是只扩订阅。

#### unsubscribe

{subscriptionId:"..."}；关闭队列、释放 slot；重复 unsubscribe 幂等。当前 AS:768-772 有 unsubscribe，但需确认关闭 root event socket。

事件类型建议：

| 类型 | 触发 | 关键字段 |
|---|---|---|
| window.changed | TYPE_WINDOW_STATE_CHANGED | package, windowId, windowType, className, activity |
| windows.changed | TYPE_WINDOWS_CHANGED | added/removed window ids, layer, type |
| content.changed | TYPE_WINDOW_CONTENT_CHANGED | changeTypes, package, windowId, count |
| scroll.changed | TYPE_VIEW_SCROLLED | scrollX/Y, from/toIndex, itemCount, count |
| text.changed | TYPE_VIEW_TEXT_CHANGED | 长度/摘要，不回显密码；before/after 长度 |
| toast | TYPE_NOTIFICATION_STATE_CHANGED 且判定为 Toast | text 摘要, package |
| notification.posted/removed | NotificationListener | key, package, title/text 摘要 |
| app.foreground | 前台应用包变化 | package, previous |
| crash.anr | root helper / 规则识别 | package, reason, rootSeq |
| events.dropped | 队列丢弃 | droppedTotal, fromSeq, toSeq, kinds |
| events.heartbeat | 定时 | seq, processId, time |

### 5.4 错误码：TIMEOUT / STALE / UNKNOWN

| 码 | 触发 | retryable | 应带字段 | 不应混淆 |
|---|---|---|---|---|
| TIMEOUT | ui.wait 条件到期；ui.act 的 settle 超时不应报 TIMEOUT（应 ok:true,stable:false） | true | elapsedMs, condition, last.page, last.observed, stable | 不要用于 capture 失败/服务断开 |
| STALE | ref 失效、窗口切换、since 缺口、bootId 变化 | true（重读后） | latestRef, candidates, resetRequired, baseSeq, page | 不要用 NOT_FOUND 表示“曾经存在但已消失” |
| UNKNOWN | 未分类内部异常、重复 capture 失败且非 UNAVAILABLE、树/索引不一致 | false/视情况 | cause, diagnostics, requestId | 不要把所有失败都吞成 UNKNOWN；优先 BAD_REQUEST/BLOCKED/DISABLED/STALE |
| UNAVAILABLE | 无障碍服务未连接/被系统杀死 | true（重连后） | service, since | 不是 UNKNOWN |
| BLOCKED | 模态弹窗/遮挡者覆盖目标 | false（先处理弹窗） | blocker.ref, blocker.text, windowType | 不是 TIMEOUT |
| CONFLICT | 前台包/Activity 与预期不符 | true | expected, actual | 不是 STALE |
| NOT_FOUND | where 初次匹配不到 | true（重读后） | candidates | 不是“ref 曾经存在” |

stable:false 本身不是错误码；ui.act 仍可 ok:true。ui.diff 的 STALE 应携带 resetRequired:true，客户端据此丢弃本地 ref/seq 并重新 ui.view。

### 5.5 并发、取消与锁

- 当前所有 ui.* 走 locked {}（UE:123,129,173,201,254）；ui.wait 长睡眠会阻塞其他请求。建议：wait 期间释放 engine 锁，只对 capture/ref 表更新加短锁；用 per-session Mutex 串行化写动作，读请求可并行。
- captureNow() 会修改 previous/view/refs；两个并发 capture 会互相覆盖 diff 基线。建议一个 session 同时只允许一个 capture；读请求共享最近快照。
- ui.wait 取消：连接断开/客户端取消/服务停止时，设置 cancelled 并 notifyAll；不要等满 8s。
- events.* 的 publish 不得等待订阅者 socket；当前 offer 非阻塞是对的，保留。
- settle 由 action 线程执行；事件线程只更新 UiClock/generation，不能反向调用 captureNow()（避免死锁/递归）。

## 6. 同类实现对比

| 项目 | 机制 | 树稳定 | 动画/无事件 | 背压/失败 | 可借鉴 |
|---|---|---|---|---|---|
| Appium UiAutomator2 | Device.waitForIdle() 读 setting，默认 10s；调用 UiDevice.waitForIdle(timeout)；catch 所有异常仅 log；Click 后调用（APP/.../Device.java:68-86、WaitForIdleTimeout.java:23、Click.java:38） | 只等系统 idle，不等于两次树签名 | 动画/Canvas 不可靠 | 超时也继续，无 stable 声明 | 动作后等待的产品化位置；失败不阻塞 |
| Maestro | waitForAppToSettle 连续抓两次 hierarchy，相等且 root is-loading=false 才返回；有 timeout 忙循环，无 timeout 10 次×200ms（MAE/.../ScreenshotUtils.kt:38-74） | 两次树相等，最接近本建议 | 有 waitForAnimationToEnd（截图） | null 明确表示“未能确认稳定”（Driver.kt:96-104）；滚动后 null 会重新 stabilize（Maestro.kt:233-245） | 两次 capture + 显式 null/unknown + loading 标志 |
| Portal / droidrun | 无障碍服务只对 TYPE_WINDOW_STATE_CHANGED 记 activity；CONTENT_CHANGED/SCROLLED 分支为空；界面靠 periodic updateRunnable/250ms 轮询（POR/.../MobilerunAccessibilityService.kt:317-385） | 无通用 waitForIdle | 轮询能覆盖无事件，但延迟/成本高 | 无事件背压，树全量重建 | 反例：轮询不是事件驱动；可借鉴其“定时兜底”而非主路径 |
| DSH 当前 | settle 等 UiClock.quietMs>=120；waitFor 100ms 轮询；ui.diff 忽略 since；stable 硬编码（UE:123-169,703-719） | 无两次签名 | 无事件立即返回/动画跑满 | 订阅队列丢最旧（EB:172-189） | 已有 event bus/ref diff 骨架，补语义即可 |

结论：**Maestro 的“两次 hierarchy 相等”最接近正确方向，但它没有事件流，且 budget/超时行为不够明确；Appium 的 waitForIdle 只是系统主线程空闲，不是树稳定；Portal 是轮询反例。DSH 应该走“事件唤醒 + 两次 capture + 显式 stable=false”的中间路线。**

## 7. 可借鉴清单

| 优先级 | 建议 | 来源/依据 | 目标 |
|---|---|---|---|
| P0 | settle 记录 t0，用 max(t0,lastRelevantEventAt) 计算 quiet；至少两次 capture；签名比较 | 本文 §2.2；Maestro 两次 hierarchy | UE.settle |
| P0 | stable 不再硬编码；超时/树变/窗口切换返回 false + reason | UE:186 等；Maestro null | Change/changeJson |
| P0 | ui.diff 默认 fresh，支持 since，缺口返回 STALE+resetRequired | UE:123-126；设计 §4 | ui.diff |
| P0 | page.seq 改为 EventBus seq；page.g 保留 capture 代数 | ES:219、EB:28 | Capture/Page |
| P0 | 事件过滤：只有相关包/窗口/类型重置 quiet；systemui/IME 不污染 app settle | AS:494-501、ES:226-231 | UiClock/AgentServer |
| P0 | 订阅队列控制事件优先，dropped 累计，慢消费者 reset | EB:172-189 | EventBus |
| P1 | 事件合并 50–100ms，保留 count/firstSeq/lastSeq | §3.3 | EventBus/AgentServer |
| P1 | diff items 明细 + identity/recycled + truncated | DF:41-47、UE:769-781 | Diff/ui.diff |
| P1 | ui.wait(ref/state)、ui.expect、for.toast；错误码 STALE/TIMEOUT/UNKNOWN 分层 | 矩阵 R4/R5；UE:149-169 | UiEngine.waitFor |
| P1 | events.poll（since/limit/timeout） | 设计 §4.3 写了 poll；当前只有 stream | AgentServer |
| P2 | 截图 hash/像素差做 visualStable；按 App/动画配置阈值 | §2.1 L2；Maestro waitForAnimationToEnd | 06 号文档/可选 |
| P2 | 事件风暴自适应：按 events/sec 调整合并窗口 | §3.4 | EventBus |
| P2 | 每个 App 的 settle profile（微信/Chrome/Flutter） | 真机矩阵 | 配置层 |

## 8. 不适用 / 反例

1. **固定 sleep 300ms/500ms 代替 settle。** 对快页面浪费，对慢页面不够；02 号文档已给出反例，本文的 settle 必须由信号驱动。
2. **waitForIdle 等于“界面稳定”。** Appium 的 Device.waitForIdle() 只等系统 idle，异常还继续（APP/.../Device.java:68-86）；动画/网络/RecyclerView 绑定都可能未完成。
3. **“没有事件 = 稳定”。** 视频、Canvas、Flutter、WebView、游戏可以完全无事件；反过来，系统 UI 的噪声会让有事件页面永远不静默。必须加两次 capture。
4. **“diff 空 = 没变化”。** 当前 ui.diff 可能复用缓存树（UE:646-651），previous 与 v 同对象；且 stable 硬编码 true，空 diff 不能证明动作无效。
5. **“stable=true = 动作成功”。** stable 只说明树静默；按钮可能接受了点击但没有业务效果，必须用 L3 后置条件。
6. **把所有事件都当页面内容。** 状态栏时钟、电池、通知 shade、IME 候选栏、触摸探索都会发事件；当前 UiClock.note() 无条件重置 quiet（AS:495），是 1.5s 超时的常见来源。
7. **把 Toast 当树节点。** Toast 通常不在 app 窗口中，TYPE_NOTIFICATION_STATE_CHANGED 又与通知混用；用 ui.wait(text) 等 Toast 会超时。
8. **逐节点报 bounds。** 列表滚动/键盘弹出会让所有 bounds 变化；应折叠成 scroll/layout.changed，否则 diff 被 40 条上限截断且全是噪声。
9. **用 sourceNodeId 做跨进程/跨重启身份。** 它只是系统内节点 id（01 号文档已论证），重启/窗口重建后不可假设稳定；持久身份只能靠 ref 表 + 业务键。
10. **since 当“快照代数 g”用。** g 是 capture 签名代数，不是事件 seq；客户端混用会导致 diff 永久空或 STALE。
11. **事件队列满时丢窗口切换。** 当前无差别丢最旧（EB:172-189）；WINDOW_STATE_CHANGED 丢失会让客户端停留在旧页面。
12. **在 wait 里持有全局锁。** 8s 阻塞会让心跳/取消/其他会话全部排队；应释放锁并支持取消。

## 9. 测试与真机清单

### 9.1 单元测试（agent-core / agent-app）

当前覆盖极少：DiffTest.kt 只有 detectsTextChange 与 detectsAddedAndRemoved 两个测试（DSH/agent-core/src/test/kotlin/dev/dsh/agent/core/DiffTest.kt:8-40）；ViewTest.kt 48 行只测渲染/预算；CoreBehaviorTest.kt 80 行只测 cron/safePaths/mutating gate，没有 wait/settle/event 测试。以下用例应补：

| # | 用例 | 输入/注入 | 期望 |
|---|---|---|---|
| 1 | 无事件页面 settle | 时钟推进、两次 capture 相同 | 不早于 minObserve，返回 stable=true；耗时约 120–200ms，不是 24ms |
| 2 | 动作前有事件 | lastEventAt=t0-200ms | 仍要等动作后 120ms，不能立即返回 |
| 3 | 持续事件到上限 | 事件每 20ms 一次，持续 2s | 1.5s 返回 stable=false，reason=event_storm；不抛 TIMEOUT |
| 4 | 两次 capture 不同 | capture1 text=A，capture2 text=B | stable=false；不返回空 diff |
| 5 | checked 变化 | 只有 checked 翻转 | diff 报 checked；g/stableSignature 能感知（当前签名不含 checked，预期失败） |
| 6 | text+desc 同时变化 | 同节点两字段都变 | items 两条（当前 else-if 只报 text，预期失败） |
| 7 | 列表复用 | 同 cls/viewId/bounds，text 从 A→B，nativeId 相同 | changed + recycled=true；不得静默当作同一条业务记录 |
| 8 | 结构键误绑 | nativeId=-1，多行同结构 | 不复用 ref；旧 removed/新 added（预期当前失败） |
| 9 | 窗口切换 | window 1→2，节点相同 | windowChanged/reset；旧 ref STALE，不报大量 text changed |
| 10 | since 缺口 | current-since > 1000 | STALE + resetRequired；不得空 diff |
| 11 | since 精确 | since=current | 空 diff、stable=true、fromSeq=toSeq |
| 12 | wait text 出现 | 第 3 次 capture 出现 | 返回 matched/waitedMs；条件命中后再确认一次 |
| 13 | wait textGone | 文本先有后无 | 返回 gone；不因旧缓存误判 |
| 14 | wait ref 状态 | enabled→disabled | 支持 ref.state（当前只支持 gone，预期失败） |
| 15 | wait 超时 | 条件永不成立 | TIMEOUT + last.page + observed + hint |
| 16 | 事件队列溢出 | 订阅者不消费，publish 1000 条 | 低优先级可丢；控制事件保留；droppedTotal 累计；无 OOM |
| 17 | bootId 不匹配 | since 来自旧 bootId | resetRequired=true；订阅响应/轮询均标 |
| 18 | 服务断开 | capture 抛异常 | UNAVAILABLE，不是 UNKNOWN |
| 19 | 并发 wait/act | 两个请求同时 | 无死锁；wait 可取消；diff 基线不被覆盖 |
| 20 | 密码 diff | text 变化且 isPassword | before/after 均为 [redacted] |

测试实现建议：把 UiClock/EventBus 的时钟与事件源抽象成接口（Clock、EventSource），否则 object UiClock 的静态状态无法可靠注入；settle 目前是 private，建议抽出 Settler 类，输入 capture: () -> Snapshot、clock、policy，输出 Stability(ok, quietMs, captures, reason)。

### 9.2 集成/仪器测试

| 场景 | 操作 | 断言 |
|---|---|---|
| 点击后立即读 | ui.act(tap) → ui.view | stable 与实际页面一致；不出现 24ms 返回 |
| 页面转场 | 打开新 Activity | settle 至少观察 350ms；diff 有 windowChanged；旧 ref STALE |
| 列表分页 | 滚动到底触发加载 | ui.wait(idle) 等到 loading 消失；不把 footer 当最终内容 |
| 输入法 | 点击输入框、输入中文、收起键盘 | keyboard 标志正确；IME 事件不拖满 settle；text 条件可命中 |
| Toast | 触发“已保存”Toast | events.toast 收到；ui.wait(text) 不应误判为页面文本 |
| 权限弹窗 | 首次启动请求权限 | 弹窗窗口被捕获；目标被盖时 BLOCKED；允许/拒绝后恢复 |
| ANR | 模拟卡死/等待调试 | crash.anr 或窗口规则命中；不误报普通 Dialog |
| 通知 shade | 下拉/收起 | systemui 事件不重置 app settle；前台包不被 systemui 覆盖 |
| 分屏/旋转 | 分屏、旋转 | 窗口集合变化；diff reset；坐标/bounds 正确 |
| 服务重启 | 杀进程/重连 | bootId 变化；客户端 reset；事件无静默缺口 |

### 9.3 真机矩阵（待验证，C 级）

| 维度 | 取值 | 观察点 |
|---|---|---|
| Android 版本 | 10/11/12/13/14/15/16 | 事件类型/Toast/权限弹窗/后台限制差异 |
| 刷新率 | 60/90/120Hz | captureGap/minObserve 是否足够；两次签名是否受 vsync 影响 |
| 动画设置 | 0/0.5/1/2/10（开发者选项） | animation 阈值是否要按 scale 调整 |
| App 类型 | 原生/Compose/Flutter/WebView/游戏/视频 | 无事件/树签名不变的比例；diff 噪声 |
| 厂商 | Pixel/AOSP、小米、华为、OPPO/vivo | 系统弹窗类名、Toast 行为、后台限制 |
| 输入法 | Gboard/搜狗/微信键盘/自建 IME | 键盘事件、候选栏、content change 频率 |
| 多窗口 | 分屏/悬浮窗/画中画 | 窗口 id/layer/焦点；ref 跨窗口 |
| 无障碍 | 开启/关闭 TalkBack、GKD 共存 | 事件量、服务互斥、capture 延迟 |
| 电源 | 亮屏/息屏/省电模式 | 事件暂停、capture 失败、恢复后 reset |

### 9.4 观测指标

| 指标 | 定义 | 目标/用途 |
|---|---|---|
| settle_p50/p95 | 从动作开始到 stable=true 的耗时 | 发现 24ms 早退/1.5s 跑满 |
| stable_false_rate | stable=false / 总动作 | 评估事件过滤/阈值 |
| timeout_rate | ui.wait TIMEOUT / 总 wait | 条件/默认超时是否合理 |
| capture_ms | 一次 Capture 耗时 | 2000 节点/多窗口是否过慢 |
| events_per_sec | 按包/类型统计 | 找噪声源；动态合并窗口 |
| dropped_total | 每订阅累计丢弃 | 背压是否合理 |
| diff_items | added/removed/changed 数量 | 列表复用/整树重建误报 |
| seq_gap | 客户端 since 与 baseSeq 差 | resetRequired 触发率 |

## 10. 参考索引

### 10.1 本地代码（A 级）

| 引用 | 内容 |
|---|---|
| UE:41-43 | QUIET_MS=120、SETTLE_MAX_MS=1500、STALE_MS=30000 |
| UE:123-126 | ui.diff 忽略 since、用 current 缓存 |
| UE:129-147 | waitFor 默认 idle、500–60000ms、100ms 轮询、TIMEOUT |
| UE:149-169 | 仅 text/textGone/gone/idle；idle 300ms；无 ref 存在/expect/state |
| UE:173-198 | act：current→resolve→exec→settle→capture→diff；stable 传 true |
| UE:201-250 | batch ≤20；每 op settle；stable 传 true |
| UE:254-272 | fill；submit 前 Thread.sleep(80)；diff |
| UE:646-651 | current 30s 缓存，fresh 才 capture |
| UE:654-668 | captureNow、signature、g+1、refs.bind、previous/view |
| UE:671-679 | signature 字段（缺 checked/enabled/selected/focused/viewId） |
| UE:703-719 | settle：24ms + 16ms 轮询 + quiet>=120 / 1.5s |
| UE:761-781 | pageJson（g/seq/pkg/activity/kb/nodes）；changeJson 丢明细 |
| ES:19-41 | NodeIds 反射；失败 -1L |
| ES:53-88 | Capture：窗口按 layer、MAX_NODES=2000、pkg 取第一个 root、activity 条件 |
| ES:147 | z=window layer（同窗口节点 z 相同） |
| ES:207-231 | UiClock：所有事件 note；lastEventAt 初始 0；events++ |
| EB:27-28,71-84 | bootId、sequence、frame 信封 |
| EB:53-69 | uiChanged generation、waitForChange（无调用者） |
| EB:108-122 | replay：since<=0 空、boot/gap reset、since+1 回放 |
| EB:125-130,162-189 | 订阅上限 8；队列 256；丢最旧；dropped 清零 |
| AS:494-520 | onAccessibilityEvent：note 所有事件、uiChanged、foreground、100ms ui.changed、toast |
| AS:695-714 | subscribe 参数/响应/baseSeq/resetRequired |
| AS:716-729 | root helper crash.anr 通道 |
| AS:759-766 | heartbeat |
| DF:20-56 | Diff：prev null、text/desc else-if、checked/enabled/bounds、added/removed 上限 |
| DF:59-60 | isMeaningful |
| RS:12 | structuralKey=cls|viewId|bounds |
| RS:64-132 | bind：nativeId→结构键→新分配 |
| RS:150-177,208-215 | resolve 阈值 0.72/margin 0.08；textSim |
| DSH/agent-core/src/test/kotlin/dev/dsh/agent/core/DiffTest.kt:8-40 | 仅 2 个 diff 测试 |
| DSH/agent-app/test-src/dev/dsh/agent/CoreBehaviorTest.kt（80 行） | 无 wait/settle/event 测试 |

### 10.2 设计/能力文档（A 级）

| 引用 | 内容 |
|---|---|
| DSH/docs/agent-engine-v3.md:89-92 | ui.diff since(seq)；ui.wait for{text|textGone|idle|ref}，默认 8000 |
| DSH/docs/agent-engine-v3.md:118-125 | ui.act 返回示例：page/change/stable/quietMs；ok 仅动作接受 |
| DSH/docs/agent-engine-v3.md:139-147 | 13 错误码与 retryable/hint |
| DSH/docs/agent-engine-v3.md:153-160 | 等待语义：settle 120ms/1.5s、wait text/idle、动画 300–500ms |
| DSH/docs/agent-engine-v3.md:293-304 | 事件流类型、Toast/ANR/权限弹窗边界 |
| DSH/docs/agent-engine-v3.md:323-327 | Appium uuid+stale、Portal 250ms 轮询、Maestro waitForAnimationToEnd |
| DSH/docs/agent-capability-matrix.md:49-51 | R3 diff 按 ref 连续性；R4 wait；R5 expect=timeout0 |
| DSH/docs/agent-capability-matrix.md:94 | S11 Toast/ANR/权限弹窗部分实现 |
| DSH/docs/agent-capability-matrix.md:189-195 | 未决：uia 400ms 无 diff 是否足够 |
| DSH/docs/research/01-tree-and-refs.md:7,21-24 | DSH 路径约定；ref 四错绑；密码泄漏 |
| DSH/docs/research/02-actions-and-gestures.md:16-17 | 结论 9/10：settle 不能固定 300ms；act 单动作+后置条件 |

### 10.3 同类实现（B 级）

| 引用 | 内容 |
|---|---|
| APP/app/src/main/java/io/appium/uiautomator2/server/Device.java:68-86 | waitForIdle 默认 10s、catch 异常仅 log |
| APP/app/src/main/java/io/appium/uiautomator2/server/settings/WaitForIdleTimeout.java:23 | 默认 10000ms |
| APP/app/src/main/java/io/appium/uiautomator2/server/automation/Click.java:38 | 点击后 waitForIdle |
| MAE/maestro-client/src/main/java/maestro/utils/ScreenshotUtils.kt:38-74 | 两次 hierarchy 相等；timeout busyloop；无 timeout 10×200ms |
| MAE/maestro-client/src/main/java/maestro/Driver.kt:96-104 | null=未能确认稳定 |
| MAE/maestro-client/src/main/java/maestro/Maestro.kt:233-245 | 滚动后 null 重新 stabilize |
| POR/app/src/main/java/com/mobilerun/portal/service/MobilerunAccessibilityService.kt:317-385 | 只记 WINDOW_STATE_CHANGED；CONTENT/SCROLL 空分支；periodic update |

> 上述 _research 路径相对 /root/workspace/projects/_research；不同 checkout 的行号可能偏移，引用时以本地文件为准。

### 10.4 官方文档（B 级，访问 2026-10-29）

- Android Developers, AccessibilityEvent：<https://developer.android.google.cn/reference/android/view/accessibility/AccessibilityEvent>
  - 事件类型常量：TYPE_WINDOW_STATE_CHANGED、TYPE_WINDOW_CONTENT_CHANGED、TYPE_VIEW_SCROLLED、TYPE_VIEW_TEXT_CHANGED、TYPE_NOTIFICATION_STATE_CHANGED、TYPE_ANNOUNCEMENT。
  - TYPE_WINDOW_CONTENT_CHANGED 的 changeTypes 位掩码包含 CONTENT_CHANGE_TYPE_SUBTREE/TEXT/CONTENT_DESCRIPTION/STATE_DESCRIPTION/PANE_TITLE/PANE_APPEARED/PANE_DISAPPEARED/DRAG_* 等（以页面所列常量为准）。
- Android Developers, UiDevice：<https://developer.android.google.cn/reference/androidx/test/uiautomator/UiDevice>
  - 存在 waitForIdle(long timeout)；官方 KTX 文档在 onElements 说明中建议先调用 waitForStable 再期待多个元素同时出现（B 级，仅作方向参考）。
- Android Developers, AccessibilityService：<https://developer.android.google.cn/reference/android/accessibilityservice/AccessibilityService>
  - onAccessibilityEvent 是事件入口；服务配置 accessibilityEventTypes/notificationTimeout 影响事件到达（配置见 DSH/agent-app/src/main/res/xml/accessibility_service_config.xml，A 级）。

### 10.5 待验证（C 级）

1. 各厂商 TYPE_NOTIFICATION_STATE_CHANGED 对 Toast 的覆盖/截断行为，以及 Android 15 是否仍可靠。
2. ANR 对话框的窗口类名/包名在 Pixel/AOSP/小米/华为上的差异。
3. TYPE_WINDOW_CONTENT_CHANGED.changeTypes 在 Compose/WebView/Flutter 中的实际覆盖率。
4. AccessibilityNodeInfo.getSourceNodeId 在 RecyclerView 复用、Compose 虚拟节点、WebView 中的稳定性。
5. 两次 capture 的最小间隔在 120Hz/低端机上的合理值（50ms 是否足够）。
6. 事件合并 50ms 对 ui.wait(text) 响应时间的影响（是否应只对 idle 合并）。
7. screencap 作为 L2 稳定信号的成本/收益；是否只在动画/Canvas 场景启用。
8. 慢消费者 reset 策略对 MCP 客户端重连体验的影响。

---

**一句话总结：** 先把“稳定”拆成事件静默 + 两次树签名 + 可选像素/业务条件；把 settle 的动作起点、两次 capture、stable=false 做对；再把事件按控制/结构/高频分层合并、背压和 seq 统一；diff 才有可信的 since 和 ref 连续性。当前代码的骨架（EventBus、RefStore、Diff）可以保留，但 settle、ui.diff、stable 和事件映射需要按本文 P0 项重写。
