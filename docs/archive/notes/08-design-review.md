> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# 设计评审：v1.2（2026-10-26）

- 评审对象：`notes/05-design-v1.md`（v1.2 定稿）及其配套 `02/03/06/07`、`research/`、`analysis/`
- 评审后的优化设计：**[`11-design-v1.4.md`](11-design-v1.4.md)**（full-capability：单一 full 档 + 完整 Android 控制；v1.3 见 10）
- 评审方法：文档交叉核对 + 第三方源码核对 + **本机隔离实验**（不改系统；实验记录见 `09-p0-evidence.md`）
- 设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 结论先行：**方向正确、命门可跑，但现稿不能直接进入打包。**
  - 已复验通过：真 chroot、Ubuntu glibc、Node 24、`node-pty` PTY、`dsh web --port 0`、s6 监督、apt 装 s6/ttyd、raw media 执行。
  - 必须返工：**8 个 P0**（挂载命名空间/停止语义、宿主/chroot 内路径、工作区权限、payload 压缩、chroot 环境、DSH 工作区、`/dev/block` 与 `/proc/1/root`、钩子 fail-open 与可逆性覆盖）。
  - 最根本的一条：**chroot 内是 root，`/proc/1/root` 就是宿主 `/`；所有“只读挂载/不绑 /dev/block”都只是防手滑，不是安全边界。** 设计里必须把这句话写到醒目的位置，并把“可逆性”从 rootfs 扩展到真正值钱的数据。

---

## 1. 本机复验（新增证据，全部通过/有明确结论）

> 详细命令与输出见 `09-p0-evidence.md`。以下只列结论。
>
> 说明：E1/E2 是把 Termux 的 `node_modules` bind 进测试 rootfs 的最小验证，不是完整 payload 烘焙；但足以证明 glibc + Node 24 + node-pty + DSH web 这条路径可行。模块安装/开机时序、真实 hooks 调用、`root-full` 预设组合仍未验证，见 §11。

| # | 实验 | 结果 | 对设计的含义 |
|---|---|---|---|
| E1 | Ubuntu base 24.04.5 arm64 + chroot + Node 24 + `node-pty` | `PTY_OK`，`tty` 返回 `/dev/pts/2` | **P0 命门通过**；glibc 选型正确；node-pty 预编译可用 |
| E2 | chroot 内 `dsh web --port 0 --no-open` | 打印 `http://127.0.0.1:33561/?token=…`，`curl` 返回 `303` | **P0 命门通过**；动态端口可行；token URL 可解析 |
| E3 | chroot 内 s6 | `s6-svscan` 非 PID 1 可运行；`s6-svstat` 为 `up`；`s6-svc -d` + `s6-svwait -d -t 5000` 返回 0 | s6 选型正确；但设计里的“s6-supervise 管两个服务”表述要改成 `s6-svscan` |
| E4 | Ubuntu base 内 apt | `apt-get update` 成功（37.5 MB）；`s6`/`ttyd` 可安装；`ca-certificates` 在清理环境后成功 | Ubuntu 24.04 universe 有 s6/ttyd；**但 chroot 必须清环境变量**（见 P0-6） |
| E5 | raw `/data/media/0` 工作区权限 | 0777 目录 + 0666 文件：Termux/FUSE 侧可写；root 创建的 2750 目录：`EACCES` | **H2“文件管理器可改”当前会失败**；必须显式设权限/umask |
| E6 | 挂载传播 | `busybox unshare --propagation private -m` 后 `/` 无 `master:`；toybox `mount --make-rprivate /` 返回 1；toybox `unshare -m` 后 `/` 仍是 `master:1` | 设计里的 `mount --make-rprivate /` **在本机 shell 不可用**；改用 busybox `unshare --propagation private -m` |
| E7 | `/apex` 绑定 | `mount -o bind /apex` 后 `com.android.runtime` 内容为 0 项；`mount -o rbind /apex` 后为 6 项 | 设计里的 `mount --bind /apex` **等于绑了个空壳**；必须 `--rbind` |
| E8 | `/dev` rbind | `rbind /dev` 后 `/dev/block` 可见（258 个条目）；`/dev/block` 是 `/dev` 下的普通目录 | 设计“**不 bind /dev/block**”与 `--rbind /dev` **直接矛盾** |
| E9 | `/proc/1/root` | chroot 内 `readlink /proc/1/root` = `/`；`/proc/1/root/dev/block` 有 258 项；`/proc/1/root/data` 有 76 项 | **chroot 内 root 可直接访问宿主根**；所有结构护栏都可绕过 |
| E10 | payload 解压工具 | KSU busybox `tar` 支持 `-z`/`-J`/`-j`，**不支持 zstd**；`busybox --list` 无 `zstd` | `.tar.zst` 在开机脚本里**无法解压**；payload 与快照改 `.tar.gz` 或自带 zstd |
| E11 | chroot 环境变量 | 继承 Termux `PATH` 时 `apt-get: command not found`；继承 `TMPDIR=/data/data/com.termux/...` 时 `ca-certificates` postinst 失败；`env -i PATH=… TMPDIR=/tmp` 后成功 | 必须 `env -i`；这是烘焙阶段的高频坑 |
| E12 | 挂载语法 | toybox `mount --rbind`/`--bind`/`-o rbind` 均 rc=0 | 挂载语法本身可用；问题在传播与目标选择 |

**已确认可用的关键组合**（建议直接作为 P0 基线）：

```sh
# 宿主侧（KSU busybox）
BB=/data/adb/ksu/bin/busybox
$BB unshare --propagation private -m /system/bin/sh -c '
  mount -t proc proc "$ROOT/proc"
  mount -o rbind /dev "$ROOT/dev"
  mount -o rbind /sys "$ROOT/sys"
  exec chroot "$ROOT" /usr/bin/env -i \
    PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/root TMPDIR=/tmp LANG=C.UTF-8 TERM=xterm-256color \
    /bin/bash -l
'
```

---

## 2. 设计判断：哪些是对的（不要动）

1. **Ubuntu 24.04 glibc + 目录型 rootfs**：正确。E1/E2 已证明 Node 24、node-pty、DSH web 都能跑；Alpine 的 musl 补丁链确实不值得。
2. **真 chroot + KSU 域 root + 私有 mount ns**：正确路线。本机 userns/PIDns/Landlock 不可用，proot 没有意义。
3. **raw `/data/media/0` 绑定为工作区**：正确。FUSE `/sdcard` 是 `noexec`；raw 可执行，E5 也证明 raw 路径可写。
4. **模块目录外放数据**：正确。`/data/adb/modules/<id>` 会被整体删除；`/data/adb/chroot` 独立。
5. **s6 监督**：正确。E3 证明非 PID 1 可用；比 shell 轮询稳。
6. **动态端口 `--port 0`**：正确。E2 证明可用；不要自己探测端口。
7. **冷 tar 快照 + 目录 rootfs**：方向正确。f2fs 无 reflink、硬链接会被原地写污染；冷 tar 是可靠兜底。
8. **规则层 + 审计层 + 可逆性层**：方向正确，但实现细节和“覆盖范围”要重写（见 P0-7/P0-8）。
9. **不用 post-fs-data、service.sh 立刻返回**：正确；issue #3693 的证据成立。
10. **不绑模块目录进容器**：正确；避免 KSU 挂载视图穿透。

