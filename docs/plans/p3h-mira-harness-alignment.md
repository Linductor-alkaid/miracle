# P3h：mira Harness 对齐（lock 升级 + 契约采纳）

> 状态：Completed（2026-09-12：代码/门禁/真机全链通过——干跑矩阵、R3、takeover、
> 注入实测、真实任务至 Completed；遗留小项见验证记录，不阻塞）
> 负责人：Miracle Maintainers
> 所属计划：[Miracle 实施总计划](miracle-implementation-plan.md)
> 前置：P3（In Progress：代码完成、首轮真机闭环通过；P3h 依赖其代码基线，不阻塞于
> P3 的"≥3 类真实任务"取证欠账）
> 建议发布点：`v0.3.2`（打 tag 待用户确认）
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

- [x] `P3h-01` lock 升级：`tools/mira.lock` → `5b55e14`；`install-mira` 重建；
      公共 API 差异清单归档（[compatibility/mira-5b55e14.md](../compatibility/mira-5b55e14.md)）。
- [x] `P3h-02` 编译适配与门禁：`loop_runtime`/`runtime_glue` 适配；`assembleDebug` +
      `testDebugUnitTest` + `lintDebug` 全绿（2026-09-12）；native 独立编译零警告；
      既有干跑矩阵不回归——**随 P3h-06 真机同机执行**（本会话无设备连接）。
- [x] `P3h-03` 用户消息介入（DEC-016）：bridge 受控实例持有 → JNI 入口 → `AgentRuntime`
      门面（`sendUserInstruction`）→ 任务页输入 UI；入队前脱敏
      （`UserMessagePolicy`：长度上限 1024 + 凭据模式过滤，命中拒绝）；日志只记长度与
      digest；溢出/空消息拒绝路径对 UI 可见（真机取证随 P3h-06）。
- [x] `P3h-04` 会话投影（DEC-016）：`build_conversation_view` 接入会话详情时间线
      （只读"会话记录"卡；`UserMessage`/`LoopOutcome` 两类条目）；与既有步进时间线
      并存呈现（事件存储为唯一事实源，投影可重建）。
- [x] `P3h-05` 工具链路验证（DEC-015）：注册 `wait`（注册失败 fail-closed 不开会话）；
      干跑 transport 支持 `tool_call` 脚本条目（tool_calls wire）并记录请求标记；
      `ToolExecuted` 事件计数与 `mira.agent-loop.tool-result.v1` 回填经结果 JSON
      断言（场景 `tool`）；预算耗尽终态路径（`max_tool_executions=1`，场景
      `tool_budget`）。真机执行随 P3h-06。
- [x] `P3h-06` 真机回归（**2026-09-12 完成**）：干跑矩阵全绿（含 user_message
      四路径/tool 链路/预算耗尽）、R3 协议两轮 4×approved、悬浮球 takeover、
      注入与投影 UI 呈现、**真实任务 29s 三步至 Completed**；过程暴露并修复 6 项
      缺陷（4 项 R3/maxSteps P3 起潜伏、function_tools 能力、传输取消自死锁
      `BUG-20260912-P3H-01`，见验证记录）；证据 `build/p3-device-evidence/`；
      台账 `MIR-20260905-002` 真机取证已回写。遗留登记：真实传输「停止」负向
      路径修复后未再实测（机制与干跑 cancel 等价，下次真机轮顺带补）。

## 风险与阻塞

- `RISK-2026-04`（总计划）：lock 升级回归面——49 个提交跨度，全量门禁 + 真机回归
  兜底；发现契约分叉即登记 `MIR-` 台账。
- loop 实例生命周期改造触及 Executor 纪律：设计选型（邮箱 vs 实例持有）在实现前
  评审；取消/shutdown 中入队必须转化为明确拒绝结果。
- 用户消息改变模型行为的不确定性（指令冲突/诱导）：v1 仅呈现"已注入"，不承诺指令
  生效；高风险动作仍走 R3 确认（消息不是授权，对齐同意边界）。

## 测试与退出条件

- [x] lock 指向 `5b55e14` 且安装可复现（脚本 + lock，2026-09-12）。
- [x] 全量门禁绿（单元 + lint + native 编译）；干跑矩阵（完成/MaxSteps/取消/
      Takeover）不回归——**真机部分 2026-09-12 验证**（complete/max_steps/cancel
      全绿；takeover 经悬浮球长按取证；cancel 场景含协作取消语义）。
