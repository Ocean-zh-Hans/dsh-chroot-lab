# Chroot Core 专项学习（v2）

> 只读研究文档。日期：2026-10-30。范围：`knowledge/` 与 `references/` 中与 chroot、mount namespace、Android root、ext4/loop、Ubuntu rootfs、服务监督相关的机制和实现。
> 本文不改运行时代码、不碰真机；引用格式为 `相对路径:行号` 或 `文档路径 §章节`。结论中的“我们”指本项目的 chroot 核心，不包括另行重设计的 Agent app。

> **概念边界（2026-10-30 用户确认）：本项目坚持“全手机可读、全手机分级写、默认操作者是善良但粗心的模型”。知识库/参考项目的 sandbox、read-hiding、默认 `masked` 和威胁模型只作参考，不能覆盖本目标。**

## 0. 结论（先看这十条）

1. **chroot 只是路径视图，不是安全边界。** mount namespace、PID/net namespace、capability、SELinux 域都不会因为 `chroot(2)` 自动改变；真正的隔离来自私有 mount namespace、能力收缩和外部生命周期管理。`knowledge/01-kernel/03-chroot-and-escape.md`。
2. **读全开与写分级必须分开设计。** “读任意内容”是产品能力，不是安全 isolation；它不能替代写保护、备份、审计和 rollback。`knowledge/06-runtime/05-security-boundaries.md` 与 `references/DSH-Chroot/docs/threat-model.md` 都支持这一点。
3. **所有 mount 必须先 private，再 bind。** mount namespace 创建时复制父挂载树，传播默认可能是 shared；不先 `mount --make-rprivate /`，容器内挂载/卸载可能传播回宿主。`knowledge/01-kernel/02-mount-and-propagation.md`。
4. **挂载顺序是依赖图，不是命令清单。** loop/fsck → rootfs → `/proc`、`/sys`、`/dev` → `/dev/pts`、`/dev/shm`、`/run`、`/tmp` → bind → 服务；停止必须反序，最后 detach loop。`knowledge/06-runtime/02-mount-order.md`。
5. **`ro` 要验证，不能只看 mount 返回码。** Android/toybox 的 `mount -o bind,ro` 可能静默不生效；DSH-Chroot 的 `rt_make_ro` 会在 `/proc/mounts` 中复核。`references/DSH-Chroot/service.sh:226-240`。
6. **rootfs 镜像和快照都是“数据”，要按数据管理。** 冷快照、`fsync/rename`、`e2fsck` 退出码、SHA256、备份保留和恢复前备份缺一不可；热复制最多算崩溃一致。`knowledge/05-storage/03-snapshots-and-consistency.md`、`knowledge/05-storage/04-fsck-and-recovery.md`。
7. **Android 的 `/data`、`/sdcard`、APEX、FBE 不是普通 Linux 目录。** 镜像应放 `/data`，不要放 `/sdcard`；`/sdcard` 在部分版本是 FUSE 视图，本机实测是 `/data/media/0` 的 bind，而 `/storage/emulated/0` 才是 FUSE；APEX 只读；`/data` 本身在解锁前已挂载，但 CE 部分 `/data/user/<id>` 首次解锁前不可读（DE、`/data/system`、`/data/misc` 可更早读）；SELinux 域不会因 chroot 改变。`knowledge/02-android/03-storage-and-fbe.md`、`knowledge/03-root/08-chroot-on-android.md`。
8. **服务监督要区分 liveness/readiness/startup。** 进程存在不等于健康；端口监听不等于可服务；健康检查必须有超时、有限重试、退避和可行动日志。`knowledge/06-runtime/03-health-checks.md`、`knowledge/06-runtime/04-service-managers.md`。
9. **参考项目的价值在“踩坑记录”，不在代码量。** DSH-Chroot 的 A1–A23 坑、ADR-017 卸载残留清单、ADR-019 入口约束、ADR-021 凭据治理，比直接复制它的 chroot-distro 依赖更有价值。`references/DSH-Chroot/docs/pitfalls.md:10-135`、`references/DSH-Chroot/docs/decisions.md:605-...`、`738-...`、`931-...`。
10. **不要东施效颦。** 我们的目标（读全开、写粗心好人、宿主生命周期）与 DSH-Chroot（DSH 应用壳、chroot-distro 依赖）、dsh-alpine-ksu（UID1 非 root 运行）不同；要吸收机制和失败模式，不能照搬权限模型、目录布局或版本策略。

## 1. 阅读范围与方法

### 1.1 阅读进度（诚实记录）

| 范围 | 已通读 | 待读/抽读 |
|---|---|---|
| 总览 | `knowledge/README.md`、`knowledge/SOURCES.md`、`00-overview/01-technology-map.md`、`02-reading-guide.md`、`03-glossary.md`、`04-reference-implementations.md` | — |
| 内核 | `01-namespaces.md`、`02-mount-and-propagation.md`、`03-chroot-and-escape.md`、`04-loop-and-block.md`、`05-filesystems.md`、`06-proc-sys-dev.md`、`07-selinux.md`、`08-capabilities-and-gki.md`、`09-debugging.md` | — |
| Android | `01-boot-and-init.md`、`02-partitions-and-apex.md`、`03-storage-and-fbe.md`、`04-app-sandbox.md`、`05-selinux-and-properties.md`、`06-shell-and-tools.md`、`07-binder-and-ipc.md`、`08-root-detection.md`、`09-version-differences.md`、`10-adb-and-fastboot.md` | — |
| Root | `03-root/01-root-fundamentals.md`、`02-kernelsu.md`、`03-sukisu.md`、`04-magisk.md`、`05-module-format.md`、`06-hiding-mechanisms.md`、`07-su.md`、`08-chroot-on-android.md` | — |
| Ubuntu | `04-ubuntu/01-ubuntu-24.04.md`、`02-rootfs-build.md`、`03-apt.md`、`04-image-layout.md`、`05-s6.md`、`06-network-and-toolchain.md`、`07-locale-and-users.md` | — |
| 存储 | `05-storage/01-ext4-image.md`、`02-sparse-and-copy.md`、`03-snapshots-and-consistency.md`、`04-fsck-and-recovery.md`、`05-performance.md` | — |
| 运行时 | `01-process-and-mount-lifecycle.md`、`02-mount-order.md`、`03-health-checks.md`、`04-service-managers.md`、`05-security-boundaries.md` | — |
| Playbook | `01-safe-testing.md`、`02-boot-failure.md`、`03-mount-and-loop-failure.md`、`04-command-cheatsheet.md` | — |
| 参考 | `08-reference/01-syscalls.md`、`02-mount-options.md`、`03-android-paths-properties.md`、`04-bibliography.md` | — |

> 上表是首轮通读记录：所有列均标为“已通读”，但不等于逐条都已二次核对；本文件只对已核对内容做确定性引用，其余列入二次精读。

### 1.2 参考项目

| 项目 | 本地路径 | commit | 读法 |
|---|---|---|---|
| DSH-Chroot | `references/DSH-Chroot` | `2b42ecc` | README、pitfalls、threat-model、decisions 关键 ADR；`service.sh` 与 `dsh-ctl` 全文 |
| dsh-alpine-ksu | `references/dsh-alpine-ksu` | `b971c38` | README、service/启动链、安全说明 |
| lhroot | `references/lhroot` | 无 git | README、操作流程/风险提示 |
| chroot-distro | `references/chroot-distro` | `eaa7368` | README、安装/挂载模型 |
| chroot-mcp-safe | `references/chroot-mcp-safe` | 无 git | README 的挂载点/高危区/三层保险 |

> 本文只记录“对方怎么做、为什么、哪些能借鉴、哪些不适用”。所有“应该”都标成建议，不冒充机制事实。

## 2. 机制地图

### 2.1 chroot、namespace 与逃逸

- `chroot(2)` 改变进程的 root/cwd 解析起点，但不改变 mount namespace、PID namespace、network namespace、UID、capability 或 SELinux 域。`knowledge/01-kernel/03-chroot-and-escape.md`。
- 逃逸面：
  - 进程在 `chroot` 前打开的 fd 指向根外，`fchdir`/`openat` 可回到宿主；
  - cwd 在根外；
  - `/proc/<host-pid>/root`；
  - `CAP_SYS_ADMIN` 下的 mount、`CAP_MKNOD` 下的 mknod、`CAP_SYS_PTRACE` 或目标 ptrace 权限下的 ptrace；
  - 写 `/proc/sys`、`/sys`、`/dev` 等宿主接口；
  - `pivot_root`/`mount --move` 的传播错误。
- 结论：本项目的私有 mount namespace、`make-rprivate /`、入口/生命周期控制是必需项，不是“可选加固”。

### 2.2 mount 顺序与传播

`knowledge/06-runtime/02-mount-order.md` 的依赖图可压成：

```text
loop attach → e2fsck → rootfs mount
  → /proc, /sys, /dev
  → /dev/pts (devpts), /dev/shm (tmpfs), /run, /tmp
  → /data and other rbind/bind
  → service/s6
```

关键点：

- `mount --make-rprivate /` 要在任何 bind 之前。
- `/proc` 必须是新 proc 实例；不要 bind 宿主 `/proc` 后 `nsenter`，否则看到宿主 PID 视图。
- `/dev` 不要整棵 rbind；最小 tmpfs/devtmpfs + 必要节点；`/dev/pts` 单独 devpts；`/dev/shm` 单独 tmpfs。
- bind 不复制数据，源/目标共享 inode；`bind,ro` 只限制该挂载点，底层设备可能仍 rw。`knowledge/08-reference/02-mount-options.md`。
- 卸载按子挂载→根→loop 的逆序；`umount -l` 只是 lazy，不是强制完成；`losetup -D` 会 detach 所有 loop，禁止作为清理手段。

### 2.3 ext4、loop、sparse

