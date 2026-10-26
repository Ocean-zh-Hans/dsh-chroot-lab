# 设计 v1.4（full-capability）：完整 chroot + 完整手机控制

- 日期：2026-10-26 ｜ 设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 取代：`10-design-v1.3.md`（v1.3 的 daily/lab 分档）。
- 用户新约束（2026-10-26）：**不考虑权限收束，不考虑空间占用；只做完美的 chroot，完美的手机控制。**
- 结论：**单一 `full` 档**，root + 全 Android 视图 + 全 `/dev` + 全 `/data` + `/mnt`/`/storage`/`/sdcard` + Android 命令包装器 + debugfs/tracefs；ext4 大镜像；规则层降为可选 `guard`，审计保留；`rescue` 只用于维修。

---

## 0. “完美”的验收定义

| 维度 | 必须做到 |
|---|---|
| 真 chroot | 非 proot；内核原生 syscall；Go 编译产物直接执行；`uname -r` 与宿主一致 |
| 完整 Android 视图 | `/data`（含 `/data/user/0`、`/data/data`、`/data/system`）、`/mnt`、`/data_mirror`、`/storage/emulated/0`、`/sdcard` 全部可见 |
| 完整 Android 控制 | `am/pm/cmd/settings/input/uiautomator/screencap/screenrecord/dumpsys/content/appops/monkey/ime/wm/svc` 可用；binder/ashmem 可用 |
| 完整设备节点 | `/dev` rbind；`/dev/block`、`/dev/binder*`、`/dev/ashmem`、`/dev/pts`、`/dev/shm` 全见 |
| 完整内核视图 | `/proc`、`/sys` rw、`debugfs`、`tracefs`、`/proc/kallsyms` |
| 完整 root | chroot 内 uid 0；full capabilities；不 setpriv/不降 UID；SELinux 默认 permissive（stop 恢复） |
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
| SELinux | 默认 permissive（`--enforcing` 可保持 Enforcing） | 默认 Enforcing |
| 开机 | 默认 full | 手动 |

**不再有 daily/lab 切换**。如果用户以后要“最小面”，那是另一个可选的 `guard`/`rescue`，不是主设计。

---

## 3. 架构

