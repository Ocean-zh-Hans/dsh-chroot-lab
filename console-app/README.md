# DSH Console（可选 Android 前端）

这是一个很小的 WebView 前端，给“刷完模块后想直接点图标打开 DSH”的用法：

- 读取模块状态文件 `/data/adb/chroot/state/guest/url`；
- 通过设备自身的 `su`/KSU 授权执行固定命令：
  `/data/adb/modules/dsh_chroot/bin/dsh-ctl start --apply|stop|status`；
- 只允许加载 `http://127.0.0.1` / `http://localhost` 的 DSH URL；
- 不包含任意 shell，不自动执行未知命令。

## 构建

```sh
./console-app/build.sh
# console-app/dist/dsh-console-0.1.0.apk
```

## 安装

模块构建时如果 `console-app/dist/dsh-console-0.1.0.apk` 存在，会把 APK 放进模块根
`console.apk`；`customize.sh` 会尝试 `pm install -r`。如果没有自动装上：

```sh
adb install -r console-app/dist/dsh-console-0.1.0.apk
# 或从模块目录安装
adb shell su -c 'pm install -r /data/adb/modules/dsh_chroot/console.apk'
```

首次点 Start/Refresh 时系统/KSU 会弹 root 授权；这是前端读 URL 和调 `dsh-ctl`
所必需的。不要给其他 App root。

> 这是可见 App，不是隐藏性组件；不需要前端时可以不安装/不授权。
