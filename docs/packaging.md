# 打包设计（当前）

## 1. 产物

| 产物 | 内容 | 状态 |
|---|---|---|
| `dsh_chroot-<version>.zip` | 模块骨架 + 本地构建的 `tools/bin`（不含 payload） | 可构建 |
| `rootfs.tar.gz` | Ubuntu 24.04 + Node 24 + DSH + 工具链 | P3 生成 |
| `rootfs.img` | 128GB ext4 稀疏镜像（由 payload 烘焙） | P3 生成 |
| `SHA256SUMS` | 上述文件校验 | P3 |
| `dsh-agent-0.1.0.apk` | 可选宿主 thin agent（UI/通知/剪贴板） | 源码可构建，未真机验证 |

## 2. 模块 zip 结构

```
module.prop
customize.sh
service.sh
boot-completed.sh
uninstall.sh
action.sh
bin/
  dsh-ctl
  dsh-nsinit
  dsh-watchdog
lib/
  common.sh
  config.sh
  log.sh
  proc.sh
  state.sh
  lock.sh
  loop.sh
  mount.sh
  doctor.sh
  plan.sh
  status.sh
  lifecycle.sh
  snapshot.sh
  systemrw.sh
  guard.sh
  image.sh
control/
  bin/dsh-run
  s6/dsh/run
  s6/dsh/finish
  s6/ttyd/run
  s6/ttyd/down
  profile.d/dsh-android.sh
  hooks/hooks.json
  hooks/pretool.sh
  hooks/posttool.sh
  hooks/deny.txt
  hooks/ask.txt
tools/
  README.md          # 二进制不提交；构建时放入
  bin/dsh-mount-ro-recursive  # build/build-tools.sh 生成
```

**禁止**：

- 包根出现 `install.sh`（KernelSU/Magisk legacy 分支只解压三个文件）。
- 把 payload 放进模块 zip（安装时解压会卡住管理器）。
- 把 `.tar.zst` 作为 payload（KSU busybox tar 不支持 zstd）。
- 把工具二进制提交进 git（用 `build/build-tools.sh` 或发布附件）。

## 3. 安装期

`customize.sh`：

1. 检查 `ARCH=arm64`、API ≥ 29；
2. `mkdir -p /data/adb/chroot/{control,state,logs,dsh,snapshots,payload}`；
3. 如果存在运行中的 holder，abort 并提示先 `dsh-ctl stop`；
4. 复制 `control/` 到 `/data/adb/chroot/control`，权限 0755；
5. 不复制/解压 payload；不启动；
6. 写 `state/ctl/install-version`。

## 4. 开机

- `service.sh`：立即 `exit 0`，后台 `dsh-ctl boot`；等 `sys.boot_completed=1` + `sys.user.0.ce_available=1`；启动 watchdog。
- `boot-completed.sh`：只写日志；不重复启动。
- boot counter / last-good：P3 外部验证；当前代码状态机已有停止/快照基础。

## 5. 卸载

- `uninstall.sh` 不删除 `/data/adb/chroot`；
- 如果有 holder，先调用 `dsh-ctl stop`；
- 明确 `purge` 才删 `/data/adb/chroot` 和 `/data/media/0/ubuntu-rootfs`。

## 6. 工具链

必须自带或校验：

| 工具 | 原因 |
|---|---|
| util-linux `losetup` | toybox `-S` 是 sizelimit，不支持 `--sector-size` |
| `mke2fs`/`e2fsck`/`resize2fs` | 镜像创建/检查/扩容 |
| GNU `cp` 或 tar | toybox `cp` 无 `--sparse` |
| `dsh-mount-ro-recursive` | `mount_setattr(AT_RECURSIVE)`；6B/14B |
| util-linux `unshare`/`nsenter` | `--propagation private` 和 `--mount`；toybox 不满足 |
| `busybox`（可选） | 仅作救援工具；不是启动依赖 |
| `sha256sum`/`sync` | 快照/一致性 |

版本和 sha256 记录在 `tools/SHA256SUMS`；构建时校验。

## 7. 构建门禁

1. `unzip -l` 无 `install.sh`；
2. 所有脚本 0755、`sh -n`、`shellcheck`、`shfmt`；
3. 设备侧用 mksh 解析（P2 起）；
4. `versionCode` 单调增；
5. payload sha256 记录；
6. 无密钥/凭据；
7. 冒烟：chroot 内 `node -e require('node-pty')`、`dsh --version`；
8. KSU busybox tar 实际解压测试；
9. 模块 zip 不含 payload；`tools/bin` 只包含本地构建的 helper。

## 8. 烘焙/构建

- `bake/build-rootfs.sh`：在 arm64 Ubuntu VM 上用 debootstrap 生成 rootfs。
- `bake/make-image.sh`：用 `mke2fs -d` 生成 128GB 稀疏 ext4。
- `bake/verify-rootfs.sh`：检查 Node/DSH/node-pty。
- `build/build-tools.sh`：编译 `dsh-mount-ro-recursive`。
- `build/build-module.sh`：打包模块 zip。

## 9. 当前构建

```sh
./build/build-tools.sh
./build/check.sh
./build/build-module.sh
# 产物：dist/dsh_chroot-0.2.0.zip
```

该 zip 可安装结构，但 **不能证明 chroot 可用**；P2 外部验证前不要在主
力机启动。
