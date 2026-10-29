# DSH Agent 客户端与 Android 控制面

目的：补 chroot 内不便完成的手机感知、输入和持续会话，不替代 chroot。完整实现、安装授权和限制见 `agent-app/README.md`；验证范围见 `docs/verification.md` 第 29 节。

## 协议

- App 只监听 `127.0.0.1:17321`，使用 App 私有目录的 token；请求/响应是单行 JSON。
- 可选 root helper 独立监听 `127.0.0.1:17322`。App 本身不申请 root；helper 由用户明确安装、启动和停止。
- UI 在 `UiAutomation`、`AccessibilityService` 之间按能力回退；系统功能使用 Android API 或固定 root 命令。响应包含 `via`、`degraded`、`tookMs`，失败返回结构化错误。
- `session.analyze` 建立服务端快照；查询读快照，写操作重新解析当前活节点。`matchKey` 是定位提示，不保证唯一或永久身份；歧义和旧 epoch 均拒绝执行。
- 磁盘恢复的快照只读；autosave 默认关闭，默认不保存正文，密码字段掩码。
- 危险操作要求 `confirm:"YES"`；`readonly:true` 拒绝设备写入。root helper 的 `shell.exec` 也必须确认，不能再把整体安全边界描述为“无任意 shell”。

```json
{"token":"<32 hex token>","id":1,"method":"session.analyze","params":{}}
{"id":1,"ok":true,"via":"a11y","degraded":false,"result":{"sessionId":"s-...","epoch":1}}
```

## Python 客户端

`agent/dsh_agent.py` 仅使用 Python 标准库，支持单次请求、ADB 转发、截图保存、事件订阅和作业等待：

```sh
python3 agent/dsh_agent.py --serial <serial> session.analyze '{}'
python3 agent/dsh_agent.py --serial <serial> ui.find \
  '{"where":{"text~":"设置","clickable":true},"limit":5}'
python3 agent/dsh_agent.py --serial <serial> ui.query \
  '{"expr":"find text~=\u0027搜索\u0027 && clickable | first | click"}'
python3 agent/dsh_agent.py --serial <serial> shot '{}' --shot out/agent.png
```

在 chroot 内可省略 `--serial`，用 `--token-file /data/data/dev.dsh.agent/files/token` 连接 loopback。PC 端使用 `run-as` 读取 debug App 的 token，不打印 token；release 构建需要另外设计安全凭据交付。

```python
from agent.dsh_agent import AgentClient

with AgentClient.from_adb("<serial>") as client:
    client.call("session.analyze")
    result = client.call("ui.find", {"where": {"text~": "设置"}, "limit": 5})
    with client.subscribe(["app.foreground"]) as stream:
        event = stream.read(timeout=10)
```

客户端为每个 socket 复用读取器，事件帧不会被当作其它控制请求的确认。普通请求默认 15 秒；无副作用自检为 60 秒，多命令 prep 为 130 秒，录屏按持续时间增加余量，确认 shell 按受限参数增加余量。较长预算不是自动重试：写操作超时或 `resultState=UNKNOWN` 时仍应使用读接口确认结果；`idempotencyKey` 用于同请求去重，而不是把不同操作当成同一请求。

事件帧不含请求 `id/ok`。`since=0` 只接新事件；续传同时传 `since` 与 `processId`（兼容 `bootId`），仅回放该序号之后的缓冲事件，进程变化或缓冲不足返回 `resetRequired:true`。可用 `packages` 过滤，慢订阅者收到 `events.dropped`。作业支持 at、cron 和事件触发。旧 `ui.dump/click/setText/swipe` 调用仍可无会话使用。

主机测试：

```sh
PYTHONPATH=. python3 -u -m unittest tests.test_agent_client -v
./build/check.sh
```

PJE110 已实测主要会话、T0/T1、中文输入、事件/作业和 token 轮换链路；文件访问仍待用户手动授权，未内置 OCR，ROM 不支持的任务窗口能力返回 `UNAVAILABLE`。没有实测真实短信发送、私人内容查询、危险 root 写操作或所有 ROM 的通用兼容性。
