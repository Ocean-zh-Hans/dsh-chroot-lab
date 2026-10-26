> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# Web 资料库索引

> 规则：只存有出处的原文摘录（官方文档、源码、issue）；个人博客放 blog/ 并标注「仅线索」。
> 每条记录：URL、抓取日期、等级（A/B/C）、要点、是否已被本机实测验证。

| 日期 | 主题 | 来源 | 等级 | 文件 | 本机验证 |
|---|---|---|---|---|---|
| 2026-10-26 | SELinux 域映射 | 本机 /system/etc/selinux | A | raw/selinux-{plat,product,vendor}-seapp.txt | 已比对（有不一致） |
| 2026-10-26 | Ubuntu base 校验 | cdimage.ubuntu.com | A | raw/ubuntu-base-24.04-SHA256SUMS.txt | 已记录 |
| 2026-10-26 | mount 传播/locked/umount | man7 mount_namespaces(7)、umount(2) | A | raw 摘录见 W3 笔记 | 部分（unshare -m 实测） |
| 2026-10-26 | subreaper | prctl(2) PR_SET_CHILD_SUBREAPER | A | W3 笔记引用 | 否 |
| 2026-10-26 | s6-supervise | skarnet s6 官方文档 | A | W3 笔记引用 | 否 |
| 2026-10-26 | service.sh 阻塞 | KernelSU bootstages.rs（B 级转述） | B | research/chroot-supervision… | 否 || 2026-10-26 | DSH hooks 契约 | 本机安装包源码/README | A | dsh-hooks-and-permissions.md | 待验证工具名/ask 行为 |
| 2026-10-26 | 权限预设/批准 | 本机安装包 README | A | dsh-extension-surface.md | 否 || 2026-10-26 | issue 采矿（KSU/chroot-distro/SukiSU） | GitHub issues（B 级） | B | issues-pitfalls.md | 部分（post-fs-data/devpts 未复现） |
| 2026-10-26 | 来源可信度 | 项目事实盘点 | — | source-credibility.md | — |
