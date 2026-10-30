# DSH chroot 控制面与恢复

> 2026-10-30：`docs/plan-v2.md` 已把默认读模型改为“读全开、`selinuxfs=ro`、`/dev/block=ro`”；本文件下表仍描述当前运行时代码，目标值在 plan-v2 的 P1 实现后切换。

本目录在 guest 中以只读方式挂载为 `/opt/dsh-control`。模块目录、控制目录和活动镜像仍默认遮蔽；本目录可见不代表宿主模块目录完全开放。

## 自检信息

每次 holder 启动时生成：

- `/run/dsh/module.json`：模块 version/versionCode、启动时间、autostart、SELinuxFS 和块设备模式。
- `/run/dsh/module.sha256`：模块 prop、启动脚本和控制器/ holder / watchdog / 日志限流器 / 锁入口的 SHA256。
- `/run/dsh/hooks.status`：hooks 的 `full`、`audit-only` 或 `disabled` 模式及原因。它只表示本层请求了哪个 patch，不证明上游已经成功加载或产生审计事件。

它们来自宿主未暴露的状态目录，并以单文件只读 bind 发布。这里记录的是**模块版本**，不是用户自行升级的 DSH runtime 版本。SHA256 清单用于与已知良好构建比较，不能在没有源文件的情况下单独证明运行中的模块未被替换。

完整宿主检查仍使用 `dsh-ctl doctor`、`dsh-ctl status --json`。不要把含 URL/token 的原始状态或诊断输出贴到公开日志。

## 配置

`dsh-ctl config` 查看解析后的路径。`/data/adb/chroot/config.conf` 支持表中标记为“配置文件”的键；其它键由控制器或环境提供。修改后需要停止并重新启动 chroot，正在运行的 s6/DSH 不会自动重读。

| 键 | 默认值 | 来源/生效时机 | 含义与风险 |
|---|---|---|---|
| `DSH_ROOT` | `/data/adb/chroot` | 环境；启动控制面时 | 宿主控制/状态根目录。不是 guest 内可自由访问的路径。 |
| `DSH_CONFIG` | `$DSH_ROOT/config.conf` | 环境；读取配置时 | 可选配置文件。未知键警告，不执行配置内容。 |
| `DSH_ROOTFS_DIR` | `/tmp/dsh-rootfs` | 配置文件；启动 | 宿主 private namespace 的镜像挂载点。不是 guest TMPDIR。 |
| `DSH_TMP` | `/tmp` | 配置文件；宿主控制操作 | 宿主恢复脚本等临时文件位置。**不要用它设置 guest TMPDIR。** |
| `DSH_GUEST_TMP` | `/var/tmp` | 配置文件；下次启动 | s6、DSH 和 guest 进程的 TMPDIR，位于 rootfs 磁盘而不是 `/tmp` tmpfs。创建为 1777。不会清理既有 `/tmp` 垃圾或释放其 RAM。 |
| `DSH_WORKSPACE_DIR` | `/root/workspace` | 配置文件；下次启动 | DSH 工作目录的 **guest 路径**。宿主来源固定为 `state/workspace`；不得放在 `/root/.dsh` 内。 |
| `DSH_IMAGE_DIR` | `/data/media/0/ubuntu-rootfs` | 配置文件；镜像操作/启动 | 镜像及快照位置。默认在 guest 遮蔽。 |
| `DSH_IMAGE` | `$DSH_IMAGE_DIR/rootfs.img` | 配置文件；镜像操作/启动 | 活动 ext4 镜像。禁止并发写、删或绕过生命周期操作。 |
| `DSH_MEDIA` | `/data/media/0` | 配置文件；启动 | Android 媒体根目录。 |
| `DSH_IMAGE_SIZE_GB` | `128` | 配置文件；创建镜像时 | 稀疏镜像表观大小，不是立即占用的磁盘空间。 |
| `DSH_PROTECT_CRITICAL` | `1` | 配置文件；启动 | 保护 `/metadata`、persist、qmcs 等递归只读。关闭会扩大误写面。 |
| `DSH_SELINUXFS_MODE` | `ro`（当前默认） | 配置文件；启动 | `mask`=遮蔽；`ro`=private ns 新建只读 selinuxfs；`rw`=沿用可写视图。plan-v2 目标默认 `ro`。 |
| `DSH_SELINUXFS_MASK` | `0` | 配置文件；旧兼容 | 仅在未设置 MODE 时映射 mask/rw；新配置使用 MODE。 |
| `DSH_BLOCK_ACCESS` | `ro`（当前默认，可读不隐藏；ro 不是写保护） | 配置文件；启动 | `ro`=只读视图；`masked`=显式遮蔽；`full`=开放全部块设备，误写可损坏手机；真正写保护靠 R4 人工门禁。 |
| `DSH_SYSTEM_RW` | `0` | 配置文件/控制器 | system 可写不是默认能力；不同 ROM/AVB 兼容性未验证。 |
| `DSH_APPLY` | `0` | 控制器环境 | mount 产生的操作要求每次显式 `--apply`。不要直接裸跑 nsinit。 |
| `DSH_LOOP` | 控制器分配 | 启动 holder 时 | 当前镜像的 loop 设备。不得手工替换记录或 detach 陌生设备。 |
| `DSH_PROC` / `DSH_SYS` / `DSH_MOUNTINFO` | `/proc`、`/sys`、proc mountinfo | 测试/内部 | 生产环境不要随意覆盖，否则身份与清理判断会失真。 |

