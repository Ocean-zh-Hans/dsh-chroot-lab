# 设计 v1.5（当前唯一技术设计）

- 日期：2026-10-26
- 状态：**设计定稿；P1–P5 开发产物完成；真实挂载验证由备用机/PC AI 执行**
- 取代：v1.4、v1.3、v1.0；旧设计全部归档，不再有当前效力
- 决策依据：`decisions.md`（`1A 2B 3A 4B 5A 6B 7A 8A 9A 10A 11A 12A 13A 14B 15A`）

> 本文是“怎么做”的唯一来源。需求和硬不变量见 `requirements.md`；应用层隐藏性见 `hiddenness.md`；实测证据见 `verification.md`。

---

## 0. 一句话

在**同一台手机**上，用 KSU 模块持有 private mount namespace，把 Ubuntu 24.04 ext4 镜像 chroot 成完整 Linux；chroot 内 root/full `/data`/`/dev`/`/sys`/Android 视图全开，但所有挂载只在 private ns，全局 SELinux/prop/挂载视图不动；DSH 是 Ubuntu 的第一个应用，不是 Ubuntu 的身份。

## 1. 当前 P0 状态

| P0 | 设计处理 | 代码状态 |
|---|---|---|
| 镜像/控制面自引用 | `1A`：private ns 内 tmpfs 遮罩控制面/镜像及已知别名；接受 `/proc/1/root` 绕过 | 代码已实现；P2 验证 |
| `/sys` rw + hardguard 不是边界 | `2B`：`/sys` rw；`/sys/fs/selinux` 用 private tmpfs 遮罩；承认 hardguard 不是边界 | P2 |
| stop/namespace 竞态 | 记录 PID + starttime + PGID + ns inode；先 s6 停止，再只向 holder TERM（由它转发）；holder 活着时逆序 umount | P2 |
| 快照一致性 | stop + unmount + e2fsck 后冷复制；sha256 + 原子 rename；last-good | P2/P3 |
| 手机控制未验证 | 建立 capability matrix；UI 用宿主 agent；未验证项不写“perfect” | P4 |

**注意**：P1 的 `doctor/plan/status` 不执行表中任何 mount/apply 操作。

---

## 2. 术语与平台事实

### 2.1 术语

- **宿主/本机**：这台 Android 手机。
- **chroot 内**：同一台手机上的进程树，根切到 Ubuntu rootfs；共享内核/网络/硬件；不是 VM、不是 proot、不是“客机”。
- **holder**：`dsh-nsinit` 长驻进程，持有 private mount namespace；s6 是它的子进程。
- **approval-free**：DSH 的 approval 为 `never`，不弹批准；DSH 仍是 uid 0。
- **full**：chroot 内 `/data`/`/dev`/`/sys` 全开；不是全局 mount/prop 全开。
- **应用层隐藏性**：游戏/银行/Play Integrity 不因本模块的**可见**痕迹发现 root。

### 2.2 平台事实

| 项 | 事实 | 影响 |
|---|---|---|
| Root | SukiSU 4.2.1-r2；`u:r:ksu:s0` | 域是 permissive allow-all；全局 Enforcing 不约束它 |
| 内核 | 6.6.66 GKI；`CONFIG_USER_NS`/`PID_NS`/`IPC_NS` 未编译 | 只有 mount + UTS ns；没有 PID/USER/IPC 隔离 |
| `/data` | f2fs `nosuid,nodev`，无 `noexec` | 可执行；镜像必须放 `/data/media/0` |
| `/sdcard` | FUSE `noexec` | 执行走 raw `/data/media/0` |
| loop | 仅 `/data/media/0` 可用（`/data/adb` FBE EIO） | 镜像固定 `/data/media/0/ubuntu-rootfs/rootfs.img` |
| `/tmp` | 宿主 tmpfs | 镜像挂 `/tmp/dsh-rootfs`；启动前 `mkdir -p` |
| `/run` | 宿主不存在（root 是 ro erofs） | `/run` 是 chroot 内 tmpfs |
| 快照 | f2fs 无 reflink | 冷复制，不做“假快照” |
| 逃逸 | `/proc/1/root` 可到宿主根 | full root 的固有限制；不是 App 检测面 |

