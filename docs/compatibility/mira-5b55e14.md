# mira 消费兼容性证据：0.1.0 @ 5b55e14

> 状态：Active
> 更新日期：2026-09-12
> 适用范围：`tools/mira.lock` 钉死版本在本仓库的构建与运行消费证据
> 前置版本：[mira @ 874f4a5（P3 消费基线）](mira-16e419e.md)（同文件历史记录覆盖
> 16e419e→cbed6ad→635e136→d1993d2→874f4a5 链路，P3 验证记录内有逐次升级记录）

## lock 升级记录（工程规范 §9.1）

- 旧 commit：`874f4a51117fe87ba73f7706b1ef912a7c3ab269`（2026-09-07 锁定）
- 新 commit：`5b55e1439236403fca831297012bcd6c1f7fc74f`（2026-09-12 上游 master）
- 跨度：49 个提交；动机＝P3h 契约对齐（[DEC-004](../decisions/DEC-004-mira-dual-plane-consumption.md)
  阶段一）：mira DEC-014 双平面架构落地（M8–M14）+ DEC-015/016/017/018 harness
  契约关闭。
- 升级方式：独立变更 + 全量门禁回归（`assembleDebug` + `lintDebug` +
  `testDebugUnitTest` + native 编译零警告）+ 真机回归（P3h-06，OnePlus Ace 3）。

## 公共 API 差异清单（`git diff 874f4a5..5b55e14 -- include/mira`）

新增头文件 12 个（miracle 仅消费前两个，其余为 Workflow 平面前瞻、P6 消费）：

| 头文件 | 内容 | miracle 消费 |
| --- | --- | --- |
| `tool_executor.hpp` | `BuiltinToolSpec`/`BuiltinToolHandler`/`BuiltinToolRegistry`（DEC-015 BuiltIn 执行边界：fail-closed 派发、at-most-once per OperationId）、参考工具 `make_wait_tool()` | **P3h-05**：注册 `wait`、`AgentLoop::set_tool_registry` |
| `conversation_log.hpp` | `ConversationEntry`（UserMessage/LoopOutcome）、`build_conversation_view`（DEC-016 只读投影，事件存储为唯一事实源） | **P3h-04**：会话时间线投影 |
| `workflow_compiler.hpp`/`workflow_events.hpp`/`workflow_ir.hpp`/`workflow_learning.hpp`/`workflow_navigation.hpp`/`workflow_recovery.hpp`/`workflow_run.hpp`/`workflow_runtime.hpp`/`workflow_tools.hpp`/`workflow_versioning.hpp` | Workflow 平面（DEC-014/M8–M14：IR 契约、Strict/DryRun 运行时、策略与对话 patch、轨迹编译、App Model、记忆域、恢复编排、版本升级） | P6（前置 P4）；本轮仅编译级兼容 |

既有头文件变更（全部非破坏性演进）：

| 头文件 | 变更 | 对 miracle 的影响 |
| --- | --- | --- |
| `agent_loop.hpp` | `AgentLoopConfig` 追加 `max_tool_executions=32`/`max_pending_user_messages=16`（均有默认值）；`AgentLoop` 追加 `set_tool_registry`/`enqueue_user_message`（线程安全邮箱，步边界 drained、常驻后续请求）；`build_request` 私有签名扩展（`user_instructions`/`tool_results` 参数） | 无源码级破坏：miracle 不直接调用 `build_request`（仅 mira 内部）；新能力经 P3h-03/05 采纳 |
| `core_contracts.hpp` | `CommandKind` 追加 `CompleteTask`/`BeginTaskRecovery`；追加 `WorkflowId`/`WorkflowRunId`/`WorkflowPatchId`/`WorkflowDecisionId` ID 类型 | 无：miracle 无 `CommandKind` 穷举 switch；新命令属 `MiraRuntime` 控制面（P4 消费） |
| `runtime.hpp` | `MiraRuntime` 追加 `begin_task_recovery`/`complete_task`（DEC-023/017） | 无：miracle 未消费 `MiraRuntime`（P4 起消费） |
| `environment.hpp` | `OperationContext` 注释澄清（取消探测不得回调 runtime，防锁序倒置） | 无代码变更；与 bridge 既有取消探测实现（标志位轮询）一致 |

## 消费方式

- 安装链路不变：`tools/install-mira.sh`（本地克隆优先，精确 checkout，preset
  `android-arm64-release`，PIC 全局开启）。
- P3h 起新增链接闭包：`Mira::core` 继续覆盖 `agent_loop`/`conversation_log`/
  `tool_executor`（同一静态库，无新增 link target）。

## 证据记录

| 日期 | 环境 | 命令/结果 | 等级 |
| --- | --- | --- | --- |
| 2026-09-12 | Ubuntu 24.04 x86_64；NDK 26.3.11579264（`~/Android/Sdk`）、JDK（`/home/linductor/jdk`） | `tools/install-mira.sh --force` 全新构建安装通过；`MiraConfig.cmake` 落位 | Build verified（本仓库消费路径） |
| 2026-09-12 | 同上 + Gradle 8.10.2/AGP 8.7.3 | `./gradlew assembleDebug lintDebug testDebugUnitTest` 全绿（P3h 适配后，含 P3h-03/04/05 消费面编译与 JVM 单测 73/73） | Build verified（安装包 → APK 链路） |
| 2026-09-12 | OnePlus Ace 3（PJE110，`a4dfdcbf`），ColorOS | P3h-06 干跑矩阵六场景全绿（complete/MaxSteps/cancel/user_message 四路径/tool/预算耗尽）+ R3 确认协议 4×approved + 悬浮球 takeover；证据 `build/p3-device-evidence/`。真实任务路径存在挂起缺陷（`BUG-20260912-P3H-01`，未定性为上游缺口） | Runtime verified(device)（干跑/确认/takeover 面） |

## 已知限制

- Workflow 平面（10 个新头）在本仓库无任何消费；不宣称任何 Workflow 设备能力
  （RISK-2026-03）。
- 真机回归（P3h-06）未执行前，本版本变更不标记完成（P3h 计划退出条件）。
