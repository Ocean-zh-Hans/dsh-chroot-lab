# DSH Agent（v3 宿主桥接）

这是 chroot 内 DSH 的可选宿主桥接，不是 chroot 的替代品，也不是隐藏性组件。包名 `dev.dsh.agent`，对其他 App 可见。

## 当前状态

- v3 引擎 API 已落盘，Kotlin 实现；`sys.status` 返回 `platform` 能力快照，`ui.invalidate` 支持 shell 动作后的页面作废；`ui.windows` 返回窗口栈，`ui.wait` 支持 `package/activity/keyboard` 条件，便于 shell 启动 App 后等待前台稳定；`ui.view(screenshot:true)` 可在同次调用内取截图，`ui.shot(annotate:true)` 可画 ref 框，`notif.history` / `ui.clipboard(op:"history")` 提供进程内历史。
- M3 增量（`ui.expect`、`ui.gesture`、`notif.*`、`ui.clipboard` 与 `ui.act` 扩展）与 Phase 4 的 presence/交接/回执已通过 `:agent-core:test`、`:agent-app:testDebugUnitTest`、`:agent-app:assembleDebug`；未做真机验收。
- APK 在 AI 操控时必须是后台服务；人类接口只有顶部“在场”胶囊、常驻通知、快捷设置磁贴和半透明接管页，不是 App 内实时面板。
- 私有侧载档使用 `targetSdk=29`；这只是 Android 行为兼容档，API 存在性仍按 `Build.VERSION.SDK_INT` 判断。APK 不持有共享存储，`FileBridge` / `AgentFileProvider` 已归档，文件面由 DSH shell 处理。

## 人类界面

主界面是“身体的状态”，不是第二个 DSH 面板：只显示 Agent 是否在线/暂停、无障碍/通知/保活/电池四项体检、最近回执和连接信息。视觉方向是 ColorOS 的 Aquamorphic：深色“在场”卡、呼吸光核、低饱和背景和单一主色。

对外状态统一来自 `PresencePhase`：`IDLE`（空闲，隐藏）、`WORKING`（AI 操作中）、`WAITING_HUMAN`（待人工接管）、`PAUSED`（暂停中）、`FINISHED`（已结束）、`DISCONNECTED`（已断开）。`FINISHED` 只是“这次任务结束、可以碰手机”，成功/失败由回执颜色与标题区分。

UI 使用 [COUI](https://github.com/suqi8/coui) 1.1.0（Compose Multiplatform 的 ColorOS 风格组件库），主题跟随系统明暗色。

## 构建

```sh
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export HOME=/root
./gradlew :agent-app:assembleDebug
# agent-app/build/outputs/apk/debug/agent-app-debug.apk
```

构建链为 AGP 8.9.1、Kotlin 2.4.10、compileSdk 37（COUI 1.1.0 的 AAR metadata 要求）；`gradle.properties` 中已设置 `android.suppressUnsupportedCompileSdk=37`。需要 JDK 17+、Android SDK platform 37 / build-tools 37。debug 签名不是发布签名；`agent-app/debug.keystore` 被 `.gitignore` 忽略，不应提交。本地没有该文件时，可临时把 `tests/android-probe/debug.keystore` 复制过来完成本地构建，构建后删除。

## 边界

- 普通 App 不申请 root；可选 root helper 是显式安装、独立授权的高权限边界。给 `dev.dsh.agent` 授 root 只会让 App 有 `su` 能力，不会自动启动 helper；helper 是 `/data/adb/dsh-agent/dsh-root-helper.jar` 的独立进程（监听 17322、共享 token）。
- root helper 的 jar 已作为 APK raw 资源内置。用户在 App 的 “Root helper” 行点“启动”时，App 才通过 `su` 把它安装到 `/data/adb/dsh-agent` 并拉起进程；点“停止”会杀掉 helper 进程但保留 jar。该动作不暴露为 Agent RPC，AI 不能自行提权。无 UI/自动化场景仍可用 `agent-app/root-helper.sh install` / `start`，或 `root-helper-service.sh` 开机启动。
- 桥接只监听 `127.0.0.1:17321`，token 在 App 私有目录。
- 无障碍、通知使用权由用户显式授权；文件访问由 DSH shell 负责。未授权的能力返回明确错误，不伪造。
- 不内置 OCR；截图由调用方处理。
- 除 v3 控制面外，`handoff.request` 请求人类接手；`receipts.append` / `receipts.list` 写读本地任务回执。二者都是 App 侧本地能力，不经过 shell。
- 详细 API、错误码与后端选择见 `../docs/agent-engine-v3.md`；能力边界见 `../docs/agent-capability-matrix.md`；下一阶段计划见 `../docs/agent-plan.md`。

## 历史

v2 Python 客户端与旧协议文档已归档到 `../docs/archive/agent-v2/`，不要按旧方法名调用当前 APK。
