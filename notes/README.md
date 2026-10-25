# 笔记

## 组织方式

- `00-landscape.md` 全景与分类（先看这个）
- `01-analysis-template.md` 每个项目必须回答的六个问题
- `02-requirements.md` 我们要什么（已拍板：全能 AI、能力优先、可逆性优先）
- `03-rules-and-safety.md` 规则层 / 可逆层 / 审计层设计
- `04-design.md` 设计过程稿（v0.5，已被 05 取代）
- `05-design-v1.md` 设计 v1.2 定稿（路线比较/详细设计/验收/验证记录）
- `06-packaging.md` 模块打包清单与构建门禁
- `07-roadmap.md` 分阶段实施路线
- `research/PLAN-8H.md` 8 小时研究计划
- `research/` 专题研究
  - [dsh-extension-surface.md](research/dsh-extension-surface.md) DSH 规则与审计该挂在哪（hooks/presets/approval）
  - [kernelsu-magisk-module-packaging-and-lifecycle.md](research/kernelsu-magisk-module-packaging-and-lifecycle.md) 模块打包与生命周期
  - [f2fs-android-chroot-snapshot-backup.md](research/f2fs-android-chroot-snapshot-backup.md) f2fs 快照/备份
  - [chroot-supervision-mount-cleanup-and-lifecycle.md](research/chroot-supervision-mount-cleanup-and-lifecycle.md) 进程监督/挂载清理/启停
- `analysis/<项目名>.md` 逐项目深挖（六节结构）
  - ✅ [dsh-alpine-ksu.md](analysis/dsh-alpine-ksu.md) —— 真 chroot KSU 模块，骨架蓝本（2026-10-26）
  - ✅ [chroot-distro.md](analysis/chroot-distro.md) —— 最该当依赖用的 rootfs 轮子（2026-10-26）
  - ✅ [dsh-chroot.md](analysis/dsh-chroot.md) —— 设计密度最高：25+ 反例、威胁模型、内核极限（2026-10-26）
  - ⬜ DSHA（proot 工程化天花板）
  - ⬜ dsh-termux（现有环境的 9 个补丁）
- `_repo-list.txt` 已 clone 的第三方项目清单

## 写笔记的规矩

1. **为什么这样写**必须有证据：源码行号、issue、作者文档；找不到就写"推测"。
2. **更优雅的做法**要标代价，不能只说"可以更简单"。
3. 本机实测结果要标日期和设备（PJZ110 / Android 15 / SukiSU）。
4. 结论先行，细节在后；每条结论能指回一个文件或一条命令。