# DSH chroot 计划 v2（读全开 / 写粗心好人）

- 日期：2026-10-30
- 状态：**执行中；P0 文档与策略清理、P2-1/P2-2/P2-3/P2-4/P2-5/P2-6、P3-1–P3-7 已落地（P3-2 预览摘要已完成）；P1 读可见性已实现；`/dev/block=ro` 保持默认可读，但 ro 只是视图、不能宣称写保护，真正写保护必须靠 R4 人工门禁；宿主 config 已从 masked 改为 ro，运行中的实例仍为 masked，需下次 start 生效；P3-8 已落地 `risk.sh` + R0/R3 dispatcher 门禁/token/审计，R1/R2/R4 原语与 test_risk 覆盖已补；实际文件写入口仍待接入；2026-10-30 审计报告 `audit-reports/2026-10-30-chroot-issues.md` 为最新阻断依据**
- 范围：`module/` 宿主模块、chroot 生命周期、镜像/快照、挂载、watchdog、控制面与相关设计文档
- 明确不在本计划：Agent APK/Console App（另一分支重新设计，相关文档不再作为 chroot 主线依据）

## 0. 一句话结论

旧的“全局 SELinux 保持 Enforcing + chroot 内路径遮罩 + 读写都当安全边界”模型废弃。
新模型是：

> **读：无限制，能读尽读。写：按善意但粗心的操作者设计，所有副作用都可预览、可回滚、可发现残留。**
>
> **SELinux：不读取、不修改、不判断、不依赖全局 SELinux 状态。**

> **概念优先级（2026-10-30 用户确认）：本项目的目标是“全手机可读、全手机分级写、默认操作者是善良但粗心的模型”。知识库和参考项目只提供机制/坑，不得用它们的 sandbox、read-hiding、默认 masked 或威胁模型覆盖本目标。**

## 1. 新模型

### 1.1 读

chroot 内的 root 是本机 root 的一部分，不是访客：

- `/data`、`/data/adb`、`/data/data`、`/data/system`、`/mnt`、`/storage`、`/sdcard`、`/dev`、`/sys`、Android 分区都应可读。
- `/proc/1/root`、`/proc/<pid>/root` 等宿主进程视图不刻意遮蔽。
- `/dev/block` 默认提供只读设备节点；读内容不需要“安全审批”。
- 不再以“可能被 App 看见”为理由遮蔽 chroot 内部读路径。应用层检测由 KSU 隐藏特性和“不改全局可见信号”负责，不由 chroot 读权限负责。
- 不读取、不轮换、不脱敏 chroot 内的凭据、token、日志；日志/状态只保证宿主文件权限和生命周期正确。

### 1.2 写（善意但粗心模型）

“粗心”不是“允许任意 root 写”，而是**让一次手滑不会变成不可恢复的宿主损坏**：

1. **显式**：改变镜像、快照、`/system`、块设备、全局挂载的操作必须带 `--apply`；普通只读命令不带。
2. **预览**：`plan`/`doctor`/`status` 先说明目标、路径、大小、将挂载/覆盖什么。
3. **原子**：写 state、镜像、快照使用临时文件 + `fsync` + `rename`；失败保留上一份。
4. **可回滚**：镜像/state 写前有备份；快照/恢复先校验，失败自动回滚；每次启动写 last-good。
5. **可发现**：stop/start/recover/purge 后必须清理 loop、mount、namespace、临时文件；`status --json` 能看出残留。
6. **不误伤**：所有路径必须在声明的根内；拒绝 `/`、`..`、换行、可疑符号链接；删除只删自己创建的对象。
7. **危险操作二次确认**：`system-rw full`、`DSH_BLOCK_ACCESS=full`、`purge`、从不可信镜像恢复需要明确 `--apply`/`--yes` 与摘要。

### 1.2.1 写操作五档：无限制 / AI 普通确认 / AI 敏感数据确认 / AI 重大确认 / 人工确认

按**是否影响系统、是否触碰应用私有数据、后果严重度、确认权归属**分五档。核心不是“多弹框”，而是让普通写保持安静，把注意力留给真正危险的写。

