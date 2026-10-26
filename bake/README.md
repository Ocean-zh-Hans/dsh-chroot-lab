# Rootfs 烘焙（P3，host/VM）

这些脚本用于在 **aarch64 Ubuntu 24.04 虚拟机/备用 Linux** 上生成
`rootfs.tar.gz` 和 `rootfs.img`。**不要在主力手机上直接跑**：
debootstrap/chroot 会写大量文件，且当前手机没有已验证的隔离测试环境。

```sh
sudo ./bake/build-rootfs.sh
sudo ./bake/make-image.sh
./bake/verify-rootfs.sh
```

环境变量：

- `ROOTFS_DIR`（默认 `/srv/dsh-rootfs`）
- `NODE_VERSION`（默认 `24.18.0`）
- `DSH_VERSION`（默认 `0.2.0-rc.2`，必须与最终安装的 npm 包一致）
- `IMAGE_SIZE`（默认 `128G`）
- `IMAGE_PATH`（默认 `./out/rootfs.img`）

产物：

- `out/rootfs.tar.gz`：Ubuntu base + Node + DSH + s6 + 基础工具
- `out/rootfs.img`：128GB 稀疏 ext4（`mke2fs -d`）
- `out/SHA256SUMS`

P3 仍需要在备用机/VM 上验证 `chroot`、`node-pty`、`dsh web`、s6、
`/apex` 递归 ro、`/storage` 全量 rbind 和 stop 零残留。
