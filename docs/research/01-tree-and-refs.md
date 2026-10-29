# 01 · 树与 Ref：多窗口、可见性、节点身份、遍历与接口

> 只读调研，未改代码、未碰真机。日期：2026-10-29。
> 范围：`agent-core` 的 Snapshot/RefStore 与 `agent-app` 的 Capture 现状；对照 Appium UiAutomator2 Server、Portal、mobile-mcp、uiautomator2 当前 checkout。
> 引用缩写（后文均为 `缩写/相对路径:行号`）：
>
> - `DSH/` = `/root/workspace/projects/dsh-chroot-lab/`
> - `APP/` = `/root/workspace/projects/_research/appium-uiautomator2-server/`
> - `POR/` = `/root/workspace/projects/_research/portal/`
> - `MCP/` = `/root/workspace/projects/_research/mobile-mcp/`
> - `U2/` = `/root/workspace/projects/_research/uiautomator2/`

## 0. 结论（先看这十条）

1. **不要用 `rootInActiveWindow` 单根代表整屏。** 多窗口要用 `AccessibilityService.windows` 枚举，再用 active root 做锚点/去重。Portal 的 resolver 是“active root + 比 active 更高的 application 窗口”，不是全量枚举；分屏下半屏和 active root 之上的 system 弹窗都会漏。见 `POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:25-120`。
2. **去重按 window 和 node 身份，不按文本/坐标。** 先按 `window.id`/root identity 去重窗口，再按 `(windowId, sourceNodeId)` 去重节点；相同文本在分屏两半、列表两行都可能是合法不同节点。
3. **默认窗口策略：** 全部 `TYPE_APPLICATION`（含分屏两半、freeform、PiP）+ 聚焦/激活的 `TYPE_SYSTEM` 弹窗；IME 只置 `keyboard=true`、默认不遍历键盘节点；`TYPE_ACCESSIBILITY_OVERLAY` 默认排除；`TYPE_SPLIT_SCREEN_DIVIDER` 跳过。
4. **可见性裁剪分三层。** Snapshot 保留“可见节点 + 有可见后代的不可见祖先 + 可操作/可滚动节点”；RefStore 只给“可见且有义”的节点发 ref；View 只渲染“可见且有义”的节点及其祖先。不可见不喂 LLM，但也不从树里删掉。
5. **默认阈值：** 全局 `maxNodes=2000`、单窗口 `1200`、WebView 子树 `800`、`maxDepth=60`；可见面积阈值 1%，但 clickable/editable/scrollable 不看面积；`visibleBounds` 裁到 screen ∩ window ∩ 最近 scrollable ancestor，raw bounds 另存。
6. **身份没有单一银弹。** 原生场景 `windowId + sourceNodeId` 最强；Compose 优先 `testTag`（extras）/API 33 `uniqueId`；RecyclerView 的 `sourceNodeId` 是 **View 实例**身份，不是 item 身份，滚动复用后同一个 id 会显示新 item；WebView 没有稳定 DOM 身份，只能内容 + 几何 + epoch。
7. **当前 `RefStore` 四个会直接错绑的点：** `structuralKey = cls|viewId|bounds` 不含文本/collection，列表复用时会静默复用 ref；`nativeId != 0L` 把 `-1` 当有效 id；`score()` 完全没用 `desc`；空文本 `textSim=0.5` 会凭空加分。见 `DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:12,80,184-194,210-215`。
8. **当前遍历还缺：** 环检测、零尺寸父节点保留、原始 child index、窗口/根重试与去重、window recycle 的 finally、预算优先级。`Capture` 按 layer 升序 + 全局预算，背景大树会先吃光 2000 预算，最上面的系统弹窗反而采不到。见 `DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:65-88,102-116`。
9. **`path` 现在是压缩后的 child 序号，不是原始 index。** `SnapshotBuilder.add()` 用 `nodes[parent].children.size` 当 index；Appium 明确保留 `getChild(i)` 的原始 i，null child 也保留空位。见 `DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Snapshot.kt:104-106` 与 `APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:376-400`。
10. **密码必须在 Capture 层脱敏。** 当前 `Node.text` 原样保存，`ViewRenderer` 只加 `password` 标记，`Diff` 还会输出 before/after 文本；违反项目“密码掩码”设计。见 `DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:136-137`、`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/View.kt:130-139`、`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Diff.kt:41-47`、`DSH/docs/design-v1.5.md:311-312`。

---

## 1. 多窗口/分屏/悬浮窗/IME/系统弹窗：怎么取全不重复

### 1.1 先定义“取全”

“取全”不是“把 `rootInActiveWindow` 能拿到的都拿到”，而是：**当前 display 上、当前用户可感知/可操作的窗口根集合**。

| 窗口种类 | Android window type | 默认纳入 | 说明 |
|---|---|---|---|
| 普通 App | `TYPE_APPLICATION` | 是 | 每个 root 单独遍历 |
| 分屏两个 App | 两个 `TYPE_APPLICATION` | 是 | 两个 root 都要，不能只取 focused |
| Freeform / 悬浮窗 / PiP | 通常 `TYPE_APPLICATION` | 是 | 按 layer 排序，小窗口也是独立 root |
| IME | `TYPE_INPUT_METHOD` | 否（只报 `keyboard=true`） | 键盘按键通常不是 agent 目标；需要点键盘时显式开启 |
| 系统弹窗/权限/ANR | `TYPE_SYSTEM` | 聚焦/激活时是 | 状态栏、导航栏也是 `TYPE_SYSTEM`，不能无条件全收 |
| 其他无障碍悬浮层 | `TYPE_ACCESSIBILITY_OVERLAY` | 否 | 避免把 GKD/Portal 悬浮按钮当 App 内容；调试时可开 |
| 分屏分割条 | `TYPE_SPLIT_SCREEN_DIVIDER` | 否 | 没有可操作内容 |

项目已经申请 `flagRetrieveInteractiveWindows`、`flagReportViewIds`、`flagIncludeNotImportantViews`（`DSH/agent-app/res/xml/accessibility_service_config.xml:1-9`），所以 `service.windows` 具备可用前提。注意 `flagIncludeNotImportantViews` 会把大量“对无障碍不重要”的 View 也带进来，覆盖全但噪声大，必须靠可见性/有义性过滤，不能直接全渲染。

### 1.2 Appium 怎么做：root 列表 + 可选多窗口

- `AXWindowHelpers.getCachedWindowRoots()` 根据两个 setting 决定拿全部窗口、只拿 active root，还是拿“当前 active 包的最上层窗口”；结果按 cacheKey 缓存，reset 时清空。见 `APP/app/src/main/java/io/appium/uiautomator2/utils/AXWindowHelpers.java:80-104`。
- `retrieveWindowRoots()`：`enableMultiWindows` 为 true 时走 `getWindowRoots()`，否则只取 active root 或 topmost-from-active-package。见 `APP/app/src/main/java/io/appium/uiautomator2/utils/AXWindowHelpers.java:106-118`。
- `getWindowRoots()` 对每个 window 取 `window.getRoot()`，null 只打日志并跳过，**不按 type 过滤、不去重**。见 `APP/app/src/main/java/io/appium/uiautomator2/utils/AXWindowHelpers.java:162-173`。
- `getTopmostWindowRootFromActivePackage()` 按 `layer` 降序，筛同 package 的第一个 root。见 `APP/app/src/main/java/io/appium/uiautomator2/utils/AXWindowHelpers.java:175-190`。
- `getActiveWindowRoot()` 对 `rootInActiveWindow` 做 **10s / 250ms** 重试，直到非 null 或超时抛错。见 `APP/app/src/main/java/io/appium/uiautomator2/utils/AXWindowHelpers.java:120-141`。
- `getWindows()` 在 API 30+ 用 `getWindowsOnAllDisplays()` 再按当前 displayId 过滤；旧版用 `getWindows()`。见 `APP/app/src/main/java/io/appium/uiautomator2/utils/AXWindowHelpers.java:143-160`。
- 多 root 最终由 `UiElementSnapshot.take(AccessibilityNodeInfo[] roots, ...)` 合成一个虚拟 `hierarchy` 根，root 的 children 按数组下标编号；每个节点带 `WINDOW_ID` / `DISPLAYED` 属性。见 `APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:116-130,233-238`。