## 工作区与 DSH home

- 宿主 `state/workspace` 是独立的真实目录，默认绑定到 guest `/root/workspace`，模式 0700。工作文件随 state 快照归档，但不写入 rootfs.img。
- 宿主 `state/dsh-home` 只绑定到 `/root/.dsh`，由 DSH 自己维护；工作区不再放在这个目录，也不创建兼容软链接。
- `DSH_WORKSPACE_DIR` 只改变 guest 挂载点，不能设为整个 `/root`、`/root/.dsh` 或其子目录；宿主工作文件仍统一保存在 `state/workspace`。
- 安装其它步骤全部完成后，才把旧的 `state/dsh-home/workspace` 真实目录原子迁到 `state/workspace`，前期失败不改变旧路径。新旧目录同时存在时拒绝合并；legacy 链接需要操作者显式迁移，不能静默跟随到任意宿主路径。
- 恢复旧快照时同样迁移真实工作区目录。恢复快照会覆盖当前工作文件，必须先确认内容和备份。正常模块安装不替换用户 DSH runtime、插件或其它 dsh-home 数据。

## DSH 不启动时的恢复

**不需要先进入 chroot。** 在已获得 root 的 Console 中可以查看状态、停止或执行“清理残留”。Console 与 holder 生命周期独立：退出/结束 Console 不会停止 chroot，也不是 DSH 的前台看守进程。

经 adb 和已授权的 su：

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl doctor'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl status'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl diag'
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl log 200'
```

有残留或 `recovery-required` 时：

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl recover --apply'
```

需要只读 rescue shell 时，先成功 stop/recover，再：

```sh
su -c '/data/adb/modules/dsh_chroot/bin/dsh-ctl rescue --apply'
```

不要添加直接 `setsid dsh-nsinit` 的 `service.d` 脚本：那会绕过镜像校验、生命周期锁、holder 身份和清理。`ttyd` 默认关闭；未默认提供匿名网络 root shell 或无人值守远程恢复入口。

## 日志与 URL

- `/var/log/dsh/dsh.log` 启动时保留为 `.1` 至 `.5`，不再只有一代 `.prev`。上游重复 model-listing 日志的级别需要上游修正，不在本层静默删除。
- 宿主 `boot.log` 记录每次 boot 尝试及退出码；首次失败后等待 30 秒，最多重试一次。watchdog 在 boot 尝试结束后启动，避免争锁。`boot.log` 约 1 MiB 一代、保留 5 代；`watchdog.log` 独立限流并保留 3 代，重复行最多每 60 次报告一次，watchdog 的 sleep 失败会停止 daemon。当前及轮转日志均要求 0600。
- `PostToolUse` 审计写入 `/var/log/dsh/audit/posttool-YYYY-MM-DD.log`，按命名模式清理 14 天以上的旧文件。旧版 `posttool.log` 不静默删除。只记录时间与工具名，不记录命令正文/token。
- URL 从输出中提取 loopback 候选，不再依赖固定 `dsh web:` 文案；仍经过严格 loopback 校验和真实 HTTP 健康检查。当前没有承诺上游原生 `--url-file`、JSON events 或 `/healthz`。

## 权限边界

chroot 与 Android 共享 PID、网络，并使用完整宿主挂载视图。guest root 能操作 Android 进程、`/data` 和允许的 sysfs；只读 bind、遮罩和审计不是针对恶意 root 的安全沙箱。rescue 不启动 s6，rootfs 只读，`/tmp` 是其独立可写 tmpfs。
