# 03 · 文本输入 / IME / 剪贴板 / 焦点

> 子代理 29633e03 调研稿的精简落盘版（2026-10-29）。证据等级：A 本地源码逐行；B 仓库/文件级；C 推测/待真机。
> 完整论证与测试矩阵见子代理原始消息；本文件保留结论、接口建议和可核对引用。

## 0. 结论（10 条）

1. ACTION_SET_TEXT 的返回值只代表“动作被接受”，不代表文本已经变成目标值。Appium 直接把 performAction 的 boolean 当成功（APP/app/src/main/java/io/appium/uiautomator2/utils/ElementHelpers.java:59-71）；Portal 的 a11y 兜底也是 SET_TEXT 后 setSelection 就返回 true（POR/app/src/main/java/com/mobilerun/portal/service/MobilerunAccessibilityService.kt:1025-1035）。只有 Portal 的 IME 路径做了 before/after 回读（POR/app/src/main/java/com/mobilerun/portal/input/InputConnectionTextEditor.kt:156-174）。必须把“执行成功”和“校验成功”分开。
2. 失败先分类再重试：不可编辑 / 只读 / 禁用 / 节点失效 / 应用不实现 action / 密码框 / 焦点不在目标 / 输入会话切换。对应 NOT_EDITABLE、DISABLED、STALE、UNAVAILABLE、DENIED、CONFLICT。当前 UiEngine 把 SET_TEXT 被拒一律报 INTERNAL（DSH/agent-app/src/dev/dsh/agent/UiEngine.kt:465-467），模型拿到后无法自纠。
3. 当前实现没有真正的 IME 兜底，也没有校验。setText 只走 a11y；Backends 表里 uia 和 shell 的 text 都是 false（DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Backends.kt:26-36），chain(“setText”) 永远只有 a11y（:55-58）。appendText 用实时节点 h.text 拼接（UiEngine.kt:463），不处理 hint/selection；keyEnter 只对 root 做 ACTION_FOCUS，根本没有发 ENTER（:521-529）。
4. 自建 IME 的 commitText 是中文/长文本最可靠的后手，但只在“当前有 InputConnection”时有效。Portal 的 InputConnectionTextEditor 是现成范本：finishComposingText → getExtractedText → setSelection → commitText → 100ms 后回读比对，结果分 Verified / AcceptedUnverified / CommitOutcomeUnknown / InputSessionChanged / Rejected（POR/.../InputConnectionTextEditor.kt:6-12,42-178）。
5. ADBKeyboard 广播不是“另一条 SET_TEXT”：它要求把默认输入法全局切到 ADBKeyboard，广播由 IME 进程接收并 commitText。切 IME 会让当前输入会话重启、软键盘闪动、用户原输入法被顶掉。适合一次性 bootstrap/无 APK 场景，不适合每次输入。
6. 剪贴板粘贴适合“长文本一次到位”，但有三个硬前提：目标已聚焦且支持 ACTION_PASTE；会覆盖用户剪贴板；Android 10+ 读剪贴板只允许前台 App 或默认 IME。Portal 的 getClipboard() 直接要求自家 IME 已选中（POR/.../api/ApiHandler.kt:455-458），这是“无障碍服务身份读不到”的旁证。
7. 节点级 COPY/CUT/PASTE 不是 ClipboardManager：它们作用于节点的 selection/编辑器，不保证写系统剪贴板；PASTE 用的是当前系统剪贴板；密码框通常禁用 COPY/CUT。Appium 只提供全局 clipboard get/set（APP/.../handler/GetClipboard.java:46-67、SetClipboard.java:46-63），没有节点级动作。
8. 选择/光标只能靠 ACTION_SET_SELECTION，offset 是 UTF-16。清空 = SET_TEXT 空串；追加 = 读原值后 SET_TEXT（有竞态）或 setSelection(end)+commitText（更接近真实输入）；提交 = ACTION_IME_ENTER（API 30+）或 InputConnection.performEditorAction / sendKeyEvent(ENTER)，不是对 root 做 ACTION_FOCUS。
9. 密码框必须从 Capture 层脱敏，且文本 op 默认拒绝读原文。当前 Node.text 原样保存、Diff 输出 before/after（DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:136-137、DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Diff.kt:41-47，见 01 号文档结论 10）。appendText/selectAll 这种“先读再写”的 op 在密码框上应直接 DENIED，除非调用方显式 allowPassword:true。
10. 建议接口：ui.act 增加 setSelection/submit/copy/cut/paste；ui.fill 改成字段数组并逐字段返回 {ref,ok,code,verified,chars}；ui.clipboard 只做 get/set，get 失败返回 DENIED/UNAVAILABLE + alternatives:[“ime”]；所有文本响应不回声原文（密码恒空，普通文本只给 chars 与前 20 字摘要）。测试覆盖 EditText/Compose/WebView/密码/禁用/无 IME/剪贴板后台读。

