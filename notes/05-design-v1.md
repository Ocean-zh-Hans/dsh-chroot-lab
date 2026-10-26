# 设计 v1.2（定稿）：全能 AI 的真 chroot 环境

> **2026-10-26 评审后状态**：本文件是 v1.2 原文，保留作对照。评审发现的 8 个 P0 与 v1.3 修订建议见 [`08-design-review.md`](08-design-review.md)；**优化后的 v1.3 设计见 [`10-design-v1.3.md`](10-design-v1.3.md)**。在 v1.3 落地前，不要按本文件的挂载/停止/压缩命令直接施工（尤其 §5.2 `mount --make-rprivate`、§5.5 宿主侧 umount 校验、§5.6 `tar --zstd`、§5.7 hooks 路径）。

- 日期：2026-10-26 ｜ 设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 证据：A 级 = 官方文档/源码/本机实测；B 级 = issue/维护者回复；C 级仅线索。详见 notes/research/web/INDEX.md

## 0. 一句话结论

**用「目录型 Ubuntu 24.04 (glibc) rootfs + 真 chroot（ksu 域 root）+ s6 监督 + raw /data/media/0 工作区 + tar 冷快照 + DSH hooks 规则/审计」**。
它不是沙箱；安全性来自规则与可逆性，不来自隔离。

## 1. 设备现状（实测）

| 项 | 事实 |
|---|---|
| 系统 | Android 15 / SDK 35 / 4KB 页 / 安全补丁 2025-09-01 |
| Root | SukiSU 4.2.1-r2；`su` → `uid=0, u:r:ksu:s0`；Enforcing |
| 内核 | 6.6.66 GKI；`USER_NS`/`PID_NS`/`LANDLOCK` 未编译；`LOOP`/`EXT4`/`DM_SNAPSHOT`/`OVERLAY` 已编译 |
| 文件系统 | /data f2fs（无 noexec，有 nosuid/nodev）；/sdcard FUSE noexec；/storage tmpfs noexec |
| 关键限制 | loop 只在 **/data/media/0** 可用（FBE 加密区读 loop → EIO）；f2fs 无 reflink；硬链接可建但会污染快照 |
| 网络 | Docker Hub 不可达；Ubuntu cdimage / deb.debian.org / GitHub 可达 |

## 2. 可加什么（基础环境候选）

| 候选 | 体积 | 结论 |
|---|---|---|
| Ubuntu base 24.04 arm64（官方 tarball + SHA256） | ~30MB 起，装完 DSH 约 0.8–1.2GB | ✅ 选它（glibc，node-pty/sharp 预编译可直接用） |
| Debian 12（debootstrap） | 相近 | ⭕ 备选，等价 |
| Alpine minirootfs | 5–8MB | ❌ node-pty 无 musl 预编译；cua-driver musl 包 404 且**静默降级** |
| 现成 Docker 镜像 | — | ❌ Docker Hub 不可达（实测超时） |

## 3. 我们要什么

- 真 chroot、root、宽挂载、能 apt/编译/挂载；Go 产物可直接运行；
- 工作区在共享存储可见（raw media）；
- 开机自启、可启停、可诊断；
- 可逆性（快照/回收站/审计）与规则层（deny/ask）；
- 不做安全沙箱、不做多容器、不动内核、不替代 DSHA 设备控制。

## 4. 路线比较与推荐

| 路线 | 结论 | 理由 |
|---|---|---|
| proot / DSHA 式 | ❌ | 有 root 不必付 ptrace 翻译；且绑进 app 沙箱 |
| Alpine APK（dsh-alpine-ksu 式） | ⚠️ 可跑但不选 | musl 补丁链（node-pty 重建、koffi shim）+ 静默降级风险 |
| Termux 原生（现状） | ⭕ 保留为备用 | 最轻、补丁成熟；但无开机自启、无法脱离 Termux |
| AVF 虚拟机 | ⏸ 未来 | 本机 Android 15 无用户入口；架构上不堵死 |
| **真 chroot（推荐）** | ✅ | 原生 syscall、可开机自启、可控目录与快照；代价是 root 域无隔离 |

## 5. 详细设计

### 5.1 目录

