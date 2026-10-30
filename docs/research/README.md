# 功能调研索引（8 组）

> 目的：为 agent-app 的 v3 引擎与 MCP facade 提供可核对的同类实现依据。
> 方法：只读本地 checkout + 公开资料；每条结论带 path:line 或 URL；不修改代码、不碰真机。
> 日期：2026-10-29 · 分支 feat/session-core

## 0. 研究规则

1. **先结论后证据**：文档开头先列 10 条以内结论，正文再逐条展开。
2. **每条判断可追溯**：本地代码用“缩写/相对路径:行号”，网络资料用完整 URL + 访问日期。
3. **区分四类**：可直接借鉴 / 思路可借鉴但实现不同 / 不适用 / 待真机验证。
4. **不写“应该”**：要么给出对方源码怎么做的，要么标注为“推测，待验证”。
5. **单篇 300–700 行**：太短没有证据，太长没人看；表格优先。
6. **不重复造轮子**：先看已有 01 号文档和 docs/agent-engine-v3.md，避免重复结论。

## 1. 本地参考仓库

| 缩写 | 目录 | 语言/规模 | 版本/提交 | 参考价值 |
|---|---|---|---|---|
| APP | _research/appium-uiautomator2-server | Java 21,976 行 | 56bf27a（2026-10-06） | ★★★★★ 无障碍服务、元素缓存、等待、多窗口 |
| U2 | _research/uiautomator2 | Python 4,898 行 | 3adba4a（2026-10-23） | ★★★★ JSON-RPC、服务端等待、watcher |
| MCP | _research/mobile-mcp | TypeScript 4,537 行 | 6c94597（2026-10-26） | ★★★★ 面向 LLM 的扁平元素与坐标动作 |
| ATX | _research/atx-agent | Go 4,357 行 | 0c30c97（2024-04-07） | ★★★ 设备端守护进程、空闲退出、自愈 |
| DROID | _research/droidrun（解压目录 mobilerun-main） | Python 50,267 行 | mobilerun 0.7.4 | ★★★★★ 与 agent-app 形态最像：无障碍 App + 本地 API + agent |
| POR | _research/portal（mobilerun-portal-main） | Kotlin/Java 57,147 行 | 与 mobilerun 0.7.4 同期 | ★★★★★ 树导出、可见性、坐标动作、IME |
| MAE | _research/maestro（Maestro-main） | Kotlin/Java 50MB | main 3fc4ef3（2026-10-28） | ★★★★ YAML 流程、waitForAnimationToEnd、assertVisible |

补充：

- droidrun 的 Portal 驱动 HTTP 客户端不在 Python 仓库内，在 mobilerun-core-local 包里；研究 Portal 以 POR 为准。
- android.googlesource.com 在本机不可达；AOSP 结论以本地 android.jar 反编译/官方开发者文档为准，需标注来源。
- GitHub 直连不稳定；tarball 走 gh-proxy.com 镜像。研究文档只引用最终代码，不把网络问题写进结论。

## 2. 八组分工

| 编号 | 主题 | 交付文件 | 关键问题 | 状态 |
|---|---|---|---|---|
| 01 | 树与 ref：多窗口/可见性/身份/遍历 | 01-tree-and-refs.md | 怎么取全不重复；可见性怎么裁；RecyclerView/Compose/WebView 身份；TreeSource 接口 | ✅ 完成（686 行，58,670 B，119 条引用） |
| 02 | 动作注入与手势：语义动作/坐标/多指/遮挡 | 02-actions-and-gestures.md | ACTION_CLICK vs dispatchGesture vs input；长按/滑动/多指；遮挡与 BLOCKED；动作后 settle | ✅ 完成（8,619 B；子代理 420e12f7） |
| 03 | 文本输入/IME/剪贴板：SET_TEXT/commitText/焦点 | 03-text-ime-clipboard.md | ACTION_SET_TEXT 失败条件；自建 IME；中文输入；剪贴板前台限制；选择/复制/粘贴 | ✅ 完成（17,291 B；子代理 29633e03） |
| 04 | 等待/事件/稳定性/diff：settle/idle/事件合并 | 04-wait-events-diff.md | 何时算稳定；事件风暴合并；diff 粒度；wait 的阻塞语义；超时与重试 | ✅ 完成（671 行，58,947 B） |
| 05 | 通知/权限/Toast/ANR/系统弹窗 | 05-notifications-dialogs.md | NotificationListener 能做什么；RemoteInput 回复；权限弹窗识别；ANR/Toast 事件；模态遮挡 | ✅ 完成（392 行，38,115 B） |
| 06 | 截图/视觉/OCR 边界：screencap/takeScreenshot/ML Kit | 06-screenshot-ocr.md | screencap 与 takeScreenshot 的延迟/权限/坐标；raw 格式；OCR 选型；截图标注 | 待派 |
| 07 | 协议/会话/幂等/错误码：JSON-RPC vs JSON Lines/W3C | 07-protocol-session.md | 长连接协议；idempotencyKey 语义；会话生命周期；错误码映射；事件 seq/bootId | 待派 |
| 08 | MCP 工具面/Agent 可用性：命名/schema/批处理 | 08-mcp-tools.md | 工具数量与粒度；schema 设计；批处理；错误如何让模型自纠；DSH 工具形状 | 待派 |

## 3. 每组必须回答的交付模板

~~~markdown
# 0X · 主题

## 0. 结论（10 条以内）
1. ...
2. ...

## 1. 问题定义（我们到底要解决什么）
## 2. 同类实现（逐项目，带 path:line）
## 3. 横向对比表
## 4. 可借鉴清单（按优先级 P0/P1/P2）
## 5. 不适用/反例
## 6. 对 agent-core / agent-app 的具体接口建议
## 7. 测试与真机验证清单
## 8. 参考索引
~~~

## 4. 与设计文档的关系

- docs/agent-engine-v3.md §12 是能力边界的唯一权威；研究文档只能提出证据和建议，不能改边界。
- 01 号文档已经指出当前 Capture/RefStore 的具体缺陷；02–08 号文档应避免重复，只在必要时引用 01 的结论。
- 研究结论进入实现前，先更新 docs/agent-capability-matrix.md 的状态列，再改代码。

## 5. 进度与风险

- 已完成 5/8；剩余 3 组（06 截图/OCR、07 协议/会话、08 MCP 工具面）。
- 子代理通道已单测可用；并发过多会失败，按 2 个一批派发，失败后单发重试。
- 每个子代理结束后检查：文件是否存在、行数是否 >=300、引用是否可解析、是否修改了代码或真机。
- 最终汇总到 docs/research/SYNTHESIS.md（待所有组完成后写）：跨项目共识、分歧、对我们的 P0 建议。

## 6. Chroot Core 专项学习（2026-10-30）

- `chroot-v2-study.md`：`knowledge/` 与 `references/` 的 chroot/mount/loop/SELinux/服务监督专项学习记录；只读研究，不是代码改动。
