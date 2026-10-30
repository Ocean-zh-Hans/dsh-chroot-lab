# 打包设计（当前）

## 1. 产物

| 产物 | 内容 | 状态 |
|---|---|---|
| `dsh_chroot-<version>.zip` | 模块 + 已校验的 Android 原生挂载 helper；默认不含控制台或 root helper | PC 已构建并验证 |
| `rootfs.tar.gz` | Ubuntu 24.04 + Node 24 + Go + DSH + 工具链 | 已在 ARM64 VM 构建并执行 verify-rootfs；手机端首轮 chroot 已验证 |
| `rootfs.img` | 128GB ext4 稀疏镜像 | 已在备用机生成并校验 SHA256/e2fsck；VM 未构建 img |
| `SHA256SUMS` | rootfs tar/img 校验 | tar 由 VM 生成，img 由备用机生成并校验 |
| `dsh-agent-0.2.0.apk` | 可选宿主 Android 控制面，Compose UI、会话/查询、事件/作业 | debug 构建/签名并安装到备用机；T0/T1 主要链路已实测，文件访问未授权 |
| `dsh-root-helper.jar` | 可选、独立授权的 root UiAutomation/固定系统命令/helper shell | D8 `classes.dex` 构建与备用机 T0 已实测；默认不安装、不启用开机服务 |
| `chroot-console-0.2.0.apk` | 可选原生控制台 + WebView | debug 构建/签名/zipalign 并安装到备用机；SUList 授权后重启、启停和 WebView 已实测 |

## 2. 模块 zip 结构

```
module.prop
customize.sh
service.sh
boot-completed.sh
uninstall.sh
action.sh
console.apk                 # 仅 --with-console；默认不存在；不自动安装
bin/
  dsh-ctl
  dsh-nsinit
  dsh-watchdog
  dsh-recover-mounts
lib/
  common.sh config.sh log.sh proc.sh state.sh lock.sh loop.sh mount.sh unmount.sh
  doctor.sh plan.sh status.sh lifecycle.sh snapshot.sh systemrw.sh
  guard.sh ttyd.sh image.sh
control/
  README.md
  bin/dsh-run
  bin/dsh-android-env
  bin/dsh-healthcheck.py
  dsh/hooks.patch.yml
  s6/dsh/run
  s6/dsh/finish
  s6/ttyd/run
  s6/ttyd/down
  profile.d/dsh-android.sh
  hooks/hooks.json
  hooks/riskcontext.sh
  hooks/posttool.sh
tools/
  README.md
  SHA256SUMS
  TERMUX-SOURCES.json
  bin/dsh-mount-ro-recursive
  bin/cp
  bin/BUILDINFO
  libexec/cp
  lib/...GNU cp 所需的 Android ARM64 运行库...
  licenses/...各工具包的许可证...
```

**禁止**：

- 包根出现 `install.sh`；模块 zip 携带 rootfs payload；未经校验的宿主二进制。
- 默认携带 `system/priv-app` 或其他全局 App overlay。
- 把 `.tar.zst` 当作 KSU busybox 可解压 payload。
- 把工具二进制提交进 git（由 `build/build-tools.sh` 生成并写入校验清单）。

## 3. 安装期

`customize.sh`：

1. 检查 arm64、API ≥ 29；存在 holder 或 loop 记录时中止，先 stop/recover。
2. 创建 `/data/adb/chroot/{control,state,logs,dsh,snapshots,payload,rootfs-staging}`；显式安装/更新时清除 watchdog disable。
3. 复制 `control/`，给控制脚本、模块脚本、helper 和 `tools/libexec/*` 设置执行权限，不依赖安装器保留 ZIP 执行位。
4. 给原生 helper、GNU cp、debugfs 和共享库设置 `u:object_r:shell_data_file:s0`，失败则中止安装。这只解决已观察到的 Magisk 域执行上下文兼容性；不关闭 SELinux、不修改全局策略，且仍须在激活模块后实测。包装脚本不改此上下文。
5. 不复制/解压 payload，不启动 chroot，不安装 APK。
6. 写入 `state/ctl/install-version`。

