> **ARCHIVED 2026-10-26 — historical only; not a current design or decision. See `docs/README.md` and `docs/design-v1.5.md`.**

# 分析：容器AI 0.1.0 APK（com.containerai.console）

- 来源：`/storage/emulated/0/Download/容器AI_0.1.0.apk`（60MB；2026-10-26 分析）
- 许可：APK 内未发现 LICENSE/版权声明；默认**专有/不可复用**
- 一句话评价：**它是“免 root、proot Alpine + 本地/云端模型 + 原生 Compose 控制台”的消费级形态；UX 值得参考，但运行模型是 B 类 proot，不是我们的真 chroot 路线。**

---

## 1. 问题与约束

- 目标用户：没有 root 的 Android 用户；想在一个 App 里同时得到“Linux 容器 + AI 对话 + 终端 + 资源监控”。
- 约束：不能 chroot；只能 proot；不能开机自启；不能访问 `/data`；只能在自己的 app 沙箱/共享存储里工作。
- App 事实（`aapt2`/`apktool` 实测）：
  - package `com.containerai.console`，versionName `0.1.0`，versionCode `1`
  - minSdk 33 / targetSdk 35 / compileSdk 35
  - 权限只有 `INTERNET`（+ 自带 dynamic receiver 权限）
  - `debuggable=true`、`allowBackup=true`、`extractNativeLibs=true`
  - 60MB；Compose UI；`libproot.so`、`libproot_loader.so`、`libandroid-shmem.so`、`libtalloc.so`
  - `assets/rootfs/alpine-rootfs.bin`：gzip 压缩的 Alpine rootfs，3.9MB 压缩 / 9.1MB 解压
- 代码结构（DEX 类名）：
  - `core/ai/LlmApiClient`、`LocalModelStore`、`LocalServiceManager`、`ModelStore`
  - `data/model/AiModel`、`AiProvider`、`AiProviderKind`、`KeyObfuscator`、`LocalEngine`、`LocalModel`、`LocalServiceStatus`
  - `core/container/ContainerStore`；`core/monitor/SystemMonitor`、`ContainerProcessProbe`
  - `ui/chat/ChatScreen`、`ChatViewModel`（含 `buildTools`、`runToolLoop`）、`ui/ai/AiModelsScreen`
- 结论：**它确实是一个“容器 + AI”App**：本地 GGUF/云端 provider + 工具循环 + proot Alpine 控制台；不是简单的终端壳。

## 2. 运行模型

- **容器引擎**：proot（`libproot.so` + loader），不是 chroot；依赖 ptrace 做路径/UID 翻译。
- **rootfs**：Alpine，打包在 APK assets；解压到 app 私有目录；没有 root、没有 `/data`、没有系统分区。
- **UI**：Jetpack Compose；Dashboard（资源监控）、Chat（AI 对话/工具循环）、Models（本地/云端模型管理）、Container/Console、Settings。
- **AI**：
  - `LlmApiClient`：云端 provider（OpenAI 兼容等，具体 provider 未逐项验证）；
  - `LocalModelStore` + `LocalServiceManager` + `LocalEngine`：本地模型/本地推理服务管理（GGUF 相关类名）；
  - `ChatViewModel.buildTools`/`runToolLoop`：工具调用循环；工具可能包括 shell/文件/容器操作（类名可见 Chat/ToolLoop，具体工具集未逐项验证）。
- **权限**：app 沙箱；没有 `MANAGE_EXTERNAL_STORAGE`/`READ/WRITE_EXTERNAL_STORAGE`；工作区大概率在 app 私有目录或通过 SAF；不能直接写 `/sdcard` 全盘。
- **生命周期**：随 App 进程；没有 root 守护进程/开机自启；App 被杀/系统回收，容器停止。

## 3. 生命周期

- 安装：APK 自带 Alpine rootfs；首次启动解压；proot 启动。
- 运行：App 前台/后台服务；容器进程由 App 管理；没有 s6/系统级监督。
- 停止：App 退出/杀进程；proot 子进程被回收；没有宿主级 mount 清理（proot 不需要）。
- 更新：APK 升级；rootfs 可能随 assets 替换；没有用户数据快照/回滚（至少从 APK 结构看不出）。
- 数据：app 私有目录；`allowBackup=true` 可能把数据进 Android 备份；`debuggable=true` 是测试构建。