```
L4 接入     DSH web (loopback token) / ttyd (可选) / 手机浏览器 / PC adb forward
L3 策略     DSH permission preset root-free (danger-full-access + never) 默认
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

- `/data` 1:1 rbind 需要 rootfs 不在 `/data` 子树内；镜像挂到 `/run/dsh-rootfs`，既不在 `/data` 也不在 `/mnt`，**彻底避免自递归**。
- 空间不是约束：128GB sparse 镜像，实际 rootfs 12–16GB；`resize2fs` 可扩。
- 快照=镜像文件冷复制；回滚=换镜像；比目录 tar 更“整存整删”。
- 镜像必须放 `/data/media/0/.dsh/rootfs.img`（FBE 加密区 loop 会 EIO）。

### 4.2 创建/扩容

```sh
# 首次：从烘焙好的 rootfs 目录建镜像
mke2fs -t ext4 -b 4096 -d /data/adb/chroot/rootfs-staging /data/media/0/.dsh/rootfs.img 128G
# 或 truncate + mke2fs + mount + rsync
truncate -s 128G /data/media/0/.dsh/rootfs.img
mke2fs -t ext4 -b 4096 /data/media/0/.dsh/rootfs.img
mount -o loop /data/media/0/.dsh/rootfs.img /run/dsh-rootfs
rsync -aHAX --numeric-ids /data/adb/chroot/rootfs-staging/ /run/dsh-rootfs/
```

- `losetup --sector-size 4096 --direct-io=off` 需要 util-linux；toybox/busybox 不支持。模块 `bin/` 自带静态 util-linux `losetup`，或预生成镜像后 push。
- `e2fsck -p` / `resize2fs` 来源要固定（模块自带或 rootfs 内 e2fsprogs）；不要假设 `/system/bin` 全有。
- 镜像 128GB 默认；实际使用 12–16GB；`df` 看的是 ext4 内部，不是文件 apparent size。

### 4.3 镜像生命周期

- start：`e2fsck -p` → `losetup --sector-size 4096` → 在 namespace 内 `mount -t ext4` 到 `/run/dsh-rootfs` → chroot。
- stop：namespace 内 `sync` → `remount,ro` → `umount` → 宿主 `losetup -d`。
- 快照：stop 后 `cp --sparse=always rootfs.img snapshots/rootfs-<ts>.img`（空间不优化；保留 7 份）；`sync`；sha256。
- 回滚：stop → 校验 → 当前镜像改名 `rootfs.bad.<ts>.img` → 新镜像 rename → start → smoke；失败切回。
- 扩容：stop → `e2fsck -f` → `truncate -s +64G` → `losetup` → `resize2fs`；或直接换更大镜像。

---

## 5. 挂载表（full）

### 5.1 基础

| 客机路径 | 源 | 类型/选项 | 说明 |
|---|---|---|---|
| `/` | `/run/dsh-rootfs` | ext4 | 128GB 镜像 |
| `/proc` | `proc` | `proc,nosuid,noexec,nodev` | 完整进程视图 |
| `/sys` | `sysfs` | **rw** | 完整内核/设备视图 |
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

| 客机路径 | 源 | 类型/选项 | 说明 |
|---|---|---|---|
| `/data` | `/data` | **rbind rw** | 含 `/data/user/0`、`/data/data`、`/data/system`、`/data/adb` |
| `/mnt` | `/mnt` | **rbind rw** | 多用户视图；含 `/mnt/user/0`、`/mnt/installer/0`、`/mnt/androidwritable/0` |
| `/data_mirror` | `/data_mirror` | **rbind rw** | Android 11+ 多用户镜像 |
| `/storage/emulated/0` | `/storage/emulated/0` | **rbind rw** | Android/data、Android/obb 子挂载 |
| `/sdcard` | `/data/media/0` | bind rw | raw；exec；完整内部存储 |
| `/system` | `/system` | bind/`rbind` | 硬件 ro；可选 overlay rw |
| `/system_ext` | `/system_ext` | rbind | ro |
| `/product` | `/product` | rbind | ro |
| `/vendor` | `/vendor` | rbind | ro；firmware/dsp 子挂载 |
| `/odm` | `/odm` | rbind | ro |
| `/apex` | `/apex` | **rbind ro** | plain bind 是空壳 |
| `/linkerconfig` | `/linkerconfig` | bind ro | Android linker |
| `/metadata` | `/metadata` | **bind rw** | 完整控制；可选 `--protect-critical` 改 ro |
| `/mnt/vendor/persist` | 同名 | rw（随 `/mnt`） | 完整控制；可选 protect 改 ro |
| `/mnt/vendor/qmcs` | 同名 | rw（随 `/mnt`） | 完整控制；可选 protect 改 ro |
| `*_dlkm`/`oem`/`cust`/`my_*` | 存在则 | bind | 硬件 ro |
| `/sys/kernel/debug` | debugfs/宿主 bind | rw | BPF/kprobe/uprobe |
| `/sys/kernel/tracing` | tracefs/宿主 bind | rw | ftrace/perf |

**说明**：`/system` 等 erofs 物理只读；要写用 `dsh-ctl system-rw on` 的 overlay（upper/work 在 `/data/adb/chroot/overlays/<part>`），不是 `remount,rw`。`/metadata`/persist/qmcs 默认 rw（按“不要权限收束”）；`--protect-critical` 可一键改 ro，属于可选防手滑，不是默认限制。

### 5.3 挂载顺序

1. `busybox unshare --mount --propagation private --uts --ipc -m`（UTS/IPC 隔离可用；PID ns 不可用）
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
| UI 自动化 | `uiautomator dump` | 完整 |
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
# 内部：upper/work 在 /data/adb/chroot/overlays/<part>；
# mount -t overlay overlay -o lowerdir=/<part>,upperdir=...,workdir=... /<part>
```

- 只对需要写的分区开；默认关闭。
- overlay 可能影响 APEX/linker/系统属性；先在一个分区试，smoke 通过再扩。
- 空间不是约束：upper 放 `/data` 即可。

---

## 7. 生命周期（full）

### 7.1 start

```
dsh-ctl start [--enforcing] [--protect-critical]
  1. flock state/ctl/lock
  2. doctor：root、namespaces、chroot、mount、seccomp、SELinux、TMPDIR、镜像、losetup、node-pty、DSH
  3. e2fsck -p 镜像；losetup --sector-size 4096；记录 loopdev
  4. setsid busybox unshare --mount --propagation private --uts --ipc -m sh -c '
       mounts（镜像 mount 到 /run/dsh-rootfs）
       cp -a /opt/dsh-control/s6/. /run/s6/
       exec s6-svscan /run/s6'
  5. 记录 launcher PID/PGID/starttime、loopdev、profile=full
  6. 等 state/guest/url 或 nsenter s6-svstat；写 running
  7. 失败：namespace 内逆序清理、loop detach、degraded
```

