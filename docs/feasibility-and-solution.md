# Miracle 项目可行性与方案分析

> 日期：2026-09-05
> 基准：mira `0.1.0` @ commit `16e419e0c5b3c634885d97aebe54bc0497b609c1`（master，2026-09-05）
> 状态：分析稿，待评审

## 1. 结论

**项目可行，且定位与 mira 的既有决策直接契合。** mira 的 DEC-011 已明确"能力验证与需求发现
由独立仓库中的 demo 产品承载，demo 只通过安装后的公共包接口消费 mira"。miracle 正是这个
demo 仓库：Android 应用（主 GUI + 悬浮球）作为 Android Host，实现 Host ABI v1，把截屏与
触控注入能力接入 mira Core，由 mira 驱动 `Observe → Reason → Plan → Act → Verify` 闭环。

可行性成立的前提与边界：

1. **mira Android 侧目前只有构建证据、零真机运行证据**（platform-matrix 明示 `Build verified`
   不外推为运行支持）。本项目 P0–P2 的首要任务就是产出第一批真机证据，集成摩擦是预期内的
   主要不确定性。
2. **v1 只能做"视觉 + 离散动作"闭环**。UI 树观察、连续控制（M6 已取消）、本地感知（M5 已
   取消）、闭环内自定义工具执行在 mira 当前公共 API 中不可用或未交付，见 §2.2。
3. **合规与告知是产品需求而非附属品**。"已通知用户后获取屏幕信息并模拟触控"对应 Android
   的三项特殊授权（无障碍、媒体投影、悬浮窗）+ 前台服务常驻通知 + 显著披露，均有成熟实现
   路径，但 UX 流程必须作为一等设计对象。

## 2. 现状盘点（以 mira 仓库证据为准）

### 2.1 mira 已提供

| 能力 | mira 证据 | 对本项目的意义 |
| --- | --- | --- |
| 公共契约、任务状态机、EventStore、安全边界 | M0/M1 已交付；DEC-004 确认协议 | 宿主注入 Principal/grants；高风险动作需结构化确认，不允许模型自证同意 |
| Android Host ABI v1（冻结纯 C ABI） | `include/mira/adapters/android/host_abi.h`；`docs/compatibility/android-host-abi.md` | 本项目 Kotlin/JNI 侧要实现的全部边界：`capture_frame` / `get_ui_tree` / `dispatch_input` 异步操作、buffer lease、epoch 失效、协作取消、exactly-once 终态回调 |
| Native Android Adapter 骨架 | `adapters/android/`（`AndroidHostAdapter` + `HostDispatcherBridge`），fake host 契约测试全绿 | app 的 .so 导出 ABI 符号即可被 adapter 消费，无需改 mira |
| AgentLoop（离散闭环） | `include/mira/agent_loop.hpp`；M3 alpha | `run()` 驱动观察→推理→动作→验证；decision schema 冻结；`compile_discrete_action` 产出归一化坐标输入序列 |
| OpenAI-compatible Provider + ModelGateway | M3；`OpenAiCompatibleProvider` 支持 Responses/Chat Completions 双方言 | 云端 VLM 接入；重试/熔断/预算/准入内建 |
| 传输层 | `Mira::net_transport` + `Mira::mbedtls_transport`，Android arm64 Build verified | 设备侧 HTTPS 出站；fail-closed TLS 语义（DEC-010） |
| 有状态底座 | M4：SQLite state_store（WAL/FTS5）、Checkpoint、崩溃恢复、Replay、`ContextMemorySupervisor` | 任务断点续跑、事件回放检视页 |
| 安装包消费 | `find_package(Mira 0.1)`；`examples/minimal_consumer.cpp`、`stateful_agent_consumer.cpp` | 唯一合法集成方式；demo 不 fork mira 源码 |
| Android 交叉构建 | NDK 26.3.11579264、API 24、`android-arm64-release` preset，CI 复验 | NDK 下限 API 24 ≤ app minSdk；应用 SDK 基线（minSdk 26/target 35/compile 36）见[构建打包设计](design/build_packaging_design.md) 定稿 |

### 2.2 mira 未提供（直接约束方案）

