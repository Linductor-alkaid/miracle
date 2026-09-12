# Miracle 实施总计划

> 状态：Active
> 版本：1.1
> 更新日期：2026-09-12
> 前置分析：[可行性与方案分析](../feasibility-and-solution.md)
> 规范：[项目管理与工程规范](../project/project-standards.md)

## 1. 当前状态

`In Progress`。文档基线已建立；**P0、P1、P2 已完成**（P2：输入链路全通——
dispatchGesture 全集经 mira adapter 与直接 ABI 探针双轨验证，协作取消按原子语义
`EXECUTION_UNCERTAIN+side=1` 结算，输入安全矩阵（负向/超时/取消/home/旋转）真机
取证，2026-09-06；上游缺口 `MIR-20260906-005` 登记）。
**P3（闭环 MVP）实施完成、真机取证待补跑**（2026-09-06：AgentLoop 组装 + 宿主
IHttpTransport + R3 确认协议 + 双前端全功能交付；单测 48/48、全门禁绿；真机干跑
矩阵/连通性/悬浮球取证与"≥3 类真实任务"因无设备连接延后，补跑条件见
[p3-loop-mvp.md](p3-loop-mvp.md)。2026-09-06 追加：mira lock 升级 `16e419e`→
`cbed6ad`（上游两轮反馈修复，MIR-006/007 等六项关闭），宿主 PNG 编码链路落地，
真实任务图像路径阻断解除，门禁重跑全绿 62/62——真机取证仍待设备）。
2026-09-06/07 追加：lock 依上游修复连续升级至 `874f4a5`（MIR-008/009/010 相继
关闭），PJE110 真机真实任务首次 5 步推进至 `Completed`；P3 代码与首轮真机闭环
验证完成，"≥3 类真实任务"取证与终态语义收尾仍欠（见
[p3-loop-mvp.md](p3-loop-mvp.md)）。

**2026-09-12 上游对齐**：mira 已演进至 `5b55e14`（自锁定 `874f4a5` 起 49 个提交）：
DEC-014 冻结 Agent Harness/Workflow 双平面架构，M8–M14 交付 Workflow 全链路
（Android 双 ABI CI 门禁，零设备证据），DEC-015/016/017/018 关闭 harness 契约缺口，
DEC-032 冻结分层上下文方向（未实现）。miracle 消费路线按
[DEC-004](../decisions/DEC-004-mira-dual-plane-consumption.md) 冻结：新增 P3h
（契约对齐）与 P6（Workflow 平面消费），P4 扩展 `MiraRuntime` 控制面消费，P5 吸收
mira `MNT-202609-27` 消费证据义务；lock 升级走 P3h 独立变更。

**P3h 完成（2026-09-12）**：lock 升级 `5b55e14` 全量门禁回归（单测 73/73）；DEC-016
用户消息介入（受控实例持有 + `UserMessagePolicy` 脱敏 + 任务页注入 UI + 会话投影）
与 DEC-015 工具链路（Core `wait` 注册 + 干跑场景）落地并**真机全链验证**：干跑矩阵
六场景全绿（user_message 四路径/tool 链路/预算耗尽）+ R3 协议 4×approved + 悬浮球
takeover + **真实任务 29s 三步至 Completed**。真机轮暴露并修复 6 项缺陷（含
P3 起潜伏的 R3 主体身份×2、场景 maxSteps 覆盖，与真实任务挂起根因——传输取消
通知持锁 JNI 自死锁 `BUG-20260912-P3H-01`）。详见
[p3h-mira-harness-alignment.md](p3h-mira-harness-alignment.md)（Completed）。

## 2. 交付边界（SCOPE）

- [ ] `SCOPE-01` Android 单 APK 应用：主 GUI（Compose）+ 悬浮球/面板两种前端。
- [ ] `SCOPE-02` mira 安装包消费链路（lock+脚本+CMake），版本钉死可复现。
- [ ] `SCOPE-03` Host ABI v1 全量实现（截屏、输入、能力/拓扑、生命周期、取消）。
- [ ] `SCOPE-04` 视觉离散闭环端到端（截图→VLM→tap/long_press/swipe/type/back/home→Verify）。
- [ ] `SCOPE-05` 同意与告知体系：披露、会话级同意、活动指示、R3 动作级确认、takeover。
- [ ] `SCOPE-06` 持久状态：checkpoint/崩溃恢复/replay 只读检视。
- [ ] `SCOPE-07` 验证报告：失败分类法量化指标 + mira 上游缺口回流。
- 明确不包含：连续控制、本地感知模型、多显示/多任务并行、Play 上架、闭环内自定义工具
  执行（`POST-01` 触发再评）。

