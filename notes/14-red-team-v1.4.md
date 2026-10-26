# v1.4 红队审查（2026-10-26）：不能直接开工

- 审查对象：`notes/11-design-v1.4.md`
- 参与：6 个长任务红队（挂载/隐藏/控制/可靠性/实现/安全）+ 10 个扩展角度（内核、隐藏深挖、Android 15、先例、可靠性、实现、最优性、实验安全、控制完整性、许可证）+ 4 个短上下文反驳。长任务队列尚未全部回传；以下是**已返回的短任务 + 独立复核**，不会把未回传内容当成结论。
- 结论：**v1.4 的方向成立，但现在不能开工 apply。** 至少 5 个 P0 必须先修；其中“rootfs 镜像被自己 rw 暴露”和“/sys rw + hardguard 不是边界”是硬伤。

---

## P0-1 rootfs 镜像与chroot 内 `/data` 自引用

**问题**
- v1.4 把 `/data` rbind rw 进 chroot 内，同时 rootfs 镜像在 `/data/media/0/ubuntu-rootfs/rootfs.img`。
- chroot 内（root）可以写/删/改名自己的 backing file；一旦 truncate/rename/unlink，loop 仍持有旧 inode，ext4 元数据与宿主 f2fs 都可能损坏；重启/快照/卸载逻辑也会找不到镜像。
- 0700 只能挡普通 App，挡不住 chroot 内的 root；`/sdcard` 侧也可能看到 `ubuntu-rootfs`。

**修法**
- 在chroot 内里 **遮住内部路径**（不是遮用户数据）：
  - `/data/adb/chroot`（control/state/rootfs/versions）→ `mount -t tmpfs tmpfs`
  - `/data/media/0/ubuntu-rootfs`（rootfs.img）→ `mount -t tmpfs tmpfs`
  - `/sdcard/ubuntu-rootfs` → `mount -t tmpfs tmpfs`（如果 `/sdcard` 是 raw bind）
- 宿主 `dsh-ctl` 仍通过 `/data/...` 访问；chroot 内看不到。
- 或者把镜像放到 `/data/adb/dsh/` 并同样遮罩；关键是**chroot 内不能写自己的 backing file**。
- 这不是权限收束，是完整性：只隐藏内部控制面/镜像，其他 `/data` 仍全开。

**验收**：chroot 内 `ls /data/media/0/ubuntu-rootfs`、`ls /data/adb/chroot` 为空/不可见；`stat` 不能改镜像；宿主仍可 `dsh-ctl snapshot`。

---

## P0-2 `/sys` rw + `/dev` rbind + approval-free，hardguard 不是边界

**问题**
- `/sys` rw 暴露 `/sys/fs/selinux/{enforce,policy,booleans}`；直接 `echo 0 > enforce` 不需要 `setenforce` 二进制。
- `/dev` rbind 暴露 block/loop；root 可 remount/写分区。
- `hardguard` 是 DSH 工具路径上的命令钩子；ttyd/root shell、编译二进制、`syscall()`、直接 sysfs 写都能绕过。
- chroot 内 root 还有 `/proc/1/root` 逃逸；没有 userns/PIDns 时这是事实，不是配置问题。
- 所以“默认 Enforcing + hardguard”只能降低误操作，**不能保证 root 隐藏性**。

**修法（保留最大能力，但承认边界）**
- `/sys` 改 **ro**；`debugfs`/`tracefs` 单独挂到 `/sys/kernel/debug`、`/sys/kernel/tracing` rw（父 ro 不阻止子挂载；本机 debugfs/tracefs 挂载已验证）。
- `/sys/fs/selinux` 在chroot 内用 **tmpfs 遮罩**（private ns；绝不对宿主 selinuxfs remount，见事故复盘）。
- `/dev` 是否保留 rbind 是用户取舍：保留=完整设备控制但 root 可写块设备；不保留=更隐藏但失去 loop/USB。无法两全。
- `hardguard` 改名/定位为 **guardrail/telemetry**，不叫安全边界。
- 在文档中明确：**approval-free + root 权限 + 无 userns/PIDns 不可能保证“root 隐藏性绝对不降”**；只能保证不改全局 props、不 remount selinuxfs、不全局挂载、默认 Enforcing。

**验收**：chroot 内内 `cat /sys/fs/selinux/enforce` 不存在/不可写；`/sys` 只读；debugfs/tracing 可读；`/proc/1/root` 仍可逃逸（如实记录）。

---

## P0-3 stop / namespace 竞态

**问题**
- `nsenter -t <pid>` 有 PID 复用窗口；`/proc/*/root` 扫描在目标 ns 内外语义不同；s6 会在 kill 后重启服务；`losetup -d` 可能在 dirty page/打开 fd 未释放时执行，导致 EBUSY 或镜像损坏。
- 设计里的 `sync → kill → umount → losetup -d` 顺序太粗；没有 namespace fd 固定、没有 s6 停止确认、没有 last-good。

