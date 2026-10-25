# 分析：WenlaoJP/dsh-alpine-ksu

- 来源：`sources/WenlaoJP_dsh-alpine-ksu`（clone 于 2026-10-26；仓库创建 2026-10-19；pushed 2026-10-20）
- 许可：MIT ｜ 规模：32 文件 / 397KB（payload 不入库）｜ 发布：`dsh_alpine-0.2.0-poc.zip` 257MB
- 定位：KernelSU 模块，在**真 chroot** 里跑 Alpine + Node + DSH 0.2.1-rc.2 + ttyd，开机自启，进程终态 UID 1
- 一句话评价：**这是目前唯一"能装的真 chroot DSH"，四段启动链设计得相当干净，值得作为我们模块骨架的蓝本；但它为 musl/体积做的补丁链是主要维护债。**

---

## 1. 问题与约束

作者在 README 里明确写的取舍：

- PoC / pre-release，要求 kernel ≥ 4.19、≥2GB 可用空间、16KB page-size 兼容设备
- 安装器**不解压约 800MB 运行时**（`customize.sh:3` 注释："never expand the ~800MB runtime in the KernelSU installer"）→ 因为某些管理器会等待安装脚本，解压会卡死管理器
- 运行时不驻留在安装目录，而是解压到 `/data/adb/dsh`
- 明确声明"安全边界是 chroot + UID 1，不是虚拟机"

隐含约束（Android 平台）：

- targetSdk ≥ 29 的应用/模块不能从可写目录直接 exec（W^X）
- `/sdcard` 是 FUSE 挂载，带 `noexec`，且非 app UID 访问可能异常
- 开机阶段 `/data/media/0` 在用户 0 解锁前不可用

## 2. 运行模型

启动链（来自源码 + README 架构图）：

```
KernelSU 安装 → customize.sh（只建目录/chown，不 tar）
  ↓ 重启
service.sh 或 boot-completed.sh（非阻塞，立刻 return）
  └─ setsid bin/dsh-service-boot        （后台）
       ├─ 等 /data/media/0（最多 300s）
       ├─ bin/dsh-bootstrap             （解压 rootfs + DSH baseline，带版本标记与 new/old 交换）
       └─ bin/dsh-watchdog
            └─ bin/dsh-launch           （锁 + stopped 检查；unshare -m）
                 └─ bin/dsh-chroot      （bind /dev /proc /sys /sdcard(raw) + 数据目录）
                      └─ busybox chroot + setpriv --reuid=1 --regid=1
                           --groups=3003,1015,1023,1028 --no-new-privs
                           └─ /usr/local/bin/dsh-entrypoint   （环境变量 + 生成 ttyd 密码）
                                └─ /usr/local/bin/dsh-supervisor
                                     ├─ dsh web 127.0.0.1:3080
                                     └─ ttyd   127.0.0.1:7681
```

身份与命名空间（关键事实）：

| 项 | 值 | 证据 |
|---|---|---|
| 最终 UID/GID | 1:1 | `bin/dsh-chroot:60-62` |
| 附加组 | 3003(inet), 1015(sdcard_rw), 1023(media_rw), 1028(sdcard_r) | 同上 |
| 能力 | setuid 后自然归零，只显式加了 `--no-new-privs` | 同上（**没有** `--bounding-set=-all`） |
| mount ns | 有则 `unshare -m`，无则直接 chroot | `bin/dsh-launch:33-36` |
| PID/user ns | 未使用（本机也不可用） | 源码无 |
| SELinux 域 | 未显式设置（继承管理器）；可选 initrc 里写 `u:r:ksu:s0`，默认禁用 | `extras/initrc/dsh.rc.disabled:4` |

**它故意绑定的是 `/data/media/0`（原始媒体目录），不是 FUSE 的 `/sdcard`**，并在绑定前先清掉目标上所有叠加挂载（`bin/dsh-chroot:41,45-49`）：

> 注释原文（`bin/dsh-chroot:39`）："Android 11+ /sdcard is a MediaProvider FUSE mount. UID 1 (non-app UID) can get EFAULT from FUSE open/create even with allow_other. Prefer the raw media view to avoid the FUSE daemon entirely."

这也是它顺带解决"工作区产物不可执行"的原因（我们前面在本机实测过：FUSE 路径即使 chmod 755 也 EACCES，raw 路径可以执行）。

## 3. 生命周期

