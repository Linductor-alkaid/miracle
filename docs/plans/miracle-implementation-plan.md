# Miracle 实施总计划

> 状态：Active
> 版本：1.0
> 更新日期：2026-09-05
> 前置分析：[可行性与方案分析](../feasibility-and-solution.md)
> 规范：[项目管理与工程规范](../project/project-standards.md)

## 1. 当前状态

`In Progress`。文档基线已建立；**P0、P1 已完成**（P1：真机与模拟器截屏链路全通，
两帧 observe `"ok":true`、干净关闭，2026-09-05；上游缺口 `MIR-20260905-004` 登记）。
下一里程碑 P2（输入链路）未开始。

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
| P2 | 输入链路：dispatchGesture 全集、取消、RELEASE_ALL、输入安全矩阵 | v0.2.0-alpha | `p2-input-dispatch.md`（待建） |
| P3 | 闭环 MVP：AgentLoop+模型配置、双前端全功能、披露/确认/接管 | v0.3.0 | `p3-loop-mvp.md`（待建） |
| P4 | 有状态：state_store 落盘、崩溃恢复、replay 检视 | v0.4.0 | `p4-stateful.md`（待建） |
| P5 | 验证报告与上游回流 | v0.5.0（评估报告） | `p5-validation-report.md`（待建） |

各里程碑的目标/退出条件概览见[可行性分析 §8](../feasibility-and-solution.md)；里程碑文件
建立时以本计划为准细化工作项，不回改可行性分析。

## 6. 暂定默认值（待冻结决策）

| 项 | 暂定值 | 负责人 | 最迟冻结 |
| --- | --- | --- | --- |
| R3 风险策略表初版（应用/动作组合） | 从严：支付/删除/发送/凭据输入全部确认 | Miracle Maintainers | P3 |
| VLM 端点与预算默认值 | 未定（Settings 必填，无默认端点） | Miracle Maintainers | P3 |
| 悬浮球交互细节（尺寸/吸附/阈值） | 原型迭代定 | Miracle Maintainers | P3 |
| applicationId 定稿 | `dev.linductor.miracle` | Miracle Maintainers | P0 |

已冻结：DEC-001（前端 Compose）、DEC-002（工具集路线）、DEC-003（构建与设备基线）。

## 7. 通用完成定义（DOD）

- [ ] `DOD-01` 实现位于正确层，依赖方向无违例（架构设计 §1）。
- [ ] `DOD-02` 适用测试通过；真机项有 OnePlus Ace 3 记录或明确补跑条件。
- [ ] `DOD-03` 受影响设计/决策/计划/兼容性文档同步更新。
- [ ] `DOD-04` 上游缺口已登记台账并被引用（如涉及）。
- [ ] `DOD-05` Commit/MR 符合工程规范第 10 节。

## 8. 延后项（POST）

- `POST-01` L3 扩展工具层：触发条件＝P3 证据显示工具面不足为主要失败归因（DEC-002）。
- `POST-02` 模拟器/x86_64 支持：触发条件＝出现无真机的回归测试需求；依赖 mira 增加
  android-x86_64 预设（台账 `MIR-20260905-003`）。
- `POST-03` Play 分发评估：触发条件＝demo 结论决定产品化。
- `POST-04` 多设备矩阵扩展：**已触发**（2026-09-05，Huawei ADA-AL00 / API 31 完成 P0+P1 验证，见 `docs/compatibility/huawei-ada-al00.md`）；后续设备按同流程增量登记。

## 9. 已识别风险（摘要）

完整风险表见[可行性分析 §6](../feasibility-and-solution.md)；登记项：

- `RISK-2026-01`（=可行性 R1）mira Android 真机零证据 → P0/P1 最小链路先行，问题走
  `MIR-` 台账。
- `RISK-2026-02`（=可行性 R2）误动作副作用 → R3 确认 + 白名单 + takeover + Verify。