## 3. 架构约束（RULE）

- [ ] `RULE-01` mira 只经 `find_package(Mira)` 安装包公共 API 消费；缺口登记 `MIR-` 台账
  回流，不 fork 不绕过。
- [ ] `RULE-02` Host ABI v1 语义（exactly-once 回调、lease 恰好一次释放、epoch 失效、
  fail-closed）不得弱化；契约测试常驻。
- [ ] `RULE-03` UI 只经 `AgentRuntime` 门面访问运行时；层依赖单向（架构设计 §1）。
- [ ] `RULE-04` 截屏与触控只在 `SessionGate` 准入的会话中执行；R3 动作必须动作级确认；
  无任何绕过路径（负向测试覆盖）。
- [ ] `RULE-05` 凭据与截图数据规则（架构设计 §6）；日志脱敏。
- [ ] `RULE-06` 真机能力声明必须有 OnePlus Ace 3 证据；仅构建通过不得表述为运行支持。

## 4. 并发边界（EXEC）

- [ ] `EXEC-01` JNI bridge 是 mira Executor 唯一外部 owner；关闭顺序按 mira §17.2，由
  `AgentForegroundService` 生命周期触发并测试。
- [ ] `EXEC-02` bridge 自研代码零线程创建；平台回调（无障碍/ImageReader/JNI）封装在
  Provider/投递边界内，回调线程只做有界校验与投递。
- [ ] `EXEC-03` Kotlin 侧结构化协程；禁止 `GlobalScope`；native↔Kotlin 交互只经 bridge
  入口。
- [ ] `EXEC-04` 队列/在途操作/事件缓冲有界；拒绝、超时、取消、关闭中提交转为明确结果
  与事件。

## 5. 里程碑索引

| 里程碑 | 目标（能力增量） | 建议发布点 | 详细计划 |
| --- | --- | --- | --- |
| P0 | 骨架与消费验证：工程初始化、mira 安装链路、JNI 加载、Executor 生命周期、空 GUI/悬浮球 | —（内部基线） | [p0-skeleton-consumption.md](p0-skeleton-consumption.md)（Completed） |
| P1 | 截屏链路：MediaProjection UX、capture_frame、lease、epoch | v0.1.0-alpha | [p1-screen-capture.md](p1-screen-capture.md)（Completed） |
| P2 | 输入链路：dispatchGesture 全集、取消、RELEASE_ALL、输入安全矩阵 | v0.2.0-alpha | [p2-input-dispatch.md](p2-input-dispatch.md)（Completed） |
| P3 | 闭环 MVP：AgentLoop+模型配置、双前端全功能、披露/确认/接管 | v0.3.0 | [p3-loop-mvp.md](p3-loop-mvp.md)（In Progress：代码完成，首轮真机闭环通过，"≥3 类任务"取证欠账） |
| P3h | mira Harness 对齐（DEC-004）：lock 升级 `5b55e14`、DEC-016 用户消息介入、DEC-015 工具注册表链路、契约回归 | v0.3.2 | [p3h-mira-harness-alignment.md](p3h-mira-harness-alignment.md)（Completed，2026-09-12 真机全链验证） |
| P3x | 闭环增强：视觉定位（无障碍树 + Set-of-Mark、target_mark 协议） | v0.3.1 | [p3x-visual-grounding.md](p3x-visual-grounding.md)（Proposed；与 P3h 无依赖，可并行） |
| P4 | 有状态：`MiraRuntime` 控制面消费（DEC-017 `complete_task`、DEC-018 takeover）、state_store 落盘、崩溃恢复、replay 检视 | v0.4.0 | `p4-stateful.md`（待建） |
| P5 | 验证报告与上游回流（含 mira `MNT-202609-27` 消费证据归档，支撑 M7 重定义） | v0.5.0（评估报告） | `p5-validation-report.md`（待建） |
| P6 | 双平面消费（DEC-004）：Workflow 编译/DryRun/Strict、升级处置 UI、恢复编排装配、lesson 记录 | v0.6.0 | `p6-workflow-plane.md`（待建；前置 P4） |

