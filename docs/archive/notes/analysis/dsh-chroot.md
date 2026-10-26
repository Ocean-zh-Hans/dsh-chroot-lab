> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# 分析：SunsetRNE/DSH-Chroot

- 来源：`sources/SunsetRNE_DSH-Chroot`（commit `da5da52`，2026-10-19；仓库 2026-10-17 创建）
- 许可：MIT ｜ 规模：75 文件 / 6.4MB，其中 **文档 3687 行**（decisions 1416、research 533、versioning 364、guide 262、recovery 254）
- 发布：**没有 Release，当前装不了**。作者自述"早期开发中，仓库里目前是设计文档与工具链"
- 基准设备：一加 12（PJD110）/ Android 16；Root：KernelSU 系（ReSukiSU/BakaSU LKM）；依赖 `chroot-distro` 模块；约 2GB 空间
- 一句话评价：**代码还没到能装的程度，但它是这批项目里"设计密度"最高的：23 条反例、26 条 ADR、一份带"不设防声明"的威胁模型。我们的设计文档应当直接以它的结构为模板。**

---

## 1. 问题与约束

- 目标：**用 root 容器替代 DSHA**（不是补充）。ADR-001 明确"阶段一不碰内核"。
- 非目标写在 `research.md §1.3`。
- 明确的工程纪律（`guide.md §7` 边界声明、`threat-model.md §7` 变更纪律）：
  1. 加功能前回答三问（资产/对手/边界）；
  2. 加写盘位置必须登记进 ADR-017 的残留面清单；
  3. 加执行出口必须更新 ADR-020 的收口清单；
  4. 改边界（挂载表/入口/权限面）必须同时改威胁模型的 §3 与 §6；
  5. §5"不设防"清单定期复查，某条从可接受变不可接受就升级成新 ADR。

## 2. 运行模型

- **四层 + 适配器**（`research.md §4.1`）：壳对下层不敏感，所以能先做壳、后换运行时。
- **目录布局**（§4.2）：`state/` 与版本层**彻底分离**。
- **挂载机制"两套，别混"**（§4.3）。
- **两段式模块**（`module.md`/`payload.md`）：模块 zip 只放 CLI+壳+manifest（KB 级），**约 1GB payload 放 `/data/local`**，按需解包；state 永不覆盖。
- **动态端口**：不自己探测空闲端口（TOCTOU），而是让 DSH 自己 `bind(0)`（`dsh web --port 0`，`--help` 明写），端口回传发布。
- **版本策略 floor-only**（§5）：只设下限，三道闸（layer/生成器/运行时各自校验），四条流程。

## 3. 生命周期

- 安装：**全部逻辑在 `customize.sh`**；开机路径见 `module.md §3`；卸载见 §4（清空模块域，不留幽灵挂载/服务）。
- 现状：无 Release，所以这份分析全部来自文档与工具链，**没有真机验证可参考**（作者标注"尚未验证"）。

## 4. 数据与更新

- `state/` 与版本目录分离；`~/.dsh` 只通过 bind 挂进容器（`state/dot-dsh` → `/root/.dsh`）。
- payload：每层两件套共 4 个文件，manifest schema 1，**五道校验链**，确定性构建。
- 升级：不推送上游更新（ADR-015）；预发布只认 RC 通道，alpha 不碰（ADR-022）；拒绝降级（ADR-018）。

## 5. 为什么这样写（反例集 A1-A23 摘要，这是全文最值钱的部分）

