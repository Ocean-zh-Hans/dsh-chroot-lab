# chroot 进程监督、挂载清理与启停语义（联网调研）

> 适用：OnePlus / Android 15 / kernel 6.6.66（本机 `uname -r` 实测），root 域 chroot，内核未编 user ns / PID ns，SukiSU（KernelSU 系）。
> 方法：只读联网研究 + 本机只读命令；未安装、未改系统、未执行任何安装脚本或 root 模块。无一手依据的推断一律标 **未验证**。

## 结论先行（TL;DR）
1. **监督器选 s6-supervise（次选 runit runsv）**：C 实现、无解释器依赖、直接 fork 并持有子进程，所以根本不需要 pid 文件；s6-svc 官方原话是让人「不再需要 .pid 这种可怕的 hack」。体积：Alpine aarch64 s6 装后 4.1 MiB、runit 592 KiB（Debian arm64 runit 约 1.0 MB）。
2. **没有 PID ns 是最大约束**：服务一旦 double-fork 就被 reparent 给真正的 init(1)，监督器再也 wait 不到它；所以被监督进程必须是前台进程，daemonize 的程序要用 fghack 思路把管道占住。
3. **挂载前先 `unshare -m` + `mount --make-rprivate /`**：切断共享传播，chroot 内的 mount/umount 不会回灌宿主；util-linux ≥2.27 的 unshare 默认已设 private。
4. **停止顺序固定**：置 stopped → TERM 进程组 → 等真正退出（带超时）→ 逆序 umount（子先父后）→ 删 pid/state/lock。不按端口/进程名杀，也不用 `umount -l` 掩盖「还有进程在用」。
5. **service.sh 只做两件事**：等开机完成，然后 setsid 转后台交给自己的监督器；长循环不能写在 boot 脚本里。
6. **MNT_LOCKED 在本环境基本不会出现**：它是「跨特权 mount ns 继承」的产物，官方例子必须用 user ns 构造；本环境更常见的 EINVAL 是「目标根本不在当前 mount ns，因此不是挂载点」。

## 1. 进程监督选项对比

| 方案 | 体积/依赖 | 崩溃恢复 | 本环境结论 |
|---|---|---|---|
| shell 轮询循环 | 0 | 只能 wait 直接子进程；sleep 粒度；无状态机 | 仅适合「服务不 fork」的兜底 |
| s6 | Alpine aarch64 装后 4.1 MiB；C，无解释器依赖 | s6-supervise 重新 fork ./run，可配 ./finish；已有实例在管同一服务则退出 100 | **推荐** |
| runit | Alpine 592 KiB；Debian arm64 装后约 1.0 MB | runsv 重启 ./run，立即退出等 1s；supervise/pid 状态 + service/down | 最小可用 |
| supervisord | 需要 Python 运行时（官方安装页只有 pip / 无 pip 两条路） | 有 autorestart、事件、XML-RPC | 手机 chroot 不值；精确体积 **未验证** |
| systemd | Debian arm64 包自身装后约 12.9 MiB，另有大量依赖 | cgroup 追踪 + KillMode=control-group 全组清理，最可靠 | **本环境不现实** |

体积与安装页来源：s6 https://pkgs.alpinelinux.org/package/edge/main/aarch64/s6 ；runit https://pkgs.alpinelinux.org/package/edge/community/aarch64/runit 与 https://packages.debian.org/bookworm/runit ；systemd https://packages.debian.org/bookworm/systemd ；supervisord https://supervisord.org/ 、https://supervisord.org/installing.html 。

systemd 在 chroot-on-Android 不现实的一手理由：
- systemd(1) 自我定位是「When run as first process on boot (as PID 1), it acts as init system」（https://www.freedesktop.org/software/systemd/man/latest/systemd.html ）；源码里 console 初始化与若干开关显式要求 `getpid()==1`（https://github.com/systemd/systemd/blob/main/src/core/main.c ）。没有 PID ns，chroot 里的进程永远成不了 PID 1。
- 它要求容器接口先把 /proc、/sys、/sys/fs/selinux（只读）、/dev tmpfs 与设备节点备好；cgroup 要么整棵预挂、要么留给 systemd 自己挂（https://systemd.io/CONTAINER_INTERFACE/ ）。Android 的 cgroup 由 init 管理，能否让 chroot 里的 systemd 接管 **未验证**。
- 构建/运行门槛：kernel ≥5.10（≥5.14 推荐）、glibc ≥2.34 或 musl ≥1.2.6、libmount ≥2.30（https://github.com/systemd/systemd#requirements ）。
- 「系统模式在非 PID 1 下是否直接拒绝启动」**未验证**（只确认多处行为要求 PID 1）。