关键默认值：`enableMultiWindows=false`（`APP/app/src/main/java/io/appium/uiautomator2/model/settings/EnableMultiWindows.java:19-22`），`enableTopmostWindowFromActivePackage=false`（`APP/app/src/main/java/io/appium/uiautomator2/model/settings/EnableTopmostWindowFromActivePackage.java:19-22`）。也就是说 **Appium 默认只取 active 窗口**，多窗口是 opt-in；它没有替我们解决“全量 + 去重”，只是把 root 数组交出来。

### 1.3 Portal 的 root resolver：逐行走一遍

`AccessibilityRootResolver.resolve(service)` 是“以 active root 为锚，再补 active 之上的 application 窗口”：

1. 先读 `rootInActiveWindow`，再读 `service.windows`。如果 `windows` 为 null，只返回 active root（layer=0）作为降级。见 `POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:25-32`。
2. 给 windows 建 descriptor（`id/layer/type`），用 active root 的 `windowId` 找到 activeLayer；找不到就只返回 active root。见 `POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:34-45`。
3. active root 先加入候选，并把它自己的 windowId 放入 `knownWindowIds`。见 `POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:46-54`。
4. **eligible 规则：** 有 active root 时，只要 `TYPE_APPLICATION` 且 `layer > activeLayer` 的窗口；没有 active root 时，`TYPE_APPLICATION` 或 `TYPE_SYSTEM`；排序 application 在前，然后 layer 降序，再按 id。见 `POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:56-73`。
5. 对每个 eligible window：跳过重复 windowId；`window.getRoot()` 为 null 跳过；用 descriptor.id 或 `root.windowId` 得到 windowId；如果 windowId 已在 known 集合，回收 root 并跳过；如果 root 与已有候选是同一对象（或两边 windowId 都未知且 `root == root`），把 active root 的未知 windowId 补上而不是重复添加；否则加入候选并登记 windowId。见 `POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:75-114`。
6. 最后在 finally 里回收所有 `AccessibilityWindowInfo`；返回的 root 由调用方负责回收。见 `POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:117-119`。

它解决了什么：

- 不会把桌面/Launcher、后台 App 的所有窗口都收进来；以 active root 为锚，只补“盖在 active 上面”的 App 窗口（典型是 Dialog/PopupWindow）。
- 处理了 `windowId == -1` 的未知情况：先按 root identity/equals 去重，再补 id。
- 窗口对象回收干净，root 所有权明确交给上层。

它没解决什么（对我们很关键）：

- **分屏下半屏会漏。** 有 active root 时只收 `layer > activeLayer` 的 application；另一个分屏 App 在 active 下面，被过滤掉。分屏场景需要两个 `TYPE_APPLICATION` 都收。
- **active root 之上的 system 弹窗会漏。** 有 active root 时 eligible 只允许 application；权限弹窗/ANR 若是 `TYPE_SYSTEM` 且 active root 仍是下面 App，就不会被加入。除非系统把弹窗设为 active root（很多时候会），但这不是可以依赖的保证。
- **IME 永远不遍历。** 只把它当键盘可见性信号；Portal 的键盘检测单独走 `windows.any { type == TYPE_INPUT_METHOD }`（`POR/app/src/main/java/com/mobilerun/portal/service/MobilerunAccessibilityService.kt:868-882`）。
- **没有“模态遮挡”语义。** 它只保证 root 集合，不告诉上层“这个窗口是模态的，下面窗口的节点不可点”。如果弹窗没有盖满全屏，下层节点仍可能被误点。
- **没有按 package/type 之外的内容去重。** 两个窗口恰好返回同一个 root 时靠 identity/equals；如果 OEM 返回不同 wrapper，windowId 是主键，通常够用。

### 1.4 当前 Capture 与问题

现状（`DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:65-88`）：

```kotlin
val windows = service.windows ?: emptyList()
val ordered = windows.sortedBy { it.layer }
for (w in ordered) {
    if (w.type == TYPE_INPUT_METHOD) keyboard = true
    val root = w.root ?: continue
    if (budget[0] <= 0) { root.recycle(); continue }
    if (pkg.isEmpty()) pkg = root.packageName?.toString() ?: ""
    walk(builder, handles, root, -1, 0, w.id, w.layer, false, budget)
    root.recycle()
}
```

具体问题：

1. **按 layer 升序 + 全局预算。** 背景 App 的窗口先遍历，2000 节点预算可能在到达最上层弹窗前就耗尽。应改为“按优先级遍历 + 单窗口配额”，或至少 layer 降序。
2. **没有 active root 锚点，也没有 root 去重。** 只靠 `service.windows`；如果 OEM 同时把 active root 和 window root 暴露成不同 wrapper，可能重复；如果某个窗口 root 为 null，直接跳过，没有 fallback 到 `rootInActiveWindow`。
3. **`pkg` 取第一个 root 的 package。** 因为按 layer 升序，第一个通常是底层窗口（甚至 Launcher/SystemUI），不是前台 App。应取 focused/active application 窗口的 package；`activity` 再由 `UiClock` 匹配。
4. **没有窗口类型策略。** IME 节点会被遍历（只额外置 keyboard=true），状态栏/导航栏/其他 overlay 也会被遍历，产生噪声和重复交互目标。
5. **`z = w.layer` 只有窗口层级，没有窗口内 drawingOrder。** 同一窗口内两个重叠 View 的 z 相同，`UiEngine.tapPoint()` 的 `it.z > node.z && covers` 无法发现同窗口遮挡。Appium 把 `DRAWING_ORDER` 作为独立属性（`APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:68-85,189-190`），应把 `windowLayer` 与 `drawingOrder` 分开，比较时组合。
6. **窗口对象没有 recycle。** `service.windows` 返回的 `AccessibilityWindowInfo` 在 API < 33 需要回收；Portal 在 finally 里统一回收（`POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:117-119`）。
7. **root/walk 没有 try/finally。** `walk(builder, ..., root, ...)` 抛异常时 `root.recycle()` 不会执行；子节点在 `walk` 后 `child.recycle()`，同样没有 finally。见 `DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:157-165`。
8. **多 root 的渲染顺序被 bounds 打乱。** `Snapshot.roots` 按 layer 升序保存，但 `ViewRenderer.displayList()` 对 roots 按 `bounds.t, bounds.l` 排序（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/View.kt:96`），`Query.visualOrder()` 也只按 bounds（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Where.kt:171-173`）。弹窗在上、App 在下时视觉顺序会反过来。应按 `windowLayer` 优先，再 bounds。

### 1.5 建议的 root resolver

目标：**全量、可解释、可去重、可降级**；不照搬 Portal 的“只补 active 之上”。

```kotlin
data class WindowMeta(
    val id: Int,              // AccessibilityWindowInfo.id
    val displayId: Int,
    val type: Int,
    val layer: Int,
    val bounds: Rect,
    val pkg: String,
    val active: Boolean,
    val focused: Boolean,
    val kind: WindowKind,     // APP / SYSTEM_DIALOG / IME / OVERLAY / OTHER
    val modal: Boolean,       // 是否阻断下层点击
)

fun resolveRoots(service: AccessibilityService, opts: CaptureOptions): RootSet {
    val windows = retry(3, 120) { service.windows } ?: emptyList()
    val active = service.rootInActiveWindow
    val activeId = active?.windowId ?: -1
    val metas = windows.mapNotNull { describe(it) }
    val roots = LinkedHashMap<Int, RootCandidate>()
    if (active != null) roots[activeId] = RootCandidate(active, metas.firstOrNull { it.id == activeId }, true)
    for (m in metas) {
        if (!opts.includes(m)) { recycleWindow(m); continue }
        if (m.id in roots) { recycleWindow(m); continue }
        val root = m.root ?: run { recycleWindow(m); continue }
        val rid = root.windowId.takeIf { it != -1 } ?: m.id
        if (rid in roots) { root.recycle(); recycleWindow(m); continue }
        roots[rid] = RootCandidate(root, m, false)
    }
    return RootSet(roots.values.toList(), metas)
}
```