| 缺口 | 证据 | 对 v1 的影响 |
| --- | --- | --- |
| Android 真机运行证据为零 | platform-matrix：Android 全线 `Build verified` / `Boundary checked`；android-host-abi.md §3 明示"真实 Android Host（Kotlin/JNI）互操作：未验证" | 首批集成问题（JNI 线程、TLS/CA、SQLite 路径、DNS）由本项目发现并回流 |
| Adapter 观察仅支持 screen 组件 | `android_host_adapter.cpp`：`capabilities()` 不采纳 `accessibility_completeness`；`observe()` 对非 screen 请求 fail closed（"M2 host skeleton only captures screen components"） | v1 决策纯靠截图+VLM，无 UI 树 grounding；此缺口按 DEC-011 作为"公共 API 表面不足"证据登记回流 |
| AgentLoop 内工具提案不可执行 | `agent_loop.cpp`："tool proposals are not executable in the M3 loop"；DEC-009/M7 Blocked | "平台工具层" v1 = 宿主能力层（截屏/触控）+ 离散动作协议；扩展工具见 §7 备选路径 |
| 连续控制 | M6 已取消（DEC-011） | 无摇杆/轨迹类输入；`swipe` 为离散手势 |
| 本地感知 | M5 已取消（DEC-011） | OCR/目标定位完全依赖云端 VLM，每步都有网络往返 |
| API 稳定性 | mira 0.x（`SameMajorVersion`），无稳定性承诺 | 钉死 mira commit，升级走显式变更并重跑回归 |

## 3. 产品形态

- **主 GUI（Compose）**：权限引导页、任务台（输入目标/查看进行中任务）、实时状态机视图
  （Idle/Observing/Reasoning/Acting/Verifying/…）、事件时间线、设置（模型 endpoint/key、
  步数与预算上限、确认策略）。
- **悬浮球（SYSTEM_ALERT_WINDOW）**：全局入口；状态环颜色映射 agent 状态；点按展开快捷
  面板（新任务/暂停/停止）；长按 = Human Takeover（取消任务、`RELEASE_ALL`、阻断新动作）。
- **平台工具层**：宿主能力（截屏、触控注入、UI 树预留）经 Host ABI v1 注入 mira；LLM 可
  调用的动作集为 `tap / long_press / swipe / type / back / home`（+系统级 `release_all`）。
- **告知与同意**：见 §5.3，对齐 DEC-004（宿主是确认权威，Core 只验证协议）。

## 4. 总体架构

```
┌─ 表现层 ──────────────────────────────────────────────┐
│  主 GUI (Compose Activity)        悬浮球 (Overlay)     │
└───────────────┬───────────────────────────────────────┘
                │ bind / StateFlow
┌───────────────▼───────────────────────────────────────┐
│ 宿主服务层 (Kotlin)                                    │
│  AgentForegroundService（常驻通知、生命周期）           │
│  MediaProjection 截屏管线（授权 UX、ImageReader→RGBA）  │
│  AccessibilityService（dispatchGesture、前台应用、事件）│
│  同意/披露/接管 UX · 能力注册表 · PrincipalContext 注入 │
└───────────────┬───────────────────────────────────────┘
                │ JNI（JavaVM ↔ native）
┌───────────────▼───────────────────────────────────────┐
│ JNI Host Bridge（C++，本仓库 libmiracle_host.so）       │
│  实现 host_abi.h 全部导出符号（create/start/stop/…）    │
│  Executor 外部 owner（DEC-001 初始化/关闭顺序）         │
│  对 Kotlin 暴露运行时入口：init / submitGoal / takeover │
├────────────────────────────────────────────────────────┤
│ Mira NDK 库（安装包消费，钉死 0.1.0）                    │
│  AndroidHostAdapter ─ AgentLoop ─ ModelGateway          │
│        │                                    │          │
│  capture_frame/dispatch_input        HTTPS (mbedtls)    │
└────────┼────────────────────────────────────┼──────────┘
         ▼                                    ▼
  MediaProjection/Accessibility        OpenAI 兼容 VLM 服务
```

一次闭环数据流：

1. 用户在 GUI/悬浮球提交目标 → 宿主建 Session、注入 grants → `AgentLoop.run()`。
2. Observe：`AndroidHostAdapter.observe()` → ABI `capture_frame` → Kotlin MediaProjection
   VirtualDisplay + ImageReader → RGBA buffer lease → native 有界拷贝（旋转经 epoch 失效）。
3. Reason/Plan：`ModelGateway.infer()` 截图+目标+历史 → VLM 返回 decision（冻结 schema）。
4. Act：`compile_discrete_action` 校验归一化坐标 → ABI `dispatch_input` → Kotlin
   `AccessibilityService.dispatchGesture`（`type` 走无障碍文本注入）。