- ext4 创建：`truncate` 出文件 → `mke2fs -F -t ext4 -b 4096 -I 256 -d rootfs image` → `e2fsck -fn` 校验 → sparse copy。
- `e2fsck` 退出码：0 无错、1 已修复、2 已修复需重启、4 未修复需人工、8 操作错误、16 用法错误、32 被取消、128 共享库错误；自动化只可对 0/1/2 继续，4 以上拒绝启动。
- `losetup --find --show --partscan`；`dsh_loop_attach_record` 目前只原子写入 `losetup --show` 返回的 `/dev/loopN` 路径。`kernel:loopN` 只是 sysfs 名字且 loop 号可复用，不是跨 attach 的持久身份；应记录 `dev_t`/major:minor + backing image，detach 前校验，最后关闭 fd 或显式 `losetup -d`。
- sparse 文件 `du` 与 `ls -l` 大小不同；快照空间估算必须按实际 `du`，不能只看 apparent size。
- 冷备份/快照前必须停写、`sync`、卸载或 `fsfreeze`；热复制只保证崩溃一致，不保证应用一致。`knowledge/05-storage/03-snapshots-and-consistency.md`。

### 2.4 Android 特殊性

- 启动：init → first stage → second stage → zygote/system_server；`/data` 在 FBE 解锁前已挂载，但 CE 部分 `/data/user/<id>` 首次解锁前不可读，DE 和 `/data/system`、`/data/misc` 可更早读；`/sdcard` 在部分版本是 FUSE 视图；本机实测 `/sdcard` 是 `/data/media/0` 的 bind，`/storage/emulated/0` 才是 FUSE。
- APEX 是只读挂载，不能当普通目录改写；系统分区通常 dm-verity/只读。
- Android 的 `toybox` 不是 GNU coreutils：`mount` 的 `-o bind,ro` 可能静默失败，`sed/awk` 字段行为、`stat` 选项也不同。
- SELinux：用户构建通常不能可靠 `setenforce 0`；chroot 不改变域/标签；`/sys/fs/selinux` 的 ro 挂载只限制该挂载点，不保护全局。
- 结论：本项目不读写全局 SELinux；如果 guest 需要 selinuxfs，只提供只读视图/显式开关，不把它当安全边界。

### 2.5 服务监督与健康

- PID 1 要回收僵尸、处理信号；chroot 内 s6/s6-svscan 可以作为普通进程/监督器，但不要假设 systemd。
- liveness/readiness/startup 必须分开：启动慢不能误杀；进程活着不等于能服务；端口监听不等于业务可用。
- 健康检查必须有超时、有限重试、退避和可行动日志；健康端点不应依赖外部服务。
- s6 的 `down` 文件、`finish` 退出码 125、`s6-svwait -U` 等语义要写进运行脚本；不要用 `sleep` 猜就绪。

## 3. 参考项目深读

### 3.1 DSH-Chroot（SunsetRNE/DSH-Chroot，`2b42ecc`）

**定位**：Android 上的真 chroot 模块，依赖 `sabamdarif/chroot-distro` 做发行版安装；DSH 是上层应用壳，模块只负责 rootfs/生命周期/端口/文件通道。`references/DSH-Chroot/README.md`。

**值得学的结构**：

- `dsh-ctl` 是公开入口，`service.sh` 是唯一执行者；`version_gate`/`version_state` 把“模块版本、payload 版本、DSH 版本”分开，版本只设 floor，不把 DSH 版本编进 module.prop 的第四位。`references/DSH-Chroot/service.sh:118-181`。
- 挂载不是 `mount --rbind /` 一把梭：`rt_mounts_up` 先 `rt_bind` Node 层/DSH 层/DSH home，再挂 `/proc`；然后 `mount -t tmpfs tmpfs "$root/dev"`（**tmpfs，不是 devtmpfs**），`mknod` `null/zero/full/random/urandom/tty/ptmx`，最后挂 `devpts`；`rt_mounts_down` 逆序卸载。`references/DSH-Chroot/service.sh:266-289`。
- **`rt_make_ro` 的验证思路**：依次尝试 `mount -o remount,ro,bind`、`mount -o remount,ro`、`busybox mount -o remount,ro,bind`，每次后用 `rt_is_ro` 读 `/proc/mounts` 复核；全部失败才 `return 1`。它没有先 `mount -o bind,ro`，也不重新 bind；源注释只说明 toybox 的 `bind,ro`/`remount,ro,bind` 会静默失败。`references/DSH-Chroot/service.sh:226-239`。
- `rt_bind` 对 ro 失败不是静默：记录 `layers-rw` 并 `WARN`；这是“可诊断的降级”，不是“假装成功”。`references/DSH-Chroot/service.sh:241-255`。
- `rt_reader` 只把第一条含 `http://127.0.0.1:` 的启动行写入结果 fifo，然后 `cat >/dev/null` 吸干剩余输出；仓库中没有 `DSH_READY`/`DSH_TOKEN`。`rt_real_start` 在 `service.sh:364-368` 从该行解析 port/token，并在 `service.sh:373-378` 把 token 写入 `$RUN_DIR/dsh.json`（0600），所以“token 不落盘”不对。`references/DSH-Chroot/service.sh:293-301`。
- 文档里有大量“坑”：A1 不要先动内核；A2 chroot-distro 不是模块；A4 有 root 不要套 proot；A5 不要猜端口；A6 payload 不要塞模块 zip；A8 容器内 ro/chmod 不是安全边界；A20 Android mksh 不能假设 bash `${x%%|*}`；A23 模块根不要放 `install.sh`。`references/DSH-Chroot/docs/pitfalls.md:10-135`。
- ADR-017 卸载残留面清单（七处）和“模块域清空、用户空间保留”；ADR-019 入口三层结构与“入口必须避开 bind 子树”；ADR-021 凭据出容器/AI 降权。`references/DSH-Chroot/docs/decisions.md:605-...`、`738-...`、`931-...`。

**不能照搬**：

- 它依赖 `chroot-distro` 的在线安装/多发行版体系；我们的模块自包含，不引入这层依赖。
- 它的 DSH payload/版本是“应用壳 + 外部运行时”，我们的 chroot 核心是宿主生命周期 + 用户 rootfs；状态模型不同。
- 它的 `rt_bind` 选择“记录降级继续”，我们的 P1 读可见性要求 `null`/`ro` 明确，不能让“失败但继续”变成静默不安全。

### 3.2 dsh-alpine-ksu（`b971c38`）

- 架构：KernelSU module → `service.sh` 立即返回 → `bin/dsh-service-boot` 后台 bootstrap → `dsh-watchdog` → `dsh-launch`（mount + chroot + `setpriv`）→ `dsh-supervisor`（UID 1）管理 DSH/ttyd。`references/dsh-alpine-ksu/README.md`。
- 安全模型：DSH/ttyd/supervisor 以 UID 1 运行，无 capabilities；只监听 127.0.0.1；token + Cookie + Host/Origin 校验；ttyd 随机 Basic Auth。它是“降权”路线，不是“读全开”路线。
- 值得学：安装器只建目录和脚本，首次开机后台解压，避免 KernelSU 安装卡住；`service.sh` 非阻塞；watchdog 自动重启；16KB page-size 检查；WebUI 诊断。
- 不适用：我们的目标是 root 读全开，不能把 UID1 模型照搬；但可以借鉴“DSH 进程非 root、宿主 root 只做 mount/lifecycle”的分层。

### 3.3 lhroot（无 git）

- 最小安装器：`make_image`、`mount_image`、`bootlinux`、`killlinux`；支持多实例；明确警告“删除 chroot 目录前必须卸载所有 mountpoint”。`references/lhroot/README.md`。
- 价值：提醒**卸载/清理顺序是用户数据安全的核心**；`purge`/`uninstall` 必须先检查 mountpoint，不能 `rm -rf` 一个仍有子挂载的目录。我们的 `dsh_purge_guard` + `dsh_snapshot_require_cold` 方向正确，但应补“子挂载/loop 在别的 namespace”的检查。

### 3.4 chroot-distro（`eaa7368`）

- 多发行版安装/管理，功能全、抽象多；适合当“发行版安装器”的参考，不适合作为我们核心模块的依赖。
- 教训：不要为了“多发行版”把生命周期、挂载、状态耦合进一个大型 shell 框架；我们的模块应保持单一 rootfs + 明确状态机。

### 3.5 chroot-mcp-safe（无 git）

- 完整 Android 文件系统视图 + “高危区主动加固”：`/system`、`/vendor`、`/product` 等强制 ro；`/data` 的 persist/qmcs 等敏感子目录单独 ro；`rbind` 后 make-rprivate；卸载完整性检查。
- 值得学：高危挂载点白名单/黑名单要写进配置和测试；只读不是“chmod”，而是挂载层 + 验证；`rbind` 子树必须传播隔离。
- 不适用：它的目标是给 Agent 一个“完整 Android 视图 + 防变砖”，不是我们的宿主生命周期；但它的“高危区列表”可作为我们 `DSH_PROTECT_CRITICAL` 的参考。

## 4. 横向对比

| 维度 | DSH-Chroot | dsh-alpine-ksu | lhroot | chroot-distro | chroot-mcp-safe | 我们 v2 |
|---|---|---|---|---|---|---|
| 目标 | DSH 应用壳的 chroot | Alpine + DSH PoC | 通用 chroot 安装器 | 多发行版管理器 | Android 全视图 + 防变砖 | 宿主 chroot 生命周期 + 读全开/写分级 |
| rootfs | chroot-distro 安装 | Alpine tarball | 多发行版 tarball | 多发行版 | Ubuntu 镜像 | 自包含 Ubuntu 镜像 |
| mount ns | 共享宿主/未实现（README:33 明确无 mount namespace） | 私有（`unshare -m`） | 共享宿主/未实现 | 共享宿主/未实现 | 私有 + rprivate（`unshare --mount --propagation private`） | 私有 + rprivate |
| 特权 | root 模块 | UID1 运行 DSH | root | root | root | root（读全开），写分级 |
| 监督 | service.sh + dsh-ctl | watchdog + supervisor | shell 脚本 | 脚本 | shell | s6 + watchdog + 生命周期锁 |
| 读策略 | 应用层 | 完整 `/sdcard` bind | 完整 bind | 发行版默认 | 全 Android 视图 | 读全开，不 masking |
| 写策略 | 应用层 + ro bind | 用户空间 rw，系统分区 ro | 用户自己负责 | 用户自己负责 | 高危区强制 ro | R0–R4 分级 + checkpoint |
| 安全声明 | 明确“非沙箱” | 明确 UID1 边界 | 警告 | 弱 | 防变砖 | 明确“读不是安全边界，写靠流程” |

## 5. 对我们 chroot v2 的设计建议

### 5.1 不变量（建议写入 plan-v2）