## 1. 输入成功的四个层次

| 层次 | 含义 | 可观测 |
|---|---|---|
| L0 调用成功 | performAction/commitText 返回 true | 返回值 |
| L1 文本正确 | 节点/编辑器 text == 期望 | 回读节点或 getExtractedText |
| L2 光标正确 | selectionStart/End 符合预期 | textSelectionStart/End |
| L3 业务生效 | 搜索/发送/校验被触发 | ui.diff / 页面跳转 |

多数框架只保证 L0；agent 需要 L1 + L3，L2 只影响连续编辑。

## 2. ACTION_SET_TEXT 的契约与失败判定

- 参数 ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE（Bundle.putCharSequence）；替换整个文本，空串即清空；标准 TextView 会把光标放末尾。
- 返回 true 只表示动作已被处理，不承诺文本真的变成 value；自定义 View 可以返回 true 但忽略。
- maxTextLength 可能限制；Appium 在发送前主动截断（APP/.../core/AxNodeInfoHelper.java:422-431）。
- isEditable 为 true 通常意味着 action 存在，但 WebView/Compose/Flutter 的虚拟节点由各自实现决定；要看 actionList，不只看 editable。
- 密码框：isPassword 为 true；系统可能屏蔽文本回读/COPY，但 SET_TEXT 是否允许由应用决定，待真机验证。

判定顺序：

~~~text
1. node 还存在吗？          否 → STALE
2. editable/actionList 有吗？ 否 → NOT_EDITABLE
3. enabled/visible 吗？      否 → DISABLED / BLOCKED
4. performAction 返回？      否 → UNAVAILABLE（附 alternatives）
5. 回读 text == 期望？       否 → 再试一次 IME；仍否 → UNAVAILABLE（verified:false）
6. selection 正确？          否 → 成功但 warning（不阻塞）
~~~

常见失败与 code：performAction false → NOT_EDITABLE/DISABLED；true 但文本没变 → UNAVAILABLE（verified:false）；只改一部分（maxLength/InputFilter）→ 成功但 truncated:true；text 等于 hint → 视为空；焦点跑到别处 → CONFLICT；SecurityException/FLAG_SECURE → DENIED；节点失效 → STALE。

## 3. 焦点、软键盘、InputConnection

- ACTION_FOCUS 请求输入焦点，但不保证弹出软键盘；InputMethodManager.showSoftInput(View,...) 需要目标 App 的 View，无障碍服务拿不到，最可靠的是“点一下输入框”。
- 点击输入框会把光标放到点击位置；要精确编辑，点击后再 ACTION_SET_SELECTION。
- currentInputConnection 只在 IME 已选中且当前编辑器绑定期间非空；软键盘不可见时也可能非空（硬件键盘/抑制 showSoftInput）。
- commitText 走 IMM，不要求软键盘可见，但要求 IME 是当前输入法。
- onStartInput/onFinishInput 是输入会话边界；Portal 用 inputGeneration 递增检测会话切换（POR/.../MobilerunKeyboardIME.kt:223-232），避免把文本打进新页面。

