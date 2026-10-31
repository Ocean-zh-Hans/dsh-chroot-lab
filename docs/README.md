# DSH Ubuntu Chroot — 文档入口

- 状态：**2026-10-30 读写模型重写中；新计划见 `plan-v2.md`；chroot 核心已有 PC/备用机验收，主力机按用户授权作为正式运行环境**
- 目标设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 当前代码：模块与 `dsh-ctl doctor/plan/status/start/stop/snapshot/...`；改变镜像或生命周期的操作需显式 `--apply`；主力机已按授权安装激活，后续验证仍须先识别设备。运行时代码已切到 `SELINUXFS_MODE=ro`/`BLOCK_ACCESS=ro`；`/dev/block=ro` 保持默认可读，但 ro 只是视图、不能阻止设备节点写，真正写保护靠 R4 人工门禁；`masked` 仅显式可选
- 当前设计：`design-v1.5.md`（技术设计；冲突处按 `plan-v2.md`）
- 当前计划：`plan-v2.md`（2026-10-30 新模型）
- 当前决策：`decisions.md`（旧 15 项 + 2026-10-30 覆盖节）

> 这里描述的是**同一台手机上的 chroot 进程树**，不是虚拟机、不是 proot、不是第二台设备。
> DSH 在 chroot 内是 **uid 0**；`approval-free` 只表示不弹批准，不表示没有 root。
> “隐藏性”默认为**应用层隐藏性**：游戏、银行、Play Integrity、普通 root 检测 App 不因本模块的可见痕迹发现 root；不是防恶意 root 的安全边界。

## 0. 权威顺序

如果文档之间出现冲突，按以下顺序执行，不再并列解释：

1. **`decisions.md`**：用户已拍板的选择；2026-10-26 的 15 项组合保留，2026-10-30 覆盖节优先。
2. **`plan-v2.md`**：当前执行计划；读写模型、优先级和验收标准以此为准。
3. **`design-v1.5.md`**：技术设计；与 `plan-v2.md` 冲突时按 `plan-v2.md`。
4. **`requirements.md`**：目标、非目标、硬不变量。
5. **`hiddenness.md`**：应用层隐藏性模型（只讨论 App 可见信号，不是 chroot 读权限）。
6. **`verification.md`**：实测证据；只证明“测过什么”，不反推设计。
7. **`incident.md`**：事故与永久禁止事项。
8. **`roadmap.md` / `packaging.md`**：旧路线归档与打包说明；执行以 `plan-v2.md` 为准。
9. **`archive/`**：历史材料，**没有当前效力**；只用于追溯为什么走到今天。

## 1. 当前文档

| 文档 | 作用 |
|---|---|
| [plan-v2.md](plan-v2.md) | **当前执行计划**：读全开、写粗心好人、SELinux 不参与 |
| [console-design.md](console-design.md) | **Console 重做设计稿**：信息架构、交互、视觉、分期（设计模式，未实现） |
| [baseline-v2.md](baseline-v2.md) | **2026-10-31 当前基线**：范围、验证结果、已知缺口 |
| [requirements.md](requirements.md) | 目标、非目标、硬需求、验收定义 |
| [design-v1.5.md](design-v1.5.md) | **技术设计**：架构、挂载、生命周期、快照；冲突处按 `plan-v2.md` |
| [agent-engine-v3.md](agent-engine-v3.md) | **Agent APK 当前设计**：v3 API、后端选择、错误契约、能力边界 §12 |
| [agent-capability-matrix.md](agent-capability-matrix.md) | **能力矩阵**：什么进 APK（眼+手）、什么留给 Ubuntu shell/root |
| [agent-plan.md](agent-plan.md) | **Agent 下一阶段计划**：DeviceSession、跨后端连贯性、人类 ambient surface |
| [chroot-capability-requests.md](chroot-capability-requests.md) | **Chroot/DSH 侧能力申请清单**：有状态 shell、artifact、job、phone facade、lease、凭据隔离 |
| [kotlin-migration.md](kotlin-migration.md) | Java→Kotlin 全量迁移清单、构建改动与验收状态 |
| [research/README.md](research/README.md) | 8 组同类项目调研索引、质量规则与进度 |
| [decisions.md](decisions.md) | **已锁定决策**：`1A 2B 3A 4B 5A 6B 7A 8A 9A 10A 11A 12A 13A 14B 15A` |
| [hiddenness.md](hiddenness.md) | 应用层隐藏性：App 能看到/看不到什么 |
| [verification.md](verification.md) | 本机已验证、未验证、能力矩阵 |
| [incident.md](incident.md) | 2026-10-26 系统崩溃复盘与禁止事项 |
| [roadmap.md](roadmap.md) | 旧 P0–P5 已归档；当前路线指向 [plan-v2.md](plan-v2.md) |
| [packaging.md](packaging.md) | 模块 zip、payload、工具链、构建门禁 |
| [handoff.md](handoff.md) | **备用机/PC AI 验收交接手册** |
| [../agent-app/README.md](../agent-app/README.md) | v3 宿主桥接：构建、授权边界与当前状态 |
| [../console-app/README.md](../console-app/README.md) | Chroot Console 原生控制台 + WebView（可选） |
| [archive/README.md](archive/README.md) | 旧笔记归档说明；旧文档不再有当前效力 |

