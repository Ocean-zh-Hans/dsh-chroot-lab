# Host thin agent（P4，未实现）

目的：补 chroot 内做不到的手机 UI 能力，而不是替代 chroot。

- UI 树/点击/文本：Android AccessibilityService。
- 通知：NotificationListenerService。
- 剪贴板：前台/受系统限制的 API。
- 包管理/Activity：`PackageManager`、`am` 等价固定命令。
- 连接：`127.0.0.1:17321` + token 文件（App 私有目录，root 可读）；chroot 内通过固定
  JSON 命令集调用；**不暴露任意 shell**。

协议草案：

```json
{"id":"1","method":"ui.dump","params":{}}
{"id":"1","ok":true,"result":{"nodes":[]}}
```

方法白名单：

`ui.dump`, `ui.click`, `ui.setText`, `ui.swipe`,
`notifications.list`, `notifications.dismiss`,
`clipboard.get`, `clipboard.set`,
`package.list`, `package.install`, `package.clear`,
`activity.start`, `activity.forceStop`.

当前状态：**未实现**。chroot 内仍可用 `/system/bin/am`、`pm`、`input`、
`screencap` 等；但通用 UI 树必须等这个 agent。没有它之前，文档不会写
“完美手机控制”。