编辑器矩阵：

| 类型 | ACTION_SET_TEXT | commitText | 备注 |
|---|---|---|---|
| 原生 EditText/AppCompatEditText | 稳定 | 稳定 | 首选 |
| Compose BasicTextField | 多数版本支持 | 支持 | 依赖 Compose 版本，待真机 |
| WebView input | Chromium 虚拟节点通常支持 | 支持 | 焦点/滚动后节点重建 |
| Flutter/canvas | 经常不支持 | 有 InputConnection 时可用 | 需真机 |
| 自定义 View | 取决于 app | 取决于 app | 最差，需回读 |

## 4. 同类实现

### Appium UiAutomator2 Server

- SendKeysToElement：有 elementId 用缓存元素，否则 focused(true) 找当前焦点（APP/.../handler/SendKeysToElement.java:101-108）。
- replace=false 默认：读当前文本→clear→再读，若还有内容视为 hint，把 currentText 置空→拼接新文本（:73-85）。没有 selection 语义。
- 文本以
 结尾时，去掉
 并 UiDevice.pressEnter()（:67-71）。
- SET_TEXT 失败抛 InvalidElementStateException，消息 “Cannot set the element to '%s'. Did you interact with the correct element?”（:86-89），这个 hint 值得抄。
- ElementHelpers.setText：ACTION_SET_TEXT + 按 maxTextLength 截断，没有回读（APP/.../utils/ElementHelpers.java:59-71）。
- clear：element.setText(“”)（APP/.../model/UiObjectElement.java:169-170）。
- 剪贴板：GetClipboard/SetClipboard 用 Instrumentation.runOnMainSync，仅 plain text（APP/.../handler/GetClipboard.java:46-67、SetClipboard.java:46-63、utils/ClipboardHelper.java:47-67）。
- 当前 server checkout 没有 IME 组件；Unicode 输入历史上由客户端 unicodeKeyboard 方案解决。

### Portal

- MobilerunKeyboardIME : InputMethodService（POR/.../input/MobilerunKeyboardIME.kt:25），单例 getInstance()；isSelected() 读 Settings.Secure.DEFAULT_INPUT_METHOD 与自身 ComponentName 比较（:50-57）。
- 文本入口：inputB64TextResult 直接 base64 解码后调用编辑器（:70-83），inputTextResult（:89-110）；都依赖 currentInputConnection。
- 键事件 sendKeyEventDirect 对 InputConnection 发 DOWN/UP（:127-143）；剪贴板 get/set 用 ClipboardManager（:152-211）；onStartInput/onFinishInput 递增 inputGeneration（:223-232）。
- InputConnectionTextEditor 算法（POR/.../input/InputConnectionTextEditor.kt:42-178）：
  1. 记录 inputSessionGeneration；每次重试前检查，变了返回 InputSessionChanged。
  2. 取 currentInputConnection；为空重试 2 次、间隔 100ms。
  3. finishComposingText；失败时若 clear=false 直接 commit 不校验。
  4. getExtractedText(ExtractedTextRequest(),0)；partialStartOffset==-1 才算完整报告。
  5. clear=true 时要求 startOffset==0 且 reachesFieldEnd（getTextAfterCursor 比对后缀）才敢全选。
  6. setSelection 选择替换区间；offset 加 extracted.startOffset。
  7. commitText(text,1)；异常 CommitOutcomeUnknown，false 重试。
  8. sleep 100ms 后重新 getExtractedText，比较 actual==expected（忽略 ZWSP/ZWJ 哨兵）；成功 Verified，否则 AcceptedUnverified。
