# 设计 v0.2（全能 AI 的真 chroot 环境）

> 状态：设计草案。证据标记：**[实测]** 本机验证 ｜ **[源码]** 源码/文档 ｜ **[研究]** 子代理调研笔记。
> 结构沿用 DSH-Chroot 的文档骨架；所有"待定"都在 §12。

## 1. 目标与非目标

**目标**：PJZ110（Android 15 / SukiSU）上给 AI 一个**全能力** Linux 用户态：
真 chroot（非 proot）、root 身份、宽挂载、能 apt/编译/挂载；工作区在共享存储可见且产物可执行；
开机自启、可启停、可诊断；**可逆性**（快照/回滚/审计）与**规则层**（deny/ask）。

**非目标**：不做安全沙箱（本机内核不支持 userns/pidns/Landlock）；不做多容器；不追多机型/发布；不动内核；不替代 DSHA 的设备控制。

## 2. 现状盘点 [实测 2026-10-26]

| 项 | 事实 |
|---|---|
| 机型/系统 | OnePlus PJZ110 / Android 15 / kernel 6.6.66 |
| Root | SukiSU（KSU LKM），`su` → `uid=0, context=u:r:ksu:s0`，SELinux Enforcing |
| namespace | `CONFIG_NAMESPACES=y`；**`USER_NS` / `PID_NS` 未编译**；`unshare -m` 可用 |
| Landlock | **未编译** → DSH 文件沙箱永远不可用，只能 danger-full-access |
| 文件系统 | `/data` f2fs `rw,nosuid,nodev`（无 noexec）；`/sdcard` FUSE `noexec`；`/storage` tmpfs noexec |
| 内核特性 | `BLK_DEV_LOOP=y`、`EXT4_FS=y`、`DM_SNAPSHOT=y`、`OVERLAY_FS=y` [实测] |
| 工具 | `/system/bin/{mke2fs,mkfs.ext4,losetup,mount,umount}` 齐全 [实测] |
| 空间 | /data 684GB 可用 |

## 3. 路线选择

| 路线 | 结论 |
|---|---|
| proot / DSHA | ❌ 有 root 不付 ptrace 代价；且绑进 app 沙箱 |
| Alpine + musl | ❌ node-pty 只发 glibc 预编译；cua-driver musl 404 且**静默降级** [研究/源码] |
| **Debian slim (glibc) + 真 chroot** | ✅ 唯一"不现场编译、不静默降级" |
| overlayfs 分层 | ❌ 不采用（ADR-009：bind+ro+active 指针等价且可控；少一层内核依赖） |
| 硬链接快照当主线 | ❌ 见 §7（原地写会串改快照） |
| AVF 虚拟机 | ⏸ 本机无入口；架构上不堵死 |

## 4. 架构（四层 + 适配器）

```
L3 策略层    DSH: permission-preset(root-full) + hooks(deny/ask) + 审计
L2 DSH 运行时 node 24 + dsh（versions/<v> + current 软链 + previous 回滚）
L1 容器层    预烘焙 Debian rootfs（ext4 镜像）+ bind 挂载 + chroot + 身份
L0 宿主模块  KSU 模块：service.sh 非阻塞 → bootstrap → s6 监督 → 快照/清理
```

- **L1 不依赖 chroot-distro 运行时**：它只在宿主侧烘焙阶段用来装 rootfs、做 diff；运行时只用 mount+chroot（shell），开机路径零 Python。
- **L0 数据在模块目录外**：`/data/adb/modules/<id>` 会被整体删除 [研究]，所以 rootfs/state/payload 全放 `/data/adb/chroot/`。
- **不 bind 模块目录进容器**（ADR-009 约束：会穿过 KernelSU 的挂载视图）。

## 5. 目录与挂载

```
/data/adb/chroot/
├── payload/     rootfs.tar.zst（或 rootfs.img）+ dsh-baseline.tar.zst
├── images/      rootfs-<ts>.img（活动镜像与快照）
├── mnt/         当前活动镜像的挂载点（loop）
├── state/       DSH_HOME / ttyd.pass / port / stopped
├── logs/        bootstrap/launch/supervisor/dsh/audit
└── snapshots/   state 与镜像的冷快照
```

挂载（**父先子后**）：
`/dev`、`/dev/pts`、`/proc`、`/sys`、`/system:ro`、`/apex:ro`、`/vendor:ro`、`/product:ro`、
`/data/media/0 → /sdcard`（raw，绕 FUSE）、`/data/adb/chroot/state → /root/.dsh`。
不 bind `/dev/block`。启动时先 `unshare -m` 再 `mount --make-rprivate /`；树里有子挂载时用 `--rbind` [研究]。

## 6. 生命周期

- **启动**：`service.sh` 被 magiskd **同步等待** [研究] → 立刻 return，`setsid` + 关 fd 转后台；
  等 `sys.boot_completed=1` 与 `/data/media/0`（≤300s）→ bootstrap（版本标记 + new/old 原子交换）→ 监督器。