---

## 3. 架构

```
L4 接入      DSH web (loopback token) / ttyd (optional) / browser / adb forward
L3 策略      DSH preset approval-free (danger-full-access + never)
             hardguard 只拦明显改全局 SELinux/prop/全局 mount 的命令（不是边界）
             audit 始终记录
L2 运行时    Ubuntu 24.04 + Node 24 + DSH versions/current/previous
             s6-svscan (dsh, ttyd) + dsh-run (URL/ready)
L1 chroot    ext4 128GB image + full mount set + chroot
             /data /mnt /data_mirror /storage /sdcard /dev /sys /proc
             Android partitions + debugfs/tracefs + Android wrappers
L0 宿主      KSU module: service.sh / boot-completed.sh / uninstall.sh
             dsh-ctl: doctor/plan/status/start/stop/...
```

原则：

- L1 追求“与宿主等价”，不是最小面。
- L2 只跑一个 Agent（DSH）；宿主 agent 是可选补全。
- L3 默认 free；`guard` 可选；审计始终记录。
- L0 只做生命周期，不做业务。

### 3.1 分层命名

```
L0  Ubuntu 24.04 base            rootfs.img 内 /bin /usr /etc /apt
L1  Node 24 / s6 / 基础工具      装进 Ubuntu
L2  DSH（第一个应用）             /data/adb/chroot/dsh → /opt/dsh
L3  以后其他应用（如 AstrBot）    同样装进 Ubuntu
```

**基础系统用发行版名** `ubuntu-rootfs`；DSH 名字只出现在 DSH 运行时、home、工作区。模块名 `dsh_chroot` 是部署工具名，不是 Ubuntu 的名字。

---

## 4. 存储布局

```
/data/adb/chroot/                       # 模块私有数据（0700 root）
├── control/                            # hooks/s6/bin → /opt/dsh-control
├── state/
│   ├── dsh-home/                       # → /root/.dsh
│   ├── guest/                          # URL/ready/launcher.pid/ns inode
│   └── ctl/                            # status/lock/last-good
├── logs/                               # → /var/log/dsh
├── dsh/                                # DSH versions/current/previous
├── payload/                            # rootfs.tar.gz（首次安装）
├── snapshots/                          # state 快照
└── config.conf                         # 可选，0600

/data/media/0/ubuntu-rootfs/             # 唯一镜像目录（0700 + .nomedia）
├── rootfs.img                           # ext4 128GB sparse
└── snapshots/                           # 镜像冷快照

/data/media/0/DSH/workspace/             # → /sdcard/DSH/workspace
```

- `rootfs.img` 是 Ubuntu 的虚拟磁盘，不是 DSH home，不是工作区。
- DSH home 在镜像外：`/data/adb/chroot/state/dsh-home`。
- 镜像目录不是 Android 官方目录；是为了避开 FBE 的 loop EIO。
- 所有控制面路径 `0700`；镜像 `0600`；目录放 `.nomedia`。

---

## 5. 挂载设计（full）

### 5.1 顺序

1. `unshare --mount --propagation private --uts -m`（util-linux，模块自带；不 `--ipc`；IPC_NS 未编译）。
2. `mount --make-rprivate /`。
3. `mount -t ext4` 镜像到 `/tmp/dsh-rootfs`。
4. 在 `$ROOT` 下先 `mkdir -p` 所有挂载点，再挂载：`/proc`、`/sys`、`/dev`、`/dev/pts`、`/dev/shm`、`/run`、`/tmp`。
5. Android 视图：`/data`、`/mnt`、`/data_mirror`、`/storage`、`/sdcard`、`/system`、`/system_ext`、`/product`、`/vendor`、`/odm`、`/apex`、`/linkerconfig`。
6. 控制面 bind（必须在遮罩之前）：`/opt/dsh`、`/opt/dsh-control`、`/root/.dsh`、`/run/dsh`、`/var/log/dsh`。
7. 保护/遮罩（控制面 bind 完成之后）：`/sys/fs/selinux` tmpfs；`/metadata`、`/mnt/vendor/persist`、`/mnt/vendor/qmcs` 递归 ro；控制面/镜像别名 tmpfs。
8. 写 `/etc/resolv.conf`、`/etc/hosts`；复制 s6 服务到 `/run/s6`。
9. 父 holder fork + exec `chroot "$ROOT" ... /bin/sh -c '... exec s6-svscan /run/s6'`。