窗口策略：

```text
TYPE_APPLICATION              -> root != null && bounds intersects screen
TYPE_SYSTEM                   -> focused || active || isDialogPackage(pkg)
TYPE_INPUT_METHOD             -> opts.includeIme
TYPE_ACCESSIBILITY_OVERLAY    -> opts.includeOverlays && pkg != ownPackage
TYPE_SPLIT_SCREEN_DIVIDER     -> false
TYPE_MAGNIFICATION_OVERLAY    -> false
其他                          -> false（记录到 errors/degraded）
```

- `isDialogPackage` 至少覆盖 `com.android.permissioncontroller`、`com.google.android.permissioncontroller`、`com.android.packageinstaller`、`com.google.android.packageinstaller`、`android`；OEM 包名需真机补。
- `TYPE_SYSTEM` 的 status bar/nav bar 通常不 focused；用户下拉通知栏时 status bar 会 focused，这时纳入合理。
- `TYPE_ACCESSIBILITY_OVERLAY` 默认排除；如果确实要读，标记 `kind=OVERLAY`，且不分配可点击 ref。
- `TYPE_INPUT_METHOD` 默认不遍历，只置 `keyboard=true`；需要点键盘时用 `includeIme=true`，并标记 `ime=true`，视图层降权/折叠。

去重规则：

1. 窗口级：`window.id` 相同 → 同一个窗口；`window.id == -1` 时用 root identity/equals；仍不确定则用 `(pkg, root.windowId, root.sourceNodeId)`。
2. 节点级：`(windowId, sourceNodeId)` 相同 → 同一个节点；`sourceNodeId` 无效（-1/undefined）时用 `(windowId, originalPath)`。**不要**用 `(text, bounds)` 全局去重。
3. active root 与 window root 重复：优先保留 active root，把 window 的元数据补到 active 候选上；Portal 的 `existing.windowId == UNDEFINED_WINDOW_ID` 分支就是处理这个（`POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:89-108`）。
4. 同 package 多窗口不合并：分屏同 App、App + Dialog 都可能是多个 `TYPE_APPLICATION`，按 window 分开。

失败/降级：

- `service.windows` 抛异常或 null → 用 `rootInActiveWindow` 单根，`degraded=true`，记 `windows_unavailable`。
- `window.root` 为 null → 跳过该窗口，记 `root_null:<id>`；如果所有窗口都 null，回退 active root。
- active root 为 null 且 windows 为空 → 返回空 Snapshot，`degraded=true`；不要假装成功。
- root 读取建议 2-3 次短重试（120ms 级），不要像 Appium 那样阻塞 10s（`APP/app/src/main/java/io/appium/uiautomator2/utils/AXWindowHelpers.java:120-141` 适合测试框架，不适合在线 agent 的交互延迟）。

### 1.6 模态/遮挡：root 取全了，还要告诉上层“谁能点”

- `TYPE_SYSTEM` 且 `focused` → `modal=true`，它下面的所有窗口 `blockedBelow=true`。
- 同 package 的 `TYPE_APPLICATION` 且 `focused`、bounds 小于 display、下面还有同 package 窗口 → 视为 App Dialog，`modal=true`。
- 分屏两个不同 package 的 application 都可见 → 都不是 modal；各自区域可点。
- 悬浮窗/PiP 不自动 modal；它只遮挡自己 bounds 内的区域，用几何遮挡处理。

`UiEngine.tapPoint()` 现在只按 `z > node.z && covers` 找 blocker（`DSH/agent-app/src/dev/dsh/agent/UiEngine.kt:532-550`）。应增加“窗口级 blocked”：目标窗口在某个 modal 窗口之下，直接 `BLOCKED`，不要试图找缝。同一窗口内用 `drawingOrder`，不同窗口用 `window.layer`，比较时用 `(windowLayer, drawingOrder)` 二元组。

---

## 2. 可见性裁剪：各方案代价与默认档位

### 2.1 四种方案

| 方案 | 做法 | 遍历成本 | 输出/Token | 误裁风险 | 代表 |
|---|---|---|---|---|---|
| A. 不裁 | 所有 child 都收 | O(N)，N 可能几千 | 最大 | 无 | 调试模式 |
| B. Appium 子级剪枝 | 每个 child 先 `isVisibleToUser()`，false 就整棵子树丢掉；bounds 再裁到 display/window/最近 scrollable ancestor | 每 child 1 次可见性判断；bounds 裁剪沿祖先链，最坏 O(N·depth) | 小 | **Compose 不可见父 + 可见子会被误杀**，需 `alwaysTraversableViewClasses` 豁免 | `APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:363-401`；`APP/app/src/main/java/io/appium/uiautomator2/core/AxNodeInfoHelper.java:311-356` |
| C. Portal 子先父后 + 1% | 先递归 children，再判断自己：可见面积 ≥1% 或“有有效 children”就保留；min size 5px | O(N) 全遍历，不能提前剪枝 | 较小；每节点 JSON 重 | 1% 会丢细线/小字；父保留可救 Compose 不可见父 | `POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityTreeBuilder.kt:15-17,71-124,374-398` |
| D. 当前 leaf-only | 只有 `!visible && childCount==0` 才丢；父节点无论可见都保留；不做 bounds 裁剪 | 每节点 1 次可见性 + 1 次 bounds；只省叶子 | 仍大；不可见文本进 View | 零尺寸父节点被直接丢（连可见子节点一起丢） | `DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:102-116` |

容易忽略的成本点：

- `isVisibleToUser()` 不是“被其他窗口遮住”的判断。它只表示节点在**自己的 window 内**对用户可见；系统弹窗盖住的 App 节点仍可能返回 true。遮挡必须靠 window layer/几何单独算。
- `getBoundsInScreen()` 可能返回负坐标/超出屏幕；Appium 会先和 display、window、最近 scrollable ancestor 求交（`APP/app/src/main/java/io/appium/uiautomator2/core/AxNodeInfoHelper.java:311-356`）。我们当前直接用 raw bounds，点击中心可能落在屏幕外。
- Appium 的 `getVisibleBoundsInScreen` 对每个节点沿祖先链递归（`APP/app/src/main/java/io/appium/uiautomator2/core/AxNodeInfoHelper.java:336-353`），深树上是 O(depth)；可以用“遍历时携带 clipRect”做到 O(1)/节点：进入 scrollable 节点时 `clip = clip ∩ node.bounds`，退出恢复。效果等价，成本更低。
- Portal 的 1% 是**面积百分比**，不是可见像素数；2px 宽分隔线可能面积 <1% 被丢。对纯装饰可以，对可点击细线不行，所以 clickable/editable/scrollable 应豁免面积阈值。
- `ViewRenderer.selects()` 没检查 `Flags.VISIBLE`（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/View.kt:100-105`），即使 Snapshot 标了不可见，只要有文本/clickable 仍会渲染。`Diff.isMeaningful()` 同样没检查可见（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Diff.kt:59-60`），不可见节点增删会产生 diff 噪声。

### 2.2 默认应裁到什么程度

**Snapshot 层（保留结构，供 ref/查询/点击）**

保留：

