# dsh-chroot-lab

Android 上"全能 AI + 真 chroot"方案的研究与自研工作区。

## 结构

```
sources/       第三方项目源码（git clone --depth 1，不纳入本仓库版本控制）
notes/         我们的笔记：全景、方法、需求、规则、逐项目分析、设计
scripts/       我们自己写的脚本/模块（后续）
experiments/   非破坏验证（P0：Debian chroot + Go 编译，不动现有环境）
```

## 目标（长期）

在 OnePlus PJZ110 / Android 15 / SukiSU 上，自研一套：

- **真 chroot**（非 proot），root 域、能力优先，Go 可编译且产物可直接执行
- 工作区在共享存储可见且可执行 → bind 原始 `/data/media/0`
- 开机自启、不阻塞开机、可启停、可诊断
- **可逆性优先**：rootfs 快照 + 数据备份 + 回收站 + 卸载前强制 umount
- **规则可注入**：`deny.txt` / `ask.txt` 危险命令清单

## 方法

每个第三方项目都要回答六个问题（见 [notes/01-analysis-template.md](notes/01-analysis-template.md)）：
它解决什么问题 / 运行模型 / 生命周期 / 数据与更新 / **为什么这样写** / **有没有更优雅的做法**。

## 笔记索引

- [notes/00-landscape.md](notes/00-landscape.md) 全景：四种运行模型 + 已研究项目
- [notes/01-analysis-template.md](notes/01-analysis-template.md) 逐项目分析模板
- [notes/02-requirements.md](notes/02-requirements.md) 需求定义（能力优先 + 可逆性）
- [notes/03-rules-and-safety.md](notes/03-rules-and-safety.md) 规则层与可逆性设计
- [notes/08-design-review.md](notes/08-design-review.md) **v1.2 评审：8 个 P0、P1 清单、v1.3 修订建议**
- [notes/09-p0-evidence.md](notes/09-p0-evidence.md) 评审的本机实验记录
- [notes/10-design-v1.3.md](notes/10-design-v1.3.md) v1.3 设计（daily/lab/rescue；已被 v1.4 取代）
- [notes/11-design-v1.4.md](notes/11-design-v1.4.md) **full-capability：单一 full 档、128GB 镜像、完整 Android 视图/控制**
- [notes/12-v1.4-verification.md](notes/12-v1.4-verification.md) v1.4 能力实测与未验证清单
- [notes/13-incident-2026-10-26-selinux-remount.md](notes/13-incident-2026-10-26-selinux-remount.md) 2026-10-26 系统崩溃事故复盘与纠正
- [notes/14-red-team-v1.4.md](notes/14-red-team-v1.4.md) **v1.4 红队审查：5 个 P0，暂不批准开工**
- [notes/15-decisions-pending.md](notes/15-decisions-pending.md) **待拍板决策单：选项/代价/推荐**
- [notes/16-hiddenness-first.md](notes/16-hiddenness-first.md) **应用层隐藏性模型：full 可保留；private ns/全局 prop/SELinux 是关键**
- notes/analysis/ 逐项目深挖（进行中）

## 已 clone 的项目

见 [notes/_repo-list.txt](notes/_repo-list.txt)。当前 17 个，覆盖四类运行模型（新增 mini-panel、chroot-mcp-safe、astrbot-mubuntu 三个参考）：
真 chroot 模块（dsh-alpine-ksu、DSH-Chroot、chroot-distro、lhroot）、
proot APK（DSHA、xliaoy、kai6666666、Soodok、masgzy、cmyfqwq）、
Termux 原生（dsh-termux）、AVF 指南（run-linux-on-android-guide、gunyah-on-sd-guide）。