没有 PID ns 对监督模型的直接影响：
- 孤儿 reparent 规则：没有 subreaper 时交给 init(1)；子进程若 double-fork，收养它的是 Android 的 init 而不是你（https://man7.org/linux/man-pages/man2/PR_SET_CHILD_SUBREAPER.2const.html ）。自研 launcher 可以先 `prctl(PR_SET_CHILD_SUBREAPER, 1)`，让 double-fork 的孙进程仍 reparent 到自己、可 wait 可 kill（kernel ≥3.4，本机 6.6 可用）。
- daemontools 的 fghack(8) 就是官方「anti-backgrounding tool」：给子进程挂一堆描述符到管道，子进程后台化后管道不关，fghack 就不退出，监督器才能继续把它当直接子进程（https://manpages.debian.org/unstable/daemontools/fghack.8.en.html ）。
- s6-supervise 要求 ./run 是长生命周期前台进程（原文 `should not die`）；s6-svc 只按服务目录发命令，不必知道 PID，官方称因此不需要 .pid 文件（https://github.com/skarnet/s6/blob/master/doc/s6-supervise.html 、https://github.com/skarnet/s6/blob/master/doc/s6-svc.html ）。
- runsv(8)：./run 退出就重启，若立即退出先等 1s；service/down 文件存在则不启动 ./run（https://manpages.debian.org/bookworm/runit/runsv.8.en.html ）。

## 2. 挂载卫生

- **`mount --make-rprivate /` 的作用**：把该挂载点及其下所有挂载改成 private（https://man7.org/linux/man-pages/man8/mount.8.html ）；private 不转发也不接收传播，而 shared 挂载的 mount/umount 会传播给整个 peer group（https://docs.kernel.org/filesystems/sharedsubtree.html ）。新 mount ns 会继承父 ns 的传播类型，若宿主 / 是 shared，chroot 内的挂载事件会回灌宿主。
- **bind「父必须先于子」的原因**：bind 只复制单个挂载、不含子挂载，要整棵子树必须 `--rbind`；而后挂的挂载会让目标路径原内容（包括已经挂好的子挂载）become invisible（同上 mount(8)）。所以顺序是 rootfs（最好一次 `--rbind`）→ 再往里面挂 proc/sys/dev/tmpfs；卸载严格反过来（子先父后），否则带子挂载的父挂载 EBUSY（https://man7.org/linux/man-pages/man2/umount.2.html ）。
- **`umount -l`（MNT_DETACH）的风险**：官方语义是「立即对新访问不可见、与挂载表及其下所有挂载断开，真正的卸载等最后一个引用消失」（umount(2)，同上）。它会返回成功但底层 fs/loop 仍被占用（EBUSY 被掩盖），findmnt 看不到，之后再 umount 同一路径只会拿到 EINVAL「not a mount point」——问题被藏起来更难查。只当最后手段，并记录内核 MNT_UMOUNT 状态（https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/include/linux/mount.h ）。
- **umount2 返回 EINVAL 的两种成因**（先分清再动手）：(a) 目标在别的 mount ns 里，从本 ns 看只是普通目录，umount(2) 报 EINVAL「target is not a mount point」，典型就是 per-launch unshare 建的挂载；(b) 目标带 MNT_LOCKED，跨特权继承的挂载整组锁定，内核 `if (mnt->mnt.mnt_flags & MNT_LOCKED) return -EINVAL;`（https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/fs/namespace.c ）。官方 man page 的锁定例子用 `unshare --user --map-root-user --mount` 构造，并明确**允许在锁定挂载之上再叠一层新挂载**（https://man7.org/linux/man-pages/man7/mount_namespaces.7.html ）。
- **「死 mount namespace 留下挂载」这个说法要修正**：ns 在最后一个进程退出/离开时销毁，其挂载随之消失；只有被引用（打开的 ns fd、bind 的 ns 文件等）才被 pin 住（https://man7.org/linux/man-pages/man7/namespaces.7.html ）。而无 user ns 时不满足 MNT_LOCKED 的「跨特权」前提，本环境更常见的是 (a)。以上是基于官方条件的推断，**未验证**（未在设备上复现）。
- **per-launch `unshare -m` vs 长驻 holder**：per-launch 每次启动一个私有 ns，最后一个进程退出即自动回收全部挂载，天然无残留；代价是外部看不见也清不掉，诊断/清理必须在 ns 内做（nsenter 进 /proc/<pid>/ns/mnt）。holder 一次 unshare 并保活，后续用 `nsenter -t <holder> -m` 进入，挂载可跨服务重启复用、由 holder 统一回收；代价是 holder 一死整棵 ns 消失、状态跨启动泄漏、单点。建议默认 per-launch，只有需要共享昂贵挂载时才用 holder，且 holder 必须是被监督的前台进程。

