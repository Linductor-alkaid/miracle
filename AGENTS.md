# Miracle 项目协作约定

## 适用范围

本文件适用于 Miracle 仓库中的全部自研代码（Kotlin、C++、Gradle/CMake 配置）、测试、文档与
发布材料。`third_party/` 与安装前缀中的上游代码（mira 及其依赖）遵循其自身约定；除非任务
明确要求升级或反馈缺口，否则不修改上游代码。本文件是仓库级最高强制约束，与
[项目管理与工程规范](docs/project/project-standards.md)（下称"工程规范"）配套使用：本文件
定义底线，工程规范定义完整流程、模板与证据要求。

## 项目管理与文档规范

所有计划、里程碑、设计、决策、验证证据和文档变更必须遵循工程规范第 1-8 节。开始非平凡
变更前，必须确认所属计划工作项、相关设计和决策依据；完成时必须同步更新任务状态、测试
结果、验收证据以及受影响文档。环境限制导致的未执行验证（例如真机不在手边）不得标记为
完成，必须记录原因、负责人和补跑条件。

## 产品目标

Miracle 是运行在 Android 上的 AI Agent 应用：以安装包形式消费 mira（跨平台 C++ Agent
Runtime），由 mira 驱动 `Observe → Reason → Plan → Act → Verify` 闭环；应用作为 Android
Host，经冻结的 Host ABI v1 注入屏幕采集与触控注入能力，向用户提供主 GUI 与悬浮球两种
前端。本项目同时是 mira DEC-011 定义的独立验证载体。

必须保持稳定的公开边界：

- **mira 消费边界**：只经 `find_package(Mira)` 安装包公共 API 消费 mira；不 fork、不镜像
  mira 源码进本仓库。公共 API 无法满足的需求登记到上游反馈台账（`MIR-` 条目）回流，
  不得在本仓库内绕过。
- **Host ABI v1 边界**：Kotlin 宿主能力（截屏、触控、UI 树预留）只经
  `mira/adapters/android/host_abi.h` 的冻结 C ABI 进入 native 侧；ABI 语义（exactly-once
  回调、lease 恰好一次释放、epoch 失效、fail-closed）不得弱化。
- **前端边界**：UI 层（Compose 与悬浮球）只经 `AgentRuntime` 门面访问运行时，不直接触碰
  JNI、ABI 或 mira 类型。
- **同意边界**：截屏与触控注入只在用户已被告知并授权的会话中执行；高风险动作必须走
  DEC-004 风格的结构化确认。任何代码路径不得绕过同意门面。

## 并发与生命周期纪律

Miracle 是双运行时项目，并发纪律按层划分：

1. **mira Executor（native 侧唯一并发设施）**：miracle 的 JNI bridge 是 Executor 的唯一
   外部 owner（对齐 mira DEC-001 与 AGENTS.md）。初始化、关闭顺序（停止生产者 → 取消 →
   回收 worker → 排空有限任务 → 非 worker 线程 `shutdown(true)`）由 bridge 层显式实现并
   测试；native bridge 自研代码不得使用 `std::thread`/`std::async`/私有线程池。
2. **Kotlin 侧**：使用结构化协程（生命周期 owner 作用域），禁止 `GlobalScope` 与无主
   `launch`。平台线程亲和（主线程、`ImageReader` handler 线程、无障碍回调）封装在对应
   Provider 内，经有界投递汇入 `AgentRuntime` 状态流。
3. **ABI 回调纪律**：`on_operation_complete` 等回调内只做有界校验、拷贝和完成投递，不在
   回调线程执行业务逻辑或阻塞；回调线程模型在 capabilities 中如实声明。
4. 队列、缓存与在途操作必须有容量/预算上限；拒绝、超时、取消与关闭中的提交必须转化为
   明确结果与事件，不得静默重试或吞掉失败。

## Runtime 与状态模型

- Agent 会话状态以 mira 状态机为准（Idle/Observing/Reasoning/Planning/Acting/Verifying/
  Recovering/Completed/Failed）；UI 状态是它的纯投影，不得自造第二状态机。
- Task 与 Session 有稳定 ID 与取消上下文；终态幂等，迟到结果不得复活已取消/完成的任务。
- Human Takeover（悬浮球长按、通知按钮）必须：阻断新的自主动作、取消在途输入并
  `RELEASE_ALL`、恢复前强制重新观察。

## 安全底线

- 模型凭据（API key 等）只存于 Android Keystore 加密后的存储；不得进入日志、事件、
  剪贴板、崩溃报告或仓库。
- 截图与会话事件按敏感数据分类：出设备（发往模型服务）前必须经用户同意；日志中的截图
  引用只保留摘要。
- 高风险动作（支付、删除、发送、凭据输入）默认 `RequireConfirmation`，确认绑定动作摘要
  与 nonce，单次有效；模型自述"用户已同意"不构成授权。

## 工程约束

- Kotlin（Android）/ C++20（JNI bridge）+ Gradle + CMake（NDK）。依赖方向：UI → 门面 →
  Kotlin 宿主服务 → JNI bridge → mira；不得反向依赖或跨层直连。
- mira 版本钉死在 `tools/mira.lock`（精确 commit）；升级走独立变更并重跑验收。
- 为状态投影、取消竞态、关闭顺序、权限缺失路径、ABI 契约（lease 释放、epoch 失效、
  exactly-once）编写测试；新增异步路径至少验证：正常完成、异常、取消、超时、shutdown、
  Takeover。
- 变更公开契约（ABI 实现、门面 API、事件 schema、风险策略默认值）时同步更新设计文档
  与决策记录。不得宣称未经真机验证的平台能力。

## Git 提交与仓库纪律

Commit、分支、MR、评审与合并遵循工程规范第 10 节。要点：Commit Message 使用
`<type>(<scope>): <subject>`，scope 取自词表（`ui`/`overlay`/`host`/`bridge`/`consent`/
`build`/`ci`/`docs`/`tools`）；`master` 为保护分支只经 MR 合入；未执行的测试不得写成
"测试通过"；签名密钥与凭据不入库。

## 完成定义

一项 Miracle 变更只有在以下条件满足才算完成：职责位于正确层；native 任务全部受 mira
Executor 管理、Kotlin 任务全部结构化；取消与 shutdown 路径闭合；同意与确认边界未被绕过；
失败对 UI 与日志可见；关键事件可复现；相关测试通过；计划状态、设计、决策和验收证据已
同步；Commit/MR 符合仓库纪律；上游（mira/Executor）缺口已按台账登记并被引用。
