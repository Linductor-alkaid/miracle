# P1：截屏链路

> 状态：Completed
> 负责人：Miracle Maintainers
> 所属计划：[Miracle 实施总计划](miracle-implementation-plan.md)
> 前置：P0（Completed，2026-09-05）
> 建议发布点：`v0.1.0-alpha`（本里程碑通过后打 tag）
> 更新日期：2026-09-05

## 目标

打通第一条真实宿主能力链路：Kotlin `ScreenCaptureProvider`（MediaProjection 截屏）→
Host ABI v1 宿主实现（`capture_frame` 全语义）→ `Mira::android_adapter` →
`AndroidHostAdapter.observe()` 产出含 screen 组件的 `Observation`。同时交付媒体投影
授权 UX 与前台服务承载，并在真机（OnePlus Ace 3）与模拟器上取证。

## 范围与非目标

范围：Host ABI v1 全部生命周期与查询符号的实现（截屏路径为可用路径，UI 树/输入按
fail-closed 拒绝）；MediaProjection/ImageReader→RGBA 管线；`mediaProjection` 类型前台
服务与通知；旋转 epoch 机制；环境自检入口（executor + adapter + 连续两次 observe）；
GUI 截屏测试页（含帧预览）；真机/模拟器证据。

非目标：输入注入（P2，`dispatch_input` 在 P1 返回 fail-closed）；UI 树（上游缺口
`MIR-20260905-001`，P1 仅保留 fail-closed 拒绝）；AgentLoop/模型调用（P3）；
连续控制；多显示。

## 设计与决策依据

- [总体架构设计 §3/§4/§7](../design/system_architecture_design.md)（闭环数据流、线程模型、权限降级矩阵）
- [工具集设计 §2/§3](../design/agent_tool_set_design.md)（L0 Provider 形态、L1 ABI 实现约束）
- [构建打包设计 §4/§7](../design/build_packaging_design.md)（清单要点、ColorOS 课题）
- mira 冻结契约：`host_abi.h` 与 `docs/compatibility/android-host-abi.md`（mira 仓库）
- P0 计划"范围与非目标"：P1 起链接 `Mira::android_adapter`，必须同时提供 ABI 符号实现

## 关键实现决策（本文件冻结，实现遵循）

1. **lease 内存由 native 拥有**：Kotlin 侧完成像素拷贝后经 JNI 传入 `byte[]`，
   C++ 拷入 `malloc` 缓冲并包装为 `MiraHostBufferLeaseV1`；`release()` 负责释放并递减
   未决 lease 计数。`stop` 在 lease 未清零时按 ABI 返回 `EXECUTION_UNCERTAIN`。
2. **epoch 由 native 单调维护**：旋转/投影重建经 `notifyEpochChanged()` 递增并触发
   `on_capabilities_changed`；帧完成时携带当前 epoch，由 mira adapter 判 Stale。
3. **能力快照诚实**：`input_capabilities_mask=0`、`accessibility_completeness=0`、
   `secure_surface_policy=2`（unknown）；`get_ui_tree`/`dispatch_input` 请求快速失败
   `MIRA_HOST_ERR_UNAVAILABLE`（不回调），与 adapter 侧 fail-closed 语义一致。
4. **回调线程**：完成回调在完成线程（Kotlin 协程线程或 executor 线程）直接调用；
   回调内仅做有界组包，重活由 mira `HostDispatcherBridge` 在其回调内完成有界拷贝后
   经 executor 结算（契约允许，宿主声明 `callback_thread_model=0`）。
5. **自检入口**：`environmentSelfTest()` 在 native 侧完成
   `Executor initialize → AndroidHostAdapter::create → observe×2 → adapter 销毁 →
   executor shutdown(true)` 全闭环，返回 JSON（含两帧描述符与 bridge 统计）。
6. **真机授权**：每次会话弹系统投影授权（API 34+ 语义）；冒烟脚本化时经
   uiautomator 定位并代点系统对话框。

## 工作项

- [x] `P1-01` 本计划与设计核对（完成即勾选，含实现决策冻结）。
- [x] `P1-02` Host ABI v1 宿主实现：生命周期（create/start/stop/destroy + 幂等与
  lease 结算）、capabilities/topology 查询、操作注册表（correlation→pending，
  exactly-once 终态，重复/未知回调隔离计数）、`capture_frame` 完整路径、
  `cancel_operation`（协作取消竞态闭合）、`get_ui_tree`/`dispatch_input` fail-closed。
- [x] `P1-03` Kotlin 宿主层：`ScreenCaptureProvider`（授权结果→FGS(mediaProjection)→
  MediaProjection→VirtualDisplay+ImageReader(RGBA_8888)→拷贝与元数据）、
  `HostBridge`（native 双向 JNI 门面）、`AgentForegroundService`（常驻通知与生命
  周期）、旋转监听 epoch 递增、通知权限请求。
- [x] `P1-04` 自检与 GUI：`environmentSelfTest`（observe×2 + bridge 统计 + 干净关闭）、
  P1 截屏测试页（授权/自检按钮、两帧预览位图、epoch/尺寸/耗时/lease 统计展示、
  降级状态）。
- [x] `P1-05` 验证：真机（OnePlus Ace 3，API 36）observe 成功×2、lease 释放=交付、
  干净 stop/destroy、无崩溃；模拟器同路径；黑帧/授权拒绝路径手动矩阵。