---

## 3. P0 阻断项（不修不要打包）

### P0-1 私有 mount namespace 与 stop/umount 状态机自相矛盾

**问题**
- 设计 §5.4 每次启动 `unshare -m`，§5.5 又要求 `dsh-ctl stop` 在宿主侧“逆序 umount → 校验 `/proc/self/mountinfo` 无残留”。
- 但 per-launch 私有 namespace 里的挂载，**宿主侧 `dsh-ctl` 的 `/proc/self/mountinfo` 根本看不到**；它也无法直接 `umount` 这些挂载（`EINVAL: not a mount point`）。
- 结果：stop 的“校验”会**假通过**（本来就没有），真正残留的进程/挂载可能没清掉；验收第 4 条“stop 后 `/proc/self/mountinfo` 无残留”没有意义。

**本机证据**
- E6：toybox `unshare -m` 后 `/` 仍是 `master:1`（slave），`mount --make-rprivate /` 返回 1；busybox `unshare --propagation private -m` 才是真正 private。
- E9：chroot 内 root 能看 `/proc/1/root`；同理，宿主 `dsh-ctl` 看不到私有 ns 的 mountinfo。

**修法（二选一，推荐 A）**
- **A. per-launch namespace + “杀命名空间成员”而不是外部 umount**
  1. `setsid` 启动 `busybox unshare --propagation private -m … s6-svscan`，记录 launcher PID、PGID、`/proc/<pid>/stat` starttime。
  2. stop：写 `state/ctl/stopped` → **进入 namespace** 执行 `nsenter -t <launcher> -m s6-svc -d /run/s6/dsh`（`s6-svstat` 同理）→ 等 `s6-svwait -d` → 向 **PGID** TERM → 超时 KILL。
     > 注意：s6 服务目录在私有 ns 的 `/run` tmpfs 里，宿主侧直接 `chroot` 看不到；`s6-svc`/`s6-svstat` 必须 `nsenter`，或把服务目录放宿主可见的 bind 源。
  3. 扫 `/proc/[0-9]*/root` 指向 rootfs 的进程，全部 TERM/KILL；再扫 `/proc/[0-9]*/mountinfo` 是否还有 rootfs 路径。
  4. 最后一个 namespace 进程退出后，内核自动回收挂载；**不需要从宿主 umount**。
  5. 只有确实要保留 holder 时才用 `nsenter -t <pid> -m umount -R /`，并记录。
- **B. 长驻 holder**：holder 保活 namespace；stop 通过 `nsenter` 进入 holder 做逆序 umount。代价是 holder 单点、状态跨启动泄漏；本项目只跑一个环境，不推荐。

**验收**
```sh
# 运行中：从宿主看不到挂载是正常的；要检查 launcher 的 mountinfo
grep -F "$ROOT" /proc/$(cat state/ctl/launcher.pid)/mountinfo | wc -l   # >0
# stop 后：全机扫描，不能只看 /proc/self
grep -lF "$ROOT" /proc/[0-9]*/mountinfo 2>/dev/null | wc -l             # 0
for p in /proc/[0-9]*/root; do [ "$(readlink "$p")" = "$ROOT" ] && echo "$p"; done  # 空
```

**设计文本要改**
- §5.4：`unshare -m` → `busybox unshare --propagation private -m`；删掉 `mount --make-rprivate /`（本机 toybox 不支持）。
- §5.5：把“宿主侧逆序 umount + `/proc/self/mountinfo`”改为“PGID + `/proc/*/root` + `/proc/*/mountinfo` 全机扫描”；`umount` 只在 holder 模式或 `nsenter` 内做。
- §6 验收第 4 条改为“全机 `/proc/*/mountinfo` 无 rootfs 路径、无 `/proc/*/root` 指向 rootfs 的进程”。

---

### P0-2 宿主路径/chroot 内路径混用：hooks、audit、s6 服务目录在 chroot 内根本不存在

**问题**
- 设计目录树把 `hooks/`、`logs/`、`etc/s6/` 放在 `/data/adb/chroot/`（宿主路径），但 DSH 运行在 chroot 内，看到的 `/` 是 rootfs；`/data/adb/chroot/...` 在chroot 内里**不存在**。
- 具体会坏的地方：
  - `hooks.json` 的 `command` 指向 `/data/adb/chroot/hooks/pretool.sh` → chroot 内找不到 → 钩子启动失败 → **fail open**，规则完全不生效。
  - `PostToolUse` 写 `/data/adb/chroot/logs/audit/...` → chroot 内路径不存在 → 审计丢失。
  - s6 的 `etc/s6/dsh/run` 放在宿主路径 → `s6-svscan` 在chroot 内里看不到服务目录 → 服务起不来。
- 这不是“路径写错一个字符”，而是**控制面没有设计进chroot 内**。

**修法：增加一个只读控制面 bind，运行时目录放 tmpfs**
```
宿主                                    chroot 内                  模式
/data/adb/chroot/control/             /opt/dsh-control/      ro
  hooks/{hooks.json,pretool.sh,posttool.sh,deny.txt,ask.txt,selftest.sh}
  s6/{dsh/run,dsh/finish,ttyd/run,ttyd/finish}
  bin/{dsh-rm,dsh-run}
/data/adb/chroot/dsh/                 /opt/dsh/              ro（或 rw，见更新策略）
/data/adb/chroot/state/dsh-home/      /root/.dsh             rw
/data/adb/chroot/logs/                /var/log/dsh/          rw
```
- s6 服务目录**不能直接 ro bind**：`s6-svscan`/`s6-supervise` 要在服务目录里创建 `.s6-svscan/`、`supervise/`。启动时把 `control/s6` **复制到 `/run/s6`（tmpfs）**，再 `s6-svscan /run/s6`。
- `hooks.json` 的 `configPath` 用**chroot 内绝对路径** `/opt/dsh-control/hooks/hooks.json`；`command` 用 `/opt/dsh-control/hooks/pretool.sh`。
- `audit` 路径用 `/var/log/dsh/audit/YYYY-MM-DD.jsonl`。
- `pretool.sh` 要可执行（0755），且 `hooks.json` 里 `timeout: 10`（秒）。

**验收**
```sh
chroot "$ROOT" /bin/sh -c 'test -x /opt/dsh-control/hooks/pretool.sh && echo ok'
chroot "$ROOT" /bin/sh -c 'echo "{}" | /opt/dsh-control/hooks/pretool.sh; echo rc=$?'
```

---

### P0-3 工作区权限：root 建的目录/文件，手机文件管理器改不了（H2 直接失败）

**问题**
- 设计选 chroot 内 root；root 的默认 umask 022 会在 `/data/media/0` 下建 `0755` 目录、`0644` 文件。
- Android 文件管理器通过 FUSE 访问时，**不是 root**；`0644` 只能读，`0750` 连目录都进不去。H2 的“手机文件管理器可改”不成立。
- 设计 §5.3 把“UID1+groups”列为可选，但没有给 root 方案补工作区权限策略。

