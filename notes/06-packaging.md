# 模块打包清单（v1）

> **2026-10-26**：v1.2 配套稿；v1.3 的模块/打包以 [`10-design-v1.3.md`](10-design-v1.3.md) §11 为准。

- 目标：一个 KB 级模块 zip + 一个约 0.6–1.2GB 的 payload；模块只负责启动/挂载/监督/清理。
- 依据：KernelSU 模块文档、DSH-Chroot 的 module.md/payload.md、issue #3396/#3693

## 1. 模块 zip 内容（不含 payload）

```
module.prop
customize.sh          # 安装期：校验 + 建目录 + 复制 control/ + 权限；不解压 payload
service.sh            # 开机：立刻 return，后台 bootstrap
uninstall.sh          # stop + 清理；保留 /data/adb/chroot
bin/dsh-ctl           # start/stop/status/diag/log/snapshot/restore
bin/boot-bootstrap    # 等 boot_completed + /data/media/0 → 解压/校验 → 启动
control/hooks/pretool.sh   # deny/ask 规则（读 deny.txt/ask.txt）
control/hooks/posttool.sh  # 审计 JSONL
control/hooks/deny.txt
control/hooks/ask.txt
control/hooks/hooks.json
control/s6/dsh/run
control/s6/ttyd/run
```

**关键**：模块目录 `/data/adb/modules/<id>` 不会被 bind 进 chroot；`customize.sh` 必须把 `control/` 复制到 `/data/adb/chroot/control`（宿主路径），运行时再 bind 到客机 `/opt/dsh-control`。不要把 `hooks/`、`etc/s6/` 留在模块根目录指望客机可见。

**禁止**：包根出现 `install.sh`（触发 legacy 分支，只解压三个文件，装不上）；payload 放进 zip。

## 2. module.prop

```
id=dsh_chroot
name=DSH Chroot
version=1.0.0
versionCode=1
author=<you>
description=Real chroot (Ubuntu 24.04 glibc) + DSH + s6; data in /data/adb/chroot
```

- `id` 发布后不可改；`versionCode` 单调整数，唯一比较依据；`version` 不参与比较。
- 可选 `updateJson`（指向自己的 release JSON：`{version, versionCode, zipUrl, changelog}`）。

## 3. customize.sh（安装期）

- 校验：`uname -m` = aarch64、API ≥ 29、`/data` 剩余 ≥ 3GiB、payload 存在（若已 push；不存在就 `abort` 并给出 push 指令）。
- 建目录：`/data/adb/chroot/{payload,rootfs,dsh,control,state,logs,snapshots}`。
- 复制 `control/` → `/data/adb/chroot/control`；权限：`chmod 0755` 所有脚本（issue #3396）。
- **不解压** payload（issue：管理器会等安装脚本，800MB 解压会卡死）。

## 4. service.sh（开机）

- 立刻 return；`setsid` 后台跑 `bin/boot-bootstrap`，日志重定向到 `/data/adb/chroot/logs/bootstrap.log`。
- `boot-bootstrap`：等 `sys.boot_completed=1` 且 `/data/media/0` 可见（≤300s）→ 校验 payload sha256 → 解压到 `rootfs.new` → mv 交换 → `busybox unshare --propagation private -m` + 挂载 + chroot → 起 s6。
- **不用 post-fs-data**（issue #3693：APEX 未激活）。

## 5. uninstall.sh

- 调用 `dsh-ctl stop`（PGID TERM→KILL→`/proc/*/root` 扫描→`/proc/*/mountinfo` 校验；per-launch namespace 不需要宿主 umount）；
- 只删模块目录；**保留 `/data/adb/chroot`**（rootfs/state/payload/snapshots 都是用户数据）；提供 `dsh-ctl purge` 作为显式全删。

## 6. payload

- `rootfs.tar.gz`：Ubuntu base 24.04 arm64 + Node 24（官方 tar.gz）+ DSH（npm）+ s6 + 基础工具（git/curl/ca-certificates/go 可选）。
- **不要用 `.tar.zst`**：KSU busybox `tar` 不支持 zstd（2026-10-26 实测）；开机 bootstrap 只能用 `-z`/`-J`/`-j`。若坚持 zstd，必须自带静态 `zstd` 并改 bootstrap 为 `zstd -d | tar -x`。
- `SHA256SUMS` 随包；安装/启动时校验。
- 体积估算：Ubuntu base ~30MB、Node ~50MB、DSH ~500MB、其余 ~50MB → 压缩后约 250–400MB，解压后 0.8–1.2GB。

## 7. 构建门禁（打包前必须全过）

1. `unzip -l <zip> | grep -x 'install.sh'` 为空；
2. 所有脚本 0755、`sh -n` 通过、`shellcheck` 通过；
3. 设备侧脚本用 **mksh** 跑一遍（`/system/bin/sh` 是 mksh，`${s%%|*}` 有方言坑）；
4. `versionCode` 比上一版大；
5. payload sha256 记录进 release 说明；
6. 不含任何密钥/凭据；
7. 冒烟：解压后 chroot 内 `node -e require('node-pty')`、`dsh --version`；
8. **payload 用 KSU busybox `tar` 实际解一遍**（不是 GNU tar）；`.tar.zst` 直接判失败。
