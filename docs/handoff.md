# 交接：设备 / PC AI 验收手册

- 状态：**主力机是正式运行环境；当前读写模型按 2026-10-30 `plan-v2.md` 重写，SELinux 不再是 DSH 的读取/修改/门禁项。**
- 目的：在**明确识别的设备/arm64 VM** 上分层完成 P2/P3/P5 验收，不把一台设备的结果外推到另一台
- 主力机仅允许用户明确授权的恢复、备份、安装和激活：固定序列号 `3B1F6KE5MS11JHR8`。SELinux 不读取、不修改、不判断；不降级用户 DSH runtime/插件，不覆盖 chroot 历史工作区，不给 Agent root；`pre-compose-upgrade-20261029` 为已验证的 last-good。

---

## 0. 先明确边界

- `dsh-ctl start` / `rescue` / `system-rw on|off` 都要求显式 `--apply`；
- 没有 `--apply` 只打印计划/退出 3；
- 所有 chroot mount 必须在 **private mount namespace** 内；
- 永远不要对宿主的 `/sys/fs/selinux` 做 bind/remount；
- 不要在主力机的主 namespace 做 overlay/debugfs/tracefs/loop 试验；
- 每次测试前备份 `/data/adb/chroot`、镜像和 `/sdcard/DSH`。

---

## 1. 在 PC / aarch64 VM 构建

### 1.1 rootfs

在 **arm64 Ubuntu 24.04 VM**（不是手机）上：

```sh
sudo apt-get update
sudo apt-get install -y debootstrap e2fsprogs util-linux shellcheck mksh
sudo ROOTFS_DIR=/srv/dsh-rootfs \
  NODE_VERSION=24.18.0 \
  DSH_VERSION=0.2.0-rc.2 \
  ./bake/build-rootfs.sh

sudo ROOTFS_DIR=/srv/dsh-rootfs \
  IMAGE_PATH="$PWD/out/rootfs.img" \
  IMAGE_SIZE=128G \
  ./bake/make-image.sh

ROOTFS_DIR=/srv/dsh-rootfs ./bake/verify-rootfs.sh
```

产物：

- `out/rootfs.tar.gz`
- `out/rootfs.img`
- `out/SHA256SUMS`（由烘焙脚本生成）

### 1.2 模块工具

模块**必须**自带：

- `dsh-mount-ro-recursive`（`build/build-tools.sh` 编译；提供
  `--make-rprivate`、`--rbind`、`--bind`、`--remount-ro*`、递归 ro）
- GNU `cp` 及 ARM64 运行库（`tools/bin/cp`、`tools/libexec/cp`、`tools/lib`）。
  `build/android-tools.json` 固定包版本和 SHA256；`build/fetch-android-tools.py`
  校验架构、依赖和许可证。wrapper 只给该命令设置 `LD_LIBRARY_PATH`。

可选（没有也能用 Android toybox fallback）：

- util-linux `losetup`（`--sector-size`）
- util-linux `unshare`/`nsenter`（默认路径已改成 toybox 的 `-m -u` / `-t PID -m`）

`build/build-tools.sh` 默认使用 `DSH_NDK_VERSION=28.2.13676358`，也可用
`ANDROID_NDK_HOME` 指定已安装的 NDK。产物必须是 Android ARM64、
`/system/bin/linker64` 或静态链接；不能使用宿主 glibc 二进制。

检查：

```sh
./build/build-tools.sh
./build/build-tools.sh --verify
file module/tools/bin/dsh-mount-ro-recursive
```

> 模块 zip 会打包 `tools/bin`、`tools/libexec`、`tools/lib`、许可证、
> `TERMUX-SOURCES.json` 和校验清单，并在打包时复核。
> 安装期还会给原生 helper、GNU cp 和共享库设置
> `u:object_r:shell_data_file:s0`；这是针对备用机 Magisk 域执行 `system_file`
> 被拒绝的兼容性修复，不是关闭 SELinux 或修改全局策略。模块激活后必须再次
> 执行 `doctor --for-apply`，确认上下文未被重置、程序确实可以运行。

### 1.3 构建模块