| 档位 | 名称 | 典型操作 | 确认方式 |
|---|---|---|---|
| R0 | 无限制 | 读任意内容；**不改变系统状态的新建写**：写 `state/workspace`、`state/dsh-home`、日志、tmp、用户指定目录中的新文件；DSH 自有 state/log 的内部原子替换也算 R0 | **不提示**；仍原子写、失败回滚、写审计 |
| R1 | AI 普通确认 | **覆盖已有用户可见非系统文件**（如 sdcard 已有文件）；不改系统配置，只是替换用户数据 | 单轮轻提示，AI 可直接回 `yes`/`confirm:"<op>:<path>"`；不要求人工 |
| R2 | AI 敏感数据确认 | **应用私有数据**：写/覆盖/删除 `/data/data/*`、`/data/user/*/*`、`/data/user_de/*/*` 下的普通 App 数据（数据库、prefs、files、cache；`/data/data` 是 user 0 的兼容别名） | **不能裸 yes**：AI 自行审批，但必须给出 `reason`、包名、精确路径、备份/快照点、目标 hash 和回滚方式，再走专用 token；不要求人工 |
| R3 | AI 重大确认 | **影响系统但通常不造成严重后果**：`start/stop/restart`、`image install`、`snapshot restore/promote`、`purge`、`system-rw on`、`recover`、修改镜像/工作区路径、`DSH_BLOCK_ACCESS=full` 切档；以及系统关键数据 `/data/system`、`/data/misc`、系统关键包（如 `com.android.*`）的私有数据 | 先 dry-run；AI 必须提交 `reason` + 影响摘要 + 目标 hash + 备份/回滚点；两阶段专用 token，由 AI 自行审批，不要求人工 |
| R4 | 人工确认 | **可能造成严重后果**：写 `/dev/block/*`、`dd`/`mkfs`/`blkdiscard`、改分区表、直接写系统分区、`DSH_BLOCK_ACCESS=full` 下的 raw write | 必须人工审核；AI 先提交**足够原因**（目的、精确目标、影响、备份/回滚、替代方案），人工在宿主/Console 确认；无人工通道时 fail closed，AI 不得代替确认 |

五档的实现规则：

1. **R0 完全静默**：普通新建文件不该出现任何确认；否则 AI 会习惯性点“是”。R0 只记审计，不升级为安全事件。
2. **R1 只对“覆盖”提示**：同一路径的新建不提示；覆盖已有非系统文件才给一次路径/大小/是否可恢复的轻提示；AI 可以直接 yes，这是刻意保留的低摩擦层。
3. **R2 专管 `/data/data`**：读 `/data/data` 仍是 R0；写 app 私有数据不是普通 sdcard 覆盖，必须先停写/备份（能用 app 自身备份就用，否则复制目标文件/数据库），确认包名和路径；不能因为“不是系统分区”就降成 R1。
4. **R3 是 AI 自审批，不是人工审批**：重点是“不能只回一个 yes”。AI 必须先看到影响摘要，再提交 `reason` 和绑定 `op+target+nonce+expiry` 的 token；token 一次性、不可跨操作复用，`--yes` 和环境变量不能替代。
5. **R4 是人工审批，不是更长的高危提示**：默认禁用；AI 必须先提供足够原因；确认必须在宿主/Console 的人工路径完成，chroot 内 agent 不能直接调用；没有人工 TTY/确认通道时直接失败。
6. **批量只确认一次**：汇总目标数、总大小、覆盖/删除清单，按最高档位处理；不能按文件逐个刷提示。
7. **所有档位都审计**：操作、路径、目标 hash、大小、时间、结果、备份位置；R0 只是不弹框，不是无记录。

### 1.3 SELinux

- DSH 不运行 `getenforce`/`setenforce`，不保存/恢复 `selinux.orig`，不因全局 SELinux 状态拒绝 start/stop/doctor。
- 不把 `--permissive` 当作功能；删除该开关及相关测试。
- `selinuxfs` 只作为 guest 内的“可见内容”处理：
  - 默认优先 `DSH_SELINUXFS_MODE=ro`（private namespace 内新建只读 selinuxfs），让 root 能读；
  - `mask` 仅作为显式隐藏/兼容选项，不再是安全默认；
  - 不对宿主 selinuxfs 做 bind/remount。
- KSU 的 `selinux_hide`/`mount_hide`/`avc_spoof`/`kernel_umount` 仍作为“不改全局可见信号”的机制保留，但 doctor 不因它们的状态 FAIL。

### 1.4 不做什么

- 不做防恶意 root、防物理接触、防 chroot 内 root 的安全边界。
- 不把“读不到”当安全；不为了隐藏而降低可读性。
- 不用 `u:r:ksu:s0` permissive 当隐藏保证，也不试图修改它。
- 不为兼容旧 Agent API 保留接口。

## 2. 与旧文档的关系

本计划是 2026-10-30 之后的**执行计划**。旧内容已按原文归档在：

- `docs/archive/notes/18-readmax-writecareless/requirements-before.md`
- `.../hiddenness-before.md`
- `.../design-v1.5-before.md`
- `.../roadmap-before.md`

被本计划明确取代的旧条款：

- `requirements.md` 旧 §4.1「全局 SELinux 保持 Enforcing」与 `--permissive` break-glass；
- `requirements.md` G4 的「`/sys/fs/selinux` 默认 tmpfs 遮罩」安全口径；
- `hiddenness.md` 旧 §2.1/§2.4 的全局 SELinux 与 selinuxfs 默认遮罩口径；
- `design-v1.5.md` §6.1 的全局 Enforcing 启动门与 `--permissive`；
- `roadmap.md` 旧的 P0–P5 主线；新路线以本计划 §3 为准。

