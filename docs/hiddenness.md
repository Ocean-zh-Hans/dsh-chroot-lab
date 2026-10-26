# 应用层隐藏性模型

- 日期：2026-10-26
- 定义：**游戏、银行、Play Integrity、普通 root 检测 App 不会因为本模块的可见痕迹发现 root/异常**。
- 不是：防恶意 root、防物理接触、防 chroot 内部 root、把 chroot 变成安全沙箱。
- 用户明确：**不在乎 chroot 里藏什么，全开都行**；`/sdcard` 里 `ubuntu-rootfs` 这种名字本身不是封号理由，不为名字过度设计。

## 1. App 实际能看到什么

| 信号 | 普通 App 能否看到 | 本模块怎么做 |
|---|---|---|
| 全局 `ro.debuggable`/`ro.secure`/`init.svc.*`/`persist.*` | 能（`getprop`） | **绝不改**；不 resetprop |
| 全局 SELinux 是否 permissive | 通常不能直接读 `/sys/fs/selinux/enforce`（untrusted_app 有 neverallow）；特权/root 检测可能能 | 保持 Enforcing；不默认 `setenforce 0`；不靠 permissive |
| 应用自己的 `/proc/self/mountinfo` | 能 | 所有 chroot mount 在 private mount ns；App 看不到 |
| `/sdcard` 目录名 | 能（有存储权限） | `ubuntu-rootfs`/`DSH` 可接受；不放 `magisk`/`kernelsu`/`su` 标记 |
| `/sdcard/ubuntu-rootfs/rootfs.img` | 能（若目录可进） | 目录 0700 + `.nomedia`；低强度信号，不强制改名 |
| loop 设备 | 普通 App 多半读不到 `/sys/block`/`/proc/partitions`；特权检测可读 | 低强度信号；不把它当封号主因 |
| `/data/adb`、`/data/adb/modules`、`/data/adb/chroot` | 普通 App 不可读（0700 root） | 保持 0700；不需要为 App 改名 |
| `/data/data`、`/data/system` | 普通 App 不可读；chroot 内 root 可见 | 对 App 隐藏无影响；对完整性另说 |
| `u:r:ksu:s0` 域 | App 看不到别的进程域 | 不是 App 隐藏问题；不写自定义域 |
| `/proc/1/root` | App 不可读 | 安全边界问题，不是 App 检测面 |
| `/dev/block` | App 不可读；chroot 内 root 可见 | 对 App 隐藏无影响；3A 保留 |
| root 进程名（s6/node/ttyd） | 受 `hidepid=invisible` 影响 | doctor 检查；本机已是 invisible |
| 监听端口（DSH/ttyd 127.0.0.1） | App 可扫 localhost | 不是 root 信号；保持 loopback |
| KSU 管理器/模块包名 | App 可通过 PackageManager 看到管理器包；模块目录不可读 | 保持 `selinux_hide`/`mount_hide`/`avc_spoof`/`kernel_umount` |

## 2. 必须守住的“应用可见”不变量

1. **全局 SELinux 保持 Enforcing**：不默认 `setenforce 0`；`--permissive` 仅显式 break-glass。
2. **不改全局 prop**：不 `resetprop` 改 `ro.*`/`init.svc.*`/`persist.*`。
3. **所有 chroot mount 在 private mount namespace**：`unshare --mount --propagation private`；每个 rbind 后 `make-rprivate`；绝不全局 mount/overlay。
4. **`/sys/fs/selinux` 在 chroot 内遮罩**：private ns tmpfs；不 bind/remount 宿主 selinuxfs。这样 `/sys` 其余 rw（2B），但 chroot 不能直接写全局 SELinux 开关。
5. **KSU 隐藏特性保持启用**：`selinux_hide`/`mount_hide`/`avc_spoof`/`kernel_umount`。
6. **服务只监听 loopback**：DSH/ttyd 绑定 127.0.0.1。
7. **宿主 `/proc` hidepid=invisible**：doctor 检查；不同 ROM 可能不同。
8. **`/sdcard` 不放明显 root 标记**：不叫 `magisk`/`kernelsu`/`su`；`ubuntu-rootfs`/`DSH` 可以。
9. **不改 `/system` 全局视图**：`system-rw` 默认 off；overlay 只在 private ns。

## 3. 明确不要求

- 不要求 directory rootfs；ext4 镜像可以。
- 不要求不挂 `/data`/`/dev`/`/sys`/`/system`；只要在 private ns。
- 不要求自定义 SELinux 域；`u:r:ksu` permissive 不是 App 可见信号。
- 不要求隐藏 `/proc/1/root`、`/dev/block`、`/data/adb` 的内部内容；App 看不到。
- 不要求隐藏 `ubuntu-rootfs` 这个名字或 loop 设备（低强度信号）。
- 不要求把 `/sdcard` 工作区收窄；full 1A 保留完整存储。

## 4. 风险与边界

- **2B `/sys` rw**：除 selinuxfs 遮罩外，chroot 内 root 仍可改 cgroup/kernel tunables；这是能力选择，不是 App 可见面。
- **3A `/dev` rbind**：chroot 内 root 可写块设备；不是 App 可见面，但可破坏系统。
- **5A 全部在 chroot**：chroot 内 root 有 `/proc/1/root` 逃逸；任何命令级 `hardguard` 都可绕过。
- **`u:r:ksu:s0`**：permissive allow-all；全局 Enforcing 不约束它；不能拿它当安全/隐藏保证。
- **App 检测**：银行/游戏可能使用 Play Integrity、KeyStore、包扫描、mount 扫描、进程扫描、端口扫描；本模块只保证不改全局可见信号，不保证未知检测永远通过。

## 5. 结论

full 设计可以保留，前提是：

- mount 全部 private；
- 全局 prop/SELinux 不动；
- KSU 隐藏特性开着；
- `/sdcard` 不出现明显 root 标记；
- 不把“名字/loop/内部路径”当硬隐藏指标。

真正要防的是：**全局 permissive、全局 prop 改动、全局 mount/overlay、可被 App 读到的包/进程/端口**。这些才是游戏/银行会看的。
