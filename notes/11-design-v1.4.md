# 设计 v1.4（full-capability）：完整 chroot + 完整手机控制

> **2026-10-26 红队审查：本设计尚未批准开工。** 待拍板选项见 [`15-decisions-pending.md`](15-decisions-pending.md)。 5 个 P0 反对意见见 [`14-red-team-v1.4.md`](14-red-team-v1.4.md)：rootfs 镜像/控制面自引用、`/sys` 与 hardguard 不是边界、stop 竞态、快照一致性、手机控制未验证。先修 P0 并出 v1.5，再谈 apply。
>
> **红队 v2 追加（必须知道）**：
> - 本机 `u:r:ksu:s0` 是 KernelSU 的 **permissive + allow all** 域（`kernel/selinux/rules.c`：`ksu_permissive(KERNEL_SU_DOMAIN)` + `ksu_allow(ALL,ALL,ALL)`）；全局 `enforce=1` 不等于 chroot 域受约束，不能再写“SELinux 保证”。
> - `/data` rbind 有多个别名（`/data/user/0`、`/data_mirror/data_ce/null/0`、`/mnt/pass_through/0/emulated` 等），只遮 `/data/media/0/.dsh` + `/sdcard/.dsh` **不够**；镜像/控制面仍可经别名写。
> - `/storage` 不是完整 rbind，只挂了 `/storage/emulated/0`；`/storage/self`、其他用户视图缺失。
> - `/apex`、`/linkerconfig` 的 `remount,ro,bind` 不递归；`/apex/com.android.conscrypt/cacerts` 仍是 rw 子挂载，不能声称整树只读。
> - `/tmp/dsh-rootfs` 需要显式 `mkdir -p`；本机 `/tmp` 是宿主 tmpfs，`/run` 不存在。
> - `getenforce` 结果不稳定：本机 root 这次返回 `Enforcing`，app 返回 Permission denied；只以 `cat /sys/fs/selinux/enforce` + 域状态为准。


- 日期：2026-10-26 ｜ 设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 取代：`10-design-v1.3.md`（v1.3 的 daily/lab 分档）。
- 用户新约束（2026-10-26）：**不考虑权限收束，不考虑空间占用；只做完美的 chroot，完美的手机控制。**
- 结论：**单一 `full` 档**，root + 全 Android 视图 + 全 `/dev` + 全 `/data` + `/mnt`/`/storage`/`/sdcard` + Android 命令包装器 + debugfs/tracefs；ext4 大镜像；规则层降为可选 `guard`，审计保留；`rescue` 只用于维修。

---

## 0. 术语（先澄清）

- **本机 / 宿主**：这台 Android 手机，只有一台；Android 系统、内核、SELinux、`/data`、`/dev` 都属于它。
- **chroot 内**：同一台手机上的一个进程树，根目录切到 Ubuntu rootfs；共用同一内核/网络/硬件，**不是虚拟机、不是 proot、不是另一台机器**。
- **不再使用“客机”**：之前文档里的“客机”是错误借词，已统一改为“chroot 内”。

---

## 0.1 “完美”的验收定义

| 维度 | 必须做到 |
|---|---|
| 真 chroot | 非 proot；内核原生 syscall；Go 编译产物直接执行；`uname -r` 与宿主一致 |
| 完整 Android 视图 | `/data`（含 `/data/user/0`、`/data/data`、`/data/system`）、`/mnt`、`/data_mirror`、`/storage/emulated/0`、`/sdcard` 全部可见 |
| 完整 Android 控制 | `am/pm/cmd/settings/input/screencap/screenrecord/dumpsys/content/appops/monkey/ime/wm/svc` 可用；`uiautomator` 仅 `runtest`（本机无 `dump`）；binder/ashmem 可用 |
| 完整设备节点 | `/dev` rbind；`/dev/block`、`/dev/binder*`、`/dev/ashmem`、`/dev/pts`、`/dev/shm` 全见 |
| 完整内核视图 | `/proc`、`/sys` rw、`debugfs`、`tracefs`、`/proc/kallsyms` |
| 完整 root | chroot 内 uid 0；full capabilities；不 setpriv/不降 UID；**全局 enforce=1，但 `u:r:ksu:s0` 是 permissive allow-all 域**；SELinux 不是 chroot 的边界 |
| 完整存储 | `/data` rw、`/sdcard` raw rw、`/storage` rw、`/mnt` rw；镜像 128GB；不做 workspace 收窄 |
| 完整生命周期 | 开机自启、s6 监督、启停、诊断、快照/回滚、卸载清理 |
| 可选 | `guard` 规则、`system-rw` overlay、`protect-critical` 只读保护——都**不是默认** |

**明确不做**：least privilege、UID1、最小 `/dev`、只挂 `/sdcard/DSH`、`/data` 只读、`/mnt` 只读、workspace 收窄、空间裁剪。  
**风险声明**：这是“能力最大化”设计；`/data`、`/dev/block`、`/metadata`、`/mnt/vendor/persist` 写入都可能造成不可逆后果。按你的要求，不用权限/挂载限制去兜底；只保留可选 guard/备份。

---

## 1. 平台事实（不变）

