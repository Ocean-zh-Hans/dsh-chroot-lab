# W4 DSH 规则与审计：hooks 契约、预设、批准

- 核验日期：2026-10-26 ｜ 等级：A（本机安装包源码 + README）
- 源码位置：`~/.local/opt/dsh-termux-runtime/work/node_modules/@deepseek-ai/{dsh-hook-protocol,dsh-hooks-claude-code,dsh-user-approval,dsh-permission-presets}`

## 1. 四个策略组件

| 组件 | 作用 | 关键配置 |
|---|---|---|
| dsh-sandbox-policy | 文件效果策略：read-only / workspace-write / danger-full-access + workspaceRoot | `mode`, `workspaceRoot` |
| dsh-user-approval | 一次性批准 seam：`ask`（默认，无 answerer 时 **fail closed**）/ `never` | `policy: ask\|never` |
| dsh-permission-presets | 把 sandbox + approval 打包成"权限"预设；`/permission` 切换 | `presets`, `defaultPreset` |
| dsh-hooks-claude-code | 运行 Claude Code 风格的 **命令钩子** | `configPath` 指向 hooks.json |

出厂预设：`workspace-write`（sandbox=workspace-write, approval=ask）、`danger-full-access`（sandbox=danger-full-access, approval=**never**）。
→ 我们要的"全能但危险命令先问" = 自定义预设 `root-full`（danger-full-access + **ask**）。

## 2. hooks.json 结构（Claude Code 方言）

```json
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "Bash|Write|Edit",
        "hooks": [ { "type": "command", "command": "/data/adb/chroot/hooks/pretool.sh", "timeout": 10 } ] }
    ],
    "PostToolUse": [
      { "matcher": "*",
        "hooks": [ { "type": "command", "command": "/data/adb/chroot/hooks/posttool.sh", "timeout": 10 } ] }
    ]
  }
}
```

- **matcher**：只由"词字符 + `|`"组成时按**字面量管道分隔**精确匹配（如 `Bash|Write`）；其它按**非锚定正则**；缺失/空/`*` = 全匹配。
  `UserPromptSubmit` 与 `Stop` 没有 matcher 主体，写了会被丢弃。
- **timeout**：单位**秒**（源码 `timeout` → `timeoutSec`）；不写则用默认 **600000ms（10 分钟）**。
- 只执行 `type: "command"`；`http`/`mcp_tool`/`prompt`/`agent` 会被跳过并警告。
- 配置**进程启动时读一次**；改 hooks.json 要重启 DSH（但钩子脚本每次运行都会重读我们的规则文件）。

## 3. 输入 payload（源码实测字段）

公共字段：`session_id`、`transcript_path`（恒为 `""`）、`cwd`、`hook_event_name`。

| 事件 | 额外字段 |
|---|---|
| PreToolUse | `tool_name`、`tool_input`（工具参数对象）、`tool_use_id` |
| PostToolUse | `tool_name`、`tool_input`、`tool_use_id`、`tool_response`（文本化结果） |

## 4. 输出协议（源码实测）

- **退出码 2**：阻断，stderr 作为给模型的理由。
- **退出码 0 + stdout JSON**：
  ```json
  { "hookSpecificOutput": {
      "hookEventName": "PreToolUse",
      "permissionDecision": "deny" | "ask" | "allow",
      "permissionDecisionReason": "…",
      "additionalContext": "…",
      "updatedInput": { } } }
  ```
  `hookEventName` 必须与触发事件一致，否则该块被丢弃。
  顶层还可有 `continue`、`stopReason`、`systemMessage`；**顶层 `decision` 不是合法用法**（allow/deny/ask 只在 `hookSpecificOutput.permissionDecision`）。
- **其它退出码**：非阻塞失败——**动作继续**，失败被记录（即 **fail open**）；钩子起不来也一样。
- 多钩子合并：**deny > ask > allow**。

## 5. 对实现的含义

1. **matcher 先用 `*`**：工具名以 `tool_name` 为准，先用全匹配 + 脚本内分支，观察审计日志里的真实工具名后再收窄。
2. `ask` 决策依赖 dsh-user-approval 的 answerer（Web UI 有）；无 answerer 时 **fail closed**（拒绝）。
3. 钩子是**护栏不是边界**：脚本必须零依赖（`/system/bin/sh` 即可）、快速返回、超时设小（5–10s）。
4. **审计**：PostToolUse 追加 JSONL 到 `/data/adb/chroot/logs/audit/YYYY-MM-DD.jsonl`（时间、session_id、tool_name、tool_input、结果摘要）。
   DSH 自己也会写 `hook/invoked`/`hook/result` 与 `approval/asked/decided` 到会话日志，但那是压缩的会话日志，不便检索。
5. **自检**：启动时喂一条已知坏命令（例如 `mkfs.ext4 /dev/block/...`）给钩子脚本，必须返回 deny；否则告警。

## 6. 待验证

- [ ] 本机实际工具名（Bash? bash?）——用全匹配跑一天审计即可得到
- [ ] `ask` 在纯 CLI（无 Web UI）时是否确实 fail closed
- [ ] 钩子执行延迟（sh 脚本 vs node）
- [ ] `updatedInput` 是否可用于"改写危险命令"（例如自动加 `--dry-run`），还是只用于补全参数
