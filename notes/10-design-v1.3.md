# 设计 v1.3（优化版）：全能 AI 的真 chroot 环境

- 日期：2026-10-26 ｜ 设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 取代：`05-design-v1.md`（v1.2）。v1.2 的 8 个 P0 评审见 `08-design-review.md`，实验证据见 `09-p0-evidence.md`。
- 新增参考：`chroot-mcp-safe`（full/lab 档实现）、`mini-panel`（面板/Agent 产品化）、`容器AI` APK（proot + 本地模型 + 原生 UX）。分析见 `analysis/`。
- 一句话：**三档运行（daily / lab / rescue）+ 双 rootfs 模式（目录 / ext4 镜像）+ 控制面 bind + s6 + DSH hooks；默认 daily，lab 全 Android 视图显式开启；不再把 chroot 当沙箱，也不再把“不绑 /dev/block”当安全边界。**

---

## 0. 结论先行

1. **默认 `daily`**：目录 rootfs、raw `/sdcard` 工作区、最小 `/dev`、`/data` 不挂、Android 分区不挂；DSH 可用、apt/Go 可用、可快照/回滚。适合日常。
2. **显式 `lab`**：ext4 镜像 rootfs（放 `/data/media/0`，挂 `/run/dsh-rootfs` 后再 chroot）+ `/data`/`/mnt`/`/storage`/`/dev` rbind + Android 分区 ro + debugfs/tracefs；适合 Agent 调试/逆向/设备控制。**必须显式开启，开机不自动进 lab。**
3. **`rescue`**：目录 rootfs、最小挂载、只起 `/bin/bash`，用于 DSH/rootfs 坏掉后的修复；不启动 DSH。
4. **控制面必须在客机可见**：`control/` → `/opt/dsh-control` ro，`logs/` → `/var/log/dsh` rw，`state/dsh-home` → `/root/.dsh`；否则 hooks/audit/s6 都是“宿主路径，客机看不到”。
5. **生命周期以 namespace 为中心**：`busybox unshare --propagation private -m`；stop 用 `nsenter` 进 namespace 做 `s6-svc`/sync/umount，再 PGID + `/proc/*/root` 扫描；不依赖宿主 `umount`。
6. **DSH 仍是 root、仍然不是沙箱**：`/proc/1/root` 就是宿主 `/`；规则层/审计层是护栏，真正的兜底是**冷快照 + `/sdcard/DSH` 外部备份 + 高危区 ro**。
7. **`.tar.zst` 全部改 `.tar.gz`**：KSU busybox tar 不支持 zstd；payload、快照、state 统一 gzip。
8. **默认不 `setenforce 0`**：需要 permissive 时用 `dsh-ctl lab --permissive --yes`，记录原状态并恢复；不做静默降权。

---

## 1. 设备事实与边界（沿用 v1.2 实测）

| 项 | 事实 | 对设计的约束 |
|---|---|---|
| 系统 | Android 15 / SDK 35 / 4KB 页 | 不需要 16KB 对齐；保留检查 |
| Root | SukiSU 4.2.1-r2；`su` → `u:r:ksu:s0` | chroot 在 ksu 域 root；无 userns/pidns |
| 内核 | 6.6.66 GKI；`USER_NS`/`PID_NS`/`LANDLOCK` 未编译 | 无文件系统隔离；只有 mount ns/chroot/UID/caps |
| 文件系统 | `/data` f2fs `nosuid,nodev`（无 noexec）；`/sdcard` FUSE `noexec` | rootfs 可执行；工作区必须 raw `/data/media/0` |
| loop | 仅 `/data/media/0` 可用（`/data/adb` FBE EIO） | lab 镜像必须放 `/data/media/0` |
| 快照 | f2fs 无 reflink；硬链接原地写会污染 | 冷 tar；硬链接只做不可变归档 |
| 网络 | Docker Hub 不可达；Ubuntu/GitHub 可达 | 官方 Ubuntu base + SHA256；不用 Docker Hub |

**最重要的边界**：chroot 内 root 可通过 `readlink /proc/1/root` 看到宿主 `/`；`/proc/1/root/dev/block`、`/proc/1/root/data` 可达。任何“只读挂载/不绑 /dev/block”都只是**防手滑**，不是安全边界。v1.3 不再把结构护栏写成“安全”。

---

## 2. 决策记录（ADR）

