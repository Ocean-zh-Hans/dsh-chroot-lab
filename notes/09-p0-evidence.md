# P0 证据记录：v1.2 设计复验（2026-10-26）

- 设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 原则：不改系统；所有测试在 `/data/local/tmp/ubuntu-review`（临时 rootfs）或私有 mount namespace 中做；结束后删除。
- 目的：给 `notes/08-design-review.md` 的结论提供可复查的命令与输出。

---

## 1. 基础平台（只读）

```sh
id
# uid=10565(u0_a565) gid=10565 ... groups=...,1077(external_storage),3003(inet),...

uname -a
# Linux localhost 6.6.66-android15-8-g29d86c5fc9dd-abogki428889875-4k #1 SMP PREEMPT ... aarch64

getprop ro.build.version.release; getprop ro.build.version.sdk
# 15
# 35

grep -E ' /data | /storage | /mnt/user/0/emulated ' /proc/mounts
# /dev/fuse /mnt/user/0/emulated fuse rw,...,noexec,...
# /dev/block/dm-74 /data f2fs rw,...,nosuid,nodev,...
# tmpfs /storage tmpfs rw,...,noexec,...
```

**结论**：`/data` 无 `noexec`，rootfs 放 `/data` 可执行；`/sdcard` FUSE `noexec`，工作区必须 raw media。

---

## 2. Ubuntu base + chroot + Node 24 + node-pty

### 2.1 下载与校验

```sh
curl -L -o ubuntu-base-24.04.5-base-arm64.tar.gz \
  https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz
# SHA256SUMS: a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
sha256sum ubuntu-base-24.04.5-base-arm64.tar.gz
# a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
```

### 2.2 进入 chroot（私有 mount ns）

```sh
BB=/data/adb/ksu/bin/busybox
$BB unshare --propagation private -m /system/bin/sh -c '
  mount -t proc proc /data/local/tmp/ubuntu-review/proc
  mount -o rbind /dev /data/local/tmp/ubuntu-review/dev
  mount -o rbind /sys /data/local/tmp/ubuntu-review/sys
  $BB chroot /data/local/tmp/ubuntu-review /usr/bin/env -i \
    PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/root TMPDIR=/tmp /bin/bash
'
```

**注意**：不设 `PATH` 时 `apt-get: command not found`；继承 Termux `TMPDIR` 时 `ca-certificates` postinst 失败（见 §5）。必须 `env -i`。

### 2.3 Node 24 + node-pty

```sh
# Node 24.18.0 linux-arm64 官方 tar.xz，解压到 /opt/node
# 复制 Termux 的 node-pty 1.2.0-beta.15（prebuilds/linux-arm64/pty.node）
$BB chroot /data/local/tmp/ubuntu-review /usr/bin/env -i \
  PATH=/opt/node/bin:/usr/bin:/bin HOME=/root TMPDIR=/tmp \
  /opt/node/bin/node -e '
    const pty=require("/opt/node/lib/node_modules/node-pty");
    const p=pty.spawn("/bin/bash",["-c","echo PTY_OK; tty"],{name:"xterm",cols:80,rows:24,cwd:"/"});
    p.onData(d=>process.stdout.write(d));
    p.onExit(e=>process.exit(e.exitCode||0));
    setTimeout(()=>process.exit(2),5000);
  '
```

输出：

```
v24.18.0
PTY_OK
/dev/pts/2
EXIT 0
```

**结论**：glibc + Node 24 + node-pty 预编译可用；`/dev` rbind 的 host devpts 可用。

---

## 3. `dsh web --port 0` 在 chroot 内

```sh
# 把 Termux 的 node_modules bind 到 /opt/dsh/node_modules
$BB chroot /data/local/tmp/ubuntu-review /usr/bin/env -i \
  PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  HOME=/root TMPDIR=/tmp DSH_HOME=/tmp/dshhome \
  /opt/node/bin/node /opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js \
    web --port 0 --no-open
```

