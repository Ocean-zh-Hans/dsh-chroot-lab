# 归档说明

本目录保存**历史材料**，不再有当前效力。

- 原 `notes/` 整体移到这里：`archive/notes/`。
- 旧设计、旧决策单、旧路线、早期研究、逐项目分析都在里面。
- 保留原因：追溯设计演进、事故证据、第三方项目分析、验证命令。
- 使用规则：**不要从归档文档直接实现当前功能**。当前实现只以 `docs/decisions.md`、`docs/plan-v2.md`、`docs/design-v1.5.md`、`docs/requirements.md` 为准。

## 归档映射

| 归档文件 | 历史作用 | 当前替代 |
|---|---|---|
| `notes/02-requirements.md` | v2 需求草案 | `docs/requirements.md` |
| `notes/03-rules-and-safety.md` | 规则层/可逆性草案 | `docs/design-v1.5.md` §8、`docs/requirements.md` |
| `notes/04-design.md` | v1.0 设计 | `docs/design-v1.5.md` |
| `notes/05-design-v1.md` | v1.1 设计 | `docs/design-v1.5.md` |
| `notes/06-packaging.md` | v1.2 打包草案 | `docs/packaging.md` |
| `notes/07-roadmap.md` | v1.2 路线 | `docs/roadmap.md` |
| `notes/08-design-review.md` | v1.2 评审/P0 证据 | `docs/design-v1.5.md`、`docs/verification.md` |
| `notes/09-p0-evidence.md` | 早期 P0 证据 | `docs/verification.md` |
| `notes/10-design-v1.3.md` | daily/lab/rescue 三档设计 | `docs/design-v1.5.md`（已废弃三档） |
| `notes/11-design-v1.4.md` | full 设计 v1.4 | `docs/design-v1.5.md` |
| `notes/12-v1.4-verification.md` | v1.4 验证日志 | `docs/verification.md` |
| `notes/13-incident-2026-10-26-selinux-remount.md` | 事故复盘 | `docs/incident.md` |
| `notes/14-red-team-v1.4.md` | v1.4 红队 | `docs/design-v1.5.md` §1（P0 状态） |
| `notes/15-decisions-pending.md` | 旧决策单/推荐组合 | `docs/decisions.md`（已锁定） |
| `notes/16-hiddenness-first.md` | 应用层隐藏性 | `docs/hiddenness.md` |
| `notes/17-project-overview.md` | 总览 v2 | `docs/README.md`、`docs/design-v1.5.md` |
| `agent-v2/` | v2 Python 客户端、旧协议与旧模块 README | `docs/agent-plan.md`、`docs/agent-engine-v3.md` |
| `notes/18-readmax-writecareless/` | 2026-10-30 前旧读/写/SELinux 模型快照 | `docs/plan-v2.md`、`docs/requirements.md`、`docs/hiddenness.md` |
| `notes/analysis/` | 第三方项目逐项分析 | 历史参考；不直接复制代码/结论 |
| `notes/research/` | 早期调研 | 历史参考；部分推荐已过时 |

## 已知会被归档“覆盖”的旧说法

以下旧说法**不再有效**，不要在新代码/新文档中复活：

- v2 Agent 方法名 `session.analyze` / `ui.query` / `ui.find` / `shot` / `job.*` → v3 的 `ui.view` / `ui.nodes` / `ui.diff` / `ui.act` / `ui.wait` 等；v2 客户端在 `agent-v2/`，不要复活。

- `daily` / `lab` / `rescue` 三档 → 只有 `full` + `rescue`。
- 目录型 rootfs 主线 → ext4 镜像主线。
- `/data/adb/chroot/rootfs` 目录 → `/data/media/0/ubuntu-rootfs/rootfs.img`。
- `/run/dsh-rootfs` → `/tmp/dsh-rootfs`（宿主 `/run` 不存在）。
- `unshare --ipc` → 只 `--uts`（IPC_NS 未编译）。
- `uiautomator dump` 可用 → 本机不可用，只 `runtest` 或宿主 agent。
- `/storage` 只挂 `/storage/emulated/0` → 完整 rbind `/storage`。
- `/apex` 顶层 `remount,ro,bind` 等于整树只读 → 必须递归 ro。
- `u:r:ksu:s0` 受全局 Enforcing 约束 → 它是 permissive allow-all，不受约束。
- `getenforce` 作为判据 → **SELinux 状态不读、不修改、不判断**。
- `--permissive` 默认/break-glass → **整个 `--permissive` 开关删除**。
- `/data` 三路径遮罩就够 → 已知别名 best-effort 遮罩，且承认 `/proc/1/root` 绕过。
- “hardguard 能保证隐藏性” → 只是命令级护栏，不是边界。

## 来源

归档内容中的命令/结论可能包含当时未验证或后来被纠正的推断；引用前先看 `docs/verification.md` 的“未验证”清单。
