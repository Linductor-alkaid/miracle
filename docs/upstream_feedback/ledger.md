# 上游反馈台账（mira 公共 API / Executor）

> 状态：Active
> 建立日期：2026-09-05
> 规范：[工程规范 §9.4](../project/project-standards.md)

登记规则：先核对 pinned mira 版本的公开头文件、API 文档与测试，排除选型/配置错误、平台
限制和应用层职责；每条必须含可复现证据、影响范围、期望语义与可验收结果。分级：
`P1` 系统性将就 / `P2` 结构性将就 / `P3` 有而未用 / `违规`。

## 条目

### MIR-20260905-001：AndroidHostAdapter 不提供 structure 观察

- 状态：Open
- 分级：P2（结构性：v1 全部任务失去 UI 树 grounding）
- 证据：mira `16e419e` 的 `adapters/android/android_host_adapter.cpp`：
  `capabilities()` 不采纳 `accessibility_completeness`；`observe()` 对非 screen 组件请求
  fail closed（"the M2 host skeleton only captures screen components"）。ABI 已定义
  `MIRA_HOST_OP_GET_UI_TREE`（op kind 2），host 侧可提供，native adapter 不消费。
- 影响：Miracle v1 决策纯靠截图+VLM；目标定位可靠性与 token 成本受限。
- 期望语义：宿主声明 `accessibility_completeness≥1` 时，adapter 在 `observe()` 中聚合
  UI 树组件（或至少允许 structure-only 观察）；能力不满足时维持现有 fail-closed。
- 可验收结果：真机 UI 树经 ABI 进入 Observation；`capabilities().ui_tree` 与宿主声明
  一致；fake host 契约测试扩展覆盖。
- 备注：P5 验证报告将提供"视觉-only 失败归因"量化证据支撑重开决策。

### MIR-20260905-002：AgentLoop 内 ToolProposals 不可执行

- 状态：Open（与 DEC-002 的 `POST-01` 关联）
- 分级：P3（当前未使用该能力，属方向性缺口）
- 证据：mira `16e419e` 的 `src/model/agent_loop.cpp`："tool proposals are not executable
  in the M3 loop"；DEC-009/M7 Blocked。
- 影响：扩展工具只能走本仓库 L3 旁路（直连 ModelGateway）或等待上游。
- 期望语义：闭环可执行经 allowlist 暴露的工具并回填 `ToolExecutionRecord`。
- 可验收结果：以 `POST-01` 触发时的证据重新定义。

### MIR-20260905-003：缺少 android-x86_64 构建预设

- 状态：Open
- 分级：P3
- 证据：mira `CMakePresets.json` 仅有 `android-arm64-*`；平台矩阵声明单 ABI。
- 影响：无真机时缺乏模拟器回归路径（Miracle `POST-02`）。
- 期望语义：提供与 arm64 等价的 android-x86_64（或 universal）预设并进 CI。
- 可验收结果：Miracle CI 可在 x86_64 模拟器上运行 instrumented 冒烟。
