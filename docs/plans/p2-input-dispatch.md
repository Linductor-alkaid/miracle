# P2：输入链路

> 状态：Completed
> 负责人：Miracle Maintainers
> 所属计划：[Miracle 实施总计划](miracle-implementation-plan.md)
> 前置：P1（Completed，2026-09-05）
> 建议发布点：`v0.2.0-alpha`（本里程碑通过后打 tag）
> 更新日期：2026-09-06

## 目标

打通第一条出站宿主能力链路：mira `InputSequence`（[0,1]² 规范坐标）→
`AndroidHostAdapter.execute()` → Host ABI v1 `dispatch_input`（全语义）→ JNI →
Kotlin `InputDispatcher`（无障碍 `dispatchGesture`/`performGlobalAction`/文本注入）
→ 回执（Dispatched/Completed/Rejected/Unknown + side_effect 标志）如实回填 mira。
同时交付：无障碍服务与授权引导、协作取消与 `RELEASE_ALL`、输入安全矩阵
（mira 设计 15.2/15.3 对应项：错误分类映射与取消/超时/权限缺失路径）、直接 ABI 契约
探针与 P2 输入自检页，并在真机（OnePlus Ace 3）取证。

## 范围与非目标

范围：`dispatch_input` 全部事件类型（tap/long_press/swipe/type/back/home/
release_all）；操作注册表复用（exactly-once 终态）；输入路径的协作取消（cancel →
cancelGesture 合成抬起）；`stop` 时在途输入的有界排空；能力位如实（无障碍断开时
input mask=0）；无投影时的显示拓扑兜底（输入不依赖投影）；文本注入（焦点节点
ACTION_SET_TEXT）；`MiracleAccessibilityService`（手势能力 + 窗口事件跟踪种子）；
P2 自检页（无障碍引导、靶点、文本框、BackHandler、结果与违规计数展示）；真机矩阵
（自动套件、取消路径、无障碍关闭负向、home、旋转）。

非目标：UI 树（`MIR-20260905-001` 不变）；AgentLoop/模型决策（P3）；同意/确认门面
与 SessionGate（P3，本里程碑自检为用户显式触发，靶点位于自身 UI 内）；多指手势与
连续控制；AppMonitor 完整形态（仅保留最近前台包跟踪，供 home 验证）；悬浮球（P3）；
`get_ui_tree` 仍 fail-closed。

## 设计与决策依据

- [总体架构设计 §2/§3/§4/§8](../design/system_architecture_design.md)（InputDispatcher
  职责、Act 数据流、线程模型、错误语义映射）
- [工具集设计 §2/§3/§4](../design/agent_tool_set_design.md)（L0 Provider 形态、L1 约束、
  输入动作风险分级与 release_all 安全原语）
- mira 冻结契约：`host_abi.h`（`dispatch_input`/`cancel_operation` 注释语义：
  已进入输入管线的原子调用被取消时报 `EXECUTION_UNCERTAIN` +
  `side_effect_may_have_occurred=1`）
- mira `adapters/android/android_host_adapter.cpp`（`execute()` 消费路径：canonical
  payload 解析、拓扑前置、receipt→`ExecutionStatus` 映射、`wait_for_outcome` 的
  200ms 取消宽限）
- mira 设计 15.2/15.3（错误分类与 Recovery：Action 可能已执行→不重发）
- P1 计划"关键实现决策"1/4/8（操作注册表、回调线程、失败可见性）沿用

## 关键实现决策（本文件冻结，实现遵循）

1. **输入 Provider 归属与结构化并发**：`MiracleAccessibilityService`（系统绑定生命
   周期）拥有 `InputDispatcher` 及其 `Main.immediate` 结构化作用域；
   `onServiceConnected`/`onUnbind` 时经 `HostBridge` 绑定/解绑并触发 epoch 递增
   （无障碍重连属 epoch 源，工具集设计 §3）。native→Kotlin 只经
   `HostBridge.dispatchInput/cancelInput/inputConnected` 静态门面。