| 项 | 事实 | 影响 |
|---|---|---|
| Root | SukiSU 4.2.1-r2；`u:r:ksu:s0` | chroot root；无 userns/pidns |
| 内核 | 6.6.66 GKI；`USER_NS`/`PID_NS`/`LANDLOCK` 未编译 | 没有文件系统隔离；只有 mount ns/chroot/caps |
| `/data` | f2fs `nosuid,nodev`，无 `noexec` | rootfs 可执行；image 放 `/data/media/0` |
| `/sdcard` | FUSE `noexec` | 执行必须走 raw `/data/media/0` |
| loop | 仅 `/data/media/0` 可用（`/data/adb` FBE EIO） | 镜像放 `/data/media/0/.dsh/rootfs.img` |
| 快照 | f2fs 无 reflink；硬链接原地写污染 | 冷镜像复制/tar；不做“假快照” |

**永远成立**：chroot 内 root 可通过 `/proc/1/root` 访问宿主 `/`；`/dev/block`、`/data` 不挂也能到。v1.4 不再把任何挂载限制写成安全边界。

### 1.1 Root 隐藏性不变量（硬约束）

1. **全局 SELinux 保持 Enforcing，但承认 `u:r:ksu:s0` 是 permissive allow-all**：不默认 `setenforce 0`；`--permissive` 仅显式 break-glass，且要明确“本次接受隐藏性下降”。SELinux 不是 chroot 的约束；hardguard 只是护栏。**绝不对 `/sys/fs/selinux` 做 bind/remount,ro**（本次事故的高度怀疑关联操作；机制未证实，但一律禁止）；要限制用 private ns 的 tmpfs 遮罩 + hardguard。
2. **不改全局 prop**：不用 `resetprop` 改 `ro.debuggable`/`ro.secure`/`init.svc.*` 等；不伪造 root 状态。
3. **挂载不暴露到宿主 namespace**：所有 chroot 挂载在私有 mount ns；`make-rprivate`；不做全局 bind/共享挂载。
4. **不把模块目录 bind 进 chroot**：避免 KSU 挂载视图穿透/被检测。
5. **数据目录最小可见**：`/data/adb/chroot` 0700；镜像目录 `/data/media/0/.dsh` 0700 + `.nomedia`；`/sdcard/DSH` 只放工作区，不放 root 标记。
6. **保留 SukiSU 隐藏特性**：`su_compat`/`kernel_umount`/`selinux_hide` 保持启用；不在 chroot 内改 SELinux 策略。
7. **不改 `/system`**：模块不落 `/system`；`system-rw` overlay 只在私有 ns，可随时关。
8. **Loop 设备**：镜像 loop 是 Android 常见形态（APEX 也用 loop）；不创建可疑命名/多余 loop；镜像文件 0700、`.nomedia`。
9. **服务只监听 loopback**：DSH/ttyd 绑定 `127.0.0.1`；不 `0.0.0.0`；端口不做广告。
10. **崩溃恢复**：watchdog 在 DSH 异常退出时恢复 SELinux 原状态；不留全局 permissive。
11. **root-free 的硬例外**：`setenforce`、写 `/sys/fs/selinux/*`、`resetprop` 修改 `ro.*`/`init.svc.*`/`persist.*`、全局 mount 传播——这些始终由 `hardguard` 拦截；它们不是普通权限，是 root 隐藏性的根。

**便利性最大化**：在上述不变量内，root-free、全 `/data`/`/dev`/`/sys`、完整 Android CLI、debugfs/tracefs、128GB 镜像、`protect-critical` 默认 off、`system-rw` 可选——全部保留。

---

## 2. 单一 full 档 + rescue

| 维度 | `full`（默认，唯一运行档） | `rescue`（手动维修） |
|---|---|---|
| 目的 | 完美 chroot + 完美手机控制 | 修 rootfs/DSH/镜像 |
| rootfs | ext4 镜像 128GB（`/data/media/0/.dsh/rootfs.img`） | 同一镜像 ro，或临时目录 |
| `/data` | **rbind rw** | 不挂 |
| `/mnt`/`/data_mirror` | **rbind rw** | 不挂 |
| `/storage`/`/sdcard` | **rbind/bind rw**（完整） | 不挂 |
| `/dev` | **rbind**（block/binder/ashmem 全见） | 最小 tmpfs |
| `/sys` | **rw**；debugfs/tracefs rw | ro |
| Android 分区 | `/system` 等 rbind/bind ro（硬件只读）；可选 `system-rw` overlay | 不挂 |
| Android CLI | 全量包装器 + `ANDROID_*` | 不保证 |
| DSH | 启动；`root-free` 默认 | 不启动，只 `/bin/bash` |
| s6 | 是 | 否 |
| 规则 | 默认 free；`guard` 可选 | 无 |
| 快照 | 128GB 镜像冷复制 + state | 无 |
| SELinux | **默认 Enforcing**；`--permissive` 仅显式 break-glass（会降低隐藏性） | Enforcing |
| 开机 | 默认 full | 手动 |

**不再有 daily/lab 切换**。如果用户以后要“最小面”，那是另一个可选的 `guard`/`rescue`，不是主设计。

### 2.1 SELinux permissive 的代价（为什么默认不用）

