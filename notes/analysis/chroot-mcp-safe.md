# 分析：PhoenixHairpin/chroot-mcp-safe

- 来源：`sources/PhoenixHairpin_chroot-mcp-safe`（v2.2.1，2026-07-11；4183 行 shell）
- 许可：**未声明**（README 写“继承原仓库 license，未指定时默认私有”）→ 只借鉴思路，**不复制代码**
- 定位：Android root 下的 chroot 容器管理脚本；卖点是“完整 Android 视图 + Agent 调试就绪”
- 一句话评价：**它是“full-access/lab 档”最完整的开源参考实现；挂载表、预检、nsenter 停止、高危区只读都值得直接抄策略，但默认 full-access + 可选 `setenforce 0` + sshd 不适合作为我们的默认档。**

---

## 1. 问题与约束

- 目标设备：Android（作者在 Termux / KernelSU 系 root 下用），要求容器内能看到**完整 Android 视图**：`/data/user/0/<pkg>`、`/sdcard/Android/data`、binderfs、debugfs/tracefs、`am/pm/input` 等。
- 约束：chroot 不是沙箱；`/data` 下的子挂载（多用户、FUSE、Android/data）单层 bind 看不到；宿主 `/mnt/vendor/persist`、`/mnt/vendor/qmcs`、`/metadata` 写入可能变砖。
- 明确取舍（README）：
  - 默认 `--full-access`（`/data`、`/storage` 可写）；`--safe`/`--ro-data` 可只读；
  - `--permissive` 可临时 `setenforce 0`，退出恢复；
  - 默认后台 sshd（端口 8023），也可前台 shell；
  - 支持目录 rootfs 与 ext4 镜像；镜像用于 `/data` 真 1:1 映射、扩容、整存整删。

## 2. 运行模型

- **命名空间**：`unshare --mount --propagation private`；进入后 `mount --make-rprivate /`；每个 `rbind` 后再 `mount --make-rprivate $dst`（`do_mount`，约 3720–3730 行）。
- **挂载表**（README + 脚本 3894–4045 行）：
  - `proc`（nosuid,noexec,nodev）
  - `sysfs` **rw**（为了在容器内 `mkdir` debugfs/tracefs 挂载点；节点写权限仍受内核保护）
  - `/dev` **rbind**（带 binderfs/cgroup/usb-ffs/ashmem 子挂载），`nosuid`，去掉 `noexec` 让 frida/stackplz 可用
  - `devpts` **newinstance,ptmxmode=0666**（独立 pts 实例）
  - `/tmp`、`/run`、`/dev/shm` tmpfs
  - `/data` **rbind rw**（含 `/data/user/0` 等子挂载）
  - `/system`、`/vendor`、`/product`、`/odm`、`/system_ext` bind **ro**；`/apex` **rbind ro**；`/metadata` bind **ro**；`/linkerconfig` ro
  - `/storage/emulated/0` **rbind rw** → `/storage/emulated/0` 与 `/sdcard`
  - `/data_mirror` **rbind rw**；`/mnt` **rbind rw**；`/mnt/vendor/persist`、`/mnt/vendor/qmcs` **remount ro**（变砖防护）
  - `vendor_dlkm`/`system_dlkm`/`odm_dlkm`/`oem`/`cust`/`my_*` 存在则 bind ro
  - `debugfs`/`tracefs` rw；`/sys/kernel/debug`、`/sys/kernel/tracing` 可见
- **身份**：宿主 root（`u:r:ksu:s0` 或类似）；chroot 内 root；没有降权；chroot 不是沙箱。
- **Android 运行时**：`prepare_android_runtime_env` 写 `/etc/profile.d/chroot-mcp-android.sh`，设置 `ANDROID_DATA/ROOT/ART_ROOT/I18N_ROOT/TZDATA_ROOT`，并为 `am dumpsys cmd settings pm getprop input appops content ime monkey screencap screenrecord svc wm` 安装 `/usr/local/bin` 包装器，保证 PATH 被 Agent 重写后仍可用。

## 3. 生命周期

- **启动**：挂载 → `preflight_chroot` → 自检 `chroot ... echo pre_sshd_ok` → 启动 sshd（或前台 shell）。后台模式写 `state` 文件（TARGET/PORT/SSHD_PID/LOG/IMAGE/LOOPDEV）。
- **停止**（`daemon_stop`，约 951–1120 行）：
  1. 读 state；`pid_root_matches_target` 校验 `/proc/<pid>/root` 是否指向 rootfs；
  2. `nsenter -t <sshd_pid> -m` 里 `sync`；
  3. 再 `nsenter` 进 mount namespace：`kill` PID → `collect_target_pids` 扫 `/proc/*/root` → TERM/KILL；
  4. 逆序 umount（`/etc/resolv.conf`、debugfs/tracefs、`/mnt`、`/data_mirror`、`/storage`、`/sdcard`、`/metadata`、`/apex`、`/system*`、`/data`、`/dev/*`、`/sys`、`/proc`）；
  5. `remount,ro` rootfs → `umount` → `losetup -d`（镜像）；
  6. 处理“state 丢失但宿主有残留挂载”的 fallback。
- **卸载**：`daemon_stop` → 删除 rootfs/镜像/state；状态/端口配置可保留。
- **预检**（`preflight_chroot`，约 2925–2970 行）：检查 rootfs 属主 0:0、`/bin/sh`/`/bin/bash`、`chroot / /system/bin/sh -c 'exit 0'`；失败时打印 `Seccomp`、`NoNewPrivs`、SELinux context；Enforcing 下建议 `--permissive`。

## 4. 数据与更新

