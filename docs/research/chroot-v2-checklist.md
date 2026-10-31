# chroot v2 设计与验证检查清单（学习收敛版）

状态：2026-10-30；从 `docs/research/chroot-v2-study.md` 收敛出的可执行检查项，不是新需求；与 `docs/plan-v2.md` 冲突时以 plan 为准。

## A. 读模型（R0）

- [ ] guest root 可读宿主 `/data`、`/system`、`/vendor`、`/product`、`/odm`、`/apex`，不做路径遮蔽/脱敏/权限收缩。
- [ ] `/sdcard`、`/storage/emulated/*`、`/data/media/*` 可读；FUSE 视图与真实路径的差异在文档中说明。
- [ ] `/dev/block/*` 的“读”与“raw write”分开：读可见不等于允许写；R4 只管写。
- [ ] bind mount Android 目录时默认不强行改 `context=`/`fscontext=`，避免破坏原标签；需要兼容时单独开关+记录。
- [ ] 不用 `setenforce`、不写 `selinux.orig`、不因 SELinux 状态阻断 start/stop/doctor。

## B. 写分级（R1–R4）

- [ ] R0：普通新建写、DSH 自有可重建数据，无提示。
- [ ] R1：覆盖用户可见非系统文件；轻提示，AI 可直接 yes。
- [ ] R2：普通 App `/data/user/<id>/<pkg>`、`/data/user_de/<id>/<pkg>`；AI 自行审批但必须给 reason、包名、真实路径、备份/快照、hash、回滚，不能裸 yes。
- [ ] R3：start/stop/restart、镜像/快照/purge/system-rw/recover、`/data/system`、`/data/misc`、系统关键包；两阶段摘要+reason+token，AI 自行审批，不能裸 yes。
- [ ] R4：`/dev/block/*` raw write、`dd`/`mkfs`/`blkdiscard`、分区表、fastboot、boot/init_boot 修改；人工审核，AI 必须先给原因/目标/影响/备份/回滚/替代方案。
- [ ] 每个写操作记录 before/after hash（或快照点）、目标解析路径、owner/mode/SELinux 标签。
- [ ] 禁止无边界 `rm -rf`、禁止 `chmod -R 777`、禁止 `losetup -D`。

## C. 挂载

- [ ] 所有挂载在私有 mount namespace；`mount --make-rprivate /` 在 root 挂载前完成。
- [ ] rootfs 挂载前 `e2fsck -fn`（未挂载镜像）；先只读检查关键文件，再 `remount,rw`。
- [ ] `/proc`、`/sys`、`/dev`、`/dev/pts`、`/dev/shm`、`/run`、`/tmp` 按依赖图挂载；`/run` 在 Android 不存在时先创建。
- [ ] 所有 bind target 用 `realpath`/`openat2(RESOLVE_BENEATH|NO_SYMLINKS)` 验证在 `$DSH_ROOTFS_DIR` 内；rootfs 是用户镜像，按不可信内容处理。
- [ ] `/system`、`/system_ext`、`/product`、`/vendor`、`/odm`、`/apex` 以只读 bind/rbind 进入 guest；默认不写。
- [ ] `/dev/block` 默认 `ro`（**可读、不隐藏**）；`ro` 只是只读视图、不是写保护（审计 P0-1）；`masked` 仅显式可选；`full` 仅 R4 且显式；rescue 模式也不能默认暴露 raw block 写。
- [ ] `/sys` 的 `ro`/`rw` 由配置明确；`/sys/fs/selinux` 默认只读视图或 mask；不通过它判断/修改全局。
- [ ] 卸载按子挂载→根→loop 逆序；`umount -l` 不算完成；卸载后验证 `findmnt`/`mountinfo`/`losetup -a`。
- [ ] 任何“只读”挂载都从 `/proc/self/mountinfo` 检查实际 `ro` 标志，不只信 `mount` 返回码。

## D. 生命周期

- [ ] start 必须显式 `--apply`；stop/restart/recover 有明确影响摘要。
- [ ] state 写入原子（临时文件+fsync+rename）；PID/starttime/ns/boot_id 完整记录。
- [ ] loop attach 后若 `loop.file` 写失败立即 `losetup -d`；start 失败清理 recovery 记录。
- [ ] stop 先 s6 服务、TERM、超时 KILL；cleanup 文件等待有上限；不无限卡。
- [ ] 不依赖 systemd；s6 的 `down`、`notification-fd`、`s6-svwait -U` 正确使用。
- [ ] watchdog 不只在 autostart=1 时启动；手动 start 也要进入监督；residual scan 覆盖所有镜像/快照/备份上的 loop。
- [ ] stop/recover 遇到身份不一致不提前 return，必须保留/恢复必要清理状态；不允许把全局 SELinux 留在 permissive。
- [ ] boot 等待 `sys.boot_completed` + CE 可用；FBE 未解锁不写 `/data/user/*`。

## E. 镜像/快照

- [ ] image create 源路径拒绝 `/`、非 rootfs 结构；size 严格 `^[1-9][0-9]*[KMG]$`；预览打印 size/sha。
- [ ] image install 校验 sha256、fsck、0600；不覆盖 active image；保留 previous image。
- [ ] 快照/恢复前 `require_cold`；恢复先备份当前 image/state。
- [ ] archive 验证拒绝绝对路径、`..`、反斜杠、重复项、设备/FIFO、symlink target 越界；解包用 no-follow（`tar --no-overwrite-dir`/自实现或 `openat2`）。
- [ ] restore/purge 失败路径有回滚；partial state 不能覆盖当前可用状态。
- [ ] 所有镜像/快照路径使用 3-2-1 备份原则；快照不是备份。

## F. 验证矩阵

- [ ] host/mock：`tests/run.sh` 全绿；新增 symlink/loop-record/snapshot-symlink/fsck 退出码用例。
- [ ] 模拟破坏：rootfs `/etc -> host-etc`、`/run -> host-run`、`/dev -> host-dev`、archive `evil -> /etc/passwd`、loop record 写失败、watchdog manual start。
- [ ] 只读系统调用审计：`grep -R setenforce / selinux.orig / --permissive` 为空。
- [ ] mksh/toybox：所有模块脚本 `sh -n`+`mksh -n`；不依赖 GNU `grep -P`/`find -printf`/`sed -i`。
- [ ] 真机前：备份 `init_boot`/`boot`/`persist`/`frp`，记录 slot/AVB/FBE/SELinux/LKM feature set；不在唯一设备上做 R4。
- [ ] 真机验证只做只读盘点 + 可回滚的 start/stop；R4 操作人工执行并旁路记录。

## G. 待定决策

- [ ] `/dev/block` 读可见的粒度：整块只读 bind vs 仅 `/dev/block/by-name` vs 可配置白名单；默认必须可读，不能把 `ro` 当写保护，也不能用 `masked` 取代读全开。
- [ ] `/sys` 默认 `ro` 还是 `rw`；`/sys/fs/selinux` 默认 `ro` 还是 mask。
- [ ] Android APEX/系统分区 bind 是否需要按分区单独开关。
- [ ] R2 的“AI 自行审批”token 由谁签发、有效期、是否绑定包名/路径。
- [ ] R3/R4 的人工确认入口（Console/CLI/通知）与审计日志位置。