- **全局生效**：`setenforce 0` 影响整机所有域，不只是 chroot；运行期间所有进程都少了 SELinux 这一层。
- **不是“只放开 chroot”**：恶意 App、被注入的进程、系统服务都同样受益；这是安全取舍，不是挂载技巧。
- **不要把它当成“App 一定看得见/看不见”**：untrusted_app 对 `/sys/fs/selinux/enforce` 有 neverallow；KernelSU `selinux_hide` 还会对 UID≥10000 返回 fake status page（源码 `my_sel_open_handle_status`）。所以普通 App 不一定能直接读到 permissive；但 root/特权扫描器、adb、某些检测工具仍可能看到。它是全局安全降级，不是隐藏手段。
- **Play Integrity 不应被简化成“读 SELinux”**：PIF Detector 的检测引擎明确“没有 SELinux check”，其核心是 key attestation/启动状态等；Play Integrity 的具体 verdict 不公开且会变化，不能保证 permissive 对它是“无影响”。
- **崩溃未恢复**：DSH 崩溃/被强杀时，如果没人恢复，设备会保持 permissive 到重启；需要 watchdog + stop 恢复。
- **`getenforce` 结果不稳定**：本机一次 root `getenforce` 返回 `Disabled`，另一次返回 `Enforcing`；app 返回 `Permission denied`；root `cat /sys/fs/selinux/enforce` = `1`。doctor 只用 `/sys/fs/selinux/enforce` + 域状态，不把 `getenforce` 当唯一判据。
- **不改变 DAC/capabilities**：permissive 只去掉 SELinux 拒绝；root、文件权限、能力仍在。
- **v1.4 结论**：为了不降低 root 隐藏性，**默认 Enforcing**；`--permissive` 只作为显式 break-glass（需要你确认“本次接受隐藏性下降”），stop/watchdog 恢复。若某个工具必须 permissive，优先用窄域/单次操作，而不是全局 `setenforce 0`。


---

## 3. 架构

```
L4 接入     DSH web (loopback token) / ttyd (可选) / 手机浏览器 / PC adb forward
L3 策略     DSH permission preset root-free (danger-full-access + never) 默认
            hardguard 始终开启（SELinux/prop/mount 传播硬例外）
            可选 root-guard (danger-full-access + ask) + hooks deny/ask
            审计 PostToolUse 始终开启
L2 运行时   Node 24 + DSH versions/current/previous + profile web
            s6-svscan (dsh, ttyd) + dsh-run (URL/ready)
L1 容器     ext4 128GB rootfs + full mount set + chroot
            /data /mnt /data_mirror /storage /sdcard /dev /sys /proc
            Android 分区 + debugfs/tracefs + Android 命令包装器
L0 宿主     KSU module: service.sh + boot-completed.sh
            dsh-ctl: doctor/start/stop/status/diag/snapshot/restore/guard/system-rw/purge
```

**原则**：
- L1 追求“与宿主等价”，不是“最小面”。
- L2 只跑一个 Agent（DSH）；不再引入 mini-panel/Mini Agent。
- L3 默认 free（不拦），`guard` 是可选开关；审计始终记录。
- L0 只做生命周期，不做业务。

---

## 4. rootfs：128GB ext4 镜像

### 4.1 为什么用镜像

- `/data` 1:1 rbind 需要 rootfs 不在 `/data` 子树内；镜像挂到 `/tmp/dsh-rootfs`，既不在 `/data` 也不在 `/mnt`，**彻底避免自递归**。
- 空间不是约束：128GB sparse 镜像，实际 rootfs 12–16GB；`resize2fs` 可扩。
- 快照=镜像文件冷复制；回滚=换镜像；比目录 tar 更“整存整删”。
- 镜像必须放 `/data/media/0/.dsh/rootfs.img`（FBE 加密区 loop 会 EIO）。
- 目录权限：`chmod 700 /data/media/0/.dsh`；放 `.nomedia`；rootfs.img 0600。这样普通 App/文件管理器看不到镜像，不增加 root 可见标记。

### 4.2 创建/扩容

```sh
# 首次：从烘焙好的 rootfs 目录建镜像
mke2fs -t ext4 -b 4096 -d /data/adb/chroot/rootfs-staging /data/media/0/.dsh/rootfs.img 128G
# 或 truncate + mke2fs + mount + rsync
truncate -s 128G /data/media/0/.dsh/rootfs.img
mke2fs -t ext4 -b 4096 /data/media/0/.dsh/rootfs.img
mount -o loop /data/media/0/.dsh/rootfs.img /tmp/dsh-rootfs
rsync -aHAX --numeric-ids /data/adb/chroot/rootfs-staging/ /tmp/dsh-rootfs/
```

- `losetup --sector-size 4096 --direct-io=off` 需要 util-linux；toybox/busybox 不支持。模块 `bin/` 自带静态 util-linux `losetup`，或预生成镜像后 push。
- `e2fsck -p` / `resize2fs` 来源要固定（模块自带或 rootfs 内 e2fsprogs）；不要假设 `/system/bin` 全有。
- 镜像 128GB 默认；实际使用 12–16GB；`df` 看的是 ext4 内部，不是文件 apparent size。

### 4.2.1 128GB vs 256GB

