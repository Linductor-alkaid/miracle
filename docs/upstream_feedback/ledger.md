# 上游反馈台账（mira 公共 API / Executor）

> 状态：Active
> 建立日期：2026-09-05
> 规范：[工程规范 §9.4](../project/project-standards.md)

登记规则：先核对 pinned mira 版本的公开头文件、API 文档与测试，排除选型/配置错误、平台
限制和应用层职责；每条必须含可复现证据、影响范围、期望语义与可验收结果。分级：
`P1` 系统性将就 / `P2` 结构性将就 / `P3` 有而未用 / `违规`。

## 条目

### MIR-20260905-001：AndroidHostAdapter 不提供 structure 观察

- 状态：Resolved（上游 mira `cbed6ad`，PR [#16](https://github.com/Linductor-alkaid/mira/pull/16) `b12383d` + DEC-012）
- 关闭注记（2026-09-06）：上游 `capabilities().ui_tree` 镜像宿主 `accessibility_completeness`；
  `observe()` 经 `MIRA_HOST_OP_GET_UI_TREE` 聚合 `mira.host.tree.v1` JSON（fail-closed 解析、
  未知 token 降级、[0,1] 规范 bounds、BoundedSkew 声明）。Miracle 宿主侧仍声明
  `accessibility_completeness = 0`（UI 树聚合未实现，能力诚实）；启用为后续计划项
  （Kotlin 无障碍树 → mira.host.tree.v1 序列化 + completeness 声明切换）。
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

- 状态：Open（与 DEC-002 的 `POST-01` 关联；已反馈上游：mira
  [#8](https://github.com/Linductor-alkaid/mira/issues/8)，2026-09-06）
- 分级：P3（当前未使用该能力，属方向性缺口）
- 证据：mira `16e419e` 的 `src/model/agent_loop.cpp`："tool proposals are not executable
  in the M3 loop"；DEC-009/M7 Blocked。
- 影响：扩展工具只能走本仓库 L3 旁路（直连 ModelGateway）或等待上游。
- 期望语义：闭环可执行经 allowlist 暴露的工具并回填 `ToolExecutionRecord`。
- 可验收结果：以 `POST-01` 触发时的证据重新定义。

### MIR-20260905-003：缺少 android-x86_64 构建预设

- 状态：Resolved（上游 mira `cbed6ad`，PR [#16](https://github.com/Linductor-alkaid/mira/pull/16) `403e69d`+`6d49cc7`，CI 含 arm64+x86_64 矩阵）
- 关闭注记（2026-09-06）：上游提供 `android-x86_64-base/-release` 预设与工具链文件，
  ABI guard 接受双 ABI，CI 矩阵覆盖。Miracle 侧模拟器回归路径（`POST-02`）解锁：
  lock 仍钉 `android-arm64-release`（真机基线），x86_64 消费构建与 instrumented 冒烟
  为后续独立变更（需扩展 lock/构建脚本双 ABI）。
- 分级：P3
- 证据：mira `CMakePresets.json` 仅有 `android-arm64-*`；平台矩阵声明单 ABI。
- 影响：无真机时缺乏模拟器回归路径（Miracle `POST-02`）。
- 期望语义：提供与 arm64 等价的 android-x86_64（或 universal）预设并进 CI。
- 可验收结果：Miracle CI 可在 x86_64 模拟器上运行 instrumented 冒烟。

### MIR-20260905-004：AndroidHostAdapter 内置 MemoryArtifactStore 容量不可配置

- 状态：Resolved（上游 mira `cbed6ad`，PR [#16](https://github.com/Linductor-alkaid/mira/pull/16) `b12383d` + DEC-012；备注统计问题经 `769de83` 修复）
- 关闭注记（2026-09-06）：`AndroidHostAdapterOptions` 支持注入 `IArtifactStore`
  （含 FileArtifactStore）或声明 adapter 自有 store 容量（默认 8→64 MiB）。
  Miracle 随 lock 升级落地：loop 注入 128 MiB `HostFrameStore`（原始帧转码 PNG 后
  即回收）；宿主降采样（0.9M px）自"容量规避"转为"载荷大小策略"，全分辨率档
  待真机补跑评估。`leases_released` 统计口径经 `769de83` 覆盖全部释放路径。
- 分级：P2（结构性：真机分辨率下闭环第二步即耗尽）
- 证据：miracle 真机（PJE110）P1 自检：帧 852×1876 RGBA≈6.4MB，第二帧 observe
  失败 `"artifact store capacity exhausted"`；`AndroidHostAdapter` 构造中硬编码
  `MemoryArtifactStore artifacts_{8ULL * 1024 * 1024}`，公共 API 无容量参数或
  store 注入点。
- 影响：闭环每步 observe 至少一帧；8MB 上限使"多步任务"在主流分辨率下不可行，
  宿主被迫降采样至 0.9M px（640×1406）规避。
- 期望语义：`AndroidHostAdapter::create` 支持注入 artifact store 或声明容量
  （含落盘后端选项）；能力快照同步声明帧预算。
- 可验收结果：注入/容量参数存在时，真机两帧 6.4MB observe 连续成功。
- 备注（观察项）：成功路径中 bridge 统计 `leases_released` 恒为 0，而宿主侧
  release 被调用（outstanding 归零、destroy 无悬挂）；疑似 bridge 侧计数与
  observe 尾部 `outcome.lease.release()` 路径脱节，请上游核对统计口径。
  （已单独反馈上游：mira [#12](https://github.com/Linductor-alkaid/mira/issues/12)；
  修复见 `769de83`，HostLeaseGuard 携带 ReleaseObserver 覆盖全部释放路径）

### MIR-20260906-005：InputSequence 不携带手势时长

- 状态：Resolved（上游 mira `cbed6ad`，PR [#16](https://github.com/Linductor-alkaid/mira/pull/16) `b12383d`）
- 关闭注记（2026-09-06）：`mira::InputEvent` 新增 `duration_ms`（默认 0＝宿主默认），
  adapter 映射到 ABI `duration_ms` 并对超宿主 `max_gesture_duration_ms` 的值拒绝。
  Miracle 侧 `inputTestDispatch` 的 adapter 路径已接线（此前 JNI 参数被丢弃，
  workaround 注释移除）；P2 直接 ABI 契约探针保留（host 侧契约测试语义不变）。
- 分级：P3（v1 决策协议未含时长，宿主默认时长可工作；显式时长控制受限）
- 证据：mira `16e419e` 的 `include/mira/environment.hpp`：`InputEvent` 仅
  `kind + payload(string)`；`adapters/android/android_host_adapter.cpp` 的
  `execute()` 对 long_press/swipe 仅解析坐标，`MiraHostInputEventV1.duration_ms`
  恒为 0。宿主侧 `duration_ms` 默认（long_press 600ms / swipe 350ms）。
- 影响：决策层无法表达"慢速滑动/超长短按"等时长语义；P2 取消契约测试被迫
  走直接 ABI 探针（adapter 路径无法注入 3000ms 长按）。
- 期望语义：`InputEvent` 增加可选时长字段（或 payload schema 扩展），adapter
  填充 ABI `duration_ms`；缺失时维持 0（宿主默认）。
- 可验收结果：经 adapter 路径派发显式时长的 long_press，宿主按指定时长合成。

### MIR-20260906-006：安装包未导出网络传输头文件（有库无头）

- 状态：Resolved（上游 mira `cbed6ad`，PR [#17](https://github.com/Linductor-alkaid/mira/pull/17) `d4eede5`；`include/mira/adapters/net/` 随包导出）
- 关闭注记（2026-09-06）：三个 transport 头（socket/mbedtls/openssl）迁入公共头
  树随包导出，`find_package(Mira)` 消费者可合法声明官方传输类（上游安装消费者
  测试覆盖构造 `SocketHttpTransport` + `MbedTlsChannelFactory`）。Miracle 保留
  Kotlin `IHttpTransport` 宿主传输（公共扩展点，系统信任库、无 PEM CA 供给负担）；
  切换官方 transport 为后续独立变更（Android 侧 CA bundle 供给与取消语义需设计）。
- 分级：P2（结构性：宿主无法使用官方生产传输，被迫自建传输实现）
- 证据：mira `16e419e` 的 `CMakeLists.txt` L400-430：`mira_net_transport`/
  `mira_mbedtls_transport` 安装了库，但 `install(DIRECTORY include/ …)` 只安装主
  include 目录；`adapters/net/socket_transport.hpp`、`adapters/net/mbedtls_tls.hpp`
  未随包导出（`third_party/mira-install/include/mira/` 实测无 `adapters/net/`）。
  符号 `SocketHttpTransport`/`MbedTlsChannelFactory` 在库中可见，但消费者无法以合法
  头文件声明它们。
- 影响：P3 起需要模型调用的宿主只能：(a) 自行实现公共 `IHttpTransport`（Miracle P3
  采用，重复 socket/HTTPS 工作），或 (b) 以手写类声明消费非导出头（ABI 脆弱，违反
  消费边界）。M3 测试自身依赖测试内 `MockHttpTransport`，官方 transport 无包外消费者。
- 期望语义：`adapters/net/*.hpp` 随安装包导出（如 `include/mira/adapters/net/`），
  `Mira::net_transport`/`Mira::mbedtls_transport` 目标携带对应 usage requirement。
- 可验收结果：消费者仅以安装前缀 + `find_package(Mira)` 即可构造
  `SocketHttpTransport` + `MbedTlsChannelFactory`（PEM CA 由宿主提供）并发起 https
  模型调用；新增最小消费者测试进 mira CI。

### MIR-20260906-007：AgentLoop 图像 artifact 路径对真实 OpenAI 兼容 VLM 不可消费

- 状态：Resolved（上游 mira `cbed6ad`，PR [#17](https://github.com/Linductor-alkaid/mira/pull/17) `423c837` + DEC-013"宿主负责编码"；Miracle 宿主编码已随 lock 升级落地，真机取证待补跑）
- 关闭注记（2026-09-06）：`ScreenFrameDescriptor` 携带 `payload_media_type/
  payload_byte_size/payload_digest`（adapter 自 store commit 记录填充）；
  `build_request` 的 `ArtifactRef` 取自该记录；方言层对非 image 系媒体类型在
  fetch 前 fail-closed（本地可诊断）。Miracle 落地链路：Kotlin 截屏同步 PNG 编码
  → 帧完成以原始字节 sha256 为键登记 → 注入 loop 的 `HostFrameStore`（DEC-012
  注入点）commit 时重新发布为 `image/png` 工件 → `StoreArtifactSource` 经公共
  store API 供 wire 层回读 → data:image/png 数据 URL。P3 真实任务（≥3 类）补跑
  条件仅剩：真机连接 + 用户配置真实端点。
- 分级：P1（系统性：闭环真实模型任务全量阻断）
- 证据：mira `16e419e` 的 `src/model/agent_loop.cpp` `build_request()`：截图
  `ArtifactRef.media_type` 硬编码 `"application/octet-stream"`、`byte_size = w*h*4`
  （原始 RGBA）；`src/model/model_dialect.cpp` `fetch_image_data_url()` 据此生成
  `data:application/octet-stream;base64,<RGBA>` 数据 URL——真实 OpenAI 兼容端点要求
  `image/png`/`image/jpeg`。同时 `AndroidHostAdapter::artifacts_`（MemoryArtifactStore）
  私有且无公共读取 API，宿主 `IArtifactSource` 无从取得观察字节（m3 测试经
  `SimulatorEnvironment::open_artifact` 公共 API 取得——Android 侧等价物缺失）。
- 影响：Miracle P3 验收项"≥3 类真实任务端到端"不可达：即使宿主提供传输，闭环每步
  截图部件在 wire 层必然被真实端点拒绝；M3 闭环仅对 fixture transport 验证过。
- 期望语义（任一组合均可）：(a) adapter 提供工件读取 API（或允许注入宿主
  IArtifactStore），宿主负责 RGBA→PNG/JPEG 编码与降采样（顺带缓解 MIR-004 的 8MB
  容量问题），且 `build_request` 的 `ImagePart` 媒体类型来自工件描述符而非硬编码；
  或 (b) 上游在 provider/mapper 内完成图像编码并声明媒体类型。
- 可验收结果：真机截图经 ABI 进入 Observation 后，以 `image/png`/`image/jpeg` 数据
  URL 出现在 wire 请求体（真实端点 200 + decision 返回）；Miracle 补跑 ≥3 类真实任务
  取证（P3 退出条件）。
- 备注：与 `MIR-20260905-004`（artifact 容量不可配置）同源不同症，上游 DEC-012/013
  合并评估后一并修复。

### MIR-20260906-008：AgentLoop 截图 ArtifactRef 漏填 payload digest

- 状态：Resolved（上游 mira `635e136`，PR
  [#20](https://github.com/Linductor-alkaid/mira/pull/20) `a839565`；Miracle 随
  lock 升级落地并真机验证通过）
- 关闭注记（2026-09-06）：上游 `build_request()` 补 `reference.digest =
  screen.payload_digest`，并补测试盲区（`SimulatorArtifactSource` 按发布记录校验
  digest/byte_size + fail-closed 用例）。Miracle 真机（PJE110）：lock 升级重装后
  真实任务闭环 observe → 模型调用（截图 wire `data:image/png` + http 200 +
  决策返回）全通——MIR-008 阻断解除；后续阻断点为 `MIR-20260906-009`（决策
  schema 不约束动作参数）。
- 分级：P1（系统性：闭环真实模型任务全量阻断）
- 证据：mira `cbed6ad` 的 `src/model/agent_loop.cpp` `build_request()`：截图
  `ArtifactRef` 设置 `id/media_type/sensitivity/byte_size`，未设置 `digest`——
  `ScreenFrameDescriptor.payload_digest`（adapter 自 store commit 记录填充，
  `android_host_adapter.cpp:587`）未流入 ref，digest 保持零值。miracle 真机
  （PJE110，`timeout_ms` 传递修复后）：observe 正常、PNG 工件重新发布成功，
  模型调用阶段 `StoreArtifactSource`（按 DEC-013 语义以 ref 重建 descriptor
  后 `open()`）触发 `MemoryArtifactStore::open` 完整性校验
  `artifact descriptor integrity mismatch`（DataLoss），闭环第一步 model call
  即失败（`Failed`，steps 0）。
- 测试盲区：mira `tests/m3/m3_agent_loop_test.cpp` 的
  `SimulatorArtifactSource::fetch()` 只按 `reference.id` 打开，不校验 digest，
  漏填在上游测试中不可见（DEC-013 媒体类型/字节数断言均在 wire body 侧）。
- 影响：Miracle P3 "≥3 类真实任务端到端"不可达（继 007 关闭、宿主编码链路落地
  与 `timeout_ms` 修复之后的下一个阻断点）；任何按公共契约从 `ArtifactRef`
  重建 descriptor 消费内容寻址 store 的宿主同样 fail-closed。
- 期望语义：`build_request()` 补 `reference.digest = screen.payload_digest`；
  建议 m3 测试的 `SimulatorArtifactSource` 按发布记录校验 digest/byte_size
  （防回归）。
- 可验收结果：真机闭环截图以 `data:image/png;base64,` 出现在 wire 请求体且
  真实端点 200 + decision 返回；mira 测试含 digest 断言。
- 备注：与 `MIR-20260906-007` 同域（DEC-013 落地后暴露）：007 修复了
  media_type/byte_size 的传递，digest 漏填。

### MIR-20260906-009：决策 schema 不约束动作参数，合法决策在 compile 阶段 fail-closed 且无 repair 重试

- 状态：Resolved（上游 mira `d1993d2`，PR
  [#22](https://github.com/Linductor-alkaid/mira/pull/22) `9a1dd28`）
- 关闭注记（2026-09-06 真机验证）：compile 失败在恢复预算内以静态诊断作为
  feedback 重试、预算耗尽才带原因终态（负路径由上游 m3 测试"动作参数缺失"
  用例覆盖）。Miracle 真机（PJE110 @ `d1993d2`）：模型首次决策即输出带规范
  坐标的合法 tap（`{"action":"tap",...,"x":0.396,...}`）并成功编译执行
  （设置应用被打开）——决策编译链路正向验证通过，本轮未再复现参数缺失
  （模型输出质量波动）；后续阻断点为 `MIR-20260906-010`（验证观察零组件
  请求被 Android adapter 拒绝）。
- 分级：P1（系统性：真实任务闭环阻断——MIR-008 解除后的下一阻断点）
- 证据：mira `635e136` 的 `agent_decision_schema()`（`src/model/agent_loop.cpp:78`）
  `required` 仅 `["action","reason"]`，动作参数（x/y、end_x/end_y、text）对任何
  action 非必填且无条件依赖；`compile_discrete_action`（同文件 :123）对
  tap/long_press/swipe/type 要求参数存在。miracle 真机（PJE110，
  Qwen/Qwen3.5-4B 多模态，docs/model_provider 在用配置）：模型调用 http 200，
  响应 `{"action":"swipe","reason":"I should open the Settings application."}`
  （无坐标）——schema 校验通过（violations=0），compile 失败，AgentLoop 直接终态
  `Failed: "decision did not compile to a discrete action"`（recoveries=0、
  repairs=0，无反馈重试）。诊断经 miracle 传输层响应摘要日志
  （`miracle/transport`）取得。
- 次要问题：决策编译失败路径（:512-517）直接 break Failed；"model claimed done
  but verification disagreed" 同类可修复错误走 feedback continue，参数缺失型
  `InvalidModelOutput` 却无 repair 机会。
- 影响：小参数量多模态模型产出"动作+理由、省略坐标"为高频模式，真实任务闭环
  一步即终态；miracle 无法在仓库侧修补（决策 schema 为 mira 冻结契约，
  `SchemaId 6d6972612d6465636973696f6e2d7631` 随请求记录）。
- 期望语义：(a) schema 条件必填（tap→x,y；swipe→x,y,end_x,end_y；type→text），
  violation 经既有 rejection 通道反馈重试；和/或 (b) compile 失败归入可恢复
  路径（feedback repair，受 repairs 预算约束）。
- 可验收结果：真机同任务下参数缺失决策触发 schema violation 或 repair 重试
  （attempts/repairs>0 可见），不再一步终态；m3 测试新增"动作参数缺失"用例。
- 备注：与 MIR-20260906-008 同链路先后暴露（DEC-011 验证载体价值体现）。

### MIR-20260906-010：AgentLoop 验证观察发出零组件请求，AndroidHostAdapter fail-closed 拒绝

- 状态：Resolved（上游 mira `874f4a5`，PR
  [#24](https://github.com/Linductor-alkaid/mira/pull/24) `be01a9e`：验证观察声明
  `optional.screen = true`，尽力捕获、失败降级不阻断；m3 测试 +101 行）
- 关闭注记（2026-09-06 真机验证）：PJE110 @ `874f4a5`，真实任务 5 步推进至
  `Completed`（桌面 → 设置 → 显示与亮度 → 滑动 → done 声明验证通过），
  验证观察在每步动作后与 done 声明后均成功——本条阻断解除。遗留两项
  非 mira 缺口：滑动落点错误＝模型视觉 grounding 弱（后续 UI 树 grounding，
  见 MIR-20260905-001 关闭注记）；"Completed"语义＝miracle 误用测试实现
  `ModelDoneVerifier`（miracle 侧修复，见 P3 文档 2026-09-06 记录）。
- 分级：P1（系统性：真实任务闭环每步验证必败——MIR-009 解除后的下一阻断点）
- 证据：mira `d1993d2` 的 `AgentLoop::observe_once()`（`src/model/agent_loop.cpp:200`）
  对 `ObservationMode::Verification` 仅设 `max_age`（required/optional 组件全空）；
  `AndroidHostAdapter::observe()` 对零组件请求 fail-closed（"the host adapter only
  captures screen and structure components"）。miracle 真机（PJE110 @ `d1993d2`）：
  决策编译成功、tap 执行成功（设置应用打开），act 后 87ms 终态
  `Failed: "verification observation failed"`（steps=1、无取消、无网络错误）。
- 测试盲区：`validate_observation_request()` 不要求至少一个组件（请求合法）；
  `SimulatorEnvironment::observe()` 对零组件请求宽容返回，且
  `ModelDoneVerifier` 不消费 Observation（只看 decision.action）——m3 闭环
  测试全程绿，Simulator 与 Android adapter 行为差异不可见。
- 影响：Android 真机闭环每步动作后与 done 声明后的验证观察必然失败；
  "≥3 类真实任务"仍不可达。
- 期望语义：(a) `observe_once(Verification)` 声明 `optional.screen = true`
  （尽力捕获、失败降级不阻断）或 `required.screen = true`（fresh-observation
  严格语义）；或 (b) Android adapter 对零组件请求返回空 Observation 与
  Simulator 对齐；建议 m3 契约测试断言 Verification 请求的组件声明。
- 可验收结果：Android 真机动作后验证观察不再失败，done 声明后
  fresh-observation 验证路径可达；闭环可走多步直至 Completed。
- 备注：MIR-009 关闭后同链路暴露（第三次：Simulator 宽容/Android 严格的
  契约分叉——DEC-011 验证载体价值持续体现）。