1. **读全开**：root 在 chroot 内可读宿主所有可读内容；DSH 不因“安全”隐藏 `/data`、`/system`、`/dev/block` 等；需要时用显式只读视图，而不是读拒绝。
2. **写分级**：R0 无限制的新建/自有 state；R1 普通用户文件覆盖轻确认；R2 `/data/data` 普通 App 私有数据 AI 敏感审批（备份 + reason + 精确路径）；R3 系统/生命周期/系统关键数据 AI 重大审批；R4 原始块/分区/`dd`/`mkfs` 人工。
3. **SELinux 不参与**：DSH 不读、不写、不判断全局 SELinux；不保存/恢复 `selinux.orig`；guest 的 selinuxfs 默认只读视图或显式关闭。
4. **mount 私有**：任何 mount 前 `make-rprivate`；禁止全局传播；host 与 guest 的 mount 生命周期由同一 holder 管理。
5. **状态单一真相**：image/loop/rootfs/namespace PID/starttime/boot id 记录在一个 atomic state；不允许“记录成功但资源已 attach”的半状态。
6. **可恢复**：启动/停止/恢复是事务；失败必须留下可诊断状态和手动 `recover --apply` 路径，不静默降级。
7. **可观测**：status/health/diag 分层；日志脱敏；health 检查区分 liveness/readiness/startup。

### 5.2 具体机制设计

- **mount 路径解析**：在 C helper 里用 `openat2(RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS)` 或 `realpath` + `strncmp(root)`；不要只做字符串 `case "$p" in "$root"/*)`。当前 `module/lib/mount.sh:17-62` 已有 `dsh_mount_realpath_under`，`dsh_mount_prepare_point` 在 mkdir 前后各检查一次；`dsh-nsinit:419-445` 对 `/etc`、`resolv.conf`、`hosts` 做 realpath/符号链接拒绝。剩余风险是 TOCTOU 竞态，建议 C helper/openat2 继续加固。
- **loop 记录**：`dsh_loop_attach` 成功后立刻写 `loop.file.tmp` + `mv`；写失败必须 `dsh_loop_detach`；不要先 attach 再慢慢写状态。
- **快照归档**：tar 校验不能只检查 entry 名；symlink 的 target 必须解析并拒绝绝对/`..`/超出 stage 的路径；解包用 `--no-same-owner --no-same-permissions` 或专用 helper，不要直接 `tar -xzf` 到可写目录。
- **watchdog**：watchdog 只做“宿主视角”的 liveness/残留检查；不依赖 guest 内 s6 状态；手动 start 也应有 watchdog（可在 start 成功后尝试拉起，失败只 warn，不阻断启动）。
- **status/health**：`status` 不应打印带 token 的 URL；health 检查不只看 body 含 `dsh`，至少校验 HTTP 200 + JSON/固定端点；区分 `starting`/`ready`/`degraded`。
- **卸载/清理**：先检查 `/proc/*/mountinfo`、`losetup -j`、子 mount、rootfs namespace；任何残留都拒绝 `purge`/`rm -rf`，给出精确命令。
- **版本/状态分离**：模块版本、rootfs 版本、DSH payload 版本、schema 版本分开；不要用 module.prop 第四位承载所有信息（DSH-Chroot A21 的坑）。

### 5.3 不建议做的事

- 不要引入 `chroot-distro` 作为运行时依赖；不要把多发行版管理器塞进核心模块。
- 不要用 `mount -o bind,ro` 后不验证；不要用 `losetup -D` 清理；不要整棵 rbind `/dev`。
- 不要把“读全开”解释成“无审计/无边界”；读也要有日志、速率限制和敏感内容处理（至少不把 token 写进 status/日志）。
- 不要复制 dsh-alpine-ksu 的 UID1 模型到我们的 root 读全开目标；可以借鉴“运行 DSH 的进程降权，宿主 root 只做 mount/lifecycle”。

## 6. 当前代码与学习结论的差距（只列方向，不是本次修改）

| 主题 | 学习结论 | 当前代码状态/风险 |
|---|---|---|
| rootfs symlink 路径 | 所有 mount target 必须 realpath 包含在 rootfs 内 | `module/lib/mount.sh:17-62` 已有 `dsh_mount_realpath_under`，mkdir 前后各检查一次；`module/bin/dsh-nsinit:419-445` 对 `/etc`、`resolv.conf`、`hosts` 做 realpath/符号链接拒绝。仍有 TOCTOU/竞态窗口，建议 C helper/openat2 加固 |
| snapshot 归档 symlink | 校验 symlink target，不可只校验 entry 名 | `dsh_snapshot_validate_archive` 已检查绝对路径/`..` 和 symlink/hardlink target；剩余 gap：未拒绝设备节点/FIFO，restore 直接 `tar -xzf` 无 `--no-same-owner`/覆盖控制，`tar -tvzf` 对含空格/换行文件名有解析歧义 |
| loop 记录原子性 | attach 后立即写记录，失败 detach | 已加 `dsh_loop_attach_record`，需补注入测试并跑回归 |
| watchdog 启动 | 手动 start 也应有 watchdog；watchdog 覆盖备份/快照 image loop | `service.sh` 的 autostart 路径只在 `autostart=1` 时启动；`dsh-ctl:76` 在手动 start 成功后已调用 `dsh_watchdog_start_bg`。需分别回归两条路径 |
| status/health | URL/token 不落日志；health 不是 substring | `status` 已做 token 脱敏，`dsh-run` 仍用 `b'dsh' in body` 弱检查 |
| 服务日志故障 | 日志轮转失败不应阻断 boot | `module/service.sh:13-14` 已改为失败仅打印继续；仍保留 `exit 1` 的是 `module/boot-completed.sh:12`，应改到该处跟踪 |
| config path | `DSH_TMP` 校验、image 在 image dir、rootfs realpath | `dsh_config_check_paths` 仍有缺口；P3-3 已部分改，需测试 |
| ttyd 状态 | 模块更新不能重置用户 ttyd off/on | `module/customize.sh:80-87` 已先读旧 `s6/ttyd/down`，复制后按旧状态恢复；应改为“已修复，待回归” |

## 7. 后续学习与二次精读清单

### 7.1 知识库二次精读重点（首轮已通读）

- 优先复核 `01-kernel/02-mount-and-propagation.md`、`03-chroot-and-escape.md`、`04-loop-and-block.md` 的版本限定与内核 6.6 行为。
- 复核 `02-android/03-storage-and-fbe.md`、`05-selinux-and-properties.md`、`09-version-differences.md` 与目标机 Android 15 的实际差异。
- 复核 `05-storage/03-snapshots-and-consistency.md`、`04-fsck-and-recovery.md` 的 fsck/恢复边界。
- 复核 `06-runtime/02-mount-order.md`、`05-security-boundaries.md` 与 plan-v2 的 R0–R4 对应关系。

### 7.2 参考项目待深读

- `references/DSH-Chroot/service.sh` 全文（本次已读关键函数，待逐行）；`dsh-ctl`、`lib/errors.sh`、`lib/semver-gate.sh`。
- `references/dsh-alpine-ksu` 的 `service.sh`/`boot-completed.sh`/`scripts/build-rootfs.sh`/`scripts/finalize-rootfs.sh`。
- `references/chroot-distro` 的 `config.sh` 和挂载/安装脚本（看复杂度边界）。
- `references/chroot-mcp-safe/chroot-mcp-safe.sh` 的高危挂载/卸载实现。

### 7.3 待验证问题（host fixture，不碰真机）

1. `openat2(RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS)` 在目标内核 6.6 的可用性；没有时 fallback `realpath`。
2. `mount_setattr(AT_RECURSIVE)` 在 Android 15/6.6 的行为；失败时如何安全降级。
3. ext4 16KB page-size 镜像在目标设备的 mount/exec 行为。
4. `e2fsck` 在 `/data` 大镜像上的耗时/内存；是否要 `-E` 选项/只读检查。
5. s6 在 chroot 内作为 PID1 的僵尸回收/信号行为；与宿主 `setsid`/`nsenter` 的交互。
6. `/dev/block` 只读 bind 对 `dd` 读取、`losetup`、`blkid` 的可见性。
7. watchdog 跨 namespace 检测 rootfs 进程的准确性（`/proc/*/mountinfo` vs `nsenter`）。

## 8. 参考索引

- 知识库总览：`knowledge/README.md`、`knowledge/00-overview/01-technology-map.md`、`02-reading-guide.md`、`03-glossary.md`
- 内核：`knowledge/01-kernel/01-namespaces.md`、`02-mount-and-propagation.md`、`03-chroot-and-escape.md`、`04-loop-and-block.md`、`05-filesystems.md`、`06-proc-sys-dev.md`、`07-selinux.md`、`08-capabilities-and-gki.md`
- Android：`knowledge/02-android/01-boot-and-init.md`、`02-partitions-and-apex.md`、`03-storage-and-fbe.md`、`04-app-sandbox.md`、`05-selinux-and-properties.md`、`06-shell-and-tools.md`
- Root：`knowledge/03-root/01-root-fundamentals.md`、`02-kernelsu.md`、`03-sukisu.md`、`05-module-format.md`、`08-chroot-on-android.md`
- Ubuntu：`knowledge/04-ubuntu/02-rootfs-build.md`、`04-image-layout.md`、`05-s6.md`、`06-network-and-toolchain.md`
- 存储：`knowledge/05-storage/01-ext4-image.md`、`02-sparse-and-copy.md`、`03-snapshots-and-consistency.md`、`04-fsck-and-recovery.md`
- 运行时：`knowledge/06-runtime/01-process-and-mount-lifecycle.md`、`02-mount-order.md`、`03-health-checks.md`、`04-service-managers.md`、`05-security-boundaries.md`
- Playbooks：`knowledge/07-playbooks/01-safe-testing.md`、`02-boot-failure.md`、`03-mount-and-loop-failure.md`、`04-command-cheatsheet.md`
- 参考：`knowledge/08-reference/02-mount-options.md`、`03-android-paths-properties.md`
- DSH-Chroot：`references/DSH-Chroot/README.md`、`docs/pitfalls.md`、`docs/threat-model.md`、`docs/decisions.md`、`service.sh:118-318`
- dsh-alpine-ksu：`references/dsh-alpine-ksu/README.md`、`service.sh`、`boot-completed.sh`
- lhroot：`references/lhroot/README.md`
- chroot-distro：`references/chroot-distro/README.md`
- chroot-mcp-safe：`references/chroot-mcp-safe/README.md`