| 维度 | 128GB | 256GB |
|---|---|---|
| 实际 rootfs 占用 | 12–16GB 起 | 同 |
| 可容纳 | 完整工具链 + 多项目 + 缓存 + 若干模型 | 大量本地模型/Android 源码/大型构建缓存 |
| ext4 元数据/日志 | 较小 | 更大（inode/bitmap/日志），`mke2fs`/`fsck` 更慢 |
| 快照成本 | 7 份稀疏复制，按实际块计 | 同，但单份上限更大；填满后 7× 更大 |
| `/data` 余量 | 更稳（/data 已用 250GB/935GB） | 更容易逼近上限，尤其快照多份时 |
| 扩容 | 可 `resize2fs` 在线/离线增大 | 不能再 shrink；只能继续增 |
| 适用 | 日常 + 大多数开发 | 本地大模型/超大仓库/长期不清理 |

**建议**：**128GB 起步**，不够再 `resize2fs` 增到 256GB；ext4 只能增不能减，先小后大更稳。若你确定要放本地 GGUF/Android 源码/大量构建缓存，直接 256GB。


### 4.3 镜像生命周期

- start：`e2fsck -p` → `losetup --sector-size 4096` → 在 namespace 内 `mount -t ext4` 到 `/tmp/dsh-rootfs` → chroot。
- stop：namespace 内 `sync` → `remount,ro` → `umount` → 宿主 `losetup -d`。
- 快照：stop 后 `cp --sparse=always rootfs.img snapshots/rootfs-<ts>.img`（空间不优化；保留 7 份）；`sync`；sha256。
- 回滚：stop → 校验 → 当前镜像改名 `rootfs.bad.<ts>.img` → 新镜像 rename → start → smoke；失败切回。
- 扩容：stop → `e2fsck -f` → `truncate -s +64G` → `losetup` → `resize2fs`；或直接换更大镜像。

---

## 5. 挂载表（full）

### 5.1 基础

| chroot 内路径 | 源 | 类型/选项 | 说明 |
|---|---|---|---|
| `/` | `/tmp/dsh-rootfs` | ext4 | 128GB 镜像 |
| `/proc` | `proc` | `proc,nosuid,noexec,nodev` | 完整进程视图 |
| `/sys` | `sysfs` | **rw** | 完整内核/设备视图 |
| `/sys/fs/selinux` | — | **不要单独 bind/remount** | 2026-10-26 事故关联（机制未证实）：`mount -o remount,ro,bind /sys/fs/selinux` 之后出现 zygote SELinux 上下文失败；mount(2) 说 remount-bind 只影响该挂载点，因此不能把它当已证实的单点根因；一律禁止。zygote `selinux_android_setcontext` 写 `/sys/fs/selinux/context` 失败是现象，不等于证明 remount 是唯一原因。如需限制，用 private ns 的 tmpfs 遮罩 + hardguard，绝不在 selinuxfs 上 remount |
| `/dev` | `/dev` | **rbind** `nosuid`（去 noexec） | block/binder/ashmem/usb-ffs 全见 |
| `/dev/pts` | `devpts` | `newinstance,ptmxmode=0666,mode=620` | 独立 pts；`/dev/ptmx` → `pts/ptmx` |
| `/dev/shm` | tmpfs | `size=1g,mode=1777` | Chromium/Playwright |
| `/run` | tmpfs | `size=1g,mode=755` | s6/运行时 |
| `/tmp` | tmpfs | `size=4g,mode=1777` | `TMPDIR`；空间不优化 |
| `/root/.dsh` | `state/dsh-home` | bind rw | DSH 配置/会话/凭据 |
| `/run/dsh` | `state/guest` | bind rw | URL/ready/ttyd.pass |
| `/opt/dsh-control` | `control` | bind ro | hooks/s6/bin |
| `/opt/dsh` | `dsh` | bind ro | DSH 版本 |
| `/var/log/dsh` | `logs` | bind rw | 审计/日志 |
| `/etc/resolv.conf` | 生成 | 文件 | 8.8.8.8/1.1.1.1 |

### 5.2 完整 Android 视图

| chroot 内路径 | 源 | 类型/选项 | 说明 |
|---|---|---|---|
| `/data` | `/data` | **rbind rw** | 含 `/data/user/0`、`/data/data`、`/data/system`、`/data/adb` |
| `/mnt` | `/mnt` | **rbind rw** | 多用户视图；含 `/mnt/user/0`、`/mnt/installer/0`、`/mnt/androidwritable/0` |
| `/data_mirror` | `/data_mirror` | **rbind rw** | Android 11+ 多用户镜像 |
| `/storage` | `/storage` | **rbind rw** | 含 `/storage/self`、`/storage/emulated`、各用户视图；只挂 `/storage/emulated/0` 不完整 |
| `/sdcard` | `/data/media/0` | bind rw | raw；exec；完整内部存储 |
| `/system` | `/system` | bind/`rbind` | 硬件 ro；可选 overlay rw |
| `/system_ext` | `/system_ext` | rbind | ro |
| `/product` | `/product` | rbind | ro |
| `/vendor` | `/vendor` | rbind | ro；firmware/dsp 子挂载 |
| `/odm` | `/odm` | rbind | ro |
| `/apex` | `/apex` | **rbind**（顶层 ro 不递归） | plain bind 是空壳；`remount,ro,bind` 只到顶层，`/apex/.../cacerts` 仍 rw；要整树 ro 用 `mount_setattr(AT_RECURSIVE)` |
| `/linkerconfig` | `/linkerconfig` | bind；如可递归再 ro | 源是 rw tmpfs；`remount,ro,bind` 不递归，不能声称整树只读 |
| `/metadata` | `/metadata` | **bind rw** | 完整控制；可选 `--protect-critical` 改 ro |
| `/mnt/vendor/persist` | 同名 | rw（随 `/mnt`） | 完整控制；可选 protect 改 ro |
| `/mnt/vendor/qmcs` | 同名 | rw（随 `/mnt`） | 完整控制；可选 protect 改 ro |
| `*_dlkm`/`oem`/`cust`/`my_*` | 存在则 | bind | 硬件 ro |
| `/sys/kernel/debug` | debugfs/宿主 bind | rw | BPF/kprobe/uprobe |
| `/sys/kernel/tracing` | tracefs/宿主 bind | rw | ftrace/perf |

