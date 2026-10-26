# 已锁定决策（2026-10-26）

- 状态：**用户已拍板，不再作为待选项讨论**。
- 组合：`1A 2B 3A 4B 5A 6B 7A 8A 9A 10A 11A 12A 13A 14B 15A`
- 解释原则：`A/B/C/D` 是当次选项编号，不等于排序；以下写的是**最终采用值**，不是推荐值。
- 旧决策单里的“推荐组合”“更谨慎组合”均为历史材料，已归档；不得覆盖本文件。

## 1. 逐项决策

| # | 主题 | 锁定值 | 最终含义 |
|---|---|---|---|
| 1 | `/data` 内部路径 | **1A** | full `/data` rbind rw；对**控制面/镜像**做 best-effort 私有 namespace 遮罩：`/data/adb/chroot`、`/data/adb/modules`、`/data/adb/ksu`、`/data/media/0/ubuntu-rootfs` 及已知 storage 别名。遮罩是完整性和防手滑，不是安全边界；`/proc/1/root` 仍可绕过，接受。 |
| 2 | `/sys` | **2B** | `/sys` 整体 rw；**唯一例外**：`/sys/fs/selinux` 在 chroot 内用 private ns tmpfs 遮罩，禁止 bind/remount 宿主 selinuxfs。理由：防止 chroot 内误写全局 SELinux；其余 sysfs 保持完整。 |
| 3 | `/dev` | **3A** | 完整 rbind `/dev`；`/dev/block`、`/dev/loop*`、binder、ashmem、USB 全见。接受块设备写风险。 |
| 4 | UI/手机控制 | **4B** | 宿主 thin agent（accessibility/Shizuku/ADB）补 UI 树、通知、剪贴板、包管理；不是隐藏必需，是“完整手机控制”的补全。 |
| 5 | 架构 | **5A** | 全部在 chroot：DSH、apt、Go、Android CLI、full `/data`/`/dev`/`/sys`；宿主 agent 是可选补全，不是替代。 |
| 6 | 高危区 | **6B** | `/metadata`、`/mnt/vendor/persist`、`/mnt/vendor/qmcs` 默认递归只读；可用显式 `--protect-critical=off` 在维护窗口关闭，但默认必须 ro。 |
| 7 | `system-rw` | **7A** | 默认 off；`/system` 等保持硬件/顶层只读；需要时显式 `dsh-ctl system-rw on`（private ns overlay，只作实验）。 |
| 8 | rootfs 容量 | **8A** | 128GB ext4 镜像起步；实际 rootfs 12–16GB；可 `resize2fs` 扩到 256GB。128/256 是存储容量，不是 RAM。 |
| 9 | 快照范围 | **9A** | rootfs 镜像 + state + `/sdcard/DSH` restic；`/data`/`/sdcard` 其余明确不在承诺范围。 |
| 10 | 测试环境 | **10A** | 备用机/VM 先做 mount/chroot/stop/loop 测试；主力机不做首次 apply。 |
| 11 | 规则默认 | **11A** | `approval-free`（`danger-full-access` + `approval: never`）+ `hardguard`；`guard` 可选；审计始终记录。 |
| 12 | 开机 | **12A** | 开机 full + last-good 回滚；连续失败 3 次进 rescue；service.sh/boot-completed 非阻塞。 |
| 13 | SELinux 域 | **13A** | 接受 `u:r:ksu:s0` permissive allow-all；不写自定义域；全局 Enforcing 保持；不声称 SELinux 保证隐藏。 |
| 14 | `/apex`/`/linkerconfig` | **14B** | 递归只读：使用 `mount_setattr(AT_RECURSIVE)` 或等价的逐挂载 remount；必须验证子挂载（如 `cacerts`）也 ro。不能只做顶层 `remount,ro,bind`。 |
| 15 | `/storage` | **15A** | 完整 `rbind /storage`，包含 `/storage/self`、`/storage/emulated`、各用户视图；`/sdcard` 另 bind raw `/data/media/0` 以获得 exec。 |

## 2. 由决策直接导出的实现约束

- `dsh-ctl` 的默认值必须与上表一致；不能悄悄改用旧推荐组合。
- `plan` 必须把 `2B` 的 selinuxfs 遮罩、`6B` 的 critical ro、`14B` 的递归 ro 写进计划。
- `1A` 的别名遮罩是 **best-effort**：启动时枚举已知路径；失败的别名记为 warning，不伪造“已完全遮住”。
- `3A`/`5A` 的 full 能力意味着：任何“hardguard 能阻止 root”的说法都是错误的；`hardguard` 只拦 DSH 工具路径上的明显命令。
- `6B` 是唯一的默认只读限制；除此之外不额外做权限收束。

## 3. 明确不再采用的旧方案

- 不再使用 daily/lab/rescue 三档；只有 full + rescue。
- 不再使用目录型 rootfs 作为主线；主线是 ext4 镜像。
- 不再把 `/sys` ro + debugfs/tracing 作为默认（2B 已选 rw），但仍保留 `2A` 作为未来“更谨慎”备选，不影响当前实现。
- 不再把宿主 agent 当作替代 chroot 的方案（5A）；它是补全。
- 不再把 `/data` 缩减为只挂 `/sdcard/DSH`；1A 已选 full。
- 不再把 `u:r:ksu:s0` 说成“SELinux 约束”；13A 已接受。

## 4. 变更规则

这 15 项是产品级选择，不由实现者改。若未来要改：

1. 先写新的 decision 条目和影响分析；
2. 更新 `design-v1.5.md` 的对应段落；
3. 更新 `verification.md` 的验收项；
4. 再改代码；
5. 不删除本文件的旧值，追加新版本。