安装器可能在安装完成后移除 `customize.sh`。它是安装期脚本，不属于运行时依赖；`module.sha256` 只覆盖运行时仍需要的模块文件，不得因它缺失而拒绝启动。

## 4. 开机

- `service.sh` 后台执行 `dsh-ctl boot`，不阻塞系统启动；`boot-completed.sh` 只记录。
- 等 `sys.boot_completed=1` 和 `sys.user.0.ce_available` 为真（本机可返回 `1` 或 `true`）；`recovery-required`/`rescue-needed` 抑制自动启动。
- watchdog 自己持有 PID/starttime 和 owner 锁，检查 holder、loop backing、真实 DSH HTTP 健康、SELinux，以及 inactive 时的 loop/rootfs namespace 残留；不由 `setsid $!` 猜测身份。`--stop --disable` 可阻止新 claim，并有界等待旧 daemon 退出；失败 purge 恢复或显式维护可用 `--enable`，status/doctor 显示 disabled。
- 只有明确的镜像 e2fsck 失败才尝试一次 last-good；其他启动失败不抹掉当前系统。
- `uninstall.sh` 保留用户数据，但先 stop/disable watchdog，再 stop holder；失败必须报错，不能带着活动挂载或 daemon 卸载。

`DSH_SELINUXFS_MODE` 当前默认 `ro`，只在 private namespace 新建只读 selinuxfs，禁止宿主 bind/remount。`DSH_BLOCK_ACCESS` 当前默认 `ro`，但 `ro` 只是只读视图，不能阻止对设备节点的写入；默认仍保持 `ro` 以维持“全手机可读”，`masked` 仅显式可选，真正写保护靠 R4 人工门禁，`full` 仅显式 R4。

## 5. 工具链

必须自带或校验：

| 工具 | 原因 |
|---|---|
| Android NDK clang | 生成 ARM64 Android helper；不再默认使用宿主 cc |
| `losetup` | 优先 util-linux；toybox 回退会明确警告，不把回退当完整验证 |
| `mke2fs`/`e2fsck` | 镜像创建和校验 |
| `debugfs`/`libblkid`/`libuuid`/`libandroid-posix-semaphore` | 对未挂载镜像只读检查 guest Python 和 `/tmp`，随包携带依赖和许可证 |
| GNU `cp --sparse=always` 及运行库 | 快照不静默变成完整 128GB 普通复制；复制后核对尺寸和内容哈希 |
| `dsh-mount-ro-recursive` | `mount_setattr(AT_RECURSIVE)`、bind/rbind、private propagation、DSH 内部镜像协作锁 |
| `timeout -k` | stop/recover/health 的 guest 命令必须有界，缺失时拒绝启动 |
| `unshare`/`nsenter` | toybox 回退与 util-linux 均可，实际能力在备用机验证 |
| `sha256sum`/`sync` | 快照/一致性 |

helper 默认使用 `DSH_NDK_VERSION=28.2.13676358`；可用 `ANDROID_NDK_HOME` 指定其他 NDK。`file` 必须确认 ARM64，且为 `/system/bin/linker64` 动态链接或静态链接。`tools/SHA256SUMS` 与 zip 内二进制必须一致。

`control/bin/dsh-android-env` 在缺少 Java classpath 时，从运行中的 `system_server` 仅提取 `BOOTCLASSPATH` 和 `DEX2OATBOOTCLASSPATH`，逐项校验允许的 Android jar 路径及文件存在性；不导入其他进程环境，不依赖特定 ROM 的硬编码列表。备用机已用真实 `am instrument` 验证。

**运行时不依赖 Termux App、Termux 路径或其包管理器。** GNU cp/debugfs 及运行库在构建期从锁定的上游包取得、校验并随模块携带，Android mount helper 由 NDK 构建；生产 root 来自 KernelSU，guest 是 Ubuntu chroot。构建期包来源不等于要求用户安装 Termux。