**P0 别名警告**：`/data` rbind 会同时暴露 `/data/user/0`、`/data_mirror/data_ce/null/0`、`/mnt/pass_through/0/emulated`、`/mnt/installer/0/emulated`、`/mnt/androidwritable/0/emulated`、`/mnt/user/0/emulated`、`/storage/emulated` 等别名；只遮 `/data/media/0/.dsh` + `/sdcard/.dsh` 不够，镜像/控制面仍可经别名写。要么不 full `/data`，要么枚举所有别名并测试（见决策 14）。

**说明**：`/system` 等 erofs 物理只读；`bind/ rbind` 后 `-o ro` 会被忽略，`remount,ro,bind` 也只到顶层。要整树只读需 `mount_setattr(AT_RECURSIVE)`（util-linux/mount 支持时）并验证子挂载；否则如实写“顶层只读、子挂载可能 rw”。要写用 `dsh-ctl system-rw on` 的 overlay。**upper/work 必须放在 ext4 rootfs 内**（如 `/var/lib/dsh/overlays/<part>`），不能放 `/data`（f2fs 实测 `overlay: case-insensitive capable filesystem ... not supported`，EINVAL）。不是 `remount,rw`。`/metadata`/persist/qmcs 默认 rw（按“不要权限收束”）；`--protect-critical` 可一键改 ro，属于可选防手滑，不是默认限制。

### 5.3 挂载顺序

1. `busybox unshare --mount --propagation private --uts -m`（UTS 可用；**IPC_NS 未编译，`--ipc` 实测 EINVAL**；PID ns 不可用）
2. `busybox mount --make-rprivate /`
3. `/proc` → `/sys` → `/dev` → `/dev/pts` → `/dev/shm` → `/run`/`/tmp` → Android 分区 → `/data` → `/mnt` → `/data_mirror` → `/storage` → `/sdcard` → 控制面 → chroot
4. 每个 `rbind` 后 `busybox mount --make-rprivate "$dst"`
5. stop：namespace 内逆序；`umount -l` 只作最后手段并记录

---

## 6. 完整手机控制

### 6.1 Android 运行时环境

`/etc/profile.d/dsh-android.sh`：

```sh
export ANDROID_DATA=/data
export ANDROID_ROOT=/system
export ANDROID_ART_ROOT=/apex/com.android.art
export ANDROID_I18N_ROOT=/apex/com.android.i18n
export ANDROID_TZDATA_ROOT=/apex/com.android.tzdata
export PATH="$PATH:/system/bin:/system/xbin:/vendor/bin:/product/bin:/system_ext/bin:/odm/bin"
```

`/usr/local/bin` 包装器（指向 `/system/bin/<name>`，不覆盖 rootfs 已有同名命令）：

`am dumpsys cmd settings pm getprop input appops content ime monkey screencap screenrecord svc wm`

### 6.2 控制能力清单

| 能力 | 命令/路径 | 说明 |
|---|---|---|
| 应用管理 | `pm list/install/uninstall/clear/disable/enable` | 完整 `/data` |
| Activity/服务 | `am start/stop/force-stop/broadcast` | 完整 |
| 设置 | `settings get/put/list` | 完整 |
| 输入 | `input tap/swipe/text/keyevent` | 完整 |
| UI 自动化 | `uiautomator runtest`（需测试 jar） | **`uiautomator dump` 本机 rc=137，不可用**；可用 input+screencap 兜底 |
| 截屏/录屏 | `screencap -p` / `screenrecord` | `/dev/graphics`/`/dev` |
| 窗口 | `wm size/density` | 完整 |
| 通知/状态栏 | `cmd notification/statusbar` | 完整 |
| Binder | `/dev/binder`、`/dev/hwbinder`、`/dev/vndbinder` | `/dev` rbind |
| 内存 | `/proc/<pid>/mem`、`/proc/kallsyms` | `/proc` 完整 |
| 内核调试 | `debugfs`/`tracefs`/`kprobe` | `/sys` rw |
| 逆向 | `frida`/`stackplz`/`strace`/`ltrace` | 可选安装；`/dev/ashmem` |
| 文件 | `/data`、`/sdcard`、`/storage`、`/mnt` | 完整 |
| 包/系统分区 | `/system` 等 | 只读；可选 overlay |

### 6.3 `system-rw` overlay（可选）

```sh
dsh-ctl system-rw on system vendor product odm system_ext
# 内部：upper/work 在 ext4 rootfs 的 /var/lib/dsh/overlays/<part>（不能放 f2fs /data）
# mount -t overlay overlay -o lowerdir=/<part>,upperdir=...,workdir=... /<part>
```