输出（token 已替换）：

```
dsh web: http://127.0.0.1:33561/?token=<REDACTED>
```

`curl` 该 URL 返回 `HTTP=303`（重定向到 UI）。

**结论**：动态端口 + token URL 可用；`--no-open` 必须加。

---

## 4. s6 在 chroot 内（非 PID 1）

```sh
# 安装：apt-get install -y --no-install-recommends s6 ttyd
# 服务目录 /etc/s6/test/run:
#!/bin/sh
exec sleep 1000

s6-svscan /etc/s6 &
SV=$!
sleep 2
s6-svstat /etc/s6/test
# up (pid 32146) 2 seconds
s6-svc -d /etc/s6/test
s6-svwait -d -t 5000 /etc/s6/test
# rc=0
s6-svstat /etc/s6/test
# down (signal SIGTERM) 0 seconds, normally up, ready 0 seconds
kill $SV
```

**结论**：s6-svscan/s6-svc/s6-svwait 在非 PID 1、无 PID ns 的 chroot 内可用；设计应使用 `s6-svscan`（不是单个 `s6-supervise` 管两个服务）。

---

## 5. chroot 环境变量 / apt 的坑

### 5.1 PATH 未设置

```sh
chroot ... /bin/bash -c "apt-get update"
# /bin/bash: line 1: apt-get: command not found
```

### 5.2 继承 Termux TMPDIR

```sh
chroot ... /bin/bash -c "export PATH=...; apt-get install -y ca-certificates"
# mktemp: failed to create file via template
# '/data/data/com.termux/files/usr/tmp/ca-certificates.tmp.XXXXXX': No such file or directory
# dpkg: error processing package ca-certificates (--configure)
```

### 5.3 清理环境后

```sh
chroot ... /usr/bin/env -i PATH=... HOME=/root TMPDIR=/tmp \
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates
# Setting up ca-certificates ... 120 added, 0 removed; done.
```

**结论**：所有宿主→客机命令必须 `env -i`，至少固定 `PATH`、`HOME`、`TMPDIR`、`LANG`、`TERM`、`DEBIAN_FRONTEND`。

---

## 6. 工作区权限（raw `/data/media/0` + FUSE）

### 6.1 root 默认创建

```sh
su -c "mkdir -p /data/media/0/.probe; echo hi > /data/media/0/.probe/f; ls -ld /data/media/0/.probe"
# drwxr-s--- root media_rw ... /data/media/0/.probe
# Termux app:
ls -ld /data/media/0/.probe
# Permission denied
cat /data/media/0/.probe/f
# Permission denied
# 通过 /sdcard:
ls -ld /sdcard/.probe
# drwxr-s--- root media_rw ...
```

### 6.2 0777 目录 + 0666 文件

```sh
su -c "mkdir -p /data/media/0/.probe2/a; chmod 0777 /data/media/0/.probe2 /data/media/0/.probe2/a; echo hi > /data/media/0/.probe2/a/f; chmod 0666 /data/media/0/.probe2/a/f"
# Termux app:
echo x >> /sdcard/.probe2/a/f      # rc=0
echo new > /sdcard/.probe2/a/new  # rc=0
```

### 6.3 只 chown 不够

```sh
su -c "mkdir -p /data/media/0/.probe3/b; chown -R 10565:10565 /data/media/0/.probe3/b; chmod 0666 /data/media/0/.probe3/b/f"
# Termux app:
echo x >> /sdcard/.probe3/b/f      # rc=0
echo new > /sdcard/.probe3/b/new  # Permission denied
```

**结论**：H2 的“文件管理器可改”必须显式 `0777/0666`（或同等 ACL），并让 DSH 进程 `umask 000`；只 `chown` 不可靠。

---

## 7. 挂载传播 / mount 语法

### 7.1 toybox `mount --make-rprivate` 不可用