**本机证据（E5）**
- `mkdir` 由 root 创建 → `drwxr-s--- root media_rw`（2750）；Termux app `ls`/`cat` 全部 `EACCES`。
- `chmod 0777` 目录 + `chmod 0666` 文件 → Termux 通过 `/sdcard` 可写、可新建。
- 只 `chown 10565:10565` 目录（未加 o+w）→ 新建文件仍 `EACCES`；**不要只 chown**。

**修法**
1. 启动时创建并固定：
   ```sh
   mkdir -p /sdcard/DSH/workspace /sdcard/DSH/.trash
   chmod 2777 /sdcard/DSH /sdcard/DSH/workspace /sdcard/DSH/.trash
   ```
2. DSH 服务 `run` 里 `umask 000`；`/etc/profile.d/00-dsh-umask.sh` 也设 `umask 000`（交互 shell）。
3. 已存在文件用 `dsh-ctl fixperm`：`find /sdcard/DSH -type d -exec chmod 2777 {} +; find /sdcard/DSH -type f -exec chmod 0666 {} +`（按需，不在每次启动全量跑）。
4. `dsh-rm` 把删除移到 `/sdcard/DSH/.trash/<date>/`；不要真删。
5. 在设计 §5.3 明确：**root 方案必须配 2777/0666 + umask 000；否则 H2 不成立。**

**注意**
- 这等于工作区对任何 App 可写；用户已声明隐私不敏感，可以接受。若要更细的权限，只能走 UID1/专用用户方案，代价是 apt/mount 需要额外 root helper。

---

### P0-4 `.tar.zst` 在开机脚本里解不开（payload 与快照都会坏）

**问题**
- 设计 §5.1/§6/`06-packaging` 用 `rootfs.tar.zst`、`dsh-baseline.tar.zst`、`tar --zstd`。
- 开机 bootstrap 运行在 KSU 域，用的是 **KSU busybox/toybox**；E10 实测：
  - `busybox tar` 支持 `-z`（gzip）、`-J`（xz）、`-j`（bzip2）、`-a`（按扩展名），**没有 zstd**；
  - `busybox --list` 没有 `zstd`。
- 结果：`tar -xzf` 对 `.tar.zst` 会报错；`tar --zstd` 直接不存在。首次开机解压失败，且不是“慢”，是“起不来”。

**修法**
- **主线改 `.tar.gz`**：`rootfs.tar.gz`、`dsh-baseline.tar.gz`；`busybox tar -xzf` 可直接用；GNU tar 也能用。体积比 zstd 大 10–20%，可接受。
- 或者：模块内自带静态 `zstd`（~1 MB），bootstrap 用 `zstd -d | tar -x`；但要额外维护二进制、校验、架构。**不建议作为 v1 主线。**
- 快照同理：`dsh-ctl snapshot` 用 `tar -czf`，不要 `--zstd`；如果 `dsh-ctl` 只在 Termux 里跑，GNU tar+zstd 可以，但设计目标是脱离 Termux，所以统一 gzip。
- 若坚持 zstd，必须在 `09-p0-evidence.md` 里补一条“busybox 能解 zstd”的证据，否则不要写进设计。

---

### P0-5 `--rbind /dev` 暴露 `/dev/block`；“只读 bind”也挡不住块设备写入

**问题**
- 设计 §5.2 写“不 bind `/dev/block`”，但同时 `mount --rbind /dev`。E8 实测 `/dev/block` 就在 `/dev` 下，rbind 后 258 个条目全部可见。
- 更关键：**块设备节点的写权限由 inode 权限/设备 cgroup 决定，不由挂载点 ro 决定**。`mount -o remount,ro,bind /dev/block` 只阻止在目录里创建/删除节点，不阻止 `dd of=/dev/block/dm-74`。03-rules 里的“块设备只读护栏”是**假护栏**。
- 再加上 E9：即使把 `/dev` 做成最小 tmpfs，root 仍可通过 `/proc/1/root/dev/block` 访问宿主块设备。**在 root chroot 里，没有任何挂载层能挡住块设备写入。**

**修法（按“能力优先”选一个，但必须写清楚）**
- **A. 最小 `/dev`（推荐给“可逆性优先”）**
  - `mount -t tmpfs` 一个 `/dev`；
  - bind 入 `null, zero, full, random, urandom, tty, ptmx`；
  - `mount -t devpts -o newinstance,ptmxmode=0666,mode=620 devpts /dev/pts`；
  - `mount -t tmpfs -o size=256m tmpfs /dev/shm`；
  - 不提供 `/dev/block`、`/dev/loop*`；需要 loop/mount 镜像时再显式进入维护模式。
  - 代价：`losetup`/`mount` 镜像、部分硬件工具不可用；主线的目录 rootfs 不需要。
- **B. 继续 rbind `/dev`**：接受 `/dev/block` 暴露；把 03-rules 的“块设备只读护栏”删掉，改成“规则层 deny + 外部备份”，不要声称只读。
- **无论 A/B**：在 deny/ask 里加 `/proc/[0-9]+/root`、`nsenter`、`chroot /proc/...`、`mount ... /proc/.../root`；否则 root 可一步逃逸。
- 设计 §5.2 的“不要 bind /dev/block”必须改成“要么最小 /dev，要么承认 rbind /dev 包含 /dev/block”。

**验收**
```sh
# 最小 /dev 方案
chroot "$ROOT" /bin/sh -c 'test ! -e /dev/block && echo no-block'
chroot "$ROOT" /bin/sh -c 'node -e "require(\"node-pty\").spawn(\"/bin/sh\",[\"-c\",\"tty\"])"'
```

---

### P0-6 chroot 环境不干净：`apt`/`dpkg` 脚本会随机失败

**问题**
- `chroot "$ROOT" /bin/bash` 会继承调用者的 `PATH`、`TMPDIR`、`HOME`、`LANG` 等。`/data/adb/chroot` 的 bootstrap 从 `service.sh`/Termux 继承的环境里，`TMPDIR` 常是 Termux 的 `/data/data/com.termux/files/usr/tmp`，`PATH` 常是 Termux 的 `bin`。
- E11 实测：
  - 不设 `PATH`：`apt-get: command not found`；
  - 继承 `TMPDIR`：`ca-certificates` postinst 报 `mktemp: failed to create file via template '/data/data/com.termux/.../ca-certificates.tmp.XXXXXX'`；
  - `env -i PATH=… TMPDIR=/tmp` 后成功。
- 这不是“偶发”，是**烘焙阶段必踩**；`07-roadmap` 阶段 0 的 `chroot rootfs /bin/bash: apt update` 会直接失败。

**修法**
- 所有宿主→chroot 内命令统一走一个 helper：
  ```sh
  chroot_exec() {
    chroot "$ROOT" /usr/bin/env -i \
      PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      HOME=/root TMPDIR=/tmp LANG=C.UTF-8 TERM=xterm-256color \
      DEBIAN_FRONTEND=noninteractive \
      "$@"
  }
  ```
