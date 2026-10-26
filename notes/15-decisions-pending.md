# 待拍板决策单（v1.4 红队后）

- 日期：2026-10-26
- 用法：直接回复编号+字母，例如 `1A 2A 3C 4B`。
- **先看 `16-hiddenness-first.md`**：如果“隐藏性”指游戏/银行不被抓到把柄，推荐组合是 `1B 2A 3C 4B 5B 6B 7A 8C 9A 10A 11A 12A 13A 14D 15D`。
- 前提：只有一台手机；“chroot 内”只是同一台手机上的进程树，不是虚拟机/第二台设备。
- 当前状态：v1.4 **未批准开工**，先修 5 个 P0；这份单子决定“修成什么样”。

---

## 1. 内部路径遮罩：`/data/adb/chroot`、`/data/media/0/.dsh`、`/sdcard/.dsh`

这些是控制面/rootfs 镜像，不是用户数据。`/data` 仍完整 rw，只遮内部路径。

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| **A. tmpfs 遮罩** | 先遮这三个路径；**full `/data` 还有 `/data/user/0` 等别名，见 §14** | chroot 内看不到/写不到控制面和镜像；Android 本机与 `dsh-ctl` 仍可见；用户数据不受影响 | 三路径不够；full `/data` 必须枚举所有别名，否则镜像仍可写 | ⚠️ 仅当按 §14 做全别名 |
| B. 只读 bind 占位 | 把空文件/空目录 bind 到镜像/控制面路径 | chroot 能看到路径但写不了 | 路径仍在，容易误判；bind 文件可能影响工具 | 可选 |
| C. 不遮 | 保持现在 full `/data` | 最大透明度；chroot 内 root 可 truncate/unlink 自己的 `rootfs.img` | **P0 自引用损坏**；重启/快照可能找不到镜像 | ❌ 不建议 |

**区别一句话**：A 是“藏起来”，B 是“看得见但碰不得”，C 是“把刀递给自己的 rootfs”。

> 红队 v2：**A 的三个路径不是完整保护**；`/data/user/0`、`/data_mirror`、`/mnt/*/emulated`、`/storage` 等别名都能到 `.dsh`/control。要 full `/data` 就按 §14 枚举测试；否则选 1B。

---

## 2. `/sys` 模式

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| **A. `/sys` ro + debugfs/tracing 单独 rw** | `/sys` 只读；`debugfs` 挂 `/sys/kernel/debug`、`tracefs` 挂 `/sys/kernel/tracing`，两者 rw | 保留内核调试/BPF/ftrace；不能通过 sysfs 改 SELinux/cgroup/内核参数 | 部分依赖 `/sys` rw 的工具会失败；需要单独挂 debugfs/tracing | ✅ **推荐** |
| B. `/sys` rw | 保持完整 sysfs 写 | 最大内核控制 | 可直接 `echo 0 > /sys/fs/selinux/enforce`；hardguard 可绕过；root 隐藏性/稳定性风险 | ❌ 不建议 |
| C. `/sys` ro，不挂 debugfs/tracing | 最保守 | 隐藏性最好 | 失去 BPF/ftrace/kprobe/frida 等调试能力，不算“完美手机控制” | 仅隐藏优先时 |

**区别一句话**：A 保留“调试能力”但关掉“全局内核改写”；B 全开；C 连调试都不要。

---

## 3. `/dev` 模式

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| A. rbind 全部 `/dev` | binder/ashmem/block/loop/usb 全见 | 最大设备控制；可碰分区/loop | chroot 内 root 可写块设备；loop/分区风险；root 隐藏性不是绝对 | 只有需要刷机/分区时 |
| **B. rbind + 遮 `/dev/block`、`/dev/loop*`** | 保留 binder/ashmem/graphics/input 等；遮块设备/loop | 手机控制（am/pm/input/binder）完整；不能直接写分区/loop | 失去 raw 分区/loop 操作 | ✅ **推荐**（如果 root 隐藏优先） |
| C. 最小 `/dev` | null/zero/random/ptmx + devpts + shm | 最干净；手机控制走宿主 agent | 失去 chroot 内 binder/ashmem | ✅ 隐藏性优先（配合 4B/5B） |

**区别一句话**：A 是“连分区都能碰”，B 是“手机控制够用、不碰块设备”，C 是“只够跑 Linux”。

---

## 4. UI 自动化 / 手机控制

