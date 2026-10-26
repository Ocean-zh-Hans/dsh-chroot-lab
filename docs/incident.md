# 事故复盘：2026-10-26 SELinux remount 关联的系统崩溃

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
