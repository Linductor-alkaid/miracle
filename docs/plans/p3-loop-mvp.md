# P3：闭环 MVP

> 状态：In Progress
> 负责人：Miracle Maintainers
> 所属计划：[Miracle 实施总计划](miracle-implementation-plan.md)
> 前置：P2（Completed，2026-09-06）
> 建议发布点：`v0.3.0`（本里程碑通过后打 tag）
> 更新日期：2026-09-06

## 目标

在 P1（截屏）+ P2（输入）链路之上组装 mira `AgentLoop` 闭环运行时，并交付双前端全功能
与同意/告知体系：模型配置（端点/密钥/方言/步数）经设置页管理（密钥 Android Keystore
加密）；任务台提交目标 → `Observe → Reason → Plan → Act → Verify` 循环；R3 动作级确认
（DEC-004，mira `ConfirmationAuthority` 为协议权威）；Human Takeover（悬浮球长按/通知/
任务台，取消在途 + `RELEASE_ALL` + 阻断新动作）；悬浮球（状态环/拖动/单击展开面板/长按
接管）；常驻通知文案随状态联动。

同时交付两级自检：**模型连通性自检**（真实端点，文本-only 决策请求，验证
transport→provider→gateway→decision 解析全链）与**闭环干跑**（脚本化决策 transport，
真实环境 observe/act，验证 loop 全语义：完成/MaxSteps/取消/Takeover）。

**真机端到端真实任务（≥3 类）受上游缺口阻断**（MIR-20260906-007，见"风险与阻塞"），
代码路径完整交付，验证按补跑条件登记。

## 范围与非目标

范围：mira `AgentLoop` 组装（Executor/AndroidHostAdapter/ModelGateway/
OpenAiCompatibleProvider/admission/verifier/MemoryEventStore）；宿主 `IHttpTransport`
实现（公共接口，Kotlin `HttpURLConnection` 执行 HTTPS）；`ISecretResolver`（密钥内存
解析，不入日志/事件）；`SessionGate` 会话准入（披露确认 + 无障碍/投影/悬浮窗/通知四项
授权 + 模型配置完整性）；R3 策略表（默认从严）与确认对话框（digest+nonce 绑定、单次
有效、60s 到期）；takeover 全路径；任务台/设置页/引导页与确认对话框宿主；悬浮球 + 展开
面板；FGS 通知 action 与状态文案；P3 自检卡（连通性 + 干跑）；单测与门禁。

非目标：真实 VLM 闭环任务的真机验收（上游阻断，补跑条件见退出条件）；暂停/继续
（`AgentLoop` 公共 API 无 pause，面板提供"停止/Takeover"，暂停登记为已知限制）；
UI 树观察（MIR-001 不变）；L3 扩展工具（POST-01）；事件持久化与 replay 检视（P4）；
屏幕边缘呼吸描边（可选项，v1 以悬浮球状态环 + 通知文案为活动指示）；
`SYSTEM_ALERT_WINDOW` 常驻悬浮球（v1 球随 FGS 会话生命周期显示，常驻全局入口为后续项）。

## 设计与决策依据

- [总体架构设计 §2/§3/§4/§5/§7/§8](../design/system_architecture_design.md)（门面/
  AgentRuntime、数据流、线程模型、状态投影、权限降级矩阵、错误语义）
- [前端设计 §4/§5/§6](../design/frontend_design.md)（信息架构、悬浮球双形态、无障碍基线）
- [工具集设计 §2/§4](../design/agent_tool_set_design.md)（Provider 形态、R1/R2/R3 风险
  分级与确认策略、会话级同意不覆盖 R3）
- mira 冻结公共 API：`agent_loop.hpp`（LoopOutcome/StepPhase/ILoopVerifier/
  compile_discrete_action）、`model_gateway.hpp`（SimpleAdmissionGate）、
  `model_provider.hpp`（OpenAiCompatibleProvider）、`model_transport.hpp`
  （IHttpTransport/ISecretResolver——宿主实现传输的公共扩展点）、`security.hpp`
  （ConfirmationAuthority/ProposedEffect/PolicyDecision）、`event_store.hpp`
  （MemoryEventStore）
