# DSH Agent（可选宿主 thin agent）

这是一个**可选**的 Android 小应用，用于补 chroot 内无法获得的：
UI 树、通知列表、剪贴板、部分固定 Activity/Package 查询。

它**不替代** chroot，也不是隐藏性必需组件。安装后是一个可见 App；
如果不想多一个包，完全可以用 chroot 内的 `am`/`pm`/`input`/`screencap`。

## 构建

```sh
./agent-app/build.sh
# 产物：agent-app/dist/dsh-agent-0.1.0.apk
```

需要 Termux 的 `aapt2`、`d8`、`apksigner` 和本地 `android-sdk/platforms/android-35/android.jar`。
构建脚本使用 debug keystore；不要把它当发布签名。当前 Termux 没有 `zipalign`，产物未对齐（安装可能提示；发布前补 zipalign）。

## 安装与授权

1. `adb install -r agent-app/dist/dsh-agent-0.1.0.apk`（或手动安装）。
2. 打开 DSH Agent，记录 token 文件路径：
   `/data/data/dev.dsh.agent/files/token`（root 可读）。
3. 在系统设置中启用 DSH Agent 的无障碍服务。
4. 如需要通知列表，再启用通知使用权。
5. 不要给这个 App root；它只通过 Accessibility/PackageManager 提供固定能力。

## 协议

客户端连接 `127.0.0.1:17321`，发送一行 JSON：

```json
{"token":"<token file content>","id":1,"method":"ui.dump","params":{}}
```

响应：

```json
{"ok":true,"result":{"available":true,"package":"...","nodes":[]}}
```

方法白名单：

- `ping`
- `ui.dump`, `ui.click`, `ui.setText`, `ui.swipe`
- `notifications.list`, `notifications.dismiss`
- `clipboard.get`, `clipboard.set`
- `package.list`
- `activity.start`
- `activity.forceStop`, `package.clear`, `package.install` 目前明确返回 `supported:false`

`activity.start` 支持 `package` + `activity`，或 `action`。
`forceStop`/`clear`/`install` 需要系统权限或 pm session，不用 App 伪造。

## 安全边界

- socket 只绑定 `127.0.0.1`，token 在 App 私有目录，仅 root 可读；
- 不暴露任意 shell，不执行传入命令；
- 不是隐藏性组件；包名 `dev.dsh.agent` 对 App 可见；
- 未在真机验证；构建通过只代表 APK 结构/签名通过。
