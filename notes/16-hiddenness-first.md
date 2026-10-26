# 隐藏性定义：游戏/银行看不到“本模块的把柄”

- 日期：2026-10-26
- 目标：游戏、银行、Play Integrity、普通 root 检测 App **不因为本模块**发现 root/异常。
- 不是目标：防住 chroot 内 root 的恶意操作；防住物理接触；做 SELinux 安全边界。
- 关键结论：**v1.4 的 full `/data` + 128GB 镜像不是隐藏性最优；它会在 `/sdcard` 留下 `.dsh` 目录、`rootfs.img` 文件和 loop 设备。** 要做到应用层隐藏，应该换 architecture：**directory rootfs + 不 full `/data` + 宿主 thin agent**。

---

## 1. 应用能看见什么 / 看不见什么

| 痕迹 | 普通 App 可见？ | 说明 |
|---|---|---|
| `/sdcard/.dsh` 目录 | **可见（名字/owner/mode）** | 0700 只挡内容，不挡 `readdir`/`stat`；root-owned dotdir 本身异常 |
| `/sdcard/.dsh/rootfs.img` | **可见（如果目录可进）** | 128GB 镜像文件名很显眼；file manager/MTP 可能看到 |
| `/sys/block/loop*/loop/backing_file` | root/特权可读；普通 App 受 SELinux 限制 | 多一个 rootfs loop 仍是痕迹；`losetup -a` 可见 |
| 应用自己的 `/proc/self/mountinfo` | 看得到宿主 mounts | 私有 mount ns 的 chroot mounts **不出现**；但 loop 设备仍可能在 `/sys` |
| `/data/adb/chroot` | 普通 App 不可读（0700 root） | 不是应用可见痕迹 |
| `/data/adb/modules` | 普通 App 不可读 | 但 root 检测可能通过 manager/包名/其他信号 |
| `u:r:ksu:s0` 域 | **App 看不到别的进程域** | 这是安全边界问题，不是应用隐藏问题 |
| `/proc/1/root` | 普通 App 不可读 | root 可逃逸；不是 App 检测面 |
| 全局 `setenforce 0` | 可能被特权/检测工具看到 | 不要做；保持全局 Enforcing |
| 全局 prop 改动 | **App 可读** | 绝不改 `ro.*`/`init.svc.*`/`persist.*` |
| `/system` overlay | 私有 ns 内 App 看不到；但 PIF Detector 会看 `/proc/self/mountinfo` | 如果只在私有 ns，App 看不到；但不要默认开 |
| DSH/ttyd 端口 | App 可扫 127.0.0.1 | 不是 root 标志，但是攻击面 |
| root 进程名 | 受 `hidepid=invisible` 保护；不同 ROM 不同 | 本机 `/proc` 是 hidepid=invisible；doctor 要检查 |
| `/sdcard/DSH` 工作区 | 可见；普通目录 | 名字不含 root 标记；不要放镜像/控制面 |

**一句话**：应用层隐藏的关键不是 `u:r:ksu` 是否 permissive，而是**不要在 App 能读的地方留下 `.dsh`、rootfs.img、loop、全局 prop、permissive、可疑进程/端口**。

---

## 2. 为什么 full `/data` + 镜像会掉隐藏性

- 镜像必须放 `/data/media/0`（FBE 下 `/data/adb` loop EIO）。
- `/data/media/0/.dsh/rootfs.img` 通过 `/sdcard`/MTP/文件管理器可见（至少目录名和文件存在）。
- 为了保护镜像/控制面，需要遮 `/data`、`/data/user/0`、`/data_mirror`、`/mnt/*/emulated`、`/storage` 等一堆别名；仍然挡不住 root 的 `/proc/1/root`。
- 为了 full `/data`，还要 rbind `/data`；这会把 `/data/adb`、`/data/property`、`/data/system` 全部暴露给 chroot root，增加误改/被检测风险。
- 为了手机控制，还要 `/dev` rbind、`/sys` rw、`/system` 等；这些在私有 ns 里 App 看不到，但复杂度和权限面大幅上升。

**结论**：对“游戏/银行不抓把柄”，full `/data` + 镜像是**负资产**；不是“更完美”，是“更多痕迹”。

---

## 3. 推荐架构：directory rootfs + 不 full `/data` + 宿主 thin agent

```
┌────────────────────────────────────────────────────────────┐
│ Android 本机（唯一设备）                                   │
│                                                            │
│  ┌──────────────────────┐      ┌────────────────────────┐  │
│  │ chroot 内（Linux/DSH）│      │ 宿主 thin agent         │  │
│  │ directory rootfs      │      │ Shizuku/ADB/无障碍       │  │
│  │ /data/adb/chroot/rootfs│     │ UI/通知/剪贴板/包管理     │  │
│  │ /sdcard/DSH 工作区    │      │ 通过 Unix socket 调用     │  │
│  │ /sys ro, /dev minimal│      │                        │  │
│  │ 无 image/loop/overlay │      │                        │  │
│  └──────────────────────┘      └────────────────────────┘  │
│        同一内核、同一手机、无虚拟机、无 proot               │
└────────────────────────────────────────────────────────────┘
```

