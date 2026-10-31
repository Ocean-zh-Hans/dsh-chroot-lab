# 事故复盘：2026-10-26 SELinux remount 关联的系统崩溃

> 2026-10-30 注：本文是历史事故记录；其中 `--permissive` break-glass 已废弃。当前规则是 DSH 不读取、不修改、不判断全局 SELinux，也不运行 `getenforce`/`setenforce`。

- 设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 时间：2026-10-26 12:07 左右测试；12:18 大面积 native crash / system_server 崩溃；12:24 重启恢复。
- 结论先行：**在主机上做了多项宿主级危险测试，随后系统崩溃；责任在操作者。**
- 最强关联操作：`mount -o remount,ro,bind /sys/fs/selinux`；但**单点机制未证实**，不能写成“已证明根因”。

## 1. 做过的测试

```sh
mkdir -p /data/local/tmp/selro
mount --bind /sys/fs/selinux /data/local/tmp/selro
mount -o remount,ro,bind /data/local/tmp/selro
cat /data/local/tmp/selro/enforce
umount /data/local/tmp/selro
rmdir /data/local/tmp/selro
```

返回 `bind_rc=0`、`ro_rc=0`、`enforce=1`，但这是不该在主力机上做的测试。

同期还在主机 namespace 做了：

- overlay（lowerdir `/system`，target 临时目录）；
- debugfs/tracefs 挂到 `/data/local/tmp`；
- `uiautomator dump`（rc=137）；
- screencap/screenrecord；
- 其他 mount/unshare 试验。

## 2. 为什么不是“已证实根因”

Linux `mount(2)` 对 `MS_REMOUNT|MS_BIND|MS_RDONLY` 的语义是：只让通过该挂载点的访问变只读，不影响该文件系统的其他挂载。zygote 写的是宿主原来的 `/sys/fs/selinux/context`，不是临时 bind 点。因此：

- 有时间关联；
- 但不能证明它把底层 selinuxfs 全局置只读；
- 也不能排除其他测试是参与者。

## 3. 崩溃证据

- `data_app_native_crash@1792988289380.txt`：`com.tencent.mm` / zygote64，`selinux_android_setcontext(...) failed`，SIGABRT。
- `com.google.android.gms`、`com.tencent.mm`、`com.heytap.speechassist` native crash；`com.android.systemui` NPE；InputManager watchdog 15s。
- `system_server_crash@1792988337185.txt`：`failed to set system property "ctl.start" to "rbootanim"`，是系统不稳定后的重启失败，不是最初根因。
- 重启后 launcher 在 LuckyTool `libdexkit.so` abort，是独立问题，另查。

## 4. 恢复观察

```
sys.boot_completed=1
/sys/fs/selinux: rw
cat /sys/fs/selinux/enforce: 1
getenforce: 不稳定（一次 Disabled，一次 Enforcing）
```

- 无 `selro`/`ovl` 测试挂载残留；
- `losetup -a` 只有系统 APEX 正常 loop；
- 测试空目录已清理。

## 5. 永久禁止

1. `mount -o remount,ro,bind /sys/fs/selinux`，以及任何对 selinuxfs 的 bind/remount。
2. 在主机 namespace 做 overlay/debugfs/tracefs/loop 试验。
3. 默认 `setenforce 0`；`--permissive` 仅显式 break-glass。
4. 把“命令返回 0”当安全证明。

## 6. 允许的替代

- 需要限制 chroot 改 SELinux：**private ns 内 tmpfs 遮罩 `/sys/fs/selinux`**，或 hardguard；不动宿主 selinuxfs。
- 所有 mount/loop 验证：备用机/VM，或显式 private namespace + trap 清理，并先说明风险。
- 以后先只读，再破坏性测试；高风险操作先得到同意。

## 7. 责任

这是操作者造成的系统级故障，不是用户配置问题。后续不再在主力机上执行任何会改宿主挂载/SELinux/loop/overlay 的测试；只做只读排查。

