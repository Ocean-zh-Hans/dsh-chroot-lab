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
- `console-app/build.sh`：已构建 `chroot-console-0.1.0.apk`（原生状态/启停/日志/快照/开关 + WebView）；模块构建时同时放入 `console.apk` 和 `system/priv-app/ChrootConsole/ChrootConsole.apk`，自动安装/system overlay 未验证。
- 真实 `mount`/`loop`/`nsenter`/`chroot` 没有在本机执行。

因此当前只能声明“代码路径和静态门禁通过”，不能声明“chroot 已可用”。

## 13. 交接

真实 P2/P3/P4/P5 验收已交接给 PC AI + 备用机，步骤和回传清单见
`docs/handoff.md`。本文件不把未执行的验证写成通过。

## 14. 评审修复（2026-10-26）

- `/metadata` 加入 rbind 列表，6B 保护现在真的覆盖它。
- `/opt/dsh`、`/opt/dsh-control` 在 bind 后 remount ro。
- `dsh-nsinit` 的 TERM/wait 竞态修复：信号中断 wait 后会继续等待 s6 子进程，
  不再提前清空 `CHILD_PID`。
- 新增 `dsh-ctl recover --apply`，处理 holder 已死但 loop/mount/stale 文件残留。
- `dsh-run` 通过 `--patch` 挂载 `dsh-hooks-claude-code`；patch 已用本机
  `--dump-config` 验证。
- 工具链改为 Android toybox fallback；`dsh-mount-ro-recursive` 增加
  `--make-rprivate`/`--rbind`/`--bind`/`--remount-ro*`。
- `config.conf` 解析去掉 `eval`；`resolv.conf`/`hosts` 在 stop 时恢复。
- DSH/s6 日志重定向不再互相截断；Console 使用绝对 su 候选路径。

## 15. 接手审核修正与新增证据（2026-10-26）

本节以接手审核后的实现为准；§12–14 保留为早期实现/构建的历史记录，不能用来覆盖本节。

### 15.1 已确认的代码问题与修正

- 启动失败可能留下 permissive、loop 或 namespace：改为统一失败清理，并复核最终 SELinux Enforcing。
- stop 忽略 umount/detach/SELinux 恢复错误：现在失败返回非零并保留 `recovery-required`，不再伪报 stopped。
- holder 死后的清理误在宿主 namespace 操作：改为通过已验证的 namespace 成员执行受限恢复脚本；`recover --apply` 可显式强制恢复卡住的 holder。
- last-good 只是当前镜像路径：改为经校验的快照名，支持 `snapshot create --last-good` 和 `snapshot promote`。
- 快照缺 hash、缺 manifest、损坏 state、非原子恢复：现在必须同时校验镜像/state，临时文件校验后替换，state 中途失败会回滚镜像和 state。
- `/system` 普通 bind 被误认为 overlay、DSH 权限预设未配置、ttyd 没有认证、agent 缺 `INTERNET`：均已修正。
- 控制台默认装入 system/priv-app 与“可选/不改全局视图”冲突：默认不再包含或自动安装 App；`--with-console` 只附安装包。
- 宿主 cc 可能生成非 Android helper、Node/依赖下载无完整校验、zip 权限受 Windows/MSYS 影响：分别改为 NDK 目标校验、Node 官方 SHA256 + npm lockfile、zip 权限规范化后读回验证。

### 15.2 本轮实际执行的检查

| 检查 | 结果与证据边界 |
|---|---|
| `./build/check.sh` | 已通过 sh/mksh 解析、ShellCheck、shfmt、C helper 构建校验、文档检查、Java 行为测试和 mock/失败注入测试。mock 不证明真实内核挂载。 |
| `./build/build-module.sh` | 模块 zip 已重建；必需文件、执行权限、ARM64 helper、SHA256、无 `install.sh`、无全局 App overlay 均通过。 |
| Android helper | 实际由 NDK r28c 构建：ARM64、Android 24、`/system/bin/linker64`；`tools/SHA256SUMS` 校验通过。 |
| `tests/test_snapshot.sh` / `test_image.sh` | 使用真实小文件、tar、SHA256 和状态目录验证创建、恢复、回滚、校验拒绝和 purge 占用检查；e2fsck 仍是 mock，未证明 128GB ext4 流程。 |
| `tests/test_lifecycle.sh` / `test_mount_cleanup.sh` | 覆盖 detach 失败保留恢复信息、SELinux 恢复、mount 失败与正常启停；未执行真实 mount/loop/unshare/chroot。 |
| DSH 0.2.0-rc.2 | `--dump-config` 已接受权限/hooks 配置；`approval-free` 与 `approval-guard` 均在 PC 隔离目录实际启动并生成 loopback URL。未调用模型账号，未执行真实模型会话或手机端 hooks。 |
| `dsh-agent-0.1.0.apk` | 已重新编译、签名；未安装到手机。 |
| `chroot-console-0.1.0.apk` | 已重新编译、签名并通过 zipalign；未安装到手机。 |
| 只读设备核对 | 当时通过 adb 只读确认 PJZ110、Android 15、ARM64 及 Android 环境变量；该阶段没有安装、推送、挂载、apply 或修改 SELinux。后续授权安装记录见 §19–20。 |

### 15.3 仍未验证

- arm64 Ubuntu rootfs 和 128GB 镜像的构建、部署、挂载、扩容与冷快照。
- 真实 private namespace、full `/data`/`/storage`/`/dev`/`/sys`、递归只读和 stop 零残留。
- 真实 Node/node-pty、DSH 手机端 hooks、真实模型会话和 Android CLI 写操作。
- last-good 的实际开机回滚、反复启动、断电恢复、watchdog 和 rescue。
- agent 的无障碍/通知/剪贴板/socket，Console 的 su 路径、授权与 WebView。
- 银行、游戏、Play Integrity 和“应用层隐藏性不降低”。这些不能由静态检查或 DSH 启动推断。

### 15.4 使用边界

- 本节记录当时的限制：当时连接的是主力机，只允许只读核对。后续已按授权在主力机安装激活（§20）；新改动仍须先识别设备，并在测试机/arm64 VM 做有限写入验收。
- guard 改变的是新会话默认权限预设，重启 DSH/chroot 后生效；已有会话用 `/permission` 切换。
- ttyd 默认关闭；显式启用时必须有随机 0600 凭据和 loopback 认证。
- Console/agent 都是可见的可选 App，默认模块不安装它们；可见性影响必须单独测试。
- full chroot 的 root 不是安全沙箱；hardguard 只是命令路径护栏，不能约束直接 syscall、块设备或其他 root 入口。

## 16. 备用机稳定性实测（2026-10-26）

设备：PJE110 / Android 15 / kernel 5.15.149 / Magisk。它与目标主力机 PJZ110 的 kernel、root 实现和环境不同，本节只说明该备用机的有限结果，**不是完整 P2 验收，也不外推到主力机**。

### 16.1 实测目的与边界

最初只检查“执行受限挂载操作是否会导致系统死机”，没有安装模块、启动完整 chroot、启动 DSH、写 Android 分区、改 SELinux/prop，或进行银行/游戏检测。

使用项目内 NDK helper 和从 Termux 官方仓库取得并校验 SHA256 的临时 e2fsprogs。自定义 `mke2fs.conf` 读取失败后，使用已验证的内置默认配置创建 **16MB** ext4，`e2fsck` 返回 clean。没有使用 128GB 镜像。

### 16.2 已通过的稳定性 smoke

1. 在同一个 root shell 内执行 `unshare -m`，成功创建 private mount namespace。
2. `dsh-mount-ro-recursive --make-rprivate /` 成功。
3. 在 private namespace 内挂载 tmpfs，再在 `sys/fs/selinux` 挂载独立 tmpfs 遮罩；没有对宿主 selinuxfs 做 bind/remount。
4. 递归只读 helper 生效，向 tmpfs 和遮罩写测试文件均失败。
5. 逆序卸载成功，最终 `result=0`。宿主 mountinfo 无该测试挂载，临时目录已清理。
6. 复核 `/sys/fs/selinux/enforce=1`、`sys.boot_completed=1`、`ro.debuggable`/`ro.secure` 前后未变，`system_server` 存活（PID 19755）。

### 16.3 当时的限制与后续纠正