| # | 弯路 | 为什么错 / 正确做法 |
|---|---|---|
| A2 | 把 `sabamdarif/chroot-distro` 当模块 | 那是 **pip 的 Python CLI**，仓库里没有 `module.prop`；模块在同名的另一个仓库。判据：**看有没有 module.prop** |
| A4 | 有 root 还套 proot/DSHA 当基线 | proot 是用户态 syscall 翻译，root 本可不要这层；正确做法是 chroot + bind mount |
| A5 | 自己造动态端口（探测/扫 netstat） | 探测→bind 之间必然 TOCTOU；**让服务自己 bind(0)**，DSH 原生支持 `--port 0` |
| A6 | 把 439MB 的 DSH 塞进模块 zip 或 /system | 模块目录是挂载源；**分包**：zip 只放 KB 级壳，payload 放 /data/local |
| A7 | 把 `~/.dsh` 放进版本目录 | 升级即丢 session 与凭据；state 与版本层必须分离 |
| A8 | 以为 root 容器里的"只读/不可变"是安全边界 | 容器里的用户也是 root；要真只读用 **`mount -o remount,ro,bind`**；floor 只是"防手滑+防降级+可回滚"的护栏 |
| A9 | 直接 `--host 0.0.0.0` | DSH 的 /api 有浏览器信任栅栏，且等于把控制面裸奔；保持 127.0.0.1，由壳反代 |
| A10 | 相信"下载即安全" | chroot-distro 的 `install` **不校验哈希**，部分发行版是 **http 明文源**；正确做法：自备官方 https 的 rootfs tarball，**自己算 sha256** |
| A11 | 依赖 chroot-distro 模块的在线更新 | `update.json` 停在 v2.1.0（2025-06），main 已多 8 个提交未发版 → **点更新永远拿不到**；手动替换 + 核 sha256 |
| A12 | 在 Alpine 上"反正 Node 能装" | **node-pty 只发 glibc 预编译**；cua-driver 的 musl 包 404 且是 optionalDep → **静默降级**。正确做法 Ubuntu 24.04 glibc |
| A14 | 拿 DSH 版本号当稳定标志 | 上游自己定义 rc 语义，连 engines 都没有；**自己定 floor**，用 prerelease 感知的比较 |
| A15 | 期望纯容器完全替代 DSHA 的设备控制 | 读屏/点按/剪贴板/通知是 App 层能力（无障碍/Shizuku/MCP）；**保留极薄设备桥**（uiautomator dump + input + screencap + am/pm） |
| A16 | 先做 1GB 打包，最后才试跑 | 命门是 **node-pty 在 chroot 里能否起 PTY** 与 `dsh web --port 0`；先最小验证再打包 |
| A17 | 把 DSHA 的 `/app/*` 桥当核心链路 | 那是 App 层接口，App 变动就断；壳的核心是 root + UDS，桥只做可选增强 |
| A18 | 忽略容器与宿主 `/data` 的挂载标志 | `/data` 可能带 nosuid/nodev/noexec；chroot-distro 的 fix-suid 会 remount **宿主** /data |
| A19 | 再写一个插件管理器 | **DSH 已经有了**（plugin-manager/lifecycle/transactions/dependencies + locks/sources/activations/history…）；再写只会抢 `profiles/<name>/node_modules` 并对不上账 |
| A20 | 在 Android 的 sh(mksh) 上用 `${s%%\|*}` 切字段 | mksh 对未加引号的 `|` 处理与 dash/bash 不同（真机实测返回空串）；用 `IFS='\|'; set --` 或 `while IFS= read`；**设备脚本先在 mksh 上跑一遍** |
| A21 | 把 DSH 版本号编进 module.prop 第 4 位 | semver 的序是**稠密**的，整数编码不可能保序（final 必须大于所有 rc）；且一位不能同时等于 floor 与 baked；**version 永不参与比较**，只用 versionCode |
| A22 | 模板占位符 `@@{...}` 漏展开 | `set -u` 下变成普通字面量进入错误分支；**测试缝变量会掩盖生产故障**，要有"不设变量也能跑"的断言 |
| A23 | 在模块包根放 `install.sh` | Magisk/KernelSU 用"zip 里有没有 install.sh"判断 legacy 模块，命中后**只解压 module.prop/install.sh/uninstall.sh**，bin/ lib/ 不解 → 空 staging 目录、装不上。**包根绝不能有 install.sh** |

## 6. 威胁模型（对我们的价值最大）

**§3 六条信任边界**：宿主↔容器、容器内 AI↔宿主 root、DSH↔插件（上游承认"进程内、沙箱外、不是安全边界"）、插件↔凭据、本机↔局域网、用户空间↔模块域。

**§4 威胁↔ADR 对账表**：每条威胁都有归属，没归属的不算处理过。

