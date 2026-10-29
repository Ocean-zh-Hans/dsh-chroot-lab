# Java → Kotlin 全量迁移记录

> 状态：代码层完成（2026-10-29）；全部 Java 源文件已清零，单元测试与四个构建目标通过。
> 迁移原则：**协议冻结、类名保留、行为不变、只修迁移暴露的真 bug**；不做顺手重构，不改变 socket/token/幂等/事件语义。
> 分支：feat/session-core。

## 1. 结果摘要

| 指标 | 迁移前 | 迁移后 |
|---|---|---|
| Java 源文件（不含 build） | 25 个 / 4,535 行 | 0 |
| Kotlin 源文件（含测试） | 2,857 行 | 6,280 行 |
| agent-core 单测 | 20 个 | 20 个全绿 |
| agent-app 单测 | 旧测试已失效 | 5 个全绿 |
| console-app 单测 | 旧测试已失效 | 3 个全绿 |
| agent-app APK | 23,441,487 B（迁移前） | 约 23MB，assembleDebug 通过 |
| console-app APK | 未构建 | 约 22MB，assembleDebug 通过 |
| root helper dex jar | 723,353 B | 707KB，dexJar 通过 |

验证命令（固定环境）：

~~~bash
cd /root/workspace/projects/dsh-chroot-lab
export ANDROID_HOME=/opt/android-sdk HOME=/root
export JAVA_HOME=$(dirname $(dirname $(readlink -f /usr/bin/javac)))
./gradlew :agent-core:test :agent-app:testDebugUnitTest :console-app:testDebugUnitTest --console=plain
./gradlew :agent-app:assembleDebug :console-app:assembleDebug :agent-root-helper:dexJar --console=plain
~~~

## 2. 迁移清单

### 2.1 agent-app（20 个 Java 类，2,958 行）

| 原文件 | 行数 | 新文件 | 说明 |
|---|---:|---|---|
| AgentServer.java | 806 | AgentServer.kt | socket/JSON Lines/token/幂等/事件/会话/策略；v3 分发；最大单类 |
| SystemBridge.java | 467 | SystemBridge.kt | 系统信息/设置/应用/电源/锁/IME/音量/DND/网络/传感器/位置/任务；保留只读 status 方向 |
| Jobs.java | 229 | Jobs.kt | 后台任务/调度/持久化；当前不接线 |
| FileBridge.java | 228 | FileBridge.kt | fs.put/get/list/mkdir/rm/share；当前不接线 |
| EventBus.java | 220 | EventBus.kt | 1000 环形缓冲/8 订阅/256 队列/回放/resetRequired |
| RootClient.java | 194 | RootClient.kt | 17322 客户端/超时/可用性探测/事件 socket |
| MediaBridge.java | 190 | MediaBridge.kt | 通知/剪贴板/媒体/电话/SMS/录屏；当前不接线 |
| SystemEvents.java | 94 | SystemEvents.kt | 屏幕/电池/网络/剪贴板广播 |
| AgentFileProvider.java | 82 | AgentFileProvider.kt | content:// 只读文件共享 |
| Cron.java | 81 | Cron.kt | 5 字段 cron；修复 split limit 运行时 bug |
| NotificationRelay.java | 75 | NotificationRelay.kt | NotificationListenerService |
| Diagnostics.java | 53 | Diagnostics.kt | 512 环形诊断/冷却 |
| SafePaths.java | 49 | SafePaths.kt | 路径逃逸校验/删树/列表 |
| AgentRuntimeService.java | 47 | AgentRuntimeService.kt | 前台服务/自启开关 |
| AgentError.java | 39 | AgentError.kt | 错误码/确认门 |
| AgentService.java | 38 | AgentService.kt | AccessibilityService 生命周期 |
| AgentMethods.java | 21 | AgentMethods.kt | API_VERSION/VERSION/WRITES |
| BootReceiver.java | 17 | BootReceiver.kt | 开机自启 |
| EngineRouter.java | 17 | EngineRouter.kt | EngineRouter 到 UiEngine 的路由 |
| AgentApplication.java | 11 | AgentApplication.kt | Application 启动 AgentServer |