- `service.sh`/bootstrap 里绝不直接 `chroot rootfs /bin/bash`。
- `npm i -g` 另外加 `npm_config_unsafe_perm=true`（root 下 npm 默认把 lifecycle script 降权到 nobody，`node-pty` 的 `prebuild.js` 可能写不进包目录）。
- `07-roadmap` 阶段 0 的第 3–4 步改成“mount + `chroot_exec`”。

---

### P0-7 DSH 工作区/会话 cwd 没钉死，profile 还带着 Termux 绝对路径

**问题**
- DSH 的 Bash 工具默认 `cwd = process.cwd()`；会话的不可变 cwd 来自 `SessionHeader.cwd`。如果 s6 `run` 的 cwd 是 `/`，DSH 的工作区就是 rootfs 的 `/`，不是 `/sdcard/DSH/workspace`。
- 当前 Termux profile 的 `fs-sandbox.cwd` 是 `/data/data/com.termux/files/home`（`~/.dsh/profiles/web/cordis.patch.yml`），复制进 chroot 后这个路径不存在；`dsh-bash-local` 会在启动/首次命令时报错。
- 设计 §5.3 只说“工作区在共享存储”，没有写 **DSH 的 cwd / sandbox `workspaceRoot` / profile 迁移**。
- 另外：直接复制 Termux 的 `node_modules`/`package.json` 不可行（bionic 原生模块、`link:` 到 `/data/data/com.termux/...` 的本地 preset 都会断）。

**修法**
1. s6 `dsh/run` 里：
   ```sh
   cd /sdcard/DSH/workspace || exit 1
   umask 000
   export HOME=/root DSH_HOME=/root/.dsh TMPDIR=/tmp \
          PATH=/opt/node/bin:/opt/dsh-control/bin:/usr/local/bin:/usr/bin:/bin \
          LANG=C.UTF-8 TERM=xterm-256color
   exec /opt/node/bin/node /opt/dsh/current/lib/bin.js web \
     --host 127.0.0.1 --port 0 --no-open
   ```
2. `cordis.patch.yml`：
   ```yaml
   - id: fs-sandbox
     config:
       cwd: /sdcard/DSH/workspace
   ```
   同时把 `dsh-sandbox-policy.workspaceRoot` 设为 `/sdcard/DSH/workspace`（agentless/无 session cwd 的兜底）。
3. 迁移只复制**配置与凭据**，不复制 `node_modules`：
   - 复制 `.credentials.yaml`、settings、`cordis.patch.yml`（逐项改路径）、插件依赖清单；
   - 在 chroot 内重新 `dsh plugin`/`pnpm install`，让 glibc 原生模块重新选预编译；
   - 本地 `@local/dsh-preset-*` 用源码重新 link，不要沿用 Termux 的绝对 `link:`。
4. 并行跑一周时给 chroot DSH 独立的 `DSH_HOME`（`/root/.dsh`）和端口（`--port 0`）；Termux 的 3080 不动。

**验收**
```sh
chroot_exec /bin/sh -c 'cd /sdcard/DSH/workspace && pwd'
# DSH 内 Bash 工具执行 pwd，必须是 /sdcard/DSH/workspace
```

---

### P0-8 钩子 fail-open + 可逆性没覆盖 `/sdcard`：规则层被高估了

**问题**
- DSH 的 hooks 契约明确：**非 2 退出码 = 非阻塞失败，动作继续**；钩子起不来也一样。设计只写“启动自检报警”，报警不能阻止 DSH 继续跑。
- 更严重：设计 §5.6 的快照只覆盖 rootfs 和 state；`/sdcard`（工作区、用户文件）**没有快照**。而 AI 是 root，`rm -rf /sdcard/...` 一旦执行，rootfs 快照救不了。
- 因此“安全 = 规则 + 可逆性”目前只在 rootfs 上成立；对最值钱的用户数据不成立。H7 的表述过度。

**修法（至少做到 1+2+3）**
1. **服务级 fail-closed 门禁**：`dsh/run` 启动 DSH 前先跑 `selftest.sh`（喂 10–20 条已知坏/好命令，检查 deny/ask/allow）；不通过就**拒绝启动 DSH**并写 `state/ctl/degraded`，而不是“报警后继续”。这把“钩子起不来”从 fail-open 变成“不启动”。
2. **可逆性覆盖 `/sdcard/DSH`**：
   - `dsh-rm` 进 `.trash`；
   - 每日 `restic` 增量备份 `/sdcard/DSH`（以及用户指定的 DCIM/Documents）到 PC/NAS；
   - 在 H7/设计里明确：`/sdcard` 其余部分、`/data`、`/system` **不在快照范围**，只能靠规则/外部备份。
3. **规则层只承诺“降低误操作”**：在 §5.7 和 §7 写“hooks 不是安全边界；root 可通过 `/proc/1/root`、脚本、base64 绕过；唯一可靠兜底是外部备份 + 冷快照”。
4. 关键路径 deny：`/sdcard/DCIM`、`/sdcard/Android`、`/sdcard/Download`、`/data/media/0` 根、`/data`、`/system`、`/dev/block`、`/proc/*/root`、`reboot`、`setenforce`、`insmod` 等（见 §7）。

**验收**
- 把 `pretool.sh` 改坏/移走 → DSH 不应启动（`state/ctl/degraded` 有原因）。
- `rm -rf /sdcard/DSH/workspace/foo` → 进 `.trash` 而不是消失。
- 从 `restic` 快照恢复一个被删文件，跑通。

---

## 4. P1 重要项（会返工/会埋雷，但不一定第一天炸）

### P1-1 挂载表要重写：`/apex` 必须 rbind，`/data` 与 H6 矛盾

**问题**
- §5.2 的 `mount --bind /apex` 是空壳（E7：plain 0 项，rbind 6 项）。`/vendor` 也有 `firmware_mnt`、`dsp` 等子挂载；plain bind 会丢。
- §5.2 没有 `/data`，但 `02-requirements` H6 明写“宽挂载（/sdcard 原始视图、/data、/system 只读）”；设计与需求直接冲突。
- §5.2 没有 `/product`、`/system_ext`、`/odm`、`/linkerconfig`；如果目标只是跑 Ubuntu 工具，这些都不需要；如果目标是跑 Android CLI（`am`/`pm`/`input`），这些又不够。**每个 bind 要写“为什么”**，否则就是 cargo cult。
- `/sys` rbind 是 rw；`/sys/fs/selinux` 可写，AI 可 `setenforce 0`。

**建议**
- 先定目标：
  - **Ubuntu 工具为主**：只绑 `/system:ro`（可选）、`/dev`、`/proc`、`/sys`、`/sdcard`、控制面；不要绑 `/apex`/`/vendor`/`/product`。
  - **Android CLI 也要**：`/system`、`/system_ext`、`/product`、`/vendor`、`/odm` 全部 `--rbind` + `remount,ro,bind`；`/apex --rbind`；`/linkerconfig:ro`；然后实测 `/system/bin/am --help`、`/system/bin/pm list packages`。