- 当时的复合 `su -c` 出现混合身份，导致 UTS、loop 和存储路径的误判；后续完整 root 脚本证明 `unshare -m -u` 可用、`losetup -f` 能返回空闲 loop、`/data/media/0` 可访问。不能把早期错误结论当作内核限制。
- 16MB 镜像在 `/data/local/tmp` 挂载出现 I/O error；改放 `/data/media/0` 后 loop/ext4 挂载、写入、只读重挂载和清理通过。没有 detach 或复用系统 APEX loop。
- 当时没有完整 chroot、`dsh-ctl start --apply`、DSH、真实 128GB 镜像、Android 写操作或应用层隐藏性验证。tmpfs 通过不能证明完整流程不会死机。

## 17. 备用机稳定性复核（2026-10-27）

设备：`250da58a` / PJE110 / Android 15 / ARM64 / kernel 5.15.149 / Magisk 31000，SELinux Enforcing。只操作这台备用机，未在主力机执行安装、写入、mount、loop 或 apply。**本节仍不是完整 P2/P3 验收。**

### 17.1 环境、镜像与工具

- 完整 root 环境下 `unshare -m -u` 成功。16MB ext4 在 `/data/media/0` 的 loop 挂载、写入回读、只读重挂载、卸载和 loop detach 通过；`/data/local/tmp` 的镜像挂载 I/O error 未用于后续启动。
- 使用官方 Ubuntu Base 24.04.5 ARM64，并对照官方 SHA256 构建 **512MB 稀疏 ext4 测试镜像**，实际占用约 82MB。它只是稳定性测试用最小 Ubuntu，**不是 arm64 VM 烘焙的交付 rootfs，不含 Node、s6 或 DSH**。
- 固定版本 GNU cp 9.12 及运行库来自 Termux 官方包，校验包 SHA256、ARM64 ELF、动态库依赖和许可证。设备端逐项 `sha256sum -c` 通过，`cp --version` 实际运行成功。未使用不兼容的 toybox `cp`/`dd` 代替。
- `dsh-ctl image install` 实际安装成功，源/目标镜像 SHA256 一致、尺寸一致且保持稀疏。严格 `doctor --for-apply` 为 **37 pass / 3 warn / 0 fail**。

### 17.2 真实通过的稳定性场景

1. **最小 Ubuntu rescue**：实际执行 Ubuntu 24.04.5 ARM64 命令，mount/UTS namespace 与宿主不同；rootfs 只读写入被拒绝，未绑定 Android `/data`/`/storage`。退出后 `rescue_rc=0`，loop、挂载和 holder 清零。
2. **真实 `/apex` 递归清理**：private namespace 内 rbind、递归只读和卸载通过，包括 `dex2oat64`、`dex2oat32`、`apex-info-list.xml` 等文件级 bind mount。没有 detach 宿主 APEX loop，也没有修改宿主挂载。
3. **full-mount 失败清理**：使用真实 `dsh-ctl start --apply`，覆盖完整 Android `/data`、`/dev`、`/sys` 视图、APEX/linkerconfig 处理、镜像与控制面遮罩。最小镜像没有 s6，所以启动按预期失败并返回 4；这不是 DSH 启动成功。清理后无 `umount failed`、无 degraded、无 loop、无宿主挂载、无临时卸载清单，状态最终 stopped，`system_server=19755`、SELinux=1，测试工作区已清理。
4. **恢复边界**：实测发现 namespace inode 可复用，已改为只处理 mountinfo 仍含本 rootfs 的实例进程；无关 Android 应用未被结束。清理竞态、zombie 误判和 rescue 后台 stdin 丢失均有对应回归测试。

### 17.3 本次修正的实现

- namespace 枚举改为一次 `ls` + awk，并按 rootfs mountinfo 过滤，避免逐个启动 `readlink` 和 namespace 复用误伤。
- 递归卸载以 mountinfo 识别目录和文件挂载，逆序普通 `umount`，不把 lazy unmount 当作成功；recover 使用同一实现。
- holder 开始清理时记录 `cleanup.status`，忽略重复 TERM/INT，stop 有界等待；zombie 不算存活，临时卸载清单在成功恢复后删除。
- rescue 保存并恢复后台 bash 的标准输入，已验证真实 guest 命令输出。
- sparse copy 只接受经过校验的 GNU `cp --sparse=always`；复制后检查尺寸和内容哈希。构建产物包含所需运行库、许可证和工具清单。

### 17.4 未完成与警告

- **没有注册 Magisk 模块、没有安装 APK、没有启用自启、没有重启验收。**`customize.sh` 只在备用机隔离目录运行。
- 没有真实 128GB 镜像、完整 s6/Node/DSH 启动、真实模型会话、20 次启停、真实快照/开机回滚、Android 写操作或应用层隐藏性验证。full-mount 通过不能代替这些项目。
- `doctor` 的 3 个警告是 Magisk 域与预期 KSU 不同、toybox `losetup` 无 `--sector-size`、无 KSU 隐藏特性检查；它们不是隐藏性或完整功能通过证明。
- 本次专用临时镜像、脚本、state 和测试目录已清理；电脑保留了验证日志。结果只适用于该备用机，**不能外推主力机，也不能据此承诺不会在所有场景死机**。

## 18. 备用机限定验收（2026-10-27）

本节为当前实测记录；§15–17 是较早阶段的历史记录，不能用来覆盖本节。设备为
`250da58a / PJE110`，Android 15、kernel 5.15.149、Magisk 31000，SELinux Enforcing。
**本节只记录当时的备用机阶段，没有把这些结果外推到主力机 PJZ110；当时未在主力机安装或启动。后续主力机已按授权安装激活，见 §19–20。**

### 18.1 构建与真实运行时

- ARM64 Ubuntu 24.04 rootfs 已在 ARM64 VM 构建并执行 `verify-rootfs`；包含 Node
  `v24.18.0`、DSH `0.2.0-rc.2`、s6、Go、Python、git、jq、gcc 和真实 node-pty。
  `out/rootfs.tar.gz` 的 SHA256 为
  `639ca7747a2a9a576842bea7d9cd86111a7155b0c0d241b05fb1f08f40a39fac`。
- 备用机实际部署 **128GiB 稀疏 ext4**（表观 `137438953472` 字节），镜像 SHA256/e2fsck
  已校验。真机 Go 为 `go1.22.2 linux/arm64`，已在工作区离线编译并运行程序。
- 正式 ZIP 安装并激活后，严格 `doctor --for-apply` 为 **37 pass / 2 warn / 0 fail**。
  警告是 toybox `losetup` 没有 `--sector-size`，以及该 Magisk 环境无法验证 KSU 特性。
- DSH HTTP 返回 200；重启后还实际启动 node-pty 子进程，并验证 Go、Python、s6、
  `pm` 和 `am instrument`。guest 的 `BOOTCLASSPATH`/`DEX2OATBOOTCLASSPATH` 从运行中的
  `system_server` 提取并逐项校验 jar 路径，未修改全局 SELinux/prop。

### 18.2 生命周期、快照与真实开机

- 完整 `start/stop` **20/20** 成功；holder、loop、宿主挂载和 rootfs namespace 残留均为 0。
  运行中创建快照被拒绝；停止阶段的递归卸载是实际耗时来源，不是 s6 假死。
- 真实镜像/state 快照创建、promote、恢复通过；测试修改在恢复后消失。隔离测试副本的
  开机 last-good 恢复通过，连续三次失败后的自动启动抑制通过。正式镜像未用于破坏测试。
- 备用机完成一次真实重启：正式模块激活、自动启动和 watchdog daemon/`--once` 均通过，
  `boot-fails=0`。宿主 mountinfo 无 chroot 挂载，SELinux=1、`ro.debuggable=0`、`ro.secure=1`。
  `sys.user.0.ce_available=true` 按布尔真值正确处理。

### 18.3 Android 控制、Agent、Console 与有限探针

- `am`/`input`/`settings` 产生实际界面变化；guest 内 `pm install -r` 对测试 APK 返回
  `Success`。私有 namespace 内 `/system` overlay 可写/移除，宿主未出现测试文件；
  APEX/linkerconfig 和 `/metadata`、persist、qmcs 的递归只读保护已验证。
- 最终 Agent 验证了 token/socket、首页监听状态、点击、滑动、可见可编辑节点的文本输入
  与回读、通知读取/关闭，以及包可见性和不支持操作的明确负例。通知来自 ADB shell 注入的
  测试标签，**不是其他应用的真实验证码**。剪贴板因已有内容而跳过写入和清除，未覆盖原内容。
- 普通 `untrusted_app` 探针确认：模块目录、镜像、Agent token 不可读，chroot 挂载不可见，
  测试的存储 root 标记不存在，`ro.debuggable=0`、`ro.secure=1`。探针已卸载。
- Console 必须在 **SUList（root 管理器）**主动授权后彻底重启，才获得 root。已实测其状态、
  Stop/Start、停止后零残留、DSH HTTP 和 WebView 真实页面。未修改全局默认拒绝、Shamiko
  或其他应用的授权，也没有给 Agent 授予 root。