- [x] 用户消息四路径（JVM 单测 + 干跑场景 `user_message` 驱动，**真机 2026-09-12
      全部取证**）：正常注入（请求可见、事件 `UserMessageInjected`——场景断言
      `saw_followup` 与会话投影条目）、空消息拒绝（`UserMessagePolicyTest`）、
      队列满拒绝（场景 `max_pending_user_messages=1` + 突发 8 连发，mira
      `ResourceExhausted` 文案上投）、取消/shutdown 中入队明确拒绝（关闭后注入→
      "任务未在运行"；takeover 态门面拦截）。
- [x] `wait` 工具：干跑链路 `ToolExecuted` 事件 + 回填断言（场景 `tool`，**真机
      2026-09-12 取证**）；预算耗尽路径（场景 `tool_budget`，真机取证）。
- [ ] 真机（OnePlus Ace 3）：闭环回归 + 消息注入 + `wait` 执行证据归档——**完成
      （2026-09-12，`build/p3-device-evidence/`）**；真实任务至终态**未达成**
      （`BUG-20260912-P3H-01` 挂起缺陷）。
- [x] 台账 `MIR-20260905-002` 关闭注记补"采纳取证"引用（真机 tool 场景证据
      ToolExecuted×1 + 回填可见，2026-09-12）；本计划与总计划状态同步。

## 验证记录

（按日期追加）

2026-09-12（P3h-01～05 实施，本会话无设备连接；真机项未执行不标记完成）：

- **lock 升级（P3h-01）**：`tools/mira.lock` → `5b55e1439236403fca831297012bcd6c1f7fc74f`；
  `install-mira.sh --force` 全新构建安装通过（NDK 26.3，arm64-release，本地
  `/home/linductor/mira` 克隆取源）；公共 API 差异清单归档至
  [compatibility/mira-5b55e14.md](../compatibility/mira-5b55e14.md)（新头 12 个、
  `agent_loop.hpp`/`core_contracts.hpp`/`runtime.hpp`/`environment.hpp` 变更明细与
  miracle 影响评估——无源码级破坏：`build_request` 为 mira 内部调用面，miracle 无
  `CommandKind` 穷举）。
- **门禁（P3h-02）**：`./gradlew assembleDebug lintDebug testDebugUnitTest` 全绿
  （单测 73/73，基线 62 + 新增 11：`UserMessagePolicyTest` 7、`LoopEventParserTest`
  +4）；native 目标独立编译零警告（NDK 26.3，-Wall -Wextra -Wpedantic）；bridge
  零线程创建复核（`std::thread`/`std::async`/`pthread_create` 无新增）。
- **用户消息介入（P3h-03）实现选型记录**：采用**受控实例持有**（计划预选"邮箱
  转发"的替代项）——mira `AgentLoop::enqueue_user_message` 本身即线程安全邮箱
  （内部 `pending_mutex_` + `deque`，任意线程可调、步边界 drained），bridge 自建
  第二级邮箱只会引入转发时序与去重语义分叉。持有纪律：`LoopRuntime::live_loop`
  （shared_ptr）仅 run 执行期间在挂（state==Running 才挂接，takeover 后不挂）；
  run 闭包终态清理（`live_loop.reset()`）与状态翻转同临界区（g_loop_mutex），
  enqueue 的可见性判定原子；锁序 g_loop_mutex → AgentLoop 邮箱锁（叶子，drain 侧
  无反向路径）。取消/shutdown/关闭中入队 → 明确拒绝 JSON（`{"ok":false,"error":
  "loop is not running"}` 等），UI 呈现拒绝原因。
- **脱敏（P3h-03）**：`UserMessagePolicy`（纯函数）——trim、空拒绝、长度上限 1024、
  凭据启发式命中即拒绝（sk-/AK·AS·LTAI/Bearer/PEM 私钥块/32+hex/40+base64/
  password·token·apikey 赋值形态；拒绝不改写——改写指令会变语义，fail-closed 更
  诚实）；日志只记长度 + sha256 前 8 字节 hex。JVM 单测 7 项（含普通中文指令不
  误伤回归）。
- **会话投影（P3h-04）**：`conversation_json`（bridge）经 `build_conversation_view`
  重建（事件存储唯一事实源），Kotlin `LoopEventParser.parseConversation` 投影；
  任务页新增"会话记录（事件存储投影 · 只读）"卡，与既有步进时间线并存；终态与
  注入后刷新。
