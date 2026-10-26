> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# 本机平台事实：Android 15 / 6.6 GKI / SukiSU Ultra

- 核验日期：2026-10-26 ｜ 全部为本机只读实测（`getprop`、`/proc/config.gz`、`dumpsys`、`ps`、`su --help`）
- 目的：把"我们的运行平台"钉死，避免照搬别人的机型结论

## 1. 构建与机型

| 项 | 值 |
|---|---|
| 机型 | OnePlus PJZ110 |
| Android | **15 / SDK 35** |
| 安全补丁 | 2025-09-01 |
| 指纹 | `OnePlus/PJZ110/OP5D0DL1:15/AP3A.240617.008/V.55bc87c-...` |
| 页面大小 | **4096（4KB）**（`getconf PAGE_SIZE`，root 与 app 一致） |

> Android 15 起平台支持 16KB 页设备，但**本机是 4KB**：我们不需要 16KB 对齐，仍保留检查以便移植。

## 2. 内核

`Linux 6.6.66-android15-8-g29d86c5fc9dd-abogki428889875-4k`（kleaf/GKI 构建，clang，4K 页）。

与安全/沙箱相关的 config（root 读 `/proc/config.gz`）：

| 配置 | 值 | 影响 |
|---|---|---|
| `CONFIG_NAMESPACES` | `y` | mount/UTS/IPC/net ns 可用 |
| `CONFIG_USER_NS` | **未设置** | userns 隔离档不可用（`unshare -U` EINVAL） |
| `CONFIG_PID_NS` | **未设置** | 无 PID 隔离；守护进程会被 reparent 给宿主 init |
| `CONFIG_SECURITY_LANDLOCK` | **未设置** | DSH 文件沙箱永远不可用，只能 danger-full-access |
| `CONFIG_MODULES` / `MODULE_SIG` | `y`/`y`；`MODULE_SIG_FORCE` **未设置** | 未签名模块可加载（LKM root 的前提） |
| `CONFIG_KALLSYMS` | `y` | ksud/模块可用 |
| `CONFIG_SECCOMP` | `y` | 可用 seccomp 但本机没 userns 可配合 |
| `CONFIG_BLK_DEV_LOOP` / `EXT4_FS` / `DM_SNAPSHOT` / `OVERLAY_FS` | 全 `y` | ext4 镜像 + loop 可行；overlayfs 编译在但按 ADR-009 不用 |
| `CONFIG_F2FS_FS` | `y` | /data 为 f2fs，无 reflink（实测 EOPNOTSUPP） |

**工具**：`/system/bin/{mke2fs,mkfs.ext4,losetup,mount,umount}` 齐全；Termux 侧 `rsync 3.5.0`（**无 ACL/xattr 支持**）、`e2fsprogs-glibc` 也在。

## 3. Root：SukiSU Ultra

- `su -v` → **`4.2.1-r2:KernelSU`**；`su` 上下文 `u:r:ksu:s0`（Enforcing）。
- `su --help` 的关键选项（本机实测）：
  `-c/--command`、`-l`、`-p`、`-s`、**`-M/--mount-master`（强制全局 mount namespace）**、
  `-g/-G`（主组/附加组）、**`-Z/--context`（指定 SELinux 上下文）**、`-W/--no-wrapper`、**`--ksu-no-new-privs`**。
- 实测：默认 `su` 与 `su -M` 里做的 tmpfs 挂载**都出现在调用方（Termux）的 `/proc/self/mountinfo` 里**。
  → 说明"全局命名空间"行为在本机与预期略有差异；我们的方案必须**自己 `unshare -m`**，并在停止时清理，不能依赖 su 的命名空间隔离。
- `ksud`（`/data/adb/ksud`）命令：`module / post-fs-data / services / boot-completed / late-load / soft-reboot / insmod / install / unload / uninstall / sepolicy / profile / feature / extension`。
  `ksud module list` 可读（JSON）。**不要随便调 install/uninstall/soft-reboot**。

## 4. SELinux 域：一个反直觉的实测

- **Termux 进程**（我们当前 shell）：`u:r:untrusted_app_25:s0:c512,c768`
  虽然 `dumpsys` 显示 `com.termux targetSdk=36`。
  → 说明**本 ROM 并没有按 targetSdk 把应用分到 `untrusted_app_29+` 域**（或安装期 seinfo 固定为旧值）。
  → 因此"targetSdk≥29 就不能从 app 数据 exec"这条**在本机不成立**：我们实测 `~/gotest/hello` 可直接执行。
  → 之前把 DSHA 的 Go 失败归因于 W^X 是**过度推断**；更可能只是工作区在 FUSE（noexec）。已在笔记中修正。
- 模块脚本/守护进程：`u:r:ksu:s0`（近似无约束）。
- DSHA 的 shizuku 子进程：`u:r:ksu:s0`（root）。
- 结论：**我们的 chroot 跑在 ksu 域**，exec/挂载/文件访问都不受 app 域限制；安全性靠规则 + 可逆性，不靠域隔离。

## 5. Android 15 平台面（与我们相关的部分）

- 应用目标 SDK：`com.termux` 36、`dsh.android.client` **37**、shell 35 → 平台允许安装，但**域映射以本机实测为准**（见 §4）。
- 存储：`/sdcard` = FUSE `nosuid,nodev,noexec`；`/storage` tmpfs noexec；`/data` f2fs 无 noexec。
- AVF：`init.svc.virtualizationservice` 存在（stopped）、`ro.boot.hypervisor.protected_vm.supported=true`、`com.android.microdroid.empty_payload` 已装 → **基础设施在，但没有面向普通应用/用户的 Linux VM 入口**（Android 16 的 Linux Terminal 才面向用户）。
- 其它：`developer.android.com` 在本环境**不可达**（fetch 失败），涉及"Android 15 行为变更"的条目需以本机实测或 AOSP 源码为准；本笔记只写实测项。

## 6. 对设计的直接影响

1. **身份**：chroot 内跑 root（ksu 域），跳过 `ensure_data_suid`（不必改宿主 /data 的 nosuid/nodev）。
2. **挂载**：每次启动 `unshare -m` + `mount --make-rprivate /`；**su 默认不提供命名空间隔离**（实测），必须自己收尾。
3. **镜像**：ext4 镜像 + loop 可用；4KB 页无需 16KB 对齐（保留检查）。
4. **模块**：不需要 metamodule（我们不改 /system）；模块目录会被删 → 数据放 `/data/adb/chroot`。
5. **不要假设别机结论**：userns/PIDns/Landlock 缺失、域映射、su 命名空间行为都必须在本机实测（已做）。
6. **酷安**：`https://www.coolapk.com/` 首页可打开（200），但搜索页返回 404、文章页需要登录/App → **无法作为可用的检索源**。
   替代：GitHub（KernelSU/SukiSU 文档与 issue）、XDA、Telegram 群、以及项目自带 docs。

## 7. 待验证

- [ ] `su -Z` 能切换到哪些上下文（是否可用于给 chroot 一个更受限的域）
- [ ] `ksud feature list` 的实际输出（判断 `kernel_umount` 等特性对挂载可见性的影响）
- [ ] SukiSU 的 `extension`（umount paths / KPM）是否会隐藏我们的挂载（预期不会，但需确认）
- [ ] Android 15 的 16KB 页设备迁移对 ELF 的要求（本机 4K，暂不影响）
