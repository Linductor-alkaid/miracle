# 变更日志

本文件记录版本级变化（工程规范 §10.5）。格式遵循 Keep a Changelog；版本号与 Git tag 对应。

## [未发布] P3

### 新增

- 闭环运行时（`loop_runtime.cpp`）：mira AgentLoop 全链组装（Executor/AndroidHostAdapter/
  ModelGateway/OpenAiCompatibleProvider/SimpleAdmissionGate/MemoryEventStore/
  ModelDoneVerifier）；`AgentLoop::run` 经 `submit_cancellable` 协作取消提交；
  takeover ＝ admission 失效 → 取消 → `interrupt()`（RELEASE_ALL）→ 确认失效；关闭按
  §17.2 顺序有界排空。
- 模型传输（宿主实现公共 `IHttpTransport`，MIR-20260906-006 缓解）：C++ 薄适配（封送 +
  50ms 协作轮询 + 取消 disconnect 通知 + 容量 8）+ Kotlin `HttpURLConnection` HTTPS 执行
  （https-only、私有/回环/链路本地地址静态与 DNS 双重复核、响应字节上限、恰好一次回流）；
  方言映射/schema 校验/预算仍由 mira 完成。
- 模型连通性自检：文本-only 决策请求（无 ImagePart，图像路径受 MIR-20260906-007 阻断）
  经真实端点验证 transport→provider→gateway→decision 解析全链。
- R3 确认协议（DEC-004，mira `ConfirmationAuthority` 为协议权威）：dispatch_input 受理后
  准入询问（目标关键词/敏感应用+type 策略表，默认从严）；挑战绑定动作 digest+nonce+
  task/environment epoch，60s 到期，consume 即失效；放行按停放参数派发，拒绝/到期/取消按
  REJECTED/CANCELLED(side=0) 结算；release_all 永不确认。
- 同意与告知：首启披露（可重置复验）、SessionGate 准入（披露+无障碍/投影/悬浮窗/通知+
  配置完整性，缺项逐项引导）、R3 确认对话框宿主（倒计时/摘要/单次授权）。
- 配置与凭据：设置页（端点/前缀/模型/方言/步数上限）；API key 经 AndroidKeyStore
  AES-256-GCM 加密落 filesDir（JVM 可注入引擎已单测；AndroidKeyStore 版真机验证）。
- 提供商预设（P3 补充需求）：设置页内置主流 OpenAI 兼容提供商目录（OpenAI/DeepSeek/
  智谱 GLM/Moonshot Kimi/阿里云百炼 Qwen/OpenRouter/MiniMax/SiliconFlow），一键套用
  端点/前缀/方言/模型建议值，仅需填写 API key；mira 公共 API 无提供商注册表——
  MiniMax/SiliconFlow 取自 mira docs/model_provider 上游在用配置，其余按各厂商公开
  兼容端点配置（Configured 级），互操作以连通性自检为准；上游配置凭据不入本仓库。
- 双前端：任务台（新目标/会话卡/时间线/停止/接管）、设置页、Onboarding 引导卡、
  底部三页导航（任务/自检/设置）；悬浮球（状态环相位映射/拖动/单击展开 Compose 面板/
  长按 ≥600ms takeover）+ 常驻通知停止/接管 action 与状态文案联动。
- P3 自检：干跑四场景（① 完整闭环 tap→tap→done ② 步数上限 ③ 中途取消 ④ R3 确认），
  脚本化决策 + 真实 observe/act，自身 UI 靶点副作用断言。
- 上游台账：`MIR-20260906-006`（安装包未导出传输头文件）、`MIR-20260906-007`（loop
  图像 artifact 对真实 VLM 不可消费——真实任务验收阻断项）。

### 变更

- 主 GUI 由单列自检页改为底部三页导航（任务/自检/设置），P0–P2 自检卡保留于自检页。
- `AgentForegroundService`：通知增加停止/接管 action、文案随会话状态联动；悬浮球随
  服务生命周期显示（v1）。

### 修复

- P1 环境自检停留"正在执行环境自检…"不呈现结果（用户报告：整屏授权后录屏指示出现、
  两帧已输出）：根因为 UI 状态机搁浅——自检启动守卫进程级一次性而结果状态按
  ViewModel 实例私有，进程内第二次授权（"再次自检"）置 Running 后无完成方。自检
  状态机重构为进程级 `CaptureSelfTestCoordinator`（状态共享、在途去重、重跑折叠），
  "再次自检"重新执行 native 自检，Activity 重建实例投影同一状态流；连带修复前台
  服务对携带新授权数据的重复 start 静默丢弃（改为拆旧建新重建投影会话，含旧投影
  迟到 onStop 守卫），以及截屏取帧与像素拷贝并入同一 frameLock 临界区（消除整屏
  帧翻动下持帧被 listener 关闭的竞争）。详见 docs/plans/p1-screen-capture.md
  2026-09-06 验证记录；真机双模式矩阵复验待设备接入补跑。

