# 项目总览 v2（2026-10-26）

> 这份是**单一入口**：把需求、架构、隐藏性定义、已验证/未验证、红队 P0、路线和已拍板项收在一起。历史文档保留，但以本文为准。

---

## 0. 一句话

在 OnePlus PJZ110 / Android 15 / SukiSU 上，做一套**真 chroot 的 Ubuntu + DSH + 完整手机控制**；chroot 内可以全开，**应用层（游戏/银行/Play Integrity）看不到本模块的 root 痕迹**；不是安全沙箱，不考虑权限收束和空间优化。

---

## 1. 目标与非目标

### 目标
- **G1 真 chroot**：非 proot、非 VM；内核原生 syscall；Go 编译产物直接执行。
- **G2 完整 Linux**：Ubuntu 24.04 glibc、Node 24、DSH、apt、Go、s6；rootfs 可快照/回滚。
- **G3 开机自启**：service.sh/boot-completed 非阻塞；s6 监督；可启停/诊断。
- **G4 完整手机控制**：`/data`、`/mnt`、`/storage`、`/sdcard`、`/dev`、`/sys`、Android 分区全见；`am/pm/input/screencap/...`；UI/通知/剪贴板可由宿主 agent 补。
- **G5 应用层隐藏性**：游戏/银行/Play Integrity/普通 root 检测 App 不因为本模块发现 root；不是防恶意 root。
- **G6 可逆性**：rootfs/state 快照、`/sdcard/DSH` restic、boot counter、last-good、purge。

### 非目标
- 不做安全沙箱/多租户；不防 root 攻击者/物理接触。
- 不做 proot、AVF/VM（至少现在不做）、DockRoot/Docker。
- 不追求多机型/发布；不为了隐藏砍掉 full chroot。
- 不把 `.sdcard` 里一个自定义名字当成封号理由；不为“名字不正常”过度设计。

---

## 2. 术语（先消除歧义）

- **本机 / 宿主**：这台 Android 手机，只有一台。
- **chroot 内**：同一台手机上的进程树，根目录切到 Ubuntu rootfs；共用同一内核/网络/硬件；**不是虚拟机、不是 proot、不是第二台设备**。
- **不再使用“客机”**：旧文档里的“客机”已统一改为“chroot 内”。
- **approval-free**：DSH 的 approval 设为 `never`，不弹批准；DSH 进程本身仍是 **uid 0**、shell 命令是 root；**不是**“手机没有 root”。
- **隐藏性**：本文默认指 **应用层隐藏性**（游戏/银行不抓把柄），不是安全边界。
- **full**：chroot 内 `/data`/`/dev`/`/sys` 全开；不是全局 mount/prop 全开。
- **完美**：按检测矩阵/验收表通过，不是“任何未知检测都绝对发现不了”。

---

## 3. 运行模型

```
Android 本机（唯一设备）
├─ KSU module（KB 级）
│  ├─ service.sh / boot-completed.sh
│  ├─ dsh-ctl（生命周期/诊断/快照/恢复）
│  └─ control/（hooks、s6、bin）
├─ 私有 mount namespace + chroot
│  ├─ 128GB ext4 镜像 /data/media/0/DSH-CHROOT/rootfs.img
│  ├─ /tmp/dsh-rootfs（宿主 tmpfs 挂载点）
│  ├─ full /data /mnt /data_mirror /storage /sdcard /dev /sys
│  ├─ Android 分区（/system、/apex、/linkerconfig...）
│  └─ Ubuntu + Node 24 + DSH + s6
└─ 可选：宿主 thin agent（UI/通知/剪贴板/包管理）
```

- **默认 full**：单一 full 档；不再 daily/lab 分档；`rescue` 只维修。
- **rootfs**：ext4 **128GB 存储镜像**；128 起步、可 `resize2fs` 到 256GB；目录名不是隐藏硬指标。**128/256 是存储空间，不是内存**；实际 rootfs 12–16GB，其余是稀疏容量；loop/ext4 只用页缓存，不把整块镜像加载进 RAM。
- **私有 ns**：所有 chroot mount 只在 private mount namespace；App 的 `/proc/self/mountinfo` 看不到。
- **可选宿主 agent**：`uiautomator dump` 本机不可用；要 UI 树/通知/剪贴板/包管理，用宿主 accessibility/Shizuku/ADB agent；不是隐藏必需，是“完美手机控制”补全。

### 3.1 存储布局：镜像 ≠ DSH 工作目录

