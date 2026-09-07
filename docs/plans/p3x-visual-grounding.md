# P3x：视觉定位增强（无障碍树 + Set-of-Mark）

> 状态：Proposed
> 负责人：Miracle Maintainers
> 所属计划：[Miracle 实施总计划](miracle-implementation-plan.md)
> 前置：P3（In Progress；本计划与 P3 真实任务验收并行，P3 的"≥3 类真实任务"取证
> 可顺延至本计划切片二后复跑以获得更强 grounding 对比数据）
> 建议发布点：`v0.3.1`
> 更新日期：2026-09-07

## 目标

以 [视觉定位增强设计](../design/visual_grounding_design.md) 为依据，把 Agent 的
动作定位从"VLM 坐标回归"升级为"无障碍树 bounds 精度 + 可校验引用"：

1. 无障碍树经 Host ABI `GET_UI_TREE` 进入 mira `Observation.structure`（MIR-001
   预留通道启用），`accessibility_completeness` 如实声明。
2. 截屏编码时叠加 SoM 编号标记 + structure 摘要携带编号表（双通道呈现）。
3. 上游 `target_mark` 决策引用协议落地（MIR-20260907-001），mark 存在性校验
   fail-closed。
4. 真机量化：坐标路径 vs 标记增强路径的落点误差 A/B 对比（P3 遗留"滑动落点错误"
   的直接解法）。

## 范围与非目标

范围：Kotlin 无障碍树快照与序列化（`mira.host.tree.v1` 扩展字段随 MIR-001-001
演进）；树/帧时间对齐（D-4）；编码前 Bitmap 叠标；`ScreenCaptureProvider` 编码
路径扩展；completeness 能力位切换；A/B 取证工具与指标；上游协议反馈与 lock 升级
落地；隐私过滤（输入联想/描述截断）。

非目标：图像检测器兜底（OmniParser/OCR，盲区场景后续评估）；目标结果独立验证
（ILoopVerifier 增强，P3 文档另行登记）；iOS/桌面同构；标记布局算法库（上游纯
函数辅助，瓶颈出现再议）。

## 切片与任务

### 切片一：无障碍树进 mira（纯宿主，无上游依赖）

- `P3x-01` 树快照与序列化：`MiracleAccessibilityService` 内快照策略（D-5 待决策，
  倾向先按需 dump）；节点过滤（§6：visibleToUser/可交互/有文本，上限 64）；
  `mira.host.tree.v1` JSON 产出（时间戳 + 规范 bounds）。
- `P3x-02` ABI 通道接线：`host_abi_impl` 的 `GET_UI_TREE` 实现从预留/空实现切换为
  读快照（回调内只做拷贝，遵守 ABI 纪律）；`accessibility_completeness` 声明切换。
- `P3x-03` 验证：mira `observe()` structure 组件出现在决策 prompt（传输日志/
  preview 佐证）；干跑矩阵回归（树存在时结构摘要不破坏既有决策路径）。
- 单测：序列化契约（节点上限/截断标注/时间戳）；快照线程模型。

### 切片二：SoM 叠标 + 编号表（纯宿主，量化增益）

- `P3x-04` 标记分配与叠标：采集路径内树/帧对齐（150ms 阈值，超差降级文本表）；
  mark id 分配；编码前 Bitmap 角标绘制（只标可交互叶子，字号真机标定）。
- `P3x-05` 编号表进 prompt：structure 摘要含 mark（上游摘要预算未放宽前，宿主侧
  先过滤到可交互节点控制长度）。
- `P3x-06` A/B 取证：同任务集（含 P3 亮度滑条场景）跑坐标-only vs 标记增强，
  指标＝落点误差（像素）、参数缺失率、完成率；证据入本文档与台账。
- 单测：对齐阈值/降级路径；叠标不影响 `HostFrameStore` 发布语义（digest 变化
  已由内容寻址自然处理）。

### 切片三：`target_mark` 协议落地（上游依赖）

- `P3x-07` 上游反馈：MIR-20260907-001 issue 化（四处接口变更 + 宿主权威编号的
  时序论证，见设计 §4）。
- `P3x-08` lock 升级与适配：决策 schema `target_mark`、compile 校验/换算、
  摘要呈现；miracle 侧决策侧无改动（协议在 mira）。
- `P3x-09` 验证：编号幻觉 fail-closed（构造不存在的 mark，断言 violation →
  rejection 反馈重试）；真机任务走 mark 路径落点误差＝树 bounds 精度。

## 退出条件

- 切片一/二：真机 structure 组件进 prompt + A/B 报告显示标记增强路径落点误差
  显著低于坐标路径（亮度滑条场景修正）；干跑矩阵与既有单测全绿；隐私过滤生效
  （联想文本不上报）。
- 切片三：上游合入 + lock 升级后，mark 引用真机可用且幻觉校验生效。
- 文档/台账/决策同步；上游缺口全部按台账登记并被引用。

## 风险与阻塞

- MIR-20260907-001 排期不确定 → 切片一/二先行交付增益，不因上游阻塞。
- 叠标遮挡/编号可读性需真机标定 → 切片二验收即 A/B 实验，不达标回退纯文本表。
- 厂商无障碍限制（ColorOS 电池策略）→ 沿用 P2 兼容性经验，能力位降级。
