# 验证记录（证据，不是设计）

- 设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 日期：2026-10-26
- 用途：只记录“实际测过什么”。未测项不因为设计写了就当已支持。
- 当前设计：`design-v1.5.md`；决策：`decisions.md`。

> 事故说明：本记录中的部分宿主级 mount/overlay/debugfs/tracefs 测试与 2026-10-26 系统崩溃时间关联；不要再重复。详见 `incident.md`。本文件只保留结果，不作为操作指南。

## 1. SELinux / SukiSU

```sh
su -c 'id; cat /proc/self/attr/current; cat /sys/fs/selinux/enforce'
# uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0
# u:r:ksu:s0
# 1

/system/bin/getenforce        # app: Permission denied
su -c 'getenforce'            # 观察不稳定：一次 Disabled，一次 Enforcing
su -c '/data/adb/ksud feature list'
# su_compat        ENABLED
# kernel_umount    ENABLED
# selinux_hide     ENABLED
# mount_hide       ENABLED
# avc_spoof        ENABLED
# sulog / adb_root DISABLED
```

结论：

- 内核实际 Enforcing（`enforce=1`）；`getenforce` 不能作为唯一判据。
- `selinux_hide` 对 UID≥10000 返回 fake status page；普通 App 读 `enforce` 被拒。
- `u:r:ksu:s0` 是 permissive allow-all 域；全局 Enforcing 不约束 chroot。
- KSU 隐藏特性必须保持开启。

## 2. namespace

```sh
zcat /proc/config.gz | grep -E 'CONFIG_(UTS_NS|IPC_NS|PID_NS|USER_NS|NET_NS)'
# CONFIG_UTS_NS=y
# CONFIG_NET_NS=y
# CONFIG_PID_NS is not set
# CONFIG_USER_NS is not set
# （没有 CONFIG_IPC_NS）

busybox unshare --uts true                              # rc=0
busybox unshare --ipc true                              # EINVAL
busybox unshare --mount --propagation private -m true   # rc=0
```

结论：只有 mount + UTS 可用；不能设计 PID/USER/IPC ns 隔离；`unshare --ipc` 必须删除。

## 3. 设备节点/内核接口

```sh
ls -ld /dev/ashmem /dev/binder /dev/hwbinder /dev/vndbinder /dev/binderfs /dev/block
# /dev/ashmem char 10,125
# /dev/binder -> /dev/binderfs/binder
# /dev/hwbinder -> /dev/binderfs/hwbinder
# /dev/vndbinder -> /dev/binderfs/vndbinder
# /dev/binderfs dir
# /dev/block dir

zcat /proc/config.gz | grep -E 'CONFIG_(ASHMEM|ANDROID_BINDER_IPC|DEBUG_FS|OVERLAY_FS|SECURITY_SELINUX)='
# 全部 =y
```

结论：binder/ashmem/binderfs/block、debugfs/overlayfs/SELinux 都在内核里；这是“存在”，不是“已在最终挂载下验证”。

## 4. debugfs / tracefs

```sh
mkdir -p /data/local/tmp/dbgtest
mount -t debugfs debugfs /data/local/tmp/dbgtest     # rc=0
mkdir -p /data/local/tmp/trtest
mount -t tracefs tracefs /data/local/tmp/trtest      # rc=0
grep ' /sys/kernel/tracing ' /proc/self/mountinfo    # tracefs 已挂载
grep ' /sys/kernel/debug ' /proc/self/mountinfo      # 默认未挂载
```

结论：可挂；`/sys/kernel/debug` 默认未挂，启动时需显式挂载。**这是宿主级测试，已禁止重复。**

## 5. Android CLI

```sh
/system/bin/am                                      # help
/system/bin/pm list packages | head -3              # 正常
/system/bin/settings get global adb_enabled          # 0
/system/bin/input                                   # Usage
/system/bin/dumpsys                                 # 服务列表
/system/bin/content                                 # usage
/system/bin/appops                                  # help
/system/bin/ime                                     # help
/system/bin/wm                                      # help
/system/bin/svc                                     # help
/system/bin/cmd                                     # usage
/system/bin/screencap -p /data/local/tmp/x.png      # rc=0, 560KB
/system/bin/screenrecord --time-limit 1 ...         # rc=0, 479KB
/system/bin/cmd notification help                   # rc=0
```

`uiautomator`：

```sh
/system/bin/uiautomator                             # 只有 help / runtest，没有 dump
/system/bin/uiautomator dump /data/local/tmp/ui.xml # rc=137, Killed, 无文件
cmd -l | grep uiautomator                           # 无此 service
```

结论：命令存在/只读验证通过；`uiautomator dump` 本机不可用；写操作未验证。

## 6. overlay / system-rw

- `/data`（f2fs）做 overlay upper/work：`EINVAL`，dmesg 提示 case-insensitive capable filesystem not supported。
- 64MB ext4 镜像上的 upper/work：overlay 到临时 target 成功；`touch` 成功。
- **实际 `/system` 挂载点、AVB/verity、APEX/linker、厂商分区未验证**。

结论：`system-rw` 默认 off；upper/work 必须在 ext4 rootfs 内；只能用 private ns；不能把临时 target 成功当生产可用。