```
/data/adb/chroot/                  # 模块私有数据（标准-ish root 模块目录）
├── control/                       # hooks/s6/bin（bind 到 chroot /opt/dsh-control）
├── state/dsh-home/                # bind 到 chroot /root/.dsh（DSH 的 HOME）
├── logs/                          # bind 到 chroot /var/log/dsh
└── dsh/                           # DSH 运行时 versions/current/previous

/data/media/0/DSH-CHROOT/          # 唯一放镜像的地方（/data/adb 下 loop 会 FBE EIO）
└── rootfs.img                     # Ubuntu 24.04 的 ext4 磁盘镜像（128GB 稀疏）
    snapshots/                     # 镜像冷快照

/data/media/0/DSH/workspace/       # DSH 工作区（bind 到 chroot /sdcard/DSH/workspace）
```

- `rootfs.img` 是 **Ubuntu 的“虚拟磁盘”**，里面是 `/bin`、`/usr`、`/etc`、Node、apt 等；**不是 DSH 的 home，也不是工作区**。
- `DSH 的 home` 是 chroot 内的 `/root/.dsh`，宿主对应 `/data/adb/chroot/state/dsh-home`；里面是 profiles/sessions/credentials。
- `DSH 的工作区` 是 `/data/media/0/DSH/workspace`，chroot 内是 `/sdcard/DSH/workspace`。
- `/data/media/0/DSH-CHROOT` 不是 Android 官方标准目录；它是我们为“loop 只能放未加密 media”做的例外。`/data/adb/chroot` 才是模块私有数据的主目录。
- 顺序是：**先有 Ubuntu rootfs（镜像）→ 在镜像里装 Node/DSH → 启动时挂镜像、chroot、起 s6/DSH**；镜像不是 DSH 运行时本身。

---

## 4. 应用层隐藏性（真正的“不降隐藏性”）

### App 能看到
- 全局 prop：`ro.debuggable`/`ro.secure`/`init.svc.*`/`persist.*`。
- 全局 SELinux 状态（untrusted_app 对 `/sys/fs/selinux/enforce` 通常 neverallow；特权/root 检测可看）。
- **自己的** `/proc/self/mountinfo`。
- `/sdcard` 目录名/文件（有存储权限）。
- PackageManager 里的管理器/可疑包。
- 进程名（受 `hidepid` 影响）和 loopback 端口。
- KSU 管理器/模块痕迹（模块目录本身 App 不可读）。

### App 看不到
- chroot 的 private mount namespace。
- `/data/adb/chroot`、`/data/adb/modules`、`/data/adb/ksu`（0700 root）。
- `/proc/1/root`、chroot 内 `/dev/block`、`/sys/fs/selinux`、`u:r:ksu` 域。
- rootfs 镜像内部内容（除非在 `/sdcard` 暴露了文件名）。

### 必须守住的硬不变量
1. **全局 SELinux Enforcing**；不默认 `setenforce 0`；`--permissive` 仅显式 break-glass。
2. **不改全局 prop**。
3. **所有 chroot mount 在 private ns**；不全局 mount/overlay。
4. **保持 KSU 隐藏特性**：`selinux_hide`/`mount_hide`/`avc_spoof`/`kernel_umount`。
5. **DSH/ttyd 只监听 127.0.0.1**；宿主 `/proc` 保持 `hidepid=invisible`。
6. **`/sdcard` 不放 `magisk`/`kernelsu`/`su` 这类明显 root 标记**；`DSH-CHROOT`/`DSH` 名字可以接受，不为名字过度设计。

### 不是隐藏问题
- `u:r:ksu:s0` 是 KernelSU 的 permissive allow-all 域：**App 看不到别的进程域**；这是安全边界问题，不是应用检测问题。不需要为它写自定义 SELinux 域。
- `/proc/1/root`、chroot 内 `/dev/block`、`/sys` rw：App 看不到；不用为隐藏砍掉。
- `DSH-CHROOT`/rootfs.img/loop：低强度信号；用户明确不在意为名字封号；不强制 directory rootfs。
- `getenforce` 结果不稳定；只以 `/sys/fs/selinux/enforce` + 域状态为准；App 通常读不到。
- `selinux_hide` 不 fake enforce；不要靠 permissive 做隐藏。

---

## 5. 挂载与目录（full）

