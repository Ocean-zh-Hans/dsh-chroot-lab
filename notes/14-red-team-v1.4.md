# v1.4 红队审查（2026-10-26）：不能直接开工

- 审查对象：`notes/11-design-v1.4.md`
- 参与：6 个长任务红队（挂载/隐藏/控制/可靠性/实现/安全）+ 10 个扩展角度（内核、隐藏深挖、Android 15、先例、可靠性、实现、最优性、实验安全、控制完整性、许可证）+ 4 个短上下文反驳。长任务队列尚未全部回传；以下是**已返回的短任务 + 独立复核**，不会把未回传内容当成结论。
- 结论：**v1.4 的方向成立，但现在不能开工 apply。** 至少 5 个 P0 必须先修；其中“rootfs 镜像被自己 rw 暴露”和“/sys rw + hardguard 不是边界”是硬伤。

---

## P0-1 rootfs 镜像与chroot 内 `/data` 自引用

**问题**
- v1.4 把 `/data` rbind rw 进chroot 内，同时 rootfs 镜像在 `/data/media/0/.dsh/rootfs.img`。
- chroot 内（root）可以写/删/改名自己的 backing file；一旦 truncate/rename/unlink，loop 仍持有旧 inode，ext4 元数据与宿主 f2fs 都可能损坏；重启/快照/卸载逻辑也会找不到镜像。
- 0700 只能挡普通 App，挡不住 chroot 内的 root；`/sdcard` 侧也可能看到 `.dsh`。

**修法**
- 在chroot 内里 **遮住内部路径**（不是遮用户数据）：
  - `/data/adb/chroot`（control/state/rootfs/versions）→ `mount -t tmpfs tmpfs`
  - `/data/media/0/.dsh`（rootfs.img）→ `mount -t tmpfs tmpfs`
  - `/sdcard/.dsh` → `mount -t tmpfs tmpfs`（如果 `/sdcard` 是 raw bind）
- 宿主 `dsh-ctl` 仍通过 `/data/...` 访问；chroot 内看不到。
- 或者把镜像放到 `/data/adb/dsh/` 并同样遮罩；关键是**chroot 内不能写自己的 backing file**。
- 这不是权限收束，是完整性：只隐藏内部控制面/镜像，其他 `/data` 仍全开。

**验收**：chroot 内 `ls /data/media/0/.dsh`、`ls /data/adb/chroot` 为空/不可见；`stat` 不能改镜像；宿主仍可 `dsh-ctl snapshot`。

---

## P0-2 `/sys` rw + `/dev` rbind + root-free，hardguard 不是边界

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
- 在文档中明确：**root-free + root 权限 + 无 userns/PIDns 不可能保证“root 隐藏性绝对不降”**；只能保证不改全局 props、不 remount selinuxfs、不全局挂载、默认 Enforcing。

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
5. **`/sys/fs/selinux` remount**：事故根因；设计已删 bind-ro，但**任何“只读 bind”都不要做**；只允许 private ns 的 tmpfs 遮罩。
6. **`getenforce`**：本机返回 Disabled 但 `enforce=1`；doctor 只读 `/sys/fs/selinux/enforce`；不要把 `getenforce` 写进验收。
7. **`/data` full 与 root hiding**：`/data/adb`、`.dsh` 必须遮罩；否则chroot 内可改 KSU/模块/镜像；普通 App 仍不可见，但 root 可。
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

## 结论

- **v1.4 不是“没问题”**：P0-1（镜像/控制面自引用）、P0-2（`/sys`/hardguard 不是边界）、P0-3（stop 竞态）、P0-4（快照）、P0-5（手机控制未验证）必须先修。
- 不建议现在开始写 apply/mount 代码；先做 **P0 静态骨架 + 修正设计 v1.5**，在备用机/VM 上按安全协议验证，再上主力机。
- 我先前派出的长任务还在队列；短任务已给出上述反驳。未回传的内容不会当作结论。
