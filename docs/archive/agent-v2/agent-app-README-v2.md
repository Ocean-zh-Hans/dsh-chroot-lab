# DSH Agent（可选 Android 控制面）

这是 chroot 内 Agent 的可选宿主桥接，不是 chroot 的替代品，也不是隐藏性组件。安装后包名 `dev.dsh.agent` 对其他 App 可见。界面使用 Kotlin Compose；无障碍、通知、协议与 root helper 使用 Java。

## 功能与安全边界

- `127.0.0.1:17321` 的 JSON Lines 协议；每行请求最大 1 MiB，响应最大 16 MiB。token 保存在 App 私有目录，只绑定 loopback，不开放局域网端口。
- UI 优先可选 root `UiAutomation`，普通路径使用 `AccessibilityService`；部分系统操作使用固定 Android API 或 root shell。`tier` 可指定 `auto`、`uiautomation`、`a11y`，响应带 `via` 和 `degraded`。不是每项功能都有全部后端，失败时返回明确错误。
- 会话保存自有节点快照，不长期持有系统 `AccessibilityNodeInfo`。支持默认会话、最多 3 个 LRU 会话、epoch、查询 DSL、命名选择器、patch/全量回退和幂等写请求。
- 查询读快照，写操作重新 resolve → verify → act。`matchKey` 只是定位提示，不是稳定身份；歧义、旧 epoch、不可见目标会拒绝执行，不退化为旧坐标盲点。
- 磁盘恢复的快照 `source=disk`、`stale=true`，只供查询；写操作返回 `SNAPSHOT_READONLY`，必须重新 `session.analyze`。
- 保存默认不含正文，autosave 默认关闭；密码字段掩码。优先使用 Android Keystore AES-GCM，失败时明确返回 `encrypted=false` 的 gzip 回退。App 不允许备份。
- `readonly:true` 拒绝设备写入。危险操作需要 `confirm:"YES"`；文件路径只允许 `/sdcard/Download` 和 `/sdcard/.dsh` 的规范化子路径。
- 普通 App 不申请 root。独立 root helper 是显式安装、单独授权的高权限边界，提供受控命令及需确认的 `shell.exec`，不能把它描述为“无任意 shell”。

## 构建

```sh
./agent-app/build.sh
# agent-app/dist/dsh-agent-0.2.0.apk
# agent-root-helper/build/dsh-root-helper.jar
```

固定使用 Gradle 8.11.1、AGP 8.7.3、Kotlin 2.0.21、Compose BOM 2024.09.03、`activity-compose:1.8.2`。依赖锁为 `agent-app/gradle.lockfile`，依赖 SHA256 为 `gradle/verification-metadata.xml`；wrapper 分发包带 SHA256 校验，不关闭依赖验证。首次构建需要官方仓库，后续可使用已验证缓存。需要 Android SDK/build-tools、JDK 17。

APK 验证 debug 签名和 zipalign；debug 签名不是发布签名。helper 用 SDK `android.jar` 编译并由 D8 生成仅含 `classes.dex` 的 JAR，不包含伪造的系统类。

## 安装、授权与可选 root helper

1. 用户明确安装：`adb install -r agent-app/dist/dsh-agent-0.2.0.apk`。
2. 打开 DSH Agent，按需在系统中启用无障碍、通知使用权、位置权限和文件访问。文件访问不是安装时自动获得的权限。
3. 首页查看桥接状态；token 路径为 `/data/data/dev.dsh.agent/files/token`。默认复用现有 token。`security.tokenPolicy` 可查询 `rotateOnStart`；只有显式带 `confirm:"YES"` 启用后，才会在后续 App 进程启动时轮换，旧 token 随之失效，调用方需重新读取。`security.tokenRotate` 仍是显式单次轮换。`ping` 返回版本、源码声明的发布构建日期和后端状态。
4. 可选前台保活和开机接收器用于普通 App 服务，不保证绕过 ROM 的后台限制。**force-stop 不能由普通 App 自行撤销**，必须重新打开 App 并检查无障碍；PJE110 会移除该组件，恢复时只添加 `dev.dsh.agent/dev.dsh.agent.AgentService`，不覆盖其它服务，也不静默修改设置。
5. root helper 必须由用户在已授权的 root 环境中显式安装和启动：

```sh
DSH_ADB_SERIAL=<serial> ./agent-app/root-helper.sh install
DSH_ADB_SERIAL=<serial> ./agent-app/root-helper.sh start
# 检查或停止：
DSH_ADB_SERIAL=<serial> ./agent-app/root-helper.sh status
DSH_ADB_SERIAL=<serial> ./agent-app/root-helper.sh stop
```

helper 位于 `/data/adb/dsh-agent`，监听 `127.0.0.1:17322`，与 App 共用 token。启动脚本校验 PID、进程启动时间和 `RootHelper` 进程名。`enable` 会安装可选开机脚本，`disable` 移除它；默认未启用。UiAutomation 槽位被占用时返回 `SLOT_BUSY`，不强制停止占用者。

## 协议和客户端

```json
{"token":"<32 hex token>","id":1,"method":"session.analyze","params":{}}
```

```json
{"id":1,"ok":true,"via":"a11y","degraded":false,"result":{"sessionId":"s-...","epoch":1}}
```

Python 标准库客户端在 `agent/dsh_agent.py`，支持单次调用、截图落盘、事件订阅和 ADB 转发：

```sh
python3 agent/dsh_agent.py --serial <serial> session.analyze '{}'
python3 agent/dsh_agent.py --serial <serial> ui.query \
  '{"expr":"find text~=\u0027搜索\u0027 && clickable | first | click"}'
python3 agent/dsh_agent.py --serial <serial> shot '{}' --shot out/agent.png
```

