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

## 5. 用户确认的边界修正（2026-10-27）

- 活动镜像及已知别名继续遮罩，但**路径遮罩不是块设备保护**。full `/dev` 下的 loop/block 可被直接写入，可能破坏正在运行的 rootfs；不得把镜像遮罩声称为防自毁，也不得用真实块设备做破坏性验证。
- 新增 `DSH_SELINUXFS_MODE=mask|ro|rw`。默认仍为 `mask`；`ro` 只在 private namespace 内以 `mount -t selinuxfs -o ro selinuxfs ...` 新建只读挂载，**不对宿主 selinuxfs 做 bind/remount**。旧的 `DSH_SELINUXFS_MASK=0` 仅作兼容映射，不等于安全的只读模式，也不能成为默认。
- 删除 `PreToolUse` 的正则拦截、hardguard 和 deny/ask 文件，保留 `PostToolUse` 审计。guard 默认保持 off；显式开启时只选择 `approval-guard` 权限预设，不再启用正则拦截。`--apply`、private namespace、holder 身份、关键路径 ro、反向卸载和清理均不改变。
- `/sdcard/DSH/workspace` 暂不挪入镜像。当前目录不存在，外置工作区不属于 rootfs 快照；若以后要求快照包含工作区，需另行备份和明确恢复会覆盖工作文件。
- 后续补充：主力机已在 `state/dsh-home/workspace` 建立工作区/链接。实现优先保留该真实目录或链接目标，不重建旧 sdcard 链接，不每次修改自定义权限；工作区仍不挪进 rootfs.img。真实目录形式的内部工作区随 state 归档；链接只保存链接本身，外部目标不自动备份。恢复可能覆盖工作文件；外置 sdcard 工作区仍需另行备份。
- 备用机报告的 `/data/data`、`/data/user`、`/data/user_de` 和应用白名单属于厂商 profile 数据视图，**主力机必须单独复核**。不能用删除项目内部控制路径遮罩来宣称获得全部应用私有数据，也不能把备用机应用数量和白名单直接套用。

## 6. 操作者原则与风险提示（2026-10-27）

- 面向善意但可能粗心的操作者，不承诺防御恶意 root。移除 PreToolUse 的硬拦截和 deny/ask 文件，不用正则 guard 代替生命周期保护。
- 风险提醒使用当前 DSH 实际支持的 `SessionStart` / `UserPromptSubmit` `additionalContext`，提示块设备、活动镜像和全局 SELinux/prop 风险；不采用该版本会忽略的 `systemMessage` 冒充可见警告。
- `PostToolUse` 审计继续记录工具元数据，不记录完整命令、提示内容或凭据。guard 默认 off；显式开启时仅选择 `approval-guard` 权限预设。
- SessionStart 的提示可能错过首个请求，提示也不逐条阻止危险命令。它不是安全边界，不能消除 full `/dev`、块设备写入或人为误操作风险。

## 7. 块设备入口（2026-10-27）

- 本节取代第 3A 中 `/dev/block` 全见的部分；其它常规 `/dev` 设备与 Android IPC 保留，不再把 3A 解释为默认开放块设备。
- 默认 `DSH_BLOCK_ACCESS=masked`：保留 `/dev` 的常规设备与 Android IPC，但在 private namespace 内用 tmpfs 遮住 `/dev/block`。只有显式 `DSH_BLOCK_ACCESS=full` 才恢复完整块设备视图。loop 的 attach/mount/detach 仍由 holder 负责，不要求 DSH 在 chroot 内访问块设备节点。
- `/dev/block` 若是 symlink，必须拒绝启动，不能把链接目标误遮成整个 `/dev`。遮罩目录的卸载继续逆序进入现有 mount stack。
- 该措施只降低粗心路径误写的概率，**不是安全边界**：`/proc/1/root`、`mknod`、直接 syscall 和其他宿主入口仍可能接触块设备。不得用真实块设备做破坏性验证，也不得声称镜像因此不会被破坏。

## 8. 生命周期与健康检查复核（2026-10-27）