- ApiHandler 对 AcceptedUnverified/CommitOutcomeUnknown/InputSessionChanged 直接返回错误并跳过 fallback（POR/.../api/ApiHandler.kt:387-401），防止重复输入。
- a11y 兜底 MobilerunAccessibilityService.inputText：先 findFocus(FOCUS_INPUT)，没有就 DFS 找第一个 editable（:972-985）；读 text/hintText，currentText==hintText 视为空（:991-995）；calculateInputText 按 selection 拼接（:126-168）；ACTION_SET_TEXT 后 setSelectionOnFocusedInput，返回 true（:1025-1035），没有回读。
- 剪贴板：getClipboard 先 isKeyboardImeActiveAndSelected()，不满足直接报 “Clipboard read requires Mobilerun Keyboard to be selected”（POR/.../api/ApiHandler.kt:455-458,523-526）；setClipboard 先试 IME，失败再 App ClipboardManager（:468-487）。

### Maestro

- MaestroInputMethodService 无输入视图，onCreateInputView 返回空 View（MAE/maestro-android/.../input/MaestroInputMethodService.kt:18-21）。
- commitTextFromBroadcast：beginBatchEdit→commitText(text,1)→finishComposingText→endBatchEdit（:58-83）。
- active（服务存在）与 ready（有 currentInputConnection）分开（:92-106）；commitText 前检查两者（:108-121）。
- UnicodeInputReceiver 接收 dev.mobile.maestro.ime.commitText，base64 URL-safe 解码，resultCode 返回成功/失败（MAE/.../receivers/UnicodeInputReceiver.kt:21-42,49-52）。
- 同 APK 内 IME + 导出 Receiver 的完整范例；没有回读校验。

### ADBKeyboard

- 需要 adb shell ime enable / ime set 全局切默认 IME；广播 ADB_INPUT_TEXT/ADB_INPUT_B64/ADB_CLEAR_TEXT。
- 支持中文/emoji，走 base64 + commitText；切回原输入法要再切一次，每次 onFinishInput/onStartInput，可能丢 composing text、关闭对话框、软键盘闪动。
- am broadcast 异步；除非 ordered broadcast 读 result，否则只能轮询 UI 确认；IME 未创建时可能收不到（待真机）。
- 适合设备初始化一次性配置 + 脚本输入，不适合高频单步输入。

## 5. 剪贴板：Android 10–15 的身份限制

Android 10 起，除非应用是默认输入法或当前拥有焦点的应用，否则不能访问剪贴板数据（Android Developers, Android 10 privacy changes, clipboard-data，访问 2026-10-29）。

| 身份 | 能否 getPrimaryClip | 说明 |
|---|---|---|
| 前台 Activity（有焦点） | 能 | console-app 在前台时同 UID 可能可读 |
| 默认 IME | 能 | Portal 的 getClipboard 只在这种情况下工作 |
| 无障碍服务（无前台 Activity） | 通常不能 | 服务进程不是前台 UI，AppOps 不因绑定 a11y 放行 |
| root/shell | 视实现 | cmd clipboard 无实现；service call clipboard 不稳定 |

- Android 12 起读取有系统 toast；Android 13 起复制有预览浮层，可用 ClipDescription.EXTRA_IS_SENSITIVE 隐藏敏感预览。
- 写入 setPrimaryClip 通常不受前台/IME 限制；会覆盖用户剪贴板，响应应返回 overwrote:true。
- 节点级 COPY/CUT/PASTE：PASTE 由前台 App 读剪贴板，可绕过服务读取限制；COPY/CUT 由 App 写；密码框通常禁用 COPY/CUT。
- 关键技巧：服务读不到剪贴板时，先 setPrimaryClip（写通常允许），再对目标节点 ACTION_PASTE（前台 App 自己读），比让服务读可靠。
- 读不到兜底：让 DSH IME 选中后读（高）；节点 COPY 后前台 App 读（中）；透明 Activity 骗前台（不推荐，抢焦点）；root service call（低）；cmd clipboard（无）；用户手动粘贴（高但需交互）。
- ui.clipboard：get 只在 sys.status.clipboard.readable==true 时尝试，否则 DENIED + hint“把 DSH IME 设为默认输入法”；set 总是允许但返回 overwrote:true；sensitive:true 设 EXTRA_IS_SENSITIVE；maxChars 默认 10k；不把内容写日志。

