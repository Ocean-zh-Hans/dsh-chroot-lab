# 分析：sabamdarif/chroot-distro

- 来源：`sources/sabamdarif_chroot-distro`（commit `5fac337`，2026-10-24，v3.2.0）
- 许可：**GPL-3.0-only** ｜ 依赖：Python 3.10+，**纯标准库** ｜ 前提：**假定 root**
- 定位：不依赖 Docker/Podman 的 Linux 容器管理器：下载 OCI/Docker 镜像、构建、备份、diff、真 chroot 运行
- 一句话评价：**质量远高于其他项目；它把 Android 真 chroot 的坑逐条编码成了可复用策略，是我们最该"当依赖用"的轮子，而不是重写的对象。** 但它是通用容器管理器，多容器/OCI/build/display 这些我们一台机一个环境用不上。

---

## 1. 问题与约束（来自 AGENTS.md 的硬要求）

- **纯 Python，不 exec 二进制**：`mount(1)`/`umount(1)`/`chroot(1)`/`unshare(1)`/`nsenter(1)` 全部在 `syscalls/` 里用 ctypes/libc 重新实现。唯一例外是提权（`elevate.py` 的 su/sudo/doas/pkexec）。
- **无第三方运行时依赖**：stdlib only（3.14 以下用 `backports-zstd` 补 tarfile 的 zstd）。
- **双平台等价**：Termux(rooted Android) 与普通 Linux 都是正式目标。
- **假定 root**：提不上去就报错，不退化成非特权模式。
- 容器语义：真 chroot + mount + namespace，**不靠 ptrace**。

## 2. 运行模型

三层依赖：`commands/`（CLI 行为）→ `helpers/`（策略与编排）→ `syscalls/`（原始内核调用）。

进入容器：构造 `ChrootConfig`（`commands/login/chroot_cmd.py`），由子进程自己完成 setns → 丢弃 capabilities → `chroot` → exec。**没有任何一步 exec 外部工具**。

隔离分三档（差异在 namespace 与 host bind，不在伪文件系统）：

| 档 | 行为 |
|---|---|
| 默认 | 共享 host 路径（/data、home、storage），在 host 的 mount namespace 里挂 |
| `CD_USE_NS` | 打开 namespace，但挂载集合与默认相同 |
| `--isolated` / `CD_USE_ISOLATION` | 最大隔离：**不 bind 任何 host 路径**，chroot holder |

**每一档都挂**：fresh tmpfs `/dev`（最小节点集 null/zero/full/random/urandom/tty）、fresh 只读 sysfs、fresh procfs、newinstance devpts、fresh `/dev/shm`（runc/moby 的默认形态）；内核或 SELinux 拒绝 fresh mount 时回退为 host bind **并警告**——源码明确说这是 failure handling，不是一种模式。

**永不 bind 的三个 host 路径**（`commands/login/bindings.py` 头）：
- `/proc`：改用 fresh procfs
- `/run`：`--shared-display` 只 bind 它需要的 socket
- `/dev/ptmx`：bind host 的 devpts 会**耗尽 host 的 pty 池**（他们踩过），改由 newinstance devpts 提供

**bind 列表顺序有语义**：父（`/data`、`$PREFIX`）必须排在子之前，否则后挂的父会遮蔽已挂的子，使它们不可达并且破坏卸载扫描。

**Android 相关 bind**（`system_bindings()`、`storage_bindings()`、`dalvik_cache_bindings()`、`termux_app_bindings()`）：
`/apex`、`/odm`、`/product`、`/system`、`/system_ext`、`/vendor`、`/linkerconfig/*.ld.config.txt`、`/plat_property_contexts`、`/property_contexts`、`/storage` 与 `/storage/emulated/0`。其中一部分列进 `best_effort_bind_sources()`：某些 vendor 内核拒绝单文件 bind（EINVAL），失败只警告不中止。

**masked / read-only 表**照抄 docker 默认：`masked_paths()`（外加每个 CPU 的 thermal_throttle）、`READONLY_PATHS`（/proc/bus、/proc/fs、/proc/irq、/proc/sys、/proc/sysrq-trigger）。

**能力探测**（`helpers/namespace.py` 头）：
> "Support is established by attempting it, never by a kernel version, because a CLONE_NEW* flag depends on the kernel config, the sysctls and the caller's privileges."

CLONE_NEWUSER 即使真 root 也会探测（identity-mapped 子 userns 仍能把 caps 从 host 资源上隔开）；但"存在"不等于"可用"：它在 disposable holder 里做真实 mount 冒烟测试，失败就降级并留下 `userns_mounts_ok` 说明原因。CLONE_NEWNS 是**强制**的，没有它就拒绝运行。

## 3. 生命周期与清理

