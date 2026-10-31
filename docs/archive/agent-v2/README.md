# Agent v2 归档

本目录保存 **v2 Agent 协议** 的客户端与文档，不再有当前效力。

- `dsh_agent.py`：v2 Python 标准库客户端，方法名是 `session.analyze`、`ui.query`、`ui.find`、`shot`、`job.*` 等，与 v3 的 `ui.view/ui.nodes/ui.diff/ui.act/...` 不兼容。
- `v2-protocol.md`：原 `agent/README.md`，v2 协议与持续会话说明。
- `agent-app-README-v2.md`：原 `agent-app/README.md`，描述 Java 实现与 v2 方法表；当前实现已迁移到 Kotlin v3 engine。
- `legacy-apk/`：旧 APK 全盘文件面 `FileBridge.kt` / `AgentFileProvider.kt`；新架构中 APK 不再持有共享存储，文件由 DSH shell 处理，仅供历史参考。

当前替代：

- v3 API：`docs/agent-engine-v3.md`
- 能力边界：`docs/agent-capability-matrix.md`
- 下一阶段计划：`docs/agent-plan.md`
- 当前模块说明：`agent-app/README.md`

**不要从本目录直接实现当前功能。**