- 宿主：`/data/adb/chroot/{payload,rootfs,control,state,logs,snapshots,dsh,images}`；镜像实际 `/data/media/0/DSH-CHROOT/rootfs.img`。
- chroot 内：`/proc`、`/sys`、`/dev`、`/dev/pts`、`/dev/shm`、`/run`、`/tmp`、`/data`、`/mnt`、`/data_mirror`、`/storage`、`/sdcard`、`/system` 等、`/root/.dsh`、`/opt/dsh-control`、`/opt/dsh`、`/var/log/dsh`。
- 关键事实：
  - `/run` 本机不存在；镜像挂 **`/tmp/dsh-rootfs`**（宿主 `/tmp` 是 tmpfs），start 前 `mkdir -p`。
  - `/tmp/dsh-rootfs` 不在 `/data`/`/mnt` 下，避免 `/data` rbind 自递归。
  - `/data` rbind 有多个别名（`/data/user/0`、`/data_mirror/...`、`/mnt/*/emulated`、`/storage/emulated`）；只遮 `DSH-CHROOT` 不足以防镜像被写，但**这不是 App 隐藏问题，是稳定性问题**。
  - `/storage` 要 rbind 整个 `/storage`，不能只挂 `/storage/emulated/0`。
  - `/apex`/`/linkerconfig` 的 `remount,ro,bind` 不递归；整树 ro 需 `mount_setattr(AT_RECURSIVE)`，否则如实写“顶层只读、子挂载可能 rw”。
  - 绝不对 `/sys/fs/selinux` 做 bind/remount,ro；事故复盘见 `13`。

---

## 6. 生命周期

- **holder**：`dsh-nsinit` 长驻，mount + chroot + 启动 s6；s6-svscan 是子进程；holder 活着才能安全 unmount。
- **start**：mkdir lock → doctor → e2fsck → losetup → `unshare --mount --propagation private --uts` → dsh-nsinit → 记录 PID/starttime/ns inode → 等 URL。
- **stop**：写 stopped/down → 校验 PID/starttime/ns inode → `nsenter -t PID -m chroot $ROOT s6-svc -d` → 等 s6 → TERM/KILL PGID → 扫描 `/proc/*/ns/mnt` inode → holder 活着时逆序 umount → KILL holder → losetup -d → 恢复 SELinux → 删 lock。
- **watchdog**：30s 检查 holder；异常恢复 SELinux、清理/重启；boot 后断言 enforce=1。
- **boot**：service.sh + boot-completed 非阻塞；等 `sys.boot_completed=1` **和** `sys.user.0.ce_available=1`；默认 full；last-good 回滚。
- **工具链**：模块自带 util-linux `losetup`（支持 `--sector-size`）、e2fsprogs、GNU cp/tar；toybox 的 cp/losetup 不满足。
- **锁**：`mkdir` 原子锁；不要用 toybox `flock` 的文件形式（mksh fd>2 CLOEXEC）。

---

## 7. DSH 与手机控制

- Node 24 + DSH `versions/<v>` + `current`/`previous`；`/opt/node`；`/opt/dsh`。
- s6：`s6-svscan /run/s6`；`dsh`/`ttyd`；`dsh-run` 抓 URL 写 `/run/dsh/url`（宿主 `state/guest/url`）。
- DSH 配置：`fs-sandbox.cwd=/sdcard/DSH/workspace`；`workspaceRoot` 同；默认 `approval-free`（danger-full-access + never）；可选 `approval-guard`；审计保留。
- 手机控制矩阵：
  - 命令存在/只读验证：`am`（无参数 help）、`pm list`、`settings get`、`input`、`dumpsys`、`content`、`appops`、`ime`、`wm`、`svc`、`cmd notification`、`screencap`、`screenrecord`。
  - **`uiautomator dump` 本机不可用（rc=137）**；只有 `runtest`（需 jar）；UI 树/通知/剪贴板用宿主 agent 更完整。
  - `am start`/`pm install`/`pm clear`/`settings put` 等写操作**未验证**，必须做 capability matrix + 负例测试；不能写“perfect”。
- 宿主 agent（可选）：Shizuku/ADB/accessibility；通过 Unix socket 调；固定命令集；不把任意 shell 暴露给 App。

---

## 8. 快照、备份、恢复

- 128GB 镜像冷快照 7 份 + state 7 份；stop+unmount+e2fsck 后 `cp --sparse`/GNU cp；sha256 + 原子 rename；last-good。
- `/sdcard/DSH`：`dsh-rm` 回收站 + restic 到 PC/NAS；`/data`/`/sdcard` 其余不承诺。
- `purge` 删除 `/data/adb/chroot` **和** `/data/media/0/DSH-CHROOT`（镜像/快照），确认无 loop。
- 空间不是隐藏问题；128 起步、256 可选；ext4 只能增不能减。
- 事故教训：`selinuxfs` remount 机制未证实；所有宿主级 mount/overlay/loop 测试禁止；只在备用机/VM/private ns + trap 做。

---

## 9. 已验证 / 未验证