### 5.2 基础挂载

| chroot 内 | 源 | 选项 | 说明 |
|---|---|---|---|
| `/` | `/tmp/dsh-rootfs` | ext4 | 128GB 镜像 |
| `/proc` | `proc` | `proc,nosuid,noexec,nodev` | 完整进程视图 |
| `/sys` | `sysfs` | **rw**（2B） | 完整内核/设备视图 |
| `/sys/fs/selinux` | tmpfs | `size=1m,mode=0555` | **private ns 遮罩**；不 bind/remount 宿主 selinuxfs；防止 chroot 误写全局 SELinux |
| `/dev` | `/dev` | rbind `nosuid` | 3A：block/loop/binder/ashmem 全见 |
| `/dev/pts` | devpts | `newinstance,ptmxmode=0666,mode=620` | 独立 pts |
| `/dev/shm` | tmpfs | `size=1g,mode=1777` | Chromium/Playwright |
| `/run` | tmpfs | `size=1g,mode=755` | s6/运行时 |
| `/tmp` | tmpfs | `size=4g,mode=1777` | TMPDIR |
| `/opt/dsh` | `$CHROOT_HOME/dsh` | bind ro（host runtime 非空时） | DSH 运行时；空则用 rootfs 内烘焙版本 |
| `/opt/dsh-control` | `$CHROOT_HOME/control` | bind ro | hooks/s6/bin |
| `/root/.dsh` | `$CHROOT_HOME/state/dsh-home` | bind rw | DSH home |
| `/run/dsh` | `$CHROOT_HOME/state/guest` | bind rw | URL/ready/pid |
| `/var/log/dsh` | `$CHROOT_HOME/logs` | bind rw | 审计/日志 |

### 5.3 Android 视图

| chroot 内 | 源 | 选项 | 说明 |
|---|---|---|---|
| `/data` | `/data` | rbind rw | 1A：完整 |
| `/mnt` | `/mnt` | rbind rw | 多用户/安装器视图 |
| `/data_mirror` | `/data_mirror` | rbind rw | Android 11+ |
| `/storage` | `/storage` | rbind rw | 15A：完整，含 self/emulated/用户 |
| `/sdcard` | `/data/media/0` | bind rw | raw；exec；完整内部存储 |
| `/system` | `/system` | rbind | 硬件 ro |
| `/system_ext` | `/system_ext` | rbind | 硬件 ro |
| `/product` | `/product` | rbind | 硬件 ro |
| `/vendor` | `/vendor` | rbind | 硬件 ro；含 firmware/dsp 子挂载 |
| `/odm` | `/odm` | rbind | 硬件 ro |
| `/apex` | `/apex` | rbind + **递归 ro**（14B） | 必须验证 `cacerts` 等子挂载也 ro |
| `/linkerconfig` | `/linkerconfig` | rbind + **递归 ro**（14B） | 源是 rw tmpfs；不能只顶层 ro |
| `/metadata` | `/metadata` | bind + 递归 ro（6B） | 默认保护 |
| `/mnt/vendor/persist` | 同名 | 随 `/mnt`，再递归 ro（6B） | 默认保护 |
| `/mnt/vendor/qmcs` | 同名 | 随 `/mnt`，再递归 ro（6B） | 默认保护 |
| `/sys/kernel/debug` | debugfs | rw | 显式 mount；默认可能未挂 |
| `/sys/kernel/tracing` | tracefs | rw | 显式 mount/确认 |

### 5.4 控制面/镜像遮罩（1A）

在 private ns 中，等所有控制面 bind 完成后，对以下路径（存在才做）挂空 tmpfs：