### 18.4 仍未验证/限制

- 主力机兼容性、所有场景“不会死机”、银行/游戏/Play Integrity、真实验证码流程。
- 真实模型账号会话；DSH Web 启动和 HTTP 成功不等于模型会话已验证。
- 剪贴板写入/读取/清除往返（为保护已有内容而跳过）；不同厂商 UI 的通用自动化能力。
- 真实 purge/uninstall、断电恢复、KSU 特有隐藏特性的验证。两个 APK 都是 debug 签名，
  不是发布签名。普通 App 探针也不构成“完美隐藏”保证。

### 18.5 主要证据

本地脱敏日志包括 `build/.tools/lifecycle-20-v3-job.log`、`snapshot-restore-job.log`、
`boot-rollback-v2-job.log`、`post-boot-verify-v4-job.log`、`agent-verify-v14-job.log`、
`console-verify-host-job.log`、`probe-token-final-job.log`、`formal-module-stage-v2-job.log`、
`final-host-check.log`、`final-build-module-v2.log`。这些证据不包含完整 DSH URL、token 或用户剪贴板内容。

### 18.6 收尾状态

正式模块、真实镜像、`before-test` 镜像/state 快照和两个可选 App 保留。chroot、watchdog
和 autostart 已停止，holder/loop/rootfs namespace 为零，SELinux 保持 Enforcing。
Console 的测试 root 授权已撤销，Agent 的无障碍/通知/AppOps 已恢复原值，临时 probe
及本次临时目录已清理；未修改全局默认拒绝、Shamiko 或其他应用的授权。收尾日志为
`build/.tools/final-device-cleanup-v3-job.log`。

## 19. 主力机审核修复与一次真实手动验收（2026-10-28）

本节只适用于序列号 `3B1F6KE5MS11JHR8` 的 PJZ110 / Android 15 / SukiSU。备用机历史记录不覆盖本节。电话进程此前的反复崩溃及重启后的恢复另见 `incident.md` 第 8 节；那起事件没有用于证明或排除本模块的根因。

### 19.1 审核修正与构建

- `doctor` 同时接受 SukiSU 的状态在前和功能名在前输出，拒绝对应功能的任何 `disabled`。新增实际 `losetup -j` 检查；`start` 在 fsck 前、fsck 后和 attach 前都证明镜像无 loop，查询失败不再视为空闲。
- loop 别名按内核 loop 名/设备号识别，不再只比较路径字符串。`purge` 先 stop/disable watchdog，再在同一把锁内 stop、复核占用并删除；owner 校验 PID/starttime/命令路径，显式安装才重新启用。`uninstall.sh` 也先停用 watchdog。
- DSH 使用真实 loopback HTTP 健康检查和带时间戳的 heartbeat，不再把 URL 文件存在当作 running。启动超时或连续健康失败会有界停止登记的 web 子进程并由 s6 重启；watcher 的失败状态不会被正常退出覆盖，恢复后可回到 running。
- Console 已中文化并处理状态栏/导航栏 Insets；“清理残留”明确对应 `recover --apply`，不是快照恢复。进程输出独立读取且有超时，状态命令 15 秒、生命周期命令 180 秒，超时提示后台可能仍在执行，不能视为停止成功。
- 两轮完整主机检查及打包均通过：`audit-final-check.log`、`audit-final-build.log`、`audit-final-console.log`。模块 ZIP 为 `dist/dsh_chroot-0.2.0.zip`，SHA256 `3a8e434f875b4d6cf35f4c3f423febdc036681071e14c4005589114ea1404a8e`，大小 9073300 字节。Console APK SHA256 为 `b65d6bc304988e6ecc84b65318bcba9e80f5b4b7c3eff821ad22fff203ea4809`，仍是 debug 签名。

### 19.2 前置实测与运行结果

- 16MB 私有 namespace smoke：private propagation、tmpfs 递归只读、ext4 写入/回读、只读重挂和新 loop helper detach 均通过。宿主测试挂载为 0、SukiSU 功能未变、测试文件清理成功。此次 `loop_alias_detected=0`，因此不把它单独当成别名实测证明；别名另有内核身份实现和 mock 负例。证据：`dsh-main-smoke-final.log`。
- 正式镜像在 holder/loop 均为零时冷态 fsck 通过，本轮哈希基线为 `14f593aba8e9538134da047aa75efe641ec81bdc9fbe6a450e6e0355a81233b2`。首次合法启动和初始化已改变 ext4 元数据/内容，因此没有沿用初始哈希，也没有覆盖镜像。证据：`dsh-main-image-verify-final.log`。
- 实际执行目录优先 `modules_update`，并逐文件核对 doctor、dsh-run、healthcheck 的 SHA256，避免误用已激活目录中的旧代码。严格 doctor 为 **44 pass / 2 warn / 0 fail**；警告是 mountpoint 尚未创建，以及 toybox losetup 没有 `--sector-size`。
- 最终一次完整手动启动和停止返回 `main_deploy_rc=0`。Node `v24.18.0`、真实 node-pty、Go `go1.22.2 linux/arm64`、Python 3.12.3、s6 up、`pm path android` 均通过。DSH HTTP 为 200、正文 34846 字节、含 DSH 标记，真实健康检查和 heartbeat 也通过。
- `BOOTCLASSPATH` 47 项、`DEX2OATBOOTCLASSPATH` 20 项均有效。guest 的 `enforce=1`，selinuxfs 为独立只读挂载；实际 loop 路径在 guest 不可见，没有进行任何块设备写入。`ksud feature list` 只读查询成功，未桥接 magiskd。
- `dsh --profile web --dump-config` 含 `dsh-audit-hooks` 和插件名；实际 `hooks.json` 含 `SessionStart`、`UserPromptSubmit`、`PostToolUse`，没有 `PreToolUse`。没有调用真实模型账号或读取原 DSH 会话凭据。
- 历史日志曾记录 `main_manual_validation=ok` 和 `main_deploy_rc=0`；原始 `dsh-main-deploy-final.log` 后来发现含 token，已按秘密清理要求删除，不能作为当前可分发证据。当前可复核的安装后结果为 `dsh-main-install-check-post.out` 的 44 pass / 2 warn / 0 fail；不恢复含秘密日志。

### 19.3 收尾状态和仍未验证

- 停止后：宿主 rootfs 挂载 0、记录的 loop 为 none、holder 0、rootfs namespace 进程 0，heartbeat 已删除，SELinux=1，SukiSU 功能前后不变。`autostart=0`、guard=0、ttyd=off。没有启用开机自启，没有再次重启手机。
- `/data/data`、`/data/user`、`/data/user_de` 在主力机仍有厂商 tmpfs profile 覆盖，只有部分包的 bind。没有据此承诺全部 App data 可读写，也没有套用备用机的应用数量或解遮罩方案。全量 App data 开写、增量备份和 overlay 快照未在本轮实施；`/sdcard/DSH/workspace` 未挪入镜像。
- 控制台已安装更新并实际截图确认中文、顶部间距和“清理残留”。状态可读取，未修改 root 授权。超时返回 124 由主机 Java 测试验证，未制造真实 root 授权弹窗卡死。
- 本节的 `modules_update` staged 状态已被 §20 的最终安装激活取代。保留最终镜像、模块、控制台和脱敏日志；没有执行真实 purge/uninstall 来验证删除。
- 未验证真实模型会话、真实验证码、银行/游戏/Play Integrity、release 签名、主力机连续 20 次启停、真实开机自启、断电恢复及所有场景“不会死机”。路径遮罩、风险提示和 root 权限也不构成对恶意 root 或“完美隐藏”的保证。

## 20. 最终安装与激活（2026-10-28）

- 用户明确选择安装后，先执行只读安装前检查：最终 doctor、dsh-run、healthcheck 的哈希与新构建一致，autostart=0、guard=0、ttyd 无凭据，状态为 stopped，holder/loop/rootfs namespace 均为零，SELinux 保持 Enforcing。证据：`dsh-main-install-check-pre.out`。
- 随后按安装需要重启一次。等待 `sys.boot_completed=1` 和 `sys.user.0.ce_available=true`，再执行激活检查。新代码已位于 `/data/adb/modules/dsh_chroot`，不是仅有 `modules_update` 的 staged 状态。
- 激活后严格 doctor 仍为 **44 pass / 2 warn / 0 fail**；SukiSU 功能与重启前一致，autostart=0、guard=0、ttyd 无凭据，`DSH_SELINUXFS_MODE=ro`、`DSH_BLOCK_ACCESS=masked` 保持不变，state=stopped，holder/loop/rootfs namespace 仍为零。证据：`dsh-main-install-check-post.out`。
- 新模块没有在开机时自动启动 chroot；安装完成不等于正在运行。需要使用时仍通过控制台或 `dsh-ctl start --apply` 显式启动。
- 控制台 APK 已安装，中文、系统栏间距和“清理残留”均已实测，未修改 root 授权。激活后的 crash 缓冲区没有新的同类 TelephonyProvider/FATAL 记录，电话进程存活。
- §19 的 staged 状态已由本节取代；真实模型会话、验证码、银行/游戏/Play Integrity、连续启停、真实 purge/uninstall、断电恢复和 release 签名仍未验证。