- 所有可见节点（`isVisibleToUser=true` 且 `visibleBounds` 面积 >0）；
- 所有“有可见后代的不可见祖先”（保证路径/父子关系）；
- 所有 clickable / editable / scrollable / checkable / focused 节点，即使面积很小；
- 所有系统弹窗/焦点窗口的节点（不受 1% 限制）；
- scrollable 容器本身（offscreen children 默认不保留，除非 `includeOffscreen=true`）。

丢弃：

- 完全在 screen/window 外、且没有可见后代的叶子；
- 可见面积 <1% 且无文本、无操作、无子节点的纯装饰节点；
- WebView/Compose 的重复文本 wrapper（只在渲染层去重，Snapshot 保留 clickable 父节点）。

上限：

- `maxDepth=60`（Appium 默认 70 是为防 StackOverflow，见 `APP/app/src/main/java/io/appium/uiautomator2/model/settings/SnapshotMaxDepth.java:21-28`；Portal 128 偏深；60 够覆盖绝大多数页面，同时避免递归过深）。
- `maxNodes=2000`，单窗口 1200，WebView 子树 800。
- 超限时 `truncated=true`，并在 page 里显示 `omitted`；不要静默丢。

**RefStore 层（只给“可操作/可指认”的节点发 ref）**

- `visible && (label.isNotEmpty() || clickable || editable || scrollable || checkable || focused)`。
- 不可见的结构祖先不分配 ref；它们只作为路径存在。
- 密码节点分配 ref，但文本脱敏、fingerprint 不用 text。
- 这样 `eN` 数量从“2000 个节点”降到“几十到几百个有义节点”，ref 表更稳，LLM 不会看到一堆无意义容器。

**View 层（给 LLM 的文本）**

- 只渲染“可见 + 有义”的节点 + 祖先；
- `maxChars=2000` 保持不变，`maxDepth=3` 只是缩进上限，不是过滤深度（当前 `ViewOptions.maxDepth` 命名容易误解，见 `DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/View.kt:3-8,111-112`）；
- 相邻同 role/同 label 的 clickable 合并时，group key 加上 `window`，避免把分屏两半的同一个“确定”按钮合并成一组；
- 视觉顺序按 `(windowLayer, drawingOrder, bounds.t, bounds.l)`，不要只按 bounds。

**一句话默认档：** Snapshot 留结构（可见 + 可见祖先 + 可操作），Ref 只发可见有义，View 只显示可见有义 + 祖先；不可见不喂 LLM，但也不从树里删掉。这比“全裁”安全，比“全留”省 token。

### 2.3 成本量级（分析，不是真机 benchmark）

以 2000 节点、深度 30 的页面估算：

- 遍历：每节点至少 1 次 `childCount`、每 child 1 次 `getChild`、1 次 `getBoundsInScreen`、1 次 `isVisibleToUser`；约 4k–6k 次 framework 调用。低端机可能几十到几百 ms；本项目未做真机基准，不能给精确数字。
- Appium 的 bounds 裁剪沿祖先链，最坏每节点 O(depth)，2000×30 级别的额外调用；它用 `SnapshotMaxDepth=70` 限制递归，但仍比 O(1) clip 贵。
- Portal 的 children-first 不省遍历，只省输出；它的 JSON 每节点几十个字段，序列化成本高。
- 当前 leaf-only 省不了多少：只剪掉不可见叶子，仍会遍历所有不可见父节点；而且 `ViewRenderer` 还会把不可见文本渲染出来，等于白遍历。

## 3. 节点身份：Compose / WebView / RecyclerView 复用 / 滚动 / 文字变化

### 3.1 候选信号对比

| 信号 | 原生 View | Compose | WebView | RecyclerView 复用 | 滚动 | 文字变化 | 结论 |
|---|---|---|---|---|---|---|---|
| `windowId + sourceNodeId` | 高 | 中（composition 存活期内） | 低-中（virtual node 可能无 id） | **高但只代表 View 实例，不代表 item** | 高（View 对象还在） | 高（id 不看文本） | 原生首选；列表必须加内容 guard |
| `uniqueId`（API 33+） | 高（App 设置时） | 低（很少设置） | 低 | 高（Adapter 设置时） | 高 | 高 | 有则最强，但要处理 null |
| Compose `testTag`（extras） | 无 | **高** | 无 | 高（每个 item 设置时） | 高 | 高 | Compose 首选 |
| `viewIdResourceName` | 高（XML id） | 低/空（除非 testTagsAsResourceId） | 低/空 | 低（列表行常共用同一个 id） | 高 | 高 | 只能做辅助，不能当唯一 |
| `text/desc/hint` | 中 | 中（可能被 merge） | 中 | 低（复用后文本变） | 中（内容不变时） | **低** | 只做内容特征，不做 key |
| `collectionItemInfo(row,col)` | 无 | 低 | 无 | **高（位置）** | 中（row 可能随插入变化） | 高 | 列表项的好辅助 |
| `path/index` | 低 | 低 | 低 | 低（插入/删除就变） | 低 | 低 | 只做 fallback/调试 |
| `bounds` | 中（短期定位） | 中 | 中 | 低（复用同位置） | **低（滚动就变）** | 高 | 只做重定位分数 |
| `class` | 中 | 低（常是 View/ComposeView） | 低（常是 View） | 低（同类） | 高 | 高 | 弱特征 |

### 3.2 `sourceNodeId` 是“View 实例 id”，不是“语义 item id”

Appium 用反射读 `mSourceNodeId`，再和 `windowId` 组合成 uuid；如果 sourceNodeId 是 `UNDEFINED_NODE_ID` 或 windowId 是 `UNDEFINED_WINDOW_ID` 就返回 null。见 `APP/app/src/main/java/io/appium/uiautomator2/core/AxNodeInfoHelper.java:53-78`。

项目里的 `NodeIds.of()` 用的是 `getMethod("getSourceNodeId")`，失败返回 `-1`（`DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:19-41`）。这和 Appium 的 field 反射不同：不同 API/OEM 上方法可能不可见或不存在，建议：

1. 先尝试 `getField("mSourceNodeId")`（Appium 同款），再 fallback `getSourceNodeId()`；
2. 统一 sentinel：`NO_NATIVE_ID = -1L`，并在 `RefStore` 里只接受 `> 0` 的 nativeId；
3. 永远把 `windowId` 和 `sourceNodeId` 一起作为 key，不能只用 nativeId。

**RecyclerView 复用陷阱（最重要的身份问题）：**

假设列表行 root 的 `sourceNodeId=123`，在 `bounds=(0,300,1080,460)` 显示“WLAN”。用户滚动后，同一个 ViewHolder 被复用，`sourceNodeId` 仍是 123，bounds 仍是 `(0,300,1080,460)`，但文本变成“蓝牙”。这时：

- `RefStore.bind()` 的 nativeId pass 会命中旧 ref（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:78-90`）；
- `resolve()` 的 nativeId 分支直接返回 score=1.0，`relocated=false`（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:150-157`）；
- 结果就是“点 WLAN 的 ref，实际点了蓝牙”。

