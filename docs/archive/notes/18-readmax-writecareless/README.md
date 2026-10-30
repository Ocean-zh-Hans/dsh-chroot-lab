# 归档：读权限最大化 / 写操作“善意粗心”模型（2026-10-30）

这组文件是 2026-10-30 用户重新定义 DSH 安全模型前的文档快照，**没有当前效力**。
保留它们只为追溯旧决策和旧审查结论，不要求未来实现兼容。

## 归档原因

旧文档把两件事混在同一个“防护面”里：

1. **全局 SELinux 状态**（Enforcing / `--permissive` / selinuxfs 遮罩）；
2. **chroot 内读取范围**（内部路径遮蔽、读权限收窄）。

用户 2026-10-30 明确：

> 当前运行环境就是主力机正式环境（本项目的最终归宿），对于外部系统的存疑问题参照本操作系统即可，
> 全局 SELinux 我希望的其实是本系统不去影响，也不去在意 SELinux 属性；对于本系统的防护面，
> 原则是，信任操作者是个好人，但是假设它是一个粗心的人。

以及：

> 可读取任意内容，给它最大权限的读，写是粗心好人模型。

因此新模型是：

- **读**：chroot 内 root 可读宿主全部内容；不为了“隐藏”或“安全”读路径而遮蔽、脱敏、降权。
- **写**：按“善意但会粗心”的操作者设计；每个有副作用的写操作都必须明确、可预览、可回滚、可发现残留。
- **SELinux**：DSH 不读取、不修改、不判断全局 SELinux；`selinuxfs` 只作为 guest 可见内容处理，不再作为安全门。

## 快照文件

- [requirements-before.md](requirements-before.md)
- [hiddenness-before.md](hiddenness-before.md)
- [design-v1.5-before.md](design-v1.5-before.md)
- [roadmap-before.md](roadmap-before.md)

当前计划见 [plan-v2.md](../../../plan-v2.md)。