- mira `tests/m3/m3_agent_loop_test.cpp` 与 `tests/support/m3_support.hpp`（官方组装
  范式：profile/router/gateway/admission/verifier 与能力位声明）
- DEC-004（确认协议：宿主是确认权威，模型自述同意不构成授权）
- P1/P2 计划关键实现决策沿用（操作注册表、回调线程纪律、失败可见性、能力位诚实）

## 关键实现决策（本文件冻结，实现遵循）

1. **模型栈组装与传输边界**：mira 安装包未随包导出 `adapters/net` 头文件
   （`SocketHttpTransport`/`MbedTlsChannelFactory` 有库无头，登记
   `MIR-20260906-006`）。P3 经**公共 `IHttpTransport` 接口**实现宿主传输：C++ 侧薄适配
   （请求/响应封送 + 有界协作等待），HTTPS 执行在 Kotlin（`HttpURLConnection`，系统
   信任库，无新增依赖）。方言映射、decision schema 校验、SSE、预算/准入全部保留在 mira
   `OpenAiCompatibleProvider`/`ModelGateway`（不重复实现上游语义）。传输阻塞等待发生在
   loop worker（gateway.infer 同步链）：C++ 条件变量 50ms 轮询 `OperationContext::
   cancelled()` 与 Kotlin 完成标志；取消时通知 Kotlin 侧 `disconnect()`。SSRF 姿态与
   `TransportLimits` 默认一致：仅 https、拒绝私有/回环/链路本地地址、响应字节上限。
2. **闭环图像路径的上游阻断（MIR-20260906-007）**：`AgentLoop::build_request` 将截图
   artifact 以 `media_type="application/octet-stream"` + 原始 RGBA 字节构建 `ImagePart`
   （`agent_loop.cpp`），wire mapper 生成 `data:application/octet-stream;base64,…` 数据
   URL——真实 OpenAI 兼容 VLM 拒绝该格式；且 `AndroidHostAdapter` 工件存储私有、无公共
   读取 API，宿主无法向 provider 提供截图字节。结论：**真实 VLM 闭环在本 lock 上不可
   达**，属公共 API 表面不足，登记台账回流；P3 交付的替代验证：
   (a) 连通性自检（文本-only 请求，无 ImagePart，真实端点全链）；
   (b) 闭环干跑（脚本化 `IHttpTransport` 返回合法 decision JSON，真实 observe/act）。
   真实任务补跑条件：mira 修复 artifact 媒体类型/读取路径 → lock 升级（独立变更，重跑
   P0–P2 验收）→ 用户配置真实端点 → 按 P5 矩阵取证。
3. **并发与生命周期**：`LoopRuntime`（bridge 层）持有唯一 Executor（min/max 4 线程、
   队列 128）与整套 mira 对象；`AgentLoop::run` 经 `submit_cancellable` 提交（StopToken
   → `OperationContext.cancellation_requested`），bridge 自研代码零线程创建。Takeover
   顺序：admission `deactivate` → `request_task_cancel` → `adapter->interrupt()`
   （RELEASE_ALL 语义，P2 已验证）→ 确认挑战全部失效 → 状态置 Takeover。关闭顺序按
   mira §17.2：停止生产者（loop 终止/取消）→ 排空 → executor `shutdown(true)` →
   adapter 销毁。进程内单 LoopRuntime（与自检会话互斥，UI 保证）。
4. **状态投影（诚实粗粒度）**：mira `AgentLoop` 公共 API 无逐步观察者（仅终态
   `AgentLoopResult`）。P3 投影两层：粗相位由宿主真实信号驱动——capture 受理＝
   Observing、transport 执行＝Reasoning、input 受理＝Acting（host_abi_impl/传输层上投，
   仅 loop 会话活跃时）；终态与逐步记录（StepPhase/action_summary/verified）经
   `AgentLoopResult` JSON 投影。UI/悬浮球只读该投影；不伪造细粒度状态机（架构 §5 的
   逐相位投影以 mira 提供观察者为前置，登记观察项）。