更糟的是 `bind()` 的 structural pass：`structuralKey = cls|viewId|bounds`（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:12`），文本完全不参与；列表项复用后 cls/viewId/bounds 常常完全一样，旧 ref 会被静默绑到新 item，连 `relocated` 都不会标。

**修法：**

- `Binding` 增加 `contentHash`（label/desc/flags 的归一化 hash）和 `inScrollable` / `collectionItem`；
- nativeId 命中时，如果节点在 scrollable 容器内或 `collectionItemInfo != null`，且 `contentHash` 大变，则**不视为 exact**：返回候选并标 `contentChanged/recycledSuspect`，让上层决定是否重读；或者要求 `collectionItemInfo` 的 row/col 也匹配；
- `structuralKey` 不要含 bounds；把 bounds 从身份键移到 geometry 特征；
- 列表项优先用 `collectionItemInfo(row,col) + viewId + class + 文本锚` 组成 `itemKey`；row/col 在插入/删除后会变，所以还要文本锚兜底；
- 滚动时 bump `scrollEpoch`，所有 scrollable 容器内的 binding 标记为“需内容复核”，不要直接复用。

### 3.3 Compose

- `viewIdResourceName` 通常为空；只有 App 开了 `testTagsAsResourceId` 才可能映射到 resource-id。
- `testTag` 在 `AccessibilityNodeInfo.getExtras()` 的 key `androidx.compose.ui.semantics.testTag`；Appium 在 `MapTestTagToResourceId` 开启时把它当 resource-id 返回，并且是“无条件覆盖真实 viewId”。见 `APP/app/src/main/java/io/appium/uiautomator2/core/AxNodeInfoHelper.java:106-130`。
- `isVisibleToUser` 对某些 Compose 容器会返回 false，但它的子节点可见；Appium 专门提供 `alwaysTraversableViewClasses` 豁免，注释点名 `androidx.compose.ui.viewinterop.ViewFactoryHolder`。见 `APP/app/src/main/java/io/appium/uiautomator2/model/settings/AlwaysTraversableViewClasses.java:23-33`。
- Compose 的 semantics 会 merge：父节点的 text 可能是多个子节点拼接，`contentDescription` 可能挂在父上；不要假设“一个节点一个文本”。
- Compose 的 `className` 常是 `android.view.View` / `androidx.compose.ui.platform.ComposeView`，不能拿 class 做身份。
- `sourceNodeId` 在 composition 节点存活期内通常稳定，但重组/导航后可能变化；不能当跨页面稳定 id。

**建议：** 优先读 extras 里的 `testTag`；没有则用 `viewIdResourceName`（若 App 开了 testTagsAsResourceId）；再退到 `(cls, label, bounds, sibling ordinal)`。不要因为 `isVisibleToUser=false` 就剪掉 Compose 父节点，除非它的子节点也都不可见。

### 3.4 WebView

- 没有真实 View id，`viewIdResourceName` 多为空；`className` 可能统一是 `android.view.View`。
- `getChild(i)` 可能返回 null（virtual node 尚未 materialize/已回收），`childCount` 可能很大；`isVisibleToUser` 对 virtual node 不一定可靠。
- 页面刷新/导航会整体换树，`sourceNodeId`/bounds 全部失效；没有稳定的 DOM 身份可用。
- 文本可能重复：容器和子 Text 都带同一段文字；渲染层要按 `(text, bounds, role)` 去重，Snapshot 里保留 clickable 父节点。
- mobile-mcp 直接在接口注释里写 `getElementsOnScreen` “Works only on native apps (not webviews)”（`MCP/src/robot.ts:204-208`），说明只靠 UIAutomator dump 做 WebView 元素列表并不可靠。

**建议：**

- 给 WebView 子树打 `webview=true`，单独限额（800 节点/深度 40），不要让它挤掉系统弹窗；
- 不要用 nativeId 做跨页面 ref；页面导航/大内容变化时 bump `webEpoch`，旧 ref 全部要求重读；
- WebView 内部优先保留 text/desc + bounds + clickable，不要依赖 class/viewId；
- 如果 WebView 根下没有子节点，不要假设“页面没内容”，可能是 accessibility 未开启/尚未加载；标记 `degraded`，回退截图/坐标，而不是空树成功。

### 3.5 文字变化

- 文本是**内容特征**，不是身份。标题从“设置”变“设置2”可以复用 ref（当前单测就是这么期望的，见 `DSH/agent-core/src/test/kotlin/dev/dsh/agent/core/RefTest.kt:10-22`），但列表行从“WLAN”变“蓝牙”不能复用。
- 区分场景：**同一个语义控件的状态文本变化**（开关“已开启/已关闭”、按钮“暂停/继续”）应尽量复用；**列表项/RecyclerView 复用**不应复用。前者通常有稳定 viewId/nativeId 且不在 scrollable item 内；后者在 scrollable 容器内或 collectionItem 非空。
- 打分时 text/desc 用归一化（trim、折叠空白、数字/时间可弱化），空文本不要给“0.5 的相似度”；空对空应该是“无证据”，不是“中等相似”。

### 3.6 最稳的组合（决策树）

```text
1) uniqueId 非空且在窗口内唯一        -> stableKey = uniqueId
2) Compose testTag 非空且唯一          -> stableKey = testTag
3) sourceNodeId 有效                  -> key = (windowId, sourceNodeId)
   - 若节点在 scrollable/collectionItem 内：必须 contentHash 或 collection 位置匹配
