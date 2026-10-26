> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# f2fs + Android 自研 chroot：快照 / 回滚 / 备份调研

> **2026-10-26 后续修正**：本文 TL;DR 第 3 条“硬链接快照当主线”已被 `05-design-v1.md` 否定——硬链接与活动树共享 inode，原地写会污染所有快照；主线改为 **目录 rootfs + 冷 tar 快照**，硬链接只用于“冷且不再写”的不可变归档。详见 `08-design-review.md` §P1-3。

> 目标：rooted Android 15，/data=f2fs；rootfs 1–3GB（目录或 ext4 镜像）位于 /data/adb/<mod>；数据目录数百 MB；要求一键回滚、空间可控。
> 方法：官方文档 + 内核/AOSP 源码；另在本机 Termux **app 域**（/data=f2fs，内核 6.6.66-android15，uid untrusted_app_25）做只读性实测，会单独标注。凡未在目标 **root 域**验证者标「未验证」。

## 结论速览（TL;DR）
1. **f2fs 不支持 reflink**：`f2fs_file_operations` 无 `remap_file_range`，FICLONE 返回 `EOPNOTSUPP`。`cp --reflink=auto`（GNU cp 默认）静默回退为普通全量复制；`--reflink=always` 直接失败。
2. **f2fs 支持硬链接**；`cp -al` / `rsync --link-dest` 在**同一 f2fs 挂载内**可行（不能跨挂载点）。能否成功**取决于 SELinux 域**：app 域被禁止 `link`（EACCES），root/Magisk 域通常可以（未验证）。
3. **推荐**：目录型 rootfs + `rsync --link-dest` 硬链接快照 + bind mount/原子软链回滚；保留「1 基线 + N 增量 + 1 golden 离线归档」。空间 ≈ 基线大小 + Σ变更字节。
4. **btrfs/zfs 在 Android GKI 上不现实**：GKI android15-6.6 defconfig 无 `CONFIG_BTRFS_FS`，ZFS 非 mainline；且 `CONFIG_MODULE_SIG` 要求签名模块。
5. **一致性铁律**：停进程 → `sync` → 递归 `umount` → 快照；快照后必须**校验和 + chroot 冒烟启动**，否则"能回滚"只是假象。

## 1. f2fs：reflink 与硬链接

