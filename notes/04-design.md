# 设计草案 v0.1（全能 AI 的真 chroot 环境）

> 状态：草案。已定项来自实测与源码分析；标 **TBD** 的等研究笔记回来后补（快照、监督）。
> 结构照抄 DSH-Chroot 的文档骨架：目标/非目标 → 现状 → 路线 → 架构 → 目录挂载 → 生命周期 → 数据快照 → 规则审计 → 版本 → 威胁模型 → 验收。

## 1. 目标与非目标

**目标**：在 PJZ110（Android 15 / SukiSU）上，给 AI 一个**全能力**的 Linux 用户态：
真 chroot（非 proot）、root 身份、宽挂载、能 apt/编译/挂载；工作区在共享存储可见且产物可执行；
开机自启、可启停、可诊断；**可逆性**（快照/回收站/审计）与**规则层**（deny/ask）。

**非目标**：不做安全沙箱（本机 kernel 不支持 userns/pidns/Landlock，详见 §2）；不做多容器；
不追求多机型/发布；不动内核；不替代 DSHA 的移动 UI 与设备控制。

## 2. 现状盘点（实测，2026-10-26）

| 项 | 事实 |
|---|---|
| 机型/系统 | OnePlus PJZ110 / Android 15 / kernel 6.6.66 |
| Root | SukiSU（KernelSU LKM 系），`su` → `uid=0, context=u:r:ksu:s0`，SELinux Enforcing |
| 内核 namespace | `CONFIG_NAMESPACES=y`；**`CONFIG_USER_NS` / `CONFIG_PID_NS` 未编译**；`unshare -m` 可用 |
| Landlock | **`CONFIG_SECURITY_LANDLOCK` 未编译** → DSH 文件沙箱永远不可用，只能 danger-full-access |
| /data | f2fs，`rw,nosuid,nodev`（**无 noexec**）→ rootfs 放这里天然可执行 |
| /sdcard | FUSE，`nosuid,nodev,noexec` → 必须走 raw `/data/media/0` 才能执行产物 |
| 现有 DSH | 0.2.0-rc.2（dsh-termux 运行时），tmux 里跑 `dsh web --port 3080` |
| 空间 | /data 有 684GB 可用 |

## 3. 路线选择

| 路线 | 结论 |
|---|---|
| proot / DSHA | ❌ 有 root 就不该付 ptrace 翻译的代价；且绑定 app 沙箱 |
| Alpine + musl | ❌ node-pty 只发 glibc 预编译、cua-driver musl 404 且静默降级（DSH-Chroot A12 + libc 审计） |
| **Debian slim (glibc) + 真 chroot** | ✅ 唯一"不现场编译、不静默降级"的选择；基座体积差相对 500MB 的 DSH 是噪声 |
| AVF 虚拟机 | ⏸ 本机 Android 15 无入口；设计上不堵死（rootfs 与启动层解耦） |

## 4. 架构（四层 + 适配器）

```
L3 策略层    DSH: sandbox-policy / permission-presets(root-full) / hooks(deny·ask) / 审计
L2 DSH 运行时 node 24 + dsh（版本化目录 + current 软链 + previous 回滚）
L1 容器运行时 预烘焙 Debian rootfs + bind 挂载 + chroot + 身份切换（无 Python 依赖）
L0 宿主/模块  KernelSU 模块：service.sh 非阻塞启动、bootstrap、watchdog、快照、卸载清理
```

关键取舍：
- **L1 不依赖 chroot-distro 的运行时**。chroot-distro 只在**烘焙阶段**（宿主侧 Termux）用来装 rootfs、做 diff/备份；
  运行时只做 mount+chroot（shell 即可），避免把 Python 拖进开机路径。
- **L0 不放进模块目录**：`/data/adb/modules/<id>` 会被整体删除，rootfs/state/payload 全部放 `/data/adb/chroot/`。

## 5. 目录与挂载规范

```
/data/adb/chroot/
├── payload/        预烘焙 rootfs.tar.zst + dsh-baseline.tar.zst（不入模块 zip）
├── rootfs/         解压后的根（new/old 原子交换）
├── state/          DSH_HOME、ttyd 密码、端口、stopped 标志
├── logs/           bootstrap / launch / supervisor / dsh / audit
└── snapshots/      rootfs 与 state 的快照（TBD 策略）
```