## 21. DSH Agent 安装与授权（2026-10-28）

- `dev.dsh.agent` 已通过 adb 安装，使用重新构建并验证过签名/zipalign 的 debug APK，SHA256 为 `9115de167a2eaf83b6ea43e346beef0023d34932321067e46e540570e530ff0b`。
- `dev.dsh.agent/dev.dsh.agent.AgentService` 已加入无障碍服务，`dev.dsh.agent/dev.dsh.agent.NotificationRelay` 已加入通知使用权；按规范化组件名复核，其他服务保留。没有给 Agent root，也没有修改其他应用的 root 授权。
- 用户手动重新绑定无障碍服务后，Agent 首页实际显示 `Bridge: listening on 127.0.0.1:17321`。此前探测脚本缺少 Python/nc 是辅助工具环境问题，不是 Agent bridge 故障；没有为此安装额外依赖。
- token 没有输出或复制，未读取其他应用的通知内容、剪贴板或凭据。真实通知读取、UI 自动化、剪贴板往返和验证码流程仍未验证。

## 22. 深度审核修复与限定验收（2026-10-28）

### 22.1 主机与 App 已验证

- 从 Git 索引单独导出源码，`build/check.sh` 全部通过；随后模块 ZIP、Console APK、Agent APK 均完成干净构建。没有复制工作区的生成工具、缓存或秘密文件作为构建输入；SDK/NDK 和主机工具仍是外部前置条件。
- 回归覆盖：无 `pipefail` 的 loop 查询失败、原子/stale 锁、陌生 loop 不 detach、失败 purge 恢复 watchdog、快照回滚 hash、恢复目录清理、mountinfo 子树和空栈清理、恢复脚本失败、guest 超时、zombie/进程消失与 mountinfo 读取失败。
- Agent 在 PJE110 实测：`agent_ping=ok`、UI dump 可用、Activity 页面确认、剪贴板写入/读回/清除成功、测试通知关闭成功且最终不存在。通知只使用 `dsh-review` 测试标签；未读取其它应用通知，未输出 token、剪贴板正文或 UI 内容。该 ROM force-stop 后曾移除 Agent 无障碍组件，随后仅恢复该组件，其它服务未改。
- Console 在 PJE110 实测中文界面与“清理残留”。合并状态刷新时，`system-rw status` 返回 1 不再把有效 JSON 状态误报为 root 读取失败；最终显示“状态：运行中”。没有执行 purge 或修改 root 授权。

### 22.2 边界与未决事项

- 两个 APK 仍是 debug 签名，不是 release 签名。真机 Agent 测试不等于真实验证码、银行/游戏/Play Integrity 或任意 App 的通用自动化能力。
- 本地指定 VM key、credentials 和含 token 日志已删除且未纳入索引；外部 token/VM key 的撤销或轮换不能由本地删除证明，仍需账号/设备负责人处理。
- 测试机 crash 缓冲区存在历史 init SIGABRT，当前没有足够证据归因于模块，也不能据此证明设备完全健康；详见 `incident.md` 第 9 节。
- 本轮使用隔离目录和独立镜像验证新模块，不以旧正式实例的验收替代新代码验收。真实模型会话、真实验证码和长期稳定性不在这些测试的证明范围内。

## 23. 最终限定验收（2026-10-28）

### 23.1 测试机与 App

- 在 PJE110 的独立目录和独立 **4 GiB 稀疏 ext4** 中运行完整运行时，不替换或重启正式实例。初始严格 doctor 为 **46 pass / 3 warn / 0 fail**；恢复快照后的严格 doctor 为 **48 pass / 3 warn / 0 fail**。警告分别是测试路径例外、toybox losetup 无 `--sector-size`、Magisk 环境无 KSU 特性查询。
- rescue 实际运行 Python 3.12.3，rootfs 和 baked `/opt/dsh` 只读保护通过。DSH 完成真实启动、健康发布、停止和 watchdog 检查；冷态快照创建、恢复及恢复后再次启动均通过。`debugfs` 写入的测试标记在恢复后不存在，`snapshot_content_restored=ok`、`restart_after_restore=ok`、`device_review_smoke=ok`、`device_script_rc=0`。证据：`out/device-review-smoke-12.log`。
- 全进程扫描改为批量读取 mountinfo/stat，区分普通进程、zombie 和无 mount namespace 的内核线程；普通进程读取失败及指定 namespace 扫描失败均 fail closed，不得继续 detach。实测一次全进程检查约 33 秒。Console 状态刷新超时改为 90 秒，watchdog 仍顺序执行，不并发扫描。
- Console 实测 `console_running=1`、`console_token_masked=1`、`console_web_activity=ok`。状态、错误和命令输出中的 URL token 均隐藏，内部打开 DSH 页面仍使用完整地址。Agent 二进制未改变，沿用 §22 的实测结果。
- 最终测试目录、结果文件、loop 和宿主测试挂载均清零，SELinux=1，`system_server` 存活。crash 缓冲区仍只有已记录的历史 init SIGABRT，没有新增同类记录；这不构成对历史事件的原因判断。

### 23.2 当前交付物

```text
dist/dsh_chroot-0.2.1.zip
582229963d28f790b8be435fd31fc6b8dd7f8d18ecf76b16977e46212d13db18

console-app/dist/chroot-console-0.2.0.apk
fa41dafc7db3205305662422324348d5791c8d13f989491bf36c41fc60292b66

agent-app/dist/dsh-agent-0.2.0.apk
d70e2413b79fc5ad9ee9e5abba836aee0628327dff7cd127a2b2ee7b762607b4

agent-root-helper/build/dsh-root-helper.jar
fccd162a950f2a77ba25c6e90028c319208f92080f121b66f270066d6ca39c3f
```

两个 APK 仍是 debug 签名。必要源码、测试和执行位已纳入 Git 索引，没有提交或推送；生成 APK、ZIP、`out/` 和 `build/.tools` 未纳入索引。

### 23.3 仍需维护的边界

- 新版模块只完成隔离实例验收，**尚未替换或激活到正式目录**。正式升级需要单独维护窗口、已验证快照和升级后复核。
- 一次临时 Console UI 导出包含正式 DSH 地址凭据；明文导出文件已删除，但删除文件不能撤销凭据，当前正式 DSH token 仍需在维护窗口轮换。另发现 7 份旧日志含 token，已删除且本地日志扫描无匹配。此前 VM key/旧 token 的外部撤销或轮换仍未验证。
- 本轮不是新版 128 GiB 镜像、真实 purge/uninstall、断电恢复或长期稳定性验收；没有验证真实模型会话、真实验证码、银行/游戏/Play Integrity，也不承诺对所有 root 操作形成安全沙箱或完美隐藏。

### 23.4 最终干净索引构建

- 从最终 Git 索引重新导出源码，`build/check.sh` 输出 `all tests passed` 和 `all host checks passed`；随后模块、Console、Agent 均构建成功。证据：`out/final-clean-index-build3.log`。SDK/NDK 和主机工具仍是外部前置条件，没有从工作区复制生成工具、缓存或秘密文件作为源码输入。
- 干净构建与工作区构建的文件哈希因打包时间/签名而不同，不宣称字节级可复现。逐文件比较结果：模块 ZIP 所有文件内容一致；两个 APK 排除 `META-INF` 签名元数据后内容一致，`module_payload_differences=[]`、`console_payload_differences=[]`、`agent_payload_differences=[]`。§23.2 记录的是工作区最终交付物哈希。
- 脚本执行位和文本 LF 检查均无错误；索引不含 `out/`、`build/.tools/`、生成 ZIP/APK、credentials 或 VM key。

## 24. WebActivity 系统栏修复（2026-10-28）