- 只对需要写的分区开；默认关闭。本机只验证了“ext4 upper + 临时 target”的 overlay 成功；**实际 `/system` 挂载点 + APEX/linker/厂商分区未验证**，所以不默认开。
- overlay 可能影响 APEX/linker/系统属性；先在一个分区试，smoke 通过再扩。
- 空间不是约束：upper 放 `/data` 即可。

---

## 7. 生命周期（full）

### 7.1 start

```
dsh-ctl start [--permissive] [--protect-critical]  # 默认 Enforcing；--permissive 会降低 root 隐藏性
  1. mkdir state/ctl/lock（原子；写 owner pid/starttime；stale 则清理）——不要用 toybox flock 的文件形式
  2. doctor：root、namespaces、chroot、mount、seccomp、SELinux（读 /sys/fs/selinux/enforce）、TMPDIR、镜像、losetup、node-pty、DSH
  3. mkdir -p /tmp/dsh-rootfs && chmod 700 /tmp/dsh-rootfs；e2fsck -p 镜像；losetup --sector-size 4096 --direct-io=off；记录 loopdev；检查同一 backing file 没有第二个 loop
  4. setsid busybox unshare --mount --propagation private --uts -m /data/adb/chroot/bin/dsh-nsinit
     # dsh-nsinit（宿主侧脚本）分两段：
     #   host 段：ROOT=/tmp/dsh-rootfs（/tmp 是宿主 tmpfs，/run 在本机不存在）；mount 镜像到 ROOT；
     #            所有 guest mount 都写成 $ROOT/proc、$ROOT/sys、$ROOT/dev、$ROOT/data、$ROOT/mnt、$ROOT/sdcard...
     #            mount tmpfs $ROOT/run、$ROOT/tmp；bind control 到 $ROOT/opt/dsh-control；
     #            cp -a $ROOT/opt/dsh-control/s6/. $ROOT/run/s6/
     #   chroot 段：exec chroot "$ROOT" /usr/bin/env -i PATH=... /bin/sh -c
     #            'echo $$ > /run/dsh/launcher.pid; exec s6-svscan /run/s6'
  5. 从 state/guest/launcher.pid 读 PID；记录 PGID、/proc/PID/stat starttime、/proc/PID/ns/mnt inode
  6. 等 state/guest/url 或 nsenter -t PID -m chroot /tmp/dsh-rootfs /usr/bin/s6-svstat /run/s6/dsh
  7. 失败：先保留 holder 做逆序清理，再 loop detach、degraded
```

**关键**：`dsh-nsinit` 是长驻 namespace holder；s6-svscan 是它的子进程。holder 活着时才能安全 unmount；不要先杀 holder 再 umount。

### 7.2 stop

```
dsh-ctl stop
  1. mkdir lock；写 state/ctl/stopped + 各服务 down
  2. 读 PID/starttime/ns inode；校验一致；不一致按 stale 处理并扫 /proc/*/ns/mnt
  3. nsenter -t PID -m chroot /tmp/dsh-rootfs /bin/sh -c 'sync; sync'
  4. for s in dsh ttyd: nsenter -t PID -m chroot /tmp/dsh-rootfs /usr/bin/s6-svc -d /run/s6/$s
  5. for s: nsenter -t PID -m chroot /tmp/dsh-rootfs /usr/bin/s6-svwait -d -t 10000 /run/s6/$s
  6. 超时：s6-svc -k；PGID TERM；等 5–10s；仍不退再 KILL；扫描 /proc/*/ns/mnt inode（不是 grep mountinfo 路径）
  7. 保持 holder 活着：nsenter -t PID -m chroot /tmp/dsh-rootfs /bin/sh -c '逆序 umount -R /data /mnt /data_mirror /storage /sdcard /dev /sys /proc；remount,ro /'
  8. 确认无同 ns inode 的进程后，KILL holder；宿主 losetup -d；等待 loop 真正消失（losetup -d 是 lazy，不能只看 rc=0）
  9. 恢复 SELinux 原状态（若启动时切了 permissive）；删 lock/pid；保留 logs；写 stopped
```

**注意**：toybox `nsenter` 没有 `-r/--root`；必须 `nsenter -t PID -m chroot $ROOT ...`。`/proc/PID/mountinfo` 第 5 字段是相对该进程 root 的路径，不能用 `grep $ROOT` 做通过条件；用 `/proc/*/ns/mnt` inode 对比。

### 7.3 宿主 watchdog（当前缺失，必须实现）

- 独立于 DSH/s6：30s 检查 holder PID/starttime/ns inode；异常则 `sync`、恢复 SELinux 原状态、清理/重启。
- 开机后断言 `/sys/fs/selinux/enforce` = 1；若启动时切过 permissive，必须确认已恢复。
- v1.4 只写了“watchdog 会恢复”，但没有组件；在实现前不能把这条当已满足。

### 7.4 命令

`doctor/start/stop/restart/status/diag/log/snapshot/restore/guard/system-rw/rescue/purge/fixperm`。

- `guard on|off`：启用/关闭 deny/ask；默认 off。
- `system-rw on|off`：可选 overlay。
- `snapshot/restore`：镜像 + state。
- `rescue`：最小挂载 + `/bin/bash`。
- `purge`：stop + 校验 + 删除 `/data/adb/chroot` **和** `/data/media/0/.dsh`（镜像、快照、`.nomedia`），并确认无 loop；`--keep-image` 可保留。

