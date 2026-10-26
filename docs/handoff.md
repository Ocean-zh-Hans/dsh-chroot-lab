# 交接：备用机 / PC AI 验收手册

- 状态：**代码开发完成；本机没有执行真实 mount/loop/unshare/chroot/SELinux 操作**
- 目的：把开发产物交给 PC 端 AI，在**备用机**上完成 P2/P3/P5 验收
- 主设备：不要执行本手册中的 mount/apply 步骤

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
- `out/SHA256SUMS`（脚本生成的 `.sha256` 需汇总）

### 1.2 模块工具

模块需要 aarch64 Android 可执行的：

- util-linux `losetup`（`--sector-size`）
- util-linux `unshare`（`--propagation`）
- util-linux `nsenter`（`--mount`）
- `dsh-mount-ro-recursive`（本仓库 `build/build-tools.sh` 可编译）

放到：

```text
module/tools/bin/
```

检查：

```sh
file module/tools/bin/*
module/tools/bin/losetup --help | grep -- --sector-size
module/tools/bin/unshare --help | grep -- --propagation
module/tools/bin/nsenter --help | grep -- --mount
```

> 如果工具是动态链接，必须确认目标 Android 能加载（优先静态链接）。
> 模块 zip 会把 `tools/bin` 一起打包。

### 1.3 构建模块

```sh
./build/check.sh
./build/build-module.sh
# dist/dsh_chroot-0.2.0.zip
```

### 1.4 可选 App

```sh
./agent-app/build.sh
# agent-app/dist/dsh-agent-0.1.0.apk

./console-app/build.sh
# console-app/dist/dsh-console-0.1.0.apk
```

`build/build-module.sh` 会把 console APK 放进模块根 `console.apk`，
`customize.sh` 会尝试 `pm install -r`。自动安装失败时手动安装。

---

## 2. 备用机安装

1. 备份备用机数据。
2. 把 `dsh_chroot-0.2.0.zip`、`rootfs.img`、`SHA256SUMS` 推到备用机。
3. 用 KernelSU 管理器安装模块；重启。
4. 首次只读检查：

```sh
su -c 'dsh-ctl doctor'
su -c 'dsh-ctl plan'
```

必须先 `doctor --for-apply` 全绿（warning 可接受，fail 不可）。

---

## 3. 首次启动（P2）

```sh
su -c 'dsh-ctl doctor --for-apply'
su -c 'dsh-ctl start --apply'
su -c 'dsh-ctl status'
cat /data/adb/chroot/state/guest/url
```

如果 `status` 是 `degraded`：

```sh
su -c 'dsh-ctl diag'
su -c 'dsh-ctl log 200'
```

不要直接反复 `start`；先 `stop`，看 `nsinit.log`。

### 3.1 P2 必测

1. `start` → `status` running；
2. 宿主 `/proc/self/mountinfo` 不含 `dsh-rootfs`；
3. chroot 内 `/data/user/0`、`/data/data`、`/data/system` 可见；
4. `/mnt`、`/data_mirror`、`/storage`、`/sdcard` 可见；
5. `/dev/block`、binder/ashmem 可见；
6. `/sys` 可写测试用一个临时 sysfs 项；不要写 selinuxfs；
7. `/apex` 子挂载递归 ro；`touch /apex/...` 应失败；
8. `/linkerconfig` 递归 ro；
9. `/metadata`、`/mnt/vendor/persist`、`/mnt/vendor/qmcs` ro；
10. `stop` 后无同 namespace 进程、无 holder、无 loop、`/tmp/dsh-rootfs` 空；
11. 连续 start/stop 20 次；每次记录 `status` 和 `losetup -a`。

建议记录：

```sh
su -c 'dsh-ctl diag' > /sdcard/DSH/diag-$(date +%s).txt
su -c 'dsh-ctl status --json'
su -c 'losetup -a'
grep dsh-rootfs /proc/self/mountinfo || echo no-host-mount
```

### 3.2 失败清理

```sh
su -c 'dsh-ctl stop'
su -c 'dsh-ctl status'
su -c 'ps -A | grep -E "dsh-nsinit|s6-svscan"'
su -c 'losetup -a'
```

只有在确认没有 holder/loop 后，才允许处理镜像/快照。

---

## 4. P3：DSH / 快照 / 开机

### 4.1 DSH

```sh
su -c 'dsh-ctl status'
su -c 'cat /data/adb/chroot/state/guest/url'
```

- URL 应该只包含 `127.0.0.1`；
- 用 `curl` 或手机浏览器打开（token 只在 URL 中）；
- 验证 `node-pty`、DSH 版本、workspace：
  `dsh-ctl doctor` 或 chroot 内 `node -e "require('node-pty')"`。

### 4.2 快照

```sh
su -c 'dsh-ctl stop'
su -c 'dsh-ctl snapshot create before-test'
su -c 'dsh-ctl snapshot list'
su -c 'dsh-ctl restore before-test'
su -c 'dsh-ctl start --apply'
```

注意：快照只在 stopped 状态做；不要在 holder 运行时复制镜像。

### 4.3 开机

成功启动后 `autostart=1` 已写入。重启备用机，检查：

```sh
su -c 'dsh-ctl status'
su -c 'dsh-ctl diag'
```

连续 3 次失败后应出现 `rescue-needed`，自动启动被抑制。

### 4.4 rescue

```sh
su -c 'dsh-ctl rescue --apply'
```

进入只读 `/bin/bash`；验证完成后 `exit`。rescue 不挂 `/data`/`/storage`。

---

## 5. P4：host agent（可选）

```sh
adb install -r agent-app/dist/dsh-agent-0.1.0.apk
```

1. 打开 App，查看 token 文件路径；
2. 启用无障碍；
3. 如需通知，启用通知使用权；
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

- 在主力机执行 `start --apply` / `rescue --apply` / `system-rw --apply`；
- 在宿主 namespace 做 mount/overlay/debugfs/tracefs/loop 试验；
- 对 `/sys/fs/selinux` 做任何 bind/remount；
- 默认 `setenforce 0`；
- 用 `kill -9` 代替 `dsh-ctl stop`；
- 未确认 loop 已 detach 就复制/删除镜像。