- `/data`：**默认不要 rw 绑**。因为 AI 是 root，`/data` 一旦 rw，`rm -rf /data/system` 可直接 bootloop，且没有快照能救。建议 `/host-data:ro`（rbind），只读查看；需要写时走显式 `dsh-ctl mount-data-rw` 维护模式，并在规则里 ask。
- `/sys`：能 ro 就 ro；不能 ro 的部分（cgroup）至少 deny `setenforce`/`sysctl -w`/`/sys/fs/selinux` 写入。
- 增加 `/dev/shm`（tmpfs 256m，Chromium/Playwright 需要）；`/run`（tmpfs，s6 服务目录）；`/tmp`（tmpfs，避免快照膨胀）。
- `/dev/pts`：`--rbind /dev` 已经带上了 host `/dev/pts`，再单独 `--rbind /dev/pts` 是冗余的，且可能触发 issue #102 的 EBUSY；要么去掉，要么改成 `mount -t devpts -o newinstance,ptmxmode=0666,mode=620 devpts /dev/pts`。E1 用 host devpts 已验证 node-pty 可用，但 host devpts 会共享 pty 池，长期跑建议 newinstance。
- `resolv.conf`：Android 没有 `/etc/resolv.conf`；在 rootfs 写 `nameserver 8.8.8.8`/`1.1.1.1`（E4 实测可用），或启动时生成。不要把宿主 `/etc/resolv.conf` 当必然存在。
- `/storage/emulated/0` → `/sdcard` 做 symlink，兼容硬编码路径。

### P1-2 s6 的落地细节

- 设计写“s6-supervise（dsh、ttyd）”，实际应 `s6-svscan /run/s6`，每个服务目录一个 `run`；`s6-supervise` 是每个服务一个进程，不是“一个 supervise 管两个”。
- **服务目录必须可写**：s6 会在里面建 `supervise/`；不能把 `/etc/s6` 直接 ro bind。复制到 `/run/s6`（tmpfs）。
- **s6 控制面在私有 ns 内**：`/run/s6` 是 namespace 里的 tmpfs，宿主侧 `chroot` 看不到；`s6-svc`/`s6-svstat`/`s6-svwait` 必须 `nsenter -t <launcher> -m` 执行，或者把服务目录放到宿主可见的 bind 源（`state/s6`）。不要从宿主直接 `chroot ... s6-svc`。
- **就绪通知**：DSH 不会写 `notification-fd`；不要直接配 `notification-fd`。用一个 `dsh-run` 包装器（Node 或 shell）启动 DSH、抓 token URL、写 `state/ctl/url`，再写 notification fd；或者 `dsh-ctl` 轮询 URL。
- **token 抓取**：不要长期靠 `grep dsh.log` 正则；用包装器写 `state/ctl/url`（0600），`dsh-ctl status` 读。E2 的 URL 格式已确认。
- **`--no-open`**：headless 必须加，否则会尝试打开浏览器（无 `xdg-open`，至少是噪音）。
- **进程组/收尸**：s6-supervise 不 setsid 服务；DSH 的 tool 子进程可能逃逸。stop 要按 PGID + `/proc/*/root` 扫描；启动时记录 launcher PGID。
- **看门狗**：s6 只监督 dsh/ttyd，不监督 `s6-svscan` 本身；`s6-svscan` 挂了没人拉起。加一个宿主侧 `dsh-watchdog`（30s 检查 launcher pid+starttime，必要时重启），或让 `service.sh` 的 watchdog 负责。
- **`down` 文件**：stop 要写 `state/ctl/stopped` + 各服务目录 `down`；start 要删 `down` 并 `s6-svc -u`，否则下次不启动。
- **`finish` 退避**：DSH 启动失败会 tight loop；`finish` 里 `sleep 2` 或指数退避。
- **日志**：`s6-log` 或 size-capped 轮转；不要让 `dsh.log` 无限长。
- **双启动入口**：dsh-alpine-ksu 同时提供 `service.sh` 与 `boot-completed.sh`，因为某些 LKM/late-load 配置只触发其中一个；建议加一个幂等的 `boot-completed.sh`（同一个 `dsh-ctl start --boot`，靠 lock/pid 幂等），不要只赌 `service.sh`。
- **等待开机完成**：`resetprop -w sys.boot_completed 0` 放在后台 bootstrap 里，不要放 `service.sh` 前台；若 `resetprop` 不在 PATH，用 `getprop` 轮询兜底。

### P1-3 快照/回滚的完整性

- rootfs 与 state 必须**成对快照**：manifest 记录 rootfs sha、state sha、DSH 版本、时间；回滚时成对恢复，否则 DSH 版本/state schema 不匹配。
- 快照要**原子**：`tar -czf snap.tmp` → `sync` → `mv snap.tmp snap.tar.gz`；失败不留半成品。
- 快照后**验证**：`tar -tzf` 能列目录；记录 sha256；定期做一次“恢复演练”。
- **空间守卫**：`/data` 剩余 < 5GiB 时拒绝新快照并告警；保留策略 1 golden + 3 recent + 1 pre-change；state 7 份。
- **pre-change 触发**：`apt`/`npm -g`/`pip install` 前自动快照。hook 里直接调 `dsh-ctl snapshot` 不现实（chroot 内看不到宿主路径）；做一个 `dsh-apt`/`dsh-npm` 包装器，或在维护流程里手动 `dsh-ctl snapshot --tag pre-change`。设计要写清楚“谁在什么时候触发”。
- `tar` 排除 `/proc`、`/sys`、`/dev`、`/run`、`/tmp`；确保 stop 后这些挂载已随 namespace 消失。
- **rootfs 版本指针**：如果要做原子切换，`rootfs` 用 symlink 指向 `versions/<id>`，`ln -sfn` + `mv -Tf` 原子替换；比 `mv rootfs rootfs.old` 两个 rename 更稳。

### P1-4 审计的可用性与防篡改

- 审计路径改为chroot 内 `/var/log/dsh/audit/YYYY-MM-DD.jsonl`，bind 宿主 logs；`PostToolUse` 写；`PreToolUse` 的 deny/ask 也要写一条（否则被拒绝的命令没有记录）。
- **脱敏**：`tool_input` 可能包含 API key、token、`Authorization`；写日志前用 `jq` 删/替换 `token|api_key|authorization|password` 字段。
- **截断**：`tool_response` 可能几 MB；只写前 4KB + 总长度。
- **轮转/保留**：按天 + 总大小上限；不要无限增长。
- **防篡改**：AI 是 root，能删日志；不要声称“审计不可抵赖”。要更强可 `chattr +a`（f2fs 是否支持需实测）或同步到宿主/远端 syslog；至少写“审计对 root AI 不是安全边界”。

### P1-5 规则层：工具名、解析、覆盖