5. **R3 确认协议（DEC-004）**：bridge 持有 mira `ConfirmationAuthority`。
   `host_abi_impl.dispatch_input` 受理后、平台派发前询问 Kotlin `SessionGate`：
   allow / require_confirmation / deny。require_confirmation 时：C++ 计算 action digest
   （事件 JSON sha256）→ `ConfirmationAuthority::issue`（nonce、60s 到期、绑定
   task/environment epoch）→ challenge JSON 上投 Kotlin 弹窗；用户响应经
   `nativeResolveConfirmation(challengeId, nonce, approve)` → `consume` 校验（digest/
   nonce/到期/epoch 全匹配才放行）→ 放行派发或结算 `REJECTED(side=0)`。到期/取消/
   takeover 中 → REJECTED。**单次有效**：consume 即失效，重放同一 nonce 拒绝。
   `release_all` 永不确认（安全原语）。会话级同意不覆盖 R3（工具集设计 §4）。
6. **R3 策略表（默认从严，总计划 §6 冻结）**：判定输入＝动作类型 + 前台应用包名
   （P2 AppMonitor 种子）+ 会话目标文本。规则：(a) 目标命中支付/删除/发送/凭据关键词
   （策略表常量）→ 会话内全部输入动作 R3；(b) `type` 动作 + 前台应用在敏感名单（默认
   含常见支付/银行/IM 后缀，可在设置查看）→ R3；(c) 其余 R1/R2 由会话级披露覆盖。
   策略表位于 `consent` 模块，纯函数可单测；变更走决策记录。
7. **凭据与配置**：API key 经 AndroidKeyStore AES-GCM 加密后存 DataStore；native 侧
   `ISecretResolver` 在传输边界按 `SecretRef{"miracle.model.key"}` 解析（内存凭据，
   不入日志/事件/崩溃报告）。端点/模型/方言（Responses/ChatCompletions）/max_steps
   明文存 DataStore（非敏感）。profile 能力位诚实声明（用户配置端点 → text/image_input
   supported=Configured 级证据；strict_json_schema 按 dialect 声明；SSE 关闭——
   非流式）；路由与校验仍由 mira 完成。
8. **会话准入 SessionGate**：披露确认（首启一次性、DataStore 记忆、覆盖"截图将发往
   模型服务 + 触控将被模拟"）→ 四项授权自检（无障碍已连接/投影绑定/悬浮窗可绘制/
   通知已授权）→ 模型配置完整性（端点 https + key 非空 + model 非空）。缺项 fail-closed
   并逐项引导（架构 §7 降级矩阵）；投影未授权时截屏会话拒绝启动。
9. **悬浮球**：`TYPE_APPLICATION_OVERLAY` + 自绘 `View`（状态环：灰 Idle/蓝观察推理/
   橙动作/绿完成/红失败或待确认、高频呼吸＝活动指示）；拖动 + 屏内 clamp；单击展开
   面板（第二个 overlay 窗口承载 `ComposeView`，宿主提供
   ViewTreeLifecycleOwner/SavedStateRegistryOwner，关闭即回收 Composition）；长按
   ≥600ms ＝ Human Takeover（安全操作无需确认）。球随 FGS 会话生命周期显示（v1）；
   悬浮窗未授权时不可用，设置页提供跳转。通知与状态环消费同一 `SessionState` 流。
10. **UI 结构**：单 Activity + 底部三页（任务/自检/设置）+ Onboarding 覆盖层（gate 未
    满足时）+ 全局 ConfirmationHost。v1 以枚举页面切换实现"Compose Navigation"语义
    （前端设计注记：路由库待页面增多再引入，偏差记录于本计划）。任务台含新目标输入、
    当前会话卡（相位/步数/动作计数/停止/接管）、时间线（粗相位事件 + 步进记录）与
    进程内历史（持久化为 P4）。