4) collectionItemInfo + viewId/class  -> itemKey = (viewId, row, col, class, labelAnchor)
5) 以上都没有                         -> 打分：label/desc + viewId + class + sibling ordinal + 近邻 bounds
6) path/index/bounds 单独             -> 只用于重定位，绝不作为身份
```

一句话：**原生非列表用 `windowId+sourceNodeId`；Compose 用 testTag；列表项用 collection + 内容锚；WebView 只能 epoch + 内容/几何；任何场景都不要用绝对 index/path/bounds 单独做身份。**

---

## 4. 遍历的坑：环、深度、getChild null、recycle、password、WebView virtual node

### 4.1 坑位总表

| 坑 | 现象/根因 | 参考实现 | 我们的修法 |
|---|---|---|---|
| 环 | `getChild` 返回祖先/自身（WebView/Compose virtual node、OEM 实现）；递归无限/重复 | Portal 用 active path identity 防环（`POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityTraversalGuard.kt:12-31`）；Appium 没有显式环检测，只靠 `SnapshotMaxDepth=70` 防 StackOverflow（`APP/app/src/main/java/io/appium/uiautomator2/model/settings/SnapshotMaxDepth.java:21-28`） | 活动路径 `HashSet<NodeKey>`：`(windowId, sourceNodeId)` 优先，source id 无效时用 identity；进入 add、退出 remove；同时检查 `child === node` |
| 深度 | 深树导致栈溢出/预算耗尽；太浅丢深层文本 | Appium 70；Portal 128；我们 50（`DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:54`） | 迭代 DFS（显式栈）替代递归；`maxDepth=60`；每窗口配额；超限 `truncated` |
| `getChild` null | 动画/RecyclerView/WebView 常见；null 不代表树结束 | Appium 记录 `nullNodeIndexes` 并继续，保留原始 index（`APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:376-400`）；Portal 直接 continue（`POR/app/src/main/java/com/mobilerun/portal/service/MobilerunAccessibilityService.kt:803-813`） | 继续循环；index 用原始 `i`；记录 null 比例，>20% 标 degraded；不要压缩后续 child 的 index |
| `childCount` 变化 | 读取后树变化；按旧 count 循环可能多/少 | 各实现都读一次；Appium 先读 `childCount` 再循环 | 循环内 catch `RuntimeException`；不要因一次 null 提前 break；必要时下一帧重抓 |
| recycle | API <33 必须回收；漏回收导致资源泄漏；回收后使用导致 crash | Appium `recycleSafely` 在 API 33+ no-op，catch `IllegalStateException`（`APP/app/src/main/java/io/appium/uiautomator2/core/AxNodeInfoHelper.java:228-248`）；Portal 在 finally 回收 root/child/window（`POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityTreeBuilder.kt:361-367`；`POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:117-119`） | 每个 `getChild` 的 child 在 finally 回收；root/window 在 finally 回收；`handles` 里的 `obtain` 副本单独生命周期；不要回收已存入 handles 的节点 |
| password | `isPassword=true` 但 text 仍原样落入 Snapshot/Diff/View/日志 | Appium 只输出 `PASSWORD` 属性，不脱敏（`APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:161-162,177-178`）；项目设计要求密码掩码（`DSH/docs/design-v1.5.md:311-312`） | Capture 时若 `isPassword`（或 inputType 是 password variation）→ `text=""`、`hint=""`、`Flags.PASSWORD`、`sensitive=true`；Fingerprint 不用 text；Diff 跳过；View 显示 `••••` |
| WebView virtual node | 无 viewId、child null、文本重复、页面刷新全失效 | mobile-mcp 明确“native only”（`MCP/src/robot.ts:204-208`） | 单独 `webEpoch`；限额；按 text/bounds 渲染去重；不把 nativeId 当跨页身份 |
| Compose 不可见父 | `isVisibleToUser=false` 但子节点可见，子级剪枝会误杀 | Appium `alwaysTraversableViewClasses` 注释点名 `ViewFactoryHolder`（`APP/app/src/main/java/io/appium/uiautomator2/model/settings/AlwaysTraversableViewClasses.java:23-33`） | 子先父后/保留有可见后代的祖先；必要时 class 豁免 |
| 根 null / secure window | 窗口切换/FLAG_SECURE/动画中 root 为 null | Appium active root 重试 10s（`APP/app/src/main/java/io/appium/uiautomator2/utils/AXWindowHelpers.java:120-141`）；Portal 跳过 null root | 短重试 2-3 次；仍 null 记 degraded；secure window 不要假装有内容 |
| stale handle | Capture 后节点被回收/刷新，`performAction` 失败或作用到旧节点 | Appium cache 用 `getName()` 探活，失败再 restore（`APP/app/src/main/java/io/appium/uiautomator2/model/ElementsCache.java:132-163`） | 动作前 `AccessibilityNodeInfo.refresh()`；false 则重新按 nativeId 找；不要直接 performAction |

### 4.2 当前代码的具体缺口

1. **没有环检测。** `Capture.walk()` 只检查 `depth > MAX_DEPTH` 和 `budget`（`DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:102`），没有 `child === node` 或 active path；遇到环会重复 2000 个节点直到预算耗尽，且同一 nativeId 会覆盖 handles。
2. **零尺寸父节点直接丢。** `if (rect.width() <= 0 || rect.height() <= 0) return` 在 `budget--` 和递归 children 之前（`DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:114-116`），一个 0 尺寸容器即使有可见子节点也会整棵丢。Portal 是 children-first + 父保留（`POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityTreeBuilder.kt:71-124`）。
3. **原始 child index 丢失。** `SnapshotBuilder.add()` 用 `nodes[parent].children.size`（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Snapshot.kt:104-106`）。null child 或不可见 child 被跳过后，后续 child 的 index 前移，path 随之变化；Appium 用 `index` 参数保留原始 `i`（`APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:377-395`）。
4. **root path 用全局 node id。** `path = if (parent < 0) id.toString()`（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Snapshot.kt:106`），root 的 path 会随前面窗口节点数变化；应改成 root ordinal（第几个 root）。
5. **window 未 recycle、root/child 无 finally。** 见 `DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:73-84,157-165`。
6. **nativeId -1 被当有效值。** `NodeIds.of()` 失败返回 -1（`DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:34-40`），`RefStore.bind()` 用 `n.nativeId != 0L` 判断（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:80`），于是 -1 进入 `byNative`；`resolve()` 的 nativeId 分支（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:150-157`）会把同一窗口里第一个 -1 节点当成目标，score=1.0。这是必须优先修的 correctness bug。
7. **`handles` 只按 nativeId 存。** `handles: Map<Long, AccessibilityNodeInfo>`（`DSH/agent-app/src/dev/dsh/agent/UiEngine.kt:37,149-155`），没有 windowId；同一 sourceNodeId 在两个窗口（理论上）会覆盖。应改 `Map<NodeKey, AccessibilityNodeInfo>`。
8. **`getChild` 循环里没有 `child === node`。** Portal 有这个检查（`POR/app/src/main/java/com/mobilerun/portal/service/MobilerunAccessibilityService.kt:815-818`），我们没有。
9. **`flagIncludeNotImportantViews` 放大树。** `DSH/agent-app/res/xml/accessibility_service_config.xml:4`；覆盖全但噪声大，必须配合可见性/有义性过滤，否则 2000 上限很容易被无意义 View 吃满。
10. **事件没有滚动 epoch。** `AgentServer.onAccessibilityEvent` 只 `UiClock.note()` 和 UI 变更发布（`DSH/agent-app/src/dev/dsh/agent/AgentServer.kt:494-509`），没有 `TYPE_VIEW_SCROLLED` 的 scrollEpoch；RefStore 无法知道“列表滚过了”。

### 4.3 密码的完整处理链

建议按“采集 → 存储 → 渲染 → 差分 → 日志”全链路脱敏：

1. **Capture：** 若 `node.isPassword` 或 inputType 是 password variation → `text=""`、`hint=""`，保留 `contentDescription` 作为字段标签（如果它不含秘密），置 `Flags.PASSWORD` 和 `sensitive=true`。不要保存原值到任何字段。
2. **Fingerprint：** `sensitive=true` 时 `text=""`、`desc="<password>"` 或空；打分完全跳过文本，靠 viewId/class/bounds/nativeId。
3. **View：** 显示 `password` 标记，不显示值；`label` 返回空或 `••••`。
4. **Diff：** `sensitive` 节点不比较 text/desc，只比较 checked/enabled/focused；避免 `ChangedItem(before, after)` 泄漏。
5. **日志/候选：** `UiEngine.candidatesJson()` 会输出 `c.label`（`DSH/agent-app/src/dev/dsh/agent/UiEngine.kt:750-756`），`where` 歧义时也输出 label（`DSH/agent-app/src/dev/dsh/agent/UiEngine.kt:740-743`）；密码节点要替换为占位符。
6. **输入：** `ACTION_SET_TEXT` 的参数不要回显到返回 JSON；当前 `fill()` 返回 `text` 原值（`DSH/agent-app/src/dev/dsh/agent/UiEngine.kt:270`），密码场景应返回 `***`。

## 5. 给 `A11yTreeSource` 接口与 `RefStore` 指纹的具体建议

### 5.1 现有 TreeSource 接口为什么不够

设计文档里的接口是：

```kotlin
interface TreeSource {
    fun roots(): List<RawNode>
    fun refresh(handle: Long): RawNode?
}
```

见 `DSH/docs/agent-engine-v3.md:177-182`。问题：

- `roots()` 只有 root，没有 window 元数据（type/layer/displayId/active/focused/modal），无法做多窗口排序、遮挡和去重。
- `handle: Long` 只有 nativeId，没有 windowId；跨窗口可能碰撞，也无法判断“同一个 sourceNodeId 在另一个窗口”。
- 没有 capture epoch / window epoch；无法拒绝旧 handle 或旧 ref。
- 没有 `degraded/truncated/errors`，失败和“真的空树”无法区分。
- 没有 options：IME、system、overlay、maxDepth、maxNodes、密码脱敏都不可配。
- 没有所有权/回收约定；raw node 是 ANI 还是拷贝不明确。

### 5.2 建议接口（读 + 刷新 + 动作校验）

核心原则：**`A11yTreeSource` 是唯一碰 `AccessibilityNodeInfo` 的地方**（设计文档也是这么定位的，见 `DSH/docs/agent-engine-v3.md:170`）；core 只收不可变数据。

```kotlin
package dev.dsh.agent.core

data class WindowKey(
    val displayId: Int,
    val type: Int,
    val pkg: String,
)

data class NodeHandle(
    val windowId: Int,
    val sourceNodeId: Long,   // -1 = 不可用
    val uniqueId: String?,    // API 33+，可为 null
)

data class WindowMeta(
    val id: Int,
    val key: WindowKey,
    val layer: Int,
    val bounds: Rect,
    val active: Boolean,
    val focused: Boolean,
    val kind: WindowKind,
    val modal: Boolean,
)

