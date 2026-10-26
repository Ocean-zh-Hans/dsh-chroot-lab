# 路线与当前阶段

## 0. 现状

- P0：设计定稿、决策锁定、旧文档归档 — **完成**
- P1：静态 module 骨架 + `dsh-ctl doctor/plan/status` — **完成（2026-10-26）**
- P2/P3：mount holder、lifecycle、snapshot、system-rw、guard、rescue、DSH runner、bake 脚本 — **代码实现，待外部验证**
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

## 2. P1/P2/P3：静态骨架与运行时（代码完成）

当前代码范围：

- `module/`：模块元数据、安装/开机/卸载/action；
- `dsh-ctl`：`doctor/plan/status`、`start --apply/stop/restart/boot/diag/log`、
  `snapshot create/list/restore`、`restore`、`purge`、`system-rw`、`guard`、`rescue`；
- `dsh-nsinit`：private namespace holder，挂载镜像/Android 视图/控制面/遮罩，
  fork/exec s6 子进程并等待；
- `dsh-watchdog`：identity/SELinux 检查；
- `control/bin/dsh-run`：DSH URL 发现；
- `control/hooks`：hardguard + 可选 deny/ask；
- `bake/`：host/VM 上的 Ubuntu rootfs 与 ext4 镜像脚本；
- `tests/`：mock 外部命令，**不执行真实 mount/loop/unshare/chroot**。

本地测试边界：

- 所有危险命令在测试里都是 fake；
- `start` 的 mock 测试只验证状态机/挂载调用序列，不证明内核挂载成功；
- 真实 apply 只在 P2 备用机/VM 做。

## 3. P2：隔离环境验证（代码已备，未执行）

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
5. `nsenter --mount=/proc/PID/ns/mnt` + chroot；
6. stop 20 次零残留；
7. loop 真正 detach；
8. `/apex` 递归 ro；
9. `/storage` full；
10. 失败 trap 清理。

**零残留定义**：没有同 ns inode 进程、没有 `dsh-rootfs` 挂载、没有 holder、没有测试 loop、没有 `/tmp/dsh-rootfs` 残留。

## 4. P3：DSH 运行时（代码已备，未验证）

- Node 24 / node-pty / DSH web；
- s6-svscan / dsh-run / URL；
- `/root/.dsh` / 工作区权限；
- snapshot/restore；
- approval-free + hooks 自检；
- 开机自启、watchdog、last-good。

## 5. P4：手机控制（最小 agent 源码/构建，未真机验证）

- full Android CLI 能力矩阵；
- 写操作负例；
- 宿主 thin agent：UI 树/通知/剪贴板/包管理；
- `am/pm/settings` 写验证；
- `uiautomator runtest`；
- 不在 P4 前写“perfect”。

## 6. P5：打包/迁移/检测（脚本已备，硬件验证未开始）

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