```sh
./build/check.sh
./build/build-module.sh
# dist/dsh_chroot-0.2.2.zip（默认不含控制台或 system overlay）
```

### 1.4 可选 App

```sh
./agent-app/build.sh
# agent-app/dist/dsh-agent-0.2.0.apk

./console-app/build.sh
# console-app/dist/chroot-console-0.2.0.apk
```

默认模块不安装 App，也不添加 `system/priv-app`。需要随模块携带 Console 安装包时，
使用 `./build/build-module.sh --with-console`，它只添加模块根的 `console.apk`。
安装必须由用户明确选择：`adb install -r console-app/dist/chroot-console-0.2.0.apk`。

本备用机的 Console root 授权入口是 **SUList（root 管理器）**，不是面具的应用列表。
只给 `dev.chroot.console` 主动授权，然后彻底结束并重新打开 App，再验证状态；不要给
Agent root，也不要修改全局默认拒绝或 Shamiko。测试结束后只撤销本次 Console 的授权，
保留其他应用和原有 root 策略。不要仅凭授权弹窗或点击被接受就宣称授权成功。

---

## 2. 备用机安装

1. 备份备用机数据，并确认设备序列号不是主力机。
2. 用 KernelSU 管理器安装 `dsh_chroot-0.2.2.zip`；重启。
3. 只在备用机推送镜像并校验安装：

```sh
adb push out/rootfs.img /data/local/tmp/rootfs.img
adb push out/rootfs.img.sha256 /data/local/tmp/rootfs.img.sha256
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl image install \
  --source /data/local/tmp/rootfs.img \
  --sha256 /data/local/tmp/rootfs.img.sha256 --apply'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl doctor'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl plan'
```

`image install` 会验证 SHA256 和 ext4，写入临时文件后原子安装；已有镜像时必须显式 `--replace`。
必须先 `doctor --for-apply` 全绿（warning 可接受，fail 不可）。

---

## 3. 首次启动（P2）

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl doctor --for-apply'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl start --apply'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl status'
# URL/token 仅在本地使用；不要把完整 URL 或 status --json 原文写入共享日志。
```

如果 `status` 是 `degraded`：

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl diag'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl log 200'
```

不要直接反复 `start`；先 `stop`，看 `nsinit.log`。如果是 `recovery-required`，必须先成功 `recover --apply`，不能继续 snapshot、rescue 或 purge。

**DSH 没启动不等于必须先进 chroot。** 已授权 root 的 Console 独立于 holder/DSH，可直接查看状态、Stop 或执行“清理残留”；结束 Console 不会停止 chroot。也可通过 adb+su 在宿主执行本节的 `doctor/status/diag/log`、`recover --apply`，需要只读修复 shell 时先清理再 `rescue --apply`。不要安装直接裸跑 `dsh-nsinit` 的 `service.d` 脚本，它会绕过镜像校验、holder、锁和清理。没有默认开放匿名网络 root shell 或无人值守远程恢复。

guest 的配置和恢复说明位于 `/opt/dsh-control/README.md`；`/run/dsh/module.json` 与 `module.sha256` 是启动时生成的只读元数据，不代表开放模块目录。工作区使用独立 `state/workspace` 并绑定 `/root/workspace`，`state/dsh-home` 仅交给 DSH。安装和旧快照恢复会迁移真实 legacy 工作区，冲突或 legacy 链接拒绝自动处理。正常模块安装不替换用户已升级的 DSH runtime、插件或其它 dsh-home 数据；恢复快照前仍需注意其中可能包含旧 dsh-home 和会覆盖当前文件的工作区。

### 3.1 P2 必测