### 验证

- 本地门禁：`assembleDebug lintDebug testDebugUnitTest` 全绿（单测 54/54，新增 34，含提供商预设 6）；
  native 目标 NDK 独立编译零警告（-Wall -Wextra -Wpedantic）；自研代码零线程创建、
  无 GlobalScope（grep 复核）。
- P1 自检修复门禁：`testDebugUnitTest assembleDebug lintDebug` 全绿（新增
  `CaptureSelfTestCoordinatorTest` 8 例：首跑/重跑/在途折叠/拒绝/补跑/服务超时/
  失败负载/异常路径，合计 62/62）。
- 真机（OnePlus Ace 3）与真实 VLM 任务取证待补跑（实施会话无设备连接；真实任务另受
  MIR-20260906-007 阻断）：补跑条件见 docs/plans/p3-loop-mvp.md。

## [未发布]

### 新增

- 文档基线：可行性分析、四份设计（架构/工具集/前端/构建打包）、DEC-001~003、
  实施总计划、P0 里程碑计划、工程规范与 AGENTS.md、上游反馈台账。
- P0 工程骨架：Gradle 单 APK 工程（arm64-v8a、NDK 26.3.11579264、minSdk 26）。
- mira 安装消费链路：`tools/mira.lock` 钉死 mira 0.1.0 @ `16e419e0`，
  `tools/install-mira.sh` 构建安装到本地前缀，Gradle/CMake `find_package(Mira)` 消费。
- native 自检桥：`libmiracle_host.so`（`Mira::core`）经 JNI 暴露
  RuntimeBaseline 初始化/提交/等待/干净关闭自检，结构化 JSON 结果。
- 空壳 GUI：Compose 单 Activity 自检页（状态、耗时、mira 版本、降级状态）。
- CI：GitHub Actions（SDK 组件与 NDK 钉版本、mira lock 校验、assembleDebug +
  lintDebug + testDebugUnitTest、APK 产物上传）。

### 验证

- 本地：`assembleDebug lintDebug testDebugUnitTest` 全绿（单测 3/3）；APK 含
  `libmiracle_host.so`（arm64-v8a）。
- 模拟器（API 35 google_apis x86_64 + ARM 翻译）：应用启动自检
  `"ok":true`，RuntimeBaseline 终态 `Stopped`，mira 0.1.0；干净退出无崩溃。
- 集成备注：mira 安装包消费需 PIC 开关与 find-root 前缀注入
  （见 `docs/compatibility/mira-16e419e.md`）。
- OnePlus Ace 3（PJE110，Android 16 / API 36 / ColorOS 16）真机冒烟：安装、启动自检
  `"ok":true`（init 0ms / wait 1ms）、终态 `Stopped`、干净退出——mira Android arm64
  首批真机运行证据（Runtime verified(device)）。

## [未发布] P1

### 新增

- Host ABI v1 宿主实现（`host_abi_impl.cpp`）：生命周期、能力/拓扑查询、操作注册表
  （exactly-once 竞争结算）、capture_frame 全路径（native 拥有 lease 内存）、协作
  取消；ui_tree 与输入 fail-closed。
- 截屏管线（Kotlin）：MediaProjection 授权 → `mediaProjection` 类型前台服务 →
  VirtualDisplay + ImageReader(RGBA_8888) → 行紧凑拷贝；降采样 0.9M px（上游
  artifact store 8MB 约束，`MIR-20260905-004`）；旋转/投影失效 epoch 递增。
- 环境自检：Executor + `AndroidHostAdapter::create` + observe×2 + 干净关闭，
  JSON 结果含两帧描述符与 bridge/host 统计；P1 自检页（授权引导、帧预览、
  统计展示、降级状态）。
- 上游回填拟稿：`docs/upstream_feedback/mira-abi-backfill-draft.md`。

### 验证

- 真机（OnePlus Ace 3）：两帧 640×1406 RGBA8888（155.8/106.8ms），`"ok":true`，
  epoch 一致，无违规回调，干净退出（Runtime verified(device)）。
