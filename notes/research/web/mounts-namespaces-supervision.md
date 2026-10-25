# W3 运行时：挂载传播、清理、PID 与监督

- 核验日期：2026-10-26 ｜ 等级：A（man7 / kernel doc / s6 官方文档）+ B（KernelSU 源码转述）

## 1. mount namespace 与传播

- unshare(CLONE_NEWNS) 复制父命名空间的挂载；之后新挂载是否传播回父，取决于每个挂载的 **shared / slave / private** 属性（man7 mount_namespaces）。
- 容器惯例：进入新 ns 后立刻 **`mount --make-rprivate /`**（MS_REC|MS_PRIVATE），把整棵树设为私有，避免把容器内挂载传播到宿主。
- **bind 与 rbind 的区别**：MS_BIND 只挂目录本身，**不带子挂载**；要连子挂载一起带，用 **MS_REC|MS_BIND（--rbind）**。所以 /dev、/sys 这类树必须 rbind。
- **父先子后**：先挂父目录再挂子目录；否则后挂的父会遮蔽先挂的子（chroot-distro 的 bindings 头也明写这条）。卸载时反过来：先子后父。

## 2. locked mount 与 umount 的 EINVAL

man7 mount_namespaces 原文（要点）：
> Mounts that come as a single unit from a more privileged mount namespace are locked together and may not be separated in a less privileged mount namespace... an attempt to umount(2) the mount fails.

- 由**更高特权命名空间**带过来的一批挂载是"锁在一起"的，不能单独 umount；对它们 umount2 返回 **EINVAL**。
- umount(2) 的 EINVAL 还有另一种含义：**目标不是本命名空间里的挂载点**。
  → 所以看到 EINVAL 要区分：①不在本 ns（最常见，尤其是"死掉的 namespace 留下的挂载"）②locked。
- umount(2) 其它错误：**EBUSY**（还忙）、**MNT_DETACH**（lazy，立即从视图移除但等引用释放；会**掩盖** EBUSY，排障时不要一上来就用）。
- 实践：**只清理自己在本次 ns 里创建的挂载**；别人的挂载（含死 ns 残留）不要硬拆，必要时在它上面叠一层（叠前先把被覆盖的挂载设为 private，否则新挂载会传播出去）。

## 3. 没有 PID namespace 的后果

- 没有 PID ns 时，**double-fork 的守护进程会被 reparent 给最近的 subreaper，或宿主 init**（PID 1）——监督器**不再是它的父进程，wait 不到它**。
- 对策 A（推荐）：**服务必须前台运行**（不 daemonize）；需要 daemonize 的程序用 `fghack` 之类包一层。
- 对策 B：自己写 launcher 时，在启动子进程前调用 **`prctl(PR_SET_CHILD_SUBREAPER, 1)`**：
  > A subreaper fulfills the role of init(1) for its descendant processes... when an orphan terminates, it is the subreaper process that will receive SIGCHLD and will be able to wait(2).
  注意：**subreaper 属性不被 fork 继承**（子进程要自己设）；在 exec 后保留。
- 僵尸：如果自己是 subreaper，必须 `waitpid(-1)` 循环回收，否则僵尸堆积。

## 4. 监督器选型

| 方案 | 结论 |
|---|---|
| **s6-supervise** | ✅ 选它。每服务一个进程，读服务目录里的 `run`；**没有 `./down` 文件就拉起**，死了自动重启；`s6-svc -d/-u/-t/-k` 控制；可选 `notification-fd` 做就绪通知；C 实现、无依赖 |
| runit (runsv) | ✅ 等价方案（`down` 文件、`run`、`finish`），Debian 也能装；功能足够，文档略少 |
| systemd | ❌ 需要 PID 1 + cgroup 容器接口；无 PID ns 的 chroot 里不可行 |
| supervisord | ❌ 需要 Python；体积与依赖都不划算 |
| shell while 循环 | ⚠️ 能跑，但没有就绪通知、没有退避、容易和 stop 竞态；只作 fallback |

## 5. 开机启动（KernelSU 侧）

- `service.sh` 由 magiskd/ksud **同步等待**（B 级：KernelSU `bootstages.rs`，见 research/chroot-supervision-… 笔记）；
  → 脚本里**禁止长循环**，必须立刻 return；用 `setsid` + 重定向 fd 转后台。
- 后台 bootstrap 要等：**`sys.boot_completed=1`** 与 **`/data/media/0` 可见**（用户 0 解锁）；两者都满足后再解压/启动。
- 解压用「new → 校验 → mv 交换 → 删 old」，失败不动现有 rootfs。

## 6. 停止状态机（设计）

1. 写 `state/stopped`（等价 s6 的 `down` 文件），阻止 watchdog/监督器复活；
2. `s6-svc -d` 或向**进程组**发 TERM（不用 pkill 名字，名字匹配只有 15 字符）；
3. 等真实退出：用 pid + `/proc/<pid>/stat` 的 starttime 校验（PID 会复用）；
4. 超时后 KILL（先 TERM，等 5-10s，再 KILL）；
5. **逆序 umount**（先子后父），遇到 EBUSY 先查 `/proc/*/mountinfo` 谁还占着；
6. 校验 `/proc/self/mountinfo` 里已无我们的 rootfs 路径；
7. 删 lock/状态，保留日志。

## 7. 结论（写进设计）

- 每次启动：`unshare -m` → `mount --make-rprivate /` → 按"父先子后"挂载（/dev rbind、/proc、/sys、/system:ro、/data/media/0 → /sdcard、state）。
- 监督：s6-supervise 管 `dsh` 和 `ttyd`，服务目录放 `/data/adb/chroot/etc/s6/<svc>/run`；用 `down` 文件做停。
- 停止：不依赖名字/端口；用 pid + starttime + 进程组；逆序卸载并校验。
- 若自研 launcher：设 `PR_SET_CHILD_SUBREAPER` 并 `waitpid(-1)` 收尸。