**已验证（本机）**
- chroot 可进；Node 24 + node-pty PTY；`dsh web --port 0` URL；s6-svscan/s6-svc/s6-svwait；apt 装 s6/ttyd；raw `/data/media/0` 执行；`/sys`/`debugfs`/`tracefs` 部分；Android CLI 多数 help/只读；`screencap`/`screenrecord`；`/dev/ashmem`/binder；`/data` 别名存在；`/storage` 只有 emulated/0；`/tmp` tmpfs；`/run` 不存在。

**未验证（不能当事实）**
- 真实 chroot 的 full `/data` rbind、`/mnt`/`/storage`、`/system` overlay 实际挂载点、128GB 镜像全流程、`am start`/`pm install`/`pm clear` 写操作、`uiautomator runtest`、frida/stackplz、`/proc/kallsyms`、DSH `approval-free` + `dsh-bash-sandbox` 组合、App 级银行/游戏检测。

**事故**
- 2026-10-26 主机级测试后系统崩溃；具体单点机制未证实；`selinuxfs` bind/remount 等所有宿主级测试永久禁止；详见 `13`。

---

## 10. v1.5 必须修的 P0（不是实现）

1. `/tmp/dsh-rootfs` 创建 + `/run` 不存在；start 两段式 host mount → chroot。
2. `/data` 镜像/控制面自引用；是否遮内部路径按你“全开”决定；不遮则 AI 可能毁 rootfs（稳定性，不是 App 隐藏）。
3. stop/ns：`nsenter -m` 不换根；用 `chroot $ROOT`；PID starttime/ns inode；holder 活着再 unmount；losetup -d 真正生效。
4. `/storage` 完整 rbind；`/apex` 递归 ro 或如实写顶层 ro；`/tmp` 创建；`getenforce` 用 `enforce` + 域状态。
5. watchdog/last-good/boot counter；`purge` 删除镜像/快照。
6. 工具链自带 util-linux/e2fsprogs/GNU cp/tar；lock 用 mkdir。
7. 手机控制 capability matrix：`uiautomator dump` 不可用；`am/pm` 写操作未验证；宿主 agent 补 UI。
8. 文档不再把 `u:r:ksu` permissive 说成“SELinux 保证”；App 隐藏靠 private ns/全局 prop/SELinux 不变。

---

## 11. 路线

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | v1.5 设计修正；决策拍板 | 本文/15 的选项确认 |
| P1 | 静态 module 骨架 + `dsh-ctl doctor/plan/status`（不 apply） | shellcheck/sh -n/mksh；不碰系统 |
| P2 | 备用机/VM：mount/ns/stop/loop 测试 | 安全协议；零残留 |
| P3 | DSH/node/s6/URL/工作区 | P0 命中 |
| P4 | full Android 视图 + 宿主 agent 手机控制 | 能力矩阵/负例 |
| P5 | 模块打包、开机、回滚、迁移 | 安装/卸载/重启自愈 |

**当前没有开始写实现代码，也没有再改系统。**

---

## 12. 已拍板（2026-10-26，全按推荐）

**已拍板组合：full 能力 + 应用层隐藏**：

`1A 2B 3A 4B 5A 6B 7A 8A 9A 10A 11A 12A 13A 14B 15A`

- 1A：full `/data`；内部遮罩只作稳定性防手滑（可改 1C 不遮，接受 AI 可毁 rootfs）。
- 2B：`/sys` rw；App 看不到；求稳可 2A。
- 3A：`/dev` 全 rbind；App 看不到；全设备控制。
- 4B：宿主 agent 补 UI/通知/剪贴板；不是隐藏必需，是“完美手机控制”。
- 5A：full chroot；宿主 agent 可选。
- 6B：`protect-critical` ro；防砖，不影响 App 隐藏。
- 7A：`system-rw` off。
- 8A：128GB 镜像；`DSH-CHROOT` 名字不硬隐藏；256 可选。
- 13A：接受 `u:r:ksu` permissive；App 看不到。
- 14B/15A：`/apex` 递归 ro、`/storage` 完整 rbind。

**更谨慎/少痕迹（可选）**：

`1B 2A 3C 4B 5B 6B 7A 8C 9A 10A 11A 12A 13A 14D 15D`

---

## 13. 当前文档

- `11-design-v1.4.md`：full 设计（未批准；P0 待修）。
- `14-red-team-v1.4.md`：20 个红队 agent 的反对意见。
- `15-decisions-pending.md`：逐项选项/代价/推荐。
- `16-hiddenness-first.md`：应用层隐藏性模型（full 可保留）。
- `13-incident-2026-10-26-selinux-remount.md`：事故复盘与禁止事项。
- `12-v1.4-verification.md`：本机验证/未验证清单。
- 本文 `17-project-overview.md`：单一入口。