- 模拟器（API 35 x86_64 + ARM 翻译）：两帧 636×1414，同样通过。
- 集成修复三项宿主契约对齐（out 参数零初始化、out_operation 可空、deadline
  换算），均未修改 mira 源码。

### 多设备（POST-04）

- 新增受支持设备：Huawei ADA-AL00（Android 12 / API 31 / EMUI 14.2）——同一 APK
  零改动，P0+P1 自检均通过（Runtime verified(device)）；观察项：首跑授权对话框
  未阻塞（待查），EMUI 对话框布局与 ColorOS 不同。

## [未发布] P2

### 新增

- Host ABI v1 输入实现（`host_abi_impl.cpp`）：`dispatch_input` 全语义（逐事件
  校验快速失败、操作注册表复用、恰好一次完成结算携带 receipt 与
  side_effect_may_have_occurred）、输入协作取消（异步结算：未提交→CANCELLED、
  已提交→EXECUTION_UNCERTAIN）、`stop` 在途输入有界排空、能力位如实
  （input mask bits 1..7 / max_gesture_duration 60s / max_pointers 1，无障碍
  断开即归零）。
- 输入链路（Kotlin）：`MiracleAccessibilityService`（dispatchGesture/焦点查找/
  前台包名跟踪/epoch 通知）+ `InputDispatcher`（tap/long_press/swipe 手势合成、
  type=ACTION_SET_TEXT、back/home 全局动作、RELEASE_ALL、有界并发 16）+
  `DisplayGeometry`（无投影拓扑兜底，输入链路不依赖投影）。
- P2 自检双轨：直接 ABI 契约探针（非法参数/过期 deadline/RELEASE_ALL/长按中途
  取消/取消后复验）+ 经 mira adapter 会话（InputSequence→execute() 全链路，
  对自身 UI 断言副作用）；P2 自检页（无障碍引导、靶点/长按/滑动/文本/BackHandler
  交互区、探针与会话结果、违规计数展示）。

### 修正

- 输入取消语义按平台事实落地：公共 API 无 `AccessibilityService.cancelGesture`
  （API 35 android.jar 实测），已提交手势不可中断、自然收敛后按
  `EXECUTION_UNCERTAIN+side=1` 结算（host_abi.h 冻结的原子输入约定）；
  "合成抬起事件"登记为已知限制。
- Compose `boundsInWindow` 与屏幕原点的厂商偏差（真机约 77px）经
  `LocalView.getLocationOnScreen` 屏幕锚定修正。
- 无障碍服务独立启动路径的 native 库加载崩溃（`UnsatisfiedLinkError`）修复：
  `HostBridge.ensureNative()` 幂等守卫。

### 验证

- 真机（OnePlus Ace 3）：探针 5/5（取消→EXECUTION_UNCERTAIN side=1、取消后 tap
  复验通过）；adapter 会话 7/7 步 Completed（tap/long_press/swipe/back/type/
  home），UI 副作用断言全过，违规计数全 0，连续两次完整通过；负向
  （无障碍未连接→PermissionDenied fail-closed、无崩溃）；旋转 epoch 递增（1→2）
  且旋转后落点正确；force-stop 干净退出（Runtime verified(device)）。
- 单测 20/20；`assembleDebug lintDebug testDebugUnitTest` 全绿。
- 兼容性：ColorOS 16 拦截无障碍源 `GLOBAL_ACTION_HOME`（派发成功不导航），
  经 CATEGORY_HOME intent 兜底（前台会话可达）；记录于
  `docs/compatibility/oneplus-ace3.md`。
- 上游台账：新增 `MIR-20260906-005`（mira InputSequence 不携带手势时长）。
- 已知限制：模拟器输入回归未执行（补跑条件见 P2 计划）；home 后台导航兜底
  受后台启动限制（P3 悬浮窗权限后覆盖）。

### P2 修复（2026-09-06）

- type 步骤在用户操作路径下 Rejected 的三项修复（生产语义不变，仍 fail-closed）：
  焦点重试窗口 500ms→2s（有界）；焦点子树内有界解析可编辑节点（覆盖容器聚焦
  的 Compose 无障碍暴露差异）；自检页 type 前确定性聚焦辅助。真机复验：用户式
  滚动布局与 Done 后布局均 ✅（type=Completed、零告警、home 达 launcher）。
- 上游反馈：台账 5 条 MIR 条目、`leases_released` 统计观察项（自 MIR-004 备注
  拆出）与 android-host-abi.md 证据回填拟稿已提交 mira issue #7–#13；台账与
  拟稿回填对应 issue 链接（提交前证据均经 mira `16e419e` 源码复核）。
