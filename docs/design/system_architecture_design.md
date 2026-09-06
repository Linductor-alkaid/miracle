# Miracle 总体架构设计

> 状态：Proposed（P3 实现同步修订）
> 版本：1.1
> 更新日期：2026-09-06
> 上位文档：[可行性分析与方案](../feasibility-and-solution.md)、[工程规范](../project/project-standards.md)
> 关联决策：[DEC-001 前端形态](../decisions/DEC-001-frontend-compose.md)、
> [DEC-002 工具集路线](../decisions/DEC-002-agent-tool-set-route.md)、
> [DEC-003 构建与设备基线](../decisions/DEC-003-build-device-baseline.md)

## 1. 职责与边界

本文定义 Miracle 的模块划分、层间契约、线程/生命周期模型与数据流。各专题设计见：

- [Agent 工具集设计](agent_tool_set_design.md)：宿主能力 → ABI → mira 决策协议 → 扩展层。
- [前端设计](frontend_design.md)：Compose 选型、React 概念映射、页面与悬浮球。
- [构建打包与分发设计](build_packaging_design.md)：Gradle/NDK/mira 消费、APK、真机基线。

分层总图：

```
┌─ UI 层（Kotlin + Compose）───────────────────────────────────┐
│ MainActivity（主 GUI）          FloatingBall/Panel（悬浮窗）   │
│          │ 只经 AgentRuntime 门面（StateFlow/事件流）          │
├──────────▼───────────────────────────────────────────────────┤
│ 门面层 AgentRuntime（Kotlin）                                  │
│  会话生命周期 · 状态投影 · 同意门面（SessionGate）· 确认对话框    │
├──────────────────────────────────────────────────────────────┤
│ 宿主服务层（Kotlin，前台服务承载）                               │
│  ScreenCaptureProvider（MediaProjection/ImageReader）          │
│  InputDispatcher（AccessibilityService.dispatchGesture/文本）  │
│  CapabilityRegistry（能力快照/epoch/权限状态） · AppMonitor     │
├──────────────────────────────────────────────────────────────┤
│ JNI Bridge 层（C++20，libmiracle_host.so 自研部分）             │
│  host_abi_impl：实现 mira Host ABI v1 导出符号                  │
│  runtime_glue：Executor 唯一 owner · mira 运行时组装 · 观察者   │
├──────────────────────────────────────────────────────────────┤
│ mira（安装包消费，钉死 commit）                                  │
│  AndroidHostAdapter → AgentLoop → ModelGateway → VLM(HTTPS)   │
└──────────────────────────────────────────────────────────────┘
```

依赖方向单向向下；UI 层不得 import JNI/ABI/mira 类型，宿主服务层不得 import UI 类型。

## 2. 模块清单与职责

| 模块 | 语言 | 职责 | 关键接口（对上层） |
| --- | --- | --- | --- |
| `app.ui` | Kotlin | 主 GUI：引导、任务台、会话详情、设置 | Compose 组件树，仅消费 `AgentRuntime` 状态 |
| `app.overlay` | Kotlin | 悬浮球与展开面板、活动指示 | `OverlayController`（show/hide/状态更新） |
| `runtime.AgentRuntime` | Kotlin | 门面：会话启动/停止/takeover、状态流、事件流、确认请求流 | `suspend fun startSession(goal)`、`StateFlow<SessionState>`、`SharedFlow<SessionEvent>`、`SharedFlow<ConfirmationRequest>` |
| `consent.SessionGate` | Kotlin | 同意门面：披露确认、能力授权状态、会话准入、活动指示控制 | `fun canStartSession(): GateStatus` |
| `host.ScreenCaptureProvider` | Kotlin | MediaProjection 授权、VirtualDisplay+ImageReader、帧租约（RGBA） | `fun requestFrame(deadline): FrameLease` |
| `host.InputDispatcher` | Kotlin | 手势合成与 dispatchGesture、焦点文本注入、取消（原子语义：未提交 CANCELLED／已提交 EXECUTION_UNCERTAIN+side=1）与 RELEASE_ALL | `fun dispatch(events, deadline): Receipt` |
| `host.CapabilityRegistry` | Kotlin | 能力快照、epoch 维护（旋转/权限/会话变化递增）、权限自检 | `fun snapshot(): HostCapabilities` |
| `bridge.host_abi_impl` | C++ | 实现 `mira_android_host_*` 全部符号；操作注册表（correlation→pending）；lease 生命周期 | （被 mira adapter 调用，无上层接口） |
| `bridge.runtime_glue` | C++ | Executor 初始化/关闭（唯一 owner）、自检入口封送、JNI 注册 | JNI 导出（P3 实际面）：`loopOpen/loopSubmit/loopCancel/loopTakeover/loopClose/loopState`、`modelConnectivityTest`、`consentResolve`、`nativeHttpExchangeComplete` 等 |
| `bridge.loop_runtime` | C++ | P3 新增：AgentLoop 组装（gateway/provider/admission/事件存储/verifier）、宿主 `IHttpTransport`（Kotlin HTTPS 执行 + C++ 协作等待）、脚本化干跑传输、R3 确认协议（`ConfirmationAuthority`） | 被 runtime_glue 调用；host_abi_impl 经其查询会话活跃/签发挑战 |
| `mira`（上游） | C++ | Observe→Reason→Plan→Act→Verify 闭环、模型网关、持久状态 | `find_package(Mira)` 公共 API |