客户端区分控制确认和事件帧，按请求 ID 等待；写操作超时不得盲重试。普通请求默认 15 秒，无副作用自检/prep/录屏/确认 shell 使用与工作量匹配的有界预算；较长预算不改变 `UNKNOWN` 语义。旧客户端不带会话的 `ui.dump/click/setText/swipe` 保持可用。`activity.start`、URL 和分享的 `launchRequested:true` 只表示已请求启动，`confirmed:false` 不保证目标页面已显示。

### 主要方法

| 分类 | 方法 |
|---|---|
| 感知/UI | `sys.info`、`ui.dump`、`ui.texts`、`ui.windows`、`ui.find`、`ui.query`、`ui.resolve`、`ui.path`、`ui.refs`、`ui.seek`、`ui.explain`、`ui.highlight`、`ui.snapshot`、`shot`、`screen.diff`、`ui.wait`、`ui.waitIdle` |
| 输入 | `ui.click/longClick/doubleTap/gesture/swipe/drag/fling/scrollUntil`、`ui.setText/appendText/clearText/selectAll/copy/paste/cut`、`ui.key/global`、`ime.status/show/hide/set`、`input.profile` |
| 会话 | `session.analyze/current/status/list/use/drop`、`session.save/load/saved.list/saved.delete/autosave/restoreLast`、`ui.flag/unflag/flags.list` |
| 应用/系统 | `package.list`、`activity.start`、`app.openUrl`、`intent.send`、`settings.get/put`、`power.state/wake/sleep/reboot`、`lock.state/unlock`、`net.status/wifi/data/airplane`、`display.setRotation/setDensity`、`prep.automation/restore`、`dnd.set`、`volume.get/set/mute`、`sensor.read`、`location.get/mock` |
| root 管理 | `package.install/uninstall/clear/forceStop/enable/disable`、`activity.forceStop`、`perm.grant/revoke`、`task.list/remove/splitScreen/pip`、`user.list`、`perf.fps`、`crash.anr`、`root.ping` |
| 媒体/通信/文件 | `notifications.list/click/dismiss/reply`、`clipboard.get/set/clear`、`media.nowPlaying/play/pause/next/prev/stop`、`screen.record`、`call.dial`、`sms.send/list/search`、`contacts.search`、`calendar.list`、`fs.put/get/list/mkdir/rm`、`share.file` |
| 事件/作业/诊断 | `events.subscribe/unsubscribe`、`macro`、`job.start/status/cancel/schedule`、`recording.start/stop/replay`、`backend.status/selftest`、`diag.log/tail/clear`、`backend.diag.tail`、`security.setMode/tokenRotate/tokenPolicy` |

`events.subscribe` 使用独立事件帧，事件帧不含请求 `id/ok`。`since=0` 只接新事件；续传传 `since` 与 `processId`（兼容 `bootId`），只回放该序号之后且仍在缓冲中的事件。进程代际不符或缓冲不足时 `resetRequired:true`，不伪装成连续历史。`baseSeq` 是订阅时高水位。`packages` 可过滤事件包，慢客户端收到 `events.dropped`；`backend.status` 另提供近似事件速率与队列深度。SystemUI 的时钟变化不会使会话或 idle 持续失效，但仍可显式订阅 SystemUI 事件。作业支持 at、cron 和 `on:<event>`；持久化的是计划而不是已完成结果。

`ui.find` 使用 `where` 与精简 `fields`；`selector` 同时接受结构化对象和 DSL 字符串，`ui.flag` 也支持这两种形式。`ui.query` 使用受限管道解析器，不使用 `eval`、脚本引擎或反射求值；`find|count` 的数量同时位于 `result.count` 与 trace。多目标未显式选择时返回 `AMBIGUOUS_MATCH`。`ui.click` 可直接接受 `{x,y}`，`ui.explain` 接受数值坐标。副作用不回滚，写后 epoch 会递增，后续请求应使用返回的新 epoch，或在一次 `macro` / `ui.query` 中连续执行，由服务端重新解析。写请求可带 `idempotencyKey`，同键同参数返回 `replayed:true`，不同参数返回 `CONFLICT`，超时的结果状态是 `UNKNOWN`。

## 已验证范围与限制

PJE110 / Android 15 已实测：Compose 页面、socket/token、T1 和关闭无障碍后的 T0 会话、会话复用、旧 session/epoch 拒绝、中文输入、双指手势、歧义保护、幂等点击、磁盘快照只读、截图/差异、事件/作业、通知测试标签、剪贴板往返、自动化设置恢复、DND 读回、token 轮换和录屏。root helper 的 UiAutomation、截图、固定系统命令和有界确认 shell 也已实测。

- **文件访问尚未由用户授权**；`fs.put/get/list/mkdir/rm/share.file` 的实际读写/分享未验收，不能由 AppOps 静默授权替代。
- 未内置 OCR；`shot` 返回 PNG，由调用方 OCR。
- 该 ROM 的 `task.splitScreen/pip` 返回 `UNAVAILABLE`；`ime.show` 没有可靠的 shell-only 实现。
- `sms.send` 返回 `ROLE_REQUIRED`，不伪装成已发送。短信、联系人、日历的 root 查询没有读取真实私人数据或验收。
- 未实际执行 reboot、卸载、清数据、撤销权限、修改 PIN/锁屏密码或 mock 真实位置；只验证缺少确认时的拒绝。
- 普通 `location.get` 需要用户位置权限；未静默授予。真实验证码、银行、游戏、Play Integrity 和所有 ROM 的通用控制不在验证承诺内。

详细设备证据见 `docs/verification.md` 第 29 节。