- `start` 在 fsck 前、fsck 后和 attach 前都必须证明目标镜像没有 loop；查询失败不是空闲。已有附件时不得对镜像执行 fsck，也不得通过失败清理顺手 detach 一个并非本次创建的 loop。
- loop 所有权不能只比较路径字符串。已知 `/dev/block/loopN` 与 `/dev/loopN` 通过内核 `loopN` 身份和设备号识别；一个别名路径不存在时选择实际存在的节点 detach。不属于目标镜像的设备仍不得释放。
- `purge` 以操作标记阻止新 claim，先停用 watchdog，再在同一把生命周期锁内完成 stop、cold/loop 复核和删除；失败必须清标记并恢复 watchdog，提供 `dsh-watchdog --enable`。watchdog 使用 PID/starttime/命令路径验证，缺失或不匹配 birth identity 不得抢占。uninstall 同样先停用 watchdog，后续 stop 失败且此前未 disable 时恢复。
- DSH 不再以 URL 文件存在作为唯一健康依据。真实 loopback HTTP 检查要求 200 和 DSH 页面标记，限制超时和重定向；带时间戳的 heartbeat 驱动 running/degraded。启动超时或连续健康失败只结束 web 子进程，让 s6 重启 DSH 服务，健康恢复后可回到 running。
- 控制台“恢复”明确命名为“清理残留”，仍执行 `recover --apply`，不表示快照恢复。命令采用独立输出读取线程和有界等待，超时提示后台可能仍在执行，不能当作停止成功。

## 9. 跨重启 holder 身份（2026-10-29）

- holder 增加内核 `boot_id` 记录；有记录时，PID/starttime/namespace 与 boot_id 必须共同匹配，旧版本没有该记录时保留原有校验。不得把重启后复用的 PID 当作 holder 发信号。
- 身份不符的 `recover --apply` 只有在全局无 rootfs 挂载、目标镜像无 loop 时才清理遗留运行记录，否则保留 `recovery-required`。boot 只自动清理 boot_id 明确属于上一次启动的记录；旧版本需一次显式恢复。`rescue-needed` 不被该自动恢复绕过。
- 安装器只在完整身份匹配时拒绝运行中的 holder，不因旧 PID 或 loop 记录阻止安装修复包；遗留记录交由新版控制器安全恢复，不由安装器强删。

## 10. 开机日志、watchdog 与 hooks（2026-10-29）

- boot 控制器尝试与 watchdog 不再并发启动。每次尝试记录退出码和脱敏状态，首次失败等待 30 秒后最多重试一次；不能静默退出或无限重试。
- watchdog 的 sleep 失败必须退出 daemon。其输出从 boot.log 分离到 watchdog.log，按重复行限速、约 1 MiB 轮转并保留 3 代；boot.log 保留 5 代，当前及归档均要求 0600。
- permission-presets 必须按 web profile 的隔离依赖解析，不能只凭 runtime 顶层包存在宣称完整 hooks 可用。缺少该条目时链接当前 runtime 的同版本包；无法提供则明确选择 audit-only，审计依赖也缺失时禁用 patch。`hooks.status` 只记录本层请求与降级原因，不冒充上游加载或真实审计事件已成功。
- watchdog owner 同时校验 PID/starttime/命令路径和可选 boot_id。真实 daemon 不得重复 claim；失效 owner 仅清理元数据，不向复用 PID 发信号。半成品清理仅限新 owner 已创建但写入失败，不得删除现有 owner。
- 权限显示名改为中文：`approval-free` 为“自动执行（不弹批准）”，`approval-guard` 为“谨慎执行（逐项批准）”；preset key、协议 ID 和既有会话语义不变。

## 11. 锁获取与 stale 回收（2026-10-29）

- `dsh_lock_acquire` 通过原生 `lock.guard` 串行化获取与回收，避免清理者与候选者交错覆盖。进程信息不可读时不得误判 stale；只有进程消失或身份不符才允许回收。
- 锁的 owner 是调用方，不是执行获取的 helper 子进程。使用原子 `mkdir` 检查锁不存在后写入并复核 owner 元数据，不依赖 `mv` 在目标已存在时的覆盖或嵌套行为；真实 owner 不得因并发清理被覆盖。