`agent-root-helper` 是独立 Java/D8 构建，不进入 Magisk 模块 ZIP，也不随 Agent APK 自动安装。产物为 `agent-root-helper/build/dsh-root-helper.jar`，必须含非空 `classes.dex`；安装/启动由 `agent-app/root-helper.sh` 显式执行，默认没有开机服务。App 与 helper 的源码和锁文件进入源码包，生成 APK/JAR 不进入 Git。

## 6. 构建门禁

1. `sh -n`、mksh 解析、ShellCheck、shfmt；构建脚本另按 Bash 检查。
2. helper 重建、ABI/链接器、SHA256；不能只写 C 语法检查。
3. zip 无 `install.sh`、无 `system/` overlay；必需文件和 `0755` 权限齐全。
4. `build/verify-module.py` 规范化 zip Unix 权限，再读回验证，兼容 Windows/MSYS。
5. `versionCode` 单调增（发布时）；payload 校验记录；不含用户密钥/凭据。
6. Java URI/退出码/超时测试；用 `android.jar` 编译 Console/Agent/helper 全部 Java 源码，并执行 Agent selector/cron/path 和 Python 客户端测试；APK 编译、签名、zipalign 验证，并强制检查根目录 `classes.dex`。签名和对齐不能代替 APK 结构或真机安装验证。
7. arm64 rootfs 内验证 Node/node-pty/DSH；KSU busybox 解压测试留到备用机。

## 7. 烘焙/构建

- `bake/build-rootfs.sh`：在 arm64 Ubuntu VM 上生成 rootfs；Node tar 包对照官方 SHA256，安装 `golang-go`，使用锁定依赖的 `npm ci` 和有限 `allowScripts`，不使用 `unsafe-perm` 或放开全部安装脚本。
- `bake/dsh-runtime.package.json` 与 `bake/dsh-runtime.package-lock.json` 固定 DSH 及传递依赖，使用 `npm ci`，不使用浮动的 `npm install` 作为交付输入。
- `bake/make-image.sh`：用 `mke2fs -d` 创建稀疏 ext4；默认拒绝覆盖现有镜像，临时文件校验后原子发布，并生成汇总 `SHA256SUMS`。
- `bake/verify-rootfs.sh`：以隔离的 guest 环境检查指定 Node/DSH 版本、node-pty，离线编译并运行 Go 小程序，并实际启停一个空 s6 监督树。裸 chroot 必须有可写的 `/dev/null`；Android 上不能用带错误 SELinux 上下文的解包 `/dev` 代替正式 private namespace 绑定 `/dev` 的运行时验收。
- `build/build-tools.sh`：构建并验证 Android helper；按 `build/android-tools.json` 获取并校验 GNU cp、运行库和许可证。`build/fetch-android-tools.py` 检查包 SHA256、ARM64、动态库依赖与许可证。
- `build/build-android-app.sh` 保留给旧的直接 Java 构建调用；Compose Agent 和 Console 使用根 Gradle wrapper，`agent-app/build.sh` 同时构建 App 与 root helper，`console-app/build.sh` 构建 Compose Console。两个 App 都使用各自依赖锁与统一依赖 SHA256 校验，入口处理 Windows/MSYS 路径；运行时不依赖 Termux。
- `build/build-module.sh`：打包模块；`--with-console` 只在根目录附带可选 APK，不做系统 overlay。

## 8. 当前构建

```sh
./build/build-tools.sh
./build/check.sh
./build/build-module.sh
./agent-app/build.sh
./console-app/build.sh
# 模块：dist/dsh_chroot-0.2.2.zip
# Agent：agent-app/dist/dsh-agent-0.2.0.apk
# helper：agent-root-helper/build/dsh-root-helper.jar
# Console：console-app/dist/chroot-console-0.2.0.apk
```

ZIP 的结构、权限和构建门禁已验证，备用机也已完成正式安装、真实开机、chroot/DSH 和 App 核心流程验收。**仅凭 ZIP 校验不能证明其他设备可用**，不得据此直接安装到未实测的主力机。