| ADR | 决策 | 理由 | 代价/备注 |
|---|---|---|---|
| ADR-001 | DSH 以 root 运行；不做用户态沙箱 | 用户要 apt/mount/设备控制；本机无 userns/Landlock | `/proc/1/root` 可逃逸；规则+备份兜底 |
| ADR-002 | 默认 `daily`，`lab` 显式开启 | 默认最小面；full Android 视图风险高 | 用户多一步 `dsh-ctl lab` |
| ADR-003 | daily 用**目录 rootfs**；lab 用**ext4 镜像** | 目录快照简单；镜像才能安全 rbind `/data`（避免自递归） | 镜像 8–20GB、loop/FBE 限制 |
| ADR-004 | daily `/dev` 最小 tmpfs；lab `rbind /dev` | daily 不需要块设备；lab 需要 binder/ashmem/debugfs | daily 失去 loop/USB；lab 暴露 `/dev/block`（规则兜） |
| ADR-005 | 默认不 `setenforce 0` | 安全状态可预期；避免全局副作用 | 若 Enforcing 阻止 chroot，报错并给 `--permissive` break-glass |
| ADR-006 | hooks 只做护栏；服务级自检不通过**不启动 DSH** | DSH hooks 是 fail-open | 一个脚本 bug 可能导致 DSH 起不来；可接受 |
| ADR-007 | `/sdcard/DSH` 用 restic + `.trash`；`/data` 不承诺可逆 | rootfs 快照救不了用户数据 | 需要 PC/NAS 备份目标 |
| ADR-008 | 不把 mini-panel 作为核心/依赖 | AGPL、Docker/DockRoot 路线冲突、Agent 重复 | 可选 sidecar/UX 参考 |
| ADR-009 | 不把 APK 的 proot/Alpine 纳入运行时 | 与 H1/H2/H3 冲突；musl 原生模块坑 | 只借鉴原生 UX/模型管理 |
| ADR-010 | s6-svscan + `dsh-run` 包装器 | 需要监督/URL/ready；DSH 不写 notification-fd | 多一个 Node/shell 包装器 |
| ADR-011 | 控制面 bind（ro）+ 运行目录 tmpfs | 客机可见；s6 可写 | 多一层目录设计 |
| ADR-012 | 快照/state 成对、原子、校验、空间守卫 | 避免“假回滚” | 需要停机/空间 |

---

## 3. 架构

```
L4  UI/接入     DSH web (loopback token) / ttyd (可选) / 手机浏览器 / PC adb forward
                 └─ 可选：mini-panel sidecar（lab only，AGPL，不默认）
                 └─ 可选：thin native client（参考 APK UX，不内置 proot）
L3  策略/审计   DSH permission preset root-full (danger-full-access + ask)
                 + hooks: PreToolUse deny/ask + PostToolUse JSONL
                 + selftest gate + trash/backup
L2  DSH 运行时  Node 24 + DSH versions/<v> + current/previous + profile web
                 + s6-svscan (dsh, ttyd) + dsh-run (URL/ready)
L1  容器/挂载   rootfs (dir | image) + profile daily/lab/rescue
                 + mount namespace + chroot + control/log/state binds
L0  宿主模块    KSU module: service.sh + boot-completed.sh
                 + dsh-ctl: doctor/start/stop/status/diag/snapshot/restore/lab/rescue
```

**关键分层原则**：
- L0 只做“启动/停止/挂载/监督/清理”，不写业务逻辑；
- L1 的 profile 决定“看到多少 Android”，不决定“是否安全”；
- L2 的 DSH 是唯一 Agent，不再跑第二个 Agent（mini-panel/Mini Agent 只做可选 UI）；
- L3 的规则/审计是护栏；L4 不直接执行命令。

---

## 4. 运行档（profiles）

| 维度 | `daily`（默认） | `lab`（显式） | `rescue`（手动） |
|---|---|---|---|
| 目的 | 日常 AI/开发/apt/Go | Agent 调试、逆向、设备控制 | 修 rootfs/DSH |
| rootfs | 目录 `/data/adb/chroot/rootfs` | ext4 镜像 `/data/media/0/.dsh/rootfs.img`，挂 `/run/dsh-rootfs` | 目录 rootfs |
| `/data` | **不挂** | **rbind rw**（含 `/data/user/0`） | 不挂 |
| `/sdcard` | raw `/data/media/0` rw | raw `/data/media/0` rw + `/storage/emulated/0` rbind rw | 不挂 |
| `/mnt`/`/data_mirror` | 不挂 | rbind rw；`persist`/`qmcs` ro | 不挂 |
| `/dev` | 最小 tmpfs（null/zero/full/random/urandom/tty/ptmx + devpts + shm） | rbind `/dev`（binder/ashmem/block 全见） | 最小 tmpfs |
| `/sys` | ro | rw；debugfs/tracefs rw | ro |
| Android 分区 | 不挂（需要时切 lab） | `/system`/`/system_ext`/`/product`/`/vendor`/`/odm` ro；`/apex` rbind ro；`/metadata` ro；`/linkerconfig` ro；`*_dlkm`/`oem`/`cust`/`my_*` ro | 不挂 |
| Android CLI | 不保证 | `am/pm/cmd/settings/input/...` 包装器 + `ANDROID_*` | 不保证 |
| DSH | 启动 | 启动 | 不启动，只 `/bin/bash` |
| s6 | 是 | 是 | 否 |
| 快照 | rootfs/state tar.gz | rootfs 内容 tar.gz + state；`/data` 不承诺 | 无 |
| 开机 | 默认 | **不自动**；需 `dsh-ctl lab --yes` | 手动 |
| 风险 | 低（相对） | 高：`/data` rw、`/dev/block`、debugfs | 中：root shell |

**切换规则**：
- `dsh-ctl lab --yes`：stop daily → 检查镜像（首次从 daily rootfs `mke2fs -d` 生成 8GB 镜像）/preflight → start lab；写 `state/ctl/profile=lab`，但**重启后仍回 daily**（除非 `--persist` 且 `state/ctl/lab.confirmed` 存在；默认不持久）。
- `dsh-ctl daily`：stop lab → 清理 loop/挂载 → start daily。
- `dsh-ctl rescue`：stop 任意 → 最小挂载 → `chroot /bin/bash`；退出即 stop。若只有 lab 镜像，rescue 以 ro 方式挂镜像；若镜像损坏，从 payload 解压临时目录。
- 任何 profile 切换写审计 `profile/switch`。

---

## 5. 目录（宿主）

