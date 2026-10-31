# Chroot v2 基线（2026-10-31）

- 基线名称：`chroot-v2-readmax-writecareless-20261031`
- 适用分支：`chroot`
- 范围：`module/`、chroot 生命周期/挂载/镜像/快照、`docs/plan-v2.md` 及研究文档；Agent APK/Console 不在本基线范围。
- 模型：读全开；写按善意但粗心操作者分 R0–R4；DSH 不修改/不判断全局 SELinux。

## 已包含

- P0 文档与策略清理：归档旧读遮蔽/SELinux 模型，建立 `docs/plan-v2.md`。
- P1 读可见性：默认 `DSH_BLOCK_ACCESS=ro`、`DSH_SELINUXFS_MODE=ro`；`/dev/block` 默认只读 bind，不宣称写保护。
- P2：SELinux 门禁移除、rootfs 符号链接 containment、loop 记录失败自动 detach、快照归档 symlink target 校验、watchdog 多镜像扫描、lock 身份校验。
- P3：CLI 粗心陷阱、image 源/size、config 路径、ttyd 状态、service 日志降级、URL token 脱敏、system-rw 死字段等。
- P3-8 五档原语：`module/lib/risk.sh` + `dsh-ctl risk issue|check|status`；R0/R3 dispatcher 门禁、单次 token、审计；R1 覆盖确认、R2 备份+token、R4 无 TTY fail-closed 均有 `test_risk` 覆盖。实际文件写入口仍待接入。

## 验证

- `tests/run.sh`：`all tests passed`（包含 `test_risk.sh`）。
- `tests/test_risk.sh`：ok。
- `sh -n` / `shellcheck` / `shfmt`：通过。
- `build/check.sh`：shell/Kotlin/模块检查通过到 Android helper；当前环境未设置 `ANDROID_NDK_HOME`，helper 编译段未完成。
- 未执行真实设备 start/stop、loop、mount、system-rw 或任何块设备写操作。

## 已知缺口

- 实际文件写命令（例如 `/data/data` 写入）尚未接入 `risk check` 原语；R4 的人工 Console/TTY 通道仍需后续实现。
- P3-2 image 预览的完整 size/sha 摘要仍待补。
- 真机验证前需按 `docs/plan-v2.md` 的 P4 清单重新跑 host/mock，并由人工执行设备检查。