2. **能力位诚实**：无障碍连接时 `input_capabilities_mask` 置 bits 1..7
   （tap/long_press/swipe/type/back/home/release_all），断开时为 0（mira 推导的
   `discrete_input`/`input_release` 随之失效）；`max_gesture_duration_ms=60000`
   （平台 StrokeDescription 上限）、`max_pointers=1`（v1 仅单指）。
3. **拓扑不依赖投影**：新增 `DisplayGeometry`（仅读显示 metrics）；无
   `ScreenCaptureProvider` 绑定时 `HostBridge.topologyJson` 以其兜底，`cap_w/cap_h=0`
   （无截屏能力，诚实）。输入链路（含经 adapter 的 `execute()` 拓扑前置）因此可在
   仅开无障碍的会话中验证。
4. **手势合成**：tap=60ms 单点 stroke；long_press 时长取 `duration_ms∈(0,60000]`
   否则默认 600ms；swipe 直线路径时长否则默认 350ms；坐标 [0,1]²→**当前**真实屏幕
   像素（`maximumWindowMetrics`，API<30 走 `getRealMetrics`），clamp 屏内；旋转后
   旧坐标由 epoch 失效（见风险表：无投影时 epoch 源受限）。
5. **文本注入**：`findFocus(FOCUS_INPUT)` + `ACTION_SET_TEXT`（需
   `canRetrieveWindowContent`），无焦点节点时 500ms 内有界重试；失败报
   `(OK, REJECTED, side=0)`。"逐键手势降级"因无软键盘布局知识不可行，登记为本里程碑
   已知限制（P3 证据化后决定是否推动上游）。
6. **back/home**：`performGlobalAction`，布尔结果如实映射 COMPLETED/REJECTED。
7. **取消与 RELEASE_ALL**：`cancel_operation` 对输入操作**不同步结算**——标记 +
   通知 Kotlin 协作取消；Kotlin 恰好一次完成回流：未提交平台→`CANCELLED`
   (side=0)；已提交且被中断→`EXECUTION_UNCERTAIN`(side=1)；竞态下先完成→原结果。
   `RELEASE_ALL`（kind=7）为可派发事件：阻断**全部**在途序列的未派发事件，无需
   确认、随时可发。`stop()`：输入操作标记取消 + 通知 + 有界等待（≤1s）排空，
   剩余同步结算 `CANCELLED`；capture 路径维持 P1 同步结算。
   **实施修正（2026-09-06）**：原设计假设 `AccessibilityService.cancelGesture`
   存在（"取消当前手势/合成抬起"），android.jar API 35 公共面实测**无此 API**
   （`dispatchGesture` 返回 boolean）。按 host_abi.h 冻结的原子输入语义实现：
   已提交手势不可中断、自然收敛（≤60s）后按 `EXECUTION_UNCERTAIN+side=1` 结算；
   序列尾复查 interrupting 保证取消后到事件如实标注。"合成抬起事件"登记为
   已知限制（需非公共 API）。
8. **回执语义**：全事件完成→`(OK, COMPLETED)`；平台拒收（dispatchGesture 返回 0/
   无焦点/全局动作 false）→`(OK, REJECTED, side=0)`；deadline→
   `(DEADLINE_EXCEEDED, side=手势在途?1:0)`；服务中途销毁→`(EXECUTION_UNCERTAIN,
   side=1)`；并发超限（在途 >16）→`(CAPACITY)`；无障碍未启用→`PERMISSION_DENIED`、
   门面未绑定→`UNAVAILABLE`（受理快速失败路径，经注册表竞争结算恰好一次）。
9. **参数校验（同步快速失败，不回调）**：`struct_size` 偏小、`display_id≠0`、
   `event_count` 为 0 或 >64、坐标非有限或越界 [0,1]、type 文本长度 0 或 >4096、
   duration>60000 → `INVALID_ARGUMENT`；生命周期非 started → `INVALID_STATE`。
   事件以 UTF-8 字节数组过 JNI（避免 MUTF-8 假设），native 组装紧凑 JSON。