```
/data/adb/chroot/
├── payload/                  rootfs.tar.gz + dsh-baseline.tar.gz + SHA256SUMS
├── rootfs/                   daily/rescue 活动根（或 symlink -> versions/<id>）
├── versions/                 rootfs 版本（可选）
├── images/
│   └── rootfs.img -> /data/media/0/.dsh/rootfs.img
│                              lab ext4 镜像（实际在未加密区；这里只放 symlink/元数据）
├── dsh/
│   ├── versions/<v>/         DSH 运行时
│   ├── current -> versions/<v>
│   └── previous -> versions/<v>
├── control/                  ro bind -> /opt/dsh-control
│   ├── hooks/{hooks.json,pretool.sh,posttool.sh,selftest.sh,*.deny,*.ask}
│   ├── s6/{dsh/run,dsh/finish,ttyd/run,ttyd/finish}
│   └── bin/{dsh-run,dsh-rm,dsh-fixperm,dsh-apt}
├── state/
│   ├── dsh-home/             rw bind -> /root/.dsh
│   ├── guest/                rw bind -> /run/dsh（url/ready/ttyd.pass）
│   └── ctl/                  host-only：lock、launcher.pid/starttime、profile、stopped、degraded
├── logs/                     rw bind -> /var/log/dsh
│   ├── bootstrap.log
│   ├── dsh.log
│   └── audit/YYYY-MM-DD.jsonl
├── snapshots/                rootfs-<ts>.tar.gz、state-<ts>.tar.gz、manifest
└── profiles/                 daily.env / lab.env / rescue.env（可选）
```

**硬规则**：
- `/data/adb/chroot` 不在模块目录内；卸载模块不删数据。
- control/logs/state 与 rootfs 分离；rootfs 快照不包含控制面/日志。
- daily rootfs 在 `/data` 下 → **禁止**在 daily 里 bind `/data`（自递归）；lab 必须用镜像 + `/run/dsh-rootfs` 挂载。

---

## 6. 挂载表

### 6.1 公共（所有 profile）

| 客机路径 | 源 | 类型/选项 | 说明 |
|---|---|---|---|
| `/proc` | `proc` | `proc,nosuid,noexec,nodev` | 无 PID ns；会看到宿主进程 |
| `/sys` | `sysfs` | daily/rescue `ro`；lab `rw` | lab 需要 mkdir debugfs/tracefs |
| `/dev` | tmpfs 或 `/dev` | 见下 | daily 最小；lab rbind |
| `/dev/pts` | `devpts` | `newinstance,ptmxmode=0666,mode=620` | 独立 pts；`/dev/ptmx` 用 symlink → `pts/ptmx`（不要 bind host ptmx） |
| `/dev/shm` | tmpfs | `size=256m,mode=1777` | Chromium/Playwright |
| `/run` | tmpfs | `size=200m,mode=755` | s6 运行目录 |
| `/tmp` | tmpfs | `size=1g,mode=1777` | `TMPDIR`；避免快照膨胀 |
| `/root/.dsh` | `state/dsh-home` | bind rw | DSH 配置/会话/凭据 |
| `/run/dsh` | `state/guest` | bind rw | DSH 写 url/ready/ttyd.pass；宿主 `dsh-ctl` 读 |
| `/opt/dsh-control` | `control` | bind ro | hooks/s6/bin |
| `/opt/dsh` | `dsh` | bind ro | DSH 运行时；更新由宿主做 |
| `/var/log/dsh` | `logs` | bind rw | 审计/日志 |
| `/etc/resolv.conf` | 生成 | 文件 | 8.8.8.8/1.1.1.1；不 bind 单文件（EBUSY/dangling symlink） |

### 6.2 daily

| 客机路径 | 源 | 类型/选项 | 说明 |
|---|---|---|---|
| `/sdcard` | `/data/media/0` | bind rw | raw，exec；工作区 |
| `/storage/emulated/0` | symlink → `/sdcard` | symlink | 兼容硬编码路径 |
| `/dev` | tmpfs | `mode=755,size=1m` | 最小设备 |
| `/dev/null` 等 | `/dev/<node>` | bind | null/zero/full/random/urandom/tty；`ptmx` 用 symlink → `pts/ptmx` |
| `/system` 等 | — | 不挂 | 需要 Android CLI 时切 lab |

**daily 不挂 `/data`**：rootfs 在 `/data/adb/chroot`，bind `/data` 会自递归；且 `/data` 没有快照，rw 不可逆。
**daily 不挂 `/dev/block`**：最小 `/dev` 防手滑；但 root 仍可通过 `/proc/1/root/dev/block` 访问（规则兜）。

### 6.3 lab

| 客机路径 | 源 | 类型/选项 | 说明 |
|---|---|---|---|
| `/data` | `/data` | **rbind rw** | 含 `/data/user/0` 等子挂载 |
| `/mnt` | `/mnt` | **rbind rw** | 多用户视图；`persist`/`qmcs` 强制 ro |
| `/data_mirror` | `/data_mirror` | **rbind rw** | Android 11+ 多用户镜像 |
| `/storage/emulated/0` | `/storage/emulated/0` | **rbind rw** | Android/data、Android/obb 子挂载 |
| `/sdcard` | `/data/media/0` | bind rw | raw/exec 工作区（与 `/storage` 并存） |
| `/system` `/system_ext` `/product` `/vendor` `/odm` | 同名 | bind/`rbind` **ro** | 子挂载用 rbind |
| `/apex` | `/apex` | **rbind ro** | plain bind 是空壳（E7） |
| `/metadata` | `/metadata` | bind **ro** | 变砖高危 |
| `/linkerconfig` | `/linkerconfig` | bind ro | Android linker |
| `*_dlkm`/`oem`/`cust`/`my_*` | 存在则 | bind ro | 厂商扩展 |
| `/dev` | `/dev` | **rbind** `nosuid`（去 noexec） | binder/ashmem/block 全见 |
| `/sys` | sysfs | rw | lab 需要 |
| `/sys/kernel/debug` | debugfs/host bind | rw | BPF/kprobe |
| `/sys/kernel/tracing` | tracefs/host bind | rw | ftrace/perf |