11. **干跑探针（脚本化 transport）**：C++ 内置 `ScriptedTransport : IHttpTransport`，
    按脚本返回合法 decision wire JSON（tap→tap→done；never-done 用于 MaxSteps；取消
    路径经 UI 触发 takeover/停止）。真机干跑矩阵：Completed/MaxSteps/中途取消→
    Cancelled、确认正负向（type 步骤触发 R3 弹窗）、takeover 后 `RELEASE_ALL`、
    bridge 违规计数为 0、shutdown 干净。脚本化路径与真实路径共用同一 LoopRuntime 组装
    （仅 transport 不同），保证语义同构。
12. **错误语义映射**：模型路径失败如实分类上投（transport 拒绝/非 2xx/schema 违例/
    admission 拒绝/预算），UI 呈现"配置或端点错误"与安全摘要；不静默重试（重试/熔断
    由 mira ModelGateway 内建，次数进结果 JSON）。

## 工作项

- [x] `P3-01` 本计划与设计核对（完成即勾选，含实现决策冻结与上游缺口登记）。
- [x] `P3-02` bridge 闭环运行时：`LoopRuntime`（Executor/adapter/gateway/provider/
  admission/MemoryEventStore/verifier）、`submit_cancellable` 提交、StopToken→
  OperationContext、takeover/cancel/close 顺序、结果与步进 JSON 投影、粗相位上投。
- [x] `P3-03` bridge 模型传输与连通性：C++ `IHttpTransport` 薄适配（封送/有界等待/
  协作取消）+ Kotlin HTTPS 执行（https-only/私有地址拒绝/字节上限/超时）+
  `ISecretResolver`；`modelConnectivityTest` 入口（文本-only 决策请求）。
- [x] `P3-04` bridge R3 确认：`ConfirmationAuthority` 持有、dispatch_input 准入询问、
  challenge 上投/resolve 回流/到期与取消结算、takeover 失效。
- [x] `P3-05` Kotlin 同意层：披露 UI 与记忆、`SessionGate`（授权+配置完整性）、R3
  策略表、确认对话框宿主（digest/nonce/倒计时）、活动指示状态流。
- [x] `P3-06` Kotlin 配置与门面：DataStore + Keystore 加密存储、`AgentRuntime` 门面
  （startSession/cancel/takeover/状态流/事件流）、FGS 通知 action 与状态文案。
- [x] `P3-07` UI：任务台（新目标/会话卡/时间线/历史）、设置页（端点/密钥/模型/方言/
  步数/敏感名单/悬浮窗引导）、Onboarding、P3 自检卡（连通性 + 干跑）。
  补充需求（2026-09-06，真机补跑会话）：设置页内置提供商预设目录
  （`ProviderPresets`：OpenAI/DeepSeek/智谱 GLM/Moonshot Kimi/阿里云百炼
  Qwen/OpenRouter/MiniMax/SiliconFlow——后两家端点/方言/模型名取自 mira
  docs/model_provider 上游在用配置，凭据不入本仓库），FilterChip 一键套用端点/
  前缀/方言/模型建议值，用户仅需填写 API key；mira 公共 API 无提供商注册表
  （仅 `OpenAiCompatibleProvider` + 两方言），其余预设按各厂商公开 OpenAI 兼容
  端点配置（Configured 级），互操作以连通性自检为准；单测覆盖目录契约与
  套用/匹配语义（6 项）。
- [x] `P3-08` 悬浮球与面板：状态环/拖动/单击展开/长按接管/面板（新任务/停止/接管/
  最近动作）；与通知、任务台状态同源。
- [x] `P3-09` 测试与门禁：单测（策略表全分支/确认绑定与负向（nonce 重放、错误
  nonce、超期）/配置 JSON 与凭据加解密核心/结果与相位投影解析）；`./gradlew
  assembleDebug lintDebug testDebugUnitTest` 全绿；bridge 零线程创建 grep 复核。
- [x] `P3-10` 文档与发布：总计划状态、CHANGELOG、架构/工具集/前端设计同步（传输边界、
  R3 实现、投影粒度、导航偏差注记）、台账 `MIR-20260906-006/007`、验证记录（真机项
  登记补跑条件）、`v0.3.0` tag 建议（打 tag 需用户确认）。

