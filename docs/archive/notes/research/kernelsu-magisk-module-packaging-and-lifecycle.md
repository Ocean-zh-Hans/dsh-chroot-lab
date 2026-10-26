> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# KernelSU / Magisk 模块打包与生命周期（权威做法研究）

> 适用对象：OnePlus / Android 15 / kernel 6.6 / SukiSU（KernelSU LKM 系）。SukiSU 沿用 KernelSU 的模块体系与 ksud；具体发行版差异**未验证**。
> 方法：只读联网研究，未安装任何东西、未改系统、未执行安装脚本或 root 模块。结论以官方文档 + 上游源码为准，文档与源码冲突处已标注。

## 结论先行（TL;DR）

1. **module.prop 是模块身份**：`id`（`^[a-zA-Z][a-zA-Z0-9._-]+$`，发布后不可改）、`name`/`version`/`author`/`description`（单行）、`versionCode`（整数，唯一版本比较依据）、可选 `updateJson`；KernelSU 另有 `actionIcon`/`webuiIcon`/`metamodule`/`oneshot`。必须 LF 换行。
2. **打包钩子用 `customize.sh`，绝不要在包根放 `install.sh`**：命中 legacy 检测后安装器只解压 `module.prop install.sh uninstall.sh common/*`，`system/` 等其余文件全部丢失。
3. **生命周期首选 `service.sh`（非阻塞）**；`post-fs-data.sh` 阻塞开机（KernelSU 文档称 10s、源码给脚本共享预算 35s，口径不一致，**未验证**哪个实际生效）；`boot-completed.sh`/`post-mount.sh` 是 KernelSU 独有；`action.sh` 由用户点按、不参与开机。
4. **模块脚本由 ksud 以 `u:r:ksu:s0` 域 root 执行**（init 用 `exec u:r:ksu:s0 root -- /data/adb/ksud post-fs-data` 拉起 ksud），基本不受限；Magisk 对应域是 `u:r:magisk:s0`。
5. **卸载在下次开机的 post-fs-data 早期处理**：`remove` 标记 → metamodule 的 `metauninstall.sh` → 模块 `uninstall.sh` → 清 module_configs → `remove_dir_all`。**必须在 uninstall.sh 里先 umount** 自己创建的挂载/loop，否则 `rm -rf` 撞 busy 挂载点（EBUSY）或留下悬空挂载。
6. **在线更新走 module.prop 的 `updateJson`**，JSON 固定为 `{version, versionCode, zipUrl, changelog}`；只有 `versionCode > 当前` 且 `zipUrl` 非空才提示。更新**不会**执行 `uninstall.sh`。
7. **模块根 `/data/adb/modules/<id>` 会被整体删除**：要长期保留的 chroot rootfs / 用户数据请放模块目录**之外**（如 `/data/adb/chroot`、`/data/chroot`）。

## 1. module.prop 字段语义；KernelSU 与 Magisk 差异；managedFeatures

| 字段 | 语义 | 依据 |
|---|---|---|
| `id` | 唯一标识，`^[a-zA-Z][a-zA-Z0-9._-]+$`，发布后不可改 | KSU module.prop / Magisk module.prop |
| `name` `version` `author` `description` | 单行字符串，展示用 | 同上 |
| `versionCode` | 整数，唯一版本比较字段 | 同上 |
| `updateJson` | 可选，在线更新 JSON 的 URL | 同上 |
| `actionIcon` / `webuiIcon` | KernelSU 独有，相对模块根的图标路径 | KSU module.prop |
| `metamodule=1/true` | 标记元模块（提供挂载 hook） | KSU metamodule |
| `oneshot=true/1` | 一次性脚本 ZIP，不进 /data/adb/modules | KSU one-shot scripts |

- 官方字段表：KernelSU https://kernelsu.org/guide/module.html#moduleprop ；Magisk https://topjohnwu.github.io/Magisk/guides.html#moduleprop 。
- 两者模块格式/目录（`/data/adb/modules`）几乎相同；差异：KernelSU 无内置 Zygisk（用 ZygiskNext）、挂载下沉到 metamodule（官方 `meta-overlayfs`）、`system/` 必须装 metamodule 才挂载、不支持 `.replace`（用 `mknod file c 0 0` 白障或 `REMOVE`/`REPLACE`）、busybox 路径不同、多 `boot-completed`/`post-mount` 阶段、不可在 recovery 刷入。依据：https://kernelsu.org/guide/difference-with-magisk.html 。
- 判断环境：用 `KSU=true`；KernelSU 里 `MAGISK_VER_CODE` 恒为 `25200`、`MAGISK_VER` 恒为 `v25.2`，**不能**用它判断。依据：https://kernelsu.org/guide/module.html#variables 。
- **managedFeatures 是什么**：模块声明"我接管 KernelSU 的某个内建 feature"，避免多模块抢同一 feature。声明方式是 module config `manage.<feature>=true|false`，`ksud module list` 把它合成为逗号分隔的 `managedFeatures` 暴露给管理器。依据：https://kernelsu.org/guide/module-config.html#declaring-managed-features ，源码 `module.rs` 的 `list_module`/`get_managed_features`（https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/module.rs ）。
- feature 全集以源码枚举为准：`su_compat, kernel_umount, sulog, adb_root, selinux_hide, shell_su_adb_only`（https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/feature_id.rs ）；**文档只列 `su_compat`/`kernel_umount`，已落后源码**（差异已核实，非未验证）。
- 另有安装期预警路径：`installer.sh` 的 `check_managed_features()` 直接读 `module.prop` 的 `managedFeatures=` 行，用 `ksud feature check <name>` 判断，`unsupported`/`managed` 时打印警告。该字段**未列在文档 module.prop 表**，属源码行为，标**未验证**（长期稳定性）。源码：https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/installer.sh 约 258–296 行。