- WebActivity 不再单独依赖旧 `getSystemWindowInset*`。Android 11/API 30 及以上使用 `WindowInsets.Type.systemBars() | displayCutout()`，API 29 使用兼容路径；没有删减或硬编码状态栏高度。padding 应用于 WebView 外层容器，主界面共用同一实现，不修改 DSH 网页内容。
- PJE110 / Android 15 实测：StatusBar 高度 `120`，WebView 边界 `[0,120][1080,2244]`；NavigationBar 从 `2244` 开始，内容没有顶到系统栏。主界面标题顶部仍为 `144 = 120 + 24`。实际进入 `dev.chroot.console.WebActivity` 成功。
- §23.2 的 Console APK 哈希已更新为本节修复后的构建；模块 ZIP 和 Agent APK 未改变。
- 从包含本节修改的 Git 索引干净导出源码，`build/check.sh` 再次输出 `all tests passed`、`all host checks passed`，Console APK 构建成功。工作区与干净构建的 APK 排除 `META-INF` 签名元数据后内容一致，`console_payload_differences=[]`。证据：`out/insets-clean-build.log`。

## 25. Android tar 状态归档链接路径修复（2026-10-28）

- 主力机创建快照时出现 `state archive contains unsafe paths`，镜像 e2fsck 为 clean。实测 Android toybox `tar -tf` 会把软链接显示为 `state/link -> /etc/passwd`，旧检查把链接目标误判为归档成员的绝对路径；不是镜像损坏，未删除状态或关闭安全检查。
- 校验改为读取 verbose 条目并移除元数据：软链接只检查成员名，硬链接分别校验成员名和链接目标；真实绝对路径、`../` 及伪装成链接名称的穿越路径仍拒绝。`tests/test_snapshot.sh` 新增正常软/硬链接和三组危险路径用例，已通过；PJE110 使用 Android tar 实测同一组归档，输出 `device_archive_validation=ok`。
- 修复后的模块 ZIP SHA256 为 `2022562a6984458a370d7b7911db176010913d55d66eb0b64c1997928dbc809f`。§23.2 已同步更新；Console 和 Agent APK 未改变。主力机当时断开连接，修复包的安装结果尚未确认。
- 从包含本节修复的 Git 索引干净导出源码，`build/check.sh` 输出 `all tests passed`、`all host checks passed`，模块 ZIP 构建成功。工作区与干净构建的模块所有包内文件一致，`module_payload_differences=[]`。证据：`out/archive-fix-clean-build.log`。

## 26. Console 屏幕方向（2026-10-28）

- 代码和 Manifest 原先没有写 `accelerometer_rotation` 或 `user_rotation`，但两个 Activity 都未限定方向，会交给系统或厂商策略处理。现 `MainActivity`、`WebActivity` 均显式设置 `android:screenOrientation="portrait"`，只限制本 App 为竖屏，不改变系统自动旋转开关；`build/check.sh` 增加 Manifest 回归。
- PJE110 实测安装、启动 Console 和打开 WebActivity 前后，`accelerometer_rotation=0`、`user_rotation=0` 均保持不变，`web_activity=ok`。§23.2 的 Console APK 哈希已更新为 `605e53b00219abf4fcf11b5e43442d0ad65dc76aaa4baede67e95206d6047da9`；模块和 Agent 未改变。
- 从包含本节修改的 Git 索引干净导出源码，`build/check.sh` 输出 `all tests passed`、`all host checks passed`，Console APK 构建成功。工作区与干净构建的 APK 排除 `META-INF` 签名元数据后内容一致，`console_payload_differences=[]`。证据：`out/portrait-clean-build.log`。

## 27. 并发、状态边界与 runtime 修复（2026-10-28）

- 控制器写操作和每次 watchdog 检查使用同一原生 lifecycle claim；嵌套 image/lifecycle claim 复用 active 标记，restart、system-rw、ttyd 和 watchdog 调 stop 不重复加锁。`dsh_lock_clear_stale` 在 quarantine 后复核 owner，candidate 按真实嵌套路径清理，避免误删新锁或留下目录。
- status/watchdog 按内核设备身份拒绝额外 loop 和查询失败，不自动 detach 陌生设备。status/doctor 同时报告 `watchdog.disabled` 与 `purge.in-progress`；purge 恢复 watchdog 时检查 `--enable` 和 marker 删除的返回值。
- URL、health、guard、ttyd.pass 移到独立 `state/runtime` 并 bind 给 guest；holder 的 PID/starttime/ns、loopdev、mount stack、cleanup.status 留在 `state/guest`，不再暴露给 guest root。state 快照、回滚和旧布局迁移均覆盖新目录。
- 宿主 runtime 只有 `current/bin/dsh` 可执行时才使用；否则要求 baked 同路径可执行。doctor 增加 control `dsh-run` 与 runtime executable 检查，不能只检查目录和 Python。
- 文档明确默认 `/dev/block=masked`、只有显式 full 才开放；修正已删除 token 日志的引用，并分开 PJZ110 与 PJE110 的 crash 样本，不把崩溃归因于模块或宣称完全健康。
- 已通过 `test_lock`、`test_status`、`test_watchdog`、`test_snapshot`、`test_doctor`、`test_guard`、`test_ttyd` 及各自新增的并发/负例。PJE110 原生互斥与嵌套调用实测 `device_lifecycle_claim=ok`。
- 真机发现 holder 会继承 claim fd，导致启动后 watchdog/stop 永久冲突；helper 改为发布完整 fd 列表，nsinit 关闭全部继承 fd，控制器和 image claim 仍按各自生命周期持锁。旧测试现场已验证停止和清理，随后完整 smoke 不再出现 `lifecycle.claim: Try again`。
- PJE110 独立 4 GiB 镜像的严格 doctor 为 **49 pass / 3 warn / 0 fail**，恢复快照后为 **51 pass / 3 warn / 0 fail**。DSH 启动、watchdog、真实快照内容恢复和恢复后启动均通过，`device_review_smoke=ok`、`device_script_rc=0`；测试目录、loop 和宿主测试挂载清零，SELinux=1。证据：`out/device-review-smoke-fds-fix.log`。
- 最终模块 ZIP SHA256 为 `8546dd0b054d86db9558f9d02f686fe793f7b8355ada757652440cf43a169a7b`；§23.2 已更新。Console 和 Agent APK 未改变。
- 从包含全部修复的 Git 索引干净导出源码，`build/check.sh` 输出 `all tests passed`、`all host checks passed`，模块、Console、Agent 均构建成功。工作区与干净构建逐文件比较均为 `module_payload_differences=[]`、`console_payload_differences=[]`、`agent_payload_differences=[]`（APK 排除 `META-INF` 签名元数据）。证据：`out/final-fds-clean-build.log`。
- `dsh-watchdog` 的直接 `--once`、`--stop`、`--enable` 也经过同一 lifecycle claim，不是绕过 claim 的第三方 writer；`test_watchdog.sh` 增加直接 `--enable` 的 claim 断言。`dsh_state_update` 的生产写者因此都被串行化，不能据此推断直接库调用也有独立 CAS。
- status 在镜像文件丢失但 holder 尚存活时改为 `degraded`，并给出 `note=image-missing`，不再仅凭健康 PID/URL/loop 显示 running；`test_status.sh` 覆盖删除和恢复镜像的状态变化。

## 28. Agent Compose 一次性迁移（2026-10-28）

- Agent 的 `MainActivity.java` 已删除，改为 Kotlin `ComponentActivity` + Compose；不保留新旧页面混合。`AgentService`、`AgentServer`、`NotificationRelay`、socket/token 和现有 JSON 协议保持不变；Console 不在本次迁移范围内，也未实施此前扩展功能清单。
- 固定 Gradle 8.11.1、AGP 8.7.3、Kotlin 2.0.21、Compose BOM 2024.09.03；`activity-compose` 明确固定为 1.8.2，因为它不由 Compose BOM 提供版本。Agent 完整依赖锁位于 `agent-app/gradle.lockfile`，构建依赖 SHA256 位于 `gradle/verification-metadata.xml`；校验没有关闭。补齐 `junit-bom-5.9.3.module` 时已与官方 Maven Central 文件核对哈希。
- Compose 构建、签名和 zipalign 检查通过。工作区 APK SHA256 为 `c7b661e20ddcfb5913f17b9132c008493603ba0bc23a0bf3a8fbe057f2bfb820`，大小 22290929 字节；§23.2 已更新。证据：`out/compose-build-verified.log`。
- PJE110（序列号 `250da58a`）升级安装后启动成功，原无障碍与通知授权保持。实际截图确认中文标题、桥接状态、token 路径、说明和两个设置按钮；标题顶部为 192，第二个按钮文字底部为 1424，屏幕为 1080x2376，没有被状态栏或导航栏遮挡。证据：`out/agent-compose-main.png`、`out/compose-device-verify.log`。
- 使用已有本机认证实测 `agent_ping=ok`，两个设置入口均真实打开，返回后桥接仍为 `settings_return_bridge=ok`；验证转发已移除。没有输出 token、通知正文、剪贴板或完整 UI 树，没有给 Agent root，SELinux 保持 Enforcing。
- 首次完整干净索引命令在 30 分钟后台上限处终止，不能记作完整成功；日志实际已输出 `all tests passed`，但 Agent 构建因工具目录 `cygpath` 生成 `/cygdrive/...` 及错误 wrapper jar 路径而失败。标准 Agent 构建入口已改为使用宿主 shell 的路径工具并规范化 `JAVA_HOME`，未跳过检查。
- 在同一干净源码目录应用上述构建修正后，Agent、模块 ZIP、Console 均构建成功；工作区与干净构建比较为 `module_payload_differences=[]`、`console_payload_differences=[]`、`agent_payload_differences=[]`（APK 排除 META-INF）。证据：`out/compose-final-clean-build.log`、`out/compose-baseline-build-resume-3.log`、`out/compose-baseline-payloads.log`。后续新控制面仍需重新做完整干净索引构建。

