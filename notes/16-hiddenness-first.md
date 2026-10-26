# 应用层隐藏性模型（pragmatic）

- 日期：2026-10-26
- 用户定义：**游戏、银行、Play Integrity、普通 root 检测 App 不会因为本模块发现 root/异常**；不是防住 chroot 内 root 的安全边界。
- 用户补充：**不在乎 chroot 里藏什么，全开都行**；`/sdcard` 里一个自定义名字（如 `.dsh`）本身不应被当成封号理由，不需要为了“名字不正常”过度设计。
- 结论：**full v1.4 可以保留**，只要满足“应用可见信号”不泄漏；不需要为了隐藏把 full `/data`/镜像/Android 视图砍掉。真正要管的是：全局 prop、全局 SELinux、应用自己的 mountinfo、进程/端口、KSU/模块痕迹，而不是 `.dsh` 这个名字。

---

## 1. 应用实际能看到什么

| 信号 | 普通 App 能否看到 | 本模块该怎么办 |
|---|---|---|
| 全局 `ro.debuggable`/`ro.secure`/`init.svc.*`/`persist.*` | **能**（`getprop`） | **绝不改**；不 resetprop |
| 全局 SELinux 是否 permissive | 通常不能直接读 `/sys/fs/selinux/enforce`（untrusted_app 有 neverallow）；特权/root 检测可能能 | 保持全局 Enforcing；**不默认 setenforce 0**；不靠 permissive |
| 应用自己的 `/proc/self/mountinfo` | **能** | chroot 所有 mount 必须在 private mount ns；App 看不到；不要全局 bind/overlay |
| `/sdcard` 下的目录名 | 能（有存储权限） | `.dsh`/`DSH` 名字本身不是标准 root 信号；**不为名字过度设计**；不把 `su`/`magisk`/`kernelsu` 之类放进 `/sdcard` |
| `/sdcard/.dsh/rootfs.img` 文件 | 能（如果目录可进） | 不是标准检测信号；如果要干净可改名/放 `/data/adb`，但**不是隐藏性硬要求** |
| 多出来的 loop 设备 | 普通 App 多半读不到 `/sys/block`/`/proc/partitions`；root/特权检测可读到 | 记住它是“低强度信号”，不是封号主因；介意的可选 directory rootfs，不强制 |
| `/data/adb`、`/data/adb/modules`、`/data/adb/chroot` | 普通 App 不可读（0700 root） | 不需要为“App 看到”改名/搬家；保持 0700 |
| `/data/data`、`/data/system` | 普通 App 不可读；chroot 内 root 可见 | 对 App 隐藏无影响；对 chroot 完整性有影响（另说） |
| `u:r:ksu:s0` 域 | App 看不到别的进程域 | 不是 App 隐藏问题；不需要为此写自定义 SELinux 域 |
| `/proc/1/root` | App 不可读 | 安全边界问题，不是 App 检测面 |
| `/dev/block` | App 不可读；chroot 内 root 可见 | 对 App 隐藏无影响；不需要为隐藏砍 `/dev` |
| root 进程名（s6/node/ttyd） | 受 `hidepid=invisible` 影响；不同 ROM 不同 | doctor 检查宿主 `/proc` 的 hidepid；本机已是 invisible |
| 监听端口（DSH/ttyd 127.0.0.1） | App 可扫 localhost | 不是 root 信号；保持 loopback；可用 Unix socket 进一步减少 |
| KSU 管理器/模块包名 | App 可通过 PackageManager 看到管理器包；模块目录不可读 | 保留 `selinux_hide`/`mount_hide`/`avc_spoof`/`kernel_umount`；不额外暴露模块名 |

**一句话**：App 看不到 chroot 的内部；它看的是**全局 prop、全局 SELinux、自己的 mountinfo、包/进程/端口、少数 sdcard 名字**。`.dsh` 这个名字不是标准检测项。

---

## 2. 必须守住的“应用可见”不变量

