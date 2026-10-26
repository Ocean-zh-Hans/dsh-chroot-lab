# 实施路线（从零到可用，再到模块）

原则：每一步都能独立验收；P0 不通过不进入打包。

## 阶段 0：烘焙 rootfs（在手机上自举，1–2 小时）

1. 下载 `ubuntu-base-24.04.5-base-arm64.tar.gz` + 校验 SHA256；
2. 解压到 `/data/adb/chroot/rootfs`；
3. `busybox unshare --propagation private -m` + 挂载 `/dev`、`/proc`、`/sys`、写 `/etc/resolv.conf`（`nameserver 8.8.8.8`/`1.1.1.1`；Android 没有 `/etc/resolv.conf`）；
4. **不要直接 `chroot rootfs /bin/bash`**：用 `env -i PATH=... HOME=/root TMPDIR=/tmp LANG=C.UTF-8 TERM=xterm-256color` 进入，否则 `apt-get: command not found`，且 `TMPDIR` 继承 Termux 会让 `ca-certificates` postinst 失败（2026-10-26 实测）；再 `apt update && apt install -y s6 git curl ca-certificates xz-utils`；
5. 装 Node 24：下载官方 `node-v24.22.1-linux-arm64.tar.xz`（sha256 `33c37be2…`），解压到 `/opt/node`，软链到 `/usr/local/bin`；
6. 装 DSH：`npm_config_unsafe_perm=true npm i -g @deepseek-ai/dsh@<pin>`（root 下 npm 默认降权，node-pty 的 prebuild 脚本可能写不进包目录；或装到 `/opt/dsh/versions/<v>`），验证 `node-pty`/`koffi`/`sharp` 原生模块；
7. 冒烟：`node -e "require('node-pty')"`、`dsh web --port 0 --no-open` 打印 token URL；
8. 退出、卸载、`tar -czf` 打包 → `payload/rootfs.tar.gz` + sha256（**不要 `.tar.zst`**：KSU busybox tar 不支持 zstd）。

> 也可以在一台 aarch64 Linux/PC（QEMU）上烘焙，产物同样适用；手机上自举的好处是环境与目标一致。

## 阶段 1：P0 手动验证（不做模块，1 天）

- 解压 payload 到 `rootfs/`，按 `08-design-review.md` §5.2 的挂载表（不是 05 §5.2 原文）挂载，`chroot` 进入；
- 验证 P0 命门：root/uname、Go 编译并运行（/sdcard raw）、node-pty PTY、`dsh web --port 0 --no-open`、stop 后全机 `/proc/*/mountinfo` + `/proc/*/root` 无残留、`.tar.gz` 快照+回滚、`/apex` rbind、工作区 2777/0666、钩子自检；
- 记录每个命令与输出到 `notes/p0-log.md`。

## 阶段 2：脚本化（dsh-ctl，2–3 天）

- `dsh-ctl start|stop|restart|status|diag|log|snapshot|restore`；
- 每个动作幂等；stop 走状态机；status 输出 rootfs/DSH 版本、端口、token 路径、挂载数；
- 失败时给出可操作的下一步（不是只打印错误）。

## 阶段 3：模块化（1 周）

- 按 `06-packaging.md` 打包；
- 安装 → 重启 → 自启 → 停/启 → 卸载（保留数据）全流程；
- 模拟失败：payload 损坏、rootfs 解压中断、端口占用、DSH 崩溃。

## 阶段 4：迁移 DSH 配置（半天）

- 从 Termux `~/.dsh` 复制 `settings`/`credentials`/插件清单到 `/data/adb/chroot/state/.dsh`；
- 重新登录/校验 API Key；跑一周并行；
- 稳定后再决定是否删除 Termux 的 dsh 运行时（1.4GB）与 DSHA。

## 回滚策略

- 每阶段前：`tar` 快照 + 记录当前 commit/版本；
- 模块升级：保留上一个 zip 与 payload；`dsh-ctl restore <snapshot>` 一键回退；
- 最坏情况：`/data/adb/chroot` 整体删除重建（数据先备份）。