## 8. TelephonyProvider 读取异常与恢复（2026-10-28）

- 用户在主力机看到反复“程序停止运行”，随后 SIM 无法识别。约 00:06 的 crash/events/system 日志显示 `com.android.phone` 持续崩溃并被系统重启，`am_process_crashed_too_much` 反复出现。异常是 `SubscriptionDatabaseManager.loadDatabaseInternal` 查询时，`ActivityThread.acquireProvider` 的 provider 引用为空并触发 `NullPointerException`。
- 更直接的读取证据是：`Zip: failed to read at offset 1808775`、`Failed to open APK '/system_ext/priv-app/TelephonyProvider/TelephonyProvider.apk': I/O error`，provider Context 创建失败。`adb pull` 同样返回 `remote read failed: I/O error`，不是单纯的 ZIP 格式不兼容。
- `/system_ext` 为 EROFS，但该 TelephonyProvider 目录另有 Oplus `opex` overlay，首选 lower 位于 `/mnt/opex/ovl_update_3456@3/...` 的 tmpfs。直接拉取该源文件也返回 I/O error。包管理仍显示已安装、未隐藏、未停用，`enabled=0` 是默认状态，不是禁用。
- 这些事实**不能单独证明**是 chroot、LSPosed、SIM 硬件、系统分区永久损坏或某个 root 模块导致。重启前没有取得内核 `dmesg`，因此不把存储硬件或 tmpfs 损坏写成已证实根因。没有清数据、重刷分区、禁用系统组件或修改其他 root 授权。
- 用户重启并报告恢复后，约 00:18 复核：`system_server` 和 `com.android.phone` 均存活，crash 缓冲区没有新的同类 FATAL/provider Context 错误；APK 可完整拉取，`unzip -t` 全部通过。恢复后的 APK SHA256 为 `a09da737e28851f6ff405a3f54b7671bad55e03db670a67346dfb1777c387760`。这证明当时读取已恢复，但不证明故障永久消失或根因已确定。
- 若复发，先保留新 crash/system 日志并尽早读取内核 I/O、tmpfs/swap 和设备映射错误，再决定恢复动作。不要先清电话数据、刷机或把 SIM 硬件判死。新的 chroot 验证曾在排查期间暂停，用户确认恢复后才继续。

## 9. 测试机 init SIGABRT 的未决样本（2026-10-28）

- 当前识别设备为 PJE110 测试机。只读 crash 缓冲区仍有一条 2026-10-27 17:08:36 的记录：PID 7319（init）收到 SIGABRT，随后 `crash_dump helper failed to exec, or was killed`。
- 当前缓冲区没有对应的 Abort message，未取得当时的 dmesg，不能用这两条记录确定触发方，也不能归因于 DSH 模块。此前其他日志中的 init/permissioncontroller 记录没有被本次样本完整复核。
- 当前 `system_server` 与 `com.android.phone` 存活，SELinux=1；模块旧验收通过不等于设备完全健康。需要设备负责人核对当时重启/安装时间线并保留新的内核日志，不能把该样本写成已解决。

## 10. 主力机 PJZ110 安装前后 crash 样本（2026-10-28）

- `main-final-crash.txt` 的 fingerprint 为 OnePlus/PJZ110，包含 00:16:33 的 init SIGABRT，以及 00:23:40 的 permissioncontroller SIGABRT。`main-install-crash.txt` 另有 00:45:53 的 init SIGABRT。它们与第 9 节的 PJE110 / 2026-10-27 17:08 样本不是同一设备或同一时间点，不能合并为一条已解释的 PJE110 事件。
- 这些记录只证明相应进程发生过崩溃，不能单独证明由模块、安装或重启引起，也不能证明设备完全健康。需要设备负责人核对安装/重启时间线，并在复发时保留 dmesg、crash 和恢复记录；原始诊断文件继续保留在被 `.gitignore` 忽略的 `build/.tools`，不进入源码交付。
