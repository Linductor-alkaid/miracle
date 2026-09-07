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

1. **模型栈组装与传输边界**：P3 开工时 mira 安装包未随包导出 `adapters/net` 头文件
   （登记 `MIR-20260906-006`），经**公共 `IHttpTransport` 接口**实现宿主传输：C++ 侧薄适配
   （请求/响应封送 + 有界协作等待），HTTPS 执行在 Kotlin（`HttpURLConnection`，系统
   信任库，无新增依赖）。方言映射、decision schema 校验、SSE、预算/准入全部保留在 mira
   `OpenAiCompatibleProvider`/`ModelGateway`（不重复实现上游语义）。传输阻塞等待发生在
   loop worker（gateway.infer 同步链）：C++ 条件变量 50ms 轮询 `OperationContext::
   cancelled()` 与 Kotlin 完成标志；取消时通知 Kotlin 侧 `disconnect()`。SSRF 姿态与
   `TransportLimits` 默认一致：仅 https、拒绝私有/回环/链路本地地址、响应字节上限。
   lock 升级至 mira `cbed6ad` 后官方 transport 头已随包导出（MIR-006 关闭）；Kotlin
   传输保留（公共扩展点 + 系统信任库），切换官方 socket/mbedtls 栈为后续独立变更。
2. **闭环图像路径（开工时受 `MIR-20260906-007` 阻断；lock 升级后解除）**：开工基线
   （mira `16e419e`）的 `AgentLoop::build_request` 将截图 artifact 以
   `media_type="application/octet-stream"` + 原始 RGBA 字节构建 `ImagePart`，真实端点拒绝
   且宿主无工件读取路径——真实 VLM 闭环不可达，P3 以 (a) 连通性自检（文本-only）与
   (b) 闭环干跑（脚本化 transport）双轨替代验证。mira `cbed6ad`（DEC-012/013）后，
   Miracle 按宿主编码语义落地：Kotlin 截屏同步 PNG 编码 → 帧完成以原始字节 sha256 为键
   登记（`frame_encoding.hpp`）→ 注入 loop 的 `HostFrameStore` 在 commit 时重新发布为
   `image/png` 工件（容量 128 MiB，原始帧转码后回收；无登记载荷时原始帧如实按
   `image/x-host-frame` 发布）→ `StoreArtifactSource` 供方言层生成 `data:image/png`
   数据 URL；干跑与真实路径共用该链路。真实任务（≥3 类）补跑条件：真机连接 + 用户
   配置真实端点 → 按 P5 矩阵取证。
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
| 真实 VLM 闭环图像路径（原 MIR-007 阻断） | lock 升级 mira `cbed6ad` + 宿主编码链路（决策 2）解除；真机 ≥3 类任务取证待补跑（设备 + 真实端点） |
| 官方 transport 头未导出（原 MIR-006） | 上游已导出（`cbed6ad`）；Kotlin 传输保留（公共扩展点），切换官方栈为后续独立变更 |
| PNG 编码失败/未登记（宿主编码链路） | fail-open 仅影响 wire 格式：原始帧按 `image/x-host-frame` 如实发布（过方言 image 门，真实端点可能拒——上游既定边界）；编码异常落日志可见 |
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
- [ ] 真机：≥3 类真实任务端到端（可行性 §8 P3 验收）——上游阻断已解除（mira `cbed6ad`
  + 宿主 PNG 编码链路，2026-09-06 lock 升级），补跑条件：设备连接 + 真实端点配置 +
  P1/P2 自检通过。
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

2026-09-06（mira lock 升级独立变更：`16e419e` → `cbed6ad`，上游反馈两轮落地后的
适配）：