5. Verify：重新观察，`ILoopVerifier` 判定；未满足则继续，直至完成/失败/步数上限/取消。

建议目录结构：

```
miracle/
├── app/                         # Android 应用（Kotlin + Compose）
│   └── src/main/
│       ├── kotlin/…/            # ui/ overlay/ service/ accessibility/ projection/ consent/
│       └── cpp/                 # host_abi_impl、jni_bridge、CMakeLists（find_package(Mira)）
├── docs/                        # 本文档、验证计划与证据（DEC-011 要求）
└── tools/                       # mira 预安装脚本（install prefix 管理，钉死 commit）
```

## 5. Android 平台能力与合规

### 5.1 权限与机制清单

| 机制 | API 级别 | 用途 | 获取方式 |
| --- | --- | --- | --- |
| `INTERNET` | — | 模型服务调用 | 清单声明 |
| `SYSTEM_ALERT_WINDOW` | — | 悬浮球 | 特殊应用授权（`Settings.canDrawOverlays()` + 引导页跳转） |
| `FOREGROUND_SERVICE`（+`SPECIAL_USE` / `MEDIA_PROJECTION` 类型） | 34+ 强制类型声明 | agent 常驻前台服务；Android 14 起媒体投影必须先启动对应类型 FGS | 清单 + 运行时启动顺序 |
| `POST_NOTIFICATIONS` | 33+ | 常驻通知 | 运行时弹窗 |
| AccessibilityService（`canPerformGestures`，`isAccessibilityTool`） | 24+（手势注入恰为 24） | 触控/文本注入、前台应用事件 | 用户在系统设置手动启用，app 引导检测 |
| MediaProjection | 21+ | 屏幕采集 | 每会话系统授权对话框；Android 14 需 FGS 先行 |

### 5.2 平台硬限制（一律 fail-closed，映射为 mira 的 `PermissionDenied` / `InvalidObservation`）

- `FLAG_SECURE` 页面截屏为黑帧；安全键盘、锁屏下输入注入受限。Verify 阶段检测黑帧/无变化
  并走 Recovery，不静默重试。
- 无障碍手势无法操作部分系统安全 UI；`type` 文本注入优先无障碍文本动作，失败再降级逐键
  手势。
- 厂商后台管控差异：不承诺保活，以前台服务 + 引导页告知为准。
- 分发：Play 对非无障碍用途使用 AccessibilityService 有申报与显著披露要求。demo 阶段以
  侧载（apk 直发）为主，Play 上架是独立评估项，不阻塞当前目标。

### 5.3 告知与同意设计（用户要求"已经通知用户"的落地）

1. **常驻前台通知**：会话期间不可清除，实时显示状态机相位与已执行动作计数。
2. **活动指示**：截屏/触控进行时悬浮球状态环变色，可选屏幕边缘呼吸描边。
3. **首次显著披露**：明确告知"屏幕内容将发送至你配置的模型服务；触控将被模拟执行"，
   未确认不进入授权流程。
4. **Human Takeover**：悬浮球长按或通知按钮 → 取消任务 + `RELEASE_ALL` + 阻断新动作，
   恢复前强制重新观察（对齐 mira 状态机 Takeover 语义）。
5. **高风险动作确认**（DEC-004）：支付/删除/发送/凭据输入类目标默认
   `RequireConfirmation`，宿主弹结构化确认（绑定 action digest 与 nonce），确认只授权
   被展示的单个动作。

## 6. 关键风险与对策

| # | 风险 | 等级 | 对策 |
| --- | --- | --- | --- |
| R1 | mira Android 真机零证据：JNI 线程模型、mbedtls CA/DNS、SQLite 落盘路径均可能踩坑 | 高 | P0/P1 先以最小链路（加载库→baseline→单帧截图）验证；问题按 DEC-011 登记回流 mira，不在 demo 内绕过 |
| R2 | 误动作副作用（误触支付/删除） | 高 | DEC-004 确认协议 + 动作白名单起步 + 每步 Verify + Takeover 随时可触发 |
| R3 | 视觉-only 决策可靠性不足（无 UI 树 grounding） | 中 | P5 按失败分类法量化归因；UI 树缺口作为公共 API 证据回流 mira 重开里程碑 |
| R4 | 每步上传整屏截图的延迟与成本 | 中 | 帧降采样/JPEG 压缩（宿主侧，进 lease 前）；`ModelGateway` 预算闸门与 `max_steps` 上限；指标进验证报告 |
| R5 | 三项特殊授权的引导流失 | 中 | 引导页逐步检测 + 状态自检 + 能力降级声明（无投影则只读 UI 树路径不可用时明确报错，不假装可用） |
| R6 | mira 0.x API 变动 | 中 | 钉死 commit `16e419e`；`tools/` 内安装脚本固化；升级单独评审 |
| R7 | 输入法文本注入兼容性（各家 IME） | 低 | `type` 双路径（文本动作优先，手势打字兜底）；失败计数进报告 |