## 3. 一次任务闭环的数据流

1. **会话建立**：UI 提交目标 → `SessionGate` 校验披露与能力授权（无障碍已启用、投影已授
   权、悬浮窗已授权、通知已授权；缺项则引导补齐）→ 常驻通知开启、活动指示置位 →
   `AgentRuntime.startSession`。
2. **Observe**：mira AgentLoop 调 `AndroidHostAdapter.observe()` → ABI `capture_frame` →
   bridge 操作注册表登记 correlation → JNI 调 Kotlin `ScreenCaptureProvider.requestFrame`
   → ImageReader 取帧，直接 ByteBuffer 包装为 `MiraHostBufferLeaseV1`（release=关闭
   Image）→ 回调 `on_operation_complete`（bridge 有界拷贝后经 Executor 结算）→ mira 得
   到归一化坐标的 screen Observation。
3. **Reason/Plan**：`ModelGateway.infer()` 组装请求（截图 + 目标 + 历史动作 + decision
   schema）经 mbedtls 通道发往所配置的 OpenAI 兼容 VLM；返回经解析校验为结构化
   Decision。
4. **Act**：`compile_discrete_action` 产出 `InputSequence`（[0,1] 坐标）→ ABI
   `dispatch_input` → Kotlin `InputDispatcher` 映射为 `GestureDescription`（tap/长按/
   滑动路径）或无障碍文本动作（type）→ 回执（Dispatched/Completed/Rejected/Unknown +
   side_effect 标志）回填 mira。高风险决策先经 `SessionGate` 确认门面（见工具集设计
   §4），未确认动作按 Rejected 结算。
5. **Verify**：重新观察，`ILoopVerifier` 判定；循环直至 Completed/Failed/MaxSteps/取消。
6. **收尾**：终态事件 → 通知与悬浮球归位 → 会话事件归档（mira EventStore/SQLite）→
   UI 时间线可回放（Replay，只读）。

## 4. 线程与生命周期模型

| 执行上下文 | 归属 | 规则 |
| --- | --- | --- |
| Android 主线程 | UI/无障碍回调 | 无障碍回调内只投递不阻塞；手势合成在协程中完成后 dispatchGesture 可在主线程提交 |
| ImageReader handler 线程 | ScreenCaptureProvider | 帧到达只做租约封装与完成投递；拷贝发生在 bridge 回调内的有界复制 |
| mira Executor workers | bridge 持有 | 模型调用、结算、闭环推进全部在 Executor 内；bridge 自研代码不创建线程 |
| JNI 回调线程 | bridge → Kotlin | 缓存 `JavaVM`，`AttachCurrentThread` 按需；局部引用及时清理；异常不得穿越 JNI |
| Kotlin 协程 | `AgentRuntime`/Provider | 结构化作用域（service scope）；与 native 交互经 bridge 入口 |

**生命周期映射**：

