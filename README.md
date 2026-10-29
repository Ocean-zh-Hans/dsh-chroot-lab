# dsh-chroot-lab

Android 上“真 chroot 的 Ubuntu + DSH + 完整手机控制”的设计与实现工作区。

- 目标设备：OnePlus PJZ110 / Android 15 / kernel 6.6.66 / SukiSU 4.2.1-r2
- 当前阶段：**P1（接手审核修正与 PC 构建检查）基线已通过；模块 `0.2.1`、Console/Agent `0.2.0` 已安装到主力机并保留历史数据；运行中直接重启暴露了旧 holder 身份记录导致的恢复缺陷，`0.2.2` 修复候选正在验证，尚不宣称完整交付**
- 备用机 PJE110 已完成 128GB chroot/DSH、20 次启停、快照/回滚和核心 App 流程验收。主力机结果不外推为所有 ROM、应用或长期场景的兼容性保证。
- 当前设计：[docs/design-v1.5.md](docs/design-v1.5.md)
- 已锁定决策：[docs/decisions.md](docs/decisions.md)
- 文档入口：[docs/README.md](docs/README.md)
- 验收交接：[docs/handoff.md](docs/handoff.md)

## 结构

```
docs/          当前文档（唯一事实源）+ archive/ 历史归档
module/        KernelSU 模块源码与生命周期实现
bake/          Ubuntu rootfs/镜像烘焙（arm64 host/VM）
agent/         宿主 thin agent 协议说明
agent-app/     可选宿主 agent App（备用机接口已实测）
console-app/   可选 Chroot Console 原生控制台 + WebView
build/         检查、Android helper/APK/模块构建与产物校验
tests/         本地 mock 与行为测试（不碰真实挂载/系统）
sources/       第三方源码参考（git clone，不纳入版本控制）
experiments/   隔离环境实验（只在备用机/VM）
```

## 快速检查

```sh
./build/check.sh
./build/build-module.sh
./agent-app/build.sh
./console-app/build.sh
# 默认模块：dist/dsh_chroot-0.2.2.zip（不含控制台或 system overlay）
```

`./build/build-module.sh --with-console` 只在模块根附带 `console.apk`，不安装 App，也不修改系统分区视图。默认不附带。

## 当前状态

| 项 | 状态 |
|---|---|
| Ubuntu rootfs / 128GB 镜像 | ARM64 VM 烘焙与校验完成；主力机保留原镜像和历史数据，未重新烘焙覆盖 |
| 真 chroot / DSH | 主力机与备用机均完成真实运行验收；主力机运行中重启后出现恢复缺陷，`0.2.2` 候选待真机复核 |
| `doctor/plan/status` | PC 检查与备用机正式模块检查通过；主力机 `0.2.1` staged 严格 doctor 59 pass / 1 warn / 0 fail |
| 生命周期/快照/恢复 | 备用机 20 次启停零残留；主力机真实启动、正常停止、再次 boot、只读备份与 last-good 冷快照通过；运行中直接重启的恢复缺陷已复现，修复候选待验收 |
| 手机控制 / Agent | PJE110 已实测 Compose、T0/T1 会话/DSL、中文输入、事件/作业和剪贴板往返；主力机 ping、无障碍和无输入自检通过；文件访问未授权，真实验证码未测 |
| 开机自启 / watchdog | 正常停止后的 boot 与 watchdog 曾通过；主力机运行中直接重启自启未通过，正在修复，不能宣称完整重启验收通过 |
| 两个 Android APK | 主力机 Console/Agent `0.2.0` 已安装并核对实际 APK 哈希；外部浏览器与 Agent 本机 API 通过；debug 签名不是发布签名 |
| 模型账号会话 | 未调用账号，未验证完整模型会话 |
| 银行/游戏/Play Integrity | 未验证，不能承诺通过 |

> 本项目不是安全沙箱。chroot 内是 root，full `/data`/`/dev`/`/sys`；所有 chroot 挂载只在 private mount namespace，不主动修改全局 SELinux/prop/挂载视图。应用层隐藏性边界见 [docs/hiddenness.md](docs/hiddenness.md)。