## 7. v1 范围与非目标

**做**：单屏单任务串行；视觉离散动作闭环；主 GUI + 悬浮球双前端；截屏/触控全程告知与
一键接管；checkpoint/崩溃恢复；事件时间线与 replay 检视。

**不做（明确排除）**：连续控制（M6 已取消）；本地感知模型（M5 已取消）；多显示/多任务
并行；Play 商店上架；闭环内自定义工具执行。

**关于"平台工具端供 LLM 调用"的边界说明**：mira 当前闭环的"工具协议"就是冻结的
decision schema——LLM 每步可调用的原语即 `tap/long_press/swipe/type/back/home` 加观察。
若后续需要更多平台工具（打开应用、剪贴板、通知读取等），有两条合规路径：
(a) 绕过 `AgentLoop` 直接驱动 `ModelGateway`，由本仓库自管编排与验证——公共 API 允许
（`ModelRequest.tools` 存在），但闭环语义自负；建议在核心闭环验证稳定后作为扩展层引入。
(b) 将需求登记为 mira issue 推动闭环内工具执行落地（DEC-009/M7 重定义）。
推荐顺序：先用 AgentLoop 跑通核心价值，再评估 (a) 的引入时机。

## 8. 分阶段计划与验收标准

| 阶段 | 内容 | 验收标准 | 估时 |
| --- | --- | --- | --- |
| P0 骨架与消费验证 | 仓库初始化；mira 安装与版本钉死；Gradle+NDK 工程；JNI 加载；Executor supervised 生命周期（外置 owner、§17.2 关闭顺序）；空 GUI/悬浮球占位 | app 真机启动完成 mira `RuntimeBaseline` 初始化与干净关闭；无 std::thread/私有线程 | 1–2 周 |
| P1 截屏链路 | MediaProjection 授权 UX；`capture_frame` ABI 实现；ImageReader→lease→RGBA；旋转 epoch | 真机截图进入 native 解出有效帧；lease 恰好一次释放；结果回填 mira `android-host-abi.md` 证据表 | ~2 周 |
| P2 输入链路 | `dispatchGesture` 全动作集；协作取消；`RELEASE_ALL`；输入安全矩阵（设计文档 15.2/15.3 对应项） | 真机通过输入 contract tests；取消/不可中断路径按 `EXECUTION_UNCERTAIN` 语义结算 | ~2 周 |
| P3 闭环 MVP | AgentLoop + 模型配置；任务台/事件流；悬浮球全功能；披露/通知/接管 | ≥3 类真实任务端到端完成；Takeover 与步数上限路径演练通过 | 3–4 周 |
| P4 有状态 | state_store 落盘；崩溃恢复；replay 检视页 | 进程被杀后任务可恢复续跑；事件回放只读不重放副作用 | ~2 周 |
| P5 验证报告 | 失败分类法（感知/延迟/模型/任务建模）量化指标 | 报告注明 mira 版本；缺口清单转化为 mira issue | 1–2 周 |

单人全职估算约 3–4 个月。每阶段完成必须同步更新本文档状态与 docs/ 证据。

## 9. 证据回流机制（对齐 DEC-011）

- 本仓库只经 `find_package(Mira)` 公共包消费 mira，不 fork、不绕过公共 API；无法满足的
  需求（当前已知：UI 树观察、闭环内工具执行）登记为"公共 API 表面不足"证据，回流为
  mira issue/计划项。
- 每份验证报告注明所基于的 mira 版本/commit。
- mira 升级为显式变更：更新 `tools/` 钉死版本 → 重跑 P0–P2 验收 → 记录差异。

## 10. 开放问题（待产品决策）

1. 首个验证周期的目标设备档位与 Android 版本基线（建议：一台 API 34+ 主流真机 + 模拟器
   矩阵）。
2. VLM 选型与每任务预算上限的默认值（影响 R4 指标基线）。
3. 扩展工具层路径 (a) 的引入时机（P3 后评估 or P5 后）。
4. 悬浮球交互细节（收起态尺寸、展开面板布局）留待 P3 原型迭代。
