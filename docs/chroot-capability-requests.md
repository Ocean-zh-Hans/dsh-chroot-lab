# Chroot / DSH 侧能力申请

状态：**已记录，未开发**。这些能力需要 chroot 内的 DSH runtime 或 shell 层配合，APK 不实现，也不应为了它们往 APK 塞第二套 shell。

APK 的边界仍是：只提供无障碍 UI 的“眼”和“手”，以及通知、剪贴板、手势、回执、交接这些必须贴着 Android UI 的能力。

## C1. 有状态的 shell / REPL session

现状问题：一次 `exec` 起一个进程。`r2 -A`、`ssh`、`gdb`、`sqlite3`、`python`、`adb shell` 这类工具每次调用都重新初始化，状态丢失，模型只能反复重建。

请求接口：

```text
session.open({kind: "pty"|"pipe", cmd, cwd, env, rows, cols}) -> {sessionId}
session.exec({sessionId, input, timeoutMs, yieldMs}) -> {cursor, output, running, exitCode}
session.read({sessionId, cursor}) -> {cursor, output, running, exitCode}
session.signal({sessionId, signal})
session.close({sessionId})
session.list() -> [{sessionId, cmd, cwd, running, startedAt, lastActiveAt}]
```

要求：

- 每个 session 有独立 stdin/stdout/stderr 和生命周期；
- 输出用 cursor 增量读取，不能每次回传全量；
- 空闲超时、任务结束、DSH 重启都要有明确回收策略；
- 可选：用 `tmux` / `abduco` 做 DSH 重启后的存活层，但不能把 tmux 语义暴露给模型。

## C2. 大输出 artifact store

长反汇编、构建日志、`dmesg`、扫描结果不能直接塞进模型上下文。

- 每个 session 有环形缓冲和 `cursor`；
- 超过阈值的输出落 artifact，返回 `artifactId / bytes / sha256 / preview`；
- 模型按 `read(artifactId, offset, limit)` 分段读取；
- artifact 有 TTL 和任务级清理。

## C3. 后台 job

构建、下载、长任务需要：

```text
job.start({cmd, cwd, env}) -> {jobId}
job.status({jobId}) -> {state, exitCode, startedAt, endedAt}
job.logs({jobId, cursor, limit}) -> {cursor, lines}
job.cancel({jobId})
job.list({state?})
```

不能用一个长时间阻塞的 `exec` 占住模型回合。

## C4. DSH 侧 DeviceSession + `phone.*` facade

APK 已经有 `page`、`g/seq`、`uiEffect`、`ui.invalidate` 和 Kotlin 版 `DeviceSession` 参考实现；
但真正消费这些状态的是 chroot 内的 DSH，不是 APK。

需要：

- DSH 内实现 TypeScript 版 `DeviceSession`（或等价状态机）；
- 统一 `phone.view / phone.act / phone.observe / phone.wait / phone.handoff / phone.receipt` facade；
- shell 动作带 `uiEffect`，`possible/certain` 自动调用 `ui.invalidate`；
- 旧 `g` 的 ref 一律返回 `STALE_PAGE`，不静默重绑；
- APK 只暴露 `ui.*`，模型不直接感知“APK 还是 shell”。

## C5. 跨后端事务 / lease

一个用户任务可能跨 APK 和 shell（A→B→C）。需要：

- `TaskLease`：任务级租约，绑定 `sessionId`、权限范围、过期时间；
- 跨后端动作序列在 DSH 侧串行化；
- shell 动作后自动 `ui.invalidate` / refresh；
- 失败时保留回执与最后一张好页面。

## C6. 凭据隔离

shell session 里注入的 token、密码、私钥不能进模型上下文：

- secret 由 DSH 注入到进程环境或 stdin，不经过模型消息；
- 输出过滤已知 secret；
- 日志与回执默认脱敏。

## C7. 人类确认与策略

高风险动作（支付、删除、短信、电话、安装）由 DSH 策略层拦截，APK 只负责 UI 执行和状态回传。