`uiautomator dump` 在本机不可用（rc=137）；`uiautomator` 只剩 `runtest`。

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| A. `uiautomator runtest` + 自建测试 jar | 用 UiDevice 写测试 jar，跑 runtest | 不装额外 App；可做固定流程自动化 | 没有通用 UI 树；要写/维护 jar；对任意 App 不够灵活 | 轻量方案 |
| **B. 宿主侧 accessibility/agent** | 在 Android 本机装一个 thin agent（无障碍/Shizuku/ADB），chroot 通过 socket 调它 | 能拿 UI 树、通知、剪贴板、点按；root 隐藏性更好（chroot 不直接碰这些） | 多一个 App/服务；要授权；架构多一层 | ✅ **“完美手机控制”推荐** |
| C. `input` + `screencap` | 盲点 + 截图 | 零依赖 | 无 UI 树、坐标脆弱、FLAG_SECURE 黑屏、IME 输入不稳 | 兜底 |
| D. 继续等 `uiautomator dump` | — | — | 本机不可用，不要再写进验收 | ❌ |

**区别一句话**：A 是“固定流程自动化”，B 是“真正的手机控制”，C 是“盲操作”。

---

## 5. 架构：全部塞 chroot，还是 chroot + 宿主 thin agent

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| A. 全部在 chroot | DSH root + full `/data`/`/dev`/`/sys`，手机控制也在 chroot | 单环境、简单；能力最大 | root 隐藏性只能“尽力”；UI/通知/剪贴板仍受限；一个 root 进程可碰一切 | 能力优先 |
| **B. chroot 做 Linux，宿主 agent 做手机控制** | chroot 保留 DSH/apt/Go；宿主 thin agent 做 UI/通知/剪贴板/包管理 | 手机控制更完整；chroot 可少碰 `/dev`/`/sys`；隐藏性更好 | 两个组件；要写/装 agent；调试多一层 | ✅ **“完美手机控制”推荐** |
| C. 缩 chroot | 只给 `/sdcard/DSH`、最小 `/dev` | 最隐藏 | 失去 full `/data`/设备控制，不符合“完美 chroot” | 隐藏优先时 |

**区别一句话**：A 是“一个 root 全能”，B 是“Linux 归 Linux，手机归 agent”，C 是“退回受限环境”。

---

## 6. 高危区 `protect-critical`

对象：`/metadata`、`/mnt/vendor/persist`、`/mnt/vendor/qmcs`。

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| A. 全 rw（当前） | 不保护 | 最大权限 | 写错可能变砖/丢 IMEI/认证 | 只有明确要改时 |
| **B. 默认 ro** | 三个路径 remount ro；`--protect-critical` 可关 | 正常手机控制不受影响；防手滑 | 不能直接改 persist/qmcs/metadata | ✅ **推荐** |
| C. 选择性 | 例如 metadata ro，persist/qmcs rw | 折中 | 仍需记住哪个能写 | 可选 |

**区别一句话**：这三个不是日常手机控制需要的；默认 ro 几乎不损失便利，但能防大事故。

---

## 7. `system-rw` overlay

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| **A. 默认 off** | `/system` 等保持硬件 ro；需要时 `dsh-ctl system-rw on` | 最稳；不触发 overlay/AVB 风险 | 不能直接改系统分区 | ✅ **推荐** |
| B. 默认只给 `/system` 开 | ext4 upper；只 `/system` 可写 | 可改系统；保留其它 ro | overlay 对 AVB/APEX/linker 兼容性未验证；可能被 root 检测 | 单分区实测后再考虑 |
| C. 所有分区开 | system/vendor/product/odm/system_ext 全 overlay | 最大可写 | 兼容性/检测/回滚风险最高 | ❌ 不建议默认 |

**区别一句话**：A 是“系统分区只读”，B 是“只改 system”，C 是“全改”。

---

## 8. 镜像 128GB vs 256GB

| 选项 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|
| **A. 128GB 起步** | 实际 rootfs 12–16GB；够工具链/多项目/缓存；不够再 `resize2fs` 到 256GB | ext4 只能增不能减；以后要扩一次 | ✅ **推荐** |
| B. 直接 256GB | 给本地 GGUF/Android 源码/大缓存留足 | 元数据/fsck 更慢；填满后 7 份快照更容易超 `/data` | 确定要放模型/大仓库时 |
| **C. directory rootfs（无镜像）** | 目录 rootfs，不 loop、不 `/sdcard/rootfs.img`；快照=tar | 无镜像/loop 痕迹；最适合应用层隐藏 | 不能 full `/data` rbind（会自递归）；容量受 `/data` 文件系统 | ✅ 隐藏性优先 |