10. **自检双轨**：(a) **直接 ABI 契约探针**（独立 host 实例 + 自有回调表）覆盖：
    非法参数同步拒绝、过期 deadline 结算、RELEASE_ALL、3000ms 长按中途取消→
    `EXECUTION_UNCERTAIN+side=1`、stop/destroy 干净；探针内的有界 sleep 仅存在于
    测试代码（生产取消路径事件驱动、无阻塞）。(b) **经 mira adapter 会话**
    （Executor + `AndroidHostAdapter`）按 tap/long_press/swipe/back/tap/type 顺序对
    自身 UI 靶点/文本框执行并断言副作用（计数/内容）；home 为末步，经无障碍窗口
    事件断言 launcher 前台后由 adb 拉回应用呈现结果。会话与 P1 自检共用"进程内单
    host"约束，UI 保证互斥。

## 工作项

- [x] `P2-01` 本计划与设计核对（完成即勾选，含实现决策冻结）。
- [x] `P2-02` Host ABI v1 输入实现：`dispatch_input`（校验/注册/JNI 受理/完成结算
  with receipt+side_effect）、`cancel_operation` 输入协作取消、`stop` 有界排空、
  能力位（input mask/max duration/max pointers）、事件 JSON 组装与 UTF-8 传递。
- [x] `P2-03` Kotlin 宿主层：`MiracleAccessibilityService`（手势+焦点查找+窗口事件
  跟踪+epoch 通知）、`InputDispatcher`（手势合成/dispatchGesture/文本/全局动作/
  协作取消/RELEASE_ALL/有界并发）、`HostBridge` 输入门面、`DisplayGeometry`、
  manifest 与无障碍服务配置。
- [x] `P2-04` 自检与 GUI：native `inputContractProbe` + `inputTestOpen/Dispatch/
  Interrupt/Close`（adapter 会话）；P2 输入自检卡（无障碍状态与引导、靶点计数、
  长按计数、swipe 区、文本框、BackHandler 计数、探针与会话结果、bridge/host
  违规计数展示）。
- [x] `P2-05` 验证：单测 20/20（事件 JSON 解析/坐标映射/结果投影）；
  `./gradlew assembleDebug lintDebug testDebugUnitTest` 全绿；真机矩阵（OnePlus
  Ace 3）全项通过（详见验证记录）；模拟器回归未执行（ARM 翻译路径为 P1 已有
  补充证据，非 P2 门禁，见限制）。
- [x] `P2-06` 文档与发布：总计划状态、CHANGELOG、兼容性证据（oneplus-ace3.md
  增 P2 记录）、工具集/架构设计中输入取消语义的实测修正、上游台账新增
  `MIR-20260906-005`、`v0.2.0-alpha` tag 建议（打 tag 需用户确认）。

## 风险与阻塞

| 风险 | 对策 |
| --- | --- |
| ColorOS/EMUI 无障碍开关路径与手势限速（厂商反自动化） | 以真机实测为准；限速表现为耗时上升/回执如实；异常路径 fail-closed 并记录 |
| 无投影会话中旋转不触发 epoch（P1 的 epoch 源在 capture provider） | P2 记录为已知限制；P3 SessionGate 统一 epoch 源；真机旋转矩阵用投影绑定会话验证 |
| `ACTION_SET_TEXT` 对 Compose TextField 的焦点可见性时序 | 500ms 有界重试；失败 REJECTED 可见；真机断言文本内容 |
| 长按取消后是否有"粘滞触点"（抬起未合成） | 取消探针后追加 tap 复验（副作用计数递增证明输入管线未滞死） |
| mira adapter `execute()` 不传 duration（payload 仅坐标） | 宿主默认时长（决策 4）；需要显式时长的契约测试走直接 ABI 探针 |
| 双自检入口并发触发"进程内单 host"冲突 | UI 互斥 + 探针/会话生命周期串行；冲突时 adapter create 失败路径可见 |

## 测试与退出条件

- [x] 单测：输入事件 JSON 解析（含 UTF-8 文本、非法输入拒绝）、[0,1]²→像素映射
  （clamp/旋转尺寸）、自检结果 JSON 投影全分支（20/20）。
- [x] 真机（OnePlus Ace 3，API 36）：P2 自检页一键套件通过——adapter 会话各步
  receipt=Completed，tap/长按计数与文本框内容断言成立；host 违规计数
  （unknown/late/violations）为 0。连续两次完整通过（可重复执行）。
