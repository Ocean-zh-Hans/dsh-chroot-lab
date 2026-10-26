# 规则层与可逆性设计（草案 v1）

> **2026-10-26 评审后注**：本草案的“rootfs 用 ext4 镜像 / 数据每日快照”部分已被 `05-design-v1.md` 的“目录型 rootfs + tar 冷快照”取代；规则层/审计层思路仍有效，但“块设备只读 bind”不是安全边界（root 可通过 `/proc/1/root` 绕过）。实施以 `08-design-review.md` 的 P0/P1 与 `10-design-v1.3.md` §9 的分档规则为准。

## 前提
全能 AI + root = 长期可用的唯一关键不是"拦住它"，而是**让它做的事可逆**。
规则仍然必要（阻止愚蠢/被注入的命令），但规则不能是唯一防线。

## 第一层：规则（你立规矩，机器可读）

建议落成两个文件，agent/DSH 启动时加载：

```
/data/adb/dsh/policy/deny.txt   # 命中即拒绝
/data/adb/dsh/policy/ask.txt    # 命中需人工确认
```

**建议红线条目（deny，示例）**
- 块设备写入：`mkfs`、`dd\s+.*of=/dev/block`、`fdisk`、`parted`、`flash_erase`、`fastboot flash`
- 系统分区写入：`/system`、`/vendor`、`/product`、`/apex`、`/boot`、`/init_boot`（除非进入明确的维护模式）
- 递归删除命中关键路径：`/data/adb/modules`、`/data/data`、`/data/media/0/DCIM`、`/sdcard/Android`、`/`、`/storage`
- 未 umount 就删 chroot 目录内容
- `chmod -R` / `chown -R` 作用于 `/`
- 持久化关闭 SELinux、卸载/禁用 KernelSU 模块
- 覆盖式重定向到块设备/字符设备节点

**建议必问条目（ask，示例）**
- `rm -rf`、`git clean -fdx`、`apt purge`、`pm uninstall`、`settings put`、任何写 `/data/adb` 的操作

## 第二层：结构性可逆（技术保底，不依赖模型记性）

1. **rootfs 用 ext4 镜像**：升级/改造前自动快照（`cp --reflink=auto` 或 tar），保留 N 份，`dsh-ctl snapshot list|restore` 一键回滚。
2. **数据每日快照**：`/data/adb/dsh/{home,opt,state}` → `/data/adb/dsh/snapshots/<date>.tar.zst`，保留 7 份。
3. **回收站**：提供 `dsh-rm`（移动进 `.trash/<date>/` 而不是真删）；高危路径即使 deny 未命中也会被它兜住。
4. **卸载强制协议**：脚本先扫 `/proc/mounts` 找出所有指向 rootfs 的挂载 → umount → 校验 → 才允许删目录（lhroot 的 README 用血泪写过这条）。
5. **块设备只读护栏**：可选把 `/dev/block` 以只读方式 bind 进 chroot，或干脆不 bind —— 让"误刷分区"变得需要额外一步。

## 第三层：审计（事后能查）

- 每条 agent 执行的命令：时间、cwd、完整 argv、退出码 → `/data/adb/dsh/logs/audit/YYYY-MM-DD.log`
- 危险规则命中单独记录，便于复盘是"规则太严"还是"模型跑偏"
- 日志本身只追加，不参与自动清理（除非你手动）

## 不过度设计
- 不做 userns/pidns（本机不支持，且与"全能"目标相反）
- 不做文件系统级沙箱
- 规则清单保持短小可读：条目一多，模型会开始"花式绕过"，人也懒得维护