- **监督**：**s6-supervise**（C，4.1MiB；runit 592KiB）[研究]。无 PID ns → 服务必须**前台运行**；
  会自 daemonize 的程序用 `fghack` 思路兜住；自研 launcher 可设 `PR_SET_CHILD_SUBREAPER`。
  systemd 不可行（需 PID 1 + cgroup 容器接口），supervisord 需 Python。
- **停止**：写 `stopped`（等价 runsv 的 `down` 文件）+ 拿锁 → TERM **进程组** → 等真退出（pid + `/proc/<pid>/stat` starttime 校验）→ 超时 KILL → **逆序 umount** → 删状态。
  禁止按端口/进程名杀（`pkill` 名匹配只有 15 字符）[研究]。
- **崩溃**：s6 自动重启；stop 期间靠 `down` 文件阻止复活，避免竞态。

## 7. 数据、快照与回滚

**已实测决定**：
- f2fs **不支持 reflink**（`cp --reflink=always` → EOPNOTSUPP；`auto` 静默全量）[实测/研究]。
- `/data/adb` 下 **root 域可以创建硬链接**（links=2、同 inode、label `adb_data_file`）[实测]。
- `rsync -aH --link-dest` 可用；但 Termux 的 rsync **未编译 ACL/xattr**，`-A`/`-X` 会直接报错，只能用 `-aH` [实测]。
- **硬链接快照会与活动树共享 inode**：活动树里任何原地写（append/覆盖）都会污染所有快照 [实测]。
  → 只能用于**冷且此后不再写**的归档，不能当活动 rootfs 的主线快照。

**方案**：
- **rootfs = ext4 镜像**（稀疏文件，losetup 挂到 `mnt/`）：
  - 快照 = `cp --sparse=always` 整镜像（全量、免疫原地写；684GB 空间足够）；
  - 回滚 = 停服务 → umount → 换镜像文件 → 重新挂载；
  - 保留：1 份 golden + 3 份近期 + 1 份 pre-change。
- **state/（数百 MB）= tar.zst 冷快照**（先 stop+sync+umount），保留 7 份。
- 每周 `restic` 离线归档到 PC（防 /data 损坏）[研究]。
- P0 必须先验证：losetup 挂载、exec 权限、SELinux 标签、卸载干净。

## 8. 规则与审计（已定，详见 [research/dsh-extension-surface.md](research/dsh-extension-surface.md)）

- 预设 `root-full`：`sandbox: danger-full-access` + `approval: ask`（出厂 danger-full-access 预设会把 approval 设成 never）。
- `dsh-hooks-claude-code` + `hooks.json`；`PreToolUse` 钩子读 `deny.txt`/`ask.txt`，deny → exit 2 + 理由，ask → 走批准 seam。
- `PostToolUse` 钩子写 `logs/audit/YYYY-MM-DD.log`（时间/cwd/工具/输入）。
- **钩子 fail open**（崩了命令照跑）→ 零依赖 sh 脚本 + 启动自检 + 文档写明"护栏不是边界"。

## 9. 版本策略

模块 `versionCode` 单调整数（唯一比较字段）；DSH floor-only + bake 台账；升级装到 `versions/<v>`，smoke test 后切 `current`，保留 `previous`。

## 10. 威胁模型与不设防声明

资产：整机数据、DSH 凭据、设备控制、系统可用性。
不防：root 攻击者、物理接触、提示注入本身（只限爆炸半径）、DSH 上游漏洞、内核/固件、用户自己的不可逆命令。
**安全 = 规则（降低误操作）+ 可逆性（错误可回滚）+ 审计（事后可查）**。

## 11. 验收标准（P0，全过才打包）

1. chroot 内 `id`=0、`uname -r` 与宿主一致。
2. **Go 在 /sdcard 工作区编译并直接运行产物**。
3. `node-pty` 在 chroot 内起 PTY。
4. `dsh web --port 0` 打印 token URL 且可访问。
5. `dsh-update` 装新版 → smoke test → 切 current → rollback。
6. stop 后 `/proc/mounts` 无残留、无孤儿进程。
7. 镜像快照 → 破坏 → 回滚，一轮通过。
8. 钩子拒绝已知坏命令 + 审计有记录 + 失效自检报警。

## 12. 待定项

- [ ] Q1 进程身份：**root**（推荐：全能优先，且可跳过 `ensure_data_suid`，不动宿主 /data 的 nosuid）vs UID1+groups（/sdcard 属主更自然）
- [ ] Q2 规则脚本：纯 sh（推荐：快、零依赖）vs python（易写）
- [ ] Q3 payload 分发：手工 push 到 `/data/adb/chroot/payload`（推荐）vs 模块内置 zip
- [ ] Q4 s6 vs runit（体积差 3.5MiB，功能都够；倾向 s6）
