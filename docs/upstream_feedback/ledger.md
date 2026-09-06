# 上游反馈台账（mira 公共 API / Executor）

> 状态：Active
> 建立日期：2026-09-05
> 规范：[工程规范 §9.4](../project/project-standards.md)

登记规则：先核对 pinned mira 版本的公开头文件、API 文档与测试，排除选型/配置错误、平台
限制和应用层职责；每条必须含可复现证据、影响范围、期望语义与可验收结果。分级：
`P1` 系统性将就 / `P2` 结构性将就 / `P3` 有而未用 / `违规`。

## 条目

### MIR-20260905-001：AndroidHostAdapter 不提供 structure 观察

- 状态：Open（已反馈上游：mira [#7](https://github.com/Linductor-alkaid/mira/issues/7)，2026-09-06）
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

- 状态：Open（已反馈上游：mira [#9](https://github.com/Linductor-alkaid/mira/issues/9)，2026-09-06）
- 分级：P3
- 证据：mira `CMakePresets.json` 仅有 `android-arm64-*`；平台矩阵声明单 ABI。
- 影响：无真机时缺乏模拟器回归路径（Miracle `POST-02`）。
- 期望语义：提供与 arm64 等价的 android-x86_64（或 universal）预设并进 CI。
- 可验收结果：Miracle CI 可在 x86_64 模拟器上运行 instrumented 冒烟。

### MIR-20260905-004：AndroidHostAdapter 内置 MemoryArtifactStore 容量不可配置

- 状态：Open（已反馈上游：mira [#10](https://github.com/Linductor-alkaid/mira/issues/10)，2026-09-06）
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
  （已单独反馈上游：mira [#12](https://github.com/Linductor-alkaid/mira/issues/12)）

### MIR-20260906-005：InputSequence 不携带手势时长

- 状态：Open（已反馈上游：mira [#11](https://github.com/Linductor-alkaid/mira/issues/11)，2026-09-06）
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

- 状态：Open
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
- 备注：Miracle P3 经公共接口自建 Kotlin HTTPS 传输为临时缓解，上游修复后切换官方
  transport 走独立变更。

### MIR-20260906-007：AgentLoop 图像 artifact 路径对真实 OpenAI 兼容 VLM 不可消费

- 状态：Open
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
- 备注：与 `MIR-20260905-004`（artifact 容量不可配置）同源不同症，建议合并评估。