**修法**
1. 启动记录 launcher PID + `/proc/<pid>/stat` starttime + PGID + `/proc/<pid>/ns/mnt` inode。
2. stop 前重读 starttime/ns inode，不一致就按“PID 复用”处理。
3. 先 `s6-svscanctl -t`/`s6-svc -d`，等 `s6-svwait`；再 TERM PGID；再 KILL。
4. `nsenter` 用已打开的 `/proc/<pid>/ns/mnt` fd（`nsenter --mount=/proc/self/fd/N`）而不是裸 `-t`。
5. namespace 内 `sync` → `remount,ro` → `umount`；宿主确认无 `/proc/*/mountinfo` 残留；最后 `losetup -d`。
6. 下次启动 `e2fsck -p`；保留 `last-good` 镜像；失败自动回滚。
7. `cgroup.freeze` 是否可用未验证；先做 pid/starttime/ns fd，不把 cgroup 当已实现。

**验收**：反复 start/stop 20 次无残留、无 EBUSY、无镜像损坏；kill -9 launcher 后 stop 仍能清理/回滚。

---

## P0-4 快照一致性与容量

**问题**
- 对运行中的 ext4 镜像做 `cp --sparse` 会得到 torn image；7×128GB 在镜像填满时超过 `/data` 容量；`/data`/`/sdcard` 不在快照里；快照后没有 `e2fsck`/sha 验证。
- `rootfs.img` 被chroot 内暴露时，快照还可能复制到被篡改的镜像。

**修法**
- 只做 **stop + unmount + e2fsck** 后的冷复制；`cp --sparse=always` 到临时文件 → `sync` → `sha256` → `mv` 原子改名；保留 `last-good` 指针。
- 启动前 `e2fsck -p`；失败自动回滚 last-good；连续 3 次失败进入 rescue。
- 7 份是上限不是承诺；先检查 `/data` 余量 ≥ 1×镜像实际用量 + 安全余量；镜像填满时拒绝新快照。
- `/sdcard/DSH` 单独 restic/trash；`/data`/`/sdcard` 其余明确“不在快照范围”。

---

## P0-5 “perfect phone control”仍是未验证集合

**问题**
- `uiautomator dump` 本机不可用（rc=137），只有 `runtest`；没有 UI 树，`input` 只能盲点。
- `am`/`pm` 只验证了存在/帮助/`pm list`/`settings get`；`am start`、`pm install`、`pm clear`、`pm grant` 的实际权限/会话/Android 15 限制**未验证**。
- `settings put` 需要 `WRITE_SECURE_SETTINGS`；`am start` 对非导出组件会 SecurityException；`pm install` 有 session/验证/targetSdk/16KB 约束。
- `clipboard`/`notification listener`/`accessibility` 需要额外授权；`/sdcard` FUSE 与 raw `/data/media/0` 不是同一视图；多用户 `--user` 未验证。
- 所以“完整手机控制”目前只能写成“命令存在/部分只读验证”，不能写“完美”。

**修法**
- 建一个 **capability matrix**：每项状态 `present / help-only / read-only-verified / write-verified / unavailable`。
- 必测负例：非导出 `am start` 应 SecurityException；`settings put secure` 无权限应失败；`pm install` 用测试 APK；`pm clear --user 10`；`input` + `screencap`；`uiautomator runtest` 测试 jar；`content call scan_volume`。
- UI 自动化：要么接受 `uiautomator runtest` + 自建 jar，要么引入 accessibility service；不能承诺 `dump`。
- 在未完成 write-verified 前，文档不能写“perfect”。

---

## P1 其他重要问题

