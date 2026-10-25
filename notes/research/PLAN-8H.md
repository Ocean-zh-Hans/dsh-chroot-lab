# 8 小时研究计划（严谨设计）

目标：产出一份能照着做的设计，而不是印象式方案。每条关键结论必须能指回：官方文档 / AOSP 或上游源码 / 本机实测 / 可复现的 issue。

## 可信度分级（写进每份笔记）

- **A 级**：官方文档、AOSP 源码、上游仓库源码与发布说明、本机实测（命令 + 输出）
- **B 级**：项目 issue 里有复现步骤与日志的讨论、维护者回复
- **C 级**：个人博客 / CSDN / 知乎 / 酷安帖子（只当线索，必须用 A/B 验证）
- **D 级**：无出处的转述、AI 生成内容（不采用）

## 工作流

| 工作流 | 内容 | 产出 |
|---|---|---|
| W1 平台事实 | Android 15 的 SELinux 域映射（seapp_contexts / mac_permissions）、存储（FUSE / scoped storage / noexec）、内核（GKI 6.6、config、模块签名）、SukiSU（su / ksud / feature / umount paths） | notes/research/web/android15-selinux-storage.md |
| W2 基础环境 | 发行版选型（Debian / Ubuntu / Alpine）、rootfs 来源与校验（Docker Hub / OCI、debootstrap、官方云镜像）、ext4 镜像 vs 目录、loop 挂载、fsck / resize、空间与性能 | notes/research/web/rootfs-and-images.md |
| W3 运行时 | mount 传播（rprivate / rslave / rbind）、MNT_LOCKED、umount 语义、PID1 与僵尸回收、s6 vs runit、启动/停止状态机、service.sh 非阻塞 | notes/research/web/mounts-namespaces-supervision.md |
| W4 DSH 集成 | hooks.json 契约（PreToolUse / PostToolUse 的 JSON、exit code、timeout）、permission presets、approval answerer、审计、端口 0 | notes/research/web/dsh-hooks-and-permissions.md |
| W5 路线与设计 | 路线比较表、推荐与理由、详细设计 v1.0、验收清单 | notes/05-design-v1.md |

## 规矩

1. 遇到不懂的词（MNT_LOCKED、seapp_contexts、PR_SET_CHILD_SUBREAPER、rprivate、open_tree、fsfreeze 等）先查清楚再写。
2. 个人博客只当线索；结论必须由 A/B 级材料或本机实测支撑，并在笔记里标明等级。
3. 每个坑尽量找 issue 实证（含复现条件与版本）。
4. 不安装模块、不改 Termux/DSHA；只读，外加在 /data/adb/chroot 里做无副作用实验。
5. 网页原文存 notes/research/web/raw/（截断到关键段落），并在 INDEX.md 登记 URL / 日期 / 等级 / 要点。
