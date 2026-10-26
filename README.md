# dsh-chroot-lab

Android 上“真 chroot 的 Ubuntu + DSH + 完整手机控制”的设计与实现工作区。

- 目标设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 当前阶段：**P1–P5 开发产物完成；真实挂载/设备验证由 PC AI 在备用机执行**；`start --apply` 是显式门
- 当前设计：[docs/design-v1.5.md](docs/design-v1.5.md)
- 已锁定决策：[docs/decisions.md](docs/decisions.md)
- 文档入口：[docs/README.md](docs/README.md)
- 验收交接：[docs/handoff.md](docs/handoff.md)

## 结构

```
docs/          当前文档（唯一事实源）+ archive/ 历史归档
module/        KernelSU 模块源码（P1/P2/P3 代码）
bake/          Ubuntu rootfs/镜像烘焙（host/VM）
agent/         宿主 thin agent 协议草案（P4）
agent-app/     最小宿主 agent App（可选，未真机验证）
console-app/   DSH Console 原生控制台 + WebView（可选）
build/         检查/构建脚本
tests/         本地单元测试（不碰系统）
sources/       第三方源码参考（git clone，不纳入版本控制）
experiments/   隔离环境实验（P2 起；P1 不产生系统修改）
```

## 快速检查

```sh
./build/check.sh
./build/build-module.sh
# 产物：dist/dsh_chroot-0.2.0.zip
```

## 当前状态

| 项 | 状态 |
|---|---|
| Ubuntu rootfs / 128GB 镜像 | 设计完成，未构建 |
| 真 chroot apply | 代码已实现；P2 外部验证前禁止在主力机执行 |
| `doctor/plan/status` | 已实现（只读） |
| 手机控制 | 只读/help 验证；写操作未验证 |
| 开机自启 / watchdog | 代码已实现，未验证 |
| 银行/游戏/Play Integrity 检测 | 未验证 |

> 本项目不是安全沙箱。chroot 内是 root，full `/data`/`/dev`/`/sys`；所有挂载只在 private mount namespace，全局 SELinux/prop/挂载视图不动。应用层隐藏性边界见 [docs/hiddenness.md](docs/hiddenness.md)。
