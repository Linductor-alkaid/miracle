# Miracle Agent 工具集设计

> 状态：Proposed
> 版本：1.0
> 更新日期：2026-09-05
> 上位文档：[总体架构设计](system_architecture_design.md)
> 关联决策：[DEC-002 工具集路线](../decisions/DEC-002-agent-tool-set-route.md)

## 1. 问题定义

"Android 端 agent 工具集"在本项目中有精确含义：**LLM 在闭环中可调用的一切能力原语**。它
由四层组成，每层职责不同，不得混淆：

| 层 | 位置 | 职责 | 变更节奏 |
| --- | --- | --- | --- |
| L0 平台能力层 | Kotlin（`host/`） | 把 Android 系统能力（截屏、手势、文本、前台应用）实现为可取消、可观测的异步操作 | 随 app 迭代 |
| L1 ABI 边界 | C++（`bridge/host_abi_impl`） | 把 L0 能力经冻结 C ABI 暴露给 mira，维护 lease/epoch/exactly-once 语义 | 随 mira ABI（冻结） |
| L2 决策协议层 | mira（上游） | LLM 实际"看到"的工具协议：AgentLoop 的冻结 decision schema + 观察通道 | 随 mira 版本，钉死 |
| L3 扩展工具层 | Kotlin + bridge（P3+） | decision schema 之外的宿主工具（开应用、剪贴板等），经直连 ModelGateway 的旁路会话执行 | 本仓库自主，见 §6 |

**结论（对应 DEC-002）**：v1（P0–P3）只建 L0+L1，LLM 工具面 = L2 决策协议；L3 作为
P3 后的可选增量，接口在本设计中预留但不实现。

## 2. L0 平台能力层

每个 Provider 遵循统一形态：`suspend fun op(args, deadline): Result`，支持协作取消，向
`CapabilityRegistry` 上报能力与权限状态，错误映射遵循架构设计 §8。

| Provider | 操作 | 实现要点 |
| --- | --- | --- |
| `ScreenCaptureProvider` | `requestFrame(display, deadline)` | MediaProjection → VirtualDisplay + ImageReader(RGBA_8888)；帧到达即包装为 lease（ByteBuffer 指针 + release 闭 Image）；旋转/重建递增 epoch；黑帧检测标志 |
| `InputDispatcher` | `dispatch(events, deadline)` | 手势：`GestureDescription` + `dispatchGesture`（stroke 路径、时长、连续手势 willContinue 备用）；文本：优先无障碍文本动作，失败降级逐键手势；取消：`cancelGesture` + `RELEASE_ALL` 语义（合成抬起事件）；回执如实区分 Dispatched/Completed/Rejected/Unknown |
| `AppMonitor` | 前台应用/包名事件流 | 无障碍 `TYPE_WINDOW_STATE_CHANGED` 过滤；供 Verify 与 UI 显示，不进决策（v1） |
| `UiTreeProvider` | `requestTree(display, maxBytes)` | **预留**：无障碍节点树序列化；mira adapter v1 不采纳（见 §7 台账），仅做技术验证用 |

## 3. L1 ABI 边界实现约束

`bridge/host_abi_impl` 实现 `host_abi.h` 全部导出符号，并保证：

1. **操作注册表**：correlation → pending 操作；每个操作恰好一次终态回调，重复/未知回调
   计数并丢弃（契约违反可见于统计）。
2. **lease 语义**：frame lease 在回调期间有效，native 有界拷贝后恰好一次 release；成功、
   失败、取消、stop 路径全覆盖。
3. **epoch 维护**：`environment_epoch` 单调递增；旋转、投影重建、无障碍服务重连、权限
   变化时递增，使在途观察判 Stale。
4. **capabilities 如实**：`screenshot_pixel_formats_mask`、`input_capabilities_mask`、
   `accessibility_completeness`、`permission_state` 等按实际状态上报，不虚报；宿主能力变
   化经 `on_capabilities_changed` 推送。
5. **回调线程模型**：声明为宿主内部线程（`callback_thread_model=0`）；回调内只做有界校验、
   拷贝与完成投递。

## 4. L2 决策协议：v1 工具清单与风险分级

mira 冻结 decision schema 即 v1 工具协议。完整清单与治理策略：