```
/data/adb/chroot
/data/adb/modules
/data/adb/ksu
/data/media/0/ubuntu-rootfs
/sdcard/ubuntu-rootfs
/storage/emulated/0/ubuntu-rootfs
/mnt/user/0/emulated/0/ubuntu-rootfs
/mnt/installer/0/emulated/0/ubuntu-rootfs
/mnt/androidwritable/0/emulated/0/ubuntu-rootfs
/mnt/pass_through/0/emulated/0/ubuntu-rootfs
```

- 目标必须是已存在目录；不存在则 skip，不创建宿主目录。
- 每个 mask 记录 `mounted/skipped`；启动日志显示。
- **不声称完整**：已知别名 + `/proc/1/root` 仍可绕过；这是稳定性/防手滑，不是安全边界。
- 遮罩后 `/data` 的用户数据仍完整可读写。

### 5.5 递归只读（6B/14B）

需要一个模块自带的 `dsh-mount-ro-recursive`：

- 静态 C 小程序，调用 `mount_setattr(2)`，`AT_RECURSIVE | AT_EMPTY_PATH`（或 `AT_FDCWD` + path）设置 `MOUNT_ATTR_RDONLY`。
- 目标：`/apex`、`/linkerconfig`、`/metadata`、`/mnt/vendor/persist`、`/mnt/vendor/qmcs`。
- 若内核/工具不支持：**start 失败并报错**，不能降级成“顶层 ro”后声称完成。
- 备选：`findmnt -R -n -o TARGET` + 逐挂载 `mount -o remount,ro,bind`；仅在 `mount_setattr` 不可用时作为临时方案，并记录“非原子递归”。

### 5.6 绝不做

- 不对 `/sys/fs/selinux` 做 bind/remount（事故教训）。
- 不做主机 namespace 的 overlay/debugfs/tracefs/loop 测试。
- 不把任何 chroot mount 传播到宿主。
- 不用 `mount -o ro,bind` 假装递归只读。

---

## 6. SELinux 与隐藏性

### 6.1 全局不变量

- `/sys/fs/selinux/enforce` 必须是 `1`；`getenforce` 只作参考，不作唯一判据。
- 不改全局 prop；不重置 `ro.*`/`init.svc.*`/`persist.*`。
- 不默认 `setenforce 0`；`--permissive` 只允许显式 break-glass，记录并在 stop/watchdog 恢复。
- 不对宿主 selinuxfs 做任何 bind/remount。
- KSU 隐藏特性保持：`selinux_hide`/`mount_hide`/`avc_spoof`/`kernel_umount`。

### 6.2 域事实

`u:r:ksu:s0` 是 KernelSU 的 permissive + allow-all 域；它不受全局 Enforcing 约束。应用看不到其他进程的域，所以这不是 App 检测面；但**不能**说“SELinux 保证 chroot 安全/隐藏”。`hardguard` 只拦 DSH 工具路径上的明显命令，ttyd/编译程序/syscall/直接 sysfs 写都能绕过。

### 6.3 应用可见面

必须守住：

1. 全局 SELinux Enforcing；
2. 全局 prop 不变；
3. 所有 chroot mount 在 private ns；
4. KSU 隐藏特性不关；
5. DSH/ttyd 只 loopback；
6. 宿主 `/proc` 的 `hidepid=invisible`（本机已是）；
7. `/sdcard` 不放 `magisk`/`kernelsu`/`su` 之类明显标记。

`ubuntu-rootfs`/`DSH` 名字、loop 设备、sdcard 工作区是可接受的低强度信号；不为名字过度设计。详见 `hiddenness.md`。

---

## 7. 生命周期

### 7.1 start（设计；P2 实现）