## 29. Android Agent 控制面与会话验收（2026-10-28）

本节记录 PJE110（`250da58a`、Android 15）上新增 Agent 的限定验收。SELinux 保持 Enforcing；结果不外推到未实测 ROM，也不替代 chroot 模块的生命周期验证。

### 29.1 实现与主机检查

- Agent 页面采用 Kotlin Compose，系统服务与协议使用 Java。普通 App 不申请 root；独立 `agent-root-helper` 通过 Android SDK `android.jar` 和 D8 生成只含 `classes.dex` 的 JAR，经反射连接 UiAutomation，不打包伪造系统类。root helper 使用 loopback `17322`、共用 token，启动脚本校验 PID/starttime/进程名；没有启用它的开机服务。
- 会话使用自有 POJO，支持默认会话、3 个 LRU、单调 nodeId、epoch、matchKey 歧义拒绝、受限 DSL 和 patch/全量回退。写操作重新 resolve → verify → act，不使用旧坐标；磁盘恢复快照只读。autosave 默认关闭、默认不保存正文，密码掩码，优先 Keystore AES-GCM；回退 gzip 时明确 `encrypted=false`。
- v2 客户端（已归档：`docs/archive/agent-v2/dsh_agent.py`）使用同一 socket 读取器并区分事件帧和控制确认。Windows socket 超时转换为 `TIMEOUT`，写请求不盲重试。无副作用自检、prep、录屏与确认 shell 使用各自有界预算，root helper 的 prep 也不再固定 15 秒；较长预算不改变写操作 `UNKNOWN` 语义。每个事件订阅者有独立有界队列，支持 `events.dropped` 和幂等退订。幂等写请求先登记执行中状态，超时结果标为 `UNKNOWN`。
- `AgentCoreTest: ok` 覆盖 selector、cron、路径安全和无副作用写方法分类；`PYTHONPATH=. python3 -u -m unittest tests.test_agent_client -v` 通过 6 个客户端测试，包括实际客户端 socket 的 prep 超时预算。较早完整主机检查 `out/full-host-final-3.log` 已输出 `all host checks passed`、`BUILD SUCCESSFUL`；后续源码的最终干净索引构建另记交付节，不能用旧日志替代。

### 29.2 实际设备结果

- `out/agent-system-device-15.log` 退出成功，末行 `agent_system_device=ok`，逐项通过：`backend_root_and_a11y`、`system_info`、`session_reuse_and_dsl`、`legacy_click_and_chinese_input`、`danger_confirmation`、`automation_restore`、`dnd_readback`、`windowing_boundary`、`package_user_task_queries`、`sensor_and_location_permission`、`event_subscription=battery`、`event_unsubscribe_idempotent`、`jobs_at_cron_event`、`autosave_disk_readonly`、`recording_diagnostics_readonly`、`token_rotation_and_helper_reconnect`、`screen_record`。录屏临时文件已清理，`/sdcard/.dsh/recordings` 最终为空。
- `out/agent-core-device-6.log` 验证同会话复用、截图/差异、flag、幂等点击、事件与作业、保存/加载。force-stop 后新进程旧 sessionId 返回 `SESSION_NOT_FOUND`；加载的 `source=disk/stale=true` 快照可查询，写操作返回 `SNAPSHOT_READONLY`；旧 epoch 写操作返回 `STALE_HANDLE`。
- `out/root-helper-device-2.log` 验证 root、UiAutomation 和 shell 状态可用，T0 dump 返回 27 个节点，root 截图 `via=uiautomation`，有界确认 shell 通过。
- `out/root-session-device-8.log` 在关闭 Agent 无障碍时验证 T0 会话、查询、matchKey/幂等点击、中文输入/清除和双指手势；随后只恢复 Agent 的无障碍组件，保留原有输入法服务。
- `activity.start`、URL、拨号和分享在普通启动受 BAL/ActivityNotFound 限制时可回退 root `am start`，只返回 `launchRequested`，不伪报页面已显示。`prep.restore` 对原本不存在的设置使用 delete，不写字符串 `null`。`ui.waitIdle` 在无障碍可用时使用 T1，并只跟踪当前前台窗口，不被其它窗口的高频事件持续阻塞。

### 29.3 明确未通过、未授权或未实测

- **文件访问仍需用户授权**。日志为 `file_access=requires_user_grant`，`fs.put/get/list/mkdir/rm/share.file` 的真实读写/分享未验收；没有使用 AppOps 静默授权。
- 未内置 OCR；`shot` 提供 PNG，由调用方 OCR。该 ROM 的 `task.splitScreen/pip` 返回 `UNAVAILABLE`，`ime.show` 没有可靠 shell-only 实现。`sms.send` 返回 `ROLE_REQUIRED`，不伪装成已发送。
- 普通 `location.get` 返回 `LOCATION_PERMISSION`，未静默授予位置权限。短信、联系人、日历 root 查询没有读取真实私人数据或验收；`location.mock` 未改真实位置。
- 未实际执行 reboot、卸载、清数据、撤销权限、修改 PIN/锁屏密码，只验证危险操作缺少 `confirm:"YES"` 时拒绝。`shell.exec` 是单独 root 信任边界，不代表普通 App 获得 shell。
- 两个 Android APK 仍是 debug 签名。未验证真实验证码、真实模型会话、银行、游戏、Play Integrity、完全隐藏或所有 ROM 的通用控制。

### 29.4 收尾与交付边界

- 测试后 root helper 已停止，未启用其开机服务；通知监听保留原服务与 Agent，测试机的无障碍恢复为 `com.baidu.input_oppo/com.baidu.acs.service.AcsService:dev.dsh.agent/dev.dsh.agent.AgentService`。没有修改主力机，未给普通 Agent App root。
- 交付以 **Git 索引/从索引生成的源码包** 为准，不以旧 `HEAD` 代替当前源码；没有 commit 或 push。`out/`、`build/.tools/`、生成 APK/JAR、凭据与 token 不进入索引或源码包。

## 30. Agent 最终边界复核（2026-10-28）

- 最终接口检查发现 `ui.query` 的 `scrollUntil` 曾直接调用无障碍实现，绕过顶层只读检查；现已统一通过 `AgentServer.dispatch`。`ime.show/hide/set` 也纳入写入分类。无副作用的 `AgentMethods` 供主机单元测试使用，DSL 内部读写分别调度，纯查询在只读模式下仍可用。
- 中间回归 `out/agent-system-device-final.log` 的只读负例已通过，但之后 `backend.selftest` 超过原 15 秒客户端预算；即时复测自检和截图成功，不能把该次运行写成全绿。最终客户端对无副作用自检使用 60 秒预算，helper 的 root ping/status 也有较短独立等待。
- `out/agent-system-device-delivery-2.log` 随后在 `prep.automation` 超时。该操作包含多次固定 `settings` 命令，15 秒不足以覆盖慢设备；App 到 helper 的 prep 等待改为 125 秒，Python 客户端 prep 为 130 秒，自检/录屏/确认 shell 同样使用与工作相匹配的有界预算。普通输入不延长，写操作仍不自动重试。
- 该次 prep 在服务端完成后才发生客户端超时，旧测试脚本未登记恢复需求，导致设置暂时停留在 automation 值。随后显式执行 `prep.restore`，9 项设置均按备份读回一致；不存在的设置正确区分 JSON `null` 与备份字符串 `"null"`。新测试脚本在发起 prep 前登记恢复需求，不把补偿恢复误当成原写请求重放。
- 中间完整干净构建在上述修正前启动，已停止，不能作为最终交付证据。最终重新构建、设备结果和包内比较另见下一节；当前没有 commit 或 push，旧 `HEAD` 不是当前源码。

