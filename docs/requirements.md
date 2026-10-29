# 需求定义（当前，2026-10-26 锁定）

## 1. 定位

在**一台** Android 手机上，为 DSH 提供：

- **真 chroot** 的 Ubuntu 24.04 环境（非 proot、非 VM、非第二台设备）；
- Linux 侧的完整能力：root、apt、Go、Node、Python、编译、挂载、调试；
- Android 侧的完整控制：`/data`、`/mnt`、`/storage`、`/sdcard`、`/dev`、`/sys`、Android 分区与 `am/pm/input/...`；
- 应用层隐藏性：游戏/银行/Play Integrity/普通 root 检测 App **不因为本模块的可见痕迹**发现 root；
- 可逆性：rootfs/state 快照、last-good、卸载清理、显式 purge。

用户原话约束：

> 不考虑权限收束问题，不考虑空间占用问题，只做完美的 chroot，完美的手机控制。

> 不降低本机 root 隐藏性的前提，最大幅度提供便利。

> 游戏、银行等应用不会因为本模块抓到把柄。

> 我不在乎 chroot 内藏什么，全开都行，我只在乎应用会不会通过东西发现我隐藏。

## 2. 目标

| 编号 | 目标 | 验收口径 |
|---|---|---|
| G1 | 真 chroot | 非 proot；`uname -r` 与宿主一致；Go 产物直接执行 |
| G2 | 完整 Ubuntu | Ubuntu 24.04 glibc、apt、Node 24、DSH、s6、常用工具链 |
| G3 | 生命周期 | 开机非阻塞自启；s6 监督；start/stop/status/diag；镜像损坏时可用已验证的 last-good 恢复，其他失败进入人工恢复而非盲目覆盖 |
| G4 | 完整 Android 视图 | full `/data`、`/mnt`、`/data_mirror`、`/storage`、`/sdcard`、`/dev`、`/sys`、Android 分区；`/sys/fs/selinux` 用 private tmpfs 遮罩；完整控制命令 |
| G5 | 应用层隐藏性 | 不改全局 prop、不默认改全局 SELinux、不把 chroot mount 暴露到 App 的 mountinfo；KSU 隐藏特性保持 |
| G6 | 可逆性 | 镜像/state 冷快照；last-good；boot counter；`purge` 删除镜像/快照/loop 校验 |
| G7 | 可维护 | 文档单一事实源；代码可静态检查；P1 不碰系统；P2 只在备用机/VM 验证 |

## 3. 非目标

- 不做安全沙箱、多租户、防恶意 root、防物理接触。
- 不做 least privilege、UID 降权、最小 `/dev`、只挂 `/sdcard/DSH` 的受限模式。
- 不做 proot、AVF/VM、Docker/DockRoot（至少不是当前主线）。
- 不追求多机型、公开发布、兼容所有 Android 版本。
- 不把“任何未知检测都绝对发现不了”作为可证明承诺；只按检测矩阵验收。
- 不为了隐藏砍掉 full chroot；也不把 chroot 内部当作 App 可见面。

## 4. 硬不变量（不可被实现“优化”掉）

1. **全局 SELinux 保持 Enforcing**；不默认 `setenforce 0`；`--permissive` 只允许显式 break-glass，且必须记录并恢复。
2. **不改全局 prop**：不用 `resetprop` 改 `ro.debuggable`、`ro.secure`、`init.svc.*`、`persist.*`。
3. **chroot 的所有 mount 只在 private mount namespace**；不做全局 bind/overlay；每个 rbind 后 `make-rprivate`。
4. **不对 `/sys/fs/selinux` 做 bind/remount**；默认使用 private namespace 内 tmpfs 遮罩，选择 `DSH_SELINUXFS_MODE=ro` 时只允许在该 namespace 新建只读 selinuxfs。
5. **保持 SukiSU 隐藏特性**：`selinux_hide`、`mount_hide`、`avc_spoof`、`kernel_umount` 不关闭。
6. **DSH/ttyd 只监听 127.0.0.1**；不监听 `0.0.0.0`。
7. **`/sdcard` 不放 `magisk`/`kernelsu`/`su` 等明显 root 标记**；`ubuntu-rootfs`/`DSH` 名字可以接受。
8. **不把 `u:r:ksu:s0` permissive 当作隐藏手段**；它是能力/安全边界事实，不是 App 隐藏保证。
9. **认可能力与隐藏性的边界**：`/dev/block` 默认遮罩只减少粗心路径误写，`/proc/1/root`、`mknod` 和直接 syscall 仍可绕过；风险提醒和审计不是安全边界，生命周期保护必须保留。
10. **P1 不执行任何宿主级 mount/loop/overlay/SELinux 操作**；P2 起只在备用机/VM 或显式 private namespace + trap 中验证。

## 5. “完美”的工程定义

“完美”不是“理论上不可检测”，而是：

- 功能验收表全过；
- 已知检测矩阵（应用层）全过；
- 所有未验证项在 `verification.md` 里明确标“未验证”；
- 出错可回滚；
- 不把“没测过”写成“已支持”。

当前状态：**备用机正式模块、128GB chroot/DSH、20 次启停、快照/回滚、真实开机和固定 App 接口已实测通过；这不等于“完美”目标已达成。主力机 `0.2.1` 已完成安装、真实手动启停和健康检查，但随后运行中直接重启暴露了旧 holder PID 记录导致的 `identity mismatch` / `recovery-required`，所以运行中重启自启尚未通过；`0.2.2` 修复候选正在验证。chroot 历史数据和恢复备份保留，未改全局 SELinux/prop 或其他授权。真实模型会话、真实验证码、断电恢复、真实 purge/uninstall、银行/游戏/Play Integrity 和主力机完整兼容性仍未验证。**