```
/data/adb/chroot/
├── payload/   ubuntu-base-24.04.x-arm64.tar.gz（带 SHA256）+ dsh-baseline.tar.zst
├── rootfs/    解压后的 Ubuntu（活动根）
├── state/     DSH_HOME / ttyd.pass / port / stopped
├── logs/      bootstrap / launch / s6 / dsh / audit
├── hooks/     pretool.sh / posttool.sh / deny.txt / ask.txt
├── etc/s6/    dsh/run、ttyd/run
└── snapshots/ rootfs-<ts>.tar.zst、state-<ts>.tar.zst
```
（模块目录 /data/adb/modules/<id> 卸载即删，不放数据；不 bind 模块目录进容器。）

### 5.2 挂载（父先子后；每次启动私有 namespace）

```
unshare -m
mount --make-rprivate /
mount --rbind /dev            <rootfs>/dev
mount --rbind /dev/pts        <rootfs>/dev/pts
mount -t proc proc            <rootfs>/proc
mount --rbind /sys            <rootfs>/sys
mount --bind /system          <rootfs>/system     (ro: mount -o remount,ro,bind)
mount --bind /apex            <rootfs>/apex       (ro)
mount --bind /vendor          <rootfs>/vendor     (ro)
mount --bind /data/media/0    <rootfs>/sdcard     (raw media，绕 FUSE)
mount --bind state            <rootfs>/root/.dsh
```
不 bind /dev/block。

### 5.3 身份

- chroot 内 **root**（ksu 域），跳过 `ensure_data_suid`（不动宿主 /data 的 nosuid/nodev）。
- 可选 UID1+groups（若日后需要 /sdcard 属主更自然），但会失去部分能力。
- 加固选项（实测）：`su -Z` 可切到 `u:r:shell:s0` / `u:r:untrusted_app:s0` / `u:r:priv_app:s0` / `u:r:system_app:s0`；但 `u:r:su:s0` 会 rc=139（SIGSEGV），**禁用**。是否用 shell 域取决于它能否读 `/data/adb`（待测），默认不用。

### 5.4 启动链

`service.sh`（立刻 return）→ `dsh-bootstrap`（等 `sys.boot_completed=1` 与 /data/media/0；解压 new→校验→mv 交换）→ `unshare -m` + 挂载 + chroot → **s6-supervise**（dsh、ttyd）。
服务前台运行（无 PID ns，double-fork 会逃逸）。

### 5.5 停止

写 `state/stopped` → `s6-svc -d` / 进程组 TERM → pid+starttime 校验真实退出 → 超时 KILL → 逆序 umount → 校验 mountinfo → 删 lock。

### 5.6 快照与回滚

- 冷快照：stop → sync → `tar --zstd` 全量；rootfs 保留 1 golden + 3 近期 + 1 pre-change；state 保留 7 份。
- 回滚：停服务 → 解压/替换 rootfs → 重启 → 冒烟（`dsh --version`、node-pty、端口）。
- 硬链接快照只用于不可变归档（会因原地写污染）。

### 5.7 规则与审计

- 预设 `root-full` = danger-full-access + **ask**；`/permission` 可切。
- `dsh-hooks-claude-code` + `hooks.json`（PreToolUse matcher `*`，脚本内按 tool_name 分支；PostToolUse 写审计）。
- deny → exit 2 或 JSON `permissionDecision: deny`；ask → `permissionDecision: ask`（走批准 seam，无 answerer 时 fail closed）。
- 钩子 fail open：零依赖 sh、5–10s 超时、启动自检。

### 5.8 版本

模块 `versionCode` 单调整数；DSH floor-only + bake 台账；`versions/<v>` + `current` 软链 + `previous` 回滚。

### 5.9 诊断

`dsh-ctl status|diag|log|snapshot|start|stop|restart`：状态含 rootfs 版本、DSH 版本、端口、token 位置、挂载点数量。

## 6. 验收清单

**P0（不通过不进入打包）**
1. chroot 内 `id`=0、`uname -r` 与宿主一致；
2. Go 在 /sdcard 工作区编译并直接运行产物；
3. node-pty 能起 PTY；`dsh web --port 0` 打印 token URL 且可访问；
4. stop 后 /proc/self/mountinfo 无残留、无孤儿进程；
5. tar 快照 → 破坏 → 回滚 一轮通过；
6. 钩子 deny 已知坏命令 + 审计有记录 + 自检报警。