---

*本文是学习记录，不是新的权威设计；与 `docs/plan-v2.md` 冲突时，以 plan-v2 和 `docs/decisions.md` 的最新决定为准。*

## 9. 补充：chroot-mcp-safe 的高危写保护清单（只读学习）

`references/chroot-mcp-safe/README.md` 的“防变砖三层保险”对本项目最有价值的部分不是完整 Android 视图，而是**把“可读”与“可写”拆开**：

- 读：`/data`、`/mnt`、`/storage/emulated/0`、`/dev` 全部可见，满足 Agent 反调试/逆向/文件分析；
- 写保护：`/system`、`/system_ext`、`/vendor`、`/product`、`/odm`、`/mi_ext`、`/apex` 强制 ro；
- 变砖高危：`/metadata` 强制 ro；`/mnt/vendor/persist`（IMEI/序列号/校准）、`/mnt/vendor/qmcs`（高通认证）在 rbind `/mnt` 后单独 `remount,bind,ro`；
- 传播：根 `mount --make-rprivate /`，每个 `rbind` 后再次 `mount --make-rprivate $dst`；
- 卸载：`sync → kill → remount,ro → umount → losetup -d`，并把 `/data`、`/data_mirror`、`/mnt`、`/dev` 等 rbind 的 lazy-umount 作为“必须 lazy 才能卸载”的显式例外，而不是无条件 `umount -l`。

映射到我们的 v2：

| 场景 | 读 | 写 |
|---|---|---|
| `/data` 普通 App 数据 | 允许 | R2（普通 App 私有数据） |
| `/data/system`、`/data/misc`、系统 key 包 | 允许 | R3 |
| `/system`、`/system_ext`、`/vendor`、`/product`、`/odm`、`/mi_ext`、`/apex` | 允许 | 默认只读；`system-rw` 临时 overlay 才可写 |
| `/mnt/vendor/persist`、`/mnt/vendor/qmcs`、`/metadata` | 允许读 | 永久 ro；任何写尝试必须 R4 |
| `/dev/block/*` | 默认 `ro` 只读 bind，**可读、不隐藏**；ro 只是视图，不阻止设备节点写；`masked` 仅显式可选 | R4 原始块写；`dd`/`mkfs`/`blkdiscard` 人工 |

这也解释了为什么“读全开”不等于“所有 mount 都 rw”：读可见性与写保护是两个维度，mount 的 ro 是最外层写保护，不是读限制。

## 10. 补充：模块打包与启动钩子（DSH-Chroot / dsh-alpine-ksu）

### 10.1 DSH-Chroot 的模块包规范

`references/DSH-Chroot/docs/module.md` 的规则值得直接对照我们的 `build/build-module.sh`：

- zip 只装“引导器”（壳 + manifest + 安装/开机钩子），payload 永远不进 zip；payload 首启由 `service.sh bootstrap` 解包到 `/data/local`。
- 包根**绝不能有 `install.sh`**：KernelSU/Magisk 会把含 `install.sh` 的包判成 legacy 模块，只解压 `module.prop/install.sh/uninstall.sh/common/*`，`bin/` 与 `lib/` 不会落地，导致安装钩子在空目录里失败（pitfalls A23）。
- `customize.sh` 被安装器 **source**，不是执行；`MODPATH` 指向解包目录；它负责可执行位、拒绝降级、复制运行期文件到 `DSH_ROOT`、只建空目录不碰已有数据。
- 退出码区分：`30` payload 缺失/校验失败，`60` 运行时未就绪；`status` 能一眼区分“还没投递”和“投递了但起不来”。
- 构建可重复：条目排序、固定 mtime、固定权限、不写目录项/平台元数据；两次构建 sha256 相同；`--selftest` 覆盖安装模拟、闸门、拒绝降级和字节级漂移。

对我们的映射：我们的模块没有 1GB payload，但同样应保证 **zip 只含模块脚本/二进制、不含 state/image**；`customize.sh` 不依赖 `install.sh`；`build-module.sh` 的 zip 条目顺序/权限/mtime 可重复；`uninstall.sh` 不删除用户数据。

### 10.2 dsh-alpine-ksu 的非阻塞启动

`references/dsh-alpine-ksu/service.sh` 共 28 行：检测 busybox → `setsid dsh-service-boot &` → `exit 0`，注释明确“never run a long-lived loop in service.sh itself; some managers wait for this script, which would block boot at the boot animation”。`boot-completed.sh` 是第二条非阻塞、幂等启动路径。

对我们的映射：`module/service.sh` 里 `dsh_service_boot` 已经在后台 subshell 中执行；仍要保证：

- `service.sh` 本身不做耗时挂载/fsck/等待；
- 两条入口（service.sh / boot-completed.sh）幂等，重复触发不会启动两个 holder；
- 日志重定向不能阻塞；
- `dsh-watchdog` 的启动不应反过来卡住 boot（用 `setsid`/后台，失败只记录）。

### 10.3 关于 ARB/分区写

`references/DSH-Chroot/docs/recovery.md:150-155` 把分区风险分三类：内核侧可回刷 `boot`/`init_boot`/`vendor_boot`/`dtbo`/`vbmeta`/`recovery`；引导/固件侧（`xbl`/`abl`/`tz`/`modem` 等）绝不能刷旧包；`data` 永不恢复。因果要点：内核侧旧包回刷相对安全；引导/固件侧旧包绝不能刷，可能黑砖；升级系统可能永久熔断 9008，不可逆、降级无效。对我们的直接含义：

- DSH 的写分级里，`/dev/block/*` raw write / `dd` / `mkfs` / `blkdiscard` 必须是 R4 人工确认，且默认拒绝；
- `system-rw` 只允许做 `/system` 等分区的**私有 namespace overlay**，不能写真实分区；
- 任何“修复分区/刷 boot”的能力都不属于 chroot 核心；不要因为“读全开”就顺手提供 raw 写便利。

### 10.4 DSH-Chroot 的执行契约与诊断（读完 `service.sh`/`dsh-ctl` 后的补充）

`references/DSH-Chroot/service.sh` 的头部把契约写得很清楚，值得直接对照我们的 `dsh-ctl`：

- 六个契约：非交互（`exec 0</dev/null`）、环境钉死、幂等、先停后换、默认离线、状态+标记+退出码。
- 退出码分层：`0` 成功、`10` 已是当前状态、`20` 停止失败、`30` payload 缺失/校验失败、`40` 切换失败、`50` 版本闸门拒绝、`60` 运行时未就绪。`status`/`diag` 可以一眼区分“没投递”和“投递了但起不来”。
- `do_bootstrap`：`node.json`/`dsh.json` 的 sha256 字段**存在时**才校验；有活跃实例先 `do_stop`；Node 层版本相同复用；`promote_layer` 先移旧目录到 `.old` 再移新目录；`active` 指针最后写。`references/DSH-Chroot/service.sh:505-594`。
- `do_install`：只接受 `node-*.tar.gz`、`dsh-*.tar.gz`、`*.json`；同类旧包先删，保证 inbox 只留“下一份待装”的包；避免 bootstrap 选到旧包。
- `do_start`：先检查 `pid_alive`（already-running=10），再检查 active 层和 node 层（payload-missing=30），再 `version_gate`，最后 `runtime_start`（runtime-failed=60）。
- `do_diag`：**只读**，不启进程、不 mount/umount；输出“证据 → 结论”，并引用 `lib/errors.sh` 的错误码表；没有命中时明确说“没发现已知故障”，不猜。这个“证据表 + 错误码表”做法比单纯打印日志更适合无人值守诊断。
- `dsh-ctl` 是公开入口，`service.sh` 是唯一执行者；`dsh-ctl` 只在手动路径做 cgroup 搬家（`boot`/`diag` 跳过，因为 diag 必须只读），搬家成功/失败都如实打印，不假装成功。`references/DSH-Chroot/dsh-ctl:1-139`。
- 细节不一致：`rt_reader` 注释说“token 不进任何文件”，但 `rt_real_start` 实际把 `token` 写进了 `$RUN_DIR/dsh.json`（权限 0600）。这不是说它一定不安全，而是提醒我们：**审计要看代码，不要只信注释/README**；我们的 status/日志脱敏要独立复核。

对 ours 的映射：

- 我们的 `dsh-ctl` 目前把“生命周期 claim + 命令分发”混在一层；可以保持实现简单，但应保留“公开入口/执行者”边界，至少让 `diag` 严格只读、不被 lifecycle lock 阻塞。
- 我们的 `status`/`diag` 应有稳定字段和退出码；不要把“运行中但 guest 未就绪”和“未启动”混成一个状态。
- `bootstrap`/`image install` 的“先停后换 + sha256 + active 指针最后写”思路可直接用于我们的 `image install`/`snapshot restore`。
- `dsh-ctl` 的 cgroup 搬家不是我们当前目标，但“手动路径才搬家、boot/diag 不动”这种**按路径区分副作用**的原则值得保留。

### 10.5 Root 模块生命周期（`knowledge/03-root/05-module-format.md`）

- 标准顺序：安装 ZIP → `modules_update/<id>` → 重启 → `post-fs-data.sh`（/data 已挂载、zygote 前）→ 模块文件挂载 → `service.sh`（late-init 后）→ `boot-completed.sh`（开机完成后；并非所有 root 方案都支持）。
- `post-fs-data.sh` 适合早期目录/挂载准备，**不能做长任务/等待用户/网络**，失败可能阻止开机；`service.sh` 适合守护进程，但必须自己 daemonize/`setsid`，不能阻塞管理器；`boot-completed.sh` 只能当可选第二入口，不能假设一定被调用。
- `module.prop` 的 `id` 发布后不可变；`versionCode` 必须递增；`uninstall.sh` 只清理属于本模块的持久文件/服务，不删用户数据。
- 模块以 root 运行，安全边界是“可信来源 + 最小写面”，不是“chroot 内 root 被限制”。

对我们的映射：我们的 `service.sh` 已经后台调用 `dsh-ctl boot`，但需要确认 `post-fs-data` 是否存在长任务、`boot-completed.sh` 是否只是日志、`service.sh` 是否在 KernelSU 管理器等待时立即返回。这三条应进入装前检查。

