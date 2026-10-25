# W1 平台事实：SELinux / 存储 / 内核 / SukiSU

- 核验日期：2026-10-26 ｜ 原始文件：`web/raw/selinux-{plat,product,vendor}-seapp.txt`
- 等级：A（本机实测 + 系统文件原文）

## 1. SELinux 域映射：文本规则与实际观测不一致（重要）

`/system/etc/selinux/plat_seapp_contexts`（root 读取，47 行）里的应用域映射：

```
user=_app minTargetSdkVersion=34 domain=untrusted_app
user=_app minTargetSdkVersion=32 domain=untrusted_app_32
user=_app minTargetSdkVersion=30 domain=untrusted_app_30
user=_app minTargetSdkVersion=29 domain=untrusted_app_29
user=_app minTargetSdkVersion=28 domain=untrusted_app_27
user=_app minTargetSdkVersion=26 domain=untrusted_app_27
user=_app                          domain=untrusted_app_25   ← 兜底
```

**实测观测**：
- `com.termux`（`targetSdk=36`，2026-09-14 安装）→ **`u:r:untrusted_app_25:s0:c512,c768`**，其 `bash` 子进程同域；
- 其它第三方 App（如 `com.coloros.weather2`）→ `u:r:untrusted_app:s0:c69,c257,...`；
- 按文本规则，targetSdk=36 应落到 `untrusted_app`，与 Termux 的 `_25` **不符**。

**推断（未验证）**：实际生效的是 `/odm/etc/selinux/precompiled_sepolicy`（预编译策略），其 seapp 规则可能与本机文本文件不同；或该 App 的 seinfo 走了兜底。文本文件不能当作运行时事实。

**对设计的影响**：
1. 我们**不依赖 app 域**做任何事：chroot 在 `u:r:ksu:s0`（root）下运行，exec/挂载/文件访问都不受 app 域限制。
2. 但也**不能拿 Termux 的 `_25` 当通用结论**（例如"app 数据总能 exec"对别的 App 未必成立）。
3. 若要精确判断某个 App 的域，用 `ps -A -o LABEL,NAME | grep <pkg>`，不要查文本文件。

## 2. 存储与执行语义（实测）

| 挂载 | 选项 | 含义 |
|---|---|---|
| `/data` | f2fs `rw,lazytime,seclabel,nosuid,nodev,noatime` | 无 noexec；`nosuid` 影响 guest 内 setuid（如 sudo） |
| `/mnt/user/0/emulated` | fuse `rw,nosuid,nodev,noexec,allow_other` | `/sdcard` 的底层；noexec 在这里 |
| `/storage` | tmpfs `rw,seclabel,nosuid,nodev,noexec` | 同上 |

- 实测：`/sdcard` 上的文件即使 `chmod 755` 执行也 `Permission denied`；`/data/media/0`（raw）在 root 域可执行。
- 因此工作区必须走 **raw `/data/media/0` 绑定**，不能用 FUSE 的 `/sdcard`。

## 3. 内核与 root（摘要，详见 `android15-kernel-sukisu.md`）

- Android 15 / SDK 35 / 4KB 页 / GKI 6.6.66；`USER_NS`、`PID_NS`、`LANDLOCK` 未编译；`LOOP`、`EXT4`、`DM_SNAPSHOT`、`OVERLAY_FS` 已编译。
- `su` = SukiSU `4.2.1-r2:KernelSU`，上下文 `u:r:ksu:s0`；选项含 `-M/--mount-master`、`-Z/--context`、`--ksu-no-new-privs`。
- **实测**：默认 `su` 与 `su -M` 的挂载都会出现在调用方 mountinfo → 必须自己 `unshare -m` 并清理。
- `ksud` 子命令：`module / post-fs-data / services / boot-completed / late-load / soft-reboot / insmod / install / unload / uninstall / sepolicy / profile / feature / extension`。

## 4. 结论（对设计）

1. 域隔离不可用也不必要：我们的边界是 **ksu 域 + 规则 + 可逆性**。
2. 工作区用 raw media；rootfs 放 `/data/adb/chroot`（f2fs，可执行）。
3. 任何"App 域能力"的说法都必须按 App 实测，不能外推。

## 5. 未解问题

- [ ] 为什么 Termux 落在 `untrusted_app_25`（预编译策略？seinfo？）——不影响设计，但别再引用错误结论。
- [ ] `su -Z` 可切换到哪些域（能否给 chroot 更受限的域）。
- [ ] `ksud feature list` 的实际输出（`kernel_umount` 等对挂载可见性的影响）。