## 31. 最终 Agent 设备验收与安装包一致性（2026-10-28）

- 最终 APK 实际安装到 PJE110，完整 `out/agent-system-device-verified.log` 退出成功，末行 `agent_system_device=ok`。本轮约 403 秒，不把它写成快速性能测试。
- 通过项目包括 root+a11y、系统信息、会话复用/DSL、旧接口中文输入、危险操作确认、automation 备份恢复与读回、DND 读回、包/用户/任务查询、传感器、事件订阅/退订、at/cron/on:event 作业、autosave/磁盘快照只读、无副作用自检、token 轮换后 helper 重连和录屏。
- 只读模式额外实测：普通点击、DSL click、DSL scrollUntil、IME hide 均返回 `READONLY`；纯 DSL 查询可用；macro 中的写步骤被拒绝，后台写任务进入 failed，不执行设备注入。`SNAPSHOT_READONLY` 负例仍通过。
- `out/agent-final-installed-artifact-check.log` 逐项核对设备实际安装内容：Agent APK SHA256 为 `640d60e0e68ef27010a6749285342336fd5e2f35483ffe4e8eb392ed9e82bb0e`，helper JAR SHA256 为 `fccd162a950f2a77ba25c6e90028c319208f92080f121b66f270066d6ca39c3f`，均与工作区最终构建一致。不是只测试了旧安装包。
- 本轮 `file_access=requires_user_grant`，文件读写/分享仍未验收。录屏成功且 `/sdcard/.dsh/recordings` 最终为空。未实际发送短信、读取私人通信数据或执行危险 root 写入；§29.3 的限制不变。
- 收尾 SELinux=Enforcing，root helper=stopped，未安装其开机脚本；无障碍保留输入法与 Agent，通知监听保留原有组件与 Agent。未修改主力机，未启用普通 Agent App 的 root。

## 32. Console Kotlin Compose 与浏览器（2026-10-29）

- Console 的 `MainActivity.java`、`WebActivity.java` 和旧 `SystemBars.java` 已移除，主界面和 WebActivity 使用 Kotlin Compose；保留 `ConsoleSupport.java` 的固定 root 命令、256 KiB 输出上限、退出码、超时、URI 和 token 校验。两个 Activity 仍固定竖屏，不修改系统自动旋转。
- 主界面使用 `safeDrawingPadding`，实际 PJZ110 截图 `out/console-compose-main.png` 显示中文标题、运行状态、PID、loop、镜像路径及脱敏地址，没有系统栏遮挡。关闭 Console 不会停止 holder/chroot。
- DSH 可打开内置 WebView，也可通过 `ACTION_VIEW` 使用外部浏览器。**用户已亲测外部浏览器打开成功**。内置页主框架加载失败或渲染进程退出时提供浏览器回退；不把此前内置页空白写成已修复。
- 地址优先读取 `status --json`，备用读取兼容 `state/runtime/url` 和旧 `state/guest/url`；不向日志输出 token。构建使用 `console-app/build.gradle.kts`、独立 `console-app/gradle.lockfile` 和统一 SHA256 验证，签名/zipalign 通过。
- 最终 Compose APK 为 `console-app/dist/chroot-console-0.2.0.apk`，SHA256 `fa41dafc7db3205305662422324348d5791c8d13f989491bf36c41fc60292b66`，实际安装内容哈希一致。debug 签名不是发布签名，未改变 root 授权或 chroot 历史数据。

## 33. 主力机反馈修正范围（2026-10-29）

- holder 启动时生成只读 `/run/dsh/module.json` 和 `module.sha256`，源文件留在未暴露的宿主状态目录，不解除模块、控制目录或活动镜像遮罩。快照恢复排除启动期元数据。
- guest `TMPDIR` 默认改为 rootfs 磁盘 `/var/tmp`，与宿主 `DSH_TMP` 分离；不自动删除旧 `/tmp` 文件，也不承诺立即释放既有 RAM。rescue 保留独立可写 tmpfs。
- 工作区兼容已有 `state/dsh-home/workspace`：真实目录使用 `/root/.dsh/workspace`，链接目标绑定到 `/run/dsh/workspace`，不重建旧 sdcard 链接、不每次修改自定义权限。真实目录内容会随 state 归档；链接只保存链接本身，外部目标不自动备份。正常模块安装不覆盖用户 DSH runtime、插件或 dsh-home。
- 启动日志保留 5 代，审计按日保留 14 天；URL 候选不再依赖固定 `dsh web:` 前缀，仍经严格 loopback 与健康检查。上游 model-listing 噪音、原生 URL API 和 healthz 不冒充已解决。
- `losetup` 错误输出不再丢弃。实测发现 `LOOP_CTL_GET_FREE` 返回已占用的 loop12，toybox 反复尝试直到 EMFILE；显式 `losetup /dev/block/loop47 <image>` 的 `LOOP_SET_FD` 成功。空闲探测还会返回 `No such device or address`，不能只接受 `No such file or directory`。控制器在自动分配失败时逐个验证候选设备，跳过占用和未知查询错误，再显式 attach；不 detach 陌生设备。
- Termux 仅用于本次调试工具安装，生产运行不依赖 Termux App、路径或包管理器。构建期 GNU cp/debugfs 及运行库经锁定、校验并随模块携带，mount helper 由 NDK 构建。
- 本节是代码与限定诊断范围，最终完整主机检查、隔离实例验收、正式升级备份及远程提交结果另见后续记录，未将它们提前写成完成。

## 34. 主力机 0.2.1 安装与激活验收（2026-10-29）

- 先停止 watchdog，核对 loop 归属并将所属块设备设为只读，保留手机内的镜像/state 备份；源镜像与备份 SHA256 一致，实际 `e2fsck -fn` 返回 0。正常重启后 `recover --apply` 返回 `recover: ok`，没有强删 loop、lazy unmount 或覆盖 chroot 数据。额外只读备份 `pre-loop-recovery-20261029` 未冒充 last-good。
- 用修复后的 staged 控制器创建 `pre-compose-upgrade-20261029` 冷快照并标记 last-good，镜像 SHA256 为 `69a64de3aa8ab0390ce09b0176956d244896071c2f9598dbdad76216eb54ee1c`。旧控制器的归档校验会误伤正常软链接，因此没有继续沿用旧校验器生成备份。
- `ksud module install` 安装 `0.2.1 (versionCode 3)`，staged 严格 doctor 为 **59 pass / 1 warn / 0 fail**。KernelSU 安装后移除 `customize.sh`；运行时 `module.sha256` 不再依赖该安装期脚本。激活后活动目录的 `dsh-ctl` 和 `dsh-watchdog` 均报告 `0.2.1`，关键脚本哈希与本地一致，不再以旧控制器的结果冒充新版验收。
- 清理阶段改为在宿主 mount namespace 执行只读进程扫描，避免把扫描器及其子进程误认成 guest 存活进程。真实启动、正常停止、再次 `boot` 均通过，`activated_stop_boot=ok`；停止后的全局 mount/namespace 复核保留。watchdog daemon 正常运行，SELinux 始终为 Enforcing。
- guest 内 `/run/dsh/module.json` 为 `0.2.1 (3)` 且只读挂载、模式 0444；`/var/tmp` 模式 1777 并位于 rootfs 磁盘。模块目录、控制目录和活动镜像路径保持遮罩或不可见，未解除 `/data` 或块设备保护。
- `state/dsh-home/workspace` 仍是真实目录，模式 0700、117 个文件、8402539 KiB，升级前后一致；未重建旧 sdcard 链接、未覆盖 DSH runtime、插件或历史工作区。新实例 token 与恢复前不同，未记录其明文。
- Console/Agent 均为 `0.2.0 (versionCode 2)`，实际安装 APK 哈希分别为 `fa41dafc7db3205305662422324348d5791c8d13f989491bf36c41fc60292b66`、`d70e2413b79fc5ad9ee9e5abba836aee0628327dff7cd127a2b2ee7b762607b4`。Console Compose 页面正常，外部浏览器 `mark.via` 成功打开 DSH。Agent ping、无障碍绑定、dump、截图及无输入自检通过；root helper 哈希一致但保持停止，未启用开机服务。
- 本节不证明真实模型会话、验证码、银行/游戏/Play Integrity、长期稳定性或所有 ROM 的兼容性。两个 APK 仍为 debug 签名。最终完整构建、源码包与远程推送结果另记，不以真机结果替代这些交付检查。