## 11. loop / 文件系统补充（`knowledge/01-kernel/04-05`）

- loop 设备是“把普通文件映射成块设备”；`losetup -f --show` 找空闲设备，`-r` 只读，`-P` 扫描分区表，`--offset/--sizelimit` 用于分区镜像；`LOOP_CONFIGURE` 需 Linux 5.8+。
- `LO_FLAGS_AUTOCLEAR` 在最后一个打开者关闭后自动 detach；手工 `losetup` 后卸载不会自动 detach，除非设置 AUTOCLEAR。`losetup -D` 会 detach 所有 loop，生产环境禁止随手使用。
- 清理顺序永远是：停进程 → 卸载子挂载 → `umount` 根 → `losetup -d` → `findmnt`/`losetup -a` 复核；`umount` busy 时不要直接 detach，先找 `lsof/fuser/findmnt -R`。
- Android 上 `/data` 可能位于 FUSE/FBE 之上，loop 打开镜像可能 `EIO` 或性能异常；这是经验边界，不是平台保证。`/dev/loop-control` 不一定可写。
- ext4 创建用 `truncate` + `mke2fs -F -t ext4 -b 4096 -I 256 -d rootfs image`；`-F` 在普通文件上必须，否则交互确认会导致脚本失败。
- `e2fsck` 退出码应按 0/1/2 可继续、4 需人工、8/16/32/128 工具/环境失败处理，不能简单 `if e2fsck; then`。
- ext4 支持离线缩容但必须先 `e2fsck -f` 且备份；在线通常只能扩容。f2fs 工具不能用于 ext4，反之亦然；EROFS 只读，overlayfs 的 upper/work 必须同一文件系统，tmpfs 断电即失。
- Android 的 `/data` 不是“默认 f2fs”、`/system` 不是“全是 EROFS”、`/sdcard` 不是“必然 noexec”；这些必须按设备和版本记录，不能写成平台保证。

对我们的映射：我们的 `dsh_loop_attach` 使用 `losetup --sector-size`/`--direct-io` 前必须在 doctor 里探测；`dsh_loop_detach` 只能在 umount 后调用；`snapshot`/`image` 的镜像清理要按“文件实际被 loop 占用”判断，不只看路径。

## 12. `/proc`、`/sys`、`/dev` 与路径解析（`knowledge/01-kernel/06-proc-sys-dev.md`）

- `/proc` 是内核视图，不是普通目录；把宿主 `/proc` 挂进 chroot 会暴露宿主进程、`/proc/PID/root`、fd、namespace，且 `/proc/PID/root` 可被有 `CAP_SYS_PTRACE`/ptrace 权限的进程用来绕过 chroot。正确方向：PID namespace + 重新挂载新 proc + hidepid；仅靠 chroot 不行。
- `/sys` 通常只读挂载，写某些属性会改内核状态；不要对宿主 `/sys` 做全局 remount。
- `/dev` 是 devtmpfs/tmpfs，设备节点由主/次设备号标识；`mknod` 需 `CAP_MKNOD`，在 user namespace 中通常不能创建真实设备节点。Android 的 `/dev` 由 init/ueventd 创建，不要假设应用/普通 root shell 能 mknod。
- chroot 最小设备集是 null/zero/full/random/urandom/tty/ptmx；本项目为了 Android 二进制与 adb/调试需要完整 `/dev`，但必须把 `/dev/block` 等写敏感面单独 ro（P1 设计）。
- 路径解析安全可用 `openat2` 的 `RESOLVE_BENEATH`/`RESOLVE_IN_ROOT`/`RESOLVE_NO_MAGICLINKS`/`RESOLVE_NO_XDEV`/`RESOLVE_NO_SYMLINKS`；这些是路径分辨率约束，不能替代 mount namespace/LSM。

对我们的映射：`dsh-nsinit` 当前把宿主 `/proc` 直接 `mount -t proc` 到 rootfs（不是 bind 宿主 proc），但没有 PID namespace；因此 guest 能看到宿主 PID 空间。若 v2 要“读任意内容”，这是可接受的读面；但要在设计上明确“DSH 不是隔离沙箱”。`/proc/PID/root` 的逃逸风险与“读全开”不冲突于数据读取，但会影响写路径的路径校验。

## 13. Android 启动、/data 与 FBE（`knowledge/02-android/01-03`）

- Android 启动链：Boot ROM → bootloader → boot/vendor_boot/init_boot → kernel → init（PID1）→ first stage → selinux_setup（enforcing，失败常 bootloop）→ second stage → zygote/system_server。`post-fs-data` 是 `/data` 已挂载后的触发器，负责配置/启动 vold 等，不是首次挂载点；`service.sh` 在其后（late-init）。
- Android 13 起**出厂 13+** 设备常见 `init_boot`；升级到 13 的旧设备不一定有。分区名/是否存在必须看设备，不是版本号保证。
- `/data` 可能是 ext4 或 f2fs（本机实测为 f2fs）；FBE 下 `CE`（`/data/user/<userId>`）要等用户解锁，`DE`（`/data/user_de/<userId>`、`/data/system`、`/data/misc`）可更早读。依赖 CE 的服务不能首次解锁前启动。
- `/data/data` 是兼容路径/符号链接，实际指向 user 0 私有目录；写 R2 时必须先 `readlink -f`/`realpath` 解析并保留原路径，不能把它当独立目录。
- `/sdcard` 通常是 `/data/media` 经 FUSE/emulated 的视图，本机实测是 `/data/media/0` 的 bind；`/storage/emulated/0` 才是 FUSE；它不是可直接 mount 的块设备；性能、`noexec`、权限随设备/版本变化；镜像放 `/sdcard` 不可靠，DSH_IMAGE_DIR 默认在 `/data/media/0/ubuntu-rootfs`（仍是 `/data`，不是 FUSE 视图）。
- Android 的 `/dev` 由 init/ueventd 创建；完整 `/dev` 是 Android 二进制运行的需要，但 `/dev/block` 的写面必须单独限制。
- init 的 `namespace` 选项可为服务创建独立 mount namespace，但不是完整容器；共享内核/PID/SELinux。

对我们的映射：`dsh_boot` 等待 `sys.boot_completed=1` 与 `ce_available` 是必要的；但“读全开”不意味着在 CE 未解锁时能读 `/data/user/0`。R2 写 `/data/data` 前必须确认 CE 已解锁，否则会看到空/不可读目录。

## 14. 服务监督与 fsck 的补充结论（`06-runtime/04`、`05-storage/04`）

- systemd 通常不能作为 chroot 的 PID 1；chroot 内优先 s6/runit/supervisord。s6 的 `run` 必须前台、`finish` 做清理、`down` 控制默认状态、`notification-fd` 做 readiness、`s6-svwait -U` 等就绪。PID 1 必须回收僵尸、处理信号；容器主进程不擅长时用 tini/dumb-init。
- `e2fsck` 的 `-fn` 只读检查、`-fy` 修复；修复流程必须是“停止使用→备份镜像→只读检查→记录→修复→再检查→只读挂载验证”。正常读写挂载上跑 e2fsck 可能加重损坏。
- 备用超级块 `e2fsck -b 32768 -B 4096` 只在确认块大小/备份位置后使用；`debugfs -w` 是危险写操作，必须 R4。
- 对我们的映射：`dsh-start` 的 e2fsck 只应在冷镜像上跑；`dsh-run` 的 s6 服务用 `notification-fd`/`s6-svwait` 判 ready，不能只看 `s6-svstat` 的 up。

## 15. KernelSU/SukiSU 与 rootfs 构建补充（`03-root/02-03`、`04-ubuntu/02`）

- KernelSU 官方支持 GKI 2.0（内核 5.10+），旧内核/非 GKI 需要自行编译；特性 id 由内核返回，`mount_hide`/`avc_spoof` 是 SukiSU 分支扩展，不是上游通用。不要把 Manager 版本当内核能力，`su --help`/feature set 必须实测。
- 模块以 root 运行，`/data/adb/modules` 生命周期与 Magisk 类似；`post-fs-data` 失败可能阻止开机，`service.sh` 必须非阻塞。SukiSU 的 SUSFS 是外部内核补丁+用户空间，不是“装了 SukiSU 自动有”。
- Ubuntu rootfs 构建：`debootstrap --arch=arm64 --variant=minbase noble http://ports.ubuntu.com/ubuntu-ports/`（x86 宿主需 qemu-user-static/binfmt）；Base tarball 用 `tar -xpf` 保留权限；chroot 内 apt 要放 `policy-rc.d` 返回 101，避免 postinst 启动服务。
- 镜像制作：`truncate -s 8G` → `mke2fs -t ext4 -F -L ubuntu-rootfs` → `mount -o loop` → `cp -a rootfs/. /mnt/rootfs/` → `umount` → `e2fsck -f`；复制必须保留权限/符号链接，不能 `cp -r` 丢失 owner。稀疏化用 `cp --sparse=always`，adb push/tar 后可能失去稀疏性，要复核实际占用。
- 首次启动清理：`/etc/machine-id` 置空、删除 ssh host key、apt cache/lists；不要预置默认密码/私钥。

对我们的映射：我们的 `build-rootfs.sh` 要把 `policy-rc.d`、`mke2fs -F`、`cp -a`、umount 后 e2fsck 作为硬检查；`doctor` 的 KSU 特性检查不应把 SukiSU 扩展当上游通用，按设备 feature set 报告。

## 16. 本机 KernelSU 模式：LKM（2026-10-30 实测）

读取 `/data/adb/ksud debug info`（只读）：

```text
version: 41100
uapi_version: 6
features: 0x2714
lkm: true
bundled: true
late_load: false
runtime_mode: lkm
```

结论：

