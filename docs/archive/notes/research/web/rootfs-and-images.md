> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# W2 基础环境：rootfs 来源、镜像 vs 目录、快照

- 核验日期：2026-10-26 ｜ 等级：A（本机实测 + 官方下载页）

## 1. rootfs 来源：用官方 Ubuntu base，而不是 Docker Hub

- 官方发布页：https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ （curl 200）
- 可用文件（arm64）：ubuntu-base-24.04.3/4/5-base-arm64.tar.gz
- 校验：官方 SHA256SUMS（已存 web/raw/ubuntu-base-24.04-SHA256SUMS.txt），例如
  a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2 *ubuntu-base-24.04.5-base-arm64.tar.gz
- **Docker Hub 在本网络不可达**：registry-1.docker.io 与 hub.docker.com 均 15s 超时（curl 28）。
  → chroot-distro 默认从 Docker Hub 拉镜像这条路在本机不可用；必须用本地 tarball（它支持本地归档安装）。
- 备选：debootstrap 从 deb.debian.org（curl 200）构建 Debian 12；或 TUNA 镜像。

**决定**：烘焙阶段用 **Ubuntu base 24.04.x arm64 官方 tarball + SHA256 校验**；运行时不联网。

## 2. ext4 镜像 + loop：只在 /data/media/0 可用（重要实测）

测试方法：dd 64MB → mke2fs -t ext4 -b 4096 -d <dir> → losetup --sector-size 4096 --direct-io=off → mount -t ext4 → 执行镜像内二进制。

| 位置 | 结果 |
|---|---|
| /data/adb/... | ❌ I/O error, dev loopX, sector 0 / EXT4-fs: unable to read superblock |
| /data/local/tmp/... | ❌ 同样 EIO |
| /data/media/0/... | ✅ 挂载成功、镜像内二进制可执行、可写 |

原因（推断但证据强）：/data/adb、/data/local/tmp 属于 **FBE 加密区**，loop 读该文件时拿不到 inline-crypto 的密钥上下文，返回 EIO；/data/media/0 是 Android 唯一**不加密**的用户数据区，loop 可读。
（同一 f2fs 挂载、同样 flags；不是 SELinux，dmesg 无 avc denial，只有块层 I/O error。）

其它发现：
- 镜像必须 -b 4096（默认 1K 块在 4096 逻辑扇区上会 bad block size 1024）。
- losetup 必须显式 --sector-size 4096（util-linux 2.42 可用；toybox/busybox 的 losetup 不支持这些选项）。
- 失败的测试留下过 loop 设备（已手动 detach）；实现时必须 trap 清理。

## 3. 目录 rootfs vs ext4 镜像

| 方案 | 优点 | 缺点 |
|---|---|---|
| **目录 rootfs（/data/adb/chroot/rootfs）** | 简单；在加密区、root-only；无 loop 依赖；可直接 chroot | 快照要全量 tar（1-3GB，压缩后数百 MB） |
| ext4 镜像（/data/media/0） | 快照=复制文件（快、原子切换）；免疫原地写 | 只能放未加密的 /data/media（FUSE 可见、可能被清理）；依赖 loop + 4096 块 |

**推荐**：主线用 **目录 rootfs**；镜像作为可选模式（明确限制在 /data/media/0）。

## 4. 快照与回滚

- f2fs **不支持 reflink**（cp --reflink=always → EOPNOTSUPP）。
- /data/adb **可以建硬链接**（root 域，links=2、同 inode、adb_data_file）。
- 但硬链接快照与活动树共享 inode → **原地写会污染快照**；只适合「冷且不再写」的归档。
- 因此主线：**tar 全量冷快照**（stop → sync → 快照），保留 1 golden + 3 近期 + 1 pre-change；
  数据目录（数百 MB）同样 tar；每周可用 restic 离线归档到 PC。

## 5. 结论

1. rootfs = Ubuntu base 24.04 arm64（官方 + SHA256），解压到 /data/adb/chroot/rootfs。
2. 不用 Docker Hub（不可达），不用 loop（除非镜像放 /data/media/0）。
3. 快照 = tar.zst/gzip 全量；硬链接仅用于不可变归档。