挂载（**父必须先于子**）：
`/dev`、`/dev/pts`、`/proc`、`/sys`、`/system:ro`、`/apex:ro`、`/vendor:ro`、`/product:ro`、
`/data/media/0 → /sdcard`（raw，绕 FUSE）、`/data/adb/chroot/state → /root/.dsh`。
不 bind `/dev/block`（防误刷分区）。每次启动在 `unshare -m` 私有命名空间内挂载。

## 6. 生命周期（TBD：监督细节待研究笔记）

- **启动**：`service.sh` 立刻 return → `dsh-service-boot`（等 /data/media/0，最多 300s）→ bootstrap（版本标记 + new/old 交换）→ watchdog → launch（锁 + stopped 检查）→ `unshare -m` + chroot + setpriv。
- **停止**：写 `stopped` → 按 pid 文件 TERM → 等 → KILL → umount → 删 lock；禁止按端口/名字杀。
- **崩溃**：watchdog 每 10s 重拉；与 stop 用 `stopped` 标志避免竞态。

## 7. 数据、快照与回滚（TBD：等快照研究笔记）

要求：rootfs 升级前快照 1 份、state 每日 1 份保留 7 份、一键回滚、占用上限 5GB。
候选：目录 + `rsync --link-dest` 硬链接快照 / tar.zst 全量 / ext4 镜像 + `cp`。**待定**。

## 8. 规则与审计（已定，详见 [research/dsh-extension-surface.md](research/dsh-extension-surface.md)）

- 预设：自定义 `root-full` = `sandbox: danger-full-access` + `approval: ask`（出厂的 danger-full-access 预设会把 approval 一并设成 never）。
- 规则：`dsh-hooks-claude-code` + `hooks.json`，`PreToolUse` 命令钩子读 `deny.txt`/`ask.txt`；命中 deny → exit 2 + 理由；命中 ask → 走批准 seam。
- 审计：`PostToolUse` 钩子把 时间/cwd/工具/输入 追加到 `logs/audit/YYYY-MM-DD.log`。
- 红线：钩子 **fail open**（崩了命令照跑）→ 零依赖脚本 + 启动自检 + 文档写明"护栏不是边界"。

## 9. 版本策略

- 模块：`versionCode` 单调整数，唯一比较依据；`version` 不参与比较。
- DSH：floor-only（允许的最低版本）+ bake 台账（每次烘焙的 DSH 版本单独记录，不编码进 module.prop）。
- 升级：新版本装到 `versions/<v>`，smoke test 原生模块后切 `current`，保留 `previous` 回滚。

## 10. 威胁模型与不设防声明

资产：整机数据、DSH 凭据、设备控制能力、系统可用性。
不防：有 root 的攻击者；物理接触；提示注入本身（只限爆炸半径）；DSH 上游漏洞；内核/固件；用户自己执行不可逆命令。
**结论**：安全 = 规则（降低误操作概率）+ 可逆性（把错误变成可回滚）+ 审计（事后能查）。

## 11. 验收标准（P0 命门，全部通过才进入打包）

1. chroot 内 `id` 与 `uname -r` 正确（root / 与宿主同内核）。
2. **Go 在 /sdcard 工作区编译并直接运行产物**（raw media 生效）。
3. `node-pty` 在 chroot 内能起 PTY（DSH 的命脉）。
4. `dsh web --port 0` 打印带 token 的 URL，Web 可访问。
5. `dsh-update` 安装新版本 + smoke test + 切 current + rollback。
6. stop 后 `/proc/mounts` 无残留、无孤儿进程。
7. 快照 → 破坏 → 回滚，一轮通过。
8. 钩子拒绝一条已知坏命令；审计文件有记录；钩子失效自检能报警。

## 12. 待定项（需要拍板/等研究）

- [ ] Q1 进程身份：root（推荐，全能优先）vs UID1+groups（避免 /sdcard 属主问题）
- [ ] Q2 快照方案与保留策略（等笔记）
- [ ] Q3 监督器：shell 轮询 vs s6（等笔记）
- [ ] Q4 规则脚本语言：纯 sh（快、零依赖）vs python（易写）
- [ ] Q5 payload 分发：手工 push 到 /data/adb/chroot/payload，还是模块内置？
