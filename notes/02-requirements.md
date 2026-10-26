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
- H1 真 chroot（非 proot），Go 可编译且产物可直接执行
- H2 工作区在共享存储可见（手机文件管理器可改），产物仍可执行 → raw `/data/media/0` 绑定
- H3 开机自启，且不阻塞开机（service.sh 立刻返回 + 后台 bootstrap）
- H4 可启停/重启 + 状态与日志（坏了自己说清哪坏了）
- H5 数据可备份/可回滚；停用/卸载前 stop + 全机残留校验；模块卸载保留 `/data/adb/chroot`，`dsh-ctl purge` 显式全删
- H6 **能力优先（分档）**：root 域；daily 默认挂 raw `/sdcard`、不挂 `/data`；lab 显式挂 `/data`/`/mnt`/`/storage`/`/dev`，Android 分区只读；两档都能 apt/编译/挂载/改文件
- H7 **可逆性（分资产）**：rootfs + state 冷快照；`/sdcard/DSH` 回收站 + restic；`/data`/`/system` 不做承诺；卸载前 stop/umount 校验
- H8 **规则可注入**：按 profile 的 deny/ask 清单 + 服务级自检；agent 启动即加载，自检失败不启动 DSH
- H9 **分档运行**：默认 daily；lab 全 Android 视图需显式开启；rescue 用于修复；重启回 daily

## 软需求
- S1 把 DSH 搬进 chroot 并开机自启（摆脱 Termux 常驻）
- S2 保留 DSHA 的移动 UI / 设备控制
- S3 发行版：Ubuntu 24.04 base（glibc，省掉 musl 补丁链；Debian 12 等价备选）
- S4 rootfs：daily 目录 rootfs；lab ext4 镜像（放 `/data/media/0`，挂 `/run/dsh-rootfs`）
- S5 本机专用（不追求多机型/发布）
- S6 可选 Web 终端（ttyd）

## 明确不要
- 不再出现"chroot 是沙箱"这种叙事
- 不为了隔离牺牲能力
- 不在验证完成前删除现有 Termux / DSHA 环境

## 待拍板
- [x] Q1 进程身份：chroot 内 root（v1.3 ADR-001）；UID1 留作未来 v2
- [x] Q2 快照：rootfs 1 golden + 3 recent + 1 pre-change；state 7 份
- [x] Q3 回收站：`dsh-rm` 进 `.trash`；`rm -rf` 走 ask；`/sdcard/DSH` 重点保护
- [x] Q4 规则：已起草 daily/lab 两套（见 10-design-v1.3.md §9）