- 本机是 **标准 LKM 模式**，不是 late-load：KernelSU 内核模块在 init 阶段加载，`KSU_LATE_LOAD` 不会设置；`post-fs-data.sh`、`service.sh`、`boot-completed.sh` 按正常模块生命周期执行。
- LKM 模式通常 patch `init_boot`（Android 13+ 出厂设备）或 `boot` 的 ramdisk，不替换内核；优点是保留原内核、AVB 风险低、可通过 `ksud unload`/卸载模块临时关闭。代价是依赖 Manager/内核模块版本匹配（`version: 41100` 是内核 `KSU_VERSION`；`ksud --version` 才是 4.3.0，两者不能混用）。
- `features: 0x2714` 是 `KSU_FEATURE_MAX`（10004，feature id 上界），**不是启用位图**，不能据此判断 `mount_hide`/`avc_spoof` 已启用。`ksud feature list` 会遍历 `FeatureId::ALL`，逐项打印 `NOT_SUPPORTED`/`DISABLED`/`ENABLED`；必须看实际状态，不能只看字段值或列表中出现。
- 若是以后切到 **late-load**：`post-fs-data.sh` 会被 `late-load` 阶段替代，模块不能再假设 post-fs-data 已执行；`KSU_LATE_LOAD=1` 可用于检测，模块需要 `late-load.sh`。我们的 `service.sh` 目前不依赖 post-fs-data，兼容性较好，但文档/doctor 要区分 `KSU_RUNTIME_MODE`。
- 观察补充：从当前 shell `ls /data/adb/modules` 为空，但 `ksud module list` 能看到 zygisksu 等模块；`mount_hide`/namespace/视图差异可能造成“文件系统视图”和“KernelSU 实际模块列表”不一致。不能仅凭 `ls /data/adb/modules` 判断模块是否安装，应该以 `ksud module list`/Manager 和实际挂载为准（这也解释了为什么我们的 `dsh-nsinit` 不应把“模块目录为空”当作未安装）。

对我们的映射：`module/service.sh` 的非阻塞启动在标准 LKM 下是正确路径；`boot-completed.sh` 可作为第二入口，但不能假设一定被调用。若未来支持 late-load，需要增加 `KSU_LATE_LOAD` 检测和 `late-load.sh`，不能直接复用 `post-fs-data` 假设。

## 17. Android 分区、APEX 与 SELinux 补充（`02-android/02`、`02-android/05`）

- Android 分区不是统一表：`boot`/`init_boot`/`vendor_boot` 的 ramdisk 位置随 Android 版本与出厂版本变化；LKM 模式通常 patch `init_boot` 或 boot 的 ramdisk，不换内核。动态分区 `super` 需要 `liblp`/`fastbootd`，不是普通块设备直写；`metadata` 损坏可能导致 `/data` 无法解密，`persist` 刷错可能丢校准。
- APEX 是只读、可更新的系统组件，位于 `/system/apex` 等；不是普通 APK，不要把它当可写目录。`system`/`vendor`/`product` 可能是 ext4/EROFS，不能假设全是 EROFS。
- Android SELinux 是强制访问控制，root/UID 0 仍受域/策略约束；`setenforce 0` 是调试手段，不是生产配置。`context=` 改挂载点标签，`fscontext=` 改文件系统级内存标签，二者都不写磁盘 xattr；只读挂载 selinuxfs 只限制该挂载点，不保护全局，仍可通过 `/proc/1/root/sys/fs/selinux` 等路径访问。
- 属性不是安全凭据：`ro.*` 只是用户空间只读，root 可 resetprop；不能用 `ro.debuggable=0` 作为唯一安全判断。
- 对本项目：我们的 `/dev/block`、`/system`、`/vendor` 等 bind 到 guest 时，若设置 `context=` 可能改变 guest 看到的 SELinux 标签；这与“读全开、尽量原样”目标冲突。建议默认不强行改 context，除非有明确兼容需求；doctor 要能报告 AVC denial，而不是用 `setenforce 0` 掩盖。
- 在 LKM 模式下，`ksud boot-info` 在 chroot 内可能因 `/dev/block/by-name` 不存在而失败（本机实测 `open /dev/block/by-name/boot_a: No such file`）；boot/分区检查应在宿主 root shell 或只读 sysfs 做，不能依赖 chroot 内的 `/dev/block/by-name` 视图。

## 18. APT、网络、locale 与 UID 语义补充（`04-ubuntu/03/06/07`）

- chroot 默认共享宿主网络 namespace：同一 `localhost`、同一端口空间、同一路由/防火墙；不会自动获得宿主 DNS。Android 的 DNS 由 netd 管理，宿主 `/etc/resolv.conf` 可能不存在或指向 `127.0.0.1`。我们的 `dsh-nsinit` 固定写 `8.8.8.8/1.1.1.1` 是可用但不可配置的默认；应允许 `DSH_DNS` 配置或优先读取宿主的 `net.dns*`，并把名称解析失败报成明确诊断。
- chroot 不虚拟化 UID：guest 内 `uid 0` 就是宿主 `uid 0`（除非 user namespace）。这直接说明“读全开/写分级”不是沙箱，而是**操作者意图约束**：同一 root 权限下，任何 R2/R3 保护都只能靠 DSH 入口和审计，不能靠内核权限。`/data/data` 的 R2 备份/回滚因此是“防止粗心”，不是“防止恶意”。
- locale/时区：`LC_ALL=C` 适合脚本稳定输出；镜像应生成 `C.UTF-8` 或 `en_US.UTF-8`，`/etc/localtime` 可绑定宿主或复制；`/etc/machine-id` 模板应清空，首次启动生成。当前 `dsh-nsinit` 并未绑定 `/etc/localtime`；如需共享宿主时区，应新增显式绑定并测试；否则 guest 用 UTC，不影响功能但日志时区要说明。
- APT：chroot 内运行 apt 需要 `/proc`/`/sys`/`/dev`、`resolv.conf` 和 `policy-rc.d`（返回 101）；不要在 chroot 里跑 `systemctl`；制作镜像前 `apt clean`，避免把缓存/机器 ID/ssh host key 打进模板。
- glibc vs bionic：Android 二进制依赖 `/system/bin/linker64`，Ubuntu 程序依赖 glibc loader；不要把 Android 动态 ELF 放进 Ubuntu rootfs 直接执行；跨环境用静态链接（我们的 `dsh-mount-ro-recursive` 就是 static ELF）或显式打包对应 loader。
- 对本项目：`dsh-run` 的 DSH web 必须是 rootfs 内 glibc 构建；`dsh-nsinit` 里所有 helper 要么 static，要么在 rootfs 内提供依赖；`dsh_boot` 等 `ce_available` 的判断不能只用 `sys.boot_completed`。

## 19. fsck 与安全实验（`05-storage/04`、`07-playbooks/01`）

- e2fsck 退出码：0 无错、1 已修、2 已修建议重启、4 未修复错误、8 操作错误、16 用法、32 取消、128 共享库错误。`-fn` 只读检查，`-fy` 自动修复；修复前必须先卸载/只读、备份镜像，修复后再 `-fn` 复核、只读挂载验证。
- 禁止在正常读写挂载的文件系统上跑 `e2fsck`；`debugfs -w` 属于高风险写操作；备用超级块 `-b 32768 -B 4096` 不是万能，取决于块大小/特性。
- 安全实验顺序：只读盘点 → host/mock → 备用机 → 主力机；一次只改一个变量；每步有回滚；记录命令、输出、版本、时间；实验后清理临时挂载、loop、进程和测试数据。
- 破坏性清单：`rm -rf`、`dd of=/dev/...`、`mkfs`、`fsck -y`、`mount -o remount`、`fastboot flash/erase`、`setenforce 0`、`sysctl -w`、`chmod -R`。执行前用 `findmnt`/`lsblk`/`mountpoint` 确认目标，变量加引号，优先 `--`。
- 对本项目：`dsh_image_create`/`snapshot` 的 `e2fsck` 只应跑未挂载镜像；真机验证前必须准备 `init_boot`/`boot` 原厂镜像与恢复路径；任何 `system-rw on`、`/dev/block` 写、`fastboot` 操作都是 R4 人工确认。

## 20. 启动/mount 故障与命令速查（`07-playbooks/02`–`04`）

- 启动分层：电源 → bootloader → 内核 → initramfs/init → 关键服务 → 框架/桌面 → 应用。先定位层，不要直接刷机；内核看串口/pstore/`/sys/fs/pstore`/`last_kmsg`，Android 看 `dmesg`/`logcat -b all`/`getprop`。
- chroot 启动失败常见原因：动态 linker/架构不匹配、`/proc` 未挂载、`/dev/null` 缺失、DNS 未配置、systemd 依赖不满足、ro 文件系统、uid/capability/SELinux 拒绝。检查 `file`、`readelf -l`、`findmnt -R`。
- mount/loop 诊断顺序：确认镜像存在 → `losetup -a`/`-j` → `blkid`/`file` → 挂载点存在且为空 → 只读挂载 → `dmesg` → 检查占用 → 清理。`mount --rbind` 不会自动 private；`umount -l` 只是延迟分离。
- 清理：先停进程 → `umount -R` → 处理 `EBUSY`（`fuser`/`lsof`）→ `losetup -d` → 验证 `findmnt`/`losetup -a` 无残留。不要随意 `fuser -km` 或 `losetup -D`。
- 命令速查：`findmnt -R`、`mountpoint`、`losetup -j`、`nsenter -t PID -m`、`s6-svwait -U`、`s6-svstat`；toybox 的 `ps`/`mount`/`grep`/`sed` 选项与 GNU 不同，脚本必须先 mksh/toybox 验证。
- 对本项目：`dsh-ctl diag` 应按层采集证据（boot log、`/proc`、`findmnt`、`losetup -a`、AVC），并在输出中明确“未执行修复”；真机故障时先回滚最近改动，不叠加修复。

## 21. 系统调用、挂载选项与 Android 路径（`08-reference/01`–`03`）