## 4. 数据与更新

- rootfs：assets 内；解压到 app 私有目录；升级 APK 可能覆盖/保留。
- 模型：`ModelStore`/`LocalModelStore`；本地模型文件可能在 app 私有目录；云端 provider key 用 `KeyObfuscator`（混淆不是加密）。
- 会话：Chat/ViewModel；没有看到跨设备同步。
- 备份：依赖 Android backup/allowBackup；没有 rootfs 快照/回收站。
- 更新：APK 渠道；没有模块化 payload/回滚。

## 5. 为什么这样写（证据）

1. **免 root 是最大约束**：只有 `INTERNET` 权限、没有存储权限、proot 原生库；说明作者选择“app 沙箱 + proot”换取可安装性。
2. **单 APK 一体化**：Alpine rootfs 3.9MB 打进 assets；用户安装即用；这是消费级产品的正确取舍。
3. **原生 Compose UI**：Dashboard/Chat/Models/Console；比 Web UI 更适合手机；`SystemMonitor`、`ContainerProcessProbe` 提供资源/进程可视化。
4. **本地+云端模型**：`LocalModelStore`/`LocalServiceManager`/`LlmApiClient` 同时支持本地 GGUF 与云端，降低“必须联网/必须有 API key”的门槛。
5. **工具循环**：`ChatViewModel.buildTools`/`runToolLoop` 说明模型可以调用工具；但具体权限/确认机制未验证。
6. **Alpine**：体积小、启动快；但 musl 对 Node 原生模块（node-pty/sharp/koffi）不友好——这正是我们选 Ubuntu glibc 的理由。

## 6. 更优雅的做法（带代价）

| # | 现状 | 更优雅 | 代价/风险 |
|---|---|---|---|
| 1 | proot + Alpine | 真 chroot + Ubuntu glibc（我们的路线） | 需要 root；但原生 syscall、Go 编译、node-pty 预编译都更好 |
| 2 | 单 APK 内置 rootfs | 模块 + payload 分离（我们的路线） | 安装多一步；但 payload 可独立更新/校验/回滚 |
| 3 | app 沙箱，无 `/sdcard` 全盘 | 真 chroot + raw `/data/media/0` 工作区 | 需要 root；但文件管理器可见、可执行 |
| 4 | App 进程生命周期 | KSU service.sh + s6 开机自启 | 需要模块；但可常驻/可诊断 |
| 5 | 无快照/回滚 | rootfs/state 冷快照 + restic | 需要空间/停机；但可逆 |
| 6 | 本地模型/云端 provider UI | 保留为**可选 native client**，连 DSH web API | 需要开发客户端；但不用把 proot/模型塞进 chroot |
| 7 | `debuggable=true`、`allowBackup=true` | release 构建关闭 debug/备份；密钥用 Keystore | 发布前必须改；否则凭据/数据风险 |

## 7. 对我们的启示

**值得借鉴**
- **原生移动端 UX**：Dashboard（CPU/内存/磁盘）、Chat、Models、Console、Settings 的信息架构；如果以后做手机客户端，可参考。
- **模型管理**：本地 GGUF + 云端 provider 的统一模型列表/测试/选择；比只支持一个 API 更友好。
- **工具循环 + 状态卡片**：Chat 里显示工具调用/结果/资源状态；DSH web 已有类似能力，可对齐。
- **单 APK 安装体验**：降低门槛；但我们的模块+payload 也可以做一键安装脚本/管理器入口。

**不要照搬**
- proot/Alpine：与 H1（真 chroot）、H2（raw media 可执行）、H3（开机自启）冲突；node-pty/sharp 的 musl 坑我们已经踩过。
- App 沙箱：无法访问 `/data`、无法系统级设备控制；不是“全能”。
- 无快照/回滚；无可诊断的宿主级生命周期。
- 专有 APK、`debuggable`、`allowBackup`：不能作为代码/安全参考。

**结论**：把它当**产品/UX 参考**，不是架构参考；如果要做手机端，做**DSH 的 thin client**（WebView/HTTP/WS），不要把 proot 和本地模型再塞回 chroot。
