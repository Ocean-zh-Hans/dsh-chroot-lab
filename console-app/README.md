# Chroot Console（可选 Android 控制台）

它不是“换个浏览器”，而是 chroot 的低频控制台；DSH Web 只是其中一个入口。
命名跟随 chroot，而不是跟随 DSH。

## 功能

- 状态卡片：running/stopped/degraded、PID、URL、loop、镜像路径；
- 生命周期：Start（`--apply`）、Stop、Restart、Refresh、Plan、Doctor；
- 日志：`log 200`；
- 快照：创建快照、列出快照；
- Purge：二次确认后清理 `/data/adb/chroot` 和镜像目录；
- DSH Web：只打开 `127.0.0.1` / `localhost` 的 DSH URL；
- 设置开关：
  - Autostart on boot（写 `state/ctl/autostart`）
  - Protect critical paths（下一次 Start 传 `--protect-critical=off`）
  - Guard deny/ask（`dsh-ctl guard on|off`）
  - System-RW `/system`（运行中时 `system-rw on|off --apply`）

所有动作都通过设备自身的 `su`/KSU 授权，执行固定 `dsh-ctl` 命令；不接收任意 shell。
它不提供 Accessibility UI 树/通知/剪贴板；那些由独立的 `agent-app` 提供。

## 安装方式

模块构建时会把 APK 同时放到：

```text
module/console.apk
module/system/priv-app/ChrootConsole/ChrootConsole.apk
```

KernelSU 开机 overlay 后，桌面应直接出现 `Chroot Console` 图标。`customize.sh`/
`service.sh` 还会尝试 `pm install -r` 作为 fallback。

如果还是没有图标，手动安装：

```sh
adb install -r console-app/dist/chroot-console-0.1.0.apk
# 或
adb shell su -c 'pm install -r /data/adb/modules/dsh_chroot/console.apk'
```

## 构建

```sh
./console-app/build.sh
# console-app/dist/chroot-console-0.1.0.apk
```

Termux 没有 `zipalign` 时产物未对齐；发布前用 Android build-tools 补 `zipalign`。

> 这是可见 App，需要用户授予 root；不是隐藏性组件。不需要时可以不安装/不授权。