1. `start` → `status` running；
2. 宿主 `/proc/self/mountinfo` 不含 `dsh-rootfs`；
3. 记录 `/data/data`、`/data/user`、`/data/user_de` 的实际 profile 可见范围；完整 `/data` 绑定不等于所有应用私有数据可写，写入前需单独确认并备份；
4. `/mnt`、`/data_mirror`、`/storage`、`/sdcard` 可见；
5. `/dev/block` 默认只读可见，原始写只允许显式 `DSH_BLOCK_ACCESS=full`；binder/ashmem 可用；
6. `/sys` 保持设计的 rw 视图；guest 内 selinuxfs 默认只读可见，不读取/不修改全局 SELinux；`/dev/block` 默认只读；
7. `/apex` 子挂载递归 ro；`touch /apex/...` 应失败；
8. `/linkerconfig` 递归 ro；
9. `/metadata`、`/mnt/vendor/persist`、`/mnt/vendor/qmcs` ro；
10. `stop` 后无同 namespace 进程、无 holder、无 loop、`/tmp/dsh-rootfs` 空；
11. 连续 start/stop 20 次；每次记录 `status` 和 `losetup -a`。

建议记录：

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl diag' | sed -E 's/(token=)[^ &"]+/\1REDACTED/g' > /sdcard/DSH/diag-$(date +%s).txt
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl status --json' | sed -E 's/(token=)[^ &"]+/\1REDACTED/g'
su -c 'losetup -a'
grep dsh-rootfs /proc/self/mountinfo || echo no-host-mount
```

### 3.2 失败清理

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl stop'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl status'
```

如果 holder 已死、卡住、状态是 stale，或 `/tmp/dsh-rootfs` 非空/loop 残留：

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl recover --apply'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl status'
su -c 'ps -A | grep -E "dsh-nsinit|s6-svscan"'
su -c 'losetup -a'
```

`recover --apply` 可强制停止卡住的 holder，通过已记录的 private namespace
逆序清理递归子挂载，再 detach 属于本镜像的 loop。只有 mountinfo 仍含记录
rootfs，或 `/proc/PID/root` 指向该 rootfs 的进程才属于本实例；chroot 子进程
也必须被识别。namespace inode 复用时不得进入或结束无关应用。
失败会保留 `recovery-required`，不能把它当作清理成功。它不会删除镜像或用户数据。

`0.2.2` 起 holder 额外记录 `launcher.bootid`，身份校验同时要求它与当前内核 boot_id 一致。运行中直接重启后，boot 只对明确属于上一次启动的记录进行安全清理；旧版本没有 boot_id 时先显式执行一次 `recover --apply`。身份不符的 recover 不向旧 PID 发信号，只有确认没有进程挂载本 rootfs、目标镜像没有任何 loop 后才清除遗留记录；查询失败或仍有占用会保留 `recovery-required`，不能强杀 PID 或直接删除状态文件。

holder 等待 guest 进程退出时，通过 `nsenter` 在宿主 mount namespace 中执行只读扫描，避免扫描器及其子进程被误认成 guest 存活进程；扫描输出使用宿主状态目录中的私有临时文件传递。该路径不增加挂载，也不改变 guest 的 private namespace。停止和 detach 前的全局 mount/namespace 复核仍保留，不能用定向扫描替代全局安全检查。

holder 已开始清理时，stop 有界等待清理完成，不重复发送 TERM；zombie 不算存活。
普通停止阶段默认等待 holder 最多 30 秒（可用 `DSH_STOP_TIMEOUT` 调整，Windows mock 使用 120 秒），强制阶段再等待最多 10 秒；递归挂载清理可能超过旧的 10 秒窗口。清理超时仍拒绝中断，强制恢复必须显式选择 `recover --apply`。

`service.sh` 记录每次 boot 尝试的退出码和脱敏状态，首次失败后等待 30 秒并最多重试一次；watchdog 在尝试结束后启动，不再与 boot 并发争锁。watchdog 的 sleep 失败会退出 daemon，输出单独写入 `watchdog.log`，重复行每 60 次至多报告一次，约 1 MiB 轮转并保留 3 代；`boot.log` 保留 5 代。当前及轮转日志均要求 0600，失败状态输出中的 token 必须脱敏。

只有在确认没有 holder/loop 后，才允许处理镜像/快照。`start` 在 fsck 前、fsck 后和 attach 前都必须证明镜像空闲，查询失败不能继续。loop 别名按内核身份/设备号比较，不属于目标镜像的设备不得 detach。

purge 先写操作标记阻止 watchdog claim，执行 `dsh-watchdog --stop --disable`，再在同一把生命周期锁内停止实例、复核占用并删除；失败清标记并恢复 watchdog，可用 `dsh-watchdog --enable` 显式恢复。`uninstall.sh` 同样先停用 watchdog，后续 stop 失败且此前未 disable 时恢复。daemon owner 必须校验 PID/starttime/命令路径，存在 boot_id 时也必须匹配；旧记录失效时只清理元数据，不向复用 PID 发信号，真实 daemon 不得重复 claim，半成品清理不得删除现有 owner。停止等待默认最多 45 秒（`DSH_WATCHDOG_STOP_TIMEOUT` 可调整），失败不能继续删除。

---

## 4. P3：DSH / 快照 / 开机

### 4.1 DSH

`dsh-run` 根据 profile 依赖选择 hooks patch。优先复用当前 runtime 中同版本的 permission-presets，只在 profile 缺少该条目时创建链接，不覆盖已有依赖；无法提供时明确降级为 `hooks-audit-only.patch.yml`，审计依赖也缺失时禁用 patch。`/run/dsh/hooks.status` 和 `dsh.log` 记录 `full`、`audit-only` 或 `disabled` 及原因；这是本层请求状态，不冒充上游加载成功。完整 patch 下默认 `approval-free` 不弹批准；`approval-guard` 只在显式开启时使用。
`SessionStart`/高风险 `UserPromptSubmit` 通过 `additionalContext` 提醒块设备和全局 prop 风险；`PostToolUse` 保留审计；不再使用 PreToolUse 硬拦截。SessionStart 提醒可能错过首个请求，不能当作安全边界。

`dsh-run` 还会进行真实 loopback HTTP 健康检查并维护带时间戳的 heartbeat。启动超时或连续健康失败会先 TERM web 子进程，等待最多 10 秒后仅 KILL 该登记子进程，由 s6 重启 DSH 服务；watcher 失败状态不会被正常退出覆盖。健康恢复后可回到 running。status 需要新鲜 heartbeat，旧 URL 文件不再单独代表 running。

控制台界面使用中文并适配系统栏；“清理残留”执行 `recover --apply`，不是快照恢复。状态命令每个最多等待 15 秒，生命周期命令最多 180 秒，输出读取与等待都有边界；超时返回 124，并提示后台操作可能仍在进行，不能把超时视为停止成功。Console 与 holder/DSH 生命周期独立，退出或结束 Console 不会停止 chroot；DSH 未启动时仍可用 Console 查看状态和执行已授权的恢复操作。

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl status'
# URL/token 只在本地使用，不把完整 URL 或 status --json 原文写进共享日志。
```

- URL 应该只包含 `127.0.0.1`；token 不要在回传日志中公开。
- 用 `curl` 或手机浏览器打开；验证 `node-pty`、DSH 版本、workspace。
- `guard on|off` 只改变新会话的默认预设，重启 DSH/chroot 后生效；已有会话使用 `/permission` 切换。
- `ttyd` 默认关闭。确有需要时在备用机执行 `dsh-ctl ttyd on`，密码只在 root 私有的 `state/runtime/ttyd.pass`；使用后用 `dsh-ctl ttyd off` 关闭。

### 4.1.1 软件层（DSH 本体）

`app` 系列只读探针需要 chroot 正在运行；切版本只改宿主侧 `$DSH_ROOT/dsh/{current,previous}` 符号链接，要求 chroot 已停止，并按 R3 记录原因与一次性 token。

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl app version'   # dsh/node/pnpm/hooks 与来源
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl app plugins'   # 控制面预设与 phone-mcp 的生效状态
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl app exec -- dsh --version'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl stop'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl app switch <version> --apply'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl app rollback --apply'
```

`app exec` 是 R1：执行前需要 `DSH_RISK_CONFIRM=yes`，控制台会替用户带上。`app switch` 只切换已安装版本，不下载新版本；没有 `versions/<v>/bin/dsh` 的目录一律拒绝。

### 4.2 快照

独立宿主 `state/workspace` 的内容随 state 快照归档并绑定 `/root/workspace`，不写入 rootfs.img；`state/dsh-home` 仅保存 DSH 自己的数据。旧快照中的真实 `.dsh/workspace` 会在恢复暂存区迁移为独立目录，legacy 链接或新旧目录冲突会拒绝恢复。恢复可能覆盖当前工作文件，不能在未确认内容和备份的情况下回滚。

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl stop'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl snapshot create before-test'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl snapshot list'
```

先在备用机完成启动和基本 smoke 检查，再 stop，并显式执行：

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl snapshot promote before-test'
```

`last-good` 此时才指向已校验快照，不是每次启动自动复制当前镜像。
可以在**备用机 rootfs 内**增加临时测试文件，再执行 `restore before-test`，
确认该文件消失且 state 一并恢复；不要破坏 Android 数据分区。

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl restore before-test --apply'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl start --apply'
```

注意：快照要求无 holder、无相关 namespace、无 loop；不要在 holder 运行时复制镜像。
boot 只在明确的 e2fsck 失败时尝试一次 last-good，其他失败进入人工恢复。

### 4.3 开机

成功启动后 `autostart=1` 已写入。重启备用机，检查：

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl status'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl diag'
```

连续 3 次失败后应出现 `rescue-needed`，自动启动被抑制。
`sys.user.0.ce_available` 在不同 ROM 上可能返回 `1` 或 `true`，按布尔值接受两者；不能把等待条件写死为 `=1`，但 `sys.boot_completed=1` 仍必须满足。

### 4.4 rescue

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl rescue --apply'
```

必须先成功 stop/recover，才能执行 `rescue --apply`。进入只读 `/bin/bash`；
验证完成后 `exit`，holder 会等待并清理。rescue 不挂 `/data`/`/storage`，
但保留 `/proc`、`/dev` 和只读 `/sys`，它不是安全沙箱。

---

## 5. P4：host agent（可选）

```sh
adb install -r agent-app/dist/dsh-agent-0.2.0.apk
```

1. 打开 App，查看 bridge 状态和 token 文件路径；
2. 确认 manifest 含 `android.permission.INTERNET`，但不授予 App root；
3. 启用无障碍；如需通知，再启用通知使用权；
4. 测试：

```sh
su -c 'cat /data/data/dev.dsh.agent/files/token'
su -c 'printf ... | nc 127.0.0.1 17321'
```

协议见 `agent-app/README.md`。先测 `ping`、`ui.dump`，再测点击/文本。
不要把 agent 当隐藏性组件；它的包名可见。

---

## 6. P5：检测矩阵

至少记录：

- Play Integrity / 银行 / 游戏是否因本模块可见信号失败；
- App 自己的 `/proc/self/mountinfo` 是否出现 chroot 挂载；
- 全局 `getprop` 是否被改；
- `/sys/fs/selinux/enforce` 是否为 1；
- KSU `selinux_hide`/`mount_hide`/`avc_spoof`/`kernel_umount` 是否仍启用；
- `/sdcard` 是否出现 `magisk`/`kernelsu`/`su` 标记。

**不要在没有记录的情况下写“完美/全部通过”。**

---

## 7. 回传内容

请把以下内容回传给开发端（脱敏）：

1. 设备型号/Android/内核/SukiSU 版本；
2. `dsh-ctl doctor` 全文；
3. `dsh-ctl status --json`；
4. `dsh-ctl diag`；
5. `dsh-ctl log 200`（去掉 token/API key）；
6. `mountinfo` 中 `dsh-rootfs` 搜索结果；
7. `losetup -a`；
8. 20 次 start/stop 的结果表；
9. 失败时的完整 `nsinit.log`；
10. DSH URL 是否能打开、node-pty 是否正常。

---

## 8. 绝对禁止

- 在主力机执行超出用户明确授权范围的 `start --apply` / `rescue --apply` / `system-rw --apply`；本轮只允许既定 private-namespace 手动验证，不允许 rescue/system-rw；
- 在宿主 namespace 做 mount/overlay/debugfs/tracefs/loop 试验；
- 对 `/sys/fs/selinux` 做任何 bind/remount；
- 运行 `setenforce`（DSH 不读取、不修改、不判断 SELinux）；
- 用 `kill -9` 代替 `dsh-ctl stop`；
- 未确认 loop 已 detach 就复制/删除镜像。
