> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# 分析：S1LENCE-D/astrbot-mubuntu

- 来源：`sources/S1LENCE-D_astrbot-mubuntu`（v1.5.2，2026-09-25；约 10KB shell）
- 许可：**未声明**（仓库无 LICENSE）→ 只借鉴思路，不复制代码
- 定位：KernelSU/Magisk/APatch 模块，在 **Mubuntu（Ubuntu 24.04 chroot）** 上一键部署/运行 AstrBot
- 一句话评价：**它是“薄模块 + 外部 rootfs 环境 + Action 一键部署”的典型样本；验证了“默认不挂内部存储”、状态标记、凭据抓取、卸载保留环境的取舍；但进程管理是 nohup/pkill，不是可交付的监督模型。**

---

## 1. 问题与约束

- 目标：用户不想手动在 chroot 里装 Python/依赖/克隆 AstrBot；希望刷模块 + 点“执行”就完成。
- 约束：
  - rootfs 由 **Mubuntu** 提供，本模块不重复携带 Ubuntu（README 明确）。
  - 仅 arm64；KSU/Magisk/APatch；Mubuntu 命令为 `mubuntu run / status / daemon-start / autostart`。
  - 默认 **不挂载内部存储**（`SDCARD_MOUNT=0`），避免存储视图异常；需要时手动开。
- 目标服务：AstrBot，端口 6185，`/opt/astrbot`，Python/uv；初始用户名/密码在日志里。

## 2. 运行模型

- **环境层**：Mubuntu rootfs（`/data/adb/mubuntu/rootfs` 或 `/data/adb/mubuntu`，脚本自动探测存在 `/etc` 的目录）。
- **本模块**：只带 `setup.sh`/`action.sh`/`service.sh`/`config.conf`/`prop_update.sh`；不带走 rootfs。
- **部署**：`action.sh deploy` → `setup.sh`：
  1. `mubuntu autostart on` + `daemon-start`；
  2. `mubuntu run` 里 `apt-get install python3/git/curl/ca-certificates`；
  3. `git clone --depth=1 AstrBot /opt/astrbot`；
  4. `uv sync` 或 venv + pip；
  5. `nohup ... main.py &` + `echo $! > /var/run/astrbot.pid`；
  6. 从 `/var/log/astrbot.log` 抓 `Initial username/password`，写 `/opt/astrbot/.astrbot_cred`；
  7. `touch .astrbot_deployed`，更新 `module.prop` description。
- **开机**：`service.sh` → `mubuntu daemon-start` + `autostart on` → 若 `.astrbot_deployed` 则 `action.sh start`。
- **卸载**：`mubuntu run` 停 AstrBot → `rm -rf $MUBUNTU_RFS/opt/astrbot`；Mubuntu 环境保留。
- **状态**：`action.sh status` 以容器内 `main.py/.git` 为准，读 pid 文件；`prop_update.sh` 写 `✓ 已安装/× 未安装` 到 module.prop description。
- **日志**：`deploy.log`/`update.log`/`service.log`；action 有 `deploylog/updatelog`。

## 3. 生命周期

| 阶段 | 行为 | 问题 |
|---|---|---|
| 安装 | `customize.sh` 检查 Mubuntu；可选解压 `assets/mubuntu.zip` 到 `/data/adb/modules/mubuntu`；设置脚本 0755 | 直接解压另一个模块 zip，绕过 KSU 安装器/metadata/sepolicy；不应模仿 |
| 部署 | Action → setup.sh 同步执行约 10 分钟 | 阻塞 Action；无进度/取消；失败无回滚 |
| 启动 | `nohup ... &` + pid 文件 | 无 s6；无崩溃重启；pid 可能 stale；`pkill -f` 误杀 |
| 停止 | `pkill -f /opt/astrbot/main.py` | 按命令行匹配，可能误杀；无 TERM→等待→KILL |
| 开机 | service.sh 调 mubuntu daemon/autostart，sleep 4，再 start | 没有等 boot_completed/解锁；sleep 不是就绪判定 |
| 卸载 | stop 后 `rm -rf` 容器内 `/opt/astrbot` | 路径依赖 `MUBUNTU_RFS` 探测；若出错可能删错；无校验 |

## 4. 数据与更新

- rootfs：Mubuntu 提供；本模块不备份。
- AstrBot：`/opt/astrbot`，`.venv`/uv；`/var/log/astrbot.log`；`/var/run/astrbot.pid`；`.astrbot_cred`。
- 模块状态：`.astrbot_deployed`；`module.prop` description。
- 更新：`action.sh update_run` → `git pull --ff-only` + `uv sync`/pip + `rm -rf data/dist`；无版本 pin、无回滚、无快照。
- 备份：无 rootfs/数据快照；依赖用户自己备份。