## 3. 停止语义

- **不按端口/进程名杀**：pkill 的进程名匹配只取 /proc/pid/stat 里的 15 个字符（https://man7.org/linux/man-pages/man1/pkill.1.html ；proc_pid_stat(5)：comm 超过 TASK_COMM_LEN=16 会被截断，https://man7.org/linux/man-pages/man5/proc_pid_stat.5.html ），同名会误杀；start-stop-daemon(8) 也提醒未指定 --pid/--pidfile 时行为类似 killall(1)（https://manpages.debian.org/bookworm/dpkg/start-stop-daemon.8.en.html ）。端口只是资源不是身份，查找→kill 之间有 TOCTOU 窗口；「按端口杀出事故」没有权威文档，属设计推理（**未验证**）。
- **PID 文件 + 启动时间校验**：写 pid 时同时记 /proc/<pid>/stat 第 22 字段 starttime（自开机的 clock tick 数，proc_pid_stat(5) 同上），停止前重读比对，不同即 PID 已复用；注意 comm 字段可能含空格/括号，解析要从最后一个右括号之后开始。更现代的做法是 pidfd_open(2)（内核 ≥5.3）：官方说明子进程即使已终止，PID 也不会被复用、fd 指向 zombie（有前提条件，https://man7.org/linux/man-pages/man2/pidfd_open.2.html ）。
- **正确顺序**：① 落 stopped 状态并拿锁 → ② 向进程组发 TERM（kill(2)：负 pid = 整个进程组，服务应先 setsid 独立成组，https://man7.org/linux/man-pages/man2/kill.2.html ）→ ③ 等它真正退出（s6 用 `s6-svwait -d -t <ms>`，官方强调只等通知、从不轮询，https://github.com/skarnet/s6/blob/master/doc/s6-svwait.html ；systemd 是 SIGTERM 后等 TimeoutStopSec 再 SIGKILL，https://www.freedesktop.org/software/systemd/man/latest/systemd.kill.html ）→ ④ 仍不退才 KILL → ⑤ 确认无进程再占用（扫 /proc/*/root、fuser -m）后**逆序 umount** → ⑥ 最后删 pid/state/lock。
- 挂载与卸载是一对堆栈：挂载父→子，卸载子→父；`umount -R` 这类递归卸载不属于可控停止，不建议。

## 4 开机自启的标准写法

- Magisk 官方：post-fs-data **BLOCKING**（开机暂停，总 40s、脚本 35s 上限）；late_start service **NON-BLOCKING**，是官方推荐阶段；等开机完成官方给的是 `resetprop -w sys.boot_completed 0`（https://github.com/topjohnwu/Magisk/blob/master/docs/guides.md ）。
- KernelSU 同口径（post-fs-data 阻塞、10s 上限；late_start 非阻塞），另多出 boot-completed.sh / post-mount.sh 阶段；其文档还提醒热更新时旧 daemon 不会被自动停掉，要自己先 stop（https://kernelsu.org/guide/module.html ）。
- **为什么 service.sh 不能跑长循环**：官方只承诺 late_start 相对「开机流程」非阻塞，脚本本身仍是 magiskd 同步执行的子进程（源码 bootstages.rs：`late_start()` 先 `exec_common_scripts(service)`、再模块脚本，最后才记 LateStartDone，https://github.com/topjohnwu/Magisk/blob/master/native/src/core/bootstages.rs ）。脚本不返回，这个阶段永远不结束，还占着 magiskd 的子进程槽和继承来的 fd；执行用 `fork_no_orphan`，其是否 PR_SET_PDEATHSIG 绑父进程生命周期 **未验证**。结论：脚本立刻返回，长生命周期交给你自己的监督器。
- 推荐骨架：