```
dsh-ctl start [--protect-critical=off] [--permissive]
  1. mkdir 原子锁；写 owner pid/starttime；stale 则清理
  2. doctor：root、SELinux enforce、image、工具链、路径、无残留
  3. mkdir -p /tmp/dsh-rootfs && chmod 700
  4. e2fsck -p image；检查同一 backing file 无第二个 loop
  5. losetup --sector-size 4096 --direct-io=off；记录 loopdev
  6. setsid unshare --mount --propagation private --uts -m \
       /data/adb/chroot/bin/dsh-nsinit >logs/nsinit.log 2>&1 &
       # setsid 可能 fork；不要依赖 shell 的 $! 当 holder PID
  7. dsh-nsinit（两段式，holder 不 exec 掉自己）：
       host 段：先写 $$ 到 state/guest/launcher.pid（失败 trap 删除）；
                mount image 到 /tmp/dsh-rootfs；在 $ROOT 下挂载全部；
                控制面 bind；s6 服务复制；写 resolv.conf；
       chroot 段：fork + exec chroot "$ROOT" /usr/bin/env -i ... \
                    /bin/sh -c 'exec s6-svscan /run/s6'，父进程 wait；
                收到 TERM/INT 时转发给 s6 子进程并等待退出
  8. dsh-ctl 等 state/guest/launcher.pid 出现；读 holder PID、PGID、
     /proc/PID/stat starttime、/proc/PID/ns/mnt inode；s6 是 holder 的子进程，
     不是 holder 本身。不要用 setsid 的 $! 猜 PID。
  9. 等 /run/dsh/url 或 s6-svstat；失败保留 holder 做逆序清理，再 detach loop
```

- **holder 必须长驻**；s6 是子进程。holder 活着时才能安全 umount。
- `dsh-nsinit` 不是一次性 wrapper；不能 `exec s6` 替换自己。
- 因此 `dsh-ctl` 记录的是 holder PID；stop 先通过 chroot 内的 s6 命令停服务，再清理 holder 持有的挂载。
- 启动前 `/tmp/dsh-rootfs` 必须存在且为空；不能是宿主已有挂载。

### 7.2 stop（设计）

```
dsh-ctl stop
  1. 加锁
  2. 读 PID/starttime/ns inode；不一致按 stale 处理
  3. 重新校验 PID/starttime/ns inode；用 nsenter --mount=/proc/PID/ns/mnt 进入
     （不在 shell 里 exec fd：mksh 的 special builtin 重定向失败会退出；nsenter 自己打开路径）
  4. nsenter ... chroot /tmp/dsh-rootfs /bin/sh -c 'sync; sync'
  5. s6-svc -d dsh/ttyd；s6-svwait -d -t 10000
  6. 超时：s6-svc -k；TERM holder（由 holder 转发给 s6 子进程）；等 5–10s；仍不退 KILL
  7. holder 的 EXIT trap 逆序 umount；remount,ro /
  8. 扫描 /proc/*/ns/mnt inode（不是 grep mountinfo 路径）
  9. 确认无同 ns 进程；loop 仍被占用时等待
 10. 宿主 losetup -d；等待 loop 真正消失（lazy detach 不算）
 11. 恢复 SELinux（若曾 permissive）；写 stopped；删 lock/pid；保留 logs
```

- toybox `nsenter` 没有 `-r`；必须 `nsenter ... chroot $ROOT ...`。
- `/proc/PID/mountinfo` 第 5 字段是相对 root 的路径，不能用 `grep $ROOT` 做通过条件。
- 不能用 `kill -9 holder` 代替逆序清理；会留下挂载/loop。

### 7.3 宿主 watchdog

独立于 DSH/s6，30s 检查：

- holder PID/starttime/ns inode 一致；
- `/sys/fs/selinux/enforce` = 1（若启动时改过 permissive 必须恢复）；
- loop backing 是否仍存在；
- 异常：先记录，再做可逆清理；不要盲目重启造成竞态。

### 7.4 开机

- `service.sh`：立即返回，`setsid` 后台 `dsh-ctl boot`；成功后启动 watchdog。
- `boot-completed.sh`：等 `sys.boot_completed=1` **且** `sys.user.0.ce_available=1`，再启动。
- boot counter：连续失败 3 次 → rescue，不无限重启。
- 开机后断言 `enforce=1`；失败只记录/告警，不自动 setenforce（除非用户显式 break-glass）。

### 7.5 命令面

`doctor/plan/status/start/stop/restart/diag/log/image/snapshot/restore/guard/system-rw/rescue/purge/fixperm`。