各里程碑的目标/退出条件概览见[可行性分析 §8](../feasibility-and-solution.md)；里程碑文件
建立时以本计划为准细化工作项，不回改可行性分析。

## 6. 暂定默认值（待冻结决策）

| 项 | 暂定值 | 负责人 | 最迟冻结 |
| --- | --- | --- | --- |
| R3 风险策略表初版（应用/动作组合） | 从严：支付/删除/发送/凭据输入全部确认（P3 已冻结实现：目标关键词全会话确认 + 敏感应用内 type 确认） | Miracle Maintainers | P3（已冻结） |
| VLM 端点与预算默认值 | 未定（Settings 必填，无默认端点；P3 已实现配置+Keystore 存储） | Miracle Maintainers | P3（已冻结） |
| 悬浮球交互细节（尺寸/吸附/阈值） | P3 冻结：132px 球、屏内拖动贴指、长按 ≥600ms＝takeover、单击展开 | Miracle Maintainers | P3（已冻结） |
| applicationId 定稿 | `dev.linductor.miracle` | Miracle Maintainers | P0 |

已冻结：DEC-001（前端 Compose）、DEC-002（工具集路线）、DEC-003（构建与设备基线）、
DEC-004（mira 双平面消费路线，2026-09-12）。

## 7. 通用完成定义（DOD）

- [ ] `DOD-01` 实现位于正确层，依赖方向无违例（架构设计 §1）。
- [ ] `DOD-02` 适用测试通过；真机项有 OnePlus Ace 3 记录或明确补跑条件。
- [ ] `DOD-03` 受影响设计/决策/计划/兼容性文档同步更新。
- [ ] `DOD-04` 上游缺口已登记台账并被引用（如涉及）。
- [ ] `DOD-05` Commit/MR 符合工程规范第 10 节。

## 8. 延后项（POST）

- `POST-01` L3 扩展工具层：触发条件＝P3/P5 证据显示工具面不足为主要失败归因
  （DEC-002）。**上游边界已就绪**（2026-09-12：mira DEC-015 `BuiltinToolRegistry`，
  台账 `MIR-20260905-002` Resolved）；立项时经 BuiltIn 边界注入，不自建旁路。
- `POST-02` 模拟器/x86_64 支持：触发条件＝出现无真机的回归测试需求；上游依赖已解除
  （mira `cbed6ad` 提供 android-x86_64 预设，台账 `MIR-20260905-003` 关闭），剩消费侧
  双 ABI 构建与 instrumented 冒烟接入。
- `POST-03` Play 分发评估：触发条件＝demo 结论决定产品化。
- `POST-04` 多设备矩阵扩展：**已触发**（2026-09-05，Huawei ADA-AL00 / API 31 完成 P0+P1 验证，见 `docs/compatibility/huawei-ada-al00.md`）；后续设备按同流程增量登记。
- `POST-05` mira DEC-032 分层上下文真机实测：触发条件＝mira 公共契约（五个 Provider
  接口 + `ContextIntelligenceService`）随包导出且 Stage A–D 基线建立；miracle 承担
  Stage E 真机证据（内存/延迟/功耗默认值标定），不在本仓库预建实现。

## 9. 已识别风险（摘要）

完整风险表见[可行性分析 §6](../feasibility-and-solution.md)；登记项：

- `RISK-2026-01`（=可行性 R1）mira Android 真机零证据 → P0/P1 最小链路先行，问题走
  `MIR-` 台账。
- `RISK-2026-02`（=可行性 R2）误动作副作用 → R3 确认 + 白名单 + takeover + Verify。
- `RISK-2026-03` Workflow 平面零设备证据（mira 侧仅编译级 CI；P6 首个消费轮预计
  暴露 Simulator/Android 契约分叉，先例 MIR-008/009/010）→ DryRun 先行、缺口走
  台账、不宣称未经真机验证的能力（DEC-004）。
- `RISK-2026-04` lock 升级回归面（`874f4a5`→`5b55e14`：公共头扩展、`CommandKind`/
  事件闭集追加）→ P3h 独立变更 + 全量门禁 + 真机回归后方可合入。