- **工具名是 `bash`（小写）**，不是 `Bash`；`write`/`edit`/`read` 也是小写。`research/web/dsh-hooks-and-permissions.md` 里的 `Bash|Write|Edit` 示例会不匹配。matcher 先 `*` 是对的；收窄时用 `bash|write|edit`。
- **`updatedInput` 不能改写命令**：hooks README 明确 `systemMessage`/`updatedInput` 只记录/警告，不模型可见；不要指望“自动把 rm 改写成 dsh-rm”。只能 deny/ask/allow。
- **JSON 解析**：纯 sh 用 `sed` 提取 `tool_input.command` 很脆（转义、换行、`\u002d`）。建议 rootfs 装 `jq`（~1MB），`jq -r '.tool_input.command // empty'`；解析失败时 `exit 2`（脚本级 fail closed），而不是 `exit 0`。
- **规范化**：按 `;`、`&&`、`||`、`|`、换行切段；去掉前导 `sudo/env/command/nohup`；对 `bash -c '...'`/`sh -c '...'` 递归一层；对 raw 和 normalized 都匹配。
- **deny/ask 不是安全边界**：`r''m`、`$IFS`、base64、脚本文件都能绕过；在文档里写清楚。真正的兜底是 `restic` + 冷快照。
- **自检用例**：至少 20 条（见 §7），每次构建/启动跑；不通过拒绝启动 DSH。

### P1-6 DSH 权限预设与 hooks 配置

- `root-full = danger-full-access + ask` 的 YAML 写法正确（`dsh-permission-presets` README 已核对）；`custom`/`auto` 是保留名，别用。
- `ask` 在没有 answerer（纯 CLI/headless）时 **fail closed**；Web UI 有 answerer。启动时 DSH 还没人开网页，`ask` 命令会失败；这是预期，写进文档。
- 不要把 `approval` 设成 `never`；否则 hook 的 `ask` 会变成确定性拒绝。
- `hooks.json` 的 `configPath` 绝对路径、`timeout: 10`、`PostToolUse` matcher `*`；`PreToolUse` 的 `command` 指向 `/opt/dsh-control/hooks/pretool.sh`。
- `hooks.json` 只在 DSH 启动读一次；改 matcher 要重启；改 `deny.txt`/`ask.txt` 不用重启（脚本每次读）。
- `dsh web` 用 `--host 127.0.0.1`；如果通过 `localhost` 或 PC 反代访问，加 `--trusted-host <host:port>`，否则 `/api` 的 browser-trust fence 会拒绝。
- 建议 `pluginRoot`/`projectDir` 显式设为 `/sdcard/DSH/workspace`，避免相对路径歧义。

### P1-7 迁移与并行

- 当前 Termux profile 有 `@local/dsh-preset-*` 的 `link:/data/data/com.termux/...`、`fs-sandbox.cwd` 指向 Termux home、插件 `node_modules` 是 bionic 构建；**不能整目录复制**。
- 迁移顺序：停 Termux DSH → 复制凭据/settings/插件清单 → 在 chroot 内重新安装插件 → 改 cwd → 启动 → 用新会话验证；旧 session 只读保留，不继续写。
- 并行跑一周时：两套 `DSH_HOME`、两个端口、两套日志；不要共享 `~/.dsh`（会抢锁/污染 session）。
- ttyd 7681 可能与现有服务冲突；启动前检查，或让 ttyd 用动态端口（若支持）/配置化。

### P1-8 打包/卸载/升级

- `customize.sh` 是 **source** 执行的：不要 `exit`，用 `abort`；不要包根放 `install.sh`（A23）。
- `customize.sh` 若 payload 不存在：**abort 并给出 push 指令**，不要“装上了但开机起不来”。
- 所有入口脚本 0755；`sh -n` + `shellcheck` + **mksh** 跑一遍（`${s%%|*}` 方言坑）。
- `uninstall.sh` 只删模块目录，保留 `/data/adb/chroot`；在文档里写“卸载 ≠ 删数据”，提供 `dsh-ctl purge` 作为显式全删。
- 模块更新不跑 `uninstall.sh`；更新前 `dsh-ctl stop`，更新后重启；热安装旧目录残留要清。
- 加 `action.sh`（KSU 管理器按钮）→ `dsh-ctl status`；否则 H4 的“可诊断”只能靠 Termux。
- `dsh-ctl` 在模块目录，卸载后消失；把一份只读副本放 `/data/adb/chroot/bin/dsh-ctl`，方便卸载后清理/查看。
- `versionCode` 单调整数；`version` 不参与比较；`updateJson` 可选；`id` 不改。

---

## 5. 建议的 v1.3 目录/挂载表

### 5.1 目录（宿主）

```
/data/adb/chroot/
├── payload/                 rootfs.tar.gz + dsh-baseline.tar.gz + SHA256SUMS
├── rootfs/                  活动根（或 symlink -> versions/<id>）
├── versions/                可选：rootfs 版本
├── dsh/                     versions/<v>/ + current + previous
├── control/                 hooks/ + s6/ + bin/（只读 bind 进chroot 内）
├── state/
│   ├── dsh-home/            bind -> /root/.dsh
│   └── ctl/                 launcher.pid/starttime、lock、url、stopped、degraded
├── logs/                    bind -> /var/log/dsh
├── snapshots/               rootfs-<ts>.tar.gz + state-<ts>.tar.gz + manifest
└── tmp/                     可选
```

### 5.2 挂载（chroot 内路径）

| chroot 内路径 | 宿主来源 | 模式 | 用途/备注 |
|---|---|---|---|
| `/` | `rootfs/` | rw | chroot 根 |
| `/proc` | fresh proc | rw | 无 PID ns，会看到宿主进程；必须接受 |
| `/sys` | rbind `/sys` | ro 优先 | 不能 ro 的部分用规则兜 |
| `/dev` | rbind `/dev` 或最小 tmpfs | rw | 最小方案见 P0-5；rbind 会带 `/dev/block` |
| `/dev/pts` | host devpts 或 newinstance | rw | node-pty E1 已用 host devpts 验证 |
| `/dev/shm` | tmpfs 256m | rw | Chromium/Playwright 需要 |
| `/run` | tmpfs 64m | rw | s6 服务目录复制到这里 |
| `/tmp` | tmpfs 512m | rw | 避免快照膨胀；`TMPDIR` |
| `/system` | `/system` | ro | 只有跑 Android CLI 才需要 |
| `/system_ext` | `/system_ext` | ro | 同上；rbind |
| `/product` | `/product` | ro | 同上；rbind |
| `/vendor` | `/vendor` | ro | 同上；rbind（含 firmware/dsp 子挂载） |
| `/odm` | `/odm` | ro | 同上 |
| `/apex` | `/apex` | ro | **必须 `--rbind`**；plain bind 是空壳（E7） |
| `/linkerconfig` | `/linkerconfig` | ro | 跑 Android CLI 需要 |
| `/sdcard` | `/data/media/0` | rw | 工作区；2777/0666 + umask 000 |
| `/host-data` | `/data` | ro | 默认只读；rw 需显式维护模式 |
| `/root/.dsh` | `state/dsh-home` | rw | DSH 配置/会话/凭据 |
| `/opt/dsh-control` | `control/` | ro | hooks/s6/bin |
| `/opt/dsh` | `dsh/` | ro | DSH 版本；更新由宿主做 |
| `/var/log/dsh` | `logs/` | rw | 审计/日志 |
| `/opt/node` | rootfs 内 | rw | Node 24；快照覆盖 |

**挂载顺序**：`/proc`、`/sys`、`/dev`、`/dev/pts`、`/dev/shm` → `/run`、`/tmp` → `/system` 等 → `/host-data` → `/sdcard` → 控制面 → chroot → 复制 s6 → `s6-svscan`。父先子后；卸载/杀 ns 时反向。