## 2. 一页事实表

| 项 | 值 |
|---|---|
| 设备 | OnePlus PJZ110，Android 15，kernel 6.6.66 |
| Root | SukiSU 4.2.1-r2；`u:r:ksu:s0` 是 permissive allow-all 域 |
| 全局 SELinux | **不读取、不修改、不判断**；不为它设置启动门或恢复逻辑 |
| 命名空间 | mount + UTS 可用；PID/USER/IPC ns 未编译 |
| chroot | 真 chroot；非 proot；共享内核/网络/硬件 |
| rootfs | Ubuntu 24.04 glibc；ext4 镜像；128GB 存储容量（非 RAM） |
| 镜像 | `/data/media/0/ubuntu-rootfs/rootfs.img`（loop 只能放未加密 media） |
| 挂载点 | `/tmp/dsh-rootfs`（宿主 `/tmp` 是 tmpfs；`/run` 不存在） |
| 模块私有数据 | `/data/adb/chroot` |
| DSH 运行时 | `/data/adb/chroot/dsh` → chroot `/opt/dsh` |
| DSH home | `/data/adb/chroot/state/dsh-home` → chroot `/root/.dsh` |
| DSH 工作区 | `/data/adb/chroot/state/workspace` → chroot `/root/workspace`；独立于 DSH home |
| 控制面 | `/data/adb/chroot/control` → chroot `/opt/dsh-control` |

## 3. 当前设备边界

主力机已在用户明确授权后完成最终安装、激活和一次手动验收，详见 `verification.md` §19–20；早期“禁止主力机 apply”的文字只描述当时阶段，不是当前事实。

- 新改动先在明确识别的测试机/arm64 VM 的 private namespace 验证，再决定是否交付。
- `doctor/plan/status` 是检查，不证明 chroot 已启动；挂载和生命周期操作仍需显式 `--apply`。
- 不主动修改宿主 SELinux、全局 prop 或全局 mount namespace；真实 purge/uninstall、清数据和块设备写入不是常规验证步骤。
- 备用机的 Magisk 结果不能外推为主力机 SukiSU 的完整兼容性，也不能承诺所有场景不会死机。

## 4. 一句话

**Ubuntu 是基础系统，DSH 是第一个应用；chroot 内部全开，但所有挂载只在 private mount namespace；全局 prop/挂载视图不动，SELinux 不读取/不修改/不判断；应用层看不到 chroot 的内部。**

## 5. 文档审计结论（2026-10-26）

- 原 `notes/` 已整体移到 `docs/archive/notes/`，并加了 ARCHIVED 横幅；旧文档不再有当前效力。
- 已消除的主要前后冲突：
  1. 旧决策单的推荐组合（2A/3B/5B）与用户锁定的 `2B 3A 5A` 冲突 → 以 `decisions.md` 为准。
  2. 目录型 rootfs / `/data/adb/chroot/rootfs` → 当前为 `/data/media/0/ubuntu-rootfs/rootfs.img`。
  3. `/run/dsh-rootfs` → 当前为 `/tmp/dsh-rootfs`（宿主 `/run` 不存在）。
  4. `protect-critical` 默认 off → 当前为 `6B` 默认递归 ro。
  5. `dsh-nsinit` 直接 exec s6 / s6 是子进程 → 统一为 **holder 长驻，s6 是子进程**。
  6. `/storage` 只挂 `/storage/emulated/0` → 当前为完整 `rbind /storage`。
  7. `/apex` 顶层 ro → 当前为递归 ro（14B）。
- 当前文档之间没有“设计 vs 决策”的冲突；未验证项集中列在 `verification.md`，不再伪装成已实现。