- rootfs：目录 `/data/local/chroot/<distro>` 或 ext4 镜像 `/data/local/chroot-images/<distro>.img`（默认 20GB，挂 `/mnt/chroot-rootfs/<distro>`）。
- 状态：`/data/local/tmp/chroot-mcp/chroot-mcp-daemon-<distro>.info`；日志 `/data/local/tmp/chroot-mcp/*.log`；端口 `/data/local/chroot-config/<distro>.port`。
- 镜像：`mke2fs`/`resize2fs`/`e2fsck`；`losetup` 记录；停止时 `remount,ro` + `umount` + `losetup -d`。
- 更新：脚本是单文件；没有版本化 rootfs/DSH；升级靠替换脚本/重装；没有快照/回滚，只有镜像文件可复制。

## 5. 为什么这样写（证据）

1. **`/data` 必须 rbind 而不是 bind**：README 明确“单层 bind 不会带子挂载进容器”；脚本 3988 行注释说明 `/data/user/0` 等子挂载；这是 Android 多用户/FUSE 视图的关键。
2. **每个 rbind 后 `make-rprivate`**：脚本 3723–3725 行注释“断开与宿主的传播”；否则容器内 unmount 可能回灌宿主。
3. **高危区只读**：README 39–43 行、脚本 4045+ 对 `persist`/`qmcs`/`metadata` 强制 ro；这是“防变砖”而不是“防 root”。
4. **nsenter 停止**：脚本 1004+ 先 `nsenter` sync，再 `nsenter` kill/unmount；因为 per-launch namespace 的挂载宿主看不见。
5. **预检 chroot/seccomp/SELinux**：脚本 2925+ 先做 `chroot / /system/bin/sh -c exit 0`，避免挂了一堆后才发现 chroot 被 seccomp/SELinux 拒绝。
6. **`TMPDIR` 修复**：脚本 3508 行注释明确提到 MT 终端的 `TMPDIR` 进入 chroot 后不存在，导致 `mkstemp` 失败；强制 `TMPDIR=/tmp`。
7. **`resolv.conf` 直接复制而不是 bind**：脚本 4045+ 注释：bind 单文件在嵌套 loop/多层挂载下可能 EBUSY；发行版可能把 `/etc/resolv.conf` 做成 dangling symlink。
8. **镜像模式用于 `/data` 真 1:1**：README 提到 `--auto-migrate-image` 在 rootfs 位于 `/data` 子树时迁移为镜像；否则 `/data` bind 会自递归。

## 6. 更优雅的做法（带代价）

| # | 现状 | 更优雅 | 代价/风险 |
|---|---|---|---|
| 1 | 4183 行单文件 shell，功能全耦合 | 拆成 `preflight`/`mount`/`lifecycle`/`android-runtime` 四个模块；用 s6/DSH 替代 sshd | 需要重写；但可测试性/可维护性大幅提升 |
| 2 | 默认 `--full-access` + 可选 `--permissive` | 默认 `daily`（最小面），`lab` 显式开启；`--permissive` 只作为 break-glass，记录并自动恢复 | 用户要多一步；但避免“默认全开” |
| 3 | 默认 sshd + 密码 123456 | 不要 sshd；用 DSH web/ttyd + loopback token；ssh 仅 lab 可选 | 失去 SFTP；但攻击面和凭据风险下降 |
| 4 | 每个 rbind 后 `make-rprivate` + 大量 lazy umount | 保留 `make-rprivate`；卸载在 namespace 内按栈逆序，`umount -l` 只作最后手段并记录证据 | 需要更严谨的状态机；但不会掩盖 EBUSY |
| 5 | 目录 rootfs 下 bind `/data`，用 `.rootfs_data` 规避 | **lab 用 ext4 镜像挂到 `/mnt` 外再 rbind `/data`**；daily 不挂 `/data` | 镜像 20GB、loop/FBE 限制；但彻底避免自递归 |
| 6 | 高危区 ro 只覆盖 persist/qmcs/metadata | 再加 `/proc/sysrq-trigger`、`/sys/fs/selinux`、`/dev/block` 规则；明确“ro bind 不挡块设备写” | 规则维护；但认知正确 |
| 7 | 没有快照/回滚 | 目录 rootfs 冷 tar；镜像文件备份；`dsh-ctl restore` | 需要空间/停机；但符合可逆性 |
| 8 | 未声明 license | 只借鉴策略，不复制代码；或者联系作者确认 MIT/Apache-2.0 | 法律不确定性；不能直接并入我们的 MIT 模块 |

## 7. 对我们的启示

**直接采纳**
- 挂载表（尤其 `/data` rbind、`/mnt` rbind、`/storage` rbind、`/apex` rbind、高危区 ro）；
- 每个 rbind 后 `make-rprivate`；
- `unshare --propagation private` + `mount --make-rprivate /`（失败要检查传播属性，不能静默）；
- `preflight_chroot` 的 chroot/Seccomp/SELinux 诊断；
- `nsenter` 进 namespace 做 sync/kill/umount；
- `pid_root_matches_target` + `/proc/*/root` 扫描；
- `TMPDIR=/tmp`、`resolv.conf` 复制、Android 命令包装器；
- 镜像模式用于 `/data` 真 1:1（lab 档）。

**明确不采纳**
- 默认 full-access；默认 sshd/123456；默认 `--permissive`；`umount -l` 作为常规手段；4183 行单文件；没有快照/回滚；未声明 license 的代码复制。

**结论**：把它当 **lab/full 档的参考实现**，不把它当依赖；v1.3 的 `lab` 档几乎可以按它的挂载表实现，但默认档要收窄。