### 5.3 启动/停止状态机（建议写进 v1.3）

**start**
1. 抢 `state/ctl/lock`（flock 或 mkdir）；读 `stopped`；清除。
2. 检查 payload/rootfs；必要时 bootstrap（`.tar.gz`，sha256，`rootfs.new` → 验证 → 切换）。
3. `setsid busybox unshare --propagation private -m /system/bin/sh -c '…mounts…; cp -a /opt/dsh-control/s6/. /run/s6/; exec s6-svscan /run/s6'`。
4. 记录 launcher PID/PGID/starttime；等 `state/ctl/url` 或 `nsenter -t <launcher> -m s6-svstat /run/s6/dsh`；写 `running`。
5. 失败：清理 namespace/挂载/进程，写 `degraded` + 原因，释放 lock。

**stop**
1. 抢 lock；写 `stopped` + 各服务 `down`。
2. `s6-svc -d` 所有服务（**在 namespace 内**：`nsenter -t <launcher> -m s6-svc -d /run/s6/<svc>`）；`s6-svwait -d -t 10000`；超时 `s6-svc -k`。
3. 向 launcher PGID TERM；等 5–10s；KILL。
4. 扫 `/proc/*/root` = rootfs 的进程，TERM/KILL；扫 `/proc/*/mountinfo`。
5. 若仍有挂载且 holder 在：`nsenter -t <pid> -m umount -R /`；否则报告残留（不应发生）。
6. 删 lock/pid；保留日志；写 `stopped`。
7. `dsh-ctl status` 显示 `stopped`。

**restart**：stop + start；**snapshot**：stop（记录 was_running）→ sync → tar rootfs/state → verify → start；**restore**：stop → verify → swap → start → smoke；失败回滚。

---

## 6. 可逆性矩阵（必须写进设计，避免“假可逆”）

| 资产 | 备份/快照 | 恢复 | 缺口/说明 |
|---|---|---|---|
| rootfs（Ubuntu/Node/apt） | 冷 `tar.gz`，1 golden + 3 recent + 1 pre-change | `dsh-ctl restore` | 覆盖 DSH/apt 变更；需 stop |
| state（`~/.dsh`） | 冷 `tar.gz`，7 份 | 成对恢复 | 含凭据/会话；与 rootfs 配对 |
| `/sdcard/DSH`（工作区） | `restic` 每日增量到 PC/NAS + `.trash` | restic/trash | **rootfs 快照不覆盖**；必须单独做 |
| `/sdcard` 其余 | 不自动 | 无 | 规则 deny 关键路径；用户自行备份 |
| `/data` | 不自动 | 无 | 默认 ro；rw 维护模式需确认；无快照能救 |
| `/system`/`/vendor`/`/apex` | 不自动 | 无 | ro；块设备写入靠规则（可绕过） |
| `/dev/block` | 不自动 | 无 | 最小 /dev 或规则；root 可通过 `/proc/1/root` 绕过 |
| `/data/adb`（模块/控制面） | 随 rootfs/control 备份 | 手动 | 不放模块目录内 |

**结论**：H7 只能承诺“rootfs + state + `/sdcard/DSH` 可逆”；`/data`、`/system`、`/sdcard` 其余部分不可逆。设计要么这样写，要么把 DSH 降权到非 root（代价是 apt/mount 需要 root helper）。

---

## 7. 规则清单细化

### 7.1 规则引擎（建议）

- `deny.txt`/`ask.txt`：一行一条 ERE；`#` 注释；不要用 shell 展开。
- `pretool.sh`：
  ```sh
  #!/bin/sh
  set -u
  input=$(cat)
  tool=$(printf '%s' "$input" | jq -r '.tool_name // empty') || exit 2
  cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty') || exit 2
  [ -n "$cmd" ] || exit 0
  # normalize + split; raw and normalized both check
  ...
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"..."}}\n'
  ```
- 解析失败 `exit 2`（脚本级 fail closed）；但 DSH 层 fail-open 仍在，所以还要 P0-8 的服务级门禁。
- 审计在 `pretool` 和 `posttool` 都写；`pretool` 记录 deny/ask 命中。
- `hooks.json` 的 `timeout` 10s；脚本内部 `grep` 要快；不要拉 Node。

### 7.2 需要补进 deny 的高危项（示例）

- 块设备/分区：`mkfs*`、`mke2fs`、`fdisk`、`sfdisk`、`parted`、`wipefs`、`blkdiscard`、`flash_erase`、`nandwrite`、`fastboot`、`dd` 任意 `of=/dev/`、`cat/tee/cp/mv/truncate` 写 `/dev/block`、`debugfs`、`e2fsck -y`、`tune2fs`、`resize2fs`。
- 系统分区：`mount -o remount,rw /system`、`mount /dev/block/... /system`、`rm/chmod/chown` 于 `/system`、`/vendor`、`/product`、`/apex`、`/odm`、`/system_ext`、`/boot`、`/init_boot`、`/dtbo`。
- 启动/内核：`reboot`、`poweroff`、`halt`、`svc power reboot`、`setprop sys.powerctl`、`echo b > /proc/sysrq-trigger`、`insmod`、`rmmod`、`modprobe`、`ksud`、`magisk`、`setenforce 0`。
- 进程：`kill -9 -1`、`killall5`、`pkill -9 -f .`、`kill` 关键 PID（`system_server`/`zygote`/`init`）。
- 数据：`pm clear`、`rm -rf /data`、`/data/data`、`/data/user`、`/data/system`、`/data/adb`、`/sdcard/DCIM`、`/sdcard/Android`、`/sdcard/Download`、`/data/media/0` 根；`find ... -delete`。
- 逃逸：`/proc/[0-9]+/root`、`nsenter`、`chroot /proc/...`、`mount ... /proc/.../root`、`pivot_root`、`setns`。
- 包管理/远程代码：`apt purge`、`apt autoremove`、`dpkg --purge`、`npm publish`、`curl ... | sh`、`wget ... | bash`、`base64 -d | sh`、`eval`。
- 权限：`chmod -R 777 /`、`chown -R` 于 `/`、`/data`、`/system`。

### 7.3 ask 的高危项

`rm -rf`、`git clean -fdx`、`git reset --hard`、`apt upgrade/dist-upgrade`、`npm i -g`、`pip install`、`pm uninstall`、`settings put`、`am force-stop`、`mount`/`umount` 非 `/tmp`、`kill`/`pkill`、`chmod/chown -R`、`sysctl -w`、写 `/data/adb`、`dsh-ctl restore`。

### 7.4 自检用例（至少）

- 必须 deny：`mkfs.ext4 /dev/block/by-name/userdata`、`dd if=/dev/zero of=/dev/block/dm-74`、`rm -rf /data`、`reboot`、`cat /proc/1/root/dev/block/...`、`nsenter -t 1 -m`、`setenforce 0`。
- 必须 allow：`ls /sdcard`、`go build ./...`、`node -e "require('node-pty')"`、`apt-get update`（如果规则不拦）、`echo "rm -rf /"`（只应作为文本，不应误杀）。
- 必须 ask：`rm -rf /sdcard/DSH/workspace/foo`、`apt purge`、`pm clear`。
- 规则误杀/漏杀都记录；每次改规则跑一遍。