- 已实现：doctor/plan/status/config/start/stop/restart/boot/diag/log/snapshot/restore/guard/system-rw/rescue/purge/fixperm。
- `start`/`rescue`/`system-rw on|off` 必须显式 `--apply`；没有 `--apply` 只打印计划。
- `guard on|off`：可选 deny/ask；默认 off。
- `system-rw on|off`：默认 off；private ns overlay；upper/work 放 ext4 rootfs `/var/lib/dsh/overlays`。
- `purge`：stop + 校验 + 删除 `/data/adb/chroot` 和 `/data/media/0/ubuntu-rootfs`，确认无 loop；`--keep-image` 保留镜像。

---

## 8. DSH 运行时

- Node 24；DSH `versions/<v>` + `current`/`previous`；`/opt/node`；`/opt/dsh`。
- `dsh-run`：`node ... web --host 127.0.0.1 --port 0 --no-open`；抓 URL 写 `/run/dsh/url`；转发信号；日志 `/var/log/dsh/dsh.log`。
- s6：`s6-svscan /run/s6`；`dsh`/`ttyd`；`finish` 退避；`down`。
- DSH 配置：
  - `fs-sandbox.cwd: /sdcard/DSH/workspace`
  - `workspaceRoot: /sdcard/DSH/workspace`
  - 默认 `approval-free`：`danger-full-access` + `approval: never`
  - 可选 `approval-guard`：`danger-full-access` + `ask`
- `hardguard` 始终启用（命令钩子）：拦 `setenforce`、写 `/sys/fs/selinux/*`、`resetprop` 改全局 prop、全局 mount 传播；**不是安全边界**。DSH profile 必须通过 `dsh-hooks-claude-code` 指向 `/opt/dsh-control/hooks/hooks.json`；该 profile 接线在 P3 验证。
- 工作区：`/sdcard/DSH/workspace`；`chmod 2777`、`umask 000`；文件 0666；`dsh-rm` 回收站。
- `ttyd` 可选；127.0.0.1；密码 `state/guest/ttyd.pass`。

### 8.1 宿主 thin agent（4B）

- 目的：补 UI 树、通知、剪贴板、包管理、部分 `am/pm` 写操作。
- 形态：Android 本机 accessibility/Shizuku/ADB agent；当前最小实现是 `127.0.0.1:17321` + token 文件，固定 JSON 方法集，不暴露任意 shell。
- 不是隐藏必需；不做也不影响 chroot 主体。
- 最小 App 源码在 `agent-app/`，可构建 debug APK；**未在真机验证**。

### 8.2 手机控制能力矩阵

见 `verification.md`；状态分级：

`present / help-only / read-only-verified / write-verified / unavailable`。

未达到 `write-verified` 的项不能写“完整/完美”。

---

## 9. 快照、备份、恢复

- **冷快照**：stop → unmount → e2fsck → `cp --sparse=always` 到临时文件 → sync → sha256 → 原子 rename。
- 保留 7 份上限；先检查 `/data` 余量 ≥ 1×实际用量 + 安全余量；镜像填满时拒绝新快照。
- state 快照：tar + sha256；与镜像 manifest 关联。
- `/sdcard/DSH`：`dsh-rm` 回收站 + restic 到 PC/NAS（9A）。
- `/data`/`/sdcard` 其余：明确不在承诺范围。
- 回滚：stop → 校验 → 当前镜像改名 `rootfs.bad.<ts>.img` → 新镜像 rename → start → smoke；失败切回 last-good。
- 卸载：stop + 全机扫描 + `losetup -d`；模块卸载保留 `/data/adb/chroot`；`purge` 显式全删（含镜像/快照）。

---

## 10. 打包与工具链

- 模块 zip：`module.prop/customize.sh/service.sh/boot-completed.sh/uninstall.sh/action.sh/bin/dsh-ctl/bin/dsh-nsinit/bin/dsh-watchdog/control/*/lib/*`；`control/bin/dsh-run` 负责 URL 发现。
- 包根禁止 `install.sh`；payload 不放 zip。
- 工具链必须自带：
  - util-linux `losetup`（支持 `--sector-size`）；
  - `mke2fs/e2fsck/resize2fs`；
  - GNU `cp`（`--sparse`）或 tar；
  - `dsh-mount-ro-recursive`（mount_setattr）；
  - util-linux `unshare`/`nsenter`（必须支持 `--propagation`/`--mount`）；`chroot`/`mount` 可用 Android toybox，doctor 检查。