- 关键系统调用：`mount/umount2`、`pivot_root`、`chroot`、`open_tree/move_mount`（5.2）、`fsopen/fsconfig/fsmount`（5.2）、`mount_setattr`（5.12）、`unshare/setns`、`openat2`（5.6）、loop ioctl、`fsync/fdatasync/syncfs`、`fallocate`、`copy_file_range`。
- `mount_setattr` 的 `AT_RECURSIVE` 可原子修改整棵挂载树属性；`openat2` 的 `RESOLVE_BENEATH/IN_ROOT/NO_SYMLINKS` 是路径 containment 的正确工具；`chroot` 不是安全边界；`pivot_root` 的 `new_root` 必须是挂载点，`put_old` 必须位于 `new_root` 之下，且 `new_root`/`put_old` 都不能位于当前根的挂载上（不是“彼此不同挂载”）；`pivot_root` 只切换根，不创建 namespace。
- 挂载选项事实：`ro` 只作用于当前挂载点；`bind,ro` 只让该 bind 只读，底层可能仍可写；`remount` 只需挂载点；`noexec` 不阻止解释器读脚本执行；`nosuid` 不阻止 capability/漏洞；`sync` 不等于应用数据安全，应用仍需 `fsync`。
- Android 路径：`/data/data` 是多用户下 `/data/user/<user>` 的兼容链接，真实路径要 `realpath`；`/data/user_de/0` 是 DE 存储；`/data/media/0` 是内部存储真实位置，`/storage/emulated/0` 是 FUSE 视图；`/run` 在 Android 默认不挂载；`/data/adb/*` 是 root 生态约定，不是平台 API。
- 对本项目：`dsh-nsinit` 的路径 containment 应优先用 `openat2`/`realpath` 而不是字符串拼接；`dsh_mount_bind_ro_path` 的 `ro` 必须验证实际挂载点（`/proc/self/mountinfo`），不能只信 `mount` 返回码；`/data/data` 的 R2 写路径必须解析 `/data/user/<id>` 真实路径并保护 SELinux 标签。

## 22. 来源、应用沙箱、shell 与 root 检测（`08-reference/04`、`02-android/04`、`06`、`08`）

- 引用纪律：一手优先（内核 man-pages、AOSP、util-linux/e2fsprogs/s6、KernelSU/SukiSU 源码），版本限定，给出 URL/文件/行号和访问日期；二手来源只做交叉验证。知识库本地 `references/` 快照的 KernelSU 提交为 `7bab12df...`，SukiSU 为 `2fc2b415...`。
- Android 沙箱：UID/appId 为 `uid = userId * 100000 + appId`；用户 0 普通应用 `10000–19999`，用户 10 为 `1010000–1019999`；`/data/data` 只是 `/data/user/<id>` 的兼容链接。应用由 zygote fork 后降权，叠加 SELinux 域、seccomp、capability、mount namespace、FBE/FUSE 和 AppOps；root 会破坏整个模型。
- Android shell：`/system/bin/sh` 是 mksh，`toybox` 不是 GNU coreutils；`grep -P`、`find -printf`、GNU tar 选项和 `ps` 格式都可能不可用。脚本必须 `#!/system/bin/sh`，先 `command -v`/`toybox` 检查，路径加引号；需要精确退出码时用 `adb shell 'cmd; echo $?'` 或 `exec-out`。
- root 检测：看 `su` 路径、`/data/adb`、管理器包名/行为、`ro.debuggable`/`ro.secure`/`ro.boot.verifiedbootstate`/`ro.boot.flash.locked`、挂载表 overlay/tmpfs、`init.rc` 服务等；单一信号不可靠，检测与隐藏是持续对抗。
- 对本项目：`dsh-nsinit`/`dsh-ctl` 必须按 mksh/toybox 语法测试；不得依赖 GNU `grep -P`/`find -printf`/`sed -i` 备份后缀；`/data/data` 的 R2 写必须按 userId 解析真实路径；root 检测/隐藏不是本项目的安全目标，不要把“检测不到”当安全边界。

## 23. Binder、Android 版本与 ADB/fastboot（`02-android/07`、`09`、`10`）

- Binder 是同步 IPC，不是通用网络协议；事务数据会拷贝，单事务受约 1 MiB Binder 缓冲区限制，大对象应走 fd/共享内存/分块。Binder 不是安全通道，服务端必须用 `Binder.getCallingUid()` + 权限/SELinux `binder_call` 检查调用者；`oneway` 可能丢失/延迟，不能用于关键状态同步。
- Android 版本差异：Android 13+ **出厂设备**才有 `init_boot`，不是所有 13+；Android 12+ 新设备 GKI；Android 15/16 推进 16 KB page size（`getconf PAGE_SIZE`），与 ext4 块大小不是一回事；`/data` 可能 ext4/f2fs，`/system` 可能 ext4/EROFS。
- ADB：`adb root` 只对 `ro.debuggable=1` 的 userdebug/eng 有效；`adb remount` 需要关闭 verity/可写系统，现代 Android 常用 overlayfs；ADB over TCP 暴露很危险。fastboot 是 bootloader 协议，`fastbootd` 才能写动态分区；`fastboot flash/erase` 是 R4 人工操作，`fastboot boot` 临时启动也可能触发 AVB。
- AVB/解锁：`fastboot flashing unlock` 通常清数据，解锁后 `ro.boot.verifiedbootstate=orange`、`ro.boot.flash.locked=0`；重新锁定可能要求官方签名镜像。刷机前必须备份 `persist`/`frp`/校准分区和原厂 boot/init_boot。
- 对本项目：DSH 运行时不调用 ADB/fastboot/Binder；`dsh-nsinit` 不依赖 Android Binder 服务；真机验证前的备份/恢复必须由人工完成，`fastboot`/`adb root/remount` 不纳入 AI 自动流程。Android 13+ 的 init_boot、16 KB page size、GKI 版本应作为安装前只读检查项。

## 24. Root 方案与 su 语义（`03-root/01`–`04`、`06`、`07`）

- root 是权限/能力，不是“装了一个 App”；UID 0 仍受 capability、SELinux、namespace、seccomp、只读挂载限制。Android root 通常由内核态 `su`（KernelSU/SukiSU）或 patched ramdisk（Magisk）提供；`su` 是 per-app/per-command 授权，不是全局开关。
- KernelSU：官方支持 GKI 2.0（5.10+）；特性 `su_compat`/`kernel_umount`/`sulog`/`adb_root`/`selinux_hide`/`shell_su_adb_only` 是上游基线；`mount_hide`/`avc_spoof` 是 SukiSU 扩展，不能写成 KernelSU 通用能力。SUSFS 是外部内核补丁，不是 SukiSU 内核自带。
- 本机是 LKM：`ksud debug info` 已确认 `runtime_mode: lkm`、`late_load: false`、`bundled: true`；LKM patch init_boot/ramdisk，不换内核。`ksud` 4.3.0 `feature list` 会列出 `mount_hide`、`avc_spoof` 等扩展条目，但是否 `NOT_SUPPORTED`/`DISABLED`/`ENABLED` 要看对应状态，不能只凭列表中出现判断。
- Magisk 是 systemless + patched ramdisk；不要和 KernelSU 的 `su` 选项混用。Android 13+ 出厂设备可能是 `init_boot`，patch 错分区会不生效/无法启动。
- root 隐藏（DenyList、kernel_umount、mount_hide、SUSFS、属性伪装）只能降低痕迹，不能对抗硬件证明/强完整性检测，也不是安全边界。
- `su` 选项随实现不同，KernelSU **没有 `--path`**；不要依赖 root shell 的 `PATH`/`HOME`/`umask`，脚本应显式设置 PATH、用绝对路径。对本项目：`dsh-nsinit`/`dsh-ctl` 已以 ksu root 运行，不要调用 `su` 或 `setenforce`；需要 root shell 行为时用 `setsid`/显式环境，不依赖 Magisk 语义。

## 25. Ubuntu 24.04 与全局视角（`04-ubuntu/01`、`00-overview`）

- Ubuntu 24.04 = Noble Numbat，LTS；以 `/etc/os-release` 的 `ID/VERSION_ID/VERSION_CODENAME` 判断版本，不用 `uname -r`。24.04 采用 usr-merge，`/bin`、`/sbin`、`/lib` 等通常是指向 `/usr` 的符号链接，rootfs 构建/复制必须保留链接，不能用会解引用链接的方式破坏布局。
- chroot 与完整 Ubuntu 的差异：没有独立内核、通常没有 systemd PID1、没有 udev/网络管理器/RTC/ACPI；服务需 s6/手动；apt 安装脚本要 `policy-rc.d` 返回 101；snap 通常不可用。这是环境差异，不是“Ubuntu 坏了”。
- 技术全景：硬件/firmware → Linux 内核（GKI/namespace/SELinux/文件系统）→ Android 原生用户态（init/servicemanager/zygote/vold/netd）→ Framework/App；Linux rootfs 是同一内核上的另一套用户态，不是替代某一层。平台事实与实现选择必须分开写，不能把“我们选的做法”写成“Android 必须如此”。
- 版本提醒：Android 10+ 动态分区/APEX/scoped storage；Android 11+ FUSE/vendor_boot；Android 13 起首发设备 init_boot；Android 15 部分设备 16 KB page；Linux 5.12+ `mount_setattr`；Ubuntu 24.04 使用 deb822 `.sources`。
- 对本项目：rootfs 构建要保留 usr-merge 符号链接；`dsh-nsinit` 不能假设 guest 有 systemd/udev；`dsh_boot` 的 CE 解锁等待、s6 监督、`policy-rc.d` 与 `/etc/resolv.conf` 处理都属于“chroot 没有完整系统服务”的补偿，不应写成 Android 平台限制。

## 26. 实现模型、能力、调试与挂载顺序（`00-overview/04`、`01-kernel/08`–`09`、`06-runtime/02`/`04`）