## 35. 运行中重启故障、临时恢复与修复候选（2026-10-29）

- §34 的“正常停止后再次 boot”不能外推为“运行中直接重启”通过。随后真实重启暴露出旧 `launcher.pid=1209` 被复用：记录 starttime/ns 为 `63927`、`mnt:[4026536150]`，当时实际为 `245`、`mnt:[4026531841]`。stop 与旧 recover 都因身份不符拒绝，不能靠强杀该 PID 解决。
- 只读检查确认 PID 文件时间早于本次 btime，rootfs mount/namespace 均无残留，目标镜像没有 loop，其它 loop 未触碰。临时恢复脚本把旧身份目录保留为 `state/guest.stale-20261029-093636`，随后现有控制器 `recover: ok`。这保留的是旧元数据，不是新的镜像快照，也不取代 last-good。
- 现有 `0.2.1` 随后真实启动成功，镜像 journal 恢复并 clean，状态 running，SELinux=Enforcing；staged doctor 为 **59 pass / 2 warn / 0 fail**。工作区仍为 0700、117 个文件、8402539 KiB，与恢复前一致。临时恢复只证明该实例可继续使用，不证明下一次重启会自动恢复。
- 真机审计另发现 boot 失败缺少控制器日志、watchdog 错误路径自旋并刷大日志，以及完整 hooks patch 未生效。经 guest 内实际解析复核：runtime 顶层存在同版本 `dsh-permission-presets`，但 `/root/.dsh/profiles/web` 的隔离 node_modules 缺少该包；`dsh-hooks-claude-code` 与协议包在 profile 中存在。不能仅凭顶层包或目录表象判断完整 patch 可用。
- `0.2.2 (versionCode 4)` 候选包含：boot_id 身份校验、无挂载无 loop 才清理遗留记录、安装器旧 PID 识别；boot 尝试日志和一次有界重试、boot 后再启动 watchdog；独立限流/轮转且 0600 的 watchdog 日志；profile 同版本依赖链接及 full/audit-only/disabled 的显式 hooks 状态。
- 本地 `test_log_limit`、`test_service`、`test_watchdog` 已通过，跨重启自动恢复回归也已通过。完整标准构建、`0.2.2` 真机升级与运行中重启复核、真实 hooks 加载/审计事件、最终源码包和 GitHub 推送仍未完成。真实模型会话、验证码、银行/游戏/Play Integrity、上游原生 healthz 与长期稳定性不在本节证明范围内。

## 36. Agent 事件协议与只读 UI 复核（2026-10-29）

- 修复后的 Agent `0.2.0` APK 已通过 SSH 安装，SHA256 为 `e31545cb371ab6ee142dd3014a53e827257e1db650dcf3afe45ecab56791d17a`。保留应用数据与现有 token，未卸载、清数据、force-stop、启用 root helper 或修改无障碍设置；升级后由用户重新启用无障碍。APK 仍为 debug 签名。
- 事件实测：`since=0` 的确认 `baseSeq=3`，首条新事件 `seq=3`；按 processId 续传的首条事件也是 `seq=3` 而不是从 `seq=1` 全量回放。旧 processId 返回 `resetRequired:true`，事件帧不含请求 `id/ok`。客户端 6 项主机回归通过。
- 前台实际包为 `mark.via.gp`。SystemUI 时钟存在时连续两次 `session.analyze` 均 `reused:true`，实测约 1 ms、0 ms，没有再出现每次 700–1800 ms 的持续全量重析。该结果只代表本次界面，不是性能承诺。
- 对象 selector 命中 102 个节点；对象和字符串两种 `ui.flag` 均保存成功；`ui.explain` 返回 21 层节点；`find|count` 的 `result.count=89`。缺少 epoch 的 `ui.click` 在输入前返回 `INVALID_ARGUMENT`。Activity 无法可靠确认时返回空串，不再误填 Launcher 类名；当次 `fallbackReason=null`、`reanalyzed=false`。
- **未解决项**：`backend.selftest` 的 token 检查仍为 `ok=false, err=TOKEN_INVALID`，尽管 App/run-as 均可读取 32 字节 token 且客户端实际认证成功。该项没有写成通过，也不靠轮换 token 掩盖。500 条事件压测、force-stop 自动恢复、真实模型会话、真实 hooks 审计事件以及所有 ROM 的兼容性仍未验证。

## 37. Agent token 自检与原生生命周期锁（2026-10-29）

- §36 的 token 自检已定位为固定把 `TOKEN_INVALID` 传给结果构造器，即使 token 非空也会失败；现只在 token 为空时传入该错误。没有轮换或输出 token。修复 APK 已保留数据安装，设备实际安装 SHA256 为 `f62c8da26740ec09e7a6ec165543b9b60d1ce126286f16bf65f2f9e89d06e2e2`，与工作区构建一致，仍为 debug 签名。
- 升级后系统设置为 `accessibility_enabled=0` 且服务列表为空，因此初次连接被拒绝，不能记作自检通过。恢复原已使用的 Agent 无障碍组件后，实测 `backend.selftest` 的 socket、token、a11y、dump、shot 均为 `ok=true`；input 为 `ok=true, executed=false`，明确没有注入输入。uiautomation/shell 仍按普通 App 权限返回 `NO_ROOT`，root helper 未启用。
- 生命周期锁改为原生 `--claim-wait`：只对锁竞争做有界等待，子命令失败立即返回，避免把 stop 等操作作为锁冲突重放。主机 `test_lock: ok` 覆盖竞争、stale 回收、短暂锁等待、失败子命令只执行一次和退出码保留。
- 新 ARM64 helper 在主力机独立临时目录实测通过：阻塞锁释放后命令只执行一次并返回原退出码 7；零等待遇忙返回非零且不执行子命令，结果为 `device_claim_wait=ok`。该测试不使用正式 lifecycle 锁、镜像或 chroot 数据，临时文件已清理。
- watchdog 全套回归已通过，覆盖正常启停、身份不符拒绝、跨启动 owner 回收、健康检查及遇忙零等待。周期检查不再阻塞在 stop 持有的生命周期锁上，避免 holder 无法及时处理 TERM。完整标准构建和正式升级结果见 §38。

## 38. 工作区与 DSH home 职责分离（2026-10-29）

- 用户明确重新选择 `/root/workspace` 作为工作区，并把 `/root/.dsh` 完全交给 DSH。新版不再采用 §33 的 `.dsh/workspace` 或链接方案：宿主 `state/workspace` 是独立真实目录，`state/dsh-home` 仅保存 DSH 数据。s6 默认 cwd、`DSH_WORKSPACE_DIR`、plan、doctor 和 `fixperm` 均按此职责调整。
- 安装只在其它步骤全部完成后，将旧的 `state/dsh-home/workspace` 原子重命名为 `state/workspace`；前期失败不移动旧目录。新旧目录同时存在、legacy 链接或目标链接均拒绝自动处理。`DSH_WORKSPACE_DIR` 不允许覆盖整个 `/root` 或落入 `.dsh`。
- 旧快照恢复在暂存区执行同样的真实目录迁移，并将独立工作区纳入替换与回滚；失败不得合并两份数据。工作文件随 state 归档，仍不写入 rootfs.img。
- 主机回归确认安装前期复制失败时旧目录保持原位、成功后 marker 保留、冲突不合并。最终标准构建日志 `build/.tools/build-0.2.2-final.log` 包含 `all tests passed`、`all host checks passed` 和模块打包成功；ZIP SHA256 为 `69859e507705dbde64d9b778d9a95680de379394ce02898c67e49a37ac2148a7`。
- 真机核对发现当前项目已在 rootfs `/root/workspace`，旧宿主目录只剩 5 个文件，不能直接用旧目录绑定遮住当前项目。维护脚本停止 DSH 写入、建立只读 bind，将当前工作区复制到独立存储并执行 `diff -qr`；6092 个文件比较一致后发布，原 rootfs 内容保留作备份，旧宿主目录另存为 `state/legacy-workspace-pre-independent`。没有删除原项目或用旧快照覆盖。
- 正式安装 staged doctor 为 **61 pass / 1 warn / 0 fail**，启动、full hooks、watchdog 和 SELinux 均通过；独立目录设备号/inode 在安装前后不变，权限为 0700。真实重启为 `initial_boot=ok`，holder/watchdog 的 boot_id 和身份匹配，日志为 0600，guest 内 `/root/workspace` 与 `/root/.dsh` 分离且项目可访问，`reboot_upgrade_verified=ok`。last-good 仍为 `pre-compose-upgrade-20261029`。
