# 路线与当前阶段（2026-10-30 迁移）

> 旧的 P0–P5 路线已归档到
> [archive/notes/18-readmax-writecareless/roadmap-before.md](archive/notes/18-readmax-writecareless/roadmap-before.md)；
> 该旧路线以“全局 SELinux Enforcing / 读遮蔽”为前提，不再作为执行依据。

## 当前执行计划

见 [plan-v2.md](plan-v2.md)。优先级：

1. **P0**：归档旧模型、更新 requirements/hiddenness/design/decisions 口径（本轮文档已完成）。
2. **P1**：读可见性：selinuxfs 默认只读、`/dev/block` 默认只读、读路径不再做隐藏遮罩。
3. **P2**：写安全硬伤：SELinux 门禁移除、rootfs 符号链接逃逸、loop 记录失败、快照归档 symlink、watchdog 覆盖、lock stale。
4. **P3**：粗心操作陷阱：snapshot 未知子命令、image source 检查、config 路径、ttyd 更新状态、日志轮转、status 脱敏、plan 文案。
5. **P4**：host/mock 回归测试与文档检查；不做破坏性真机实验。

## 状态

## 状态

- 2026-10-30：`plan-v2.md` 建立；旧文档快照已归档；读写模型运行时代码尚未改动。
- 下一步：先补 P2-1/P2-2 的测试，再改 `lifecycle.sh`、`dsh-nsinit`、`mount.sh`。
- 2026-10-31：app 分支并入主线（agent v3 手机控制面、presence UI、phone MCP）；Agent 下一阶段计划见 `docs/agent-plan.md`。
