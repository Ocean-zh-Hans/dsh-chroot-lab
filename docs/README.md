# DSH Ubuntu Chroot — 文档入口

- 状态：**开发完成（P1–P5 代码/脚本）**（2026-10-26）；真实挂载验证由 PC AI 在备用机执行
- 目标设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 当前代码：模块骨架 + `dsh-ctl doctor/plan/status/start/stop/snapshot/...`；**apply 需显式 `--apply`，尚未在主力机执行**
- 当前设计：`design-v1.5.md`
- 当前决策：`decisions.md`（已锁定）

> 这里描述的是**同一台手机上的 chroot 进程树**，不是虚拟机、不是 proot、不是第二台设备。
> DSH 在 chroot 内是 **uid 0**；`approval-free` 只表示不弹批准，不表示没有 root。
> “隐藏性”默认为**应用层隐藏性**：游戏、银行、Play Integrity、普通 root 检测 App 不因本模块的可见痕迹发现 root；不是防恶意 root 的安全边界。

## 0. 权威顺序

如果文档之间出现冲突，按以下顺序执行，不再并列解释：

1. **`decisions.md`**：用户已拍板的 15 项选择，任何设计不得改写。
2. **`design-v1.5.md`**：当前唯一技术设计；P0 修正和实现边界的权威。
3. **`requirements.md`**：目标、非目标、硬不变量。
4. **`hiddenness.md`**：应用层隐藏性模型。
5. **`verification.md`**：实测证据；只证明“测过什么”，不反推设计。
6. **`incident.md`**：事故与永久禁止事项。
7. **`roadmap.md` / `packaging.md`**：执行与打包。
8. **`archive/`**：历史材料，**没有当前效力**；只用于追溯为什么走到今天。

## 1. 当前文档

| 文档 | 作用 |
|---|---|
| [requirements.md](requirements.md) | 目标、非目标、硬需求、验收定义 |
| [design-v1.5.md](design-v1.5.md) | **当前设计**：架构、挂载、生命周期、快照、安全门 |
| [decisions.md](decisions.md) | **已锁定决策**：`1A 2B 3A 4B 5A 6B 7A 8A 9A 10A 11A 12A 13A 14B 15A` |
| [hiddenness.md](hiddenness.md) | 应用层隐藏性：App 能看到/看不到什么 |
| [verification.md](verification.md) | 本机已验证、未验证、能力矩阵 |
| [incident.md](incident.md) | 2026-10-26 系统崩溃复盘与禁止事项 |
| [roadmap.md](roadmap.md) | P0–P5 阶段、代码与验证状态 |
| [packaging.md](packaging.md) | 模块 zip、payload、工具链、构建门禁 |
| [handoff.md](handoff.md) | **备用机/PC AI 验收交接手册** |
| [../agent/README.md](../agent/README.md) | 宿主 thin agent 协议草案（P4） |
| [../agent-app/README.md](../agent-app/README.md) | 最小宿主 agent App（可选，未真机验证） |
| [archive/README.md](archive/README.md) | 旧笔记归档说明；旧文档不再有当前效力 |

## 2. 一页事实表

| 项 | 值 |
|---|---|
| 设备 | OnePlus PJZ110，Android 15，kernel 6.6.66 |
| Root | SukiSU 4.2.1-r2；`u:r:ksu:s0` 是 permissive allow-all 域 |
| 全局 SELinux | 必须保持 Enforcing；`getenforce` 不可靠，读 `/sys/fs/selinux/enforce` |
| 命名空间 | mount + UTS 可用；PID/USER/IPC ns 未编译 |
| chroot | 真 chroot；非 proot；共享内核/网络/硬件 |
| rootfs | Ubuntu 24.04 glibc；ext4 镜像；128GB 存储容量（非 RAM） |
| 镜像 | `/data/media/0/ubuntu-rootfs/rootfs.img`（loop 只能放未加密 media） |
| 挂载点 | `/tmp/dsh-rootfs`（宿主 `/tmp` 是 tmpfs；`/run` 不存在） |
| 模块私有数据 | `/data/adb/chroot` |
| DSH 运行时 | `/data/adb/chroot/dsh` → chroot `/opt/dsh` |
| DSH home | `/data/adb/chroot/state/dsh-home` → chroot `/root/.dsh` |
| DSH 工作区 | `/data/media/0/DSH/workspace` → chroot `/sdcard/DSH/workspace` |
| 控制面 | `/data/adb/chroot/control` → chroot `/opt/dsh-control` |

## 3. 当前阶段边界

P1/P2 静态与代码边界：

- 读取系统状态（`doctor`）；
- 打印挂载/生命周期计划（`plan`）；
- 读取状态文件并校验 PID/starttime/ns inode（`status`）；
- 模块代码/构建/本地 mock 测试。

仍然禁止：

- 在主力机执行任何 `mount`、`umount`、`losetup`、`unshare`、`chroot`、overlay、debugfs/tracefs 试验；
- 改宿主 SELinux、全局 prop、全局 mount namespace；
- 在主力机上做 apply/mount/loop 测试；
- 把 `plan` 的输出说成“已经可用”。

真正的 apply 必须等 P2：备用机/VM + private namespace + trap 清理 + 零残留验收。

## 4. 一句话

**Ubuntu 是基础系统，DSH 是第一个应用；chroot 内部全开，但所有挂载只在 private mount namespace；全局 SELinux/prop/挂载视图不动；应用层看不到 chroot 的内部。**

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