## 6. 选择、光标、清空、追加、提交

- setSelection：Bundle 放 ACTION_ARGUMENT_SELECTION_START_INT/END_INT，performAction(ACTION_SET_SELECTION)。必须先 ACTION_FOCUS；offset 是 UTF-16（emoji/代理对按 String.length）；start==end 是光标，0..len 是全选；越界 BAD_REQUEST。
- IME 路径 setSelection offset 相对于 getExtractedText().startOffset，Portal 已加 snapshot.startOffset（InputConnectionTextEditor.kt:257-261）。
- 清空优先级：ACTION_SET_TEXT 空串；IME setSelection(0,len)+commitText(“”)；全选+KEYCODE_DEL；多次 KEYCODE_DEL（最差）。清空后可能显示 hint，判空用 text.isEmpty() || text==hintText。
- 追加三种：读原值+SET_TEXT（不需 IME，但有竞态/hint 被拼/密码不能读）；setSelection(end)+commitText（真实输入，需 IME）；setSelection(caret)+ACTION_PASTE（长文本快，覆盖剪贴板）。ui.act appendText 增加 at:“end”|“caret”，默认 end。
- 提交优先级：ACTION_IME_ENTER（API 30+，触发 IME action）；InputConnection.performEditorAction(actionId)（最可靠，SEARCH/SEND/DONE/NEXT/GO 对应 3/4/6/5/2）；InputConnection.sendKeyEvent(KEYCODE_ENTER)（多行会换行）；Instrumentation/input keyevent 66（最后兜底）。
- 当前 keyEnter 只 focus root，没有提交任何东西（UiEngine.kt:521-529），是 P0 bug；提交后节点消失不一定是 STALE，页面变了就是成功。
- 焦点：首选 tap 输入框中心，等 kb:on/focused；ACTION_FOCUS 只补充；输入前校验 Flags.FOCUSED；焦点 ref 变了 → CONFLICT，不要继续 append。

## 7. 密码框

- 三层脱敏：Capture 层 isPassword 节点 text 置空/固定圆点，保留 chars 长度；View/Diff 不输出 before/after；日志/响应不 echo 原文。
- 文本 op：setText 允许 replace，响应只给 chars；appendText/selectAll/copy/cut 默认 DENIED；paste 可以（不读原文但会覆盖）；ui.clipboard get 在密码框 focused 时拒绝；Autofill 弹层不读不点。
- 验证只能用 text.length 或 changed；系统可能返回圆点；不要依赖长度做严格断言；用 ui.diff.changed 确认有变化。
- 待验证：ACTION_SET_TEXT 在 inputType=textPassword 上是否 true；getText 返回原文还是圆点；ACTION_COPY 是否被拒；Android 13+ 预览是否显示密码。

## 8. 接口建议

### ui.act 文本 op

| op | 参数 | 行为 | 主要错误 |
|---|---|---|---|
| setText | text, mode replace/append/clear, verify:true, allowPassword:false | 替换/追加/清空 | NOT_EDITABLE UNAVAILABLE DENIED |
| appendText | text, at end/caret | 末尾或光标追加 | NOT_EDITABLE CONFLICT |
| clearText | — | 清空 | NOT_EDITABLE |
| setSelection | start,end（-1 表示末尾） | 设置光标/选区 | BAD_REQUEST UNAVAILABLE |
| submit | imeAction done/send/search/next/go | 触发 IME action | UNAVAILABLE |
| focus | showIme:true | 聚焦并尽量弹键盘 | NOT_EDITABLE BLOCKED |
| copy/cut/paste | — | 节点级剪贴板动作 | UNAVAILABLE DENIED |

普通文本响应：ok/ref/op/via/verified/text{chars,changed,selection,truncated}/page/change/ms。密码响应 text{chars,changed,redacted:true}，不返回原文。

### ui.fill

字段数组：

~~~json
{"fields":[
  {"ref":"@e3","text":"张三"},
  {"label":"手机号","text":"13800000000","mode":"replace"},
  {"where":{"viewId":"com.x:id/note"},"text":"备注","submit":true}
],"stopOnError":true}
~~~