- [x] 真机：取消探针返回 `EXECUTION_UNCERTAIN` + `side_effect_may_have_occurred=1`，
  随后 tap 复验成功；stop/destroy 干净（无崩溃、可重复执行）。
- [x] 真机负向：无障碍未连接时运行 → UI 呈现 PERMISSION_DENIED（fail-closed），
  无崩溃；重新开启后可重试通过。
- [x] 真机：home 步骤回执 Completed；`GLOBAL_ACTION_HOME` 在 ColorOS 16 上派发
  成功但被系统拦截不导航（55 次前台采样证实），经 CATEGORY_HOME intent 兜底
  到达 launcher（截图证实）——OEM 行为记录于兼容性证据；launcher 前台确认在
  自检中为观察项（不作为失败判据）。旋转（投影绑定会话）epoch 递增（1→2）且
  旋转后输入落点正确；旋转瞬间在途手势被平台拒收并如实回执 Rejected
  （fail-closed 语义实证）。
- [x] 门禁：bridge/host 层零线程创建（grep + 评审，含新增输入路径）；异常不穿越
  JNI（native 完成入口全路径 try/catch）；`./gradlew assembleDebug lintDebug
  testDebugUnitTest` 全绿（20/20 单测）。
- [x] 文档同步完成（总计划、CHANGELOG、兼容性、设计修正、台账、验证记录）。

## 验证记录

（按日期追加）

2026-09-05：计划建立，状态 `In Progress`。前置 P1 已完成；输入链路按决策 1-10 冻结
后开工。

2026-09-06（P2 实施与真机验证，OnePlus PJE110 / Android 16 / API 36 / ColorOS
16.0.0 / 480dpi）：

- 实现：`host_abi_impl.cpp` 输入路径（校验/注册/JNI 受理/恰好一次结算 with
  receipt+side_effect/cancel 协作标记/stop 有界排空/能力位 bits 1..7）+
  `runtime_glue.cpp`（`nativeCompleteInput`、`inputContractProbe` 直接 ABI 探针、
  `inputTestOpen/Dispatch/Interrupt/Close` adapter 会话）+ Kotlin
  `MiracleAccessibilityService` / `InputDispatcher` / `HostBridge` 输入门面 /
  `DisplayGeometry` / P2 自检卡与 `InputViewModel`（单测 20/20，全门禁绿）。
- 实施中发现并修正两项设计-平台偏差：
  1. **公共 API 无 `AccessibilityService.cancelGesture`**（android.jar API 35
     实测；`dispatchGesture` 返回 boolean）。取消语义改为 host_abi.h 冻结的原子
     输入约定：未提交平台→`CANCELLED(side=0)`；已提交→手势自然收敛后
     `EXECUTION_UNCERTAIN(side=1)`；序列尾复查 interrupting 保证取消后到事件的
     如实标注。"合成抬起事件"（工具集设计原文）需要非公共 API，登记为已知
     限制（RELEASE_ALL 在序列粒度生效，在途手势按时长有界收敛，≤60s）。
  2. **Compose `boundsInWindow` 与屏幕原点存在厂商偏差**（真机实测约 77px）：
     经 `LocalView.getLocationOnScreen` 屏幕锚定修正；无障碍手势坐标空间与
     视觉空间一致（手动 tap 证实）。
- 另修复：无障碍服务独立于 UI 启动时 native 库未加载（`UnsatisfiedLinkError`
  崩溃）——`HostBridge.ensureNative()` 自持幂等加载守卫，能力通知与自检入口
  fail-closed。
