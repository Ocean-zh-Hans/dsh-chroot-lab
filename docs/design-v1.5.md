# 设计 v1.5（当前唯一技术设计；2026-10-30 修订）

- 日期：2026-10-26
- 状态：**接手审核修正、PC 构建检查及备用机限定验收通过；未验证项见 `verification.md`**
- 取代：v1.4、v1.3、v1.0；旧设计全部归档，不再有当前效力
- 决策依据：`decisions.md`（`1A 2B 3A 4B 5A 6B 7A 8A 9A 10A 11A 12A 13A 14B 15A`）

> **2026-10-30 修订**：§6.1、§6.3 与 selinuxfs/`/dev/block` 的读可见性口径已按 `plan-v2.md` 改写；旧原文见 `archive/notes/18-readmax-writecareless/design-v1.5-before.md`。
>
> 本文是“怎么做”的唯一技术设计来源；`plan-v2.md` 是新执行计划。需求和硬不变量见 `requirements.md`；应用层隐藏性见 `hiddenness.md`；实测证据见 `verification.md`。

---

## 0. 一句话

在**同一台手机**上，用 KSU 模块持有 private mount namespace，把 Ubuntu 24.04 ext4 镜像 chroot 成完整 Linux；chroot 内 root 保持所选 `/data`、`/sys`、常规 `/dev` 和 Android 能力，`/dev/block` 默认只读；所有挂载只在 private ns，全局 prop/挂载视图不动，且不读取/不修改/不判断 SELinux；DSH 是 Ubuntu 的第一个应用，不是 Ubuntu 的身份。

## 1. 当前 P0 状态

| P0 | 设计处理 | 代码状态 |
|---|---|---|
| 镜像/控制面自引用 | `1A`：private ns 内 tmpfs 遮罩控制面/镜像及已知别名；接受 `/proc/1/root` 绕过 | 备用机已实测 |
| `/sys` rw 与提示的边界 | `2B`：`/sys` rw；selinuxfs 默认在 private ns 新建只读挂载，`mask` 仅显式；可见风险提示和审计不是安全边界 | 已按新读模型修订 |
| stop/namespace 竞态 | 校验 PID/starttime/ns inode；先停止 s6 监督树，再由 holder 逆序清理；失败保留 recovery 信息，不能伪报 stopped | 备用机启停/清理已实测 |
| 快照一致性 | 冷状态证明 + 镜像/state 双 SHA256 + 临时文件原子替换 + 失败回滚；last-good 只引用已校验快照 | 备用机快照/恢复/回滚已实测 |
| 手机控制 | 宿主 Agent + 会话/查询 DSL + 可选独立 root helper；未验证项不写“perfect” | PJE110 主要 T0/T1 链路、剪贴板往返已实测；文件访问未授权 |

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
             SessionStart/UserPromptSubmit 风险提示（不阻断）+ PostToolUse audit
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