- 判断依据：mira 按 miracle 台账完成两轮修复（PR [#16]/[#17]，DEC-012/013），覆盖
  MIR-001/003/004/005/006/007 与 lease 统计口径；P3 登记的真实任务补跑前置（lock
  升级）满足，按计划决策 2 执行本变更。MIR-002（ToolProposals）上游未动，保持 Open。
- lock 与安装：`tools/mira.lock` commit 更新为 `cbed6ad`（含 net 头导出）；
  `tools/install-mira.sh --force` 全新构建安装通过（NDK 26.3，arm64-release）。
- 宿主编码链路（决策 2 落地）：`frame_encoding.hpp`（原始字节 sha256 为键的有界
  登记，容量 4 帧）；`HostFrameStore`（注入 adapter，commit 时把登记的 PNG 重新发布
  为 `image/png` 工件、原始工件回收；未命中回退如实标注）；`StoreArtifactSource`
  （wire 字节回读，替代原 `FailClosedArtifactSource`——后者保留用于连通性自检）；
  Kotlin `ScreenCaptureProvider` 截屏同步 PNG 编码（锁外，`Bitmap.compress`）经
  `nativeCompleteFrame` 新增 `encoded` 参数上投。
- 其他适配：`inputTestDispatch` adapter 路径接通 `InputEvent.duration_ms`（MIR-005
  workaround 移除）；`close()` 补 scripted 模式 `kotlin_transport` 空指针防护（既有
  缺陷，干跑矩阵真机补跑前修复）；降采样注释按 MIR-004 关闭更新（容量解除，现为
  载荷大小策略，采集档不变）。
- 验证：`./gradlew assembleDebug lintDebug testDebugUnitTest` 全绿（单测 62/62）；
  native 目标针对新安装前缀重编译零警告；零线程创建/无 GlobalScope grep 通过。
  真机项（干跑矩阵、连通性、真实任务 ≥3 类）仍待设备连接补跑，不标记完成。

2026-09-06（连通性自检修复：Kotlin 传输完成回流误路由，真机验证通过）：

- 问题与定位：真机连通性自检恒定失败 `{"ok":false,"stage":"infer","error":"exchange
  timed out","ms":33055}`（30s 期限 + 3s 取消宽限）；而 mira 上游 `m3_interop_probe`
  （官方 mbedtls socket transport，同步返回）对同一服务通过。网络探测排除：手机与
  开发机 `curl` 该端点均 <0.3s 返回 401（`https://api.siliconflow.cn/v1/chat/completions`）。
- 根因：`complete_http_exchange` 只把 Kotlin 完成回流路由到 `g_loop->kotlin_transport`
  （会话栈）；连通性自检使用局部 `KotlinHttpTransport` 实例（不挂 `g_loop`），HTTP
  响应回到 Kotlin 后在 native 入口即被丢弃，native 侧等满期限按超时结算。干跑
  （ScriptedTransport，同步）与真实会话（g_loop 路由正确）不受影响——与"仅连通性
  自检一直失败"的现象一致。
- 修复（`loop_runtime.cpp`，bridge 内部，不改 ABI/门面/JNI 签名）：exchange id 改
  进程级唯一原子递增（消除会话栈与自检局部实例各自从 1 起号的同 id 误投递）；
  `KotlinHttpTransport` 构造统一经 `create()` 登记进 weak_ptr 注册表（析构清理过期
  项）；`complete_http_exchange` 在注册表内按 id 路由到所属实例——锁内提升强引用、
  锁外调用（`g_transport_registry_mutex` 为独立叶子锁，文件头锁序注释同步）；
  未命中实例按未知 id 丢弃（迟到/孤儿完成的既有 exactly-once 语义不变）。
- 验证：`assembleDebug`、`testDebugUnitTest` 全绿（62/62）；真机 PJE110（`a4dfdcbf`）
  重装修复包后触发连通性自检（配置：api.siliconflow.cn，Qwen/Qwen3.5-4B，chat 方言）：
  `{"ok":true,"stage":"infer","admitted":true,"attempts":1,"decision":true,"violations":0,
  "usage_in":45,"usage_out":222,"ms":6186,"mira_version":"0.1.0"}`（修复前同配置
  33055ms 超时）。标准化取证脚本 `p3-device-verify.sh connectivity` 因投影绑定前置
  （setup 需人工完成 ColorOS 授权）本次未执行，以 logcat 证据行为准；干跑矩阵与
  真实任务真机项维持"待补跑"状态不变。

2026-09-06（真实任务首次真机运行排查：`timeout_ms` 传递缺陷修复 + MIR-008 登记）：

- 现象与定位一（会话 16ms 即 Failed）：真机任务"打开设置并调亮亮度"报
  `observation failed: host operation was cancelled`（`cancelled_ops:1`，帧迟到按
  status 7＝INVALID_STATE 结算）。根因：`ModelConfig.toNativeJson` 写死
  `"timeout_ms":0`，native `open()` 按有效值取用 → `total_timeout_ms=0` → 会话
  deadline 立即过期 → observe 经 ABI fail-closed 拒绝。干跑脚本不含该字段（走
  native 公式默认），故未暴露。
- 修复（Kotlin+native+单测）：`toNativeJson` 移除 `timeout_ms` 字段（KDoc 注明
  缘由）；native 对显式 `timeout_ms<=0` 回退公式预算（fail-safe）；
  `ModelConfigTest` 断言字段不存在防回归。`assembleDebug`/`testDebugUnitTest`
  （63/63）/`lintDebug` 全绿。
- 现象与定位二（推进至 model call 后失败）：真机重跑（用户重新授权投影）报
  `model call failed: artifact descriptor integrity mismatch`。根因在上游 mira：
  `agent_loop.cpp` `build_request()` 构造截图 `ArtifactRef` 漏填
  `digest`（`ScreenFrameDescriptor.payload_digest` 已由 adapter 填充但未流入），
  miracle `StoreArtifactSource` 按 DEC-013 语义以 ref 重建 descriptor 后
  `MemoryArtifactStore::open` 完整性校验 fail-closed。上游测试
  （`SimulatorArtifactSource` 仅按 id 打开）不可见该漏填。
- 处置：按消费边界不在本仓库绕过；登记 `MIR-20260906-008`（P1）并反馈上游
  mira [#19](https://github.com/Linductor-alkaid/mira/issues/19)（含根因、测试
  盲区、期望语义与可验收结果）。上游修复合入后走 lock 升级独立变更重跑
  "≥3 类真实任务"取证；该项维持待补跑状态。

2026-09-06（mira lock 升级 `cbed6ad` → `635e136`：MIR-008 关闭；真实任务推进至
决策编译层，MIR-009 登记）：

- lock 升级与验证：上游 PR [#20](https://github.com/Linductor-alkaid/mira/pull/20)
  （`a839565`）修复 ArtifactRef digest；`tools/mira.lock` 更新、
  `install-mira.sh --force` 重建、`assembleDebug`/`testDebugUnitTest`/`lintDebug`
  全绿。真机（PJE110）真实任务：observe → 模型调用（截图 wire `data:image/png` +
  http 200 + 决策返回）全通，MIR-20260906-008 关闭（台账已注记）。
- 宿主防御（真机暴露后落地）：任务提交前回桌面引导（`AgentRuntime.startSession`：
  真实任务 HOME intent + 600ms 过渡等待）——此前模型首次 tap 落在宿主任务页
  "停止"控件上（act 后 95ms 即"已请求取消"），且目标输入的 IME 污染首帧观察；
  干跑（scripted）不受影响。R3 确认弹窗随 Activity 后台不可见，超时即拒绝
  （fail-closed 不变），通知渠道承接登记为后续项。
- 决策可诊断性：`HttpTransportBinding` 增加响应摘要日志（`miracle/transport`：
  http 状态 + 字节数 + 头 256B，不含凭据）——决策失败此前完全不可归因。
- 新阻断（MIR-20260906-009，上游 [#21](https://github.com/Linductor-alkaid/mira/issues/21)）：
  决策 schema `required` 仅 `action+reason`，模型返回
  `{"action":"swipe","reason":…}`（无坐标）过 schema 后在
  `compile_discrete_action` 失败，AgentLoop 一步终态 Failed（无 repair）。
  miracle 侧不可修补（冻结契约）；上游修复后重跑"≥3 类真实任务"取证。
  另一观察项：Qwen3.5-4B 推理型输出单次决策耗时 ~29.7s（接近 30s 默认
  call_timeout，设置页可调 5-120s）。

2026-09-06（mira lock 升级 `635e136` → `d1993d2`：MIR-009 上游修复落地；真机取证欠账）：

- 上游修复：PR [#22](https://github.com/Linductor-alkaid/mira/pull/22)（`9a1dd28`）
  ——决策 compile 失败在 `max_recoveries_per_step` 预算内以静态诊断作为 feedback
  重试，预算耗尽才带具体原因终态 Failed（上游含 m3 测试与文档）。
- Miracle 落地：lock 更新至 `d1993d2`；`install-mira` 重建（子模块经本地路径
  解析：`protocol.file.allow always` + 指向 `/home/linductor/mira/third_party/*`，
  绕开当次 GitHub 网络中断；恢复远程时无需回退——URL 仅影响取源方式）；
  `assembleDebug`/`testDebugUnitTest`/`lintDebug` 全绿。
- 真机取证欠账（不标记完成）：当前无可用真机。补跑条件＝设备连接 + 投影/
  无障碍授权后重跑真实任务（Qwen3.5-4B），断言：参数缺失决策触发
  `recoveries>0` 反馈重试（`miracle/loop` 步记录 note 含 compile 诊断）、
  不再一步终态；随后 MIR-009 台账转 Resolved 并执行 P3 "≥3 类真实任务"
  取证（建议先把 call_timeout 调至 ≥60s，实测单次决策 ~29.7s）。

2026-09-06（`d1993d2` 真机验证：MIR-009 关闭；MIR-010 登记——验证观察零组件
请求被 Android adapter 拒绝）：

- 真机验证（PJE110，用户执行测试）：决策→动作链路全通——模型首次决策即带
  规范坐标（`tap x:0.396`）、编译执行成功、设置应用被打开；MIR-009 正向链路
  验证通过转 Resolved（负路径由上游 m3 测试覆盖）。回桌面引导下首帧观察干净
  （observing→reasoning 385ms）。
- 新阻断（act 后 87ms 终态 `Failed: "verification observation failed"`，无
  取消/网络错误）：`observe_once(Verification)` 发出零组件请求（仅设 max_age），
  `AndroidHostAdapter` 对零组件请求 fail-closed → 每步验证必败。测试盲区：
  Simulator 宽容 + `ModelDoneVerifier` 不消费 Observation。登记
  `MIR-20260906-010`（P1）并反馈上游 mira
  [#23](https://github.com/Linductor-alkaid/mira/issues/23)。上游修复后 lock
  升级重跑；"≥3 类真实任务"取证仍待此阻断解除。

2026-09-06（`874f4a5` 真机验证：闭环 5 步直达 Completed——MIR-010 关闭；两项
非阻断遗留定性）：

- 真机验证（PJE110，用户执行）：MIR-010 修复生效——验证观察每步通过，真实任务
  首次 5 步推进至 `Completed`（recoveries=1：step1 模型漏坐标经 MIR-009 反馈
  重试修正；行为链：桌面 → 打开设置 → 显示与亮度 → 滑动滑块 → done 声明）。
  MIR-010 台账转 Resolved。
- 配套修复（miracle UI 缺口）：设置页新增"单次决策超时 ms"字段
  （5000-120000）——数据层本已支持但无入口，默认 30s 在 Qwen3.5-4B 推理型
  输出下不稳定（实测 6-30s 波动，第三步曾 30.05s 超时终态）。本轮已存 120s。
- 遗留一（诚实呈现，本轮落地）：终态 `Completed` 实为模型 done 声明确认——
  `ModelDoneVerifier` 为 mira 测试实现（头文件自述"used by tests"，verify 忽略
  Observation），miracle 误作生产 verifier；真机已出现"声称完成但滑动落点
  错误、目标未达成"。任务页终态文案改为"模型声称完成（未独立验证目标）+
  建议自行核对"。真实目标验证（自定义 ILoopVerifier：UI 树/视觉对照）登记
  为后续项。
- 遗留二（模型 grounding，后续项）：滑动滑块落点错误＝Qwen3.5-4B 视觉定位
  弱；路径＝更强模型或 UI 树 grounding（Kotlin 无障碍树 →
  `mira.host.tree.v1`，MIR-20260905-001 关闭注记已预告，待独立计划项）。