---

## 8. DSH 运行时

- Node 24 + DSH `versions/<v>` + `current`/`previous`；`/opt/node`。
- `dsh-run`：`node ... web --host 127.0.0.1 --port 0 --no-open`；抓 URL 写 `/run/dsh/url`；转发信号；日志 `/var/log/dsh/dsh.log`。
- s6：`s6-svscan /run/s6`；`dsh`/`ttyd`；`finish` 退避；`down`。
- DSH 配置：
  - `fs-sandbox.cwd: /sdcard/DSH/workspace`
  - `workspaceRoot: /sdcard/DSH/workspace`
  - 默认预设 `root-free`：`danger-full-access` + `approval: never`（不弹批准；完整控制）
- **`hardguard` 始终启用**：即使 `root-free`，也拦 `setenforce`、`/sys/fs/selinux` 写入、`resetprop` 改全局 prop、全局 mount 传播；这是 root 隐藏性护栏，不是普通权限收束；它不能保证隐藏性
  - 可选 `root-guard`：`danger-full-access` + `ask` + hooks（deny/ask）
  - 默认 `root-free`；`/permission` 可切 `root-guard`
- 工作区：`/sdcard/DSH/workspace`；`chmod 2777`；`umask 000`；文件 0666；`dsh-rm` 回收站。
- `npm_config_unsafe_perm=true`；`env -i PATH=… TMPDIR=/tmp`；`jq`；`ca-certificates`。
- `ttyd` 可选；127.0.0.1；密码 `state/guest/ttyd.pass`。

**规则层**：默认不启用 deny/ask；`guard on` 时才加载 hooks。审计始终记录（PostToolUse）。v1.4 不再把规则当能力边界。

---

## 9. 审计与快照（保留，但不是限制）

- 审计：`/var/log/dsh/audit/YYYY-MM-DD.jsonl`；字段 ts/profile/tool/cwd/command/decision/exit/duration/output_head；脱敏；轮转。
- 快照：镜像 `cp --sparse=always` 7 份 + state tar 7 份；manifest sha256/DSH 版本/时间；空间不优化，保留多份。
- `/sdcard`/`/data`：完整 rw；若要做全量备份，用 `restic` 到 PC/NAS（空间不优化，按需）。
- 回滚：stop → 校验 → 当前镜像改名 → 新镜像 rename → start → smoke；失败切回。
- 卸载：stop + 全机扫描 + `losetup -d`；模块卸载保留 `/data/adb/chroot`；`purge` 显式全删（含 `/data/media/0/.dsh` 镜像/快照）。

---

## 10. 打包

- 模块 zip：`module.prop/customize.sh/service.sh/boot-completed.sh/uninstall.sh/action.sh/bin/dsh-ctl/bin/boot-bootstrap/bin/dsh-nsinit/control/*`；包根禁止 `install.sh`。
- 工具链必须自带：静态 `losetup`（util-linux，支持 `--sector-size`）、`mke2fs/e2fsck/resize2fs`、支持 `--sparse` 的 `cp`（或改用 `tar`）、`flock`（或改用 `mkdir` 锁）；toybox 的 `cp`/`losetup` 不满足。
- payload：`rootfs.tar.gz`（Ubuntu 24.04 + Node 24 + Go + Python + build-essential + s6/ttyd + Android 工具依赖；胖包，不裁空间）。
- 镜像：首次 `mke2fs 128G` + rsync；或预生成 `rootfs.img` push 到 `/data/media/0/.dsh/`。
- 构建门禁：busybox tar -xzf、mksh、shellcheck、node-pty、dsh web、doctor、full P0。
- `action.sh`：`status/start/stop/restart/url/log/diag/system-rw/guard`；长任务后台，不同步跑部署。

---

## 11. 验收（full P0）

1. chroot 内 `id`=0、`uname -r` 宿主一致；Go 在 `/sdcard` 编译并运行。
2. `node-pty` PTY；`dsh web --port 0 --no-open` URL + curl 200/303；`root-free` 可用。
3. `/data` rbind：`/data/user/0`、`/data/data`、`/data/system` 可见；`/mnt`、`/data_mirror`、`/storage`、`/sdcard` 完整。
4. `/dev/block`、binder/ashmem、`/dev/pts`、`/dev/shm` 可见；`/sys` rw；debugfs/tracefs 可读。
5. Android CLI：`am`（无参数出 help）、`pm list packages`、`input`、`screencap -p /tmp/x.png`、`screenrecord --time-limit 1`、`cmd notification help` 可用；`uiautomator dump` 本机不可用（rc=137），只承诺 `uiautomator runtest`（需 jar）。
6. `/apex` rbind 后 `com.android.runtime/bin` 非空；`/system` 等可见；`system-rw` overlay 可选通过。
7. stop：没有与 holder 相同 `/proc/*/ns/mnt` inode 的进程；`/proc/*/root` 无 rootfs 进程；无 launcher；loop 已真正消失（不是只看 `losetup -d` 返回 0）。
8. 镜像快照 → 破坏 → restore 一轮；state 同步；sha 校验。
9. 开机自启（service.sh + boot-completed，非阻塞，10–60s 就绪）；重启/强杀自愈。
10. `guard on` 时 deny/ask 生效；`guard off` 时审计仍记录。