### 2.2 agent-root-helper（1 个 Java 类，1,235 行）

| 原文件 | 行数 | 新文件 | 说明 |
|---|---:|---|---|
| RootHelper.java | 1,235 | RootHelper.kt（1,428 行） | uid=0 的 17322 服务；UiAutomation/手势/文本动作/截图/shell/录屏/content query |

迁移时把源文件从 src/main/java 移到 src/main/kotlin；类名、包名、方法名、协议不变。Kotlin 显式空值/类型转换使行数增加约 15%。

### 2.3 console-app 与测试

| 原文件 | 行数 | 新文件 | 说明 |
|---|---:|---|---|
| ConsoleSupport.java | 118 | ConsoleSupport.kt | object；URL/状态/脱敏/命令执行 |
| tests/AgentCoreTest.java | 81 | 删除 | 引用已删除的 UiSelector，旧方法表过时 |
| tests/ConsoleSupportTest.java | 43 | console-app/test-src/.../ConsoleSupportTest.kt | 3 个用例 |
| tests/android-probe/.../ProbeInstrumentation.java | 100 | ProbeInstrumentation.kt | 真机探测用；未接 Gradle |

新增 agent-app/test-src/dev/dsh/agent/CoreBehaviorTest.kt：5 个用例（cron、SafePaths、AgentMethods 写集合）。

### 2.4 未迁移/不适用

- build/ 产物、Gradle wrapper、keystore：不是源代码，不迁移。
- .github/ 无 Java 工作流；build/check.sh 已改为 Kotlin 测试入口。
- 仓库内已无 .java；后续新增代码只允许 .kt。

## 3. 构建配置改动

### 3.1 根工程

- 根 build.gradle.kts 已声明 kotlin("jvm") 2.0.21 与 kotlin("plugin.serialization") 2.0.21（apply false）。
- settings.gradle.kts 包含 :agent-core、:agent-app、:agent-root-helper、:console-app。
- 依赖校验：gradle/verification-metadata.xml 已更新；新增依赖必须带 --write-verification-metadata sha256 跑一次。
- 依赖锁定：agent-app/gradle.lockfile、console-app/gradle.lockfile 已更新。

### 3.2 agent-root-helper

- plugins 增加 kotlin("jvm")。
- java sourceCompatibility/targetCompatibility = JavaVersion.VERSION_1_8。
- kotlin compilerOptions.jvmTarget = JVM_1_8。
- dependencies 增加 implementation(kotlin("stdlib"))。
- dexJar 任务：
  - inputs 增加 configurations.runtimeClasspath；
  - D8 参数 = jar 产物 + runtimeClasspath 全部 .jar（含 kotlin-stdlib）+ --lib androidJar --min-api 29；
  - 输出 build/dsh-root-helper.jar，内含唯一 classes.dex。
- 关键点：只喂项目 jar 会缺 kotlin 运行时，D8 必须带 stdlib；单 dex 方法数当前足够。

### 3.3 agent-app / console-app 测试

- sourceSets 增加 getByName("test") { java.srcDirs("test-src") }（相对项目根）。
- dependencies 增加 testImplementation("junit:junit:4.13.2")。
- 关键坑：主 sourceSet 是 java.srcDirs(项目根/src)，会递归包含 src/test；测试必须放项目根的 test-src，否则会被 compileDebugKotlin 当主代码编译，且看不到 testImplementation。

### 3.4 build/check.sh

原 == Java behavior == 段用 javac 编译 console-app/src、agent-app/src、agent-root-helper/src 全部 .java，并引用已删除的 UiSelector.java；已替换为 == Kotlin behavior ==：