- **启动**：`service.sh` 与 `boot-completed.sh` 是两条等价路径（`boot-completed.sh:2-3` 注释：某些 LKM/late-load 配置只触发其中之一），都用 setsid 后台拉起，**立刻 exit 0**；`service.sh:3` 注释明确警告"绝不能在 service.sh 里跑长循环，某些管理器会等它，导致卡在开机动画"。
- **就绪判定**：`bin/dsh-service-boot:17` 注释说明 `/data/media/0` 可能要等用户 0 解锁；最多轮询 300s。bootstrap 用 while 循环重试（每 10s）。
- **监督**：`dsh-watchdog` 是 root 侧守护（仅当没有 initrc 时用），循环调用 `dsh-launch`；`dsh-launch` 会 exec 进 chroot，所以正常情况下不返回。Chroot 内部由 `dsh-supervisor` 每 2s 检查 dsh/ttyd 是否存活并拉起、写 pid 文件、抓 token URL、写 ttyd 认证信息、处理 `restart-dsh` 标志、日志超过 8MB 轮转。
- **停止**：`action.sh stop` → `touch $STATE/stopped` → 按 pid 文件 TERM 再 KILL（service-boot/watchdog/supervisor/dsh/ttyd + bootstrap lock）→ 逐个 `umount -l` → 删 pid 与 lock。用 `stopped` 标志避免 watchdog 立刻复活（`bin/dsh-launch:15` 与 watchdog 循环顶部的检查）。
- **幂等**：`service.sh` 与 `boot-completed.sh` 内容基本重复，但靠 pid 检查 + lock 目录保证不会起两份。

## 4. 数据与更新

| 数据 | 位置 | 说明 |
|---|---|---|
| rootfs | `/data/adb/dsh/rootfs` | 带版本标记 `.rootfs-version`；解压到 `rootfs.new` 再 mv 交换（`bin/dsh-bootstrap:39-44`），失败不会破坏旧 rootfs |
| DSH 运行时 | `/data/adb/dsh/opt/dsh/versions/<ver>` + `current` 软链 | 版本化，支持 `previous` 回滚 |
| DSH 配置/会话 | `/data/adb/dsh/home/.dsh`（bind 到 chroot 的 `/home/dsh`） | 持久化与 rootfs 解耦 |
| npm/pnpm 缓存 | `/sdcard/DSH/npm/...` | 大缓存放共享存储，不占 /data |
| 工作区 | `/sdcard/DSH/workspace`（bind raw media） | 手机文件管理器可见 |
| 状态 | `/data/adb/dsh/state`（dsh-url.txt、ttyd.pass、ttyd-auth.txt、stopped、restart-dsh） | `chmod 600` |
| 日志 | `/data/adb/dsh/logs` | supervisor 内做 8MB 轮转 |

更新路径：`dsh-update` 在 chroot 内以 UID 1 运行，装到新版本目录 → `npm rebuild node-pty --build-from-source`（`dsh-update:51`）→ 重写 koffi 的 `index.js` 指向 `./musl_arm64/koffi.node`（`dsh-update:57`）→ `dsh-prune` → 原生模块 smoke test（node-pty/koffi/sharp）→ `dsh --version` → 切换 `current` 软链并记录 `previous` → `touch restart-dsh` 让 supervisor 重启。`rollback` 直接切回 `previous`。

## 5. 为什么这样写（证据）

1. **非阻塞启动**：`service.sh:3` 的注释是直接证据，不是猜测。
2. **两条启动入口**：`boot-completed.sh:2-3` 注释说明 LKM late-load 场景差异。
3. **运行时延后解压**：`customize.sh:3` 明说避免管理器卡死；`customize.sh` 里只做 `mkdir` + 顶层 `chown 1:1`。
4. **rootfs 原子替换**：`dsh-bootstrap` 的 new/old 交换，目的是"解压失败时旧 rootfs 仍可用"。
5. **绑定 raw media 而非 FUSE**：`dsh-chroot:39` 注释直接给出 UID 1 在 FUSE 上 EFAULT 的经历；并且绑定前清掉叠加挂载（`:45-49`）——说明他们踩过"旧 FUSE 层挡住 raw 视图"的坑。
6. **unshare -m**：把挂载限制在私有命名空间，避免污染宿主；无此能力时退回全局挂载（`dsh-launch:33-36`）。
7. **降权到 UID 1 + 固定组**：既要能读写 media/inet，又要避免 root 拥有共享存储里的文件；`--no-new-privs` 防止提权。
8. **Alpine 的具体代价**：`build-rootfs.sh:64` 的 full profile 要装 `build-base python3 cmake nodejs-dev linux-headers`；`dsh-update:51` 每次更新要重建 node-pty；`dsh-update:57` 要手写 koffi musl loader shim；`dsh-prune` 里专门保留 `@img/sharp-linuxmusl-arm64` 等 musl 变体。这些都是"选 musl"换来的。
9. **16KB 对齐检查**：`build-rootfs.sh` 与 `scripts/check-16k.sh` 对 node、ld-musl、.node/.so 做 LOAD align ≥16384 校验——为未来 16KB page 设备留门。
10. **凭据最小化**：ttyd 密码 32 字节 urandom + base64，`chmod 600`，只监听 127.0.0.1；DSH 用自带 token，也只在 loopback。

## 6. 更优雅的做法（带代价）