逐字段定位、逐字段返回；失败不整体回滚；submit 只允许最后一个字段；密码字段默认 allowPassword:false，但仍可 setText（写），响应脱敏；每个字段带 via。

### ui.clipboard

- get {op:“get”,maxChars:10000}；set {op:“set”,text,sensitive:true,label:“dsh”}。
- get 返回 text/chars/source/truncated；空剪贴板 ok:true+text:“”，读不到 DENIED（不要用空串表示失败）。
- set 返回 overwrote:true；sensitive 设置 EXTRA_IS_SENSITIVE。

### IME 兜底

- 内部状态：ui.ime {op:“status”} → {enabled,selected,active,connection,id}。
- commit 只在内部：{op:“commit”,text,clear:false}，返回 Verified/AcceptedUnverified/CommitOutcomeUnknown/InputSessionChanged/Rejected。
- 降级链：a11y.set_text → 成功 verified 返回；NOT_EDITABLE/DENIED 停止；UNAVAILABLE → ime.commit → verified 返回；accepted_unverified/commit_unknown 停止（禁止二次输入）；no_connection → clipboard.paste → 失败 → keyevent.ascii（仅 ASCII）。
- 关键：AcceptedUnverified/CommitOutcomeUnknown 绝不能继续降级，否则重复输入；NOT_EDITABLE 也不能降级到 IME（IME 会输入到当前焦点，可能是另一个字段）。

### 错误码映射

NOT_EDITABLE（非 editable/无 SET_TEXT action）、DISABLED（enabled=false）、DENIED（密码读取/追加/剪贴板读不到）、UNAVAILABLE（performAction false/IME 未选中/无连接）、STALE（节点消失）、CONFLICT（焦点被抢）、BAD_REQUEST（选区越界）、TIMEOUT（回读超时）、BLOCKED（被键盘/弹窗遮挡）、INTERNAL（只表示引擎 bug，不表示应用拒绝）。

### 对现有代码的最小改动

1. UiEngine.setText：检查 editable/actionList；ACTION_FOCUS 结果；SET_TEXT 失败 → UNAVAILABLE；成功后回读同 ref 文本，verified；密码只回长度。
2. UiEngine.keyEnter：实现 ACTION_IME_ENTER → performEditorAction → sendKeyEvent(ENTER) 三级。
3. UiEngine.ime：set 走 IME commit，不复用 a11y setText；hide 只在 kb:on 时按 BACK。
4. UiEngine.fill：改成字段数组；逐字段 resolve + 文本 op。
5. UiEngine.batch：exec 增加 appendText/setSelection/copy/cut/paste/submit。
6. Backends：增加 ime 后端（text=true，requiresIme=true）；Cap.text 从 boolean 改成 textKinds NONE/ASCII/UNICODE。
7. RootHelper：NOT_CLICKABLE 改成 NOT_EDITABLE；已有 selectAll/copy/paste/cut 可复用。
8. Capture/Diff：密码脱敏；Diff 对密码字段只输出 changed。
9. 新增 ClipboardBridge：get/set；get 先检查 IME selected 或前台 Activity。
10. sys.status：增加 ime{enabled,selected,active}、clipboard{readable}。

## 9. 测试清单

单测：TextPlan replace/append(end/caret)/clear；UTF-16 emoji/代理对；hint；maxLength truncated；错误映射；密码脱敏；剪贴板策略；IME 五态。
仪器：EditText set/append/clear/selectAll/copy/paste/cut；maxLength/multiline/inputType=number；密码框 setText 成功/Capture 脱敏/append 拒绝；Compose BasicTextField；WebView input；Flutter/自定义 View；禁用/只读；IME 未选中/选中；剪贴板前台/后台/IME；分屏/弹窗焦点；硬件键盘；1k/10k 长文本。
真机矩阵：Android 10–15；Pixel/小米/三星/华为；Gboard/搜狗/讯飞/DSH IME；微信/抖音/Chrome/设置/备忘录/支付宝/银行；中文/emoji/长文本/粘贴/密码/验证码/搜索/发送/多行/分屏/悬浮窗/硬件键盘。
手工脚本：微信搜索“你好”；抖音评论；Chrome 地址栏；设置搜索；密码框错误密码只看到 chars；复制文字后 ui.clipboard get（IME 选中/未选中）；切 Gboard 再切回；分屏输入到焦点所在半屏。

