# DSH chroot 控制面与恢复

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
| `DSH_WORKSPACE_DIR` | 见下节 | 配置文件/自动检测；下次启动 | DSH 工作目录的 **guest 路径**。自定义路径必须已存在，不静默回退 `/root`。 |
| `DSH_IMAGE_DIR` | `/data/media/0/ubuntu-rootfs` | 配置文件；镜像操作/启动 | 镜像及快照位置。默认在 guest 遮蔽。 |
| `DSH_IMAGE` | `$DSH_IMAGE_DIR/rootfs.img` | 配置文件；镜像操作/启动 | 活动 ext4 镜像。禁止并发写、删或绕过生命周期操作。 |
| `DSH_MEDIA` | `/data/media/0` | 配置文件；启动 | Android 媒体根目录。 |
| `DSH_IMAGE_SIZE_GB` | `128` | 配置文件；创建镜像时 | 稀疏镜像表观大小，不是立即占用的磁盘空间。 |
| `DSH_PROTECT_CRITICAL` | `1` | 配置文件；启动 | 保护 `/metadata`、persist、qmcs 等递归只读。关闭会扩大误写面。 |
| `DSH_SELINUXFS_MODE` | `mask` | 配置文件；启动 | `mask`=遮蔽；`ro`=private ns 新建只读 selinuxfs；`rw`=沿用可写视图，可能改全局 SELinux，风险最高。 |
| `DSH_SELINUXFS_MASK` | `1` | 配置文件；旧兼容 | 仅在未设置 MODE 时映射 mask/rw；新配置使用 MODE。 |
| `DSH_BLOCK_ACCESS` | `masked` | 配置文件；启动 | 默认遮蔽 `/dev/block`；`full` 开放全部块设备，误写可损坏手机。遮罩不是对恶意 root 的绝对安全边界。 |
| `DSH_SYSTEM_RW` | `0` | 配置文件/控制器 | system 可写不是默认能力；不同 ROM/AVB 兼容性未验证。 |
| `DSH_APPLY` | `0` | 控制器环境 | mount 产生的操作要求每次显式 `--apply`。不要直接裸跑 nsinit。 |
| `DSH_LOOP` | 控制器分配 | 启动 holder 时 | 当前镜像的 loop 设备。不得手工替换记录或 detach 陌生设备。 |
| `DSH_PROC` / `DSH_SYS` / `DSH_MOUNTINFO` | `/proc`、`/sys`、proc mountinfo | 测试/内部 | 生产环境不要随意覆盖，否则身份与清理判断会失真。 |

## 工作区兼容

未显式设置 `DSH_WORKSPACE_DIR` 时：

1. `state/dsh-home/workspace` 是真实目录：使用 `/root/.dsh/workspace`。
2. 它是链接：解析真实目标后绑定到 `/run/dsh/workspace`。目标不存在则启动失败，不重建旧 sdcard 链接。
3. 它不存在：使用 `/sdcard/DSH/workspace`，创建并设为 2777。

自定义内部工作区不在每次启动时执行 `chmod 2777`。正常模块安装只更新模块/control，不替换用户 DSH runtime、插件或 dsh-home；恢复快照可能包含旧 dsh-home，不能用它无声覆盖刚升级的环境。真实目录形式的内部工作区会随 state 归档；链接只保存链接本身，外部目标不自动备份。

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
