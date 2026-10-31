# phone-mcp：DSH 侧 phone.* facade

这是 DSH runtime 侧的 MCP server，不是 APK 内的代码，也不修改 rootfs。
它把 Agent APK 的 v3 JSONL 协议包装成语义工具，让模型面对
`phone.observe` / `phone.act` / `phone.open`，而不是选择 “APK 还是 Ubuntu”。

## 为什么不用 `/opt/phone/phone_mcp.py`

旧 server 走 `/usr/local/bin/phone` → adb → Termux，只有坐标点击/截图，
没有结构化无障碍树、ref、page、`uiEffect`、通知动作、剪贴板历史。
新的 server 直接连接 APK 的 `127.0.0.1:17321`，使用同一个 `DeviceSession`。

## 文件

- `phone_mcp.py`：MCP stdio server，纯 Python 标准库，无第三方依赖。
- `module/control/dsh/runtime.patch.yml`：只包含 `id: mcp-phone`，由
  `dsh-run` **无条件**传入 `--patch`，不依赖 audit/permission hook 插件是否存在。
- `hooks.patch.yml` / `hooks-audit-only.patch.yml`：只负责 audit hook；
  它们和 runtime overlay 叠加，而不是把 phone 当作 hook 的附属品。

## 部署位置

`module/control` 在 guest 中挂载为 `/opt/dsh-control`，所以默认命令是：

```text
/usr/bin/python3 /opt/dsh-control/dsh/phone-mcp/phone_mcp.py
```

`dsh-run` 总是先传 `runtime.patch.yml`；再根据 profile 是否有 permission
preset，叠加 `hooks.patch.yml` 或 `hooks-audit-only.patch.yml`。因此即使
audit hook 不可用，`mcp__phone__*` 仍然存在，不会回退到旧 `/opt/phone`。

## 环境变量

| 变量 | 默认 | 含义 |
|---|---|---|
| `DSH_PHONE_HOST` | `127.0.0.1` | APK JSONL 地址 |
| `DSH_PHONE_PORT` | `17321` | AgentServer 端口 |
| `DSH_PHONE_TOKEN_FILE` | `/data/data/dev.dsh.agent/files/token` | Agent token 文件 |

token 文件不可读时，工具返回 `TOKEN_MISSING`，不会回退到 adb。

## 工具

| 工具 | 作用 |
|---|---|
| `observe` | `ui.view`；结构化树、`page(g,seq)`、前台包/Activity、可选截图 |
| `act` | `ui.act`；tap/longClick/setText/append/clear/focus/scroll/swipe/drag/press/global/copy/cut/paste/dismiss/scrollIntoView/setSelection/doubleTap |
| `fill` | `ui.fill`；聚焦、填值、可选 submit |
| `expect` | `ui.expect`；0 超时断言，失败给候选 |
| `wait` | `ui.wait`；package/activity/text/ref/keyboard/idle |
| `gesture` | `ui.gesture`；paths 或 pinchIn/pinchOut/twoFingerSwipe |
| `notify` | `notif.list/history/dismiss/reply` |
| `clipboard` | `ui.clipboard`；get/set/clear/history |
| `open` | shell `am start` / `monkey` + `ui.invalidate` + `ui.wait`；返回同一页 |
| `status` | `sys.status`；前台、page、能力、paused |
| `handoff` | `handoff.request` / 状态查询；恢复必须由人点通知 |
| `receipt` | `receipts.list` |
| `do` | 带 `expect` 检查点的语义步骤序列；不做通用宏/任意 shell |

## 连贯性规则

1. `observe` 返回 `page.g/seq`，之后动作带 `page`；不匹配直接
   `STALE_PAGE`，不把旧 ref 交给模型。
2. `open` 先执行 shell 启动，再 `ui.invalidate`，清空 APK 的 old ref，
   然后 `ui.wait` 等 package/activity，最后 `observe` 返回可用页面。
3. APK 的 `uiEffect` 为 `certain/possible` 的动作会更新 page；`ui.invalidate`
   是显式的跨后端栅栏。
4. `phone.do` 只调用本 server 的语义工具；shell 持久会话属于 DSH 的
   terminal/tool-bash-persistent 能力，不在 MCP server 里伪造。

## 当前限制

- `attach` 尚未实现；文件面仍由 DSH shell + 目标 App picker 处理。
- `phone.do` 不支持任意 shell 步骤，只支持语义工具与 sleep。
- 未在真机上验证 `am start`/`monkey` 的 task/flag 行为；真机验收单列。
- MCP server 进程退出会丢失 `page` 状态；重新 `observe` 即可恢复。