1. **全局 SELinux 保持 Enforcing**：不默认 `setenforce 0`；`--permissive` 仅显式 break-glass；permissive 不是隐藏手段。
2. **不改全局 prop**：不 `resetprop` 改 `ro.*`/`init.svc.*`/`persist.*`；不伪造 root 状态。
3. **所有 chroot mount 在 private mount namespace**：`unshare --mount --propagation private`；每个 rbind 后 `make-rprivate`；**绝不全局 mount/overlay**。App 的 `/proc/self/mountinfo` 看不到 chroot。
4. **不改 `/system` 全局视图**：`system-rw` overlay 只在 private ns；默认 off；不做全局 overlay。
5. **KSU 隐藏特性保持启用**：`selinux_hide`/`mount_hide`/`avc_spoof`/`kernel_umount`；不关。
6. **服务只监听 loopback**：DSH/ttyd 绑定 127.0.0.1；不 `0.0.0.0`；端口不是 root 信号，但不做广告。
7. **进程可见性**：doctor 检查宿主 `/proc` 的 `hidepid=invisible`；本机已满足；不同 ROM 可能不同。
8. **`/sdcard` 不放明显 root 标记**：不叫 `magisk`/`kernelsu`/`su`；`.dsh`/`DSH` 可以接受，不为名字过度设计。

**不要求**：
- 不要求 directory rootfs；full ext4 镜像可以。
- 不要求不挂 `/data`/`/dev`/`/sys`/`/system`；只要在 private ns。
- 不要求自定义 SELinux 域；`u:r:ksu` permissive 不是 App 可见信号。
- 不要求隐藏 `/proc/1/root`、`/dev/block`、`/data/adb` 的内部内容；App 看不到。

---

## 3. 因此 full v1.4 怎么保留

- **full `/data`、`/mnt`、`/data_mirror`、`/storage`、`/sdcard`、`/dev`、`/sys`、Android 分区**：都可以保留；只要在 **private mount namespace** 里，App 看不到这些 mount。
- **128GB ext4 镜像 / `.dsh`**：可以保留；`.dsh` 名字、rootfs.img 不是标准检测项；不为了“名字”强制改 directory rootfs。若要更干净，可改名/放 `/data/adb`（但 loop 只能 `/data/media/0`）。
- **root-free**：保留；不弹批准。
- **`u:r:ksu` permissive**：接受；App 看不到域；但**全局 Enforcing + 不改 prop** 仍要守。
- **`/sys` rw、`/dev` rbind、`/data` full**：App 看不到；可以保留；但它们是安全边界问题，不是 App 隐藏问题。
- **`system-rw` overlay**：App 看不到 private ns；但 PIF Detector 类工具可能看自己的 mountinfo（不是 chroot 的），所以 private 即可；默认仍建议 off。
- **`.dsh` 名字**：用户明确说不在意为名字封号；不把“隐藏 .dsh”当硬需求。

**唯一必须改的**：v1.4 里 `/data` rbind 的 **self-reference 可靠性**（镜像可被 chroot root 写坏）——这是稳定性问题，不是 App 隐藏问题。是否遮内部路径由你决定；不遮也能跑，只是 AI 可能毁自己的 rootfs。

---

## 4. 如果“不想太谨慎”，优先级排序

**必做（会被 App 抓）**
1. 全局 SELinux Enforcing；不 `setenforce 0`。
2. 不改全局 prop。
3. 所有 chroot mount 在 private ns；不全局 mount/overlay。
4. 保持 `selinux_hide`/`mount_hide`/`avc_spoof`/`kernel_umount`。
5. DSH/ttyd 只 loopback。
6. 宿主 `/proc` hidepid=invisible（本机已满足）。

**可选（低强度信号，不必过度）**
- `.dsh`/DSH 名字、rootfs.img 文件名。
- loop 设备存在。
- sdcard 工作区 2777。
- ttyd 端口。
- debugfs/tracefs 私有挂载。

**不用管**
- chroot 内 `/data`/`/dev`/`/sys`/`/system` 的内容。
- `/proc/1/root`、`u:r:ksu` 域、自定义 SELinux 域。
- `/data/adb/chroot` 里的控制面（App 不可读）。

---

## 5. 结论

- **full v1.4 可以保留**，只要 mount 全部 private、全局 prop/SELinux 不动、KSU 隐藏特性开着。
- `.dsh`/rootfs.img 名字不是封号理由；不需要因此砍 full `/data` 或换 directory rootfs。
- 真正要防的是：**全局 prop、全局 permissive、全局 mount/overlay、可被 App 读到的包/进程/端口**；这些才是游戏/银行会看的。
- 如果以后要更干净，可选 directory rootfs / 不挂 `/data`；但那是“更谨慎”，不是“必须”。