### chroot 内
- rootfs：**目录** `/data/adb/chroot/rootfs`，不 loop、不镜像、无 `/sdcard` 镜像文件。
- 挂载：`/proc`、`/sys` **ro**、最小 `/dev`（null/zero/random/ptmx + devpts + shm）、`/tmp`、`/run`、`/root/.dsh`、`/opt/dsh-control`、`/opt/dsh`、`/var/log/dsh`。
- 工作区：`/data/media/0/DSH` → `/sdcard/DSH`（只挂这一个子目录，不挂整块 `/data/media/0`）。
- **不挂** `/data`、`/mnt`、`/data_mirror`、`/storage`、`/system`/`/apex` 等 Android 分区。
- 结果：App 看不到 rootfs 镜像/loop/控制面；`/sdcard` 只有普通 `DSH` 目录；没有 full `/data` 的 alias 问题。

### 宿主 thin agent
- 运行在 Android 本机（可用 Shizuku/ADB/无障碍/root），负责：
  - `am start`/`pm install`/`pm clear`/`settings put`；
  - UI 树/点击/滑动/输入/截屏/录屏；
  - 通知、剪贴板、包管理、媒体扫描；
  - 需要 root 的 Android 控制。
- DSH/chroot 通过 **Unix socket** 调 agent；agent 不在 `/sdcard` 留脚本，不接受任意 shell；只暴露固定命令集。
- 这样 chroot 不需要 `/dev` rbind、`/sys` rw、`/system`、`/data`，隐藏性最好；手机控制反而更完整（UI 树/通知/剪贴板）。

### 代价
- chroot 内不能直接 `am/pm/input` 或读 `/data/data`；这些由宿主 agent 做。
- chroot 是“完美 Linux + DSH”，不是“Android 全视图”；但游戏/银行隐藏性最好。
- 宿主 agent 是额外组件；需要开发/授权；但它不是 root 标记（普通 App 形态）。

---

## 4. 在这个目标下，之前决策怎么选

| 编号 | 选择 | 理由 |
|---|---|---|
| 1 | **1B. 不 full `/data`** | 避免镜像/控制面经 `/data/user/0` 等别名暴露；目录 rootfs 不需要镜像 |
| 2 | **2A. `/sys` ro** | 不需要内核调试；`/sys` rw 是负资产 |
| 3 | **3C. 最小 `/dev`** | 手机控制走宿主 agent；chroot 不需要 block/binder |
| 4 | **4B. 宿主 accessibility/agent** | UI/通知/剪贴板/包管理更完整；chroot 不碰 |
| 5 | **5B. chroot + 宿主 agent** | 隐藏性/手机控制都更好 |
| 6 | **6B. protect-critical ro** | 不挂 `/mnt`/`/metadata` 也无所谓；保留防砖 |
| 7 | **7A. system-rw off** | 不挂 `/system`，更无 overlay 痕迹 |
| 8 | **8C. directory rootfs（无镜像）** | 无 loop/无 rootfs.img；这是隐藏性关键 |
| 9 | **9A. rootfs/state + `/sdcard/DSH` restic** | 目录 rootfs 可用 tar 快照；无需镜像 |
| 10 | **10A. 备用机/VM 先测** | 仍要验证 mount/chroot |
| 11 | **11A. root-free + hardguard** | 保留；hardguard 只护栏 |
| 12 | **12A. 开机 full + last-good** | 目录 rootfs 回滚用 tar；boot counter |
| 13 | **13A. 接受 `u:r:ksu` permissive** | App 看不到域；全局 enforce 保持；不做复杂自定义域 |
| 14 | **14D. 不挂 Android 分区** | 隐藏性优先；手机控制交给 agent |
| 15 | **15D. 不挂 `/storage`** | 同上 |

**推荐组合（隐藏性优先）**：

`1B 2A 3C 4B 5B 6B 7A 8C 9A 10A 11A 12A 13A 14D 15D`

---

## 5. 这个架构下“完美”的含义

- **完美 chroot**：真 chroot、目录 rootfs、Go/Node/apt/编译、s6、DSH、可快照/回滚；不 proot、不 VM。
- **完美手机控制**：宿主 agent 做 UI/通知/剪贴板/包管理/截屏；比 chroot 内 `input` 盲点更完整。
- **应用层隐藏性**：`/sdcard` 只有 `DSH` 工作区；无 `.dsh` 镜像、无 loop、无全局 prop、无 permissive、无 Android 分区 mount；游戏/银行看不到“本模块”的痕迹。
- **仍然不是安全边界**：chroot 内 root 仍可 `/proc/1/root`；但这不在游戏/银行检测面内；我们不把它当安全目标。

---

## 6. 如果坚持 full `/data` + full Android 视图

那就必须接受：
- `/sdcard/.dsh`/rootfs.img/loop 这些 App 可见痕迹；
- `/data` 别名遮罩不完整；
- 只能做“防手滑”，不能承诺游戏/银行完全不抓把柄；
- 需要 spare device 做应用层检测矩阵（rootbeer/Play Integrity/银行 App）。

**这两者不可兼得**：full `/data` + 镜像 = 能力优先；directory + 不 full `/data` + 宿主 agent = 隐藏优先。按你“游戏/银行不抓把柄”的定义，推荐后者。