---

## 12. 路线

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0（2–3 天） | 128GB 镜像 + full 挂载 + chroot + node-pty + DSH web | 1–3 |
| P1（2–3 天） | `/data`/`/mnt`/`/storage`/`/dev` 完整 + Android 包装器 + debugfs | 4–6 |
| P2（2–3 天） | `dsh-ctl` 全命令 + s6 + stop 扫描 + 快照/restore | 7–8 |
| P3（1 周） | 模块打包 + 开机自启 + 卸载/purge | 9 |
| P4（2–3 天） | 迁移 DSH 配置/插件；并行；`guard` 可选 | 10 |
| P5（可选） | `system-rw` overlay、frida/stackplz、mini-panel/native client | 不影响主线 |

---

## 13. 参考取舍

| 参考 | 采用 | 不采用 |
|---|---|---|
| `chroot-mcp-safe` | 完整 Android 挂载表、rbind、make-rprivate、nsenter stop、preflight、TMPDIR/resolv、Android 包装器、镜像模式 | 默认 full-access 的“安全话术”、默认 sshd/123456、单文件 4183 行、lazy umount 常规化 |
| `astrbot-mubuntu` | 薄应用层、Action 多动作、状态投影、默认不挂存储（v1.3 借鉴；v1.4 改为 full 完整存储） | nohup/pkill、同步部署、解压模块 zip、无快照 |
| `mini-panel` | 计划确认 UX、工具分类、lazy-ref/面板信息架构（可选） | AGPL 代码、Docker/DockRoot、双 Agent |
| `容器AI APK` | 原生 Compose/模型管理/工具循环 UX（可选客户端） | proot/Alpine、app 沙箱、无开机自启、专有代码 |
| `dsh-alpine-ksu` | 非阻塞启动、原子 rootfs、raw media、版本/回滚骨架 | Alpine/musl 补丁链 |
| `chroot-distro` | rootfs 安装/挂载/备份策略、fresh proc/devpts、能力探测 | 作为运行时依赖、GPL 代码复制 |

### 13.1 验证来源（2026-10-26）

- 本机 `ksud feature list`：`su_compat`/`kernel_umount`/`selinux_hide`/`mount_hide`/`avc_spoof` 已启用；`sulog`/`adb_root` 关闭。
- KernelSU `selinux_hide.c`（`my_sel_open_handle_status` 对 UID≥10000 返回 fake status page）：<https://github.com/tiann/KernelSU/blob/main/kernel/feature/selinux_hide.c>
- KernelSU PR #3457（feature: selinux hide）：<https://github.com/tiann/KernelSU/pull/3457>
- PIF Detector README（untrusted_app 对 `/sys/fs/selinux/enforce` 有 neverallow；其引擎无 SELinux check）：<https://raw.githubusercontent.com/IR0NBYTE/playIntegrityFixDetector/refs/heads/main/README.md>
- 本机验证详细命令/结果：`12-v1.4-verification.md`。
- 2026-10-26 系统崩溃事故复盘：`13-incident-2026-10-26-selinux-remount.md`（`selinuxfs` 只读 remount 的教训）。

---

## 14. 已定/待定（2026-10-26 更新）

**已按你的指令定稿**
- **权限全开**：`guard` 默认 off；`protect-critical` 默认 off；`/metadata`、`/mnt/vendor/persist`、`/mnt/vendor/qmcs` 默认 rw；`/data`、`/dev`、`/sys`、`/sdcard`、`/storage` 完整开放。
- **root-free**：DSH 默认 `danger-full-access + approval: never`；不弹批准；`root-guard` 仅作为可选开关。**硬例外**：`setenforce`/`/sys/fs/selinux`/`resetprop` 全局 prop/全局 mount 传播由 `hardguard` 尽量拦截；它只能降低误操作，不能保证 root 隐藏性。
- **默认策略尽量不降低 root 隐藏性**：全局 SELinux 默认 **Enforcing**，但 `u:r:ksu:s0` 本身是 permissive allow-all，**不能保证**隐藏性；`--permissive` 仅显式 break-glass；不做全局 prop 修改、不把 chroot 挂载暴露到宿主 namespace。
- **镜像**：128GB 起步，可 `resize2fs` 增到 256GB；ext4 不能缩。

**还需要你拍板**
1. **镜像最终值**：128GB 起步够用；如果你要放本地模型/大仓库，直接 256GB。
2. **system-rw**：`/system` 等物理 ro；要不要默认开 overlay 变可写？overlay 对 APEX/linker/厂商分区兼容性需要实测，默认仍建议 off、按需开。
3. **全量备份**：`/data` + `/sdcard` 是否 restic 全量到 PC/NAS（空间不优化，但时间/带宽）。

---

## 15. 一句话

v1.4 不再做“日常/实验”分档：**一个 full 档，128GB 镜像，完整 `/data`/`/mnt`/`/storage`/`/sdcard`/`/dev`/`/sys`，完整 Android 命令与 debugfs/tracefs，root 全能力；全局 SELinux Enforcing、域 permissive（不保证隐藏性）；规则默认 root-free（SELinux/prop 硬例外）、审计保留，`guard`/`system-rw`/`protect-critical` 都是可选开关。** 这就是“完美 chroot + 完美手机控制”的工程定义。