---

## 8. 验收清单 v2（替换 §6）

**P0（全过才打包）**
1. chroot 内 `id`=0、`uname -r` 与宿主一致；`/proc/1/root` 逃逸被明确记录（不作为隔离）。
2. Go 在 `/sdcard/DSH/workspace` 编译并直接运行产物。
3. Node 24 + `node-pty` 起 PTY（E1 已过）；`dsh web --port 0 --no-open` 打印 token URL、`curl` 200/303（E2 已过）。
4. stop 后：**全机** `/proc/*/mountinfo` 无 rootfs 路径、`/proc/*/root` 无 rootfs 进程、无 launcher pid；不是只看 `/proc/self`。
5. `.tar.gz` 快照 → 破坏 → `dsh-ctl restore` 一轮通过；rootfs/state 成对；sha 校验。
6. 钩子自检：坏命令 deny、好命令 allow、ask 命令 ask；把 `pretool.sh` 移走 → DSH **不启动**（degraded）。
7. `apt-get update` + `apt-get install s6 ttyd` 在 chroot 内成功（clean env）；`ca-certificates` 配置成功。
8. `/sdcard/DSH` 0777/0666 + umask 000；Termux/文件管理器可写（E5）。
9. `/apex` rbind 后 `com.android.runtime/bin` 非空（若启用 Android CLI）；`/dev/block` 按选择暴露/不暴露，文档一致。

**P1**
- 开机自启（service.sh + boot-completed 幂等、非阻塞、10–60s 内就绪，等待解锁）。
- DSH 升/降级 + rollback；`dsh-ctl update`；previous 可用。
- 断电/强杀后重启自愈；残留进程/挂载扫描。
- `dsh-ctl status/diag/log` 输出 rootfs 版本、DSH 版本、端口、URL、挂载数、磁盘余量。
- `restic` 备份 `/sdcard/DSH` 并恢复一个文件。

---

## 9. 文档矛盾与待拍板

### 9.1 必须统一的口径
- `02-requirements` H6 写“/data 宽挂载”，`05` §5.2 没有 `/data`；`03` 又写“rootfs 用 ext4 镜像”，`05` 改为目录。**04/03 是旧稿，必须标“已被 05 取代”或直接改。**
- `research/f2fs-android-chroot-snapshot-backup.md` 仍把“硬链接快照”当主方案；`05` 已否定（原地写污染）。**把该笔记的 TL;DR 改成“仅不可变归档”，否则会误导实现。**
- `06-packaging`/`07-roadmap` 的 `.tar.zst` 与 `05` 的 `.tar.gz` 统一；`npm i -g` 补 `--unsafe-perm`、`env -i`。
- `research/web/dsh-hooks-and-permissions.md` 的 `Bash|Write|Edit` 改为 `bash|write|edit`（实际工具名小写），并注明 `updatedInput` 不能改写命令。
- `README.md` 说 14 个项目、`_repo-list` 17 个；顺手对齐。

### 9.2 需要你拍板（不阻塞评审，但影响 v1.3）
1. **`/data` 默认 ro 还是 rw？** 推荐 ro；rw 需要显式维护模式 + 规则 ask + 外部备份，否则 H7 不成立。
2. **`/dev/block` 是否暴露？** 推荐最小 `/dev`（不暴露）；若必须 loop/mount 镜像，接受暴露并删掉“只读护栏”叙事。
3. **工作区权限**：接受 2777/0666（无隐私）还是走 UID1/专用用户（更细权限，但 apt/mount 要 root helper）？
4. **DSH 是否坚持 root？** root 最省事，但 `/proc/1/root` 可逃逸、所有结构护栏失效；非 root 更安全，但“全能”要补 root helper。
5. **`/sdcard` 备份范围**：只 `/sdcard/DSH`，还是 DCIM/Documents 也纳入？restic 到 PC/NAS 的路径/凭据。
6. **钩子失败策略**：服务级 fail-closed（推荐）还是接受 fail-open + 报警？前者可能因一个脚本 bug 导致 DSH 起不来。
7. **s6 看门狗**：是否加宿主侧 `dsh-watchdog`（推荐），还是接受 `s6-svscan` 挂了需手动重启。

---

## 10. 本次未验证（P0 阶段补）

- 模块安装/开机自启的真实时序：`service.sh` 非阻塞、`sys.boot_completed=1`、`/data/media/0` 解锁等待、`customize.sh` 复制 control/。
- `hooks.json` + `dsh-hooks-claude-code` 的真实 `PreToolUse` 调用、`permissionDecision: ask` 与 Web UI answerer；`root-full` 预设是否能在本机组合（`dsh-bash-sandbox` + danger-full-access + ask）。
- 最小 `/dev` + newinstance devpts 下的 `node-pty`/`ttyd`（E1 用的是 host devpts）。
- `/apex` rbind + `/system`/`/vendor`/`/linkerconfig` 下 Android CLI（`am`/`pm`/`input`）是否可跑；本次只验证了 `/apex` 内容非空。
- `restic` 到 PC/NAS 的备份/恢复；`dsh-rm` 回收站。
- 完整 `tar.gz` 快照 → 破坏 → `dsh-ctl restore` 一轮；rootfs/state 成对恢复。
- `ttyd` 的实际端口/认证/PTY。
- 模块卸载/更新（KSU `remove` 标记、下次开机 prune）与 `uninstall.sh` 时序。
- `dsh` 插件在 chroot 内重装后的原生模块（sharp/koffi）与 profile 迁移。

---

## 11. 结论

- **路线成立**：本机已经把最难的三件事跑通（chroot + node-pty + dsh web），Ubuntu glibc 的选择是对的；s6/apt/raw media 也都验证。
- **但 v1.2 是“能跑”的稿，不是“能交付”的稿**：挂载命名空间与 stop 语义、宿主/chroot 内路径、工作区权限、`.tar.zst`、chroot 环境、DSH cwd、`/dev/block` 与 `/proc/1/root`、钩子 fail-open 这 8 条，任意一条都会让“开机自启/可回滚/规则生效”中的一项落空。
- **最重要的认知修正**：root chroot 不是“能力优先但稍微危险”，而是“**没有任何文件系统隔离**”。`/proc/1/root` 就是宿主根；`/data` 不绑也能读写；`/dev/block` 不绑也能通过 `/proc/1/root` 访问。因此：
  - 不要把 ro bind/不绑 `/dev/block` 当安全边界；
  - 把预算从“更多挂载限制”转到“**外部备份 + 冷快照 + 规则拦截 + 审计**”；
  - 如果确实要限制爆炸半径，唯一现实路线是 **DSH 降权 + 窄 root helper**，而不是在 root chroot 里堆挂载。
- 建议按 §3 的 P0 顺序修：**P0-1/P0-2 先改架构（namespace/stop、控制面路径）→ P0-3/P0-4/P0-6 改可运行性（权限、压缩、环境）→ P0-5/P0-7/P0-8 改安全叙事与覆盖**，然后再进 §8 的 P0 验收。
