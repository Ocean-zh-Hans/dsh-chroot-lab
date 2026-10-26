# 事故复盘：2026-10-26 SELinux 只读 remount 导致系统崩溃

- 设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 时间：2026-10-26 12:07 左右执行测试；12:18 出现大面积 native crash / system_server 崩溃；12:24 重启后恢复。
- 结论先行：**高度怀疑是我执行的 `mount -o remount,ro,bind /sys/fs/selinux` 导致 selinuxfs 被置为只读，zygote 无法写 `/sys/fs/selinux/context` 设置 app 安全上下文，进而大面积崩溃。这是我的责任。**

---

## 1. 我做了什么

在“验证 `/sys/fs/selinux` 能否只读 bind”时执行了：

```sh
mkdir -p /data/local/tmp/selro
mount --bind /sys/fs/selinux /data/local/tmp/selro
mount -o remount,ro,bind /data/local/tmp/selro
cat /data/local/tmp/selro/enforce
umount /data/local/tmp/selro
rmdir /data/local/tmp/selro
```

命令返回：

```
bind_rc=0
ro_rc=0
1
```

看起来“成功”，但这是**最危险的一步**：selinuxfs 是单一 superblock；对 bind 做 `remount,ro` 可能把底层 selinuxfs 的只读标志带上，而 zygote 的 `selinux_android_setcontext()` 需要写 `/sys/fs/selinux/context` 来设置 app 的 SELinux 上下文。只读之后，写 context 失败，app/zygote 就会 abort。

---

## 2. 崩溃证据

### 2.1 zygote 的 SELinux 上下文失败

`/data/system/dropbox/data_app_native_crash@1792988289380.txt`（2026-10-26 12:18:09）：

```
Process: com.tencent.mm
Cmdline: zygote64
signal 6 (SIGABRT)
Abort message: 'JNI FatalError called: (com.tencent.mm)
frameworks/base/core/jni/com_android_internal_os_Zygote.cpp:2196:
selinux_android_setcontext(99910305, 0,
"default:targetSdkVersion=35:complete", "com.tencent.mm") failed'
```

这正好是“写 `/sys/fs/selinux/context` 失败”的典型症状。

### 2.2 其他 app 的 native crash

同一时间段：

- `com.google.android.gms` native crash
- `com.tencent.mm` 多次 native crash
- `com.heytap.speechassist` native crash
- `com.android.systemui` java NullPointerException
- `/data/anr/anr_3772_...`：`InputManagerService` watchdog blocked 15s

这些是系统级连锁反应，不是单个 app 的 bug。

### 2.3 system_server 崩溃

`/data/system/dropbox/system_server_crash@1792988337185.txt`（2026-10-26 12:18:57）：

```
java.lang.RuntimeException: failed to set system property "ctl.start" to "rbootanim"
 at com.android.server.power.OplusShutdownFeature.playShutdownAnimation(...)
 ...
 at com.android.server.wm.WindowManagerService.reboot(...)
 at com.android.server.policy.RestartAction.onPress(...)
```

说明系统在尝试重启时，连关机动画的 `ctl.start` 都设置失败；这是**系统已经处于不稳定状态后的重启**，不是最初的根因。

### 2.4 重启后的 launcher crash

`/data/system/dropbox/system_app_native_crash@...`（12:24:15）显示 `com.android.launcher` 在 `com.luckyzyx.luckytool` 的 `libdexkit.so` 里 abort；这是另一个独立问题（LuckyTool 模块），与本次 SELinux remount 不是同一时间/同一栈，不能混为一谈。

---

## 3. 当前状态（只读检查）

```
uptime: up 2 min
sys.boot_completed=1
/sys/fs/selinux: rw（当前已恢复）
cat /sys/fs/selinux/enforce: 1
getenforce: Disabled（该命令在本机不可靠，不代表 permissive）
```

- 没有发现 `ovltest`/`selro` 等测试挂载残留。
- `losetup -a` 里是系统 APEX 的正常 loop，没有我的 64MB 测试镜像。
- `/data/local/tmp` 还留有我早期测试的空目录：`apex_p`、`apex_r`、`m1`、`m2`、`m3`、`nsprobe`、`nsprobe2`；无挂载、无进程。
- 当前系统已恢复；但建议观察一段时间，确认没有重复的 `selinux_android_setcontext` / zygote crash。

---

## 4. 根因链（按证据强度）

1. **最强**：selinuxfs 被 `remount,ro` → zygote 写 `/sys/fs/selinux/context` 失败 → `selinux_android_setcontext` abort（日志直接匹配）。
2. **中**：system_server/UI 在系统已不稳定后尝试重启 → `ctl.start=rbootanim` 失败 → system_server crash。
3. **弱/独立**：launcher 的 `libdexkit.so`（LuckyTool）崩溃，发生在重启后，可能是既有模块问题，需单独排查。
4. **可疑但无直接证据**：我在宿主机 namespace 做的 `overlay` 临时挂载（lowerdir=/system，target=/data/local/tmp/ovlmerge）、`debugfs`/`tracefs` 临时挂载、`uiautomator dump`/`screenrecord`。事后看都没有残留，但不能完全排除它们对系统稳定性的影响；以后一律在 private mount namespace 做，且不碰 selinuxfs。

**不是**“`getenforce` 显示 Disabled 导致”——它只是显示；真正的破坏是 selinuxfs 只读。

---

## 5. 设计修正

- **永久禁止**：`mount -o remount,ro,bind /sys/fs/selinux`、任何对 selinuxfs 的 remount/bind-ro。
- **永久禁止**：默认 `setenforce 0`；`--permissive` 仅显式 break-glass。
- 需要限制 chroot 内改 SELinux 时：private ns 内用 tmpfs 遮罩 `/sys/fs/selinux`，或只用 `hardguard` 拦 DSH 工具调用；**不要动宿主 selinuxfs**。
- 所有 mount/overlay/loop 测试：必须在 `unshare --mount --propagation private` 内，带 trap 清理，且不把 `/system`/`/data`/`/dev` 的宿主挂载当试验场。
- `uiautomator`/`screenrecord`/`screencap` 这类系统级操作，先只读/短时，出现异常立即停。
- `notes/11-design-v1.4.md` 已删除 `/sys/fs/selinux` 的 bind-ro 行，改为“不要单独 bind/remount + tmpfs 遮罩/hardguard”。
- 新增 `notes/12-v1.4-verification.md` 记录本次危险实验与所有未验证项。

---

## 6. 我的责任

这是我的操作导致的系统级故障，不是你的配置问题。后续我不会再在你的主力机上执行任何会改宿主挂载/SELinux/loop/overlay 的试验；需要验证时：

1. 先只读检查；
2. 在独立 private mount namespace 内做；
3. 有明确 cleanup/trap；
4. 高风险操作先说明并得到你同意；
5. 不再把“命令返回 0”当成安全证明。

如果你还看到 zygote/SELinux/系统重启相关日志，请把时间点发我，我继续只读排查；在确认稳定前，不建议继续 chroot/loop/overlay 实验。