`decisions.md` 保留 2026-10-26 的 15 项锁定组合供历史与 `build/check-docs.py` 校验；
在文件末尾追加 2026-10-30 的覆盖决定，明确 SELinux 条款作废，但旧组合文本不删。

## 3. 工作包

### P0 文档与策略清理（本轮）

| ID | 工作 | 文件 | 验收 |
|---|---|---|---|
| P0-1 | 归档旧 requirements/hiddenness/design/roadmap 原文 | `docs/archive/notes/18-readmax-writecareless/` | 原文可追溯，当前文档不再引用旧安全口径 |
| P0-2 | 写新计划并更新文档入口 | `docs/plan-v2.md`、`docs/README.md` | 权威顺序和新模型清楚 |
| P0-3 | 更新 requirements/hiddenness/design 的冲突条款 | 三个当前文档 | 不再把 SELinux 当门禁，不再把读遮蔽当安全 |
| P0-4 | 追加 decisions 覆盖决定 | `docs/decisions.md` | 保留旧组合，新增 2026-10-30 覆盖节 |

### P1 读可见性（⚠️ 部分完成；P1-2 的 ro 写保护不成立）

| ID | 工作 | 主要文件 | 验收 |
|---|---|---|---|
| P1-1 ✅ | `dsh-nsinit` 默认 selinuxfs 改为 `ro`，`mask` 保留显式选项 | `module/bin/dsh-nsinit`、`module/lib/config.sh` | guest 能读 selinuxfs；不 bind 宿主 selinuxfs |
| P1-2 ⚠️ 需修正定位 | `/dev/block` 默认 `ro`：**可读、不隐藏**；`ro` 只作为只读视图，不得宣称写保护；`masked` 仅作为显式可选模式；`full` 仅显式 R4。本机 2026-10-31 实测当前运行实例仍为 `tmpfs size=1024k,mode=000`；宿主 `config.conf` 已改为 `DSH_BLOCK_ACCESS=ro`，下次 start 生效 | `dsh-nsinit`、`config.sh`、`plan.sh` | 默认能读块设备；真正阻止写必须依赖 R4/人工门禁或设备级手段，不能靠 ro 挂载 |
| P1-3 ✅ | 保留 `/data`、`/proc`、`/sys` 全量读视图；删除以隐藏为目的的 read mask | `dsh-nsinit` | 文档中的“读全开”检查通过 |

### P2 写安全硬伤（优先级最高）

| ID | 工作 | 主要文件 | 验收 |
|---|---|---|---|
| P2-1 ✅ | 修 SELinux 生命周期残留：删除 `--permissive`/`selinux.orig`，所有 stop/recover 路径不再依赖它 | `module/lib/lifecycle.sh`、`module/bin/dsh-ctl`、`doctor.sh`、`dsh-watchdog` | `grep -R 'dsh_selinux_\|setenforce' module/lib/lifecycle.sh module/bin/dsh-ctl module/lib/doctor.sh module/bin/dsh-watchdog` 为空；start/stop 在 SELinux 任意状态可完成 |
| P2-2 ✅ | 修 rootfs 符号链接导致宿主写入/挂载逃逸：`dsh-nsinit` 的 `/etc/resolv.conf`、`/etc/hosts`、`/run` 等路径做 realpath 包含检查；`mount.sh` 拒绝目标父路径符号链接 | `module/bin/dsh-nsinit`、`module/lib/mount.sh` | 假 rootfs 中 `/etc -> /etc` 的测试不能在宿主写文件 |
| P2-3 ✅ | 修 loop 记录失败：`loop.attach` 成功后 `loop.file` 写失败必须立即 `losetup -d` | `module/lib/lifecycle.sh`、`module/lib/loop.sh` | 注入写失败无残留 loop |
| P2-4 ✅ | 修快照归档符号链接：`tar -tvzf` 解析 `-> target`，拒绝绝对/越界 target；解包使用不跟随符号链接的路径校验 | `module/lib/snapshot.sh` | 恶意 symlink 归档 restore 必须失败且不写 stage 外 |
| P2-5 ✅ | watchdog 在手动 `start --apply` 也启动；residual 扫描覆盖全部 `rootfs*.img`/快照镜像 | `module/service.sh`、`module/bin/dsh-watchdog`、`module/lib/lifecycle.sh` | 手动启动 10 秒内 watchdog 存在；备份 loop 残留被发现 |
| P2-6 ✅ | `lock` stale 判断必须带 starttime/ns/boot identity；缺 identity 不得当成活锁 | `module/lib/lock.sh` | PID 复用/缺 identity 测试不永久死锁 |

