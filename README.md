# dsh-chroot-lab

Android 上“真 chroot 的 Ubuntu + DSH + 完整手机控制”的设计与实现工作区。

- 目标设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 当前阶段：**P1 静态骨架已完成**；只有 `dsh-ctl doctor/plan/status`，**没有 apply/mount**
- 当前设计：[docs/design-v1.5.md](docs/design-v1.5.md)
- 已锁定决策：[docs/decisions.md](docs/decisions.md)
- 文档入口：[docs/README.md](docs/README.md)

## 结构

```
docs/          当前文档（唯一事实源）+ archive/ 历史归档
module/        KernelSU 模块源码（P1 静态骨架）
build/         检查/构建脚本
tests/         本地单元测试（不碰系统）
sources/       第三方源码参考（git clone，不纳入版本控制）
experiments/   隔离环境实验（P2 起；P1 不产生系统修改）
```

## 快速检查

```sh
./build/check.sh
./build/build-module.sh
# 产物：dist/dsh_chroot-0.1.0.zip
```

## 当前状态

| 项 | 状态 |
|---|---|
| Ubuntu rootfs / 128GB 镜像 | 设计完成，未构建 |
| 真 chroot apply | 未实现；P2 前禁止 |
| `doctor/plan/status` | P1 实现 |
| 手机控制 | 只读/help 验证；写操作未验证 |
| 开机自启 / watchdog | 设计完成，未实现 |
| 银行/游戏/Play Integrity 检测 | 未验证 |

> 本项目不是安全沙箱。chroot 内是 root，full `/data`/`/dev`/`/sys`；所有挂载只在 private mount namespace，全局 SELinux/prop/挂载视图不动。应用层隐藏性边界见 [docs/hiddenness.md](docs/hiddenness.md)。