**区别一句话**：128 是“先够用，能长”；256 是“一开始就大，但不能缩”。

---

## 9. 快照范围

| 选项 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|
| **A. rootfs 镜像 + state + `/sdcard/DSH` restic** | 系统可回滚；工作区有外部备份；`/data`/`/sdcard` 其余不承诺 | `/data` 其余仍不可逆 | ✅ **推荐** |
| B. 本地也快照 `/sdcard/DSH` | 多一份本地副本 | 占空间；仍需外部备份 | 可选 |
| C. restic 全量 `/data` + `/sdcard` | 最大可逆 | 时间长/带宽大；`/data` 变化频繁不一定一致 | 仅明确需要时 |

**区别一句话**：A 保“系统和 DSH 工作区”，B 多一份本地工作区，C 保整机数据但代价大。

---

## 10. 测试环境

| 选项 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|
| **A. 备用机/VM 先做 mount/chroot 测试** | 主力机不承担首次 apply 风险 | 需要备用设备/VM；环境不同要重测 | ✅ **推荐** |
| B. 主力机 + private ns + 维护窗口 | 直接在目标机验证 | 仍有内核/挂载风险；必须备份、有人值守 | 次选 |
| C. 只做静态/只读验证 | 最安全 | 无法证明 mount/chroot/phone control | 只能做 P0 代码检查 |

**区别一句话**：A 是“先在小号试”，B 是“主力机带护具试”，C 是“只看不跑”。

---

## 11. 规则默认

| 选项 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|
| **A. root-free + hardguard** | 不弹批准；只拦 SELinux/全局 prop/全局 mount | 最顺手；hardguard 不是安全边界 | ✅ 当前选择 |
| B. root-guard | 默认 ask/deny | 更安全；会打断自动化 | 需要时切 |
| C. 只 hardguard，无 audit | 最少日志 | 出事无法复盘 | ❌ 不建议 |

**区别一句话**：A 是“不挡日常，只挡会掉隐藏性的”，B 是“危险操作先问”，C 是“什么都不记”。

---

## 12. 开机行为

| 选项 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|
| **A. 开机 full + last-good 回滚** | 正常自启；连续失败 3 次回滚/进 rescue | 需要 boot counter/回滚代码 | ✅ **推荐** |
| B. 开机 rescue | 每次先进维修模式，手动 start | 安全但麻烦 | 调试期 |
| C. 不自动启动 | 最可控 | 失去 H3 开机自启 | ❌ 不符合目标 |

**区别一句话**：A 是“正常自启但坏了自己回滚”，B 是“每次手动”，C 是“不自启”。

---

---

## 13. SELinux 域：`u:r:ksu:s0` 怎么处理

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| **A. 接受 `u:r:ksu:s0`** | 全局 Enforcing 保持；chroot 域本身是 KernelSU permissive allow-all | root 可做任何事；SELinux 不是边界；hardguard 只是护栏 | 不能声称“SELinux 保证 root 隐藏性”；需在文档如实写 | ✅ 现状/能力优先 |
| B. 自定义非 permissive 域 | 写 SELinux 策略，只允许 chroot 需要的东西 | 更接近真实约束；可能保住隐藏性 | 复杂、可能破坏 KSU/Android 工具；需 spare device 验证 | 未来 v2 |
| C. 非 root DSH + 宿主 agent | DSH 降权；手机控制走宿主 | 隐藏性最好 | 失去 apt/mount/full root；与“完美 chroot”冲突 | 仅隐藏优先 |

**一句话**：A 是“承认没有 SELinux 约束”，B 是“自己写策略”，C 是“放弃 root”。

---

## 14. `/data` 内部路径保护（修正 1A）

红队发现 `/data` rbind 的别名很多：`/data/user/0`、`/data_mirror/data_ce/null/0`、`/mnt/pass_through/0/emulated`、`/mnt/installer/0/emulated`、`/mnt/androidwritable/0/emulated`、`/mnt/user/0/emulated`、`/storage/emulated`。**1A 的三路径 tmpfs 遮罩不够。**

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| A. full `/data` + 全别名遮罩 | 枚举所有别名，逐个 tmpfs/ro 遮 `.dsh`、`control`、`modules`、`ksu` | 保留 full `/data`；降低误删 | 别名动态、难穷尽；root 可 unmount；`/proc/1/root` 仍绕过 | 仅“全量控制优先” |
| **B. 不 full `/data`** | 只给 `/sdcard` raw + 选定用户目录；镜像/控制面从 chroot 内不可见 | 镜像/控制面最难被写；最稳 | 失去 `/data/data`、`/data/system` 等完整视图 | ✅ 完整性/隐藏性优先 |
| C. full `/data` 不遮 | 当前 v1.4 | 最大控制 | **P0 自引用/控制面可写**；可能毁 rootfs | ❌ |