1. **`/system-rw` overlay**：ext4 upper + 临时 target 成功；**实际 `/system` 挂载点/AVB/verity/APEX/linker 未验证**；PIF Detector 把 OverlayFS on `/system` 当 root-hider 信号；默认必须 off，单分区实测后再考虑。
2. **`/data`/`/mnt`/`/metadata` rw**：用户接受权限全开，但 `/mnt/vendor/persist`、`qmcs`、`/metadata` 写入可变砖；至少提供 `--protect-critical` 可选，不能把“全开”当“无风险”。
3. **`/sys` rw**：除了 selinuxfs，还会改 cgroup/kernel tunables；建议 ro + debugfs/tracing 单独 rw。
4. **`/dev` rbind**：含 `/dev/block`、`/dev/socket`、binder；完整控制 but 暴露面大；若保留，规则层不能假装能挡 root。
5. **`/sys/fs/selinux` remount**：事故高度怀疑关联、机制未证实；设计已删 bind-ro，但**任何“只读 bind”都不要做**；只允许 private ns 的 tmpfs 遮罩。
6. **`getenforce`**：本机返回 Disabled 但 `enforce=1`；doctor 只读 `/sys/fs/selinux/enforce`；不要把 `getenforce` 写进验收。
7. **`/data` full 与 root hiding**：`/data/adb`、`ubuntu-rootfs` 必须遮罩；否则chroot 内可改 KSU/模块/镜像；普通 App 仍不可见，但 root 可。
8. **loop 可见性**：`/sys/block/loop*/loop/backing_file`、`losetup -a` 可暴露镜像路径；如果 root hiding 是硬指标，需评估 directory rootfs 或隐藏 loop 元数据；当前设计选择 image，必须承认这个代价。
9. **`unshare --uts`**：可用；`IPC_NS` 未编译，已修正；不要再写 `--ipc`。
10. **`uiautomator`**：已修正。
11. **`losetup`**：toybox `-S` 是 sizelimit，不是 sector-size；必须自带 util-linux。
12. **Module update/uninstall**：`customize.sh` 更新不跑 `uninstall.sh`；hot install 可能留下旧进程/镜像；更新前必须 stop + snapshot + verify，卸载保留 data 并 `purge` 显式。
13. **Bootloop**：service.sh 失败/镜像损坏会无限重启；需要 boot counter、last-good、rescue 自动进入。
14. **License**：mini-panel AGPL、chroot-mcp-safe 无 license、APK 专有；只能参考/独立 sidecar，不复制代码。

---

## 架构最优性（红队结论）

- **不可能三角**：真 chroot（需要 root/CAP_SYS_ADMIN）、完美手机控制（需要宿主 Binder/系统上下文）、root 隐藏性（最好没有特权进程/挂载痕迹）三者在本机无 userns/PIDns 下**不能同时最大化**。
- 当前 v1.4 选的是“能力最大化”，不是“隐藏性最大化”。如果 root 隐藏性是硬约束，必须接受以下至少一项：
  1. 不做 full `/data` rbind（directory rootfs + `/sdcard/DSH` only）；
  2. 不做 `/dev` rbind/`/sys` rw；
  3. 手机控制改为宿主侧 agent（Shizuku/ADB/uiautomator）而不是 chroot 内 root shell。
- **不建议**为了“更隐藏”直接换 proot/AVF：
  - proot 不是真 chroot，且 ptrace 可检测；
  - AVF 本机入口/可用性未验证；
  - DockRoot/Docker 与真 chroot 目标冲突。
- **建议的折中**：保留 full 能力，但做 P0-1/P0-2 的遮罩、`/sys` ro、停止/快照/回滚；手机控制用 **宿主侧 thin agent** 补 UI 自动化/通知/剪贴板；chroot 只做 Linux/DSH 计算。这样比“chroot 内 root 硬做一切”更接近“可用的完美”。

---

---

## 附：Workflow-2 长任务追加（已只读核对）

以下几条已在 `notes/11-design-v1.4.md` 修正，但仍属于 P0 级教训：

| 问题 | 事实 | 修正 |
|---|---|---|
| `/run/dsh-rootfs` 不存在 | 本机 `/run` 不存在（root 是 ro erofs）；`/tmp` 是宿主 tmpfs | 改为 `/tmp/dsh-rootfs`；preflight 检查存在/可写 |
| start 一行式没有 chroot | `/opt/dsh-control`、`/run/s6`、`s6-svscan` 都是 chroot 内路径；宿主没有 | 拆成 host 挂载段 + `chroot "$ROOT" ...` 两段；新增 `dsh-nsinit` holder |
| `nsenter -m` 不换根 | toybox `nsenter` 没有 `-r`；`s6-svc` 在宿主不存在 | 用 `nsenter -t PID -m chroot $ROOT ...` |
| `/proc/*/mountinfo` 路径校验假通过 | 第 5 字段相对该进程 root，不是宿主路径 | 用 `/proc/*/ns/mnt` inode 对比 |
| toybox `flock` 只有 fd 形式 | mksh 的 fd>2 CLOEXEC，`flock -n 9` 报 Bad file descriptor | 用 `mkdir` 原子锁 |
| 没有宿主 s6 | 宿主 `/system/bin` 无 s6 | s6 只在 chroot 内；通过 `chroot $ROOT` 调用 |
| `/apex`/`/linkerconfig` `-o ro` 不生效 | bind 忽略 `ro` 标志；源是 rw tmpfs | `mount -o remount,ro,bind` 并验证 |
| image 工具链未打包 | toybox `cp` 无 `--sparse`；toybox `losetup` 无 `--sector-size` | 模块自带 util-linux `losetup`、e2fsprogs、GNU cp/tar |
| 事故单点机制未证实 | mount(2) 说 remount-bind 只影响该挂载点 | 改为“高度怀疑关联、机制未证实”；禁止所有宿主级测试 |

