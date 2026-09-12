# DEC-004：mira 双平面架构消费路线

> 状态：Accepted（方向冻结；落地由 P3h/P4/P5/P6 里程碑承载）
> 日期：2026-09-12
> 负责人：Miracle Maintainers
> 冻结里程碑：P6（双平面消费完整落地；P3h 先行承接 harness 契约对齐）
> 替代/被替代：无（细化 [DEC-002 工具集路线](DEC-002-agent-tool-set-route.md) 的上游
> 边界前提，不推翻其结论）

## 背景与问题

miracle 锁定的 mira `874f4a5`（2026-09-07）之后，上游冻结并交付了重大架构演进：

- [DEC-014](https://github.com/Linductor-alkaid/mira/blob/master/docs/decisions/DEC-014-agent-harness-workflow-dual-plane.md)
  确立双平面架构：Agent Harness 为常驻控制平面，Workflow Runtime 为执行数据平面，
  任务可在两平面间切换。
- M8–M14（PR #29–#34、#38）交付 Workflow 全链路：IR 契约、Strict/DryRun 运行时、
  策略全集与对话 patch、轨迹编译与任务归纳、App Model 与导航、记忆域与学习环、
  恢复编排（DEC-019~031）；Android 双 ABI 编译与安装包 consumer 门禁已进 CI
  （`BUG-20260909-001` 修复），但**设备运行与宿主消费证据为零**（mira
  `MNT-202609-27` 跟踪，miracle 为指定证据来源）。
- DEC-015/016/017/018 关闭 harness 缺口：`BuiltinToolRegistry`（本仓库台账
  `MIR-20260905-002` 关闭）、步边界用户消息与会话投影、`complete_task` 终态命令、
  takeover 触发 `IEnvironment::interrupt()` 与暂停态操作准入收紧。
- DEC-032 冻结分层上下文方向（Hot/Warm/Cold + 五层管线），实现未开始；其 Stage E
  验收显式依赖 miracle 真机证据通道。

miracle 需要决定：以何种顺序与范围消费上述能力，避免范围失控，同时履行 DEC-011
验证载体义务。

## 决策

1. **采纳双平面为消费架构方向**：miracle 的目标形态对齐 mira DEC-014——Agent
   路径负责探索/未知任务/恢复，Workflow 路径承接已验证任务的确定性执行；不 fork、
   不在仓库内自建第二套工作流设施。
2. **v1 产品核心保持 Agent-native 闭环**（P3/P3x/P4 范围不变）：双平面消费是能力
   增量而非范围替换；P3 已冻结的范围与非目标不因上游演进修编。
3. **harness 契约对齐先行（P3h）**：lock 升级至 `5b55e14`，最小采纳 AgentLoop 级
   新契约——DEC-016 用户消息介入（含宿主脱敏义务）与 DEC-015 工具注册表链路验证；
   全量门禁与真机回归后关闭 `MIR-20260905-002` 的"关而未用"状态。
4. **`MiraRuntime` 控制面自 P4 消费**：P3 的 AgentLoop 直驱模式迁移到 runtime 任务
   生命周期（session/task/命令准入/检查点），随之落地 DEC-017 `complete_task`
   （成功任务不再滞留或以 Cancelled 收尾）与 DEC-018 takeover 语义（native 侧
   `interrupt()` 释放、暂停态 `begin_operation` 拒绝路径适配）。
5. **Workflow 平面立项为 P6（Proposed）**：设备侧首次消费 mira Workflow 模块——
   轨迹编译/DryRun/Strict 执行、升级 → `WaitingAgent` 的宿主处置 UI、恢复编排器
   装配（DEC-031 宿主义务：`notify_escalation`/`record_recovery_lesson`/Executor
   worker 预算）、lesson 记录；前置依赖 P4（跨重启与检查点）。
6. **P5 吸收 mira `MNT-202609-27` 消费证据义务**：固定 mira 版本上的宿主消费报告
   （host tree、图像请求、决策修复复验、旋转/前后台/权限撤销/takeover/宿主销毁
   矩阵、A–F 组合任务）直接支撑 mira M7 重定义提案（`MNT-202609-30`）与评估基线
   （28/29）。
7. **DEC-032 只做方向跟踪**：mira 实现落地前不在 miracle 内预建语义压缩设施；
   Stage E 真机实测（内存/延迟/功耗默认值）待 mira 公共契约就绪后立项；遵守其
   非目标（不打包大型生成模型）。

## 备选方案

- **维持单平面消费（不采纳 Workflow）**：否决。mira 已把 Workflow 定为一等公民并
  冻结方向，miracle 作为 DEC-011 验证载体不消费等于放弃核心反馈通道；且已验证
  任务的低成本复执行正是真机任务成功率问题的长期解法之一。
- **立即全面切换双平面**：否决。Workflow 模块零设备证据、miracle 的状态持久化
  （P4）与验证报告（P5）尚未交付；越级消费会同时放大回归面与取证盲区。
- **在 miracle 内自建轻量脚本/工作流层**：否决。违反 mira 消费边界（AGENTS.md：
  公共 API 无法满足的需求登记台账回流），且重复 M8–M14 已交付能力。

## 影响与风险

- **范围增长**：总计划新增 P3h 与 P6 两个里程碑；P4/P5 范围各有一处明确扩展
  （runtime 控制面消费、MNT-27 证据归档）。
- **Workflow 设备证据为零**（mira 侧仅编译级 CI）：P6 首个消费轮大概率暴露契约
  分叉（ Simulator/Android 差异先例：MIR-008/009/010）；缓解：DryRun 先行、缺口
  走 `MIR-` 台账、不宣称未经真机验证的能力。
- **lock 升级回归面**：874f4a5 → 5b55e14 含公共头文件扩展（`agent_loop.hpp`
  构造面、`build_request` 签名）与 `CommandKind`/事件闭集追加；按工程规范 §9.1
  走独立变更并重跑全量门禁。
- **DEC-016 脱敏义务落在宿主**：用户消息入队前须经 miracle 数据策略清理（凭据、
  输入法敏感内容）；UI 输入路径与日志两侧都要覆盖，负向测试跟进。
- **DEC-031 宿主装配成本**（P6）：Executor worker 预算需覆盖
  `max_concurrent_attempts + max_concurrent_async_drives`；`need_user`/预算耗尽
  出口需要宿主处置 UI，与同意/确认边界（SessionGate）衔接。

## 验证方式

- P3h：lock 升级后全量门禁绿；真机回归（闭环任务 + 用户消息注入 + `wait` 工具
  执行）取证；`MIR-20260905-002` 采纳证据归档。
- P4：`complete_task` 后任务终态 `Completed`（重复 NoOp、冲突拒绝）；takeover 触发
  native `interrupt()` 恰好一次、暂停态操作登记按 `InvalidState` 拒绝——契约测试
  覆盖。
- P5：MNT-202609-27 清单逐项归档（mira 与 miracle commit、设备、Provider、结果）。
- P6：DryRun → Strict → 升级 → 恢复 → lesson 记录闭环的真机证据；`WorkflowRun`
  取消/终态幂等/迟到完成隔离在宿主侧复核。

## 关联文档和工作项

- mira 决策：DEC-014（双平面）、DEC-015/016/017/018（harness 契约）、
  DEC-019~031（Workflow M8–M14）、DEC-032（分层上下文方向）。
- 设计：[总体架构设计 §10 双平面消费路线](../design/system_architecture_design.md)。
- 计划：[P3h mira Harness 对齐](../plans/p3h-mira-harness-alignment.md)、
  [实施总计划 §5 里程碑索引](../plans/miracle-implementation-plan.md)。
- 台账：`MIR-20260905-002`（Resolved，采纳由 P3h 承载）。