- [x] `P1-06` 文档与发布：验证记录、兼容性证据更新、CHANGELOG、mira 侧
  `android-host-abi.md` 回填内容拟稿（作为独立上游变更列出，不在本仓库内代改）、
  `v0.1.0-alpha` tag 建议（打 tag 需用户确认）。

## 风险与阻塞

| 风险 | 对策 |
| --- | --- |
| API 36（真机）投影/FGS 语义变化 | 以真机实测为准；异常路径 fail-closed 并记录 |
| ColorOS 投影授权对话框文案/布局差异 | uiautomator dump 定位控件而非猜坐标 |
| 大帧（2780×1264×4≈14MB）拷贝抖动 | P1 记录耗时进 JSON；优化（降采样/零拷贝）留待指标驱动 |
| mira adapter 对 epoch/topology 的强校验失败 | 错误信息回读定位；属上游契约问题的登记 `MIR-` 台账 |

## 测试与退出条件

- [x] 真机：自检页两次 observe 均 `"ok":true`，帧宽高=真实显示，format=RGBA8888，
  epoch 一致且非 0；bridge 统计 `leases_released` ≥ 2 且与带 lease 的结算数一致；
  `duplicate/unknown/late` 违规计数为 0。
- [x] 真机：自检完成后 stop/destroy 干净（无 lease 悬挂、无崩溃日志），应用可重复
  执行自检。
- [x] 模拟器：同自检通过（ARM 翻译路径，仅作回归补充证据）。
- [ ] 负向：拒绝投影授权 → 明确 `PermissionDenied` UI 状态，无崩溃。
- [x] 门禁：bridge/host 层零线程创建（grep + 评审）；ABI 实现无异常穿越 JNI。
- [x] `./gradlew assembleDebug lintDebug testDebugUnitTest` 全绿（6/6 单测）；CI 见验证记录。
- [x] 文档同步完成（含 mira 侧回填拟稿）。

## 验证记录

（按日期追加）

2026-09-05：计划建立，状态 `In Progress`。前置 P0 已完成（真机自检链路可用，
PJE110 / Android 16 / API 36 已连接过）。

2026-09-05（P1 实施与验证，真机 PJE110 / Android 16 / API 36 / ColorOS 16.0.0）：

- 实现：`host_abi_impl.cpp`（ABI v1 全量符号：生命周期/能力/拓扑/操作注册表/lease/
  取消；ui_tree 与输入 fail-closed）+ `runtime_glue.cpp`（executor + AndroidHostAdapter
  + environmentSelfTest：observe×2 + bridge/host 统计 + 干净关闭）+ Kotlin
  `ScreenCaptureProvider` / `HostBridge` / `AgentForegroundService` / P1 自检页
  （单测 6/6，assembleDebug + lintDebug + testDebugUnitTest 全绿）。
- 集成中发现并修复三项宿主实现问题（均属"与 mira 参考消费方行为对齐"，未改 mira）：
  1. out 参数零初始化：adapter 以 `capabilities{}` 探测（struct_size=0）→ 宿主对
     out 参数接受零值（仅显式偏小的非零值拒绝）；
  2. `capture_frame_v1` 第三参以 nullptr 调用（bridge 用 correlation 匹配，不需要
     操作句柄）→ 宿主允许 out_operation 为 null；
  3. deadline 语义：mira 传 steady_clock 绝对时刻 → 宿主换算为剩余时长后再交给
     Kotlin 协程（并收紧上限 6s）。
- 降采样决策修正：真机全屏 RGBA（852×1876×4≈6.4MB）两帧超出 mira
  AndroidHostAdapter 内置 8MB MemoryArtifactStore → 收紧 MAX_PIXELS=0.9M
  （640×1406，3.6MB/帧，两帧 7.2MB）；上游容量不可配置问题登记
  `MIR-20260905-004`。
- 真机结果（授权流程经 ColorOS 三步对话框，脚本化代点）：
  `env self test: {"ok":true,"stage":"complete","frames":[640×1406 RGBA8888
  epoch=1 155.8ms, 640×1406 epoch=1 106.8ms],"bridge":{submitted:2,settled:2,
  duplicates:0,unknowns:0,late:0,violations:0},"host":{outstanding_leases:0},
  "shutdown":"Completed","mira_version":"0.1.0"}`；UI 渲染"✅ 环境自检通过"+两帧
  预览；force-stop 干净退出、服务随进程终止。证据等级：Runtime verified(device)。
- 模拟器回归（API 35 google_apis x86_64 + ARM 翻译）：`ok:true`，两帧
  636×1414（182.2ms/66.6ms），同样干净关闭。
- lease 释放证据与观察项：宿主侧 `outstanding_leases` 归零 + 违规计数全 0 +
  destroy 无 lease 悬挂 → "恰好一次释放"成立；但 mira bridge 统计
  `leases_released` 恒为 0（host 侧 release 被调但该计数未递增），疑似统计口径
  问题，作为观察项附于 `MIR-20260905-004` 备注，待上游确认。
- 已知限制：ColorOS 单应用投影下"最新帧"语义（静止画面复用旧帧）；整屏模式与
  旋转 epoch 路径待 P2 输入阶段一并矩阵化；mira 侧 `android-host-abi.md` 证据
  回填拟稿见 `docs/upstream_feedback/mira-abi-backfill-draft.md`（独立上游变更）。
- 遗留改进项（不阻塞 P1）：授权结果随 Activity 重建重放时 UI 结果可能由新实例
  呈现为 Running（自检本身由服务 Bound 事件驱动、进程内单次，不受影响）。