待验证：ACTION_SET_TEXT 在 Compose/WebView/Flutter 覆盖率；密码框 SET_TEXT 与回读；a11y 在 Android 10–15 OEM 读剪贴板；ACTION_IME_ENTER 在微信/抖音/Chrome 行为；commitText 在输入法切换后的稳定性；ADBKeyboard 广播生命周期；Android 13+ 预览与 EXTRA_IS_SENSITIVE；getExtractedText 超长文本；setSelection emoji offset；前台 Activity vs 仅 a11y 的剪贴板差异。

## 10. 参考索引

本地代码：

- DSH/agent-app/src/dev/dsh/agent/UiEngine.kt:44-47,254-274,315-349,368-383,456-469,521-529
- DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Backends.kt:26-36,42-58
- DSH/agent-root-helper/src/main/kotlin/dev/dsh/agent/RootHelper.kt:322-326,743-801
- DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:136-137
- DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Diff.kt:41-47
- DSH/docs/agent-engine-v3.md §4.2、§5、§8.1–8.2、§12.1、§12.5
- DSH/docs/agent-capability-matrix.md §3.2 W3–W6/W13–W15、§10
- DSH/docs/research/01-tree-and-refs.md 结论 10
- POR/app/src/main/java/com/mobilerun/portal/input/MobilerunKeyboardIME.kt:25,50-57,70-83,89-110,127-143,152-211,223-232
- POR/app/src/main/java/com/mobilerun/portal/input/InputConnectionTextEditor.kt:6-12,42-178,180-182,215-241,243-255,257-261,263-287
- POR/app/src/main/java/com/mobilerun/portal/api/ApiHandler.kt:380-419,421-453,455-466,468-487,523-526
- POR/app/src/main/java/com/mobilerun/portal/service/MobilerunAccessibilityService.kt:126-168,972-1049
- APP/app/src/main/java/io/appium/uiautomator2/handler/SendKeysToElement.java:62-96,101-108
- APP/app/src/main/java/io/appium/uiautomator2/utils/ElementHelpers.java:59-71
- APP/app/src/main/java/io/appium/uiautomator2/model/UiObjectElement.java:169-170
- APP/app/src/main/java/io/appium/uiautomator2/core/AxNodeInfoHelper.java:422-431
- APP/app/src/main/java/io/appium/uiautomator2/handler/GetClipboard.java:46-67
- APP/app/src/main/java/io/appium/uiautomator2/handler/SetClipboard.java:46-63
- APP/app/src/main/java/io/appium/uiautomator2/utils/ClipboardHelper.java:47-67
- MAE/maestro-android/src/main/java/dev/mobile/maestro/input/MaestroInputMethodService.kt:18-21,58-83,92-121
- MAE/maestro-android/src/main/java/dev/mobile/maestro/receivers/UnicodeInputReceiver.kt:21-42,49-52

网络资料（访问 2026-10-29）：

- Android 10 剪贴板限制：https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
- Android 复制粘贴：https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
- AccessibilityNodeInfo：https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- InputConnection.commitText：https://developer.android.com/reference/android/view/inputmethod/InputConnection
- ClipboardManager：https://developer.android.com/reference/android/content/ClipboardManager
- Android 12 行为变更：https://developer.android.com/about/versions/12/behavior-changes-12
- Android 13 行为变更：https://developer.android.com/about/versions/13/behavior-changes-13
- ADBKeyboard：https://github.com/senzhk/ADBKeyBoard