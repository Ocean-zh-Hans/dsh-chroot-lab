# Rootfs 烘焙（P3，host/VM）

这些脚本用于在 **aarch64 Ubuntu 24.04 虚拟机/备用 Linux** 上生成
`rootfs.tar.gz` 和 `rootfs.img`。**不要在主力手机上直接跑**：
debootstrap/chroot 会写大量文件，且当前手机没有已验证的隔离测试环境。

```sh
sudo ./bake/build-rootfs.sh
sudo ./bake/make-image.sh
sudo -E ./bake/verify-rootfs.sh
```

环境变量：

- `ROOTFS_DIR`（默认 `/srv/dsh-rootfs`）
- `NODE_VERSION`（默认 `24.18.0`）
- `DSH_VERSION`（默认 `0.2.0-rc.2`，必须与最终安装的 npm 包一致）
- `IMAGE_SIZE`（默认 `128G`）
- `IMAGE_PATH`（默认 `./out/rootfs.img`）

产物：

- `out/rootfs.tar.gz`：Ubuntu base + Node + Go + Python/build-essential + DSH + s6 + 基础工具
- `out/rootfs.img`：128GB 稀疏 ext4（`mke2fs -d`）
- `out/SHA256SUMS`

`verify-rootfs.sh` 需要 root，在 guest `/tmp` 离线编译并运行 Go 小程序，
并实际启停一个空 s6 监督树；它从 Go 二进制路径推导 `GOROOT`，不依赖裸 chroot 的 `/proc`。
裸 chroot 的 `/dev/null` 必须可用。Android 解包目录的设备节点可能带有不允许
打开的 SELinux 上下文，不能把这种情况的静态 Node/DSH 检查当成 s6 验收；
s6 必须在正式 private namespace 绑定宿主 `/dev` 后验证。

P3 仍需要在备用机/VM 上验证 `chroot`、`node-pty`、`dsh web`、s6、
`/apex` 递归 ro、`/storage` 全量 rbind 和 stop 零残留。