- 真机结果（脚本化：uiautomator 定位 + adb 触发；结果以 logcat JSON + 截图
  像素级读取取证）：
  - 探针（直接 ABI）：`invalid_coords` 同步 `INVALID_ARGUMENT`；`expired_deadline`
    → `DEADLINE_EXCEEDED side=0`（5.5-32.9ms）；`release_all` → `OK/COMPLETED`
    （5.6-36.4ms）；`cancel_midflight`（3000ms 长按、700ms 处取消）→
    `EXECUTION_UNCERTAIN side=1`（结算于手势自然结束时 ~3015-3026ms）；
    `tap_after_cancel` → `OK/COMPLETED`（70-88ms，管线无粘滞）；
    `duplicates/unknowns=0`，stop/destroy `OK`。
  - adapter 会话（mira `execute()` 全链路，对自身 UI）：tap/long_press/swipe/
    back/tap(field)/type("miracle")/home 七步全部 `Completed`（6-629ms）；UI
    副作用断言全过（靶点/长按/BackHandler 计数递增、文本框内容=miracle）；
    bridge 提交/结算 7/7、违规计数全 0、shutdown Completed。整体 ✅。
  - 负向：无障碍未连接运行 → `⛔ 自检失败（accessibility）PermissionDenied
    fail-closed`，FATAL/ANR=0，重新开启后重试通过。
  - home：`performGlobalAction(GLOBAL_ACTION_HOME)` 返回 true 但 ColorOS 拦截
    导航（前台 55 次采样恒为本应用）；`CATEGORY_HOME` intent 兜底一次成功到达
    launcher（com.android.launcher，截图证实），一次未达（BAL 时机相关）——
    回执语义（派发层如实）与导航可达性（OEM 相关）分离记录，launcher 确认
    为观察项。
  - 旋转：投影绑定会话（P1 授权链路复验通过）中 `user_rotation 0→1` →
    `epoch changed -> 2 (host_sequence=2)`（native 日志）；旋转后 tap 步骤
    落点正确（Completed）；旋转瞬间在途 long_press 被平台拒收 → 如实
    `Rejected`；旋回后完整套件再次 ✅。
  - force-stop：FATAL/ANR=0，进程干净退出。
- 设备/工具观察项（详见 `docs/compatibility/oneplus-ace3.md` P2 记录）：
  `adb install -r`/`am force-stop` 会清除 `enabled_accessibility_services`
  （系统行为，重装后需重新启用）；ColorOS 无障碍服务绑定存在短暂 teardown/
  重连窗口；`uiautomator dump` 对 Compose 文本返回陈旧语义树（取证改用截图
  像素读取）。
- 上游台账：新增 `MIR-20260906-005`（mira `InputSequence` 不携带手势时长，
  ABI `duration_ms` 字段无法经 adapter 路径填充）。
- 遗留（不阻塞 P2）：模拟器回归未执行（P1 已有同 APK 链路的模拟器补充证据；
  P2 输入路径模拟器补跑条件：开启无障碍后运行输入自检，预期探针/套件同构
  通过）。home 后台导航兜底在生产自动化场景受后台启动限制（P3 悬浮窗
  SYSTEM_ALERT_WINDOW 权限后覆盖）。

2026-09-06（P2 收尾修复：type 步骤在用户操作路径下 Rejected）：

- 用户报告与复现：用户滚动位置/Done 后布局下 `会话·type ✗ Rejected`
  （524ms＝重试窗口耗尽），与脚本化验证通过的固定滚动布局不同。
- 根因两项（扩展诊断日志定位）：
  1. 焦点语义曝光时序：tap 落点正确时 Compose 焦点到无障碍树的可见性在
     部分厂商上可超 500ms；且滚动/布局变化使 tap(field) 落点偏移时焦点
     完全不建立。
  2. 程序化聚焦（FocusRequester）落在字段容器节点（class=android.view.View，
     非可编辑）而非 tap 聚焦的内部可编辑节点——Compose 无障碍暴露差异。
- 修复（生产语义不变，仍 fail-closed）：
  1. `FOCUS_RETRY_MS` 500→2000ms（有界放宽；无焦点仍 Rejected，不猜测目标）；
  2. `typeIntoFocusedNode` 在**焦点子树内**有界 BFS（≤64 节点）解析可编辑
     节点——不跨出焦点子树（容器聚焦/焦点宿主暴露差异均覆盖）；
  3. 自检页 type 步骤前经 FocusRequester 确定性聚焦（测试辅助，与 tap 落点
     精度解耦；被测链路 ABI→InputDispatcher→SET_TEXT 不变）。
- 复验（PJE110）：用户式单滚布局 ✅（type=Completed 526ms、零 type 告警）、
  Done 后"再次自检"布局 ✅、两轮 home 均达 launcher 前台；门禁全绿（20/20）。