/data/adb/chroot/state/workspace/         # 独立真实目录（0700）→ /root/workspace
/data/adb/chroot/state/dsh-home/          # 仅 DSH 配置/runtime → /root/.dsh
```

- `rootfs.img` 是 Ubuntu 的虚拟磁盘，不是 DSH home，不是工作区。
- DSH home 在镜像外：`/data/adb/chroot/state/dsh-home`。
- 镜像目录不是 Android 官方目录；是为了避开 FBE 的 loop EIO。
- 所有控制面路径 `0700`；镜像 `0600`；目录放 `.nomedia`。
- `state/ctl/last-good` 只保存已校验快照名，由 `snapshot create --last-good` 或 `snapshot promote` 产生；不再把当前镜像路径当作恢复点。
- `/tmp/dsh-recovery` 保存本次挂载记录、恢复脚本和清理结果，正常清理后删除；失败时保留供 `recover --apply` 使用。

---

## 5. 挂载设计（full）

### 5.1 顺序

1. `unshare -m -u`（toybox/util-linux 均可；不 `--ipc`；IPC_NS 未编译）。
2. `dsh-mount-ro-recursive --make-rprivate /`（模块自带 helper；失败则拒绝启动）。
3. `mount -t ext4` 镜像到 `/tmp/dsh-rootfs`。
4. 在 `$ROOT` 下先 `mkdir -p` 所有挂载点，再挂载：`/proc`、`/sys`、`/dev`、`/dev/pts`、`/dev/shm`、`/run`、`/tmp`。
5. Android 视图：`/data`、`/mnt`、`/data_mirror`、`/storage`、`/sdcard`、`/system`、`/system_ext`、`/product`、`/vendor`、`/odm`、`/apex`、`/linkerconfig`。
6. 控制面 bind（必须在遮罩之前）：`/opt/dsh`、`/opt/dsh-control`、`/root/.dsh`、`/run/dsh`、`/var/log/dsh`。
7. 保护/遮罩（控制面 bind 完成之后）：默认新建只读 selinuxfs（`DSH_SELINUXFS_MODE=ro`，`mask` 仅显式）；默认只读 `/dev/block`；`/metadata`、`/mnt/vendor/persist`、`/mnt/vendor/qmcs` 递归 ro；控制面/镜像别名 tmpfs。
8. 写 `/etc/resolv.conf`、`/etc/hosts`；复制 s6 服务到 `/run/s6`。
9. 父 holder fork + exec chroot；先加载 `control/bin/dsh-android-env`，再启动 s6。holder 在挂载前写入自己的 PID/starttime/ns inode。
10. 严格校验 DSH loopback URL 与 holder 身份；未就绪标记 degraded，其他失败进入统一清理事务。

### 5.2 基础挂载

| chroot 内 | 源 | 选项 | 说明 |
|---|---|---|---|
| `/` | `/tmp/dsh-rootfs` | ext4 | 128GB 镜像 |
| `/proc` | `proc` | `proc,nosuid,noexec,nodev` | 完整进程视图 |
| `/sys` | `sysfs` | **rw**（2B） | 完整内核/设备视图 |
| `/sys/fs/selinux` | selinuxfs ro（默认） | `ro`；`mask` 仅显式选项 | private ns 内新建只读 selinuxfs，让 root 可读；不 bind/remount 宿主 selinuxfs |
| `/dev` | `/dev` | rbind | 常规设备与 Android IPC 保留；默认 `/dev/block` 只读可见，只有 `DSH_BLOCK_ACCESS=full` 才可写 |
| `/dev/pts` | devpts | `newinstance,ptmxmode=0666,mode=620` | 独立 pts |
| `/dev/shm` | tmpfs | `size=1g,mode=1777` | Chromium/Playwright |
| `/run` | tmpfs | `size=1g,mode=755` | s6/运行时 |
| `/tmp` | tmpfs | `size=4g,mode=1777` | 临时小文件/兼容路径；不默认用作编译和解包 TMPDIR |
| `/var/tmp` | rootfs ext4 | 目录 1777；默认 `DSH_GUEST_TMP=/var/tmp` | s6/DSH/guest TMPDIR；减少编译/解包占用 RAM |
| `/opt/dsh` | `$CHROOT_HOME/dsh` 或 rootfs baked | bind ro；宿主仅接受 `current/bin/dsh` 可执行，否则要求 baked 同路径可执行 | DSH 运行时；两者都缺失则拒绝启动 |
| `/opt/dsh-control` | `$CHROOT_HOME/control` | bind ro | hooks/s6/bin |
| `/root/.dsh` | `$CHROOT_HOME/state/dsh-home` | bind rw | DSH home |
| `/run/dsh` | `$CHROOT_HOME/state/runtime` | bind rw | URL/health/guard/ttyd.pass；holder 的 PID/ns/loop 等身份记录留在 `$CHROOT_HOME/state/guest`，不向 guest 暴露 |
| `/run/dsh/module.json`、`module.sha256` | `$CHROOT_HOME/state/guest` 启动期生成 | 单文件 bind ro | 模块版本、启动时间、配置模式和脚本哈希；不开放模块目录或活动镜像 |
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
| `/sys/kernel/debug` | debugfs | rw | 显式 mount；失败阻止启动，不静默降级 |
| `/sys/kernel/tracing` | tracefs | rw | 显式 mount/确认；失败阻止启动 |

### 5.4 控制面/镜像遮罩（1A）

在 private ns 中，等所有控制面 bind 完成后，对默认控制/镜像路径，以及实际 `DSH_ROOT`、`DSH_IMAGE_DIR` 和 media 别名（存在才做）挂空 tmpfs。默认路径包括：

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
- 遮罩只针对控制面、模块和镜像路径；厂商 profile 或其他子挂载可能进一步限制 App data，不承诺全部可读写。

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

- DSH **不读取、不修改、不判断**全局 SELinux；不运行 `getenforce`/`setenforce`，不保存/恢复 `selinux.orig`。
- 不把 SELinux 状态作为 start/stop/doctor/watchdog 的启动门。
- 不改全局 prop；不重置 `ro.*`/`init.svc.*`/`persist.*`。
- 不对宿主 selinuxfs 做任何 bind/remount；guest 内默认新建只读 selinuxfs，`mask` 仅显式选项。
- KSU 隐藏特性保持：`selinux_hide`/`mount_hide`/`avc_spoof`/`kernel_umount`。

### 6.2 域事实

`u:r:ksu:s0` 是 KernelSU 的 permissive + allow-all 域；它不受全局 Enforcing 约束。应用看不到其他进程的域，所以这不是 App 检测面；但**不能**说“SELinux 保证 chroot 安全/隐藏”。`SessionStart`/`UserPromptSubmit` 的风险提示与 `PostToolUse` 审计不拦截命令，也不是 root 安全边界；`/dev/block` 默认只读，原始写只在显式 `DSH_BLOCK_ACCESS=full` 开放；不能阻止其他宿主入口。

### 6.3 应用可见面

必须守住：

1. 不读取、不修改、不判断全局 SELinux；
2. 全局 prop 不变；
3. 所有 chroot mount 在 private ns；
4. KSU 隐藏特性不关；
5. DSH/ttyd 只 loopback；
6. 宿主 `/proc` 的 `hidepid=invisible`（本机已是）；
7. `/sdcard` 不放 `magisk`/`kernelsu`/`su` 之类明显标记。

`ubuntu-rootfs`/`DSH` 名字、loop 设备和宿主工作区路径是可接受的低强度信号；不为名字过度设计。详见 `hiddenness.md`。

---

## 7. 生命周期

### 7.1 start

- `start --apply` 先通过 doctor，检查路径、镜像、helper、实际 loop 查询和残留状态；不检查 SELinux。fsck 前、fsck 后及 attach 前都要证明镜像无 loop；查询失败不是空闲，已有附件时不得 fsck。原生 helper 的 `--claim` 让 DSH 自己的 start/rescue/snapshot/image 操作共用协作锁，fd 由 holder 继承；它不能阻止另一个 root 进程无视锁直接 attach 或写块设备，二次检查发现陌生 loop 时保留恢复信息，不能擅自 detach。
- 启动是带失败清理的事务：任何后续失败都尝试清理；清理不完整则为 `recovery-required`，保留 PID/starttime/ns/loop 和恢复脚本，不能继续 start/snapshot/purge。
- 不读取、不修改、不判断全局 SELinux；没有 `--permissive` 开关，也不在 stop/recover 中恢复 `selinux.orig`。
- `dsh-nsinit` 立即记录 holder PID/starttime/ns inode；先 `make-rprivate /`，再挂镜像和 Android 视图。rbind 后私有化、selinuxfs 只读视图和关键目录递归只读不能静默失败。
- holder 长驻并等待 s6 子进程；chroot 和 s6 都加载 `control/bin/dsh-android-env`，统一 Android 环境与 PATH。缺少 Java classpath 时，只从运行中的 `system_server` 环境提取 `BOOTCLASSPATH` 和 `DEX2OATBOOTCLASSPATH`，逐项限制 Android 路径下的 jar 并检查存在，不复制其他进程环境，也不写死某台 ROM 的路径列表。
- `/tmp/dsh-recovery` 保存受限恢复脚本、mount 记录和结果；正常 stop/recover 在对应 private namespace 内逆序卸载，不在宿主同名路径误卸载。
- 等待严格 loopback URL 和真实 HTTP 健康检查，复核 holder 身份及新鲜 heartbeat 后才进入 running。DSH 未就绪或健康检查失败是 degraded；其他启动失败进入清理事务。

### 7.2 stop / recover

1. 加锁并校验 holder 的 PID/starttime/ns inode；有 `launcher.bootid` 时也必须匹配当前内核 boot_id。zombie 不算存活，身份不符时拒绝 kill。锁的获取与 stale 回收由原生 `lock.guard` 串行化，owner 记录调用方 PID/starttime/ns 而非 helper 子进程；发布使用原子 `mkdir` 后写入并复核元数据，不依赖 `mv` 在目标目录已存在时的行为。进程信息不可读时不得误判为可清理。
2. `sync`，停止服务，使用 `s6-svscanctl -t` 结束监督树；holder 转发 TERM 并完成 remount-ro、按当前 mountinfo 深度逆序清理整棵 rootfs 子树和结果记录。mount stack 只用于越界校验，不是完整性的依据；父挂载卸载成功也必须复核子挂载。递归 bind 的子挂载普通卸载，不把 lazy unmount 当作成功。
3. holder 开始清理时写入 `cleanup.status`，之后忽略重复 TERM/INT；stop 有界等待清理完成，超时保留 stopping，不中断正在清理的 holder。正常 stop 不把 `kill -9 holder` 当作清理；需要强制结束时必须显式 `recover --apply`。
4. holder 已死时，recover 只把 mountinfo 仍含记录 rootfs 的进程视为本实例成员，可通过该成员进入 private namespace 执行 `dsh-recover-mounts`。namespace inode 可以复用，不能仅凭 inode 进入或结束进程。
5. 清理剩余实例进程，核对所有相关 loop 已 detach、没有仍挂载本 rootfs 的 namespace 成员、SELinux 已恢复。全部满足才写 stopped 并删除运行记录；不等待或清除被复用 namespace 中的无关应用。
6. holder 身份不符时，recover 不向该 PID 发信号，也不进入记录的 namespace；只有全局扫描证明没有进程挂载本 rootfs、目标镜像没有任何 loop 后，才可清除遗留运行记录。查询失败或仍有占用必须保留记录并返回 recovery-required。boot 只对 boot_id 明确属于上一次启动的记录执行同样的安全清理；旧版本没有 boot_id 时需要一次显式 recover，不能凭 PID 复用自动删除状态。
7. 任一步失败都返回非零并保留恢复信息。`recover --apply` 是显式强制恢复，可处理卡住的 holder；不得用于跳过正常 stop 或绕过数据校验。

### 7.3 宿主 watchdog

独立于 DSH/s6，30s 检查 holder 身份、loop backing、真实 DSH HTTP 健康和全局 Enforcing；stopped/failed/stale 也检查 loop 和 rootfs 残留。每轮检查与控制器写操作使用同一原生 lifecycle claim，避免与 stop/start 等操作交错写状态。原生 `--claim-wait` 只对有界锁竞争等待，子命令失败立即返回，不得把生命周期操作当成锁冲突重放。控制命令默认最多等待 120 秒；watchdog 周期检查使用零等待，遇忙跳过本轮，避免 stop 持锁期间 daemon 卡在锁助手子进程而无法处理 TERM。daemon 维护 PID/starttime 和原子 owner 锁；`--stop --disable` 持锁等待，缺失或不匹配的 starttime fail closed。sleep 失败必须退出循环，不得无节流自旋。输出独立写入 `watchdog.log`，重复行每 60 次至多报告一次，约 1 MiB 轮转并保留 3 代，当前及归档均为 0600。purge 以操作标记阻止新 claim，再持同一把生命周期锁完成 stop、cold/loop 复核和删除；失败清操作标记并恢复 watchdog，`dsh-watchdog --enable` 提供显式恢复，安装/更新也可启用。status/doctor 显示 disabled 或 purge-in-progress，不能把失败 purge 当作成功。

### 7.4 开机

- `service.sh` 先执行 boot 尝试，再启动 watchdog；`boot-completed.sh` 只记录，不重复启动。每次 boot 退出码与脱敏状态都写日志，首次失败后等待 30 秒并最多重试一次，不允许静默失败或无限重试。
- boot 等 `sys.boot_completed=1`，以及 `sys.user.0.ce_available` 的布尔真值（ROM 可返回 `1` 或 `true`）；已有 holder 时不重复启动。
- `rescue-needed` 抑制 autostart；`recovery-required` 只有在不能确认安全的跨重启清理时也抑制。连续失败 3 次进入 rescue-needed。
- 仅当 e2fsck 失败且存在已验证的 last-good 快照时，允许一次恢复尝试；网络/DSH 暂时失败不回滚用户系统。

### 7.5 命令面

- `start`/`rescue`/`system-rw on|off` 需要显式 `--apply`；`recover --apply` 是强制清理。
- `image create` 从目录生成镜像；`image install --source FILE [--sha256 FILE] [--replace] --apply` 校验并安装现有镜像，默认不覆盖。
- `snapshot create [NAME] [--last-good]`、`snapshot restore NAME --apply`、`snapshot promote NAME`；last-good 是快照名而不是当前镜像路径。恢复前备份和回滚前都校验 hash，成功后清 recovery 目录，pre-install/pre-restore 各保留最近 5 份。
- `guard on|off` 设置新会话的权限预设，重启 DSH/chroot 后生效；已有会话用 `/permission` 切换。
- `ttyd status|on|off`：默认关闭；启用时使用随机 0600 凭据和 loopback 认证。
- `purge --yes` 必须先 stop/disable watchdog，再在同一把生命周期锁内完成 stop、cold/loop 复核和删除；新 start 不能插入停止与删除之间。

---

## 8. DSH 运行时

- Node 24 与锁定的烘焙 DSH；`versions/<v>` + `current`/`previous`；`/opt/node`；`/opt/dsh`。正常模块安装不覆盖用户已升级的宿主 runtime、插件或 dsh-home，升级版本仍需单独验证。
- `dsh-run` 启动 `web --host 127.0.0.1 --port 0 --no-open`，严格校验 URL、写 0600 `/run/dsh/url`，执行真实 loopback HTTP 健康检查并写带时间戳的 heartbeat。URL 候选提取不依赖固定 `dsh web:` 前缀，但仍是日志回退而非原生机器接口。启动日志保留 5 代，审计按日保留 14 天。启动超时或连续健康失败会先 TERM 对应 web 子进程，等待最多 10 秒后仅 KILL 该登记子进程，由 s6 重启 DSH 服务；watcher 的失败状态不能被正常退出覆盖。健康恢复后可回到 running，status 不接受陈旧 heartbeat。
- `control/dsh/hooks.patch.yml` 定义 `approval-free`（full sandbox + `approval: never`）和 `approval-guard`（full sandbox + `approval: ask`），显示名分别为 `自动执行（不弹批准）`、`谨慎执行（逐项批准）`，机器 ID 不变。仅在 profile 能解析 permission-presets 时请求；缺少该条目时链接当前 runtime 的同版本包，仍不可用则明确选择 `hooks-audit-only.patch.yml`，审计依赖缺失时禁用 patch。`/run/dsh/hooks.status` 记录请求模式和原因，不证明上游加载成功。启动时由 guard 文件选择默认预设；新会话需要重启，已有会话通过 `/permission` 切换。
- `CLAUDE_PLUGIN_ROOT` 解析 hooks 路径，生产值为 `/opt/dsh-control`。固定版本的两种配置均已通过 `--dump-config` 并在 PC 隔离环境实际启动；手机端 guard/hook 路径已实测，真实模型会话和模型工具链调用仍未验证。
- `SessionStart` 和高风险 `UserPromptSubmit` 通过 `additionalContext` 提示块设备、活动镜像和全局 prop 风险；SELinux 状态不读取、不提示为门禁；`PostToolUse` 审计记录工具元数据，不默认保存完整命令、提示内容或凭据。PreToolUse 硬拦截已移除，提示可能错过首个请求，不能当作安全边界。
- 工作区固定使用独立宿主 `state/workspace`，默认绑定 `/root/workspace`；`/root/.dsh` 完全交给 DSH。安装在其它步骤全部完成后才原子迁移真实 legacy 目录，前期失败不改变旧路径；旧快照恢复采用同一职责分离，冲突或链接拒绝自动处理。`DSH_WORKSPACE_DIR` 可更改 guest 挂载点但不能覆盖整个 `/root` 或落入 `.dsh`，宿主来源不变。工作区不可用、目标为链接或迁移失败均阻止启动，不回退 sdcard 或 `/root`。保持 full 能力，不称为安全沙箱。
- guest `TMPDIR` 默认 `/var/tmp`（rootfs 磁盘），由 `DSH_GUEST_TMP` 配置；宿主 `DSH_TMP` 继续服务控制/恢复脚本。既有 `/tmp` 垃圾不自动删除，rescue 仍使用独立可写 tmpfs。配置和恢复入口见 `/opt/dsh-control/README.md`。
- `ttyd` 默认关闭。启用时必须有随机 0600 `state/runtime/ttyd.pass`、`ttyd -c dsh:...` 和 `127.0.0.1:7681`；缺失凭据不得启动匿名 root shell。

### 8.1 宿主 Agent（4B）

- Agent 使用 Kotlin Compose 页面和 `127.0.0.1:17321` + token 的 JSON Lines 协议。普通 App 不申请 root；可选 helper 在独立 root 进程监听 `127.0.0.1:17322`，共用 token，必须显式安装/启动，不开机启用。helper 提供受控系统命令和需 `confirm:"YES"` 的 `shell.exec`，不能把它称为无任意 shell。
- UI 按能力使用 root UiAutomation 或 AccessibilityService，不能取得的后端明确报 `SLOT_BUSY`、`NO_ROOT` 或 `UNAVAILABLE`，可回退路径带 `via`/`degraded`。没有强制停止其它 UiAutomation 占用者。
- 会话在服务端保存自有节点快照，支持默认会话、LRU、受限查询 DSL、flag、事件和作业。查询可读快照，写操作必须重新 resolve → verify → act；matchKey 不是身份，歧义/旧 epoch/屏外节点不得按旧坐标盲点。
- 恢复的快照只读；autosave 默认关闭，默认不落盘正文，密码掩码，优先 Keystore AES-GCM。事件每订阅者有界，写操作幂等去重，超时是未知结果，不盲重试。
- 备用机已实测 T0/T1、会话/查询、中文输入、双指手势、快照只读、事件/作业、剪贴板往返和 token 轮换；文件访问仍待用户授权。没有内置 OCR，不宣称真实验证码、任意 ROM 通用 UI 或所有危险 root 写操作均已验证。
- Console 是独立可选 APK，主界面和 WebActivity 使用 Kotlin Compose，中文界面适配系统栏，显示 su 退出码和状态原因，严格校验 loopback URL。DSH 可打开内置 WebView，也可通过 `ACTION_VIEW` 使用外部浏览器；内置页加载失败提供浏览器回退，不把白屏当作成功。命令使用独立输出读取线程和有界等待，超时提示后台可能仍在执行；“清理残留”只表示 `recover --apply`，不表示快照恢复。默认模块不含 APK、system overlay 或自动安装；`--with-console` 只附安装包，安装必须由用户明确选择。Console 与 holder/DSH 生命周期独立，关闭 Console 不会停止 chroot。

### 8.2 手机控制能力矩阵

见 `agent-app/README.md` 的接口分类和 `verification.md` 第 29 节的限定实测记录。接口存在、明确拒绝、读回通过、写入通过是不同事实，未达到写回读验证的项不能写“完整/完美”。

---

## 9. 快照、备份、恢复

- 冷快照必须先证明无 holder、无相关 namespace、无 loop，完成 e2fsck 和空间检查后，才允许 `cp --sparse=always`。复制后核对尺寸和内容哈希；不支持稀疏复制时明确失败，不静默变成满盘复制。
- 镜像和 state 都必须有 SHA256 manifest；缺失 manifest/hash、非法路径、损坏归档或不完整目录均拒绝恢复。
- 新镜像先写同目录临时文件并校验，再原子替换；原镜像用硬链接或稀疏副本保留。state 先解包验证，再替换 dsh-home/guest；中途失败同时回滚镜像与 state。锁和运行时 PID/URL 不从快照复活。
- 保留 7 份普通快照，last-good 单独保护。`--last-good` 或 `snapshot promote` 只能指向已校验的快照。
- 镜像恢复只有在校验失败时才由 boot 尝试一次；失败进入人工 rescue，不反复覆盖。
- `/sdcard/DSH` 的 restic 是独立备份计划，不等同于 rootfs 快照；`/data` 和 `/sdcard` 的其他内容不在承诺范围。
- 卸载/更新/purge 前必须 stop/recover 成功；purge 还要检查所有镜像、快照和备份的 loop 占用。

---

## 10. 打包与工具链

- 模块 zip 包含 `dsh-recover-mounts`、全部生命周期库、控制面和 Android NDK helper；不包含 rootfs payload 或 `install.sh`。
- helper 默认使用 `DSH_NDK_VERSION=28.2.13676358`，可用 `ANDROID_NDK_HOME` 指定；必须验证 ARM64 和 Android bionic/静态链接，不能把宿主 glibc 二进制带入包。
- `tools/SHA256SUMS` 由真实二进制生成并在打包时验证；zip 权限由 `build/verify-module.py` 统一规范化并读回检查。
- 工具能力：util-linux `losetup` 优先，toybox 回退需明确警告；`mke2fs/e2fsck`；冷镜像用随包 `debugfs` 只读检查 guest Python 和 `/tmp`；固定版本并校验的 GNU `cp --sparse=always` 及运行库，复制后核对尺寸和内容哈希；`unshare`/`nsenter` 使用已实现的 toybox/util-linux 回退；guest 命令需要 `timeout -k`。
- payload 由 `bake/build-rootfs.sh` 生成：Ubuntu 24.04、Node 24、固定 DSH 及锁定依赖、Go/Python/build-essential、s6 和可选 ttyd。Node tar 包核对官方 SHA256，npm 使用 lockfile。
- 默认不含 Console 或 `system/priv-app`；`--with-console` 只在根目录附带 APK，安装由用户显式选择，不由 `customize.sh`/`service.sh` 自动执行。
- `customize.sh` 只建目录、复制 control 和设置权限；不解压 payload；更新前必须 stop/recover。
- `action.sh` 当前只读 `status`；长任务/启停动作由 `dsh-ctl` 显式执行，不把 action 按钮变成隐藏的自动启动。

---

## 11. 验收

### 11.1 功能 P0

1. chroot 内 `id`=0、`uname -r` 与宿主一致；Go 在 `/sdcard` 编译并运行。
2. Node 24 + node-pty PTY；`dsh web --port 0 --no-open` URL；`approval-free` 可用。
3. `/data` rbind：`/data/user/0`、`/data/data`、`/data/system` 可见；`/mnt`、`/data_mirror`、`/storage`、`/sdcard` 完整。
4. 默认 `DSH_BLOCK_ACCESS=ro` 时 `/dev/block` 只读可见；显式 `DSH_BLOCK_ACCESS=full` 时才验收可写。binder/ashmem、`/dev/pts`、`/dev/shm` 可见；`/sys` rw；debugfs/tracefs 可读。
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
| P1 | 静态 module + `dsh-ctl doctor/plan/status`；不 apply | PC 检查完成 |
| P2 | 备用机/VM：mount/ns/stop/loop；零残留 | 备用机 128GB chroot、20 次启停和零残留通过 |
| P3 | DSH/Node/s6/URL/工作区；快照/回滚 | 备用机运行时、快照/回滚和真实开机通过；模型会话未验证 |
| P4 | full Android 视图 + 宿主 agent 手机控制 | PJE110 已实测 Agent T0/T1、会话/DSL、中文输入、事件/作业及剪贴板往返；文件访问未授权，其它 ROM 未验证 |
| P5 | 模块打包、开机、迁移、检测矩阵 | 备用机正式安装/开机通过；检测、迁移和 purge/uninstall 未验证 |

---

## 13. 未验证/开放项

这些不是文档冲突，是事实未验证：

- Agent 已在 PJE110 实测，不等于其它 ROM 或主力机的兼容性；不同 ROM/AVB 下 system-rw 的兼容性也未验证；
- `uiautomator runtest`（需 jar）和不同厂商 UI 的通用自动化；内置 OCR 未实现，截图由调用方 OCR；
- Agent 文件访问尚未手动授权，相关读写/分享未验收；任务分屏/PiP、可靠 shell-only IME show 在该 ROM 不可用；
- 真实短信/联系人/日历私人数据，真实 PIN/锁屏密码修改、位置 mock、reboot、卸载和清数据等危险写操作；
- frida/stackplz、`/proc/kallsyms`、`/proc/<pid>/mem`；
- 真实模型账号会话和模型工具链调用；真实验证码流程；
- 断电恢复、真实 purge/uninstall、KSU 特有隐藏特性；
- 银行/游戏/Play Integrity 的实际检测矩阵。

**备用机已验证的项目不等于“完美”或绝对不会死机；不得外推到未实测的主力机。**
