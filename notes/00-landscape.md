# 全景：Android 上跑 DSH / Linux 的四种运行模型

核验日期：2026-10-26（本机实测 + 源码快照）

## 一句话结论

不是"一个赛道"，而是**四个互不兼容的运行模型**。选型必须先选模型，再谈实现：

| 模型 | 需要 root | 代表项目 | 核心能力 | 代价 |
|---|---|---|---|---|
| **A. 真 chroot（KSU/Magisk 模块）** | 是 | dsh-alpine-ksu、DSH-Chroot、chroot-distro、lhroot | 原生 syscall、无 ptrace、可开机自启 | root 域，非沙箱；需自维护；Android 适配 |
| **B. proot（APK 内嵌发行版）** | 否 | DSHA、xliaoy fork、masgzy、kai6666666、Soodok | 免 root、开箱即用、移动 UI | ptrace 开销、静态 ELF 限制、补丁链 |
| **C. Termux 原生** | 否 | ErEbusE/dsh-termux | 官方二进制、无翻译层、最接近服务器 | 依赖 Termux、无开机自启、需补丁 |
| **D. AVF/pKVM 虚拟机** | 否（需设备支持） | 目前只有指南：run-linux-on-android-guide、gunyah-on-sd-guide | 真内核、真隔离、真 chroot | Android 15/16 入口未普及、生态早期 |

## 逐项目速览（已 clone，markers 来自源码 grep）

| 项目 | 模型 | 许可/活跃度 | 关键 markers | 值得学什么 |
|---|---|---|---|---|
| WenlaoJP/dsh-alpine-ksu | A | MIT；2★；2026-10-20；**有 Release 257MB** | chroot=10 setpriv=5 unshare=1 rootfs=15 | 最完整的"KSU 模块 + 真 chroot + UID1 + 开机自启 + watchdog"骨架；**bind /data/media/0 绕 FUSE** 是关键技巧 |
| SunsetRNE/DSH-Chroot | A | MIT；0★；2026-10-19；**无 Release** | chroot=27 unshare=1 node-pty=12 | 设计纪律：威胁模型、ADR、版本闸门、pitfalls/recovery 文档（decisions.md 1416 行） |
| sabamdarif/chroot-distro | A 工具 | GPL-3.0；163★；2026-10-24 | chroot=373 unshare=17 rootfs=141 | rootfs 安装/挂载/备份/diff 的成熟实现（Python，`helpers/namespace.py`、`syscalls/mount.py`） |
| Magisk-Modules-Alt-Repo/chroot-distro | A 工具 | GPL-3.0；504★；2025-12-18 | chroot=11 rootfs=5 | Magisk 封装形态；版本落后 pip 版 |
| Magisk-Modules-Alt-Repo/lhroot | A 工具 | GPL-3.0；57★；**2024-04 停更** | chroot=6 rootfs=3 | ext4 镜像方案 `make_image`/`mount_image`；卸载血泪警告 |
| DSH-APP/DSHA | B | MIT；2026-10-25 | proot=421 rootfs=328 termux=105 node-pty=10 avf=14 | proot 工程化天花板：双引擎、备份、自检、应急、移动 UI、设备桥 |
| xliaoy/DeepSeekHarness | B | 2026-10-23 | proot=142 rootfs=131 | DSHA 二开：内置插件全家桶、UI 改造 |
| kai6666666/deepseek-harness | B | MIT；2026-10-26 | proot=37 unshare=10 rootfs=38 avf=31 node-pty=17 | PRoot Ubuntu + node + dsh + loopback MCP bridge 的另一种组织方式 |
| Soodok/Deepseek-Harness-Local-Android | B | MIT；343★；2026-10-26 | proot=10 termux=54 node-pty=16 | 高人气免 root 方案，看它怎么处理原生模块与运行时 |
| masgzy/DeepSeek-Harness-Mobile | B | GPL-3.0；2026-10-25 | proot=31 rootfs=29 termux=38 | 中文优先、完整 Linux 用户态的组织 |
| cmyfqwq/dsh-mobile-apk-legacy | B/C 混合 | 2026-09-09 | termux=77 rootfs=3 node-pty=5 | **APK 内嵌 Termux 运行时快照**（解压即跑）——介于 B 和 C 之间 |
| ErEbusE/dsh-termux | C | MIT；2026-09-10 | termux=40 | 你现在这套的源码：官方 linux-arm64 Node + 9 个 Android 补丁 |
| lfdevs/run-linux-on-android-guide | D | 2026-09-15 | chroot=6 avf=6 | AVF 上跑 Linux 的路线与坑 |
| polygraphene/gunyah-on-sd-guide | D | 2026-06-20 | avf=2 | 高通设备用 crosvm/Gunyah 跑 VM |

## 生态全景（搜索所得，多为一句话参考，不必深挖）

- 客户端/遥控类（不是我们的问题域）：xisnn/dsh-outpost、KyoMio/dsh-zen-remote、MaoYouShiJie/dsh-pocket-android、MengTan100/pocketpilot、yiloyasa/pocketagent、kaelorvyn/dsh-mobile、zylzyqzz/dsh-mobile-pwa、fxhack/DshPocketApp、FIOIU8/DSH-Pocket、Zxin-Pro/dsh-android
- 桌面端：ningbainb/deepseek-harness-desktop、liguobao/ds-harness-remote
- 其他手机端尝试：zmm863-commits/paopaocat-deepseek-harness-mobile、Meow1s/DeepSeek-Harness-For-Android、bufeibufei/dsh-mobile、ZSeven-W/rish-app

## 初步判断

1. **模型 A 是唯一能给"真 chroot + 原生 Go 编译 + 开机自启"的**，但成熟度最低（两个 DSH 相关项目都是 2026-10 新建、0–2★）。
2. **dsh-alpine-ksu 是唯一可装的 A 类 DSH 方案**，它的 4 个脚本值得逐行读。
3. **chroot-distro（sabamdarif）是我们最该复用的轮子**，不要自己写 rootfs 安装/挂载/备份。
4. **DSH-Chroot 的价值在文档不在代码**（它自己也很诚实地说 chroot 不是沙箱）。
5. **AVF 是未来方向**，你 Android 15 且短期不升级 → 现在不用赌它，但设计上别把路堵死。