**一句话**：A 是“全开但加锁”，B 是“少开但锁死”，C 是“全开不锁”。

---

## 15. `/apex` / `/linkerconfig` 只读

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| A. 顶层 `remount,ro,bind` | 只让顶层 VFS 只读 | 方便；子挂载仍可写（cacerts rw） | 不能声称整树只读；remount-bind 机制未证实 | 临时 |
| **D. 不挂 Android 分区** | chroot 只做 Linux/DSH；Android 控制走宿主 agent | 最少痕迹；无 /apex/linker 问题 | 失去 chroot 内 Android CLI/系统视图 | ✅ 隐藏性优先 |
| **B. 递归 `mount_setattr(AT_RECURSIVE)`** | util-linux/mount 支持时递归设 ro | 真正整树只读 | 需要静态 util-linux；需 spare device 测试 | ✅ 目标 |
| C. 不设 ro | rbind 原样 | 最简单 | 失去只读保护；与“完美”不符 | ❌ |

**一句话**：A 是“看起来只读”，B 是“真的递归只读”，C 是“不设防”。

---

## 16. `/storage` 范围

| 选项 | 具体做法 | 选它意味着 | 代价/风险 | 推荐 |
|---|---|---|---|---|
| **A. rbind 整个 `/storage`** | 包含 `/storage/self`、`/storage/emulated`、各用户视图 | 完整存储视图 | 暴露更多 FUSE/用户目录 | ✅ 完整控制 |
| B. 只挂 `/storage/emulated/0` | 当前 v1.4 | 基本文件管理够用 | `/storage/self`、其他用户缺失 | 可选 |
| C. 逐个绑定 `/storage/self` + `/storage/emulated` + 用户 | 精确 | 可控 | 需要枚举用户 | 复杂 |
| **D. 不挂 `/storage`** | chroot 只挂 `/sdcard/DSH` | 最少痕迹 | 失去完整存储视图；文件管理走宿主 | ✅ 隐藏性优先 |

**一句话**：A 是“完整”，B 是“只当前用户”，C 是“手工枚举”。

---

## 17. `/tmp` 挂载点（无需拍板，已修）

- `/run` 在本机不存在（root 是 ro erofs）；`/tmp` 是宿主 tmpfs。
- 设计改为 `/tmp/dsh-rootfs`；start 前 `mkdir -p /tmp/dsh-rootfs; chmod 700`；doctor 检查存在/可写。
- 这是事实修正，不是选择题。

---

## 推荐组合（红队 v2 修订）

如果 **游戏/银行隐藏性优先（推荐）**：`1B 2A 3C 4B 5B 6B 7A 8C 9A 10A 11A 12A 13A 14D 15D`

如果 **完整性/隐藏性优先（保留部分 Android 视图）**：`1B 2A 3B 4B 5B 6B 7A 8A 9A 10A 11A 12A 13B 14B 15A`
（13B 自定义非 permissive SELinux 域复杂；若不做 13B，就必须接受 13A 的“SELinux 不保证”并如实写。）
如果 **full `/data` 必须保留**：`1A 2A 3B 4B 5B 6B 7A 8A 9A 10A 11A 12A 13A 14B 15A`，并接受 1A 只是防手滑、root 仍可绕过。

## 我的推荐组合（供直接拍板）

`1A 2A 3B 4B 5B 6B 7A 8A 9A 10A 11A 12A`

即：

- 内部路径 tmpfs 遮罩；
- `/sys` ro + debugfs/tracing 单独 rw；
- `/dev` rbind 但遮 block/loop；
- 手机 UI 控制走宿主 thin agent；
- chroot 做 Linux/DSH，宿主 agent 做手机控制；
- persist/qmcs/metadata 默认 ro；
- system-rw 默认 off；
- 128GB 起步；
- rootfs+state+`/sdcard/DSH` 备份；
- 备用机/VM 先测；
- root-free + hardguard；
- 开机 full + last-good 回滚。

如果你只想改最少：至少选 **1A + 2A + 3B + 6B + 12A**；这五个直接对应 P0 事故/隐藏性/砖风险。