### 7.2 stop

```
dsh-ctl stop
  1. flock；写 state/ctl/stopped + 各服务 down
  2. nsenter -t <launcher> -m sh -c 'sync; sync'
  3. for s in dsh ttyd: nsenter ... s6-svc -d /run/s6/$s
  4. for s: nsenter ... s6-svwait -d -t 10000 /run/s6/$s
  5. 超时 s6-svc -k；PGID TERM；等 5–10s；KILL
  6. 扫 /proc/*/root → rootfs 的进程，TERM/KILL
  7. nsenter -t <launcher> -m sh -c '逆序 umount；remount,ro 镜像'
  8. 宿主 losetup -d；扫 /proc/*/mountinfo 验证无 rootfs
  9. 恢复 SELinux 原状态（若启动时切了 permissive）；删 lock/pid；保留 logs；写 stopped
```

**注意**：`/run` 是 namespace 内 tmpfs；s6 控制必须 `nsenter`；宿主 `/proc/self/mountinfo` 不能校验私有 ns。

### 7.3 命令

`doctor/start/stop/restart/status/diag/log/snapshot/restore/guard/system-rw/rescue/purge/fixperm`。

- `guard on|off`：启用/关闭 deny/ask；默认 off。
- `system-rw on|off`：可选 overlay。
- `snapshot/restore`：镜像 + state。
- `rescue`：最小挂载 + `/bin/bash`。
- `purge`：stop + 校验 + 删除 `/data/adb/chroot`。

---

## 8. DSH 运行时

- Node 24 + DSH `versions/<v>` + `current`/`previous`；`/opt/node`。
- `dsh-run`：`node ... web --host 127.0.0.1 --port 0 --no-open`；抓 URL 写 `/run/dsh/url`；转发信号；日志 `/var/log/dsh/dsh.log`。
- s6：`s6-svscan /run/s6`；`dsh`/`ttyd`；`finish` 退避；`down`。
- DSH 配置：
  - `fs-sandbox.cwd: /sdcard/DSH/workspace`
  - `workspaceRoot: /sdcard/DSH/workspace`
  - 默认预设 `root-free`：`danger-full-access` + `approval: never`（不弹批准；完整控制）
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
- 卸载：stop + 全机扫描 + `losetup -d`；模块卸载保留 `/data/adb/chroot`；`purge` 显式全删。

---

## 10. 打包

- 模块 zip：`module.prop/customize.sh/service.sh/boot-completed.sh/uninstall.sh/action.sh/bin/dsh-ctl/bin/boot-bootstrap/control/*`；包根禁止 `install.sh`。
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
5. Android CLI：`am --help`、`pm list packages`、`input keyevent`、`screencap -p /tmp/x.png`、`uiautomator dump` 可用。
6. `/apex` rbind 后 `com.android.runtime/bin` 非空；`/system` 等可见；`system-rw` overlay 可选通过。
7. stop：全机 `/proc/*/mountinfo` 无 rootfs、`/proc/*/root` 无 rootfs 进程、无 launcher；镜像已 `losetup -d`。
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

---

## 14. 仍需你确认

1. **镜像大小**：128GB 默认；要不要 256GB？
2. **SELinux**：v1.4 默认 `permissive`（启动 `setenforce 0`，stop 恢复原状态）；`--enforcing` 可保持 Enforcing。确认是否接受这个全局切换。
3. **高危区**：`/metadata`、`/mnt/vendor/persist`、`/mnt/vendor/qmcs` 默认 rw；要不要默认 `--protect-critical` 改 ro？
4. **规则**：默认 `root-free`（不拦）还是默认 `root-guard`（ask/deny）？
5. **system-rw**：要不要默认给 `/system` 开 overlay，还是保持硬件 ro、按需开？
6. **全量备份**：`/data` + `/sdcard` 是否要 restic 全量到 PC/NAS（空间不优化，但时间/带宽）？

---

## 15. 一句话

v1.4 不再做“日常/实验”分档：**一个 full 档，128GB 镜像，完整 `/data`/`/mnt`/`/storage`/`/sdcard`/`/dev`/`/sys`，完整 Android 命令与 debugfs/tracefs，root 全能力；规则默认关闭、审计保留，`guard`/`system-rw`/`protect-critical` 都是可选开关。** 这就是“完美 chroot + 完美手机控制”的工程定义。