## 风险与阻塞

| 风险 | 对策 |
| --- | --- |
| 真实 VLM 闭环被 MIR-007 阻断（图像 artifact 媒体类型/读取路径） | 台账登记 + 连通性/干跑双轨替代验证；补跑条件明确（lock 升级 + 端点配置）；P5 报告引用 |
| MIR-20260906-006：mira 安装包无传输头文件 | 宿主实现公共 `IHttpTransport`（设计内扩展点）；上游修复后可切换官方 transport（独立变更） |
| Kotlin HttpURLConnection 长阻塞 + 取消时延 | 异步注册表（容量 8，超限快速失败）+ disconnect() + C++ 50ms 协作轮询；超时与 deadline 取 min |
| Executor 死锁（loop worker 阻塞等待同池结算） | 4 线程池 + P1/P2 已验证的 submit/wait 模式；干跑矩阵含取消路径；异常路径 shutdown(true) 排空 |
| 确认弹窗无人响应阻塞任务 | 60s 到期自动 REJECTED(side=0)；任务 deadline 与确认窗口取 min；takeover 立即失效 |
| 悬浮球在 ColorOS 上的 overlay 兼容（吸附/长按） | 真机矩阵覆盖拖动/长按/展开；失败路径 fail-closed（球隐藏 + 通知可达） |
| 无障碍/投影中途断开 | epoch 失效（P1/P2 路径）→ loop Recovering→Failed；UI 呈现原因 |
| `ACTION_SET_TEXT` 焦点时序（P2 遗留） | 沿用 P2 修复（2s 重试 + 焦点子树 BFS）；干跑 type 步骤复验 |

## 测试与退出条件

- [x] 单测（JVM，34 项新增，合计 54/54 通过）：R3 策略表全分支（关键词/动作/前台
  应用组合/release_all 豁免/摘要脱敏）；确认请求/结算与结果投影解析全分支；配置
  序列化与 https/完整性校验；凭据加密核心 roundtrip + 篡改/短 blob 拒绝（JVM 引擎
  注入，AndroidKeyStore 版真机验证）；端点静态策略（私有/回环/链路本地/IPv6 字面量
  拒绝）。nonce 重放/超期路径经 mira `ConfirmationAuthority.consume` 实现（单测层
  为解析投影；native consume 校验由真机矩阵负向用例覆盖，见补跑项）。
- [x] 门禁：`./gradlew assembleDebug lintDebug testDebugUnitTest` 全绿（54/54）；
  bridge/host 层零线程创建（grep 通过：`std::thread`/`std::async`/`pthread_create`
  无新增；无 `GlobalScope`）；native 目标独立编译零警告（NDK 26.3，
  -Wall -Wextra -Wpedantic）；异常不穿越 JNI（新增入口全 try/catch）。
- [ ] 真机（OnePlus Ace 3）：**补跑**（2026-09-06 实施会话无设备连接）——干跑矩阵
  （①Completed+靶点计数 ②MaxSteps ③取消→Cancelled ④R3 正负向）、takeover+
  RELEASE_ALL、连通性自检（用户提供真实端点）、悬浮球交互（拖动/长按/展开/降级）。
  负责人：用户；条件：设备连接 + 四项授权 + P1/P2 自检通过 + 配置端点。
- [ ] 真机：≥3 类真实任务端到端（可行性 §8 P3 验收）——**上游阻断**（MIR-007），
  补跑条件：mira 修复图像 artifact 路径 + lock 升级（独立变更）+ 重跑 P0–P2 验收 +
  真实端点。
- [x] 文档同步完成（总计划、CHANGELOG、设计、台账、验证记录）。

## 验证记录

（按日期追加）

2026-09-06：计划建立，状态 `In Progress`。前置 P2 已完成；开工前置核对发现两项上游
缺口（安装包传输头文件缺失、loop 图像 artifact 路径非真实 VLM 可消费），登记
`MIR-20260906-006`/`MIR-20260906-007`，P3 验收按"连通性 + 干跑"双轨与补跑条件执行。