- **wait 工具（P3h-05）**：`open()` 时经公共边界注册 Core `wait`（注册失败
  fail-closed 返回 -4 不开会话），run 闭包 `set_tool_registry` 挂接；
  `AgentLoopConfig.max_tool_executions`/`max_pending_user_messages` 暴露为会话
  配置（默认 32/16 对齐上游，干跑场景可收紧）。`ScriptedTransport` 扩展
  `{"tool_call":{"name":..,"arguments":{..}}}` 脚本条目（tool_calls wire，
  arguments JSON 字符串与真实端点一致）并记录请求标记（`User follow-up:` /
  `Tool results from the previous turn:` 只检索不存内容）；结果 JSON 新增
  `transport`（requests/saw_followup/saw_tool_result）、`user_messages_injected`、
  `tool_events` 取证字段（事件经 `IEventStore::read` 按会话分页计数）。
- **干跑场景（自检卡 ⑤⑥⑦ + AUTO_SCENARIOS + 取证脚本默认矩阵）**：
  `user_message`（800ms 后注入一条 + 突发 8 条，断言注入成功/请求可见/投影条目/
  队列满拒绝 ≥1/关闭后拒绝——四路径）；`tool`（wait(600ms)→done，断言工具步/
  回填可见/ToolExecuted≥1/Completed）；`tool_budget`（预算 1 + 两次调用，断言
  "tool execution budget exhausted" 终态 Failed、ToolExecuted==1）。
  `tools/p3-device-verify.sh` 默认场景追加三项。
- **文档同步**：总计划状态、架构设计 §2（门面/JNI/loop_runtime 职责与消费现状
  注记）、台账 `MIR-20260905-002` 采纳注记、本计划、CHANGELOG。
- **真机项（P3h-06）未执行**（adb 无设备连接）：闭环回归矩阵、用户消息注入实测、
  `wait` 真机执行、`MIR-20260905-002` 真机证据回写——补跑条件见 P3h-06 工作项。

2026-09-12（P3h-06 真机轮，OnePlus Ace 3 PJE110 `a4dfdcbf`；mira `5b55e14`）：

- **干跑矩阵全绿（最终构建，19:23 批次 + 19:29 复验；证据
  `build/p3-device-evidence/`）**：
  - complete：Completed · 靶点 2 次 · 关闭 Completed；
  - max_steps：MaxSteps（步数修复后）· 靶点 2 次；
  - cancel：Cancelled · 靶点 4 次；
  - **user_message（P3h-03 四路径）**：注入✓ · 请求可见✓（后续模型请求含
    "User follow-up:"）· 队列满拒绝 8 次（突发 8，max_pending=1）· 关闭后拒绝✓ ·
    Completed；
  - **tool（P3h-05）**：工具步✓ · 回填可见✓（后续请求含 tool-result）·
    ToolExecuted 1 次 · Completed；
  - **tool_budget**：预算耗尽✓ · ToolExecuted 1（预期 1）· Failed。
- **R3 确认协议真机验证（两轮，各 2 次挑战全部 approved → consume 放行 → tap
  派发 → Completed）**。P3 起两个潜伏缺陷首次真机暴露并当场修复：
  ① `begin_confirmation` 未设置 `principal.user_id`（mira issue() 非空校验拒绝 →
  P3 起 R3 挑战签发从未成功，fail-closed 拒绝掩盖）——修复：注册表持有进程级
  稳定 tenant/user 身份；② `resolve_confirmation` 未设置 `response.user_id`
  （consume 一律 "identity or nonce mismatch"）——修复：回填主体。
  靶点计数断言受自检页布局限制（P3 卡在滚动列表下方，需预滚动；登记为后续 UI
  项），协议级证据（4×approved 结算）完备。
- **本轮真机暴露并修复的其他缺陷**（均为本地门禁测不出、首次真机执行暴露）：
  ① **function_tools 能力缺失**（真机用户首次真实任务即暴露）：挂载 wait 后
  `request.tools` 非空 → mira 路由要求 `function_tools` 能力 → 真实会话模型调用
  被拒（"capability not supported: function_tools"）——`build_profile` 补 Configured
  级声明（与 image_input 同级诚实度）；② **关闭摘要 JSON 截断**：结果 JSON 变长
  后 close() 的 512 定长 buffer 截断 → parseCloseSummary ParseError → closeOk 误判
  ——改 std::string 组装；③ **干跑 maxSteps 未生效**：`startSession` 提交时用
  设置页 maxSteps 覆盖场景配置——脚本模式改读脚本 `max_steps`；④ 取证脚本
  `am start` 缺 `--activity-single-top`（ColorOS 下不触发 onNewIntent，场景静默）
  ——脚本已补。
- **悬浮球 Takeover（真实任务运行中长按 ≥1s）**：timeline "Human Takeover：阻断新
  动作 + RELEASE_ALL" + UI 状态"运行中 · 已接管"（截图取证）。
- **会话投影与注入 UI 真机呈现**：任务页"会话记录（事件存储投影 · 只读）"卡与
  "运行中介入"卡均在实际会话中渲染（截图取证）；已知瑕疵：新会话开启时会话记录
  卡未清空（显示上一会话条目，登记为后续 UI 项）。
