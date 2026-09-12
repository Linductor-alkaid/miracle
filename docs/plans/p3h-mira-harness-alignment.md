# P3h：mira Harness 对齐（lock 升级 + 契约采纳）

> 状态：Planned
> 负责人：Miracle Maintainers
> 所属计划：[Miracle 实施总计划](miracle-implementation-plan.md)
> 前置：P3（In Progress：代码完成、首轮真机闭环通过；P3h 依赖其代码基线，不阻塞于
> P3 的"≥3 类真实任务"取证欠账）
> 建议发布点：`v0.3.2`
> 更新日期：2026-09-12

## 目标

把 miracle 的 mira 消费基线从 `874f4a5` 对齐到上游 `5b55e14`（2026-09-12 master），
并以最小范围采纳 AgentLoop 级 harness 新契约（[DEC-004](../decisions/DEC-004-mira-dual-plane-consumption.md)
阶段一）：

1. lock 升级独立变更 + 全量门禁回归（公共头扩展、`CommandKind`/事件闭集追加均为
   非破坏性演进，但须实证编译与行为无回归）。
2. DEC-016：任务运行中用户消息介入（`enqueue_user_message`）与会话只读投影
   （`build_conversation_view`）——miracle 首个"运行中介入"产品能力。
3. DEC-015：工具注册表链路验证（注册 Core `wait` 参考工具），关闭台账
   `MIR-20260905-002` 的"关而未用"状态。

## 范围与非目标

范围：`tools/mira.lock` 升级与安装重建；`loop_runtime` 编译适配；bridge/JNI 新入口
（用户消息入队、对话视图读取）；`AgentRuntime` 门面 API 扩展；任务页消息输入与
时间线呈现；入队前脱敏策略（长度上限 + 凭据模式过滤 + 日志摘要化）；`wait` 工具
注册与干跑验证；单测与真机回归。

非目标：`MiraRuntime` 控制面消费与 `complete_task`/DEC-018 takeover 语义（P4）；
Workflow 平面（P6）；L3 扩展工具集（`POST-01`，本轮只验链路不扩工具）；UI 树
grounding（P3x）；DEC-032 语义压缩设施（`POST-05`）。

## 设计与决策依据

- [DEC-004 mira 双平面消费路线](../decisions/DEC-004-mira-dual-plane-consumption.md)；
  mira 侧 [DEC-015](https://github.com/Linductor-alkaid/mira/blob/master/docs/decisions/DEC-015-builtin-tool-execution-boundary.md)、
  [DEC-016](https://github.com/Linductor-alkaid/mira/blob/master/docs/decisions/DEC-016-conversation-events-and-user-messages.md)。
- 实现要点（P3 现状约束）：
  - `AgentLoop` 实例当前在 `loopSubmit` 的 run 闭包内局部构造（`loop_runtime.cpp`）；
    `enqueue_user_message` 需要存活实例——将会话内 loop 提升为受控持有（Executor
    纪律下登记/注销，不引入新线程），或经 `LoopRuntime` 暴露有界消息邮箱由 run 边界
    转发。取向：**邮箱转发**（bridge 自持 `std::deque` + 互斥，run 启动时挂接），
    避免 loop 实例逃逸出 Executor 生命周期；设计实现时二选一并记录。
  - 消息语义：步边界取出后**常驻后续所有请求**（上游契约）；UI 呈现为"已注入指令"
    而非单轮聊天消息，避免用户误解。
  - 队列有界（默认 16）：溢出拒绝必须对 UI 可见（明确结果，不静默丢弃）。
  - `wait` 工具：Core 自带（有界切片睡眠、可取消、无副作用），宿主显式注册即可；
    干跑 transport 注入一次 `wait` 决策验证 `ToolExecuted` 事件（载荷脱敏断言）与
    `mira.agent-loop.tool-result.v1` 回填。
- lock 升级记录要求（工程规范 §9.1）：旧新 commit、公共 API 差异清单（`git diff
  874f4a5..5b55e14 -- include/mira`）、受影响范围与回归结果。

## 工作项

- [ ] `P3h-01` lock 升级：`tools/mira.lock` → `5b55e14`；`install-mira` 重建；
      公共 API 差异清单归档（新头 12 个：`tool_executor`/`conversation_log`/
      `workflow_*`；`agent_loop.hpp` 新增配置字段与 `enqueue_user_message`；
      `runtime.hpp` 新增 `begin_task_recovery`/`complete_task`；`CommandKind` 追加
      `CompleteTask`/`BeginTaskRecovery`）。
- [ ] `P3h-02` 编译适配与门禁：`loop_runtime`/`runtime_glue` 适配；`assembleDebug` +
      `testDebugUnitTest` + `lintDebug` + native 契约测试全绿；既有干跑矩阵不回归。
- [ ] `P3h-03` 用户消息介入（DEC-016）：bridge 邮箱/JNI 入口 → `AgentRuntime`
      门面（`suspend fun sendUserInstruction(text)`）→ 任务页输入 UI；入队前脱敏
      （长度上限、凭据模式过滤）；日志只记长度与 digest；溢出/空消息拒绝路径对 UI
      可见。
- [ ] `P3h-04` 会话投影（DEC-016）：`build_conversation_view` 接入会话详情时间线
      （只读；`UserMessage`/`LoopOutcome` 两类条目）；与既有步进记录并存的呈现规则。
- [ ] `P3h-05` 工具链路验证（DEC-015）：注册 `wait`；干跑 transport 注入工具调用
      决策；断言 `ToolExecuted` 事件（无原始载荷）与下一轮请求回填；预算耗尽终态
      路径测试（`max_tool_executions=1` 配置下第二次调用失败）。
- [ ] `P3h-06` 真机回归：PJE110 重跑 P3 同场景闭环（≥1 类真实任务至终态）+ 用户
      消息运行中注入实测（注入后模型请求可见指令）+ `wait` 真机执行；证据归档；
      台账 `MIR-20260905-002` 采纳取证回写。

## 风险与阻塞

- `RISK-2026-04`（总计划）：lock 升级回归面——49 个提交跨度，全量门禁 + 真机回归
  兜底；发现契约分叉即登记 `MIR-` 台账。
- loop 实例生命周期改造触及 Executor 纪律：设计选型（邮箱 vs 实例持有）在实现前
  评审；取消/shutdown 中入队必须转化为明确拒绝结果。
- 用户消息改变模型行为的不确定性（指令冲突/诱导）：v1 仅呈现"已注入"，不承诺指令
  生效；高风险动作仍走 R3 确认（消息不是授权，对齐同意边界）。

## 测试与退出条件

- [ ] lock 指向 `5b55e14` 且安装可复现（脚本 + lock）。
- [ ] 全量门禁绿（单元 + lint + native 契约）；干跑矩阵（完成/MaxSteps/取消/
      Takeover）不回归。
- [ ] 用户消息：正常注入（请求可见、事件 `UserMessageInjected`）、空消息拒绝、
      队列满拒绝、取消/shutdown 中入队明确拒绝——四路径单测。
- [ ] `wait` 工具：干跑链路 `ToolExecuted` 事件 + 回填断言；取消传播测试。
- [ ] 真机（OnePlus Ace 3）：闭环回归 + 消息注入 + `wait` 执行证据归档（mira 与
      miracle commit、场景、结果、限制）。
- [ ] 台账 `MIR-20260905-002` 关闭注记补"采纳取证"引用；本计划与总计划状态同步。

## 验证记录

（待实施；按日期追加。）