**lab Android 运行时**：在 `/usr/local/bin` 安装 `am dumpsys cmd settings pm getprop input appops content ime monkey screencap screenrecord svc wm` 包装器，指向 `/system/bin/<name>`；`/etc/profile.d/dsh-android.sh` 设 `ANDROID_DATA=/data`、`ANDROID_ROOT=/system`、`ANDROID_ART_ROOT=/apex/com.android.art`、`ANDROID_I18N_ROOT=/apex/com.android.i18n`、`ANDROID_TZDATA_ROOT=/apex/com.android.tzdata`。不要覆盖 rootfs 已有同名命令。

**lab 高危 ro（挂载后 remount）**：
- `/mnt/vendor/persist`（IMEI/校准）
- `/mnt/vendor/qmcs`（高通认证）
- `/metadata`
- `/system` `/system_ext` `/product` `/vendor` `/odm` `/apex` `/linkerconfig`

**lab 的 `/data` 控制面保护**：`/data` rbind 会把 `/data/adb`（control/state/rootfs/modules）和 `/data/media/0/.dsh`（rootfs.img）暴露给客机；挂载 `/data` 后立即 `mount -t tmpfs tmpfs $ROOT/data/adb` 和 `mount -t tmpfs tmpfs $ROOT/data/media/0/.dsh` 隐藏它们（需要模块时再单独 ro bind `/data/adb/modules`）。这挡不住 `/proc/1/root`，但能防手滑/误删控制面。

**lab 的 `/data` 自递归**：rootfs 是 `/data/media/0/.dsh/rootfs.img`，挂到 **`/run/dsh-rootfs`** 后再 chroot。挂载点既不在 `/data` 也不在 `/mnt` 下，所以 `/data` rbind 和 `/mnt` rbind 都不会把挂载根塞进自身。**禁止**在目录 rootfs 下直接 rbind `/data`。

### 6.4 挂载顺序与传播

1. `busybox unshare --propagation private -m /system/bin/sh -c '...'`
2. `busybox mount --make-rprivate /`（失败则 `get_propagation /` 必须为 private/slave，否则中止）
3. 父先子后：`/proc`、`/sys`、`/dev`、`/dev/pts`、`/dev/shm` → `/run`、`/tmp` → Android 分区 → `/data` → `/sdcard` → 控制面 → chroot
4. 每个 `rbind` 后 `busybox mount --make-rprivate "$dst"`（best-effort，失败要检查 `get_propagation`）
5. 卸载/停止：namespace 内逆序；`umount -l` 只作最后手段并记录证据

---

## 7. 生命周期与 `dsh-ctl`

### 7.1 启动

```
dsh-ctl start [--profile daily|lab] [--yes]
  1. flock state/ctl/lock
  2. profile 校验：lab 需 --yes；persist lab 需 state/ctl/lab.confirmed
  3. doctor --profile <p>（preflight；失败退出，不静默降级）
  4. rootfs：
     daily/rescue：校验目录/版本；必要时从 payload 解压（tar.gz）
     lab：e2fsck -p 镜像 → losetup --sector-size 4096 → 记录 loopdev（先不 mount）
  5. setsid busybox unshare --propagation private -m sh -c 'mounts（lab 在此 mount 镜像到 /run/dsh-rootfs）; cp -a control/s6/. /run/s6/; exec s6-svscan /run/s6'
  6. 记录 launcher PID/PGID/starttime、loopdev、image、profile
  7. 等宿主 `state/guest/url` 或 nsenter s6-svstat；写 running
  8. 失败：逆序清理（namespace 内 umount、loop detach），写 degraded
```

### 7.2 停止

```
dsh-ctl stop
  1. flock；写 state/ctl/stopped + 各服务 down
  2. nsenter -t <launcher> -m sh -c 'sync; sync'
  3. for s in dsh ttyd: nsenter -t <launcher> -m s6-svc -d /run/s6/$s
  4. for s in dsh ttyd: nsenter -t <launcher> -m s6-svwait -d -t 10000 /run/s6/$s
  5. 超时：nsenter ... s6-svc -k
  6. 向 launcher PGID TERM；等 5–10s；KILL
  7. 扫 /proc/*/root 指向 rootfs 的进程，TERM/KILL
  8. nsenter -t <launcher> -m sh -c '逆序 umount；lab 先 remount,ro 镜像'
  9. 宿主侧 losetup -d；扫 /proc/*/mountinfo 验证无 rootfs 路径
  10. 删 lock/pid；保留 logs；写 stopped
```

**注意**：s6 服务目录在私有 ns 的 `/run` tmpfs；宿主 `chroot` 看不到，**必须 nsenter**。`dsh-ctl` 的 `/proc/self/mountinfo` 不能用于校验私有 ns；要扫 `/proc/*/mountinfo`。

### 7.3 命令

