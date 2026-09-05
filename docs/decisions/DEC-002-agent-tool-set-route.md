# DEC-002：v1 工具集路线＝宿主能力经 Host ABI + mira 决策协议；扩展层延后

> 状态：Accepted
> 日期：2026-09-05
> 负责人：Miracle Maintainers
> 冻结里程碑：P3（扩展层进入条件为 POST 项）
> 替代/被替代：无

## 背景与问题

产品要求"构建平台工具端供 LLM 调用"。mira 现状：AgentLoop 的工具协议是冻结 decision
schema（tap/long_press/swipe/type/back/home + 观察），ToolProposals 在 M3 循环内明确不可
执行（DEC-009/M7 Blocked）；AndroidHostAdapter 观察仅支持 screen 组件。工具集建设存在
三条路线。

## 决策

1. v1（P0–P3）只建 L0（Kotlin 平台能力 Provider）与 L1（Host ABI v1 实现），LLM 工具面
   即 mira L2 决策协议；风险分级 R0–R3 与确认策略随 v1 落地（R3 = 动作级 challenge 确认，
   对齐 mira DEC-004）。
2. L3 扩展工具层（open_app/剪贴板等）不进 v1：接口设计预留（ToolSession 直连
   ModelGateway 的旁路会话），进入条件为"P3 真机证据显示工具面不足是主要失败归因"。
3. UI 树观察缺口登记 `MIR-20260905-001` 台账回流，不在本仓库内旁路实现。

## 备选方案

- 直接建设 L3（自定义工具直连网关）：短期能力更强，但在核心闭环未经真机验证前引入第二
  条编排路径，违反"先用尽公共 API、缺口回流"的 DEC-011 精神，且自担验证语义。不采用
  （作为 POST 备选）。
- 在本仓库内 fork/扩展 mira adapter 以启用 UI 树：违反 mira 消费边界（只经安装包公共
  API）。不采用。

## 影响与风险

- "打开应用"类意图需 home+视觉找图标，成功率受视觉 grounding 限制；作为已知代价量化进
  P5 报告（失败分类：任务建模）。
- R3 判定策略表（哪些应用/动作组合需动作级确认）为暂定默认值，负责人 Miracle
  Maintainers，最迟冻结里程碑 P3。

## 验证方式

P2 输入安全矩阵；P3 闭环任务集中 R1/R2/R3 各有覆盖；P5 失败分类法量化工具面充分性。

## 关联文档和工作项

[工具集设计](../design/agent_tool_set_design.md)；台账 `MIR-20260905-001/002`；总计划
`P1-*`、`P2-*`、`POST-01`。