**这些修正不等于设计已批准**：`/sys`、`/dev`、内部路径遮罩、宿主 agent 等仍需你按 `15-decisions-pending.md` 拍板。

---

## 附：Workflow-3 扩展红队追加（10 角度）

| 严重度 | 发现 | 证据/含义 | 修正 |
|---|---|---|---|
| P0 | `u:r:ksu:s0` 是 KernelSU **permissive + allow all** 域 | KernelSU `kernel/selinux/rules.c`：`ksu_permissive(KERNEL_SU_DOMAIN)` + `ksu_allow(ALL,ALL,ALL)`；全局 `enforce=1` 不约束 chroot | 文档不能再写“SELinux 保证”；要么接受，要么自定义非 permissive 域（复杂） |
| P0 | `/data` 的 rw 别名远多于三个遮罩路径 | `/data/user/0`、`/data_mirror/data_ce/null/0`、`/mnt/pass_through/0/emulated`、`/mnt/installer/0/emulated`、`/mnt/androidwritable/0/emulated`、`/mnt/user/0/emulated`、`/storage/emulated` 都指向同一 `/data` | 1A 三路径不够；要么不 full `/data`，要么枚举所有别名并测试；root 仍可 `unmount` mask / `/proc/1/root` |
| P0/P1 | `/apex`、`/linkerconfig` 的 `remount,ro,bind` 不递归 | `/apex/com.android.conscrypt/cacerts` 仍是 rw tmpfs；`-o ro` 被 bind 忽略；remount-bind 机制未证实 | 用 `mount_setattr(AT_RECURSIVE)`（util-linux/mount 支持）或如实写“顶层只读、子挂载 rw” |
| P1 | `/storage` 只挂了 `/storage/emulated/0` | `/storage/self`、其他用户视图缺失；`/storage` 是 tmpfs root `/user/0` | rbind 整个 `/storage` 或显式绑 self/emulated/用户 |
| P1 | `/tmp/dsh-rootfs` 未创建 | 本机 `/run` 不存在，`/tmp` 是 tmpfs，但 `/tmp/dsh-rootfs` ENOENT | start 前 `mkdir -p/chmod 700`；doctor 检查 |
| P1 | `getenforce` 结果不稳定 | 本机 root 一次 Disabled、一次 Enforcing；app Permission denied；`cat enforce=1` | doctor 只读 `enforce` + 域状态 |
| P1 | `selinux_hide` 不 fake `enforce` | 源码只 hook `SEL_STATUS`/context/access；`SEL_ENFORCE` 是独立 inode；permissive 更可见 | 不把 `selinux_hide` 当 permissive 隐藏；`--permissive` 是检测破坏 |
| P1 | `/sdcard/DSH` 2777 + approval-free 是 app→root 路径 | 任意存储 App 可写脚本，DSH 可执行；hook 看不到脚本内容 | 工作区专用组/UID，或 root-only 执行目录；不要 root 执行 world-writable |
| P1 | `mount_hide`/`kernel_umount` 不隐藏 loop/mount | 只过滤 app `/proc` mount 记录；不隐藏 `/sys/block/loopN`、`/proc/partitions`；chroot 不是 zygote | 不把它们当 chroot 隐藏；单独做 app-UID mount 扫描 |
| P1 | `purge` 不删 `/data/media/0/ubuntu-rootfs` | 镜像/快照留下，`ubuntu-rootfs` 仍是 root artifact | purge 删除 `ubuntu-rootfs` + 镜像/快照 + loop 校验 |
| P1 | 无宿主 watchdog | v1.4 只写“watchdog 恢复”，没有组件；permissive 崩溃后无人恢复 | 实现独立 30s watchdog + boot enforce 断言 |
| P2 | `uiautomator`/`am`/`pm` 多数只 help-only | `uiautomator dump` rc=137；`am start`/`pm install` 写操作未验证 | capability matrix + 负例测试；不要写 perfect |
| P2 | PIF Detector README 只是二手证据 | 不是 Google Play Integrity 官方；不能证明“无 SELinux check” | 官方文档 + 实际检测；标注未验证 |

## 结论

- **v1.4 不是“没问题”**：P0-1（镜像/控制面自引用）、P0-2（`/sys`/hardguard 不是边界）、P0-3（stop 竞态）、P0-4（快照）、P0-5（手机控制未验证）必须先修。
- 不建议现在开始写 apply/mount 代码；先做 **P0 静态骨架 + 修正设计 v1.5**，在备用机/VM 上按安全协议验证，再上主力机。
- 我先前派出的长任务还在队列；短任务已给出上述反驳。未回传的内容不会当作结论。
