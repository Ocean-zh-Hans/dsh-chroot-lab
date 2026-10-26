# 路线与当前阶段

## 0. 现状

- P0：设计定稿、决策锁定、旧文档归档 — **完成**
- P1：静态 module 骨架 + `dsh-ctl doctor/plan/status` — **完成（2026-10-26）**
- P2：备用机/VM 的 mount/ns/stop/loop 验证 — 未开始
- **主力机 apply：未开始，且 P2 前禁止**

“P1 完成”的定义不是“能启动 chroot”，而是：

- 所有脚本可 `sh -n` / mksh 解析；
- shellcheck 通过；
- `doctor/plan/status` 有单元测试；
- 不执行任何 mount/umount/losetup/unshare/chroot；
- 模块 zip 可构建，但只含骨架；
- 文档与代码路径一致。

## 1. P0：设计与决策（完成）

- 统一路径：`/data/media/0/ubuntu-rootfs/rootfs.img`、`/tmp/dsh-rootfs`、`/data/adb/chroot`。
- 锁定 15 项决策（见 `decisions.md`）。
- 归档旧设计/旧决策单/旧研究，建立当前单一事实源。
- 修正 P0：两段式 holder、nsenter+chroot、PID/starttime/ns inode、`/storage` 完整、`/apex` 递归 ro、critical ro、selinuxfs tmpfs、工具链、`--apply` 门。

## 2. P1：静态骨架（已完成）

范围：

- `module/` 模块骨架、`module.prop`、安装/开机/卸载/action 脚本；
- `module/bin/dsh-ctl`：`doctor/plan/status`；
- `module/bin/dsh-nsinit`、`dsh-watchdog`：占位/拒绝 apply；
- `module/lib/*`：配置、路径、日志、进程校验、doctor、plan、status；
- `module/control/*`：s6 定义、Android 环境、hook 占位；
- `build/build-module.sh`：构建 skeleton zip；
- `build/check.sh`：shellcheck/shfmt/sh -n；
- `tests/*`：纯本地、无系统修改。

明确不做：

- 不调用 `mount`/`umount`/`losetup`/`unshare`/`chroot`；
- 不写 `/data`；
- 不读 `/proc/*/root` 做破坏性操作；
- 不安装到模块目录；
- 不在手机上跑 apply。

## 3. P2：隔离环境验证（未开始）

前置：

- 备用机/VM 或明确 private ns + trap；
- 备份；
- 有人值守；
- 先只读，再挂载，再 stop。

验收：

1. `unshare --mount --propagation private --uts` 可用；
2. 128GB 镜像 mount/e2fsck；
3. full 挂载表逐项；
4. `dsh-nsinit` 两段式；
5. `nsenter --mount=fd` + chroot；
6. stop 20 次零残留；
7. loop 真正 detach；
8. `/apex` 递归 ro；
9. `/storage` full；
10. 失败 trap 清理。

**零残留定义**：没有同 ns inode 进程、没有 `dsh-rootfs` 挂载、没有 holder、没有测试 loop、没有 `/tmp/dsh-rootfs` 残留。

## 4. P3：DSH 运行时（未开始）

- Node 24 / node-pty / DSH web；
- s6-svscan / dsh-run / URL；
- `/root/.dsh` / 工作区权限；
- snapshot/restore；
- approval-free + hooks 自检；
- 开机自启、watchdog、last-good。

## 5. P4：手机控制（未开始）

- full Android CLI 能力矩阵；
- 写操作负例；
- 宿主 thin agent：UI 树/通知/剪贴板/包管理；
- `am/pm/settings` 写验证；
- `uiautomator runtest`；
- 不在 P4 前写“perfect”。

## 6. P5：打包/迁移/检测（未开始）

- 正式模块 zip + payload；
- 安装/卸载/重启自愈；
- DSH 配置迁移；
- 银行/游戏/Play Integrity 检测矩阵；
- 发布/回滚演练。

## 7. 门禁

任何阶段进入下一阶段前：

- `build/check.sh` 全绿；
- 文档路径/命令与代码一致；
- 未验证项仍标未验证；
- 没有新的宿主级危险测试；
- 变更提交，带验收记录。