| 命令 | 作用 |
|---|---|
| `dsh-ctl doctor [--profile]` | preflight：root、namespaces、chroot、mount、seccomp/SELinux、TMPDIR、payload、loop、node-pty、DSH、空间 |
| `start/stop/restart` | 生命周期 |
| `status` | profile/rootfs/DSH 版本、端口/URL、s6 状态、挂载数、磁盘、degraded |
| `diag` | status + mountinfo + s6 + node-pty + curl + dmesg/logcat（脱敏） |
| `log` | tail/rotate 日志 |
| `snapshot/restore` | 冷快照/回滚（rootfs+state 成对） |
| `backup` | restic `/sdcard/DSH` |
| `lab/daily/rescue` | 切档 |
| `profile` | 查看/设置当前档 |
| `purge` | stop + 校验 + 删除 `/data/adb/chroot`（显式） |
| `fixperm` | `/sdcard/DSH` 2777/0666 |

### 7.4 看门狗与开机

- `service.sh`：**立刻 return**；`setsid` 后台 `dsh-ctl start --boot`；stdin/out/err 重定向。
- `boot-completed.sh`：第二个幂等入口（某些 LKM/late-load 只触发其一）；同样调 `--boot`。
- `--boot`：等 `sys.boot_completed=1` 且 `/data/media/0` 可访问（≤300s）；默认 `daily`；不自动 lab。
- 宿主 `dsh-watchdog`：30s 检查 launcher pid+starttime；s6-svscan 挂了重启；stop 期间看 `stopped`。
- `s6`：`s6-svscan /run/s6`；每个服务 `run`；`finish` 退避；`down` 文件；`s6-log`/size cap。
- `dsh-run`：启动 DSH、抓 URL、写 `/run/dsh/url`、转发信号、写 notification fd（可选）。

---

## 8. DSH 运行时

### 8.1 安装与版本

```
/opt/node/                     Node 24（rootfs 内）
/opt/dsh/versions/<v>/         DSH 运行时（bind rw/ro）
/opt/dsh/current -> versions/<v>
/opt/dsh/previous -> versions/<v>
/usr/local/bin/dsh             wrapper: exec /opt/node/bin/node /opt/dsh/current/lib/bin.js "$@"
```

- 安装：`npm_config_unsafe_perm=true npm i -g @deepseek-ai/dsh@<pin>`，或 `--prefix /opt/dsh/versions/<v>`；**不要**在 root 下用默认 npm（lifecycle 降权会写不进 node-pty 包目录）。
- 版本：`current`/`previous`；升级先 `dsh-ctl snapshot`，装新版本 → smoke（`dsh --version`、`require('node-pty')`、`dsh web --port 0 --no-open`）→ 切 `current`；失败切回。
- Node 24 + node-pty 1.2.0-beta.15 已实测（N-API，prebuild linux-arm64）。

### 8.2 s6 服务

`control/s6/dsh/run`：

```sh
#!/bin/sh
cd /sdcard/DSH/workspace || exit 1
umask 000
export HOME=/root DSH_HOME=/root/.dsh TMPDIR=/tmp \
       PATH=/opt/node/bin:/opt/dsh-control/bin:/usr/local/bin:/usr/bin:/bin \
       LANG=C.UTF-8 TERM=xterm-256color
exec /opt/dsh-control/bin/dsh-run
```

`dsh-run`（Node 或 shell）：
- 启动 `node /opt/dsh/current/lib/bin.js web --host 127.0.0.1 --port 0 --no-open`；
- 读 stdout，匹配 `http://127.0.0.1:<port>/?token=...`，写 `/run/dsh/url`（宿主 `state/guest/url`，0600）；
- 转发 SIGTERM/SIGINT；child exit 时退出；
- 日志写 `/var/log/dsh/dsh.log`（或 s6-log），不要无限增长；
- 可选 `notification-fd` 写 ready。

`control/s6/ttyd/run`（可选）：
```sh
#!/bin/sh
exec ttyd -i 127.0.0.1 -p 7681 -c "dsh:$(cat /run/dsh/ttyd.pass)" /bin/bash -l
```
密码 `/run/dsh/ttyd.pass`（宿主 `state/guest/ttyd.pass`）0600；端口冲突先检查。

### 8.3 DSH 配置

`~/.dsh/profiles/web/cordis.patch.yml`：
```yaml
- id: fs-sandbox
  config:
    cwd: /sdcard/DSH/workspace
# 另：dsh-sandbox-policy.workspaceRoot: /sdcard/DSH/workspace
- name: '@deepseek-ai/dsh-permission-presets'
  config:
    presets:
      root-full:
        sandbox: danger-full-access
        approval: ask
    defaultPreset: root-full
```

- 需要 `dsh-bash-sandbox` + `dsh-sandbox-local` + `dsh-sandbox-policy` 组合，`root-full` 预设才有 `sandboxMode`；如果 profile 用 `dsh-bash-local`，预设服务不会成立（本机无 Landlock/bwrap 时仍走 danger-full-access）。
- `ask` 无 answerer 时 fail closed；Web UI 有 answerer；纯 CLI/headless 会失败，文档写明。
- 不要用 `never`；否则 hook 的 `ask` 变拒绝。
- `dsh web --host 127.0.0.1 --port 0 --no-open`；`--trusted-host` 用于 localhost/反代。
- 迁移只复制配置/凭据/插件清单；**不复制 Termux 的 node_modules**；在 chroot 内重新 `dsh plugin`/pnpm install；本地 `@local` preset 重新 link。

---

## 9. 安全、规则、审计

### 9.1 先承认

