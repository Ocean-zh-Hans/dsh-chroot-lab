> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# 分析：YevolcXhb/mini-panel

- 来源：`sources/YevolcXhb_mini-panel`（2026-10-23，main；约 28MB）
- 许可：**AGPL-3.0**（强 copyleft；不能把代码并入 MIT 模块）
- 定位：基于 1Panel + DockRoot 的轻量服务器面板，专为 Android Magisk chroot Linux 设计；带 Mini Agent（ReAct 运维助手）
- 一句话评价：**它的“面板 + Agent + 计划确认 + 工具注册表”是很好的产品化参考，但运行时是 Docker/DockRoot 容器，不是真 chroot；Agent 安全是字符串黑名单，不能当我们规则层的核心。**

---

## 1. 问题与约束

- 目标：在 Android 的 chroot Linux 里跑一个 Web 管理面板：系统监控、文件管理、Web 终端、进程、容器、应用商店、计划任务、设置、Agent。
- 技术栈：Go 1.23 + Gin + GORM/SQLite + Vue 3 + Element Plus + xterm.js + SSE。
- 运行前提：root；容器管理用 DockRoot（Docker 镜像/容器，host 网络；不支持 compose/build）。
- 明确限制（README）：仅 host 网络、不支持 Docker Compose、不支持镜像构建。
- 它不是“AI 环境”，而是“面板 + 一个 Agent 功能”；Agent 是可选能力，不是核心运行时。

## 2. 运行模型

- **面板服务**：Go 二进制（`backend/cmd/server`）在 chroot 内运行，监听 HTTP；前端是 Vue SPA；WebSocket 终端；SQLite 存用户/设置/会话/审计。
- **Agent**：
  - `internal/agent/agentcore`：ReAct 循环；`internal/agent/provider` 支持 OpenAI/Anthropic/Ollama/DeepSeek（OpenAI 兼容）；
  - `internal/agent/tools`：`execute_command`、文件读写、容器、网站、数据库、防火墙、备份、系统信息、进程、web_search/web_fetch 等；
  - `internal/agent/orchestrator`：三阶段 PLANNING → 用户确认 → CODING → REVIEWING；`PendingPlan` 等待确认；
  - 上下文压缩/检查点/溢出重试；lazy-ref 大输出；SSE 流式。
- **容器**：DockRoot（`cmd/dockroot`）拉取 OCI 镜像，`ruri` 运行；host 网络。它是“容器管理”，不是“真 chroot 环境”；和我们的路线不同。
- **权限**：面板/Agent 以 root 运行；文件管理/终端/exec 都是全权限；没有降权。

## 3. 生命周期

- 安装：`install.sh`/release 包；面板服务常驻；前端静态资源；SQLite。
- 容器：DockRoot 拉镜像/运行/停止/删除；容器状态由面板管理；不是开机自启的 chroot。
- Agent：会话持久化；计划确认挂起；工具确认挂起；上下文压缩；错误重试。
- 更新：面板二进制/前端替换；没有 rootfs 快照/回滚；数据库迁移靠 GORM。

## 4. 数据与更新

- SQLite：用户、设置、Agent 会话、审计日志（`audit_log`）、备份记录、容器/网站/数据库元数据。
- 配置：`config.yaml`（端口、DB、JWT、Agent provider）。
- 备份：面板自身有 backup service/tool；不是 rootfs 级快照。
- 更新：release/install.sh；没有 A/B 或回滚；AGPL 源码义务。

## 5. 为什么这样写（证据）

1. **Agent 工具注册表 + ReAct**：`agentcore/engine.go`、`tools/base.go`；工具接口统一 `Name/Description/Parameters/Execute`；便于扩展。
2. **计划确认**：`orchestrator.go` 的 `PendingPlan`、`pendingPlans`、`ConfirmPlan`；PLANNING 阶段先出计划，用户确认后才 CODING。这是“破坏性操作先计划”的产品化实现。
3. **危险命令黑名单**：`tools/exec.go` 的 `dangerousPatterns`（`rm -rf`、`mkfs`、`dd`、`reboot`、`curl|sh` 等），命中后返回 `confirm required`；`AllowDangerous` 可跳过。**这是子串匹配，容易绕过/误杀**；不是安全边界。
4. **Agent 工具拒绝 Docker 直连**：`exec.go` 里 `docker ` 前缀强制走 `container_op`，避免模型绕过面板的容器管理。
5. **上下文压缩/延迟引用**：`compression`、`lazy_ref`；大输出只回预览+引用，模型可再取；对长会话有用。
6. **面板功能齐全**：Dashboard/Monitor/FileManager/Terminal/Process/Container/Backup/Firewall/Website/Database/SSH/Settings；前端 Element Plus；适合移动端/桌面浏览器。
7. **AGPL**：LICENSE 是 AGPL-3.0；如果分发修改版，必须提供源码；不能并入我们的 MIT 模块。

## 6. 更优雅的做法（带代价）

| # | 现状 | 更优雅 | 代价/风险 |
|---|---|---|---|
| 1 | 面板 + Agent + 容器管理全打包 | 只保留“面板/监控/文件/终端”作为可选 sidecar；Agent 用 DSH | 少一个 Agent；但避免重复/两套权限模型 |
| 2 | 危险命令子串黑名单 | 结构化规则 + 服务级 fail-closed 自检 + 快照；见 08/10 | 需要维护规则/测试；但可靠性高 |
| 3 | Agent 以 root 在面板进程内跑 | 独立进程/独立 uid；或只通过 DSH 的 hooks/approval | 需要进程隔离/通信；但爆炸半径小 |
| 4 | Docker/DockRoot 容器 | 我们的真 chroot + profiles；如果需要多容器，再引入 DockRoot | 失去 Docker 生态；但符合“真 chroot”目标 |
| 5 | 单体 Go 面板 | 模块化：panel API / agent / runtime adapter；或直接用 DSH web | 开发量；但可替换 |
| 6 | 无 rootfs 快照 | 面板只做 UI，环境生命周期交给 `dsh-ctl` | 需要边界；但职责清晰 |
| 7 | AGPL 代码 | 不复制；只借鉴 UI/流程；独立进程运行也需评估 AGPL | 法律成本；内部使用通常可控，分发要合规 |

## 7. 对我们的启示

**值得借鉴**
- **计划确认**：破坏性任务先 PLANNING → 用户确认 → 执行；可以映射到 DSH 的 plan mode/approval，不要直接让模型执行 `rm -rf`。
- **工具分类**：系统/容器/文件/备份/网络/数据库；可用于设计 DSH 的“能力清单/风险等级”。
- **大输出 lazy-ref**：DSH 已有 spill file；可以对齐“预览 + 引用”。
- **面板信息架构**：Dashboard、Monitor、Terminal、FileManager、Backup、Logs；如果以后做移动端 UI，可参考。
- **Agent 会话/压缩**：DSH 已有；不必重复。

**不要照搬**
- 子串黑名单 + `AllowDangerous` 开关；这不是 fail-closed。
- 面板/Agent 同进程 root；没有独立权限边界。
- Docker/DockRoot 作为运行时；与“真 chroot”目标冲突。
- 把面板和 DSH 两个 Agent 同时跑；会抢权限、抢端口、互相污染会话。

**结论**：mini-panel 适合作为**可选 sidecar（lab 档）**或 **UI/流程参考**，不适合作为 v1.3 的核心运行时；AGPL 代码不并入模块。