- 实现模型对比：chroot = 只切换根，不隔离 PID/net/IPC/mount，不是沙箱；chroot + mount namespace = 挂载视图隔离；先 `unshare`/`clone` 创建 namespace，再 `pivot_root` 切换根；`pivot_root` 本身不隔离进程/网络/挂载；runc = namespace+cgroup+capability+seccomp+LSM；proot = ptrace 模拟，兼容性/性能有限；VM = 独立内核，隔离最强但开销大。选择由威胁模型决定，不由“chroot 一词”决定。
- capability：UID 0 不等于拥有全部 capability；capability 是线程级，分 Permitted/Effective/Inheritable/Bounding/Ambient；`CAP_SYS_ADMIN` 是 mount/namespace 的“新 root”，`CAP_SYS_PTRACE` 可绕过 chroot 读其他进程；`no_new_privs` 只阻止 exec 提权，不阻止既有 capability。Android 应用通常无 `CAP_SYS_ADMIN`/`CAP_SYS_MODULE`，su 域有更多但仍受 SELinux。
- GKI/KMI：Android 12 起 5.10+ 新设备 GKI 2.0；vendor 模块必须匹配 KMI/vermagic/签名；`vendor_dlkm`/`odm_dlkm` 常见；模块不匹配是 bootloop 常见原因。LKM 的 `kernelsu.ko` 也必须匹配 KMI，不能跨版本加载。
- 调试：先分层（bootloader/内核/init/挂载/用户态/应用）；`dmesg`、pstore、`logcat -b all`、`findmnt -R`、`/proc/self/mountinfo`、`lsns`、`nsenter -t PID -m cat /proc/self/mountinfo`；`strace -f`、`readelf -l` 查 interpreter；`EPERM` 可能是 capability/SELinux，不一定是 DAC。
- 挂载顺序依赖图：块设备/loop → fsck → 根文件系统 → `/proc`、`/sys`、`/dev`（→ `/dev/pts`、`/dev/shm`）→ `/run`、`/tmp` → bind → 服务。根文件系统先只读挂载检查，再 `remount,rw`；`e2fsck` 作用于设备/镜像，不是挂载点；`/run` 在 Android 上默认不存在，需创建 tmpfs。
- 服务管理：systemd 依赖 .mount/.service 单元与 target；chroot 通常没有 systemd PID1，s6 更合适；s6 的 `down`、`notification-fd`、`s6-svwait -U` 是 readiness 关键；PID1 必须回收僵尸/处理信号。
- 对本项目：`dsh-nsinit` 的挂载顺序总体符合依赖图，但它是直接 `mount -o rw`，没有“先 ro 检查再 remount rw”；`dsh-ctl` 不应依赖 systemd；`dsh-watchdog`/`dsh-run` 的 readiness 应通过 s6 通知/健康检查，而不是只查进程存在；`doctor` 应按 capability/SELinux 报 `EPERM` 原因，不要用 `setenforce` 绕过。

## 27. 审计反馈修正记录（2026-10-30）

按 `/root/workspace/projects/dsh-chroot-lab/audit-reports/2026-10-30-chroot-issues.md` 与外部文档审计反馈，已修正本文以下事实：

- §4 横向对比的 mount namespace 列：DSH-Chroot、lhroot、chroot-distro 改为“共享宿主/未实现”；只有 dsh-alpine-ksu 的 `unshare -m` 与 chroot-mcp-safe 的 `unshare --mount --propagation private` 标为私有。
- §3.1 按 `references/DSH-Chroot/service.sh` 实际代码改正：`dsh-ctl` 是公开入口、`service.sh` 是唯一执行者；`rt_mounts_up` 使用 tmpfs 而非 devtmpfs；`rt_make_ro` 是 remount 后 `rt_is_ro` 复核，不是先 `bind,ro`；`rt_reader` 不解析 `DSH_READY/DSH_TOKEN`，token 实际写入 `run/dsh.json`。
- §16 更正 `features: 0x2714`：它是 `KSU_FEATURE_MAX`（10004）上界，不是启用位图；具体 feature 状态看 `ksud feature list`；`version: 41100` 是内核 `KSU_VERSION`，`ksud --version` 4.3.0 另证。
- §5.2/§6 的“当前代码差距”按最新 working tree 改为：`dsh_mount_prepare_point` 已有 realpath 前后检查；`service.sh` 日志轮转已不 `exit 1`（`boot-completed.sh` 仍是）；`customize.sh` 已保留 ttyd 状态；手动 start 已调用 `dsh_watchdog_start_bg`；`dsh-nsinit` 仍是直接 rw 挂载，未做 ro-first。
- §10.3 按 `references/DSH-Chroot/docs/recovery.md:150-155` 重写分区风险三类：内核侧可回刷 `boot`/`init_boot`/`vendor_boot`/`dtbo`/`vbmeta`/`recovery`；引导/固件侧不可回刷；`data` 永不恢复。
- §18 删除“已绑定 `/etc/localtime`”的不实描述；§2.3 的 `mke2fs` 补 `-F`、`e2fsck` 补 128；§9 补 `/mi_ext` 与 `/data_mirror`；阅读表改为全部首轮已读、二次精读重点。

### 27.1 第二次审计修正（2026-10-31）

- §10.3 的 9008 因果改为“内核侧旧包回刷相对安全；引导/固件侧旧包绝不能刷，可能黑砖；升级系统可能永久熔断 9008，不可逆、降级无效；`data` 永不恢复”。
- §21 与 §26 的 `pivot_root` 条件改为：`new_root` 必须是挂载点，`put_old` 必须在 `new_root` 之下，且二者都不能位于当前根的挂载上；`pivot_root` 只切根，不创建 namespace；先 `unshare`/`clone` 再 `pivot_root`。
- §0.7、§2.4、§13 的 FBE 改为：`/data` 在解锁前已挂载，CE 部分 `/data/user/<id>` 首次解锁前不可读，DE 和 `/data/system`、`/data/misc` 可更早读；`post-fs-data` 是 `/data` 已挂载后的触发器，不是首次挂载点。
- §2.1 的 capability 改为 mount=`CAP_SYS_ADMIN`、mknod=`CAP_MKNOD`、ptrace=`CAP_SYS_PTRACE`/目标 ptrace 权限。
- §2.3 的 loop 身份改为记录 `dev_t`/major:minor + backing image；`kernel:loopN` 只作运行时比对，loop 号可复用。
- §6 的 snapshot gap 改为具体项：未拒绝设备节点/FIFO、restore 直接 `tar -xzf` 无 `--no-same-owner`/覆盖控制、`tar -tvzf` 对含空格/换行文件名有解析歧义。
- §24 的 feature list 改为：列表出现不等于支持/启用，要看 `NOT_SUPPORTED`/`DISABLED`/`ENABLED` 状态。
- §12 的最小设备集补 `/dev/urandom` 和 `/dev/full`。
- §10.4 引用改为 `service.sh:505-594`；§3.1 引用改为 `service.sh:118-181`、`266-289`、`293-301`。
- §1.1/§27 的阅读口径统一为“全部首轮已通读，仍需二次精读核对”。

**审计未解决、需要在代码/计划继续处理的 P0/P1**：`DSH_BLOCK_ACCESS=ro` 不是块设备写保护；但默认仍应保持 `ro` 以维持“全手机可读”，不能为了防写退回 `masked`；`masked` 仅作显式可选；真正写保护靠 R4 门禁；R0–R4 五档确认尚未实现；purge 会删除独立 workspace/dsh-home/日志/快照；多处 chmod/chown/`.nomedia`/备份删除发生在项目所有权校验之前；`/sdcard` 与 plan/log 仍有硬编码；当前 40 个修改文件 + 3 个未跟踪文件未提交。本文只是学习记录，不能作为“已完成 plan-v2”的交付证据。

## 28. 本机非破坏性核验（2026-10-31）

以下均在当前 chroot 视图中只读执行，未 mount/umount/写块设备：

- 身份：`ro.product.device=OP5D0DL1`，`ro.product.model=PJZ110`，`ro.build.version.release=15`，`ro.build.version.sdk=35`，`ro.build.type=user`，`ro.debuggable=0`，`ro.secure=1`，`ro.build.version.security_patch=2025-09-01`，`ro.board.platform=sun`，`ro.boot.hardware=qcom`。
- 内核/页大小：`uname -a` 为 `6.6.66-android15-8-g29d86c5fc9dd-abogki428889875-4k`，aarch64；`getconf PAGE_SIZE=4096`，**不是 16 KB**。
- 验证启动：`ro.boot.verifiedbootstate=green`，`ro.boot.flash.locked=1`，`ro.boot.dynamic_partitions=true`，`ro.boot.slot_suffix=_a`。
- FBE：`ro.crypto.type=file`，`ro.crypto.state=encrypted`；CE/DE 分区语义按知识库。
- 实际挂载：
  - `/` = `/dev/block/loop46` ext4 `rw,noatime,seclabel`（当前就是 DSH chroot 的 rootfs）；
  - `/data` = `/dev/block/dm-74` f2fs `rw,nosuid,nodev,noatime,lazytime,seclabel,...`；
  - `/system` = `/dev/block/dm-22[/system]` erofs `ro`；`/vendor`=dm-25 erofs ro；`/product`=dm-24 erofs ro；`/system_ext`=dm-23 erofs ro；
  - `/metadata`=`/dev/block/sda10` f2fs ro；`/mnt/vendor/persist`=`sda2` ext4 ro；`/mnt/vendor/qmcs`=`sde24` vfat ro；
  - `/dev`=tmpfs；`/dev/block`=tmpfs `size=1024k,mode=000`（**当前是 masked，不可读**）；`/run`=tmpfs；`/tmp`=tmpfs；`/sys`=sysfs rw；`/proc`=proc；`/sys/fs/selinux`=selinuxfs ro；
  - `/sdcard`=`dm-74[/media/0]` f2fs bind；`/storage/emulated/0`=fuse `rw,nosuid,nodev,noexec`。
- KSU：`/data/adb/ksud --version`=4.3.0（uapi 6）；`ksud debug info`=`version:41100`、`flags:0x31`、`features:0x2714`、`lkm:true`、`bundled:true`、`late_load:false`、`runtime_mode:lkm`、`pr_build:false`。`feature list`：`su_compat`、`kernel_umount`、`selinux_hide`、`mount_hide`、`avc_spoof` 为 ENABLED；`sulog`、`adb_root`、`shell_su_adb_only` 为 DISABLED。
- 权限：当前 `id` 为 uid0；`CapEff=000001ffffffffff`、`CapBnd` 同、`NoNewPrivs:0`、`Seccomp:0`。说明当前进程不是受限沙箱，读全开有实际基础；但这也意味着任何写路径都必须靠项目自己的分级门禁，不能依赖 capability 限制。
- SELinux 观测不一致：`getenforce` 输出 `Disabled`，但 `cat /sys/fs/selinux/enforce` 为 `1`，`/proc/self/attr/current` 为 `u:r:ksu:s0`。这是工具/视图差异，不能只用 `getenforce` 单点判断；本项目应记录原始 `enforce` 与 `attr/current`，且不写 SELinux。
- `/dev/block/by-name` 不存在，`ksud boot-info` 在 chroot 内会失败；boot/分区检查必须在宿主 root shell 或只读 sysfs 做。`/data/adb/ksud` 可见，`/data/adb/modules` 当前为空但 `ksud` 能看到模块，不能以 `ls /data/adb/modules` 判断安装状态。
- 设计含义：当前 `/dev/block` 被 `masked`，与“全手机可读”不一致；若坚持读全开，应改为可读的 `ro` 视图（或显式只读代理），并把 R4 作为写门禁；不能把 `masked` 当默认。