## 2. 安装钩子：customize.sh vs install.sh；legacy；update-binary/updater-script

- **customize.sh**：在所有文件解压、默认权限/secontext 设置完成后被安装器 **source**（不是执行），用于按 ABI/机型和文件设置权限；声明 `SKIPUNZIP=1` 可跳过默认解压，完全自己装。运行在 busybox `ash` + Standalone。依据：https://kernelsu.org/guide/module.html#module-installer 。
- **install.sh = legacy 开关**：`is_legacy_script()` 仅检查 ZIP 列表里有没有 `install.sh`；命中则走旧 Magisk 模板分支，**只** `unzip -oj module.prop install.sh uninstall.sh 'common/*'`，然后 `source install.sh`、调 `on_install()`，再按旧变量 `SKIPMOUNT/PROPFILE/POSTFSDATA/LATESTARTSERVICE` 拷贝少量文件——**现代 `system/` 布局与 customize.sh 会被跳过**。KernelSU 的 installer.sh 是 Magisk 的同源 fork，行为一致。源码：KernelSU https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/installer.sh （`is_legacy_script` 约 334 行、legacy 分支约 392–410 行）；Magisk https://github.com/topjohnwu/Magisk/blob/master/scripts/util_functions.sh 。
- Magisk 文档明确警告："由于历史原因，**不要**在安装 ZIP 里放名为 `install.sh` 的文件"；https://topjohnwu.github.io/Magisk/guides.html#notes 。
- **update-binary / updater-script** 只服务"自定义 recovery 刷入"：`META-INF/com/google/android/update-binary` 是 `module_installer.sh` 的副本，`updater-script` 只须含字符串 `#MAGISK`。Magisk App 下载模块时会**强制替换** update-binary，故不要在其里写逻辑。依据：https://topjohnwu.github.io/Magisk/guides.html#magisk-module-installer 。
- **KernelSU 不通过 recovery 安装**（文档明确"NOT compatible for installation in a custom Recovery"），它由 ksud 自己解包并 `unzip -x 'META-INF/*'`，所以 update-binary/updater-script 对 KernelSU 无意义（保留无害）。依据同 installer.sh 解包行 + https://kernelsu.org/guide/module.html#module-installer 。
- 热安装：`customize.sh` 里 `export MODULE_HOT_INSTALL_REQUEST=true`（可配 `MODULE_HOT_RUN_SCRIPT`）可免重启生效；默认新装/更新仍先落 `/data/adb/modules_update`，下次开机合并。依据：https://kernelsu.org/guide/module.html#hot-install 。

## 3. 生命周期脚本

| 脚本 | 触发时机 | 是否阻塞 | 备注 |
|---|---|---|---|
| `post-fs-data.sh` | init post-fs-data，早于任何模块挂载、早于 Zygote | **阻塞**（文档 10s / 源码脚本预算 35s，**未验证**） | 用 `resetprop -n`，**禁止 setprop**（死锁） |
| `service.sh` | late_start service（非加密/框架重启触发器） | 非阻塞 | **推荐默认阶段** |
| `boot-completed.sh` | `sys.boot_completed=1` | 非阻塞 | **KernelSU 独有** |
| `post-mount.sh` | metamodule 挂载完成之后 | 跟随 post-fs-data 预算 | **KernelSU 独有** |
| `action.sh` | 用户在管理器点 Action | 不影响开机 | 在全局 mount namespace 的 root shell 跑 |
| `uninstall.sh` | 见第 4 节 | 阻塞（Forever） | 仅卸载时 |