- `AgentForegroundService.onCreate` → 初始化 bridge（Executor initialize → 组装 mira
  运行时）→ 能力快照上报。
- 任务提交/取消/takeover → `AgentRuntime` → bridge 入口；takeover 语义：阻断新决策、取消
  在途输入、`RELEASE_ALL`、恢复前强制重新观察。
- `onDestroy`（或受控关停）→ mira §17.2 关闭顺序：停止生产者 → 取消 → 排空 → 非 worker
  线程 `shutdown(true)` → 宿主 stop/destroy（lease 归零断言）。
- 进程被杀恢复（P4）：`state_store` 检查点在 `filesDir` 持久化，重启后提示用户可恢复。

## 5. 状态投影

mira 状态机是唯一事实源；`AgentRuntime` 将运行时事件投影为 Kotlin
`SessionState`（sealed class：Idle/Running(phase,steps,takeover)/Terminal(outcome)）与
时间线事件。UI 与悬浮球只读该投影；投影层不做业务决策。

P3 粒度注记：mira `AgentLoop` 公共 API 无逐步观察者（仅终态 `AgentLoopResult`），故
相位为宿主真实信号驱动的**粗投影**（capture 受理＝Observing、transport 执行＝
Reasoning、input 受理＝Acting；终态与步进记录经结果 JSON）。逐相位/逐步实时投影以
mira 提供观察者回调为前置（观察项，随上游反馈评估）；悬浮球呼吸＝活动指示。

## 6. 数据与凭据

| 数据 | 位置 | 处理规则 |
| --- | --- | --- |
| 模型 API key | Android Keystore（AES-256-GCM）加密密文，存 filesDir；其余配置存 SharedPreferences（P3 注记：字段量小，v1 未引入 DataStore 依赖） | 不入日志/事件；bridge 收到的是内存中的传输凭据 |
| mira 持久状态（checkpoint/memory/事件） | `filesDir/mira/*.db`（SQLite WAL） | 随应用数据卸载删除；升级保留 |
| 截图 | 内存租约 + mira ArtifactStore（有界） | 出设备前经同意；日志仅摘要引用；崩溃报告不携带 |
| 应用配置 | DataStore | 端点、预算、步数上限、风险策略开关 |
| 会话回放 | 只读 Replay | 不重新执行输入与网络请求（mira 语义默认保持） |

## 7. 权限与降级矩阵

| 能力 | 所需授权 | 缺失时行为 |
| --- | --- | --- |
| 截屏观察 | MediaProjection 会话同意 + FGS(MEDIA_PROJECTION) | `PermissionDenied`，会话拒绝启动；UI 引导 |
| 触控/文本注入 | 无障碍服务启用（用户系统设置） | 同上；运行中被系统关闭 → epoch 失效，任务转 Recovering→Failed |
| 悬浮球 | SYSTEM_ALERT_WINDOW 特殊授权 | 主 GUI 仍可用；提示功能受限 |
| 常驻通知 | POST_NOTIFICATIONS + FGS | 授权流程内前置；拒绝则不允许启动会话（告知依赖通知） |
| FLAG_SECURE 页面 | —（系统行为） | 黑帧/受限事件 → Verify 检测后按 InvalidObservation 处理，不静默重试 |

## 8. 错误语义约定

Kotlin 侧异常与平台错误映射到 `MiraHostStatus`（fail-closed）：权限缺失→
`MIRA_HOST_ERR_PERMISSION_DENIED`；能力未就绪→`UNAVAILABLE`；请求参数非法→
`INVALID_ARGUMENT`；取消→`CANCELLED`（不可中断输入→`EXECUTION_UNCERTAIN` +
`side_effect_may_have_occurred=1`）；deadline→`DEADLINE_EXCEEDED`。反向，mira 的
`ExecutionStatus.Unknown` 在 UI 呈现为"动作结果未知"，触发保守恢复路径。

## 9. 非目标（本设计明确不做）

多进程/远程服务；多显示与折叠态适配；连续控制（M6 已取消）；本地感知模型（M5 已取消）；
闭环内自定义工具执行（P3+ 评估，见工具集设计 §6）；Play 商店上架流程。
