# Miracle 前端设计

> 状态：Proposed
> 版本：1.0
> 更新日期：2026-09-05
> 上位文档：[总体架构设计](system_architecture_design.md)
> 关联决策：[DEC-001 前端形态](../decisions/DEC-001-frontend-compose.md)

## 1. 选型结论与理由

**结论：主 GUI 与悬浮面板统一采用 Kotlin + Jetpack Compose；悬浮球常驻态采用轻量原生
View。**

本项目 UI 的本质约束：页面数量少（引导/任务台/详情/设置），但与系统层（前台服务、无障
碍、媒体投影、悬浮窗、JNI 门面）耦合极深。四个候选的评估：

| 候选 | 声明式/类 React | 系统层接入 | C++/mira 侧 | 结论 |
| --- | --- | --- | --- | --- |
| **Jetpack Compose** | ✅ 组件/状态/副作用模型即 React 思想（§2 映射表） | 同语言同进程直调；ComposeView 可入悬浮窗 | JNI 直连 bridge | **采用** |
| React Native | ✅ 真 React | 无障碍/投影/悬浮窗/FGS 全部要写 Native Module，等于双份实现；overlay 支持弱 | JS↔native 桥叠加在 JNI 之上，链路翻倍 | 排除 |
| Flutter | ◻ 声明式但 Dart 生态，非 React | 同样全部走 Platform Channel | FFI 可达 mira 但服务层仍需原生 | 排除 |
| WebView + React | ✅ | 悬浮窗/服务绑定/权限流基本不可用；离线包管理额外成本 | 经 JS Bridge 二次转发 | 排除 |

用户"类 React 搭建形式"的诉求由 Compose 直接满足：它就是 Android 官方的 React 式声明
UI（组合函数 = 组件、状态驱动重组 = re-render、单向数据流）。React Native 被排除的根因
不是范式不符，而是本项目 UI 面小、宿主层重，引入 JS 运行时只增加桥接而不再生能力。

## 2. React → Compose 概念映射

团队按此映射即可用 React 心智模型开发：

| React | Compose / Android | 说明 |
| --- | --- | --- |
| Component | `@Composable fun` | 组合函数，参数即 props |
| Props | 函数参数（不可变） | 状态提升（state hoisting）原则相同 |
| `useState` | `remember { mutableStateOf() }` | 局部状态 |
| derived state | `derivedStateOf` / 表达式 | memo 化由重组跳过机制承担 |
| `useEffect(fn, [])` | `LaunchedEffect(key)` | key 变化重启的协程副作用 |
| cleanup effect | `DisposableEffect` | onDispose 对应 cleanup |
| Context | `CompositionLocal` | 少用，显式传参优先 |
| store（Redux/Zustand） | `ViewModel` + `StateFlow` | 单一事实源，`collectAsStateWithLifecycle` 订阅 |
| selector | `StateFlow.map` / `select` | 派生状态 |
| 事件回调 | lambda 参数（`onXxx`） | 同约定 |
| Portal/弹层 | `Dialog` / 独立 overlay window | 悬浮球见 §5 |
| 列表 key | `LazyColumn` `key` 参数 | 同语义 |
| Suspense | （协程挂起 + 状态建模） | 显式 Loading/Result 状态 |

## 3. UI 架构

- **单 Activity + Compose Navigation**；无 Fragment（v1 无需）。
- **单向数据流（UDF）**：UI 事件 → `ViewModel` 意图 → `AgentRuntime` 门面 → 状态流更新 →
  UI 重组。ViewModel 只做投影与编排，不承载业务规则。
- **唯一事实源**：运行时状态来自 `AgentRuntime`（架构设计 §5）；页面级局部状态允许
  `remember`。禁止 UI 自建 agent 状态副本（sealed class `SessionState` 单向传播）。
- **主题**：Material 3，深色优先（悬浮球场景多为暗背景）；动效克制，状态环动画用
  `animate*AsState`。

## 4. 主 GUI 信息架构

| 页面 | 内容 | 状态来源 |
| --- | --- | --- |
| Onboarding（首次/权限缺失时） | 显著披露（截图出设备、触控模拟）→ 分步授权向导（通知→悬浮窗→无障碍→投影）→ 每步系统返回后自检 | `SessionGate` |
| Home 任务台 | 当前会话卡（状态相位、步数、动作计数、takeover 按钮）、新目标输入、历史任务列表 | `AgentRuntime` |
| SessionDetail | 状态时间线（步进、动作摘要、验证结论）、事件流、（P4）replay 检视与截图引用 | 事件流 + 持久状态 |
| Settings | 模型端点/凭据（Keystore 加密）、预算与步数上限、R3 策略表开关、关于/版本 | DataStore |
| ConfirmationDialog | R3 动作确认：动作摘要、风险说明、剩余时间 | `ConfirmationRequest` 流 |

组件树（示意）：

```
App(theme, nav)
├─ OnboardingScreen(GateStatus)
├─ HomeScreen(SessionState, History) → NewGoalBar / SessionCard / TaskHistoryList
├─ SessionDetailScreen(StepRecords) → StateTimeline / ActionList / ReplayViewer(P4)
├─ SettingsScreen(AppConfig)
└─ ConfirmationHost(ConfirmationRequest) → ConfirmationDialog
```

## 5. 悬浮球与悬浮面板

双形态设计，兼顾常驻开销与 Compose 表达力：

- **悬浮球（常驻态，原生 View）**：`TYPE_APPLICATION_OVERLAY` 窗口 + 自绘 `View`（画布绘
  制状态环）。理由：常驻显示不需要 Compose 运行时开销；拖动、边缘吸附、长按手势用原生
  触摸处理最稳。状态环颜色映射 `SessionState`（灰=Idle、蓝=Observing/Reasoning、橙=
  Acting、绿=Completed、红=Failed/需确认、闪烁=Takeover 待恢复）。
- **展开面板（交互态，Compose）**：点击球体展开第二个 overlay 窗口，内嵌 `ComposeView`
  （为其装配 `ViewTreeLifecycleOwner`/`SavedStateRegistryOwner` 的宿主实现）。面板提供：
  快捷新目标、暂停/继续、停止、takeover、最近动作摘要。关闭即回收窗口与 Composition。
- **交互约定**：拖动移动；单击展开/收起；长按 ≥600ms = Human Takeover（立即生效，无需
  确认——安全操作不需要确认）；面板中动作按钮按风险级着色。
- **活动指示联动**：截屏/触控进行时状态环高频呼吸；与常驻通知文案同步更新（同一
  `SessionState` 源）。
- **权限缺失降级**：悬浮窗未授权时球体不可用，主 GUI 正常，设置页提供跳转。

## 6. 无障碍体验基线（作为系统公民）

app 自身 UI 满足基本无障碍语义（contentDescription、最小触控 48dp、状态不仅靠颜色），
避免"使用无障碍能力却不善待无障碍用户"的悖论；此为 lint 检查项。

## 7. 测试要求

- ViewModel 投影测试（Robolectric + Turbine 流断言）。
- Compose UI 测试：任务卡状态渲染、确认对话框流转、引导页分步状态机。
- 悬浮球：手动/仪器化路径——授权缺失、球体拖动、长按 takeover 触发 `AgentRuntime` 的
  事件断言。
- 快照稳定性：核心页面 screenshot 对比（可选，防主题回归）。