- root chroot **没有文件系统隔离**；`/proc/1/root` = 宿主 `/`；`/data` 不挂也能读写；`/dev/block` 不绑也能通过 `/proc/1/root` 访问。
- 所以：**规则层是护栏，不是安全边界；可逆性只覆盖你真正备份的资产**。

### 9.2 结构护栏（降低误操作，不承诺防 root）

| 项 | daily | lab |
|---|---|---|
| `/data` | 不挂 | rw（显式） |
| `/dev/block` | 最小 /dev 不提供 | rbind 暴露；规则 deny |
| `/system` 等 | 不挂 | ro |
| `/mnt/vendor/persist`/`qmcs` | 不挂 | remount ro |
| `/metadata` | 不挂 | ro |
| `/proc/sys`/`/sys/fs/selinux` | 规则 deny/ask | 规则 deny/ask |
| `setenforce 0` | 不默认 | 仅 `--permissive --yes`，恢复 |

### 9.3 hooks 配置

- 包：`@deepseek-ai/dsh-hooks-claude-code`；`configPath=/opt/dsh-control/hooks/hooks.json`。
- `PreToolUse` matcher `*`（实际工具名 `bash`/`read`/`write`/`edit`；先全匹配，观察后收窄）。
- `PostToolUse` matcher `*` 写审计。
- 输出：`hookSpecificOutput.permissionDecision = deny|ask|allow`；`deny > ask > allow`；`exit 2` 也可。
- `timeout: 10` 秒；配置只在 DSH 启动读一次；规则文件每次读。
- **`updatedInput` 不能改写命令**（DSH README）；只能 deny/ask/allow；不要指望自动 `rm`→`dsh-rm`。

### 9.4 服务级 fail-closed 门禁

`dsh-run` 启动 DSH 前：
1. 跑 `selftest.sh`（20 条：坏命令必须 deny、好命令 allow、ask 命令 ask；含引号/换行/`$IFS`/`bash -c` 变体）；
2. `jq` 存在、规则文件可读、hook 可执行；
3. 失败：写 `/run/dsh/degraded`（宿主 `state/guest/degraded`）+ 原因，**不启动 DSH**；同时 `touch /run/s6/dsh/down`，避免 s6 无限重启；`dsh-ctl status` 显示 degraded；
4. 成功才启动。

这不能消除 DSH hook 的 fail-open（脚本崩了仍可能放行），但能防止“规则根本没加载”的裸奔。

### 9.5 规则文件

`control/hooks/daily.deny` / `daily.ask` / `lab.deny` / `lab.ask`；`active` symlink 由 profile 决定。

**deny（两档都拦）**
- 块设备/分区：`mkfs*`、`mke2fs`、`fdisk`、`sfdisk`、`parted`、`wipefs`、`blkdiscard`、`flash_erase`、`fastboot`、`dd` 任意 `of=/dev/`、`cat/tee/cp/mv/truncate` 写 `/dev/block`、`debugfs`、`e2fsck -y`、`tune2fs`、`resize2fs`。
- 系统/启动：`reboot`、`poweroff`、`halt`、`svc power reboot`、`setprop sys.powerctl`、`echo b > /proc/sysrq-trigger`、`insmod`、`rmmod`、`modprobe`、`ksud`、`magisk`、`setenforce 0`、写 `/sys/fs/selinux`、写 `/proc/sysrq-trigger`。
- 逃逸：`/proc/[0-9]+/root`、`nsenter`、`chroot /proc/...`、`mount ... /proc/.../root`、`pivot_root`、`setns`。
- 数据：`pm clear`、`rm -rf /data`、`/data/data`、`/data/user`、`/data/system`、`/data/adb`、`/data/media/0/.dsh` / `/sdcard/.dsh`（含 rootfs.img）、`/sdcard/DCIM`、`/sdcard/Android`、`/sdcard/Download`、`/data/media/0` 根。
- 包/远程代码：`apt purge`、`apt autoremove`、`dpkg --purge`、`npm publish`、`curl ... | sh`、`wget ... | bash`、`base64 -d | sh`、`eval`。
- 权限：`chmod -R 777 /`、`chown -R` 于 `/`、`/data`、`/system`。

**lab 额外 deny**：写 `/mnt/vendor/persist`、`/mnt/vendor/qmcs`、`/metadata`、`/system`、`/vendor`、`/apex`、`/dev/block`（即使可写也拦）。
**lab 允许**：Android 调试命令（`am/pm/cmd/settings/input/dumpsys`）、`strace`、`perf`、`ftrace`、binder 工具；仍记审计。
**ask**：`rm -rf`、`git clean -fdx`、`git reset --hard`、`apt upgrade`、`npm i -g`、`pip install`、`pm uninstall`、`settings put`、`am force-stop`、`mount`/`umount` 非 `/tmp`、`kill`/`pkill`、`chmod/chown -R`、`sysctl -w`、写 `/data/adb`、`dsh-ctl restore`。

### 9.6 审计

- `PostToolUse` + `PreToolUse`（deny/ask 也记）→ `/var/log/dsh/audit/YYYY-MM-DD.jsonl`。
- 字段：ts、profile、session_id、tool_name、cwd、command/path、decision、reason、exit_code、duration、output_head（≤4KB）。
- 脱敏：`token|api_key|authorization|password`；截断大输出；按天/大小轮转。
- 审计不是 root AI 的防篡改边界；可 `chattr +a`（f2fs 需实测）或远端 syslog；至少不要声称不可抵赖。

---

