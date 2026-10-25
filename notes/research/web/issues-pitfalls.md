# Issue 采矿：真实坑（B 级证据）

- 日期：2026-10-26 ｜ 方法：GitHub search API + issue 原文（有复现步骤/日志的才算 B 级；一句话报告算 C 级）
- 用途：把别人踩过的坑写进设计，避免想当然

## 高价值 issue 与设计影响

| 来源 | 标题/要点 | 教训 | 对设计的影响 |
|---|---|---|---|
| KernelSU #3693（closed） | post-fs-data.sh 在 APEX 激活前运行（Magisk 会等 ~40s），依赖 /apex 的模块静默失败 | 官方文档说与 Magisk 语义相同，实测不成立 | 我们不用 post-fs-data，只用 service.sh（late_start service，APEX 已激活）[设计 §6] |
| chroot-distro #101（closed） | Freezes while exit from chroot，kill 也失败 | 退出时可能卡在进程/挂载上 | 停止状态机必须 TERM→等待→KILL→逆序 umount，并用 pid+starttime 校验 [设计 §6] |
| chroot-distro #102（closed） | Failed to apply special mounts: mount -t devpts … Device or resource busy | devpts 目标已被占用/残留挂载 | 挂载前检查目标；用私有 mount ns + newinstance devpts；EBUSY 时先清残留而非硬挂 [设计 §5] |
| KernelSU #3396（closed） | uninstall.sh 需要可执行位才生效 | 打包时必须 chmod 755 所有脚本 | 打包门禁：module.prop/customize.sh/service.sh/uninstall.sh 全部 0755 |
| KernelSU #3511（closed） | 在 service.sh 里挂 overlayfs 的讨论 | 挂载时机/命名空间容易踩坑 | 我们坚持私有 ns + 显式挂载表，不做隐式挂载 |
| SukiSU #975（closed） | 浏览器 Open with SukiSU 不会直接开始安装模块 | SukiSU 的安装入口与 KernelSU 不同 | 安装文档要写：管理器 → 模块 → 从文件安装，不要指望打开方式 |
| chroot-distro #104/#105/#111 | 权限异常、login 后 exec、音频问题 | 容器工具的边缘问题多 | 我们不自己写容器管理，rootfs 用官方 tarball 解压，避免继承这些边缘 bug |

## 从 issue 里得到的通用规律

1. 文档与实现会分叉（KernelSU 的 post-fs-data 语义就是例子）：凡是官方说「和 X 一样」的，都要在本机实测。
2. 退出/清理比启动更容易出事（#101、#102）：设计重心要放在 stop/umount/残留检查，而不是只写 start。
3. 安装器/管理器行为不可假设（#975、#3396）：模块打包与安装路径要写死、要自检。
4. issue 质量差异大：带复现步骤 + 日志 + 版本的是 B 级；只有一句「不能用」的是 C 级，只能当线索。

## 待补

- [ ] node-pty 的 musl/prebuild issue（决定绝不选 Alpine 的第二个证据）
- [ ] s6 在 Android/chroot 下的 issue（是否有人报过 supervise 不工作）
- [ ] KernelSU/SukiSU 关于 su -M（mount-master）语义的 issue