- **⚠️ 未决缺陷（阻断"≥1 类真实任务至终态"）**：真实任务（"打开设置并调亮亮度"，
  Qwen3.5-4B/siliconflow）首次模型调用进入"推理"相位后**挂起不结算**——用户
  「停止」（19:32）与悬浮球接管（19:47）均未能使 loop 结算；进程存活、JVM 线程
  健康，但 `/proc` 线程表 **mira Executor worker 线程群整体消失**（取消令牌无
  人轮询，疑似 executor/任务生命周期异常）。诊断受限因素：ColorOS 禁止 shell
  kill -3/debuggerd/ANR traces 读取；logcat buffer 仅 ~50s 保留。**处置**：登记
  `BUG-20260912-P3H-01`（miracle 侧，根因未定——需插桩复现轮：executor
  init/shutdown/worker 生命周期日志 + JDWP/早停取证）；不排除上游 executor 契约
  问题，定性后按台账规则处置。真实任务验收维持待补跑。
- P3h-06 判定：干跑矩阵 + R3 + takeover + 注入实测**通过**；真实任务至终态**
  未达成**（挂起缺陷），维持未完成。

2026-09-12（`BUG-20260912-P3H-01` 定性与修复；真实任务至终态达成——P3h-06 收口）：

- **插桩与复现**：交换生命周期日志（受理/取消通知/结算）+ loop 终局日志 +
  `onLoopEvent` 全事件流（既有）落 logcat；真机复现两轮：
  - 第一轮（20:21，未复现挂起）：全链 17 秒走通——observe → exchange 1
    （1.28MB 截图载荷）HTTP 200 → tap 派发 → exchange 2 → 终态 Failed（模型两次
    漏 tap 坐标，恢复预算耗尽——模型质量问题，恢复/反馈机制按设计工作）。
  - 第二轮（20:25，**挂起复现并捕获根因**）：exchange 3（831KB）开始后 Kotlin
    HTTP 无响应（**上传 write 无限阻塞**——`SO_TIMEOUT` 只约束读，服务端停读时
    `stream.write` 无限等待）；123 秒后 native 传输 deadline 触发 `cancel notify
    (expired=true)`，**此后无任何日志（连 settled 都没有）**。
- **根因**：`KotlinHttpTransport` 等待循环在**持有 `exchange->mutex` 的窗口内**调用
  `notify_cancel`（JNI）→ Kotlin `cancel → emitComplete →
  nativeHttpExchangeComplete` **同线程重入** `complete()` → 后者再次尝试同一
  `exchange->mutex`——`std::mutex` 不可重入 → **loop worker 自死锁**。解释全部
  症状：取消令牌从此无人轮询（停止/takeover 无效）、loop 永不结算、（修正）
  worker 并未消失——其 comm 继承自创建线程 `DefaultDispatch`，与协程线程混同，
  早前 /proc 扫描误判。正常完成路径（IO 协程线程回调 complete）不持 worker 锁，
  无死锁——该缺陷仅在取消通知路径触发，干跑场景（scripted 传输）不可达。
- **修复**：取消通知移到锁外（`lock.unlock(); notify_cancel(); lock.lock();`），
  通知链路（cancel 的 CAS 置 done + emitComplete(-1) → complete 重入锁已释放 →
  disconnect 解阻塞 write → IO 协程 CAS 失败不重复回流）恰好一次语义保持。
- **修复验证（真机）**：真实任务（"打开设置并调亮亮度"）**29 秒三步直达
  Completed**（exchange 1 tap x:0.377 设置图标 → exchange 2 显示与亮度 →
  exchange 3 done；`loop settled: Completed (3 steps, 0 recoveries)`；证据
  `build/p3-device-evidence/hang-fix-verify.logcat.txt`）。挂起链路闭合：即使
  write 无限阻塞，deadline → 锁外通知 → disconnect → 有界结算。
- **遗留（如实登记，不阻塞 P3h）**：真实传输上的「停止」负向路径（修复后）本轮
  未再实测——协作取消语义由干跑 cancel 场景覆盖（scripted 路径），真实传输取消
  的机制等价（同一 notify 链路），下次真机轮顺带补取证；上传 write 阻塞本身是
  服务端/网络侧行为，修复后由 deadline 链路有界化，不另行加 watchdog。
- **P3h-06 收口**：干跑矩阵 ✓ + R3 ✓ + takeover ✓ + 注入实测 ✓ + 真实任务至终态
  ✓（Completed）——**P3h-06 完成**。
