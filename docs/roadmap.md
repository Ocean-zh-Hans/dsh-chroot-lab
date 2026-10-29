# 路线与当前阶段

## 0. 现状

- P0：设计定稿、决策锁定、旧文档归档 — **完成**
- P1：静态 module、只读控制面、PC 构建与 mock 检查 — **完成（2026-10-26）**
- P2：备用机完整 128GB Ubuntu/DSH、20 次启停和零残留通过；P3 运行时/快照/回滚/真实开机已实测，P4 固定手机控制接口已实测，P5 应用检测与迁移仍未完成。
- 当前实测仅针对备用机 PJE110；**主力机仍禁止安装、apply、mount/loop、chroot、SELinux 或全局 prop 修改**。

“P1 完成”只表示：脚本解析、ShellCheck、shfmt、helper/APK/模块构建、mock 与失败注入测试通过。它不表示 chroot 已能在手机启动。

## 1. P0：设计与决策（完成）

- 统一镜像、挂载点和控制面路径。
- 保留用户锁定的 15 项决策；不改为 proot/VM，不缩减 full chroot 能力。
- 接手审核修正：启动/停止 fail-closed、真实私有 namespace 清理、镜像/state 校验与回滚、可复现的 Android 构建链。

## 2. P1：PC 静态与行为验证（完成）

当前已验证：

- `sh`/mksh 解析、ShellCheck、shfmt；Android NDK ARM64 helper 重建及 SHA256。
- 生命周期失败清理、loop detach 失败、SELinux 恢复、mount 失败、system-rw 状态识别。
- 镜像/state 快照、缺失/损坏校验、state 中途失败回滚、purge 占用检查。
- DSH URL、ttyd 凭据、guard 配置、JSON、Console URL/退出码。
- 默认模块 zip 的权限、结构、无 `install.sh`、无全局 App overlay。

这些结果来自临时目录和 mock；不证明真实内核挂载成功。

## 3. P2：备用机 mount/namespace 验证（核心流程与 20 次启停通过）

前置：明确识别的备用机或 arm64 VM、备份、有人值守。当前主力机不进入此阶段。

PJE110 已通过真实 128GB 稀疏 ext4、完整 Ubuntu/DSH smoke 和 20 次启停。
镜像安装使用校验过的 GNU cp；当前严格 doctor 为 37 pass / 2 warn / 0 fail。
递归只读、private namespace、holder 身份、loop detach 和宿主无 chroot 挂载均有实测。
这些结果只适用于该备用机，不能外推主力机或所有内核/ROM。

已执行的完整验收：

1. `unshare --mount --propagation private --uts` 和 NDK helper 在目标环境可运行。
2. rootfs 镜像创建、挂载、e2fsck、启动与停止。
3. full `/data`、`/storage`、`/dev`、`/sys` 视图及关键目录递归只读。
4. holder 和 s6 的进程身份、private namespace、nsenter+chroot。
5. 连续 20 次 start/stop，零残留；失败时保留可恢复状态。
6. loop 真正 detach，SELinux 始终 Enforcing，宿主 mountinfo 无 chroot 挂载。

**零残留定义**：没有仍挂载本 rootfs 的实例进程、没有 rootfs 挂载、没有 holder、没有测试 loop、没有 `/tmp/dsh-rootfs` 或临时卸载清单残留。namespace inode 复用时不得误伤无关应用。

## 4. P3：DSH 与镜像运行时（备用机运行时、快照/回滚和开机已实测）

- 固定版本 DSH 的 `approval-free` 与 `approval-guard` 配置已检查；未调用模型账号。
- ARM64 rootfs 的 Node 24/node-pty/DSH web/Go 已实际构建并验证。
- Android 真机 URL HTTP 200、真实 PTY、权限预设、风险提示、审计路径、Android CLI 和工作区已实测。
- 真实 128GB 快照与 last-good 已生成；恢复、隔离回滚、真实开机自启和 watchdog 已通过。
- 真实模型会话、断电恢复和 purge/uninstall 仍未验证。

## 5. P4：手机控制（PJE110 主要链路已实测）

- `am/pm/settings/input` 已验证实际效果；需要独立 root helper 的 package 操作必须明确授权和确认，普通 App 不伪造系统权限。
- Agent 的 loopback socket、token、T0/T1 会话/DSL、中文输入、双指手势、通知测试标签、剪贴板往返、事件/作业和磁盘快照只读已通过。
- Console 必须经 SUList 授权并彻底重启；其 root 状态、Start/Stop 和 WebView 已通过。
- 文件访问仍待用户授权，未内置 OCR，真实验证码和通用厂商 UI 未验证；未实测的危险 root 写操作不写成通过。

## 6. P5：安装、迁移与检测（备用机部分通过，仍有明确边界）

- 备用机正式模块安装、激活、真实开机自启已通过；purge/uninstall 未做。
- ARM64 rootfs 与 128GB 镜像构建、部署和校验已通过。
- 普通无特殊权限 App 的 mountinfo、模块/镜像/token 可读性和全局属性已实测；银行、游戏、Play Integrity 未验证。
- 发布签名、迁移和断电恢复演练未完成；不能把有限探针结果写成“完美隐藏”。

## 7. 门禁

任何阶段进入下一阶段前：

- `build/check.sh` 全绿，交付物重新构建并验证；
- 文档路径/命令与代码一致；
- 未验证项仍标未验证；
- 不在主力机做新的危险测试；
- 验收记录与变更差异一并保留，不自动提交。