**§5 不设防声明（防虚假安全感）**，七条值得原文抄进我们的设计：
- 不防有 root 的攻击者；不防物理接触；**不防提示注入本身，只能限制爆炸半径**；不防 DSH 上游自身漏洞；不防内核/固件；不防用户自己 purge；**不防出网目的地**（chroot 与宿主共享 netns，强制不了）。

**§6 现在开着的口子（都带实测证据）**：
- 6.1 凭据：设备桥 token 32B 明文，**比模型 API key 危险得多**（拿到就读屏/点按/启停应用）；而 `0600` 在"AI 以 root 跑"的前提下**是纸糊的**，先降权才有意义。
- 6.2 用户存储全量暴露：chroot-distro 的 `android-bind` **默认开启**，会把 Android 根与 /sdcard 整体 bind 进容器；他们打算显式禁用再逐条 bind。
- 6.4 **沙箱层失效**：Landlock 探测 rc=125、bwrap 未安装 → 本机没有中间档，只有 danger-full-access；
- 6.5 容器内 `/proc/net/tcp` **读不到**（无法自证监听）；宿主侧查到 17 个监听，其中他们自己的 3080/3090 只在回环。
- 6.7 `/data/adb/ksu/log/logcat.log` 世界可读（`-rw-rw-rw-`），含全系统日志。

**与我们的差异**：用户明确"不管隐私、要全能" → 我们**不需要消除** 6.2 这类暴露；但要把它登记进"爆炸半径"清单，并用**可逆性**兜底——这正是他们做了威胁模型但没做的一层。

## 7. 本机实测修正（我们的设备 ≠ 他们的设备）

root 读 `/proc/config.gz`（PJZ110 / Android 15 / kernel 6.6.66）：

```
CONFIG_NAMESPACES=y
# CONFIG_USER_NS is not set
# CONFIG_PID_NS is not set
# CONFIG_SECURITY_LANDLOCK is not set
CONFIG_LSM="landlock,lockdown,yama,loadpin,safesetid,selinux,smack,tomoyo,apparmor,bpf"
```

配合实测：`unshare -U`、`unshare -p --fork`、`unshare -Urm` 全部 `Invalid argument`；`unshare -m` 正常。

结论（**这些是内核没编译，不是 SELinux/seccomp 拦的**）：
1. chroot-distro 的 userns 隔离档（Tier A/B）在本机**永远不可用**，不要照它设计。
2. **没有任何 PID 隔离** → 容器内进程可见（并在 root/UID1 下可操作）宿主进程。
3. DSH 的文件沙箱在本机**永远不可能工作**（Landlock 未编译），只剩 danger-full-access——与我们第一天的实测一致。
4. 隔离上限就是：**mount ns + chroot + UID/组 + capabilities**。安全只能靠可逆性与规则。

对照他们的 §6.4：**结论相同，成因不同**（他们缺 bwrap/内核未启用，我们直接没编译）。所以不同机型的结论不能照抄，**必须在目标机上实测**——这一条已写进我们的需求文档。

## 8. 对我们的启示

**设计文档结构直接采用他们的**：目标/非目标 → 现状盘点 → 路线比较 → 架构（四层+适配器）→ 版本策略 → 端口/通信 → 威胁模型（含不设防清单）→ 变更纪律。

**直接采纳的规矩**：A23（包根无 install.sh）、A6（模块小 + payload 放 /data/local）、A7（state/版本分离）、A5（bind(0) 不猜端口）、A20（mksh 方言）、A21（version 不参与比较，用台账 seq）、A10/A11（自备并校验 rootfs，别信在线更新）、A12（glibc，理由从"省补丁"升级为"避免静默功能缺失"）、A8（要真只读用 ro bind）、A15（保留极薄设备桥）、A16（先验命门再打包）。

**要补的（他们明确没做）**：快照/回滚、回收站、命令审计、规则 deny/ask 清单——也就是我们 H7/H8。

## 9. 待验证

- [x] 目标机 kernel 能力清单（见 §7，2026-10-26）
- [ ] `node-pty` 在 chroot 里起 PTY（命门）
- [ ] `dsh web --port 0` 的端口回传格式
- [ ] A23（install.sh 导致装不上）在 ReSukiSU 上是否复现 —— 需要装模块，**等用户批准后再做**