2026-09-06（P3 实施完成，本会话无设备连接）：

- 实现（按决策 1–12 冻结落地）：
  - `loop_runtime.cpp`（新增）：LoopRuntime 组装（Executor 4 线程/队列 128 +
    AndroidHostAdapter + ModelGateway + OpenAiCompatibleProvider + admission +
    MemoryEventStore + ModelDoneVerifier）；`KotlinHttpTransport`（公共 `IHttpTransport`
    宿主实现：封送 + 50ms 协作轮询 + 取消通知 + 注册表容量 8）；`ScriptedTransport`
    （ChatCompletions wire JSON 按脚本回流）；R3 确认（`ConfirmationAuthority`
    issue/consume，60s 到期，单次有效，容量 4）；`submit_cancellable` 提交
    （StopToken→OperationContext）；takeover/cancel/close 顺序（决策 3）；
    连通性自检（独立小栈，文本-only 决策请求）。
  - `host_abi_impl.cpp`：dispatch_input 受理后 R3 准入询问（loop 会话活跃时；
    release_all 豁免）；require_confirmation 操作停放登记（不派发平台），放行经
    `miracle_host_confirm_release` 按原参数派发，拒绝/到期/取消按
    REJECTED/CANCELLED(side=0) 结算；capture/input 受理上投 observing/acting 粗相位。
  - `runtime_glue.cpp`：新增 JNI 入口（loopOpen/Submit/Cancel/Takeover/Close/State、
    modelConnectivityTest、consentResolve、nativeHttpExchangeComplete），
    显式 RegisterNatives，异常不穿越。
  - Kotlin：`HttpTransportBinding`（https-only + 私有地址拒绝 + DNS 复核 + 响应字节
    上限 + 恰好一次回流）；`RiskPolicy`（策略表纯函数）+ `SessionGate`（披露+四项
    授权+配置完整性）+ 确认对话框宿主；`ModelConfigStore`（SharedPreferences 非敏感
    项 + AndroidKeyStore AES-GCM 密钥文件）；`AgentRuntime` 门面（状态/时间线/确认流 +
    会话生命周期 + R3 查询）；FGS 通知 action（停止/接管）与状态文案联动；悬浮球
    （状态环/拖动/单击展开 Compose 面板/长按 takeover ≥600ms）；任务台/设置/引导页
    与 P3 自检卡（连通性 + 干跑四场景）。
- 测试与门禁：单测 54/54（新增 34：RiskPolicy 11、LoopEventParser 6、ModelConfig 5、
  EndpointPolicyCheck 6、ProviderPresets 6）；`assembleDebug lintDebug testDebugUnitTest` 全绿；native
  独立编译零警告；零线程创建/无 GlobalScope grep 通过。
- 实施偏差记录：(a) 配置存储用 SharedPreferences 而非 DataStore（架构 §6 表述为
  DataStore；v1 字段量小，避免新增依赖，架构文档同步注记）；(b) 干跑探针经
  LoopSelfTestCard 靶点复用 P2 模式（原计划"复用 P2 卡靶点"，实现为 P3 卡独立靶点，
  语义等价）。
- 真机项与真实任务验收未执行（无设备连接/上游阻断），补跑条件见"测试与退出条件"，
  不标记完成。
- 补跑工具就绪（2026-09-06 追加）：闭环事件/结果证据流落 logcat（`miracle/loop`
  全事件 JSON、`miracle/verify` 场景结论）；场景可经 `am start --es
  dev.linductor.miracle.extra.AUTO_SCENARIO <complete|max_steps|cancel|r3|connectivity>`
  脚本化触发；`tools/p3-device-verify.sh [serial] [scenario...]` 一键完成安装、
  授权（通知 pm grant/悬浮窗 appops/无障碍 settings put）、投影绑定等待（logcat
  "host bound"）、逐场景取证（logcat + 截图存 `build/p3-device-evidence/`）与
  悬浮球长按 takeover（`input swipe` ≥900ms）。ColorOS 投影对话框与 R3 确认弹窗
  需人工点击（P2 经验：系统对话框不可脚本化）。