## 7. losetup / e2fsprogs

```sh
/system/bin/losetup --help
# Toybox: -S SIZE = sizelimit，不是 sector-size；无 --sector-size
/data/data/com.termux/files/usr/glibc/bin/losetup --version
# util-linux 2.42.1（可用于 --sector-size 4096 --direct-io=off）
/system/bin/mke2fs --help    # 可用，支持 -b/-d
/system/bin/e2fsck --help    # 可用
/system/bin/resize2fs --help # 可用
```

结论：模块必须自带 util-linux `losetup`；toybox `losetup` 不满足；e2fsprogs 来源需固定。

## 8. 目录权限

```sh
mkdir -p /data/media/0/ubuntu-rootfs
chmod 700 /data/media/0/ubuntu-rootfs
touch /data/media/0/ubuntu-rootfs/.nomedia
ls -ld /data/media/0/ubuntu-rootfs     # drwx------ root media_rw
# Termux:
ls -ld /sdcard/ubuntu-rootfs           # drwx------ root media_rw
ls /sdcard/ubuntu-rootfs               # Permission denied
```

结论：0700 + `.nomedia` 对普通 App 隐藏有效；root 域仍可见。

## 9. 能力矩阵（当前）

| 能力 | 状态 | 备注 |
|---|---|---|
| `am` help | present/help-only | 未做 `am start` 写验证 |
| `pm list packages` | read-only-verified | `pm install/uninstall/clear` 未验证 |
| `settings get` | read-only-verified | `settings put` 未验证 |
| `input` | present/help-only | 实际点按/文本未验证 |
| `screencap` | write-verified（文件） | 需最终挂载下复测 |
| `screenrecord` | write-verified（文件） | 需最终挂载下复测 |
| `dumpsys/content/appops/ime/wm/svc/cmd` | help-only | 未做写操作 |
| `uiautomator dump` | unavailable | rc=137；只承诺 `runtest`（需 jar） |
| `uiautomator runtest` | 未验证 | 需测试 jar |
| binder/ashmem | present | 最终挂载下未验证 |
| `/dev/block` | present | 未做读写验证 |
| `/sys` rw | 设计 2B | 最终 chroot 下未验证 |
| full `/data` rbind | 未验证 | 需 P2 |
| `/storage` full rbind | 未验证 | 需 P2 |
| `/apex` 递归 ro | 未验证 | 需 `mount_setattr` 或等价 helper |
| 128GB 镜像流程 | 未验证 | 需 P2/P3 |
| DSH `approval-free` + hooks | 未验证 | 需 P3 |
| 银行/游戏/Play Integrity | 未验证 | 需 P5 检测矩阵 |

## 10. 未验证清单

- 真实 chroot 内 full `/data`/`/mnt`/`/storage`/`/sdcard` 的别名与写权限；
- `/system` overlay 实际挂载点；
- `am start`、`pm install`、`pm clear`、`settings put`、`pm grant`；
- `uiautomator runtest`；
- frida/stackplz、`/proc/kallsyms`、`/proc/<pid>/mem`；
- 128GB mke2fs/resize2fs/快照/恢复；
- DSH approval-free 与 hooks 的实际组合；
- App 级银行/游戏/Play Integrity 检测；
- `mount_setattr` 在本机内核上的可用性。

## 11. 本次纠正

| 旧写法 | 证据 | 当前写法 |
|---|---|---|
| `unshare --uts --ipc` | IPC_NS 未编译，EINVAL | 只 `--uts` |
| `uiautomator dump` 可用 | rc=137 | 只 `runtest`；UI 用宿主 agent |
| overlay upper 放 `/data` | f2fs EINVAL | 放 ext4 rootfs |
| toybox `losetup --sector-size` | 不支持 | 自带 util-linux |
| permissive 一定被 App 看见 | App 读 enforce 被拒；selinux_hide fake | 不保证可见，但仍是全局降级 |
| `getenforce` 判断 | 不稳定 | 读 `/sys/fs/selinux/enforce` |
| `/sys/kernel/debug` 默认可用 | 默认未挂 | 启动显式挂载 |
| `/run/dsh-rootfs` | `/run` 不存在 | `/tmp/dsh-rootfs` |

## 12. 代码状态（2026-10-26，静态/mock）

- `build/check.sh`：sh/mksh 语法、shellcheck、shfmt、C 语法、docs、mock 测试全过。
- `test_lifecycle.sh`：用 fake `mount/umount/losetup/unshare/chroot/nsenter` 验证
  start → status running → stop → stopped；**不证明真实内核挂载**。
- `test_snapshot.sh`：真实 `cp/sha256sum/tar` 的小文件快照/恢复/purge（非镜像）。
- `test_guard.sh`：hardguard 和 deny/ask hook 的本地输入/输出。
- `agent-app/build.sh`：已用 Termux aapt2/d8/apksigner 构建 `dsh-agent-0.1.0.apk`（21KB，v3 签名），未安装/未真机验证。
- 真实 `mount`/`loop`/`nsenter`/`chroot` 没有在本机执行。

因此当前只能声明“代码路径和静态门禁通过”，不能声明“chroot 已可用”。
