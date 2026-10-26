# 我们要什么（需求定义，v2 · 2026-10-26 拍板）

## 定位（已拍板）
- 目标是**全能 AI**：能力优先，不把"受限沙箱"当设计目标。隐私不敏感。
- 安全不靠 OS 隔离，靠两层：
  1. **规则层**：用户立规矩，禁止/追问破坏性指令；
  2. **可逆性层**：快照、回收站、卸载前强制 umount —— 因为规则可能被绕过或被注入内容误导，唯一可靠的兜底是"能回滚"。
- 本机实测 PID ns / user ns 不可用，**不再视为损失**（本来也不打算用在隔离上）。

## 本机内核事实（2026-10-26，root 读 /proc/config.gz）

```
CONFIG_NAMESPACES=y
# CONFIG_USER_NS is not set        ← user ns 内核未编译
# CONFIG_PID_NS is not set         ← PID ns 内核未编译
# CONFIG_SECURITY_LANDLOCK is not set  ← Landlock 未编译
```

- `unshare -U` / `unshare -p --fork` / `unshare -Urm` 全部 EINVAL；`unshare -m` 正常。
- 推论：userns 隔离档在**本机永远不可用**；没有 PID 隔离；**DSH 的文件沙箱在本机永远只能 danger-full-access**。
- 因此隔离上限固定为：**mount ns + chroot + UID/组 + capabilities**；安全靠"规则 + 可逆性"，不靠隔离。
- 这些结论只对本机成立：不同机型的 kernel config 不同，**换机必须重测**（见 notes/analysis/dsh-chroot.md §7）。

## 硬需求
- H0 **不降低本机 root 隐藏性**：SELinux 保持 Enforcing；不改全局 prop；**绝不对 `/sys/fs/selinux` 做 bind/remount,ro**；chroot 挂载只在私有 namespace；保留 SukiSU `su_compat`/`kernel_umount`/`selinux_hide`
- H1 真 chroot（非 proot），Go 可编译且产物可直接执行
- H2 工作区在共享存储可见（手机文件管理器可改），产物仍可执行 → raw `/data/media/0` 完整绑定（full 档）
- H3 开机自启，且不阻塞开机（service.sh 立刻返回 + 后台 bootstrap）
- H4 可启停/重启 + 状态与日志（坏了自己说清哪坏了）
- H5 数据可备份/可回滚；停用/卸载前 stop + 全机残留校验；模块卸载保留 `/data/adb/chroot`，`dsh-ctl purge` 显式全删
- H6 **能力优先（full）**：root 域；完整 `/data`/`/mnt`/`/storage`/`/sdcard`/`/dev`/`/sys`/Android 分区视图；能 apt/编译/挂载/改文件/控制手机；不做 least privilege
- H7 **可逆性（full）**：128GB rootfs 镜像冷复制 + state 快照；`/data`/`/sdcard` 可选 restic 全量；卸载前 stop/umount 校验
- H8 **规则可选**：默认 `approval-free`（不拦、完整控制）；但 `hardguard` 始终拦 SELinux/全局 prop/全局 mount 传播；`guard` 可开更多 deny/ask；审计始终记录
- H9 **单一 full 档**：默认 full；rescue 只用于维修；不再 daily/lab 分档

## 软需求
- S1 把 DSH 搬进 chroot 并开机自启（摆脱 Termux 常驻）
- S2 保留 DSHA 的移动 UI / 设备控制
- S3 发行版：Ubuntu 24.04 base（glibc，省掉 musl 补丁链；Debian 12 等价备选）
- S4 rootfs：128GB ext4 镜像（放 `/data/media/0/DSH-CHROOT`，挂 `/tmp/dsh-rootfs`）
- S5 本机专用（不追求多机型/发布）
- S6 可选 Web 终端（ttyd）

## 明确不要
- 不再出现"chroot 是沙箱"这种叙事
- 不为了隔离牺牲能力
- 不在验证完成前删除现有 Termux / DSHA 环境

## 待拍板
- [x] Q1 进程身份：chroot 内 root；full capabilities；不降 UID
- [x] Q0 root 隐藏性：默认 Enforcing；`--permissive` 仅显式 break-glass
- [x] Q2 快照：128GB 镜像 7 份 + state 7 份（空间不优化）
- [x] Q3 回收站：`dsh-rm` 进 `.trash`；默认 free，`guard` 可开 ask
- [x] Q4 规则：默认 `approval-free`；可选 `guard`（见 11-design-v1.4.md §8）