## 10. 可逆性与备份

| 资产 | daily | lab | rescue |
|---|---|---|---|
| rootfs | 冷 `tar.gz`（1 golden+3 recent+1 pre-change） | 冷 `tar.gz`（挂载内容） | 无 |
| state | 冷 `tar.gz`（7） | 同 | 无 |
| `/sdcard/DSH` | restic 每日 + `.trash` | 同 | 无 |
| `/sdcard` 其余 | 不自动（规则 deny 关键路径） | 不自动 | 无 |
| `/data` | 不挂 | rw；**不承诺可逆**；外部全量备份另议 | 不挂 |
| `/system`/分区 | 不挂 | ro | 不挂 |
| `/dev/block` | 不提供 | 规则/ro 不挡写 | 不提供 |

**快照规则**：
- rootfs/state 成对；manifest 记录 sha256、DSH 版本、profile、时间。
- `tar -czf snap.tmp` → `sync` → `mv`；`tar -tzf` 验证；失败不留半成品。
- 空间守卫：`/data` 剩余 <5GiB 拒绝；prune 先删最旧 recent，保留 golden。
- 回滚：stop → 校验 → 当前 rootfs 移到 `rootfs.bad.<ts>` → 解压/交换 → start → smoke；失败回滚。
- lab 镜像：停止时 `e2fsck -p`；快照是“挂载内容 tar”，不是镜像文件：stop 后 `mount -o ro` 到临时 `/run/dsh-snapshot`，`tar -czf`，再 umount；恢复时重灌镜像。
- `/sdcard/DSH`：`dsh-rm` 移到 `.trash/<date>/`；restic 到 PC/NAS；恢复演练。

---

## 11. 打包、安装、升级

### 11.1 模块 zip（KB 级，不含 payload）

```
module.prop
customize.sh
service.sh
boot-completed.sh
uninstall.sh
action.sh
bin/dsh-ctl
bin/boot-bootstrap
control/hooks/*
control/s6/*
control/bin/*
```

- 包根**禁止** `install.sh`（legacy 分支只解压 3 个文件）。
- `customize.sh`：`source` 执行，不 `exit`；校验 aarch64/API/空间；payload 不存在则 `abort` 并给 push 指令；建目录；复制 `control/` → `/data/adb/chroot/control`；`chmod 0755`。
- `service.sh`/`boot-completed.sh`：立刻 return；`setsid` 后台 `dsh-ctl start --boot`；fd 重定向；不跑长循环。
- `uninstall.sh`：`dsh-ctl stop`；只删模块目录；保留 `/data/adb/chroot`；`dsh-ctl purge` 显式全删。
- `action.sh`：KSU 管理器按钮 → `dsh-ctl status`/`diag`。
- `module.prop`：`id` 发布后不改；`versionCode` 单调整数；`version` 不参与比较；可选 `updateJson`。

### 11.2 payload

- `rootfs.tar.gz`：Ubuntu base 24.04 arm64 + Node 24 + s6/ttyd + 基础工具（git/curl/ca-certificates/xz/jq/build-essential 可选）。
- `dsh-baseline.tar.gz`：DSH 版本 + 依赖（或放 rootfs，二选一）。
- `SHA256SUMS`；启动校验。
- 压缩：**gzip**（KSU busybox tar 支持）；不要 `.tar.zst`。
- 体积：Ubuntu/Node/DSH ~0.8–1.2GB 解压；payload ~300–500MB gzip。
- lab 镜像：首次 `mke2fs -t ext4 -b 4096 -d rootfs.img`；`losetup --sector-size 4096 --direct-io=off`；`e2fsck`；`resize2fs`。**注意**：toybox/busybox 的 `losetup` 不支持 `--sector-size`；必须在模块 `bin/` 携带静态 util-linux `losetup`（或实测 `/system/bin/losetup` 支持），否则 lab 档起不来。`mke2fs/e2fsck/resize2fs` 同理要确认来源。

### 11.3 构建门禁

1. 包根无 `install.sh`；
2. 所有脚本 0755、`sh -n`、`shellcheck`、mksh 跑一遍（`${s%%|*}` 方言）；
3. `versionCode` > 上一版；
4. payload `busybox tar -xzf` 实际解一遍；
5. chroot 内 `node -e require('node-pty')`、`dsh --version`、`dsh web --port 0 --no-open`；
6. doctor 全绿；daily P0 全过；lab P0 全过；
7. 不含密钥/凭据；日志/审计脱敏。

---

## 12. 验收（P0）

### 12.1 daily（默认，必须全过）

1. `id`=0、`uname -r` 宿主一致；`/sdcard` raw 可执行；Go 在 `/sdcard/DSH/workspace` 编译并运行。
2. `node-pty` 起 PTY；`dsh web --port 0 --no-open` 打印 token URL，curl 200/303；URL 写入 `state/guest/url`。
3. `/sdcard/DSH` 2777/0666 + umask 000；Termux/文件管理器可写。
4. stop 后：全机 `/proc/*/mountinfo` 无 rootfs、`/proc/*/root` 无 rootfs 进程、无 launcher；不是只看 `/proc/self`。
5. `.tar.gz` 快照 → 破坏 → restore 一轮；rootfs/state 成对；sha 校验。
6. 钩子自检：坏命令 deny、好命令 allow、ask 命令 ask；移走 `pretool.sh` → DSH 不启动（degraded）。
7. `apt-get update` + `install s6 ttyd` 成功（env -i/TMPDIR）；`ca-certificates` 配置成功。
8. 开机自启：service.sh 非阻塞；boot_completed 后 10–60s 就绪；重启/强杀自愈。