```sh
su -c 'unshare -m /system/bin/sh -c "grep \" / \" /proc/self/mountinfo; /system/bin/mount --make-rprivate /; echo rc=\$?"'
# ... / ro,relatime master:1 - erofs ...
# mount: bad /etc/fstab: No such file or directory
# rc=1
```

- toybox `unshare -m` 后 `/` 仍带 `master:1`（slave，不是 private）。
- `mount -o rprivate /`、`mount -o make-rprivate /` 也都返回 1。

### 7.2 busybox `unshare --propagation private -m` 可用

```sh
su -c '/data/adb/ksu/bin/busybox unshare --propagation private -m /system/bin/sh -c "grep \" / \" /proc/self/mountinfo"'
# ... / ro,relatime - erofs ...   （无 master:）
```

### 7.3 rbind 语法

```sh
/system/bin/mount --rbind /dev /data/local/tmp/m1   # rc=0
/system/bin/mount --bind  /dev /data/local/tmp/m2   # rc=0
/system/bin/mount -o rbind /dev /data/local/tmp/m3 # rc=0
```

**结论**：设计 §5.2 的 `mount --make-rprivate /` 在本机不可用；改用 `busybox unshare --propagation private -m`。`--rbind` 本身可用。

---

## 8. `/apex` 必须 rbind

```sh
mount -o bind  /apex /tmp/apex_p
mount -o rbind /apex /tmp/apex_r
ls /tmp/apex_p/com.android.runtime | wc -l   # 0
ls /tmp/apex_r/com.android.runtime | wc -l   # 6
ls /tmp/apex_p/com.android.runtime/bin | wc -l # 0
ls /tmp/apex_r/com.android.runtime/bin | wc -l # 8
```

**结论**：设计 §5.2 的 `mount --bind /apex` 是空壳；必须 `--rbind`（`/vendor` 同理，有 firmware_mnt/dsp 等子挂载）。

---

## 9. `/dev` rbind 暴露 `/dev/block`

```sh
ls -ld /dev/block
# drwxr-xr-x ... /dev/block
ls /dev/block | wc -l
# 258
```

`--rbind /dev` 会把 `/dev/block` 一起带进 chroot；设计 §5.2 的“不 bind /dev/block”不成立。另：块设备节点的写权限由 inode/设备 cgroup 决定，`mount -o remount,ro,bind /dev/block` 不能阻止 `dd of=/dev/block/...`。

---

## 10. `/proc/1/root` = 宿主根（无 PID ns）

```sh
chroot ... /bin/bash -c '
  echo proc1_root=$(readlink /proc/1/root)
  echo host_dev_block=$(ls /proc/1/root/dev/block 2>/dev/null | wc -l)
  echo host_data=$(ls /proc/1/root/data 2>/dev/null | wc -l)
  echo self_root=$(readlink /proc/self/root)
'
# proc1_root=/
# host_dev_block=258
# host_data=76
# self_root=/
```

**结论**：chroot 内 root 可直接访问宿主 `/`、`/dev/block`、`/data`；所有“只读挂载/不绑 /dev/block”都只是防手滑，不是安全边界。规则层必须覆盖 `/proc/*/root`、`nsenter`、`chroot /proc/...`，且不能承诺防住有意绕过。

---

## 11. busybox tar 不支持 zstd

```sh
/data/adb/ksu/bin/busybox --list | grep -E '^(zstd|tar|xz|gzip)$'
# tar
# gzip
# xz

/data/adb/ksu/bin/busybox tar --help
# -Z compress, -z gzip, -J xz, -j bzip2, -a by extension; no zstd
```

**结论**：`rootfs.tar.zst` 在开机 bootstrap（KSU busybox）里解不开；payload/快照改 `.tar.gz`（或自带静态 zstd）。

---

## 12. 实验清理

测试 rootfs、下载包、临时脚本均在 `/data/local/tmp/ubuntu-review` 与 `~/dsh-review-tmp`；评审结束后删除，不进入仓库。测试未修改系统挂载/分区；私有 mount namespace 退出后自动回收。