enum class WindowKind { APP, SYSTEM_DIALOG, IME, OVERLAY, OTHER }

data class CaptureOptions(
    val includeIme: Boolean = false,
    val includeSystem: Boolean = true,
    val includeOverlays: Boolean = false,
    val includeOffscreen: Boolean = false,
    val maxDepth: Int = 60,
    val maxNodes: Int = 2000,
    val maxNodesPerWindow: Int = 1200,
    val redactPassword: Boolean = true,
)

data class CaptureResult(
    val snapshot: Snapshot,
    val windows: List<WindowMeta>,
    val truncated: Boolean = false,
    val degraded: Boolean = false,
    val errors: List<String> = emptyList(),
    val durationMs: Long = 0,
)

data class NodeProbe(
    val handle: NodeHandle,
    val windowId: Int,
    val visible: Boolean,
    val enabled: Boolean,
    val attached: Boolean,
    val bounds: Rect,
    val visibleBounds: Rect,
    val contentHash: String,
)

interface A11yTreeSource {
    /** 原子抓取：窗口列表、root 顺序、遍历、handles 属于同一次 capture。 */
    fun capture(options: CaptureOptions = CaptureOptions()): CaptureResult

    /** 单节点刷新；返回 null 表示已 stale/不可见/不属于当前窗口。 */
    fun refresh(handle: NodeHandle): NodeProbe?

    /** 在 refresh 通过后执行动作；实现内部负责 recycle/异常映射。 */
    fun perform(handle: NodeHandle, action: NodeAction, args: android.os.Bundle? = null): Boolean

    fun close()
}
```

说明：

- `capture()` 必须是**一次锁内完成**：不能先 `roots()` 再逐个 `children()`，否则窗口集合会在两次调用之间变化。
- `NodeHandle` 用 `windowId + sourceNodeId`，不用裸 `Long`；没有 sourceNodeId 时用 `uniqueId` 或 `(windowId, path)` 作为补充，但 `NodeHandle` 应允许 `sourceNodeId=-1`。
- `refresh()` 实现里应调用 `AccessibilityNodeInfo.refresh()`，再校验 `isVisibleToUser`、`windowId`、package、bounds 与当前 window 相交；任何一项失败返回 null。
- `perform()` 不应接受裸 `AccessibilityNodeInfo`；所有动作走 `refresh` 后再 `performAction`；失败返回 false，由 `UiEngine` 映射成 `STALE/UNAVAILABLE`，再走手势/坐标兜底。
- `CaptureResult.windows` 是 Snapshot 的兄弟数据；如果不想改 `Snapshot` 构造，可先放 `CaptureResult`，再逐步并入 `Snapshot.windows`。
- `close()` 负责回收 `handles` 中所有 `obtain` 的副本；`UiEngine` 退出/服务销毁时必须调用。
- 线程：`capture/refresh/perform` 建议串行（`UiEngine` 已有 `ReentrantLock`，见 `DSH/agent-app/src/dev/dsh/agent/UiEngine.kt:31-37,634-641`）；不要把 ANI 跨线程长期持有。

### 5.3 `RefStore` 指纹字段建议

现有：

```kotlin
data class Fingerprint(
    val cls: String,
    val viewId: String,
    val text: String,
    val desc: String,
    val path: String,
    val bounds: Rect,
) {
    val structuralKey: String get() = cls + "|" + viewId + "|" + bounds
}
```

见 `DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:4-17`。建议拆成 **stable / content / geometry** 三组，不要用一个 `structuralKey` 同时承担身份和布局：

```kotlin
data class WindowKey(
    val displayId: Int,
    val type: Int,
    val pkg: String,
)

data class CollectionSlot(
    val row: Int,
    val col: Int,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
)

data class Fingerprint(
    // stable identity
    val pkg: String,
    val window: WindowKey,
    val cls: String,
    val viewId: String,
    val testTag: String,       // Compose extras
    val uniqueId: String,      // API 33+
    val collection: CollectionSlot?,
    // content
    val text: String,          // 归一化；password 时 ""
    val desc: String,
    val hint: String,
    val label: String,         // text ?: desc ?: hint
    val flags: Int,
    val sensitive: Boolean,
    // geometry / layout（只做重定位，不做身份）
    val path: String,          // 原始 child index path
    val index: Int,            // 原始 child index
    val bounds: Rect,          // raw
    val visibleBounds: Rect,   // clipped
) {
    /** 只在同窗口内使用；不含 bounds，避免滚动/复用直接命中。 */
    val stableKey: String get() = listOf(
        pkg, window.type.toString(), window.pkg, cls, viewId,
        testTag, uniqueId, collection?.toString().orEmpty()
    ).joinToString("|")

    /** 内容指纹，用于 RecyclerView 复用 guard / diff。 */
    val contentHash: String get() = listOf(
        label, desc, hint, flags.toString(), sensitive.toString()
    ).joinToString("|").hashCode().toString()

    /** 布局指纹，只用于候选缩小/重定位。 */
    val layoutKey: String get() = path + "|" + index + "|" + bounds
}
```

配套 `Binding`：

```kotlin
data class Binding(
    val ref: String,
    val nativeId: Long,          // -1 = 无
    val windowId: Int,
    val window: WindowKey,
    val fp: Fingerprint,
    val contentHash: String,
    val lastSeq: Long,
    val firstSeq: Long,
    val misses: Int = 0,
    val scrollEpoch: Long = 0,
    val inScrollable: Boolean = false,
    val collection: CollectionSlot? = null,
)
```

要点：

1. **`Fingerprint` 加 `pkg/windowKey/uniqueId/testTag/collection/flags/sensitive/visibleBounds`。** 现在 `Fingerprint.of()` 只从 `Node` 取 6 个字段（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:15`），信息不够。
2. **`stableKey` 不含 `bounds`/path。** 当前 `structuralKey = cls|viewId|bounds` 是最危险的复用源；bounds 应只进 `layoutKey`。
3. **`contentHash` 单独存。** nativeId 命中时先比 `contentHash`；在 scrollable/collection 内不一致 → 不能算 exact。
4. **`path` 改成原始 index path。** 需要 `SnapshotBuilder.add()` 增加 `childIndex` 参数；root 用 root ordinal，不要用全局 node id。
5. **`Node` 增加字段：** `windowType`、`windowLayer`、`drawingOrder`、`uniqueId`、`testTag`、`collection`、`visibleBounds`、`sensitive`、`scrollParent`（最近 scrollable 祖先 id）。这些字段从 `A11yTreeSource` 直接带出来，core 不做 Android 调用。
6. **`Snapshot` 增加 `windows: List<WindowMeta>`、`activeWindowId`、`foregroundPkg`；`pkg` 用前台/焦点窗口，不用第一个 root。**
7. **`Resolution` 增加语义：** `exactNative`、`relocated`、`contentChanged`、`recycledSuspect`、`epoch`；上层据此决定“直接点 / 重读 / 报歧义”。
8. **`Candidate` 的 `why` 保留人类可读证据**：同 viewId、同 testTag、同文本、collection 位置、偏移多少 px；不要只给分数。

### 5.4 打分建议

当前 `score()`：

```kotlin
if (a.viewId.isNotEmpty() && a.viewId == b.viewId) miss *= 0.40
else if (a.viewId.isNotEmpty() && b.viewId.isNotEmpty()) miss *= 0.95
miss *= 1.0 - 0.55 * textSim(a.text, b.text)
miss *= if (a.cls == b.cls) 0.80 else 0.98
if (a.path == b.path) miss *= 0.75
miss *= 1.0 - 0.30 * proximity(a.bounds, b.bounds)
return 1.0 - miss
```

见 `DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:184-194`。建议：