- 依据：https://kernelsu.org/guide/module.html#boot-scripts 、执行顺序图同页；阶段顺序源码 https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/init_event.rs 。
- 通用脚本目录 `/data/adb/post-fs-data.d`、`service.d`、`post-mount.d`、`boot-completed.d`，**必须 chmod +x**，模块不应在安装时写这些目录。依据同上。
- **SELinux 域**：KernelSU 内核注入的 RC 用 `exec u:r:ksu:s0 root -- /data/adb/ksud post-fs-data`（以及 services / boot-completed），`KERNEL_SU_DOMAIN` 定义为 `"ksu"`，模块脚本由 ksud 派生子进程，因此**继承 `u:r:ksu:s0`**；该域被内核规则放开为近似无约束（permissive + allow all）。源码：https://github.com/tiann/KernelSU/blob/main/kernel/runtime/ksud_integration.c 第 35–51 行、https://github.com/tiann/KernelSU/blob/main/kernel/selinux/selinux.h 第 8–12 行、https://github.com/tiann/KernelSU/blob/main/kernel/selinux/rules.c 。Magisk 对应域 `u:r:magisk:s0`：https://github.com/topjohnwu/Magisk/blob/master/native/src/include/consts.rs 。
- **可用工具 / busybox**：KernelSU busybox 在 `/data/adb/ksu/bin/busybox`，脚本 PATH 会追加 `/data/adb/ksu/bin`，并设 `ASH_STANDALONE=1`——脚本里的 `mount`/`umount`/`losetup` 等直接走 busybox，不受 Android `PATH` 影响；`ksud`（含内建 `resetprop` 软链）可用。依据：https://kernelsu.org/guide/module.html#busybox 、https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/assets.rs 、`module.rs` 的 `get_common_script_envs`。Magisk busybox 在 `/data/adb/magisk/busybox`。
- 常用环境变量：`KSU=true`、`KSU_VER`、`KSU_VER_CODE`、`KSU_KERNEL_VER_CODE`、`KSU_UAPI_VER`、`KSU_RUNTIME_MODE`（本项目为 `lkm`）、`KSU_MODULE`、`BOOTMODE=true`、`MODPATH`、`TMPDIR`、`ZIPFILE`、`ARCH`、`API`、`IS64BIT`。依据：https://kernelsu.org/guide/module.html#variables 。

## 4. 卸载语义

- 触发：管理器的"卸载"只写 `/data/adb/modules/<id>/remove` 标记，真正删除在**下次开机 post-fs-data**的 `prune_modules()`（在 post-fs-data 脚本与 metamount 之前）。源码：https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/module.rs 第 423–465 行、调用点 https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/init_event.rs （`on_post_fs_data`）。Magisk 同理：`collect_modules` 见到 `remove` 先跑 `uninstall.sh` 再 `remove_all`。源码：https://github.com/topjohnwu/Magisk/blob/master/native/src/core/module.rs 。
- **严格顺序（源码保证）**：metamodule `metauninstall.sh` → 模块 `uninstall.sh`（`ScriptWait::Forever`）→ 清空该模块全部 module configs → `remove_dir_all`。
- **为什么必须先 umount 再删目录**：`remove_dir_all`/`rm -rf` 无法删除仍是挂载点的目录（EBUSY）；即使删除成功，仍被 bind/loop 引用的 inode 会滞留成悬空挂载、泄漏空间并让后续挂载状态错乱。KernelSU 的 metamodule 架构把"先解挂"固化进 `metauninstall.sh`（在删目录前清理）；`meta-overlayfs` 的 `modules.img` loop 挂载就是典型必须 umount 的例子。依据：https://kernelsu.org/guide/metamodule.html （metauninstall.sh / Execution Order），源码 `metamodule.rs` 的 `exec_metauninstall_script`。
- 数据保留策略：模块目录与 module configs 会一起删（源码 `clear_module_configs` + `remove_dir_all`）；**模块目录之外的路径不会被删**。因此 chroot rootfs、用户数据、日志请放 `/data/adb/chroot` 之类的外部目录，并在 `uninstall.sh` 里按需自行清理。
- 更新不触发卸载：`uninstall.sh` 只在删除时跑，**更新不会跑**；热安装会把旧目录挪到 `/data/adb/ksu/hot_install_trash/`，下次开机才删。依据：https://kernelsu.org/guide/module.html#hot-install 、https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/defs.rs 。

## 5. update.json 格式与在线更新行为