- **提权**：Termux 无 daemon，每条命令通过 `su` 提权；Linux 用 daemon（`/run/chroot-distro.sock`），失败回退 sudo。
- **holder**：`NamespaceHolder` 是长驻进程，替会话把 namespace 按住（namespace 随最后一个成员死亡）。状态在 `data/<name>/`：pid 文件**附带进程启动时间**（PID 会被复用，对不上就丢弃状态而不是信任它）、flags 十六进制位掩码（2.9 之前是 unshare 风格的字符串，所以反向映射还在）。
- **会话**：`helpers/session.py` 引用计数 + 每容器一个 JSON 的 `session_registry.py`；`ContainerLock`/`BuildLock`/`RunCacheLock`。
- **结束**：`kill`/`unmount` 做有序退出（signal → zero → unmount → `ensure_no_mounts`）。
- **MNT_LOCKED 这个坑**（`helpers/mount_manager.py` 头）：死掉的 namespace 留下的挂载带 MNT_LOCKED，umount2 会以 EINVAL 拒绝；唯一出路是**在它上面叠一个正确的挂载**（`required_child`：/dev bind 用 ptmx；devpts 用 ptmxmode=666 探针）。叠之前必须把被覆盖的挂载设为 private，否则新挂载会把副本传播到 host。
- 深度清理：`_sweep_matching_mounts(marker)`、`_pids_with_container_mounts(marker)`、`deep_clean_container_mounts`。

## 4. 数据、备份与更新

- 每容器 `containers/<name>/manifest.json`（`image_ref`/`arch`/`manifest`/`image_config`）是 `reset`/`run`/`push`/`diff`/guest env 默认值的**真源**。
- `backup`：写成 tar 流，内含 `<name>/manifest.json` + `<name>/rootfs/...`，所以名字与镜像身份随文件走，`restore` 不需要额外参数。保留 numeric ownership（OCI 里非 root USER 的镜像不能被压平）；丢掉 owner/group **名字**（免得目标机 /etc/passwd 没有那个名字）；device/fifo/socket 丢弃（会话自己挂 /dev）。有活跃会话或挂载的容器**拒绝备份**（写到一半的 rootfs 会备份出半一致状态）；读不了任何一项就整体失败；失败或 Ctrl-C 会删掉半成品输出。
- 还有 `restore`/`remove`/`reset`/`diff`（相对原镜像的变更，可打包成 OCI layer）/`sync`/`copy`/`clear-cache`。

## 5. 为什么这样写（证据，全部来自模块 docstring）

1. **挂载目标一律用 fd 解析（`mount_manager.py` 头）**：
   > "the rootfs is untrusted input, and mount(2) resolving a name through a symlink the image planted is the host-write hole the walk refuses."
   这是"镜像/rootfs 内容不可信"的正面处理，比"检查字符串"强得多。
2. **bind 顺序**：见第 2 节，注释写明"后挂的父遮蔽子，并破坏卸载扫描"。
3. **不 bind /dev/ptmx**：注释写明"bind host 的 devpts 泄漏状态并耗尽了 host 的 pty 池"。
4. **能力丢弃**（`syscalls/capabilities.py` 头）：
   > "Without a user namespace, container root *is* host root, so the bounding set is the only thing left between a guest process and the host."
   用 allowlist（`CAPS_TO_KEEP`）从 `cap_last_cap` 读到的范围里丢掉其余；`CAP_SYS_ADMIN` **故意保留**（chroot 里的发行版要 mount/FUSE/guest init），`CAP_SYS_CHROOT` 保留给 Chromium/Electron 的 setuid sandbox helper。`PR_CAPBSET_DROP` 不可逆，所以放在 exec 前一刻；失败只警告不抛错（"起不来比多一个内核拒绝丢掉的 cap 更糟"）。
5. **Android 权限在附加组里**（`helpers/android.py` 头）：
   > "Android carries its permissions in supplementary group ids, so a guest whose /etc/group has never heard of them gets no network, no bluetooth and no audio no matter what it is allowed to do."
   `configure_android_rootfs` 把 `ANDROID_GROUPS`（aid_inet=3003、aid_net_raw=3004、graphics=1003、input=1004、audio=1005、video=1006、wifi=1010、usb=1018…）写进 guest 的 /etc/group，并把 root 加进每个组；Debian/Ubuntu 的 `_apt` 单独处理：把 **aid_inet(3003) 设成它的主组**，并 chown 它的 `var/lib/apt`、`var/cache/apt`。
   两个账号文件都通过 `rootfs.guest_etc_path` 解析而不是拼接：termux-docker 镜像把 `/etc/group`、`/etc/passwd` 做成指向 `/system/etc` 的绝对符号链接，在 host 上会解析到只读文件系统并 EROFS。
6. **`ensure_data_suid`**：清掉 host `/data` 上的 nosuid/nodev/noexec（guest 里的 `sudo` 要 nosuid，`--shared-tmp` 下 apt 的 gpgv 要 noexec）。并且：
   > "a remount replaces the whole VFS flag word, so the flags already in force are read back out of /proc/mounts and only nosuid, nodev and noexec are cleared."
   这句话解释了我们之前遇到的 remount 类怪问题：remount 要给完整的 flag word，文件系统自身的选项（lazytime、seclabel、f2fs 参数）必须原样传回，否则 EINVAL。