- **`desc` 必须参与。** 现在 `desc` 存在 Fingerprint 里但 `score()` 完全没读；纯图标按钮（contentDescription 是唯一文本）只能靠 viewId/class/path/bounds，容易错。把 `label = text ?: desc ?: hint` 作为主文本特征，`desc` 再单独加一项。
- **空文本不要给 0.5。** `textSim` 现在 `a == b == ""` 返回 0.5（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:210-215`），等于“两个空文本节点凭空有 50% 文本相似”。应返回 0.0（无证据），让 viewId/class/collection 决定。
- **`viewId` 要按唯一性降权。** 列表里所有行共用同一个 `viewId` 时，`viewId` 命中不代表同一行；在 snapshot 里统计 `viewId -> count`，count>1 时把 0.40 的权重按 `1/count` 缩。
- **`cls` 对通用类降权。** `android.view.View`、`android.widget.FrameLayout`、`androidx.compose.ui.platform.ComposeView` 这些类几乎不提供区分度；同类只给很小的 evidence。
- **`path` 降权。** path 受插入/删除影响；即使改成原始 index，也只适合做候选缩小，不应有 0.75 的强证据。
- **`proximity` 只做几何。** 滚动/动画时 bounds 变化大；建议对 scrollable 容器内的节点降低 proximity 权重，或者改用“相对最近 scrollable 祖先的可见位置”。
- **加 `uniqueId/testTag` 强证据。** 精确命中时几乎可当 key（miss 0.05-0.10）；但要先确认窗口内唯一。
- **加 `collection` 证据。** row/col + viewId + class 对列表项很强；但插入/删除会变，需 content 锚兜底。

建议阈值：

- `EXACT`：nativeId + contentHash 匹配，或 uniqueId/testTag 精确匹配；
- `RELOCATE_THRESHOLD=0.72` 可以保留，但要求 `margin>=0.10`（当前 0.08，见 `DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:170-175`）；
- 对 `sensitive` 节点，文本不参与，阈值可降到 0.65，但必须要求 viewId/class/bounds 至少两项；
- 候选过滤必须 `visible && window == bound.window`（或同 windowKey 的唯一候选）；不要对不可见节点打分。

### 5.5 `bind()` 的改造顺序

当前 `bind()` 是 nativeId → structuralKey → 新分配（`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:64-132`）。建议：

1. **nativeId pass：** 只处理 `nativeId > 0`；key 用 `(windowId, nativeId)`；命中后检查 `contentHash`；在 scrollable/collection 内不一致则不放行，进入候选。
2. **stableKey pass：** 用新的 `stableKey`（不含 bounds）；要求同窗口、同 pkg、同 collection；如果多个候选 → 不唯一，进入打分。
3. **fuzzy pass：** 对仍未绑定的节点，和旧 binding 做一对一打分；top1 ≥ 阈值且 margin 足够才复用 ref；否则新分配。这样布局小幅移动仍能复用，而不是像现在 structuralKey 因 bounds 变化直接失败。
4. **只给 reportable 节点分配 ref：** 不可见/零面积/纯容器不分配；否则 ref 表膨胀，LLM 看到大量空洞。
5. **旧 binding 清理：** 连续 `misses>=N`（如 3 次快照）或超过 `limit` 再淘汰；当前 LRU 只按访问顺序，不按“是否还在树上”，旧 ref 会占坑。
6. **epoch：** `captureNow()` 生成 `captureId`；`Binding` 记录 `lastSeq/captureId`；`resolve` 拒绝跨 epoch 且无 nativeId/content 证据的 binding，避免旧页面 ref 在新页面误命中。

### 5.6 建议新增/修改的测试

| 测试 | 目的 |
|---|---|
| RecyclerView 复用：同 viewId/class/bounds，文本 A→B | 旧 ref 不得静默绑到 B；应新 ref 或 `contentChanged` |
| nativeId=-1 两个节点 | `resolve` 不得命中第一个；应候选/失败 |
| null child + 不可见 child | 后续 child 的 `path/index` 不变 |
| 零尺寸父 + 可见子 | 子节点保留，父作为祖先保留 |
| 分屏两个窗口同文本 | ref 不串窗口；view 不合并 |
| 系统弹窗盖住 App | 下层节点 `BLOCKED`，上层可点 |
| 密码字段 | Snapshot/View/Diff/日志都不含明文 |
| 预算优先级：背景 3000 节点 + 顶层弹窗 | 弹窗必须被采到 |
| WebView 重复文本 | 渲染去重，但 clickable 父保留 |
| `uniqueId/testTag` 唯一 | 精确命中，不依赖 bounds |

---

## 6. 需要真机/后续验证的点

以下结论是静态阅读 + 代码推导，未在真机验证，落地前应做小规模基准：

1. `getSourceNodeId` 的 field/method 反射在 API 29–35、主流 OEM 上是否可用；失败率多少。
2. `AccessibilityNodeInfo.uniqueId` 在 Compose/WebView/RecyclerView 中的实际填充率。
3. Compose `testTag` extras 在目标 App（微信/抖音/系统设置等）中的覆盖率；`testTagsAsResourceId` 是否开启。
4. 分屏/悬浮窗/PiP 在目标 ROM 上的 `AccessibilityWindowInfo.type/layer/isFocused` 实际值；权限弹窗是 `TYPE_SYSTEM` 还是 `TYPE_APPLICATION`。
5. `isVisibleToUser` 的误报/漏报率，特别是 Compose `ViewFactoryHolder`、WebView virtual node、RecyclerView 预取 item。
6. 2000 节点遍历在目标低端机上的实际耗时；是否要降到 1200/800。
7. `AccessibilityWindowInfo.recycle()` 在 API 33+ 的行为（应为 no-op，但 OEM 可能不同）。
8. WebView 页面导航的 epoch 信号：`TYPE_WINDOW_CONTENT_CHANGED` 是否足够，还是需要 URL/标题辅助。

---

## 7. 参考索引（按主题）

- 多窗口/root：`APP/app/src/main/java/io/appium/uiautomator2/utils/AXWindowHelpers.java:80-190`；`POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt:25-120`；`POR/app/src/main/java/com/mobilerun/portal/service/ForegroundApplicationWindowResolver.kt:31-123`；`MCP/src/android.ts:486-500`。
- 多 root 快照：`APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:116-130,233-238`；`POR/app/src/main/java/com/mobilerun/portal/service/MobilerunAccessibilityService.kt:656-716`。
- 可见性/裁剪：`APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:363-401`；`APP/app/src/main/java/io/appium/uiautomator2/core/AxNodeInfoHelper.java:292-356`；`POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityTreeBuilder.kt:15-17,71-124,374-398`；`DSH/agent-app/src/dev/dsh/agent/EngineSupport.kt:102-116`。
- 节点身份：`APP/app/src/main/java/io/appium/uiautomator2/core/AxNodeInfoHelper.java:53-78`；`APP/app/src/main/java/io/appium/uiautomator2/model/ElementsCache.java:46-130`；`APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:358-382`；`POR/app/src/main/java/com/mobilerun/portal/model/ElementNode.kt:28-36,184-192`；`U2/uiautomator2/_selector.py:17-43,408-426`。
- 遍历/环/recycle：`POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityTraversalGuard.kt:8-42`；`POR/app/src/main/java/com/mobilerun/portal/core/AccessibilityTreeBuilder.kt:50-69,361-367`；`APP/app/src/main/java/io/appium/uiautomator2/core/AxNodeInfoHelper.java:228-248`；`APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:363-401`。
- 密码：`APP/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java:161-162,177-178`；`DSH/docs/design-v1.5.md:311-312`。
- 现有 core：`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/RefStore.kt:4-17,64-194`；`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Snapshot.kt:4-44,89-134`；`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/View.kt:73-139`；`DSH/agent-core/src/main/kotlin/dev/dsh/agent/core/Where.kt:130-173`。
- 现有接口草案：`DSH/docs/agent-engine-v3.md:170,177-182`。
