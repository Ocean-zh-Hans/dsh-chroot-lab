# Chroot Console（可选 Android 控制台）

它是 chroot 的低频控制台；DSH Web 只是其中一个入口。控制台不是隐藏性组件，不需要时可以不安装。

界面按"设备 / 系统 / 软件 / 设置"四层组织（设计见 `docs/console-design.md`）：系统层管起停、物资、地盘、体检，软件层管终端、DSH 本体与依赖、插件、服务；分层判据是"它坏的时候整机起不来→系统层，chroot 停止时仍需可用→系统层"。控制台按钮、状态和提示使用中文，命令输出保留原文以便排错，URL 默认完整显示（它只是本机回环地址+token，不属于敏感信息），需要时可加 `--redact`；打开 DSH 页面仍使用内部完整地址。主界面使用 Kotlin `ComponentActivity` + Compose，WebActivity 用 Compose `AndroidView` 承载受限 WebView，旧 Java Views 已移除。控制台和 WebView 使用 `safeDrawingPadding` 处理系统栏，固定为竖屏，不修改系统自动旋转设置。结束 Console 不会停止 holder 或 chroot。状态地址优先读取 `status --json`，备用文件读取兼容 `/data/adb/chroot/state/runtime/url` 与旧 `/data/adb/chroot/state/guest/url`。

## 功能

- 系统页：状态卡（启停 + loop/pid/运行时长一行）、最多两条建议、快照（最近 3 个 + 全部/新建/恢复）、运行时事实四格（镜像 / loop / rootfs 挂载 / 看门狗，不一致项转橙）、诊断（体检 + 复制纯文本诊断包）、最近日志入口。
- 软件页：依赖条（下层是否就绪）+ 终端、打开 DSH、DSH 版本、依赖版本、插件、服务与日志入口。终端、版本升降级、依赖改版本、插件管理尚未实现，点开会明确提示"还没实现"，不假装可用。
- 设备页：机型/Android/API/内核/verifyboot（只读 getprop + uname）、空间、路径（点按复制）、模块事实。
- 设置页：开机自启、新会话逐项确认、（只读）运行时能力、复制诊断包、关于。
- 日志、快照创建/列表/恢复。
- DSH Web：严格解析 URI，只允许 `127.0.0.1` 或 `localhost` 的 HTTP loopback 地址。可打开内置 WebView，也可用“外部浏览器”通过 `ACTION_VIEW` 打开；内置页主框架加载失败或渲染进程退出时显示浏览器回退，不把白屏当作加载成功。
- 设置：autostart、critical path protection、guard、private namespace 内的 System-RW。

所有控制动作都通过设备自身的 `su`/KSU 授权，执行固定 `dsh-ctl` 命令，不接收任意 shell。命令的退出码和失败原因会显示；不会仅凭有输出就宣称成功。状态刷新超时为 90 秒，生命周期命令为 180 秒；超时返回 124 并提示后台操作可能仍在进行，不能把超时当成停止成功。WebView 禁止外部导航和 mixed content。

guard 的 `approval-free`/`approval-guard` 是新会话的权限预设，重启 DSH/chroot 后生效；已有会话使用 `/permission` 切换。hooks 不是 root 安全边界。

## 安装方式（必须显式选择）

默认模块 zip **不包含**控制台，也不包含 `system/priv-app` 或其他系统 overlay；`customize.sh` 和 `service.sh` 不自动安装 App。

```sh
adb install -r console-app/dist/chroot-console-0.2.0.apk
```

若需要随模块携带安装包，可使用：

```sh
./build/build-module.sh --with-console
```

这只在模块根放置 `console.apk`，不会自动安装。可在备用机上手动执行 `pm install -r /data/adb/modules/dsh_chroot/console.apk`。

安装后还需在设备实际使用的 root 授权软件中主动允许 Chroot Console，再彻底结束并重新打开 App。本备用机使用 **SUList（root 管理器）**，不以面具的应用列表为准；仅切换后台可能仍沿用未授权的旧进程。不要因此修改全局“默认拒绝”策略，也不需要给 DSH Agent 授予 root。

## 构建

```sh
./console-app/build.sh
# 产物：console-app/dist/chroot-console-0.2.0.apk
```

使用根 Gradle wrapper、Android SDK 和 JDK 17；固定 AGP/Kotlin/Compose 版本，`console-app/gradle.lockfile` 锁定依赖，`gradle/verification-metadata.xml` 校验依赖 SHA256。APK 验证 debug 签名和 zipalign，debug 签名不是发布签名。旧 Java 实现的真机结果见 `docs/verification.md`；Compose 版本的构建与设备结果单独记录，不混用旧截图证明新页面。

> 这是可见 App，需要用户授予 root；不是隐藏性组件。不需要时可以不安装/不授权。