```sh
#!/system/bin/sh
MODDIR=${0%/*}
[ -x "$MODDIR/bin/start.sh" ] || exit 0
(
  resetprop -w sys.boot_completed 0                 # 官方推荐的开机完成等待
  exec </dev/null >>"$MODDIR/service.log" 2>&1      # 关掉继承的 fd，避免占住调用方管道
  exec setsid "$MODDIR/bin/start.sh"                # 新会话/新进程组，进入自己的监督器
) &
exit 0
```

- setsid 是关键：新会话 + 新进程组，之后可以整组 TERM（kill(2) 负 pid 语义），也不会被 boot 脚本所在会话的 HUP 带走。Android 15 本机 toybox 提供 setsid/nsenter/flock/timeout/unshare/nohup，/system/bin/setsid 与 nsenter 是 toybox 符号链接（本机只读实测）；但模块内 PATH 实际用哪个 busybox/toybox **未验证**。
- KernelSU 有 boot-completed.sh 时用它比 service.sh 更贴切；Magisk 没有这一阶段。

## 5. 崩溃恢复：watchdog 与 stop 的竞态

- 原则：**同一时刻只有一个状态写者**。start / stop / watchdog 三条路径都先抢同一把锁（`mkdir` 的 EEXIST 语义可做原子抢占，https://man7.org/linux/man-pages/man2/mkdir.2.html ；有 flock 就用 flock），锁内先读状态再决定动作。
- **停止意图必须是持久化状态，并且在发 TERM 之前写入**（temp+rename 原子替换）。watchdog 只在「没有 stopped 标志、且成功拿到锁」时才允许拉起。
- 上游先例可直接抄语义：runit 的 service/down 存在时 runsv 不启动 ./run（runsv(8)）＝ 磁盘上的 stopped 标志；s6-supervise 发现已有另一个自己在管同一服务就退出 100（隐式锁）；systemd 的 `Restart=` 对被 `systemctl stop` 显式停止的服务不生效（https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html ）＝显式停止抑制自动重启。
- 兜底：stop 在真正 umount 之前再校验一次目标 pid/starttime 是否已消失，避免 watchdog 抢跑拉起的新实例被误拆、或旧 umount 干扰新实例。

## 未验证 / 不确定项

- systemd 系统模式在非 PID 1 下是否**直接拒绝启动**（未找到官方明文；只确认多处行为要求 PID 1）。
- Android 侧 SELinux（magiskd / ksu 域）对 cgroup 写入、mount 传播、setns 的实际限制；以及无 user ns 时 MNT_LOCKED 是否真的不会出现（推断为不会）。
- supervisord 在 arm64 chroot 的实际安装体积与 Python 版本要求。
- Alpine s6 的 4.1 MiB 是否已含 s6-rc / skalibs 等全部依赖（包页面依赖未展开）。
- 「按端口杀」的具体事故案例、Magisk `fork_no_orphan` 是否设置 PR_SET_PDEATHSIG：均无一手依据。
- 本机 toybox applet 列表来自 Termux 用户态下调用 /system/bin/toybox；root 模块实际 PATH 可能不同。

## 主要一手来源

- man-pages：mount(8) / umount(2) / mount_namespaces(7) / namespaces(7) / kill(2) / pidfd_open(2) / prctl(2) / pkill(1) / proc_pid_stat(5) / mkdir(2)，https://man7.org/linux/man-pages/
- 内核：https://docs.kernel.org/filesystems/sharedsubtree.html 、https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/fs/namespace.c
- s6：https://github.com/skarnet/s6/tree/master/doc （s6-supervise / s6-svc / s6-svwait）；runit：https://manpages.debian.org/bookworm/runit/runsv.8.en.html
- systemd：https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html 、systemd.kill.html、systemd.html ；https://systemd.io/CONTAINER_INTERFACE/
- Android：https://github.com/topjohnwu/Magisk/blob/master/docs/guides.md 、https://kernelsu.org/guide/module.html