### P3 粗心操作陷阱

| ID | 工作 | 文件 | 验收 |
|---|---|---|---|
| P3-1 ✅ | `snapshot` 未知子命令直接报 usage，不默认 create | `module/bin/dsh-ctl` | `snapshot craete` 不建快照 |
| P3-2 ✅ | `image create` 拒绝 `/` 和明显非 rootfs 源；`--source` 摘要/大小预览 | `module/lib/image.sh`、`dsh-ctl` | `/` 作为 source 被拒；create 预览 size，install 预览 size+sha（已由 `test_image.sh` 覆盖） |
| P3-3 ✅ | `config` 校验 `DSH_TMP`、`DSH_IMAGE` 必须在 `DSH_IMAGE_DIR` 下、`DSH_ROOTFS_DIR` realpath 不得是符号链接 | `module/lib/config.sh` | 越界配置在 doctor --for-apply 前失败 |
| P3-4 ✅ | `customize.sh` 不再覆盖 `control/s6/ttyd/down` 等运行时状态；更新前后保持 ttyd 开关 | `module/customize.sh` | 更新前后 `ttyd status` 一致 |
| P3-5 ✅ | `service.sh` 日志轮转失败降级为警告，不阻止启动 | `module/service.sh` | 只读/满盘模拟仍进入 boot |
| P3-6 ✅ | `status` 默认输出完整 URL（loopback 入口不是秘密），`--redact` 供贴公开日志时脱敏；`plan.sh` 修正 1A/9A 过期文案 | `module/lib/status.sh`、`module/lib/plan.sh` | 默认可直接点开；`--redact` 路径有测试；plan 与当前策略一致 |
| P3-7 ✅ | `system-rw.list`/ttyd 状态持久化去死字段；`image` size 语法严格化 | `module/lib/systemrw.sh`、`image.sh` | 状态可重建；`G`/`1G2M` 被拒 |
| P3-8 🚧 | 写操作五档落地：R0 无限制、R1 AI 普通、R2 AI 敏感数据、R3 AI 重大、R4 人工 | `module/lib/risk.sh`、`module/bin/dsh-ctl`、`module/lib/systemrw.sh`、`image.sh`、`purge`/`restore` 入口 | R0/R3 dispatcher 门禁 + 单次 token + 审计；R1 可确认、R2 备份+token、R4 无 TTY 拒绝均通过 `test_risk`；实际文件写入口仍待接入 |

### P4 验证与清理

| ID | 工作 | 验收 |
|---|---|---|
| P4-1 | 新增假 rootfs 符号链接测试（不碰设备） | host temp 中没有被写入的探针文件 |
| P4-2 | 新增 loop.file 写失败注入 | `losetup -d` 被调用、无残留 |
| P4-3 | 新增恶意快照归档测试 | restore 拒绝 symlink target 越界 |
| P4-4 | 新增 SELinux 缺失/enforce=0 启动测试 | 不调用 `getenforce/setenforce`，生命周期仍可用 |
| P4-5 | 新增手动 start 后 watchdog 测试 | watchdog 进程/状态在 10 秒内存在 |
| P4-6 | 完整 `build/check.sh` + `tests/run.sh` | 全部通过，无真实设备写操作 |
| P4-7 | 写分级测试：R0 无提示、R1 覆盖可 yes、R2 `/data/data` 备份/token、R3 reason/token 错误拒绝、R4 无人工 TTY 拒绝 | 不产生真实块设备写；测试只读取设备身份 |

## 4. 当前状态

- 2026-10-30：完成本轮文档审计；旧模型原文已归档；`plan-v2.md` 建立；P0 文档与策略清理完成。
- P2-1（移除 SELinux 门禁）、P2-2（rootfs 符号链接）、P2-3（loop 记录失败）、P2-4（快照归档符号链接）均已实现并通过各自测试。下一步进入 P3 粗心操作陷阱。
- 所有验证仍在 mock/host 层，真实设备操作另行走显式授权流程。

## 5. 验收总则

任何时候以下条件必须同时成立：

1. 没有新的全局 mount/loop/overlay/SELinux 修改。
2. 读路径不因“隐藏/安全”被收窄；如果有例外，必须在本文写明理由和开关。
3. 每个写操作有预览、原子提交、失败回滚、残留检查。
4. `build/check.sh`、`tests/run.sh`、文档链接检查通过。
5. 任何真实设备实验先识别设备、备份、明确 `--apply`，不做破坏性开关测试。
- 2026-10-30：P1 读可见性已完成 host/mock 验证（selinuxfs 默认 ro、/dev/block 默认 ro、全量 /data 读）；P3-2 图像源拒绝与预览完成；未进行真机验证。