### 12.2 lab（显式，必须全过）

1. 镜像 `e2fsck`/`losetup --sector-size 4096`/mount 成功；rootfs 可 chroot。
2. `/data` rbind 后 `/data/user/0` 可见；`/storage/emulated/0/Android/data` 可见；`/sdcard` raw 可执行。
3. `/apex` rbind 后 `com.android.runtime/bin` 非空；`/system/bin/am --help`、`pm list packages` 可跑（通过包装器）。
4. `/mnt/vendor/persist`、`/mnt/vendor/qmcs`、`/metadata` 写失败（ro）；`/system` 等写失败。
5. `/dev` rbind 后 binderfs/ashmem 存在；`debugfs`/`tracefs` 可读。
6. stop 后 image 已 `remount,ro`、umount、losetup -d；宿主无残留。
7. `--permissive` 仅在显式 `--yes` 下生效，退出恢复原 SELinux 状态并记审计。
8. lab 不自动开机；重启后回 daily。

### 12.3 rescue / 模块 / 恢复

- rescue：stop 后最小挂载，`chroot /bin/bash` 可进；不启动 DSH；exit 清理。
- 模块：安装 → 重启 → 自启 → stop/start → 卸载（保留数据）→ purge（删数据）。
- 更新：DSH 升降级 + rollback；rootfs 快照回滚；断电/强杀后 doctor 修复。

---

## 13. 实施路线

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0（1–2 天） | doctor/preflight；daily 目录 rootfs；最小 `/dev`；raw `/sdcard`；node-pty；DSH web；workspace 权限；stop 扫描 | daily P0 1–4、6 |
| P1（2–3 天） | `dsh-ctl` 全命令；s6 + `dsh-run`；watchdog；快照/restore；审计 | daily P0 5–8 |
| P2（1 周） | lab 镜像；`/data`/`/mnt`/`/storage`/`/dev` rbind；Android 分区 ro；debugfs/tracefs；包装器；`--permissive` | lab P0 1–8 |
| P3（1 周） | 模块打包；service.sh/boot-completed；uninstall/purge；action；版本/更新 | 模块/恢复 |
| P4（2–3 天） | 迁移 DSH 配置/插件；并行跑一周；restic；文档 | 迁移验收 |
| P5（可选） | mini-panel sidecar（lab）；thin native client（参考 APK UX） | 不影响主线 |

**原则**：P0 不过不打包；每个阶段前 `dsh-ctl snapshot`；最坏 `/data/adb/chroot` 删除重建（先备份）。

---

## 14. 参考取舍

| 参考 | 采用 | 不采用 |
|---|---|---|
| `chroot-mcp-safe` | 挂载表、`make-rprivate`、preflight、nsenter stop、pid/root 扫描、TMPDIR/resolv、Android 包装器、镜像模式 | 默认 full-access、默认 sshd/123456、默认 `setenforce 0`、4183 行单文件、`umount -l` 常规化、无快照 |
| `mini-panel` | 计划确认/PLANNING→CODING→REVIEWING、工具分类、lazy-ref、面板信息架构 | AGPL 代码、Docker/DockRoot 运行时、子串黑名单、Agent 同进程 root、双 Agent |
| `容器AI APK` | 原生 Compose UX、模型管理（本地 GGUF/云端）、Chat+工具循环、资源/容器状态卡片 | proot/Alpine、app 沙箱、无开机自启、无快照、`debuggable`/`allowBackup`、专有代码 |
| `dsh-alpine-ksu` | 非阻塞启动、原子 rootfs、raw media、版本/回滚骨架 | Alpine/musl 补丁链 |
| `chroot-distro` | rootfs 安装/挂载/备份策略、fresh proc/devpts、能力探测 | 作为运行时依赖、GPL 代码复制 |

**许可证**：
- mini-panel AGPL-3.0：不并入 MIT 模块；如作为 sidecar 分发，需履行 AGPL。
- chroot-mcp-safe 未声明 license：只借鉴思路，不复制代码。
- 容器AI APK 未声明 license：只分析，不反编译复用。

---

## 15. 仍需你拍板

1. **lab 镜像大小**：8GB 还是 20GB？（默认 8GB，够 Ubuntu+Node+DSH；大项目再 resize）
2. **lab `/data` rw**：确认接受“`/data` 不可逆”；否则 lab 只能 `/data` ro，设备控制受限。
3. **`/sdcard/DSH` 备份目标**：PC/NAS 的 restic 仓库路径；是否纳入 DCIM/Documents。
4. **DSH 是否永远 root**：v1.3 先 root；若以后要真实隔离，需 UID1 + root helper 的 v2。
5. **mini-panel / native client 是否要做**：v1.3 只留可选；不阻塞主线。
6. **hooks 自检失败是否真的拒绝启动 DSH**：推荐是；如果你更看重可用性，可改成“降级到只读/仅聊天”。

---

## 16. 一句话总结

v1.3 把“全能”拆成**默认 daily + 显式 lab + rescue**：daily 保证日常可用/可快照，lab 用镜像 + full Android 视图满足 Agent 调试，rescue 保证坏了能修；控制面、生命周期、规则/审计、备份全部按 08 的 P0 重写。**它不再承诺 chroot 是沙箱，也不再把挂载限制当安全边界；安全来自“默认档收窄 + 高危 ro + 规则 + 外部备份 + 冷快照”。**