- payload：`rootfs.tar.gz`（Ubuntu 24.04 + Node 24 + Go + Python + build-essential + s6/ttyd + Android 工具依赖）。
- `customize.sh` 只建目录/复制 control；不解压 payload；更新前必须 stop。
- `action.sh`：当前只读 `status`；长任务/启停动作由 `dsh-ctl` 显式执行，不把 action 按钮变成隐藏的自动启动。

---

## 11. 验收

### 11.1 功能 P0

1. chroot 内 `id`=0、`uname -r` 与宿主一致；Go 在 `/sdcard` 编译并运行。
2. Node 24 + node-pty PTY；`dsh web --port 0 --no-open` URL；`approval-free` 可用。
3. `/data` rbind：`/data/user/0`、`/data/data`、`/data/system` 可见；`/mnt`、`/data_mirror`、`/storage`、`/sdcard` 完整。
4. `/dev/block`、binder/ashmem、`/dev/pts`、`/dev/shm` 可见；`/sys` rw；debugfs/tracefs 可读。
5. Android CLI：`am` help、`pm list packages`、`input`、`screencap`、`screenrecord --time-limit 1`、`cmd notification help`；`uiautomator dump` 不可用，只承诺 `runtest`（需 jar）。
6. `/apex` 子挂载递归 ro；`/linkerconfig` 递归 ro；`/metadata`/persist/qmcs 默认 ro。
7. stop：无同 ns inode 进程；无 rootfs 进程；无 launcher；loop 真正消失。
8. 镜像快照 → 破坏 → restore；state 同步；sha 校验。
9. 开机自启（service.sh + boot-completed，非阻塞，10–60s）；重启/强杀自愈。
10. `guard on` 时 deny/ask 生效；`guard off` 时审计仍记录。

### 11.2 隐藏性 P0

1. App 自己的 `/proc/self/mountinfo` 无 `dsh-rootfs`/chroot 挂载。
2. 全局 `enforce=1`；无全局 prop 改动；KSU 隐藏特性开启。
3. `/sdcard` 无 `magisk`/`kernelsu`/`su` 名称；`ubuntu-rootfs` 0700 + `.nomedia`。
4. 宿主 `/proc` hidepid=invisible。
5. 银行/游戏/Play Integrity 检测矩阵（待 P5）：至少记录通过/失败，不写空泛保证。

### 11.3 安全/可逆 P0

1. P2 只在备用机/VM 做 mount/loop；零残留。
2. 任何失败路径都有 trap 清理；不把 `losetup -d` 返回 0 当 detached。
3. `purge` 删除镜像/快照前确认无 loop、无 mount、无 holder。
4. 不在主机 namespace 做 selinuxfs bind/remount/overlay/debugfs/tracefs 测试。

---

## 12. 路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 设计定稿、决策锁定、文档归并 | 本次完成 |
| P1 | 静态 module + `dsh-ctl doctor/plan/status`；不 apply | 完成 |
| P2 | 备用机/VM：mount/ns/stop/loop；零残留 | 待做 |
| P3 | DSH/Node/s6/URL/工作区；快照/回滚 | 待做 |
| P4 | full Android 视图 + 宿主 agent 手机控制 | 待做 |
| P5 | 模块打包、开机、迁移、检测矩阵 | 待做 |

---

## 13. 未验证/开放项

这些不是文档冲突，是事实未验证：

- 真实 full `/data` rbind 的别名遮罩完整性和顺序；
- `mount_setattr(AT_RECURSIVE)` 在本机内核对 `/apex`/`linkerconfig` 的效果；
- `/system` overlay 在实际挂载点的兼容性；
- `am start`/`pm install`/`pm clear`/`settings put` 写操作；
- `uiautomator runtest`（需 jar）；
- frida/stackplz、`/proc/kallsyms`、`/proc/<pid>/mem`；
- 128GB 镜像的 mke2fs/resize2fs/快照/恢复全流程；
- DSH `approval-free` + hooks 的真实组合；
- 银行/游戏/Play Integrity 的实际检测矩阵。

**在 P2 之前，任何“已经可用/完美”的说法都不成立。**