| 工具（动作） | 参数（归一化坐标） | 风险级 | 确认策略 |
| --- | --- | --- | --- |
| `observe`（隐式，每步自动） | display | R0 只读 | 会话披露已覆盖 |
| `tap` | x, y | R1 副作用 | 会话级同意 + 活动指示 |
| `long_press` | x, y, duration | R1 | 同上 |
| `swipe` | x1,y1,x2,y2, duration | R1 | 同上 |
| `back` / `home` | — | R1 导航 | 同上 |
| `type` | text | R2 内容注入 | 会话级同意 + 目标字段展示；**凭据场景升级 R3** |
| `release_all` | — | 安全原语 | 无需确认，随时可发 |

风险分级与确认实现（对齐 mira DEC-004，宿主为确认权威）：

- **会话级同意（R1）**：`SessionGate` 在会话启动时取得——披露内容（屏幕将发往模型服务、
  触控将被模拟）+ 系统授权（投影/无障碍）+ 常驻通知开启。会话期间活动指示常亮，一键
  takeover 随时可用。会话级同意**不**覆盖 R3。
- **动作级确认（R3）**：支付/删除/发送/凭据输入类目标。判定输入：动作类型 + 目标应用/
  控件摘要 + 会话目标。实现为 `ConfirmationRequest`（含动作摘要、风险说明、nonce、到期
  时间）经 UI 弹出；用户确认只授权该单次动作，绑定失败（字段变化/超时/取消）即失效。
  模型自述确认不构成授权。
- **判定规则位置**：R3 判定规则（哪些应用/动作组合需确认）属于本仓库 `consent` 模块的
  可配置策略表，默认从严；策略表变更走决策记录。

## 5. 工具面受限的诚实声明

v1 工具面**没有**：UI 树观察（mira adapter 不采纳，MIR 台账）；开应用/剪贴板/通知读取等
宿主工具（L3 未建）；连续控制与本地感知（mira M5/M6 已取消）。LLM 完成"打开某应用"类
意图需经 `home` + 视觉寻找图标实现——可靠性代价已知，量化进 P5 验证报告（失败分类：
任务建模类），作为是否建设 L3 或推动 mira 的证据。

## 6. L3 扩展工具层（P3+ 预留设计）

**进入条件（POST）**：P3 真机任务证据显示"工具面不足"是主要失败归因，且经台账确认
mira 短期不提供闭环内工具执行。

**方案（直连网关旁路）**：`ToolSession` 由本仓库自管编排，不经 AgentLoop：

```
ToolRegistry(Kotlin) ── 声明 ExposedToolSpec 列表
ToolSession(Kotlin) ── 组装 ModelRequest(tools=…) → mira ModelGateway.infer()
                     ← ToolProposalBatch（解析/校验/fail-closed 由 mira 完成）
ToolExecutor(bridge) ── 按 proposal 调用 Kotlin 工具实现 → ToolExecutionRecord
                     ── build_tool_result_input 回填下一轮
```

约束：ToolSession 与 AgentLoop 不同时活跃（同一时刻一条主控路径）；每个扩展工具必须声明
风险级并纳入 §4 确认策略；工具结果经事件流可见；旁路会话同样受 SessionGate 管控与
takeover 语义约束。候选首批工具：`open_app`（R1）、`clipboard_read`（R2）、
`device_info`（R0）。若证据显示应推动上游（DEC-009/M7 重定义），优先回流而非自建。

## 7. 与 mira 的缺口台账（初始条目）

| 编号 | 缺口 | 影响 | 状态 |
| --- | --- | --- | --- |
| `MIR-20260905-001` | `AndroidHostAdapter` 不采纳 `accessibility_completeness`，`observe()` 对 structure 请求 fail closed | v1 无 UI 树 grounding，纯视觉决策 | Open（P5 出证据） |
| `MIR-20260905-002` | AgentLoop 中 ToolProposals 不可执行（M3 语义） | 扩展工具只能走 L3 旁路或上游变更 | Open（POST 触发） |

台账正式登记于 `docs/upstream_feedback/ledger.md`（P0 建立）；每条含可复现证据与期望
语义。

## 8. 测试要求

- L0：每 Provider 的正常/拒绝/取消/超时/权限缺失路径；lease 释放恰好一次（含异常路径）。
- L1：契约测试经 fake loop 驱动——exactly-once 回调、重复回调隔离、epoch 递增致 Stale、
  stop 幂等、destroy 时 lease 归零。
- 确认策略：R3 负向测试（模型伪造确认、nonce 重放、字段变更后确认失效）。
- 集成：P2/P3 真机矩阵（见构建打包设计 §7）。
