> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# DSH 的扩展面：规则层与审计层该挂在哪

- 核验日期：2026-10-26 ｜ 依据：本机安装树 `.local/opt/dsh-termux-runtime/work/node_modules/@deepseek-ai/*` 的 289 个包 README + 源码
- 目的：回答"我们的 deny/ask 规则清单和命令审计，应该实现在哪一层"

## 结论

DSH 自己已经有 **五个策略 seam**，规则层不需要我们从零造：
优先用 **`dsh-hooks-claude-code` + 一个 `hooks.json` 命令钩子**做规则与审计（小、透明、规则文件可随时改）；
当需要"解析后的真实路径判断"或更强失败语义时，再升级为 **原生 Cordis 插件**挂在 `tools/pre-execute`。

## 五个策略 seam（都有本地 README 证据）

| 层 | 包 | 关键事实 |
|---|---|---|
| 沙箱模式 | `dsh-sandbox-policy` | 模式 `read-only` / `workspace-write` / `danger-full-access` + `workspaceRoot`；每会话可切换、重启后保留；每次模型请求前把当前策略写进运行时上下文 |
| 沙箱后端 | `dsh-sandbox-local` | 自动选 bwrap/Landlock/Windows ACL 后端；没有可用后端时抛 `SANDBOX_UNAVAILABLE`，**绝不静默无隔离运行**；每次执行报告 full/partial 与拒绝签名 |
| 用户批准 | `dsh-user-approval` | `ctx.approval`；策略 `ask`（默认）/ `never`；**没有 answerer 时 fail closed（unavailable）**；每次请求在会话日志追加 `approval/asked` + `approval/decided` |
| 权限预设 | `dsh-permission-presets` | 一个"权限"选择器，把 sandbox 与 approval 打包；自带 `workspace-write`{sandbox=workspace-write, approval=ask} 与 `danger-full-access`{sandbox=danger-full-access, approval=never}；`defaultPreset` 写进未来会话；`/permission` 切换 |
| 文件观测 | `dsh-fs-observation-policy` | 先读后写；读后文件变化则拒绝并要求重读 |
| 钩子 | `dsh-hook-protocol` + `dsh-hooks-claude-code`/`-codex` | 读 `hooks.json` 的**命令钩子**；事件：SessionStart / UserPromptSubmit / **PreToolUse（可 block 或 ask）** / PostToolUse / Stop / SubagentStart/Stop；决策折叠 **deny > ask > allow**；matcher 是工具名 |

补充：`dsh-hooks-claude-code` 的扩展点映射（README 明写）：
`UserPromptSubmit`→`agent/pre-step`、`PreToolUse`→`tools/pre-execute`（waterfall）、`PostToolUse`→`tools/post-execute`、`Stop`→`agent/turn-stopping`。

## 规则层：两条实现路径

| | A. hooks.json 命令钩子 | B. 原生 Cordis 插件 |
|---|---|---|
| 依赖 | 只需挂 `dsh-hooks-claude-code`，写一个脚本 | 需要写 TS 插件并进 profile 组合 |
| 规则文件 | 脚本每次执行时**现读** deny/ask 清单 → 改规则即时生效 | 同理，可更复杂 |
| 能拿到什么 | 事件 JSON（session_id / cwd / hook_event_name / 工具名 + 输入） | 完整 harness API（可解析真实路径、访问 fs 服务、结构化日志） |
| 失败语义 | **fail open**：退出码非 2 = 非阻塞失败，"动作继续、失败被记录"；钩子崩了也继续 | 待验证（waterfall 抛错大概率让调用失败，即 fail closed） |
| 性能 | 每次匹配都 spawn 一个进程 | 进程内 |
| 维护 | 极小 | 与 DSH 版本耦合 |

**建议**：先 A。理由：
1. 用户"立规矩"要的是**规则文件可读可改**，A 的 `deny.txt`/`ask.txt` 最直观；
2. A 是官方支持的兼容路径，升级 DSH 不会立刻碎；
3. 我们的安全模型本来就不依赖"拦截绝对可靠"，而依赖**可逆性**（快照/回收站）——A 的 fail open 与这个模型自洽。
   但必须在设计里写明：**钩子是护栏，不是安全边界**；并且要有一条"钩子失效自检"（启动时喂一条已知坏命令，必须被拒绝，否则告警）。

**什么时候升级到 B**：当规则需要判断"解析后的绝对路径"（例如 `rm -rf $SOMEDIR` 里 $SOMEDIR 指向 /data），或需要把审计写成结构化事件、与 goal/jobs 联动时。

## 审计层

- DSH 已经记：`approval/asked` / `approval/decided`（会话日志，压缩存储，hook 脚本读不到）与 `hook/invoked`/`hook/result`。
- 我们要补的是**可 grep 的独立审计**：`PostToolUse` 钩子把 时间 / cwd / 工具名 / 完整输入 / 结果摘要 追加到 `/data/adb/<mod>/logs/audit/YYYY-MM-DD.log`。
- 与 H8 对齐：规则命中单独记一条（deny/ask 命中原因），便于复盘"规则太严"还是"模型跑偏"。

## 权限预设怎么配（给"全能"的推荐）

出厂的两个预设都不完全合适：
- `workspace-write` + `ask`：本机沙箱后端不可用 → 起不来；
- `danger-full-access` + `never`：能跑，但**批准机制被一并关掉**（`never` = 确定性拒绝所有请求）。

推荐自定义一个预设（写进 `cordis.patch.yml` 的 preset 表）：

```yaml
presets:
  root-full:
    sandbox: danger-full-access
    approval: ask        # 保留 fail-closed 的"询问"通道
defaultPreset: root-full
```

这样：沙箱不可用的事实不变（本机只能 danger-full-access），但**批准 seam 仍然活着**，PreToolUse 钩子可以对 `ask.txt` 命中的命令发起询问；没有 answerer 时 fail closed，正好符合"危险命令先问"。

## 关键风险与对策

1. **钩子 fail open**（官方行为）：脚本崩了命令照跑。→ 脚本零依赖、加启动自检、并在设计文档里明确"这不是安全边界"。
2. **钩子配置只在进程启动时读一次**（`configPath`）：改 hooks.json 需要重启 DSH；但**规则清单文件不受影响**（脚本运行时读），所以日常只改规则文件即可。
3. **钩子运行在会话工作区**（cwd = workspace），相对路径要小心；用绝对路径。
4. **每次工具调用 spawn 一个进程**：规则脚本要快（纯 sh + 前缀匹配即可，不要拉 node）。
5. **只有命令钩子会执行**：http/mcp_tool/prompt/agent 类型会被跳过并警告。

## 落地位置（本机现状）

- 组合文件：`~/.dsh/profiles/web/cordis.yml`、`~/.dsh/profiles/web/cordis.patch.yml`
- 数据/会话：`~/.dsh/sessions`、`~/.dsh/storages`
- 凭据：`~/.dsh/.credentials.yaml`（我们不做隐私保护，但审计日志不要把它写进去）

## 待验证

- [ ] 本机 `/permission` 当前生效的预设与两个 knob 的实际值
- [ ] `PreToolUse` 的 JSON 输入里，Bash 工具是否给完整命令行与 cwd
- [ ] `ask` 决策在没有 answerer（纯 CLI 运行）时是否真的 fail closed
- [ ] 每次钩子 spawn 的延迟（sh 脚本 vs node）
- [ ] 原生插件在 `tools/pre-execute` 抛错时是否 fail closed（决定 B 路径的可行性）