- `module.prop` 里 `updateJson=<url>`；该 URL 返回 JSON：`{ "version": string, "versionCode": int, "zipUrl": url, "changelog": url }`。依据：https://topjohnwu.github.io/Magisk/guides.html#moduleprop 。
- KernelSU 行为：管理器在"网络可用、`updateJson` 非空、模块 enabled 且未处 remove/update（待重启更新）"时才请求；解析后仅在 `versionCode > 模块当前 versionCode` 且 `zipUrl` 非空时提示更新。源码：https://github.com/tiann/KernelSU/blob/main/manager/app/src/main/java/me/weishu/kernelsu/data/repository/ModuleRepositoryImpl.kt 的 `checkUpdate`。
- KernelSU 新增官方 registry（`modules.kernelsu.org`）：来自 registry 的更新会按登记的 size + SHA-256 校验、限制下载 URL；不匹配则 Rejected，其余作者自建 `updateJson` 仍按 Legacy 旧逻辑。源码：https://github.com/tiann/KernelSU/blob/main/manager/app/src/main/java/me/weishu/kernelsu/data/registry/RegistryUpdatePolicy.kt 。
- 提醒：`updateJson` 指向你自己的 HTTPS（GitHub raw/release 均可），但发布后不要改动 `id`，否则更新与配置全断。

## 6. 对本项目（真 chroot 模块）的落地建议

- 包结构：`module.prop` + `customize.sh` + `service.sh`（+ 必要时 `post-fs-data.sh`）+ `uninstall.sh` + 你的 rootfs/二进制；**不要 `install.sh`**。
- 脚本均以 busybox ash Standalone 运行且 `mount`/`umount` 走 busybox；在 `customize.sh` 用 `set_perm` 给可执行文件 `0755`（默认 0644），否则 chroot 入口点无法执行。
- chroot 的挂载用 `service.sh`（非阻塞）建立，避免拖延开机；若必须在挂载前准备，再上 `post-fs-data.sh`。
- 建议每个 chroot 会话用私有 mount namespace（`unshare -m`）承载，退出时显式 `umount -R`，避免污染全局命名空间；若希望 kernel_umount 能按 App 解挂，KSU 管理的挂载 source 用 `"KSU"`。此为工程建议，非官方 chroot 指南（**未验证**）。
- `uninstall.sh` 必须先 `umount -R` 所有 chroot 挂载、解除 loop、结束占用进程，再让管理器删目录；rootfs 与用户数据放模块目录外，并在 `uninstall.sh` 中按策略决定保留/删除。
- 加固：两阶段提交你的挂载，`post-fs-data` 里加自检；注意 KernelSU safe mode / bootloop 计数会禁用所有模块。

## 7. 常见坑清单（每条一句 + 依据）

1. 包根放 `install.sh` → 被判 legacy，只解压 3 个文件 + common，`system/` 丢失（https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/installer.sh ）。
2. 想靠 `update-binary` 写安装逻辑 → Magisk App 强制替换它、KernelSU 又排除 META-INF（https://topjohnwu.github.io/Magisk/guides.html#notes ）。
3. 硬编码模块路径 / 用 `exit` 结束 customize.sh → 用 `MODDIR=${0%/*}`、`ui_print`/`abort`，别 `exit`（https://kernelsu.org/guide/module.html#shell-scripts ）。
4. `post-fs-data.sh` 里 `setprop` → 开机死锁，改 `resetprop -n`（https://kernelsu.org/guide/module.html#boot-scripts ）。
5. 脚本用了 CRLF 或 `versionCode` 不是整数 → 模块解析失败/版本比较错误（https://topjohnwu.github.io/Magisk/guides.html#moduleprop ）。
6. 通用 `.d` 脚本忘记 `chmod +x` → 静默不执行（https://kernelsu.org/guide/module.html#boot-scripts ）。
7. KernelSU 里用 `.replace` 删/换文件 → 不支持，改用 `mknod`/`REMOVE`/`REPLACE`（https://kernelsu.org/guide/difference-with-magisk.html ）。
8. 只放 `system/` 却期待生效 → 需要装 metamodule（如 meta-overlayfs）（https://kernelsu.org/guide/module.html ）。
9. 卸载脚本没 umount → `remove_dir_all` 撞 EBUSY / 留下悬空挂载（https://kernelsu.org/guide/metamodule.html ）。
10. 把要保留的数据放模块目录内 → 卸载/更新时被整体删除；放外部目录（https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/module.rs ）。
11. 以为更新会跑 `uninstall.sh` → 不会，只在删除时跑（https://kernelsu.org/guide/module.html#hot-install ）。
12. 同时启用 Magisk 与 KernelSU 模块 → 任何 KernelSU 模块启用会使 Magisk magic mount 失效（https://kernelsu.org/guide/faq.html ）。
13. 多个模块声明同一 `manage.<feature>` → 冲突；用 `ksud feature check` 先查（https://kernelsu.org/guide/module-config.html ）。
14. 改了已发布模块的 `id` → 旧模块不被覆盖、更新链路断裂（https://topjohnwu.github.io/Magisk/guides.html#moduleprop ）。
15. 未验证项：KernelSU post-fs-data 的真实超时（文档 10s vs 源码 35s）、`module.prop managedFeatures=` 行的长期语义、SukiSU 与本笔记所用 KernelSU main 的行为差异。