**P1**
- 开机自启（service.sh 非阻塞、boot_completed 后 10–60s 内就绪）；
- DSH 升/降级 + rollback；
- 断电/强杀后重启自愈。

## 7. 风险与不设防声明

不防：root 攻击者、物理接触、提示注入本身（只限爆炸半径）、DSH 上游漏洞、内核/固件、用户自己的不可逆命令。
不承诺：隔离（本机无 userns/pidns/Landlock）、性能数字（未做同机对照）。

## 8. 实施注意（来自 issue 实证，B 级）

1. **不用 post-fs-data**：KernelSU #3693 实测它在 APEX 激活前运行（Magisk 会等 ~40s），依赖 /apex 的脚本静默失败 → 只用 `service.sh`。
2. **devpts 会 EBUSY**：挂载前检查目标是否已有挂载；用私有 mount ns + `newinstance`；遇到 EBUSY 先清残留，不要硬挂（chroot-distro #102）。
3. **退出可能卡死**：chroot-distro #101 报过 kill 也失败 → stop 必须 TERM → 等待 → KILL → 逆序 umount，并校验 mountinfo（设计 §6 已含）。
4. **脚本权限**：所有入口脚本 0755（KernelSU #3396：uninstall.sh 缺可执行位不生效）。
5. **SukiSU 安装入口**：不要用「打开方式」，走 管理器 → 模块 → 从文件安装（SukiSU #975）。
6. **matcher 先用 `*`**：工具名以审计日志里的 `tool_name` 为准，观察后再收窄。

## 9. 开放问题

**已决定（2026-10-26）**：chroot 内用 **root**；规则脚本用 **sh**；payload **手工 push 到 /data/adb/chroot/payload**；监督器用 **s6-supervise**。

**仍开放（不阻塞设计）**
- [ ] DSH 工具真实名称（matcher 先用 *，跑一天审计后收窄）
- [ ] su -Z 到 shell 域能否读 /data/adb（若可，作为可选加固层）
- [ ] s6 服务目录里 dsh/ttyd 的最终 exec 参数（与 --port 0 联动）
## 10. 验证记录（本机实测，2026-10-26）

| 实验 | 结果 | 结论 |
|---|---|---|
| `unshare -m` / `unshare -U` / `unshare -p` | -m 成功；-U、-p 均 EINVAL | 只有 mount ns 可用 |
| `chroot` 最小 rootfs | 成功进入并执行（缺库报错即证明已切根） | 真 chroot 可行 |
| `setpriv` UID1 + 全 caps 丢弃 | 二进制正常执行 | 降权方案可行 |
| raw `/data/media/0` 执行 | 成功；FUSE `/sdcard` 即使 chmod 755 也 EACCES | 工作区必须 raw bind |
| ext4 镜像 loop（/data/adb、/data/local/tmp） | EIO / unable to read superblock | FBE 加密区不可 loop |
| ext4 镜像 loop（/data/media/0，-b 4096 + sector-size 4096） | 挂载、执行、写入全部成功 | 镜像模式仅在未加密区可用 |
| hardlink（/data/adb，root） | links=2、同 inode | 可建，但会污染快照 |
| reflink（f2fs） | EOPNOTSUPP | 不支持 |
| `rsync -aH --link-dest` | 同 inode 复用；但 Termux rsync 无 ACL/xattr（-A/-X 报错） | 只作不可变归档 |
| node-pty prebuilds | linux-arm64 / linux-x64 / darwin / win32，**无 linuxmusl** | 选 glibc 的决定性证据 |
| DSH node_modules 体积 | 514MB（@deepseek-ai 218MB） | payload 估算依据 |
| `dsh web --port 0` | --help 明写 "pass 0 to let the OS pick a free one" | 动态端口可行 |
| `ksud feature list` | su_compat / kernel_umount / selinux_hide 已启用 | 记录 |
| `su -Z` 各域 | shell/untrusted_app/priv_app/system_app 成功；u:r:su:s0 rc=139 | 禁用 su 域 |

## 11. 配套文档

- [06-packaging.md](06-packaging.md)：模块打包清单与构建门禁
- [07-roadmap.md](07-roadmap.md)：从烘焙到模块化的分阶段实施路线
- [research/web/INDEX.md](research/web/INDEX.md)：所有网页来源与可信度登记
