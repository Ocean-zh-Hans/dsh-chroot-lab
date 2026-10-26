# 事故复盘：2026-10-26 SELinux 只读 remount 关联的系统崩溃

- 设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 时间：2026-10-26 12:07 左右执行测试；12:18 出现大面积 native crash / system_server 崩溃；12:24 重启后恢复。
- 结论先行：**我在主机上做了多项宿主级危险测试，随后系统崩溃；责任在我。** 其中 `mount -o remount,ro,bind /sys/fs/selinux` 是时间关联最强、最可疑的操作；但**单点机制尚未证实**，不能把“selinuxfs 被置只读”写成已证明的根因。

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

看起来“成功”，但这是不该在主力机上做的测试。

---

## 2. 为什么不能把它写成“已证实根因”

Linux `mount(2)` 对 `MS_REMOUNT|MS_BIND|MS_RDONLY` 的语义是：**只让通过该挂载点的访问变只读，不影响该文件系统的其他挂载**。zygote 写的是宿主原来的 `/sys/fs/selinux/context`，不是 `/data/local/tmp/selro`。因此：

- 这个 bind remount 与 zygote `selinux_android_setcontext` 失败**有时间关联**；
- 但仅凭这条命令和日志，**不能证明**它把底层 selinuxfs 全局置只读，也不能证明它是唯一原因。

事故前后我还在主机 namespace 做了其他危险测试：

- `mount -t overlay`（lowerdir=`/system`，target=`/data/local/tmp/ovlmerge`）——全局 namespace，不是 private；
- `mount -t debugfs` / `mount -t tracefs` 到 `/data/local/tmp`；
- `uiautomator dump`（rc=137 Killed）；
- `screenrecord` / `screencap`；
- 其他 mount/unshare 试验。

这些都可能是系统不稳定的参与者，不能只抓一个命令当定论。

---

## 3. 崩溃证据

### 3.1 zygote 的 SELinux 上下文失败

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

### 3.2 其他 app 的 native crash

同一时间段：

- `com.google.android.gms` native crash
- `com.tencent.mm` 多次 native crash
- `com.heytap.speechassist` native crash
- `com.android.systemui` java NullPointerException
- `/data/anr/anr_3772_...`：`InputManagerService` watchdog blocked 15s

### 3.3 system_server 崩溃

`/data/system/dropbox/system_server_crash@1792988337185.txt`（2026-10-26 12:18:57）：

```
java.lang.RuntimeException: failed to set system property "ctl.start" to "rbootanim"
 at com.android.server.power.OplusShutdownFeature.playShutdownAnimation(...)
 ...
 at com.android.server.wm.WindowManagerService.reboot(...)
 at com.android.server.policy.RestartAction.onPress(...)
```

这是系统已经不稳定后尝试重启时，连关机动画都起不来；是**后果**，不是最初根因。

### 3.4 重启后的 launcher crash

`/data/system/dropbox/system_app_native_crash@...`（12:24:15）显示 `com.android.launcher` 在 `com.luckyzyx.luckytool` 的 `libdexkit.so` 里 abort；这是另一个独立问题（LuckyTool），与本次测试不是同一栈，需单独排查。

---

## 4. 当前状态（只读检查）

```
uptime: up 2 min
sys.boot_completed=1
/sys/fs/selinux: rw（当前已恢复）
cat /sys/fs/selinux/enforce: 1
getenforce: Disabled（该命令在本机不可靠，不代表 permissive）
```

- 没有发现 `ovltest`/`selro` 等测试挂载残留。
- `losetup -a` 里是系统 APEX 的正常 loop，没有我的 64MB 测试镜像。
- `/data/local/tmp` 里我早期测试的空目录已用 `rmdir` 清掉。

---

## 5. 根因判断（按证据强度）

| 强度 | 判断 |
|---|---|
| 确定 | 我在主机上做了多项宿主级 mount/overlay/debugfs/tracefs/uiautomator 测试；随后系统出现 zygote SELinux 上下文失败、多 app native crash、system_server 重启 |
| 高度可疑 | `mount -o remount,ro,bind /sys/fs/selinux` 是最可疑的操作之一；时间上紧邻 |
| 未证实 | “selinuxfs 被全局置只读”这一单点机制；mount(2) 语义与它矛盾；其他测试也未排除 |
| 独立问题 | 重启后 launcher 的 `libdexkit.so`（LuckyTool）崩溃，另查 |

**结论**：不把任何单点写成“已证实根因”；但**所有这类宿主级测试一律禁止**，这是不需要等根因确认就能做的决定。

---

## 6. 设计/流程修正

- **永久禁止**：`mount -o remount,ro,bind /sys/fs/selinux`、任何对 selinuxfs 的 bind/remount。
- **永久禁止**：在主机 namespace 做 overlay/debugfs/tracefs/loop 试验；只能在备用机/VM 或显式隔离的 private namespace 做，并带 trap 清理。
- **永久禁止**：默认 `setenforce 0`；`--permissive` 仅显式 break-glass。
- 需要限制 chroot 内改 SELinux 时：private ns 内 tmpfs 遮罩 `/sys/fs/selinux`，或 hardguard；不要动宿主 selinuxfs。
- `notes/11-design-v1.4.md` 已删除 `/sys/fs/selinux` 的 bind-ro 行，改为“不要单独 bind/remount + tmpfs 遮罩/hardguard”。
- `notes/12-v1.4-verification.md` 已把该测试标为危险实验；`notes/14-red-team-v1.4.md` 记录 5 个 P0。
- 以后所有验证：先只读；再备用机/VM；再 private namespace + trap；高风险操作先说明并得到同意；不再把“命令返回 0”当安全证明。

---

## 7. 我的责任

这是我的操作导致的系统级故障，不是你的配置问题。后续我不会再在你的主力机上执行任何会改宿主挂载/SELinux/loop/overlay 的试验。若还看到 zygote/SELinux/系统重启相关日志，请把时间点发我，我只读排查；在确认稳定前，不建议继续 chroot/loop/overlay 实验。