| # | 现状 | 更优雅 | 代价/风险 |
|---|---|---|---|
| 1 | Alpine(musl)：更新时重建 node-pty、手写 koffi shim、prune 保 musl 变体 | 换 Debian slim / Ubuntu minimal（glibc）：直接用 DSH 的 glibc 预编译，node-pty/koffi/sharp 都不需要补丁 | rootfs 大约 +300–400MB；apk 的轻快感变成 apt 的慢；但**维护债显著下降**（对我们的"长期自维护"更划算） |
| 2 | `unshare -m` 已隔离挂载，但 `action.sh` 仍做 `unmount_all` | 挂载既然在私有 ns 里，进程死掉会自动消失；只在"没有 unshare"的 fallback 分支才需要 `unmount_all`，可加判断或直接去掉 | 若某 ROM 不支持 unshare，退回全局挂载时会残留挂载；需要在 fallback 分支保留 |
| 3 | `service.sh` 与 `boot-completed.sh` 约 30 行重复 | 抽一个 `boot-hook.sh`，两个入口都 source 它 | 多一个文件；收益一般，但能避免两处逻辑漂移 |
| 4 | shell 轮询式 supervisor（2s） | 用 s6/runit 之类进程监督器 | 多一套依赖；对 PoC 不划算 |
| 5 | 从 `dsh.log` 正则抓 token URL | 若上游提供结构化输出/配置读取，直接读字段 | 取决于上游；正则对 URL 格式变化脆 |
| 6 | 端口/路径硬编码（3080、`/sdcard/DSH`） | 写成变量并允许配置 | 几乎零成本，收益是能避端口冲突（我们已有 3080 占用） |
| 7 | 只加 `--no-new-privs` | 显式 `--bounding-set=-all --inh-caps=-all --ambient-caps=-all` | 零成本，语义更清楚（UID1 本来也没 caps） |
| 8 | 只绑 /dev /proc /sys /sdcard / 数据目录 | 按需要加 `/system:ro`、`/apex:ro`、更宽的 `/data` | 这是我们"全能"目标需要的改动；风险用可逆性（快照/回收站）来对冲，而不是用权限 |
| 9 | `chown 1:1 /storage/emulated/0/DSH` 走 FUSE | 通过 raw 路径 `/data/media/0/DSH` 做 chown/chmod | 本机实测 FUSE 会吞掉 chmod 效果；走 raw 更可靠 |
| 10 | 无备份/无快照/无审计 | 加 rootfs 快照、数据备份、回收站、命令审计 | 这正是我们的 H7/H8，属于新增而非改造 |

## 7. 对我们的启示

**直接可复用（我建议照抄思路，重写实现）**
- 非阻塞 `service.sh` + 幂等的第二入口
- `dsh-service-boot` 的"等解锁 → 重试 bootstrap → 交 watchdog"
- `dsh-bootstrap` 的版本标记 + new/old 原子交换
- `dsh-chroot` 的挂载顺序 + **raw media 绑定 + 清理叠加挂载**
- `dsh-supervisor` 的状态文件 / 重启标志 / 日志轮转
- `/opt/dsh/versions/<v>` + `current` 软链 + `previous` 回滚 + 安装后 smoke test
- `action.sh status|diag|log|start|stop|restart` 这套运维接口
- `dsh-prune` 的"只留目标架构/CRT 变体"思路
- 16KB 对齐检查（我们设备是 4K，但作为移植检查保留）

**必须改动**
- 发行版：Alpine → Debian slim（glibc），删掉 node-pty 重建与 koffi shim 这两处最脆的补丁
- 挂载：加 `/system:ro`、`/apex:ro`、按需 `/data`；`/dev/block` 不绑或只读
- 能力：显式 caps 归零；端口/路径参数化
- 三层新增：规则清单（deny/ask）、快照/回收站、审计日志
- 明确写"chroot 不是沙箱"，不再提隔离

**避免**
- 不要沿用"从日志正则抓 token"这种脆解析（保留但要知道它的脆弱面）
- 不要在同一台机上同时跑两份 DSH（3080 冲突）；我们只留一个宿主

## 8. 待验证（不猜，留给实验阶段）

- [ ] 它的 `unshare -m` 在你的设备上是否真的生效（生效则 `unmount_all` 基本是冗余的）
- [ ] boot 服务的实际 SELinux 域（源码只给了一个默认禁用的 `u:r:ksu:s0` initrc）
- [ ] `chown 1:1 /storage/emulated/0/DSH` 在 FUSE 上是否真的生效（本机实测 chmod 会被吞）
- [ ] "UID 1 在 FUSE 上 EFAULT"这个注释描述是否能复现（我们实测的是 noexec + 无 x 位）

---

**结论**：骨架抄它，发行版换 glibc，补上可逆性与规则层。它是"能跑"，我们要的是"能跑 + 能回滚 + 能立规矩"。