## 5. 为什么这样写（证据）

1. **薄模块 + 外部环境**：README 明确“不重复携带 Ubuntu rootfs：环境由 Mubuntu 提供”；`config.conf` 自动探测 `MUBUNTU_RFS`；这是为了体积/安装速度/环境复用。
2. **Action 一键部署**：KSU 模块页“执行”触发 `action.sh` → `setup.sh`；用户不用进终端；这是移动端产品的关键 UX。
3. **默认不挂内部存储**：README 与 `customize.sh` 写 `SDCARD_MOUNT=0`；避免 FUSE/存储视图异常；需要时手动开。
4. **状态写进 module.prop description**：`prop_update.sh` 把 `✓/×` 写进描述；用户在模块列表直接看到安装状态。
5. **凭据抓取**：`setup.sh` 最多 3 次从日志抓 `Initial username/password`，写入 `.astrbot_cred`；Action `cred` 显示；解决“首次登录信息在哪”。
6. **卸载保留环境**：`uninstall.sh` 只删 `/opt/astrbot`，保留 Mubuntu；符合“应用层/环境层分离”。

## 6. 更优雅的做法（带代价）

| # | 现状 | 更优雅 | 代价/风险 |
|---|---|---|---|
| 1 | `nohup` + pid + pkill | s6-supervise/s6-svscan；pid+starttime；TERM→KILL | 多一个监督器；但可崩溃重启/可诊断 |
| 2 | setup.sh 同步 10 分钟 | 后台 job + 状态/日志/取消；Action 只入队 | 需要 job 状态机；但不会卡 Action |
| 3 | `git clone` + `uv sync` 无 pin | pin commit/tag + lockfile + SHA；失败回滚 | 维护版本；但可复现 |
| 4 | `curl -LsSf astral.sh/uv/install.sh | sh` | 固定版本二进制 + SHA256 | 多一步；但避免远程脚本 |
| 5 | `run_in` 拼接 `sh -c '$*'` | 参数数组/`exec`，不拼 shell 字符串 | 需要改接口；但避免引号/注入 |
| 6 | 解压 `mubuntu.zip` 到模块目录 | 依赖管理器安装/明确前置依赖；不伪造模块 | 用户多一步；但符合 KSU 语义 |
| 7 | 无快照/回滚 | rootfs/`/opt/astrbot` 快照；升级前 pre-change | 空间/停机；但可逆 |
| 8 | 无审计 | 命令/部署/更新审计；凭据脱敏 | 开发量；但可追溯 |
| 9 | 默认不挂存储（好） | 进一步只挂 `/sdcard/DSH` 工作区 | 用户少看见其它文件；但更安全 |

## 7. 对我们的启示

**直接借鉴**
- **薄应用层**：rootfs 环境与应用模块分离；我们的 DSH 模块可以保持 KB 级，payload/rootfs 独立。
- **Action 多动作**：`deploy/start/stop/restart/status/update/log/cred`；我们的 `action.sh` 应从“只 status”扩展为这些动作（至少 `start/stop/status/url/log`）。
- **状态投影**：`module.prop` description 显示 `✓/×`；`dsh-ctl status` 也应有同样状态。
- **默认不挂整块内部存储**：v1.3 daily 已改为只挂 `/sdcard/DSH`；这是正确的。
- **凭据/URL 抓取**：DSH token URL 由 `dsh-run` 写 `state/guest/url`，Action `url` 显示；同思路。
- **卸载保留环境**：我们保留 `/data/adb/chroot`；应用层删除；同思路。
- **AGENTS.md 提交规约**：语法检查/mksh/敏感信息扫描/不夹带；可借鉴到我们的打包门禁。

**明确不采纳**
- `nohup`/`pkill -f`/pid 文件；无监督/无重启/无就绪。
- 同步 10 分钟 Action；无 job 状态。
- 直接解压另一个模块 zip 到 `/data/adb/modules`；绕过 KSU 安装器。
- `curl | sh`、未 pin 的 `git clone`、无校验。
- `rm -rf` 依赖路径探测；无 stop 校验。
- 修改 `module.prop` description 作为唯一状态源（可以显示，但不能作为真实状态；以容器内产物/`dsh-ctl status` 为准）。

**结论**：它是**应用层/UX 参考**，不是运行时参考；v1.3 采纳“薄模块 + 默认不挂整块存储 + Action 多动作 + 状态投影”，但监督/快照/审计仍按 v1.3 的 s6/`dsh-ctl` 做。