7. **每档都 fresh /dev 而非 bind**：避免 host 设备树与 pty 池被 guest 影响。

## 6. 本机实测补充（2026-10-26，PJZ110 / Android 15 / SukiSU，root 读 `/proc/mounts`）

```
/dev/block/dm-74 /data f2fs rw,lazytime,seclabel,nosuid,nodev,noatime,...
/dev/fuse /mnt/user/0/emulated fuse rw,lazytime,nosuid,nodev,noexec,noatime,user_id=0,group_id=0,allow_other
tmpfs /storage tmpfs rw,seclabel,nosuid,nodev,noexec,...
```

结论：
- **`/data` 是 exec 允许的**（只有 nosuid,nodev，没有 noexec）→ chroot rootfs 放在 /data 下天然可执行；之前 Termux 沙箱起不来是 SELinux/Landlock 的事，不是挂载标志。
- **noexec 只在 FUSE（/sdcard）与 tmpfs（/storage）上** → 证实了 dsh-alpine-ksu 绑 raw `/data/media/0` 的做法是正解。
- chroot-distro 的 `ensure_data_suid` 在我们机器上要做的是**清 nosuid,nodev**。但这是一处**全局副作用**：影响整个设备的 /data，而不是只影响容器。
  - 我们的对策：**chroot 内直接以 root 运行**，就不需要 guest `sudo`/suid，从而**不必动 host 的 /data 挂载**。只有确实要在 guest 里用 suid/sudo 时才付出这个代价。

## 7. 更优雅（对我们的用法）

**直接当依赖用，不重写的部分**
- rootfs 安装/下载/校验、挂载与卸载、备份/恢复/diff、会话锁 —— 这些代码有 300+ 处 mount 相关逻辑与多年踩坑记录，重写等于重新踩一遍。
- 用法：`pip install chroot-distro`（Termux 内），或把它当独立工具调用；我们自己的模块只做启动/挂载策略/supervisor/快照/规则/审计。

**值得抄进我们模块的策略（即使不用它）**
- fresh `/dev` + fresh ro `/sys` + fresh proc 而不是 bind host 树（但要按"全能"目标放宽：见下）
- bind 顺序（父先于子）与"卸载扫描依赖顺序"这条约束
- best-effort bind 名单（vendor 内核会 EINVAL）
- Android 附加组注入（否则 guest 没网络/音频）
- remount 要保留原 flags（我们未来若需要清 /data noexec 会用到）
- 能力探测"靠尝试而非看内核版本"
- masked/read-only 路径表

**与我们"全能"目标冲突、需要改的**
- 它的 max isolation 是**不 bind 任何 host 路径**，正与我们要的宽挂载相反。我们应固定在"默认档 + 额外宽 bind"（`/data`、`/system:ro`、`/apex:ro`、raw media），并且**在文档里写清这不是安全边界**（它的 docstring 也是这个态度）。
- 它默认挂载在 host namespace；我们仍建议 per-launch `unshare -m`，避免长驻 holder 与 MNT_LOCKED 那类清理难题（我们只跑一个环境，不需要 holder 长期存活）。
- 它的 backup 是"容器"语义（manifest+rootfs）；我们更简单：数据目录 tar + rootfs 镜像快照（YAGNI）。

**许可证**
- GPL-3.0-only。**用它（安装/调用）没问题**；把它的代码复制进我们自己的 MIT 模块会造成许可冲突。做法：依赖它，或只借鉴策略、自己写实现。

## 8. 对我们的启示

1. rootfs 层直接依赖 chroot-distro v3.2.0（活跃、纯 stdlib、双平台、root 假定），不要自研。
2. 我们的模块 = 启动（非阻塞 service.sh + 幂等入口）+ 宽挂载策略 + 身份（root 或 UID1）+ supervisor + 快照/规则/审计。
3. 若跑 root，可跳过 `ensure_data_suid`，避免改 host /data 挂载；若跑 UID1 且要 sudo，则必须接受这个全局副作用。
4. 状态文件放 `/data/adb/<mod>/`，与 rootfs 解耦（学它的 manifest/rootfs 分离）。

## 9. 待验证

- [ ] 用它的 `probe_unshare_flags` / `probe_and_report_namespaces`（源码在 lab，**不安装**）跑一次，得到本机权威 namespace 能力报告（预期：NEWNS/UTS/IPC 可用，NEWPID/NEWUSER 在 `unshare` 下 EINVAL）
- [ ] `ensure_data_suid` 在本机的实际效果与副作用范围
- [ ] 我们采用 per-launch `unshare -m` 后，是否还会遇到 MNT_LOCKED（预期不会，因为不留死 namespace）
- [ ] 它的 bind 顺序约束在我们放宽的 bind 集合下是否仍成立（父 /data 必须先于 /data/media/0 的子路径）