### 1.1 reflink
- 内核 `fs/f2fs/file.c` 的 `f2fs_file_operations` 只有 llseek/read_iter/write_iter/open/release/mmap/flush/fsync/fallocate/ioctl/splice/fadvise，**没有 `.remap_file_range`**（v6.6 与 master 一致）。[file.c](https://github.com/torvalds/linux/blob/v6.6/fs/f2fs/file.c)
- VFS 层：`!file_in->f_op->remap_file_range` 时 `vfs_clone_file_range()` 返回 `-EOPNOTSUPP`。[remap_range.c](https://github.com/torvalds/linux/blob/v6.6/fs/remap_range.c)
- 手册：`cp --reflink=auto`（也是不写该选项时的默认）在 COW 不可用时**回退普通复制**；`=always` 报错；`=never` 标准复制。[coreutils cp](https://www.gnu.org/software/coreutils/manual/html_node/cp-invocation.html)
- 本机实测（app 域，/data=f2fs，coreutils 9.11）：`--reflink=always` → `Permission denied`（直接 ioctl FICLONE 得 `EACCES`，疑似 SELinux 对非白名单 ioctl 的拒绝）；`--reflink=auto`/默认 → `rc=0`，产物为完整 4MiB 普通文件。**结论：不要依赖 reflink。**
- 附：f2fs 有 `F2FS_IOC_MOVE_RANGE`（块搬移/碎片整理），语义与 reflink 不同，**不能当快照**。[f2fs docs](https://docs.kernel.org/filesystems/f2fs.html)

### 1.2 硬链接与 SELinux
- f2fs 是 POSIX 文件系统，硬链接正常；`cp -al`、`rsync --link-dest` 依赖它。**限制**：硬链接不能跨挂载点（EXDEV）；ext4 镜像内部的文件无法被 /data 上的硬链接引用，故镜像方案要么在镜像内快照，要么挂载后 rsync 到 /data。
- **app 域被拒**：`app.te` 对 appdomain 只授予 `app_data_file:file create_file_perms`，而 `create_file_perms = { create rename setattr unlink rw_file_perms }`，**不含 `link`**；而 `link(2)` 时 SELinux 恰好在源 inode 上检查 `FILE__LINK`。→ 普通 app 无法创建硬链接。[app.te](https://github.com/LineageOS/android_system_sepolicy/blob/lineage-22.0/private/app.te) · [global_macros](https://github.com/LineageOS/android_system_sepolicy/blob/lineage-22.0/public/global_macros) · [selinux hooks.c](https://github.com/torvalds/linux/blob/v6.6/security/selinux/hooks.c)
- 本机实测（app 域，/data=f2fs）：`link(2)`→`EACCES`；`cp -al`→`Permission denied`；`rsync --link-dest`→**rc=0 但硬链接数为 1（静默退化为全量复制）**，必须显式校验链接数。
- **root 域**：/data/adb 的 label 为 `adb_data_file`；root/Magisk 进程运行在 Magisk 注入策略的域（magisk/su），不受 appdomain 规则约束，通常可 link（**未验证**：取决于 Magisk 版本/策略）。另 `fs.protected_hardlinks` 上游默认 0；即便为 1，owner+rw 的 root 也不受限。[file_contexts](https://github.com/LineageOS/android_system_sepolicy/blob/lineage-22.0/private/file_contexts) · [namei.c](https://github.com/torvalds/linux/blob/v6.6/fs/namei.c)
- **普通 app 与 root 的差异**：① SELinux `link` 权限（app 无 / root 有）；② 路径（app 可写处只有自身 /data/data——被前述策略禁 link——与 scoped storage/FUSE——本就不支持硬链接；root 直接操作 /data/adb）；③ DAC `protected_hardlinks` 只在非 owner/无 rw 时拦截。

## 2. 方案对比（含命令与空间）

| 方案 | 命令示例 | 每次空间 | 增量 | Android 现实性 |
|---|---|---|---|---|
| tar 全量 | `tar -C rootfs -caf snap.tzst .` | ≈ 压缩后全量（1–3GB 的 30–60%） | 无 | 通用；N 份 = N× |
| tar 增量 | `tar --listed-incremental=snar -caf s.tar .` | 仅变更 | 链式 | 可，但链依赖、恢复易错、无去重 |
| rsync --link-dest | `rsync -aHAX --delete --link-dest=/snap/prev live/ /snap/new/` | 仅变更字节 + 元数据 | 是（硬链接） | **推荐**（同 f2fs、root 域）；失败会静默全量 |
| cp -al | `cp -al rootfs /snap/<ts>` | 0 数据（共享 inode） | 一次性 | 快，但**原地修改会串改快照**；只适合冻结树 |
| restic | `restic -r /data/adb/repo backup rootfs` | 去重+压缩后增量 | 是（块级） | Termux 有包；适合本地/SFTP/NAS |
| kopia | `kopia snapshot create rootfs` | 去重+压缩 | 是 | Termux **无官方包**（可自建静态二进制，未验证） |
| borg | `borg create repo::s rootfs` | 去重+压缩 | 是 | Termux 有 borgbackup；Python+C，较重 |
| btrfs/zfs | `btrfs sub snap` / `zfs snapshot` | ≈0 | COW | **不现实**：GKI 无 BTRFS，模块需签名 |

- 空间量级：硬链接方案 N 份 ≈ 基线 + Σ变更（元数据每文件数百字节）；tar 全量 N 份 = N×全量；restic/borg ≈ 唯一数据 + 索引。
- Termux 包：`restic`、`borgbackup`、`rclone`、`rdiff-backup` 有；`kopia` 无。[termux-packages](https://github.com/termux/termux-packages/tree/master/packages/restic)
- GKI 证据：defconfig 有 `F2FS_FS=y`/`F2FS_FS_COMPRESSION=y`/`OVERLAY_FS=y`/`DM_SNAPSHOT=y`，**无 BTRFS**；`MODULE_SIG=y`+`MODULE_SIG_PROTECT=y`（自编译 btrfs/zfs 模块需签名，不现实）。[gki_defconfig](https://github.com/aosp-mirror/kernel_common/blob/android15-6.6/arch/arm64/configs/gki_defconfig)

## 3. 推荐（最简可用、空间可控）

- **主方案：目录型 rootfs + 硬链接快照（root 域，全部在 /data）**
  - 基线：`rsync -aHAX --numeric-ids --delete /data/adb/<mod>/rootfs/ /data/adb/snap/base/`
  - 增量：`rsync -aHAX --numeric-ids --delete --link-dest=/data/adb/snap/prev /data/adb/<mod>/rootfs/ /data/adb/snap/<ts>/`（并把 <ts> 记为新的 prev）
  - 校验：`find /data/adb/snap/<ts> -type f -links +1 | wc -l` 应 >0（证明真是硬链接而非静默全量）；`rsync -navc --delete /data/adb/<mod>/rootfs/ /data/adb/snap/<ts>/` 应无差异。
  - 回滚（一键、可逆）：先把当前 live 存成 `/data/adb/snap/pre_rollback/`；再 `mount --bind /data/adb/snap/<ts> /data/adb/<mod>/rootfs`（或原子软链：`ln -sfn <ts> current.tmp && mv -Tf current.tmp current`）；重启 chroot 进程。
  - 若必须 ext4 镜像：挂载后对挂载点做同样的 `rsync --link-dest` 到 /data；或评估 dm-snapshot（GKI 已启用 `DM_SNAPSHOT`，但 Android 常缺 `dmsetup`、流程复杂，**未验证**）。
- **保留策略**：`base`(golden) 1 份 + 最近日备 3–7 份 + `pre_rollback` 1 份；按 mtime/数量 prune，并给 snap 目录设容量上限。预算 ≈ 3GB(基线) + 数百 MB~数 GB(变更)。
- **离线备份**：设备内快照救不了 /data 损坏/清机。每周 `restic -r <SFTP/NAS> backup` 或 `tar|zstd` 到 PC/NAS，并定期 `restic check`。
- **数据目录**：DB/日志先 `sqlite3 .backup`/`mysqldump` 出一致性副本再纳入快照，避免抓到半写状态。

## 4. 一致性与恢复演练

- **运行中快照的坑**：① 进程持有已 unlink 的文件 → 快照"丢文件"；② 正在写的文件 → 半截内容；③ 硬链接快照会被**原地修改**串改（同一 inode，所有快照一起变）；④ 跨越 bind mount（/proc、/sys、/dev、tmpfs）会抓到伪内容或失败；⑤ device nodes/socket 复制需特权，tar 恢复需 root。
- **正确顺序**：`stop`（按 pgid/`pkill -f chroot`，确认 `fuser -m <rootfs>` 为空）→ `sync` → `umount -R <rootfs>`（含 proc/sys/dev/dev/pts/shm 与 loop）→ 快照 → 重新挂载 → 启动。
- **freeze**：f2fs 支持 `.freeze_fs`（`fsfreeze -f`），但**不要冻结整个 /data**（会冻结全机）；只对独立 loop 镜像使用。[f2fs super.c](https://github.com/torvalds/linux/blob/v6.6/fs/f2fs/super.c)
- **恢复演练（必须做）**：① 清单：`find snap/<ts> -type f -printf '%P %s\n' | sort | sha256sum` 存档；② 内容比对 `rsync -navc --delete live/ snap/<ts>/`；③ 冒烟：`chroot snap/<ts> /bin/sh -c 'echo ok'`，或 bind mount 后启动核心服务跑一次自检；④ 镜像 `e2fsck -fn img`；⑤ 回滚后再切回，验证可逆。
- **SELinux/xattr**：用 `-X` / `tar --xattrs`；硬链接沿用原 inode context（自动一致），rsync 新复制文件可能 label 不对，恢复后 `restorecon -R`。

## 5. 不确定 / 未验证
- 目标机 **root/Magisk 域**对 `/data/adb`(adb_data_file) 的 `link` 是否放行 —— 未在目标机实测（本机仅 app 域）。
- 本机 `cp --reflink=always` 的 `EACCES` 归因 SELinux ioctl（推断），未抓 audit 逐层确认。
- dm-snapshot 在 Android 上的实际可用性（缺 dmsetup/流程）—— 未验证。
- `fsfreeze` 作用于 /data 的风险与行为 —— 未验证。
- AOSP sepolicy 官方源 android.googlesource.com 本机不可达，链接用 LineageOS 22.0 镜像（核心文件与 AOSP 同源）；个别厂商/版本可能改策略。

## 参考
- Linux 内核 v6.6/master：`fs/f2fs/file.c`、`fs/f2fs/super.c`、`fs/remap_range.c`、`security/selinux/hooks.c`、`fs/namei.c`（github.com/torvalds/linux）
- AOSP sepolicy（LineageOS 22.0 镜像）：`private/app.te`、`public/global_macros`、`private/file_contexts`
- GNU coreutils cp 手册；ioctl_ficlone(2)；man rsync（--link-dest / -H）
- restic / kopia / borg 官方文档；termux-packages
- GKI android15-6.6 `gki_defconfig`