~~~bash
_sdk="$ANDROID_HOME"
[ -n "$_sdk" ] || _sdk="$ANDROID_SDK_ROOT"
[ -n "$_sdk" ] || _sdk="$HOME/android-sdk"
export ANDROID_HOME="$_sdk" ANDROID_SDK_ROOT="$_sdk"
./gradlew :agent-core:test :agent-app:testDebugUnitTest :console-app:testDebugUnitTest --console=plain
~~~

其余 shell/模块/manifest/python 检查未改；2026-10-29 全量 build/check.sh 已通过（all host checks passed）。

## 4. 迁移中修掉的真问题

| 问题 | 位置 | 处理 |
|---|---|---|
| Kotlin split 不接受 limit=-1 | Cron.kt parseField | 去掉三处 limit=-1；否则 cron 解析运行时直接 IllegalArgumentException |
| Java Iterator 与 Kotlin MutableIterator 类型不匹配 | RootClient.kt:128 | 删除显式 Iterator 类型，改用 val iterator = detail.keys() |
| EventBus 被声明 internal，public AgentServer.events 暴露内部类型 | EventBus.kt | 改为 public class EventBus |
| Kotlin Set 与 java.util.Set 同名导入冲突 | EventBus.kt | 删除 java.util.Set import，QueueSubscription 用 Kotlin Set |
| MotionEvent.obtain 可空 | RootHelper.kt 手势 | 加 ?: throw Failure("INTERNAL", ...) |
| val 在 try/catch 两分支赋值 | RootHelper.kt screenshot | 改 var bytes/via |
| fling duration 类型不匹配 | RootHelper.kt | 改为 maxOf(50L, minOf(1500.0, ...).toLong()) |
| 源码字符串引号写坏导致大量假 Unresolved reference | RootHelper.kt:1146 | 修正为 expected.startsWith(双引号) |

这些修改不改变协议；Cron 是唯一影响运行时的行为修复。

## 5. 兼容性说明

- v3 是断代 API，不与旧 69 方法兼容；迁移目标是“同一 v3 行为”，不是旧 APK 行为。
- 类名、包名、Manifest 组件名、17321/17322 端口、token 文件位置、JSON Lines 帧、幂等语义均未改。
- 旧 APK 的 token 与数据保留策略不变；换签名安装时必须 pm uninstall -k 再装，数据目录保留。
- 迁移后未做真机验收；已知 UI 侧仍待修：Capture.pkg 取第一个非空 root、activity 可能为空、ui.batch 无目标 press 已修但需回归。

## 6. 验收状态

| 项 | 状态 | 证据 |
|---|---|---|
| 无 .java 源文件 | ✅ | find 结果为 0 |
| agent-core 20 单测 | ✅ | :agent-core:test |
| agent-app 5 单测 | ✅ | :agent-app:testDebugUnitTest |
| console-app 3 单测 | ✅ | :console-app:testDebugUnitTest |
| agent-app APK | ✅ | :agent-app:assembleDebug |
| console-app APK | ✅ | :console-app:assembleDebug |
| root helper dex | ✅ | :agent-root-helper:dexJar |
| build/check.sh 全量 | ✅ | build/check.sh 全量通过（all host checks passed） |
| 真机 v3 验收 | ⬜ | 等用户时间窗 |
| 代码提交 | ✅ | 本次 feat/session-core 提交 |

## 7. 后续规则

1. 新代码只用 Kotlin；需要 Java 互操作时用 @JvmStatic/@JvmField，不引入新 .java。
2. 保持 org.json 对象模型；暂不引入 kotlinx.serialization 到 Android 层（agent-core 已用）。
3. 静态入口用 companion object + @JvmStatic，保持 Manifest/反射/脚本按类名调用不变。
4. 任何构建新增依赖，同时更新 verification-metadata 与 lockfile；否则 CI 会因校验失败。
5. agent-root-helper 任何新依赖都要检查 D8 输入与 65K 方法数。
