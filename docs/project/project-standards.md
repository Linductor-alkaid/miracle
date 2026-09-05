# Miracle 项目管理与工程规范

> 状态：Active
> 版本：1.0
> 生效日期：2026-09-05
> 适用范围：Miracle 的代码（Kotlin/C++）、测试、设计、计划、决策、验证记录、发布材料和 Git 仓库操作

本规范由 `~/cpp-project-template`（其本身归纳自 Mira 与 Heyaki 两个 C++20 项目的实践）裁剪
适配而成：保留其规划层级、稳定编号、证据分级、依赖反馈台账、Git 规范与评审流程思想；
将"Executor 强制基础设施"条目收缩为 native bridge 层职责（Kotlin 层使用 Android 结构化
并发），"C++ 工程基线"替换为"Android + NDK 混合工程基线"，并新增 mira 上游缺口回流台账
（对齐 mira DEC-011 对 demo 仓库的要求）。

## 1. 目的与规范效力

本文定义项目的规划、任务跟踪、技术决策、文档编写、验证取证、上游依赖使用、Git 仓库
管理和变更同步规则。目标是让每项工作都能回答：为什么做、由什么设计约束、当前做到哪里、
如何证明完成，以及后续如何复现结论。

仓库内规则按以下顺序解释：

1. 用户或维护者对当前工作的明确要求。
2. 根目录 `AGENTS.md` 中的仓库级强制约束。
3. 本文中的项目管理与工程规范。
4. 已批准的设计文档和决策记录。
5. 总计划、里程碑计划和工作项中的局部约定。

低层文档不得静默覆盖高层约束。若实现需要改变已生效的架构、公开边界（ABI 语义、门面
API）、安全边界（同意/确认策略）或产品默认值，必须先新增或更新决策记录，再同步修改
设计、计划和测试。

文档承担不同职责，不互相替代：设计文档说明系统应如何工作与为什么；决策记录说明有实质
影响的选择、备选与后果；计划文档说明交付顺序、范围、状态与验收方式；验证记录说明某项
声明在什么条件下被证明；代码是行为的实现，但不能替代契约文档。

## 2. 文档目录

| 路径 | 内容 | 典型文件 |
| --- | --- | --- |
| `docs/design/` | 架构、模块、工具集、前端、构建打包与跨层契约 | `<domain>_design.md` |
| `docs/decisions/` | 架构决策和产品默认值记录 | `DEC-001-<topic>.md` |
| `docs/plans/` | 总计划、里程碑计划和交付清单 | `miracle-implementation-plan.md`、`p1-<scope>.md` |
| `docs/security/` | 威胁模型、隐私、凭据与截图数据处理 | `threat-model.md` |
| `docs/compatibility/` | 设备、固件、mira 版本与模型服务兼容性证据 | `oneplus-ace3.md`、`mira-<commit>.md` |
| `docs/upstream_feedback/` | 上游（mira 公共 API / Executor）能力缺口台账 | `ledger.md` |
| `docs/benchmarks/` | 闭环延迟、动作成功率等指标方法与结果 | `loop-latency.md` |
| `docs/project/` | 本规范及其他仓库级协作流程 | 本文 |
| `docs/`（根） | 可行性等入口级分析 | `feasibility-and-solution.md` |

不要用一个"总文档"承载所有内容。安全结论、兼容性声明、性能数据和上游反馈保留在各自
目录，由设计或计划通过相对链接引用。

## 3. 项目规划层级

### 3.1 总计划

总计划是交付范围与进度的唯一入口：`docs/plans/miracle-implementation-plan.md`。包含：

- 当前整体状态和最近更新时间。
- 交付边界条目（`SCOPE-NN`）：确认包含与明确不包含的能力，逐条可勾选。
- 不可破坏的架构约束（`RULE-NN`）：分层、所有权、同意边界、容量与验证底线。
- 并发边界条目（`EXEC-NN`，见第 9 节）。
- 里程碑索引（`P0`–`P5`）、依赖关系和建议发布点。里程碑必须产生可独立验收的能力增量。
- 尚未冻结的决策（`DEC-NNN` 暂定默认值）及其负责人和最迟冻结里程碑。
- 跨里程碑通用完成定义（`DOD-NN`）。
- 延后项与触发条件（`POST-NN`）。

总计划保持概览性质；具体任务、测试和逐轮实施证据放到独立里程碑文档。

### 3.2 里程碑计划

每个里程碑使用独立文件 `docs/plans/p<N>-<scope>.md`（N 与总计划索引一致），必须声明：
状态、负责人、所属总计划、前置依赖、建议发布点、更新日期、目标、范围与非目标、设计与
决策依据、带稳定编号的工作项、测试矩阵与退出条件、风险与阻塞、按日期追加的验证记录。

### 3.3 工作项与编号体系

工作项是可验收的最小计划单位，使用 `P<里程碑>-<两位序号>`（如 `P1-03`）。工作项描述结果
而非活动，且必须能关联到至少一项验收方式。

| 前缀 | 用途 | 示例 |
| --- | --- | --- |
| `SCOPE-NN` | 总计划交付边界条目 | `SCOPE-01` |
| `RULE-NN` | 总计划不可破坏架构约束 | `RULE-07` |
| `EXEC-NN` | 总计划并发边界条目 | `EXEC-05` |
| `DOD-NN` | 总计划通用完成定义条目 | `DOD-08` |
| `POST-NN` | 延后项及其立项触发条件 | `POST-03` |
| `DEC-NNN` | 架构或产品决策记录 | `DEC-004` |
| `BUG-YYYYMMDD-NNN` | 需要跨提交跟踪的重要缺陷 | `BUG-20260905-001` |
| `RISK-YYYY-NNN` | 影响里程碑或发布的已识别风险 | `RISK-2026-02` |
| `EXE-YYYYMMDD-NNN` | Executor 能力缺口反馈台账条目 | `EXE-20260905-001` |
| `MIR-YYYYMMDD-NNN` | mira 公共 API 缺口反馈台账条目 | `MIR-20260905-001` |

编号一经被引用不得复用；同一文档内前缀必须一致。

## 4. 状态与勾选规则

计划、里程碑和工作项使用以下状态词，不自造相近状态：

| 状态 | 含义 |
| --- | --- |
| `Proposed` | 已提出，范围或方案尚待评审 |
| `Planned` | 范围、依赖和验收方式已明确，可排期 |
| `In Progress` | 已开始实施，尚未满足完成定义 |
| `Blocked` | 有明确外部阻塞，已记录负责人和解除条件 |
| `Completed` | 实现和全部适用退出条件已通过 |
| `Superseded` | 已由另一文档或决策替代，保留历史链接 |
| `Cancelled` | 明确终止且不再计划交付，保留原因 |

决策记录使用 `Proposed`、`Accepted`、`Superseded`、`Rejected`；其落地进度由关联计划工作项
跟踪。

清单勾选规则：

1. 只有实现、测试、必要文档和本项验收均完成后，才能从 `[ ]` 改为 `[x]`。
2. "代码已写完""本机能编译""提交已合并"本身不等于完成。
3. 因设备、权限、网络、凭据或平台缺失而未执行的验证必须保持 `[ ]`，不得以 skip、预期
   通过或其他设备的结果冒充成功。
4. 未执行项必须记录原因、所需环境、负责人和可操作的补跑条件。
5. 工作项全部完成不自动关闭里程碑；所有退出条件也必须逐项通过并留下验证记录。
6. 重新打开已完成工作时保留原验证记录，说明失效原因，并恢复未完成状态。

## 5. 准入与完成定义

### 5.1 Definition of Ready

非平凡工作进入 `In Progress` 前至少满足：

- 目标、范围和非目标可被复述且没有关键歧义。
- 所属里程碑或维护任务明确，并已有稳定编号。
- 相关设计、决策和公开契约已定位；需要改变它们时已有同步更新安排。
- 依赖（mira 版本、设备、权限、模型服务）、风险和并发承载方式已识别。
- 验收标准可执行，所需测试环境已知（真机是否在手、ColorOS 版本等）。
- 涉及用户数据、凭据、截图或外部副作用时，安全与恢复行为已定义。

小型拼写修复等可不创建独立计划项，但仍必须遵守文档规则。

### 5.2 Definition of Done

一项变更只有同时满足以下条件才可完成：

- 实现符合分层、所有权、取消、错误和生命周期约束（AGENTS.md"并发与生命周期纪律"）。
- 新增或变更行为具有与风险相称的自动化测试。
- 适用的构建、测试、静态检查或真机验证已执行并通过。
- 公开契约（ABI 实现、门面 API、事件 schema、风险策略默认值）与示例已同步更新。
- 受影响的设计、决策、计划、安全、兼容性文档已同步更新。
- 验证记录包含可复现证据；未验证声明被明确限定（例如"仅模拟器构建通过"）。
- 不存在被吞掉的失败、无主异步任务、未闭合取消路径、绕过同意门面的代码路径或未登记的
  上游缺口。
- Commit 与 MR 符合第 10 节规范，评审意见已全部回复或处置。

## 6. 文档模板

### 6.1 里程碑文档模板

```md
# P<N>：里程碑名称

> 状态：Planned
> 负责人：姓名或团队
> 所属计划：[Miracle 实施总计划](miracle-implementation-plan.md)
> 前置：P<N-1>
> 建议发布点：<apk-标签或版本>
> 更新日期：YYYY-MM-DD

## 目标

## 范围与非目标

## 设计与决策依据

## 工作项

- [ ] `P<N>-01` 以可验收结果描述任务。

## 风险与阻塞

## 测试与退出条件

- [ ] 给出可观察、可执行的通过条件。

## 验证记录

YYYY-MM-DD：记录 commit、设备/环境、命令、结果、限制和剩余项。
```

验证记录按时间追加，不得重写历史使失败或环境限制消失。

### 6.2 决策记录模板

以下变化必须建立或更新 `docs/decisions/`：跨模块依赖方向与生命周期；公开边界（ABI 语义、
门面 API、事件 schema）；同意与确认边界及默认权限；关键依赖（mira 版本、模型服务）、
支持设备、性能目标或产品默认值；明显改变实现复杂度或扩展方向的取舍。

```md
# DEC-NNN：决策标题

> 状态：Proposed | Accepted | Superseded | Rejected
> 日期：YYYY-MM-DD
> 负责人：姓名或团队
> 冻结里程碑：P<N>
> 替代/被替代：无

## 背景与问题

## 决策

## 备选方案

## 影响与风险

## 验证方式

## 关联文档和工作项
```

暂定默认值必须标明负责人与最迟冻结里程碑；推翻既有决策时新记录链接旧记录，旧记录改为
`Superseded` 并反向链接。禁止删除已被引用的历史决策。

### 6.3 最小变更记录模板

```md
## YYYY-MM-DD：P<N>-03 简短结论

- 范围：本次实际改变的行为。
- 依据：关联设计和 `DEC-NNN`。
- 验证：设备、配置、准确命令及结果。
- 限制：未执行项、原因、负责人和补跑条件。
- 同步：已更新的计划、设计、测试及兼容性文档。
```

记录粒度应足以支持里程碑验收、问题定位和结论复现，不为每个小提交创建孤立报告。

### 6.4 上游反馈台账条目模板

见第 9.4 节。

## 7. 验证证据

每条重要验收记录应包含：日期与 commit/工作树状态；设备型号与固件（如 OnePlus Ace 3、
ColorOS 版本、API 级别）、构建类型与 feature 开关；可复现的准确命令或 CI job 链接；结果
与关键指标；已知限制、归属、负责人和补跑条件。

证据等级区分：

| 声明 | 最低证据 |
| --- | --- |
| 源码或配置支持 | 对应代码、配置和静态检查 |
| 可以编译 | 指定工具链（NDK 版本、AGP/Kotlin 版本）的实际构建结果 |
| 可以运行 | 目标设备或模拟器上的运行/集成测试 |
| 与 mira 互操作 | 钉死 mira commit 的端到端验证（安装包消费路径） |
| 满足性能目标 | 固定方法、样本、设备和统计结果 |
| 满足体验目标 | 真机上的任务成功率/时延分布实测 |

测试 skip 是诊断结果，不是成功证据；模拟器结果不得表述为真机能力。

## 8. 文档同步矩阵

| 变更类型 | 必须检查或更新 |
| --- | --- |
| 公开 API、门面接口或错误语义 | 设计、KDoc/头文件注释、示例、测试、兼容性说明 |
| 状态投影或状态转换 | 设计、事件 schema、恢复与竞态测试 |
| 并发、取消、定时或 shutdown | 设计、并发边界（EXEC-NN）、生命周期测试；能力不足时更新台账 |
| ABI 实现行为或宿主能力集 | 设计、契约测试、mira 兼容性证据、（必要时）上游反馈台账 |
| 同意/确认边界或风险默认值 | 威胁模型、决策、负向测试和脱敏规则 |
| 产品或技术默认值 | 决策记录、总计划、配置、测试和迁移说明 |
| mira 版本或构建组合 | `tools/mira.lock`、兼容性矩阵、回归记录 |
| 性能或体验声明 | benchmark 方法、结果、设备限制和计划门禁 |
| 里程碑范围或状态 | 总计划索引、里程碑文档、依赖方和验证记录 |

同步更新应与实现处于同一变更中；确需分阶段时，相关工作项保持未完成并记录后续编号。

## 9. 上游依赖使用规范（mira 与 Executor）

### 9.1 定位与锁定

mira 以**安装包**形式消费（不进 `third_party/` 源码）：`tools/mira.lock` 记录源仓库、精确
commit、mira 版本号与安装预设；`tools/install-mira.sh` 按 lock 构建并安装到
`third_party/mira-install/`（gitignored，可由脚本 + lock 完全复现）。Executor 随 mira 的
vendored 依赖进入 native 闭包，本仓库不单独引入或修改。升级 mira 必须记录旧新 commit、
公共 API 变化、受影响范围和回归结果，走独立变更。

### 9.2 强制规则（native bridge 层）

1. mira Executor 由 JNI bridge 唯一持有：初始化、接纳、取消、排空、关闭对 bridge 层
   可见；关闭顺序遵循 mira DEC-001（§17.2：停止生产者 → 取消 → 回收 → 排空 → 非 worker
   线程 `shutdown(true)`）。
2. bridge 自研代码不得使用 `std::thread`/`std::jthread`/`std::async`/私有线程池或脱离
   Executor 的 fire-and-forget。平台线程亲和（JNI 回调、Android 线程）封装在 bridge 的
   投递边界内。
3. bridge 内跨线程通信优先复用 mira/Executor 公开通信原语；确需自建时必须有界并测试。
4. Kotlin 层并发使用结构化协程；与 native 侧交互只经 bridge 公开的入口，不共享原始句柄。
5. 第三方/平台回调（`ImageReader`、无障碍、JNI）只做有界校验和投递，业务 handler 不得
   直接在回调线程执行。

### 9.3 能力路由

实现 native 并发行为前，参考 pinned mira 闭包内的 Executor 文档
（`third_party/executor`（mira 仓库内）的集成 SKILL 与 API 文档）；以本地安装闭包一致
的版本为准，不读取无关实现源码。计划与设计必须写明每类 native 工作由 Executor 哪种能力
承载、句柄由谁持有、如何取消以及关闭顺序（总计划 `EXEC-NN`）。

### 9.4 能力缺口与反馈台账

不得为绕过上游能力边界而静默引入替代设施。确认缺口时：

1. 先核对当前 pinned 版本的公开头文件、API 文档与测试，排除选型/配置错误、平台限制和
   应用层职责。
2. 在 `docs/upstream_feedback/ledger.md` 新增唯一编号条目——Executor 相关用
   `EXE-YYYYMMDD-NNN`，mira 公共 API 相关用 `MIR-YYYYMMDD-NNN`——附可复现证据、影响
   范围、期望语义与可验收结果。只写"上游不支持"不构成有效记录。
3. 在相关代码、测试或设计文档中引用该编号。
4. 确需临时方案时，限制在单一 Adapter/compatibility boundary 内，说明行为差异、风险、
   移除条件与测试覆盖。
5. 未经明确授权不修改 mira 或 Executor 源码来掩盖集成问题；先报告并等待指示。

台账分级参考：`P1` 系统性将就、`P2` 结构性将就、`P3` 有而未用（应用侧待办，非缺口）、
`违规`。每条维护状态（`Open`/`Proposed`/`Accepted`/`Resolved`/`Rejected`）与跟进记录，
上游收敛后回写迁移结论与证据。

以下情况不是上游缺口：模型供应商协议适配、Android 权限映射、手势映射、业务状态策略、
错误使用已有 API、平台本身不提供的能力。它们在本仓库对应层解决。

## 10. Git 仓库管理

### 10.1 分支模型与命名

- 主干开发：`master` 是唯一长期分支，始终保持可构建、可测试。
- 特性分支短生命周期：`feat/<scope>-<description>`、`fix/<scope>-<description>`、
  `docs/...`、`refactor/...`、`test/...`、`ci/...`；Agent 协作时可加 `codex/<type>/...`
  前缀。
- `master` 为保护分支：只能经 MR 合入，合入前 CI 通过、评审完成；不得 force-push 或改写
  已发布历史。
- `hotfix/<issue>` 从 master 切出，修复后合回 master。

### 10.2 Commit Message 规范

格式 `<type>(<scope>): <subject>`；type 限于
`feat`/`fix`/`refactor`/`perf`/`docs`/`test`/`build`/`ci`/`chore`/`revert`；scope 取自词表：
`ui`（主 GUI）、`overlay`（悬浮球）、`host`（Kotlin 宿主服务）、`bridge`（JNI/native 桥）、
`consent`（同意/披露）、`build`、`ci`、`docs`、`tools`。一个 Commit 对应一个独立逻辑修改。
示例：`feat(host): add media projection capture pipeline`。

提交前检查 `git status` / `git diff` / `git diff --cached`：不含无关格式化、临时调试代码、
运行日志、构建产物（`third_party/mira-install/`、`app/build/`、`*.apk`）、IDE 文件、大文件
与敏感信息（keystore、API key）。`user.name`/`user.email` 必须是提交者本人。

### 10.3 Merge Request 规范

标题同 Commit 格式；一个 MR 只解决一个问题或实现一个完整功能。描述至少包含：修改内容、
修改原因、实际执行的测试（未执行不得写"测试通过"，真机项注明设备与固件）、影响范围
（ABI 行为、门面 API、权限、数据格式、mira 版本等逐项说明）。大型需求按
`基础接口 → 核心实现 → 功能接入 → 测试` 拆分。

### 10.4 Code Review 与合并

至少一名相关开发人员评审，重点：分层与边界（同意门面、ABI 语义）、异常与取消、权限与
隐私、测试覆盖、文档同步。意见区分`必须修改`/`建议修改`/`讨论项`并逐一回复。合并条件：
开发完成 + 自测通过 + 目标分支同步 + 无冲突 + CI 通过 + 评审通过。合并策略统一
（推荐 Squash and Merge）。

### 10.5 版本与发布

- 版本号 `vMAJOR.MINOR.PATCH`（0.x 阶段 MINOR 递增即可），tag 打在发布点 commit 并附
  说明；里程碑"建议发布点"与 tag 一一对应。
- 维护 `CHANGELOG.md`：功能、修复、兼容性影响（含 mira commit 变化）、已知限制。
- APK 发布前核对：签名密钥可用且未入库、`apksigner verify` 通过、兼容性矩阵声明的设备
  有真机证据、危险权限与数据披露写明在发布说明。

### 10.6 仓库卫生

`.gitignore` 覆盖 `app/build/`、`.gradle/`、`local.properties`、`*.keystore`、
`keystore.properties`、`third_party/mira-install/`、`*.apk`、截图与日志产物。大文件走
Git LFS 或对象存储；验证截图含敏感内容时不入库，记录受控存储位置与摘要。凭据经系统
keyring/环境变量注入，不写入远程 URL、命令参数或日志。

### 10.7 依赖锁定与升级

- mira 锁定在 `tools/mira.lock`（源、commit、版本、许可证字段齐全）；安装脚本与 CI 校验
  commit，不匹配即失败。
- Android 依赖统一走 Gradle version catalog（`gradle/libs.versions.toml`），升级走独立 MR
  并记录差异与回归结果。
- 生成物约束在构建树内，不进入源码树。

## 11. Android + NDK 混合工程基线

- Kotlin + Jetpack Compose（UI/悬浮面板）、C++20（JNI bridge）、Gradle（Kotlin DSL）+
  version catalog、NDK 钉版本（与 mira CI 一致：26.3.11579264）、CMake 经
  `externalNativeBuild` 集成，`find_package(Mira)` 消费安装前缀。
- 构建类型至少 `debug`/`release`；v1 release 不开 R8 压缩（便于诊断），开启时必须验证
  JNI 符号保留规则。
- 单一 native 库（`libmiracle_host.so`），arm64-v8a；minSdk/targetSdk/compileSdk 与设备
  基线见[构建打包设计](../design/build_packaging_design.md)。
- 静态检查：Android Lint 必跑；Kotlin 格式化（ktlint 或官方格式化）统一；native 侧沿用
  mira 的警告基线（关键警告为 error）。
- 测试分层：Kotlin 单元测试（JUnit/Robolectric）、instrumented 测试（真机优先，
  OnePlus Ace 3 为门禁设备）、native 契约测试（lease 释放、epoch、exactly-once 回调）经
  JNI 驱动。CI 至少执行 assembleDebug + lint + unit test；真机项记录补跑条件。
- 依赖方向锁住：UI 不 import JNI/ABI 类型（可用 ARCHITECTURE 测试或 lint 规则检查）；
  bridge 不 import Android 框架类以外的 Kotlin UI 类型。
- 版本钉死信息集中在 version catalog 与 `tools/mira.lock`，不散落在模块 build 脚本。

## 12. Markdown 与链接风格

文件名小写英文与连字符，稳定编号保留大写（`DEC-001-frontend.md`）。每文件一个一级标题；
相对链接连接仓库内文档，链接文字描述内容；表格用于稳定映射，论述用段落；命令、路径、
类型、状态和 ID 使用反引号；日期统一 `YYYY-MM-DD`；缩写首次出现时解释；修改文档后检查
相对链接与被引用文件存在性。

## 13. 归档与替代

已完成的里程碑和历史决策保留原路径。文档不再适用时标记 `Superseded` 或 `Cancelled`，
开头写明替代文档、日期与原因；仅当文件从未被引用且内容完全错误时才考虑删除。

## 14. 评审与变更控制

评审顺序：产品目标与范围 → 分层、所有权、同意边界与生命周期 → 失败/取消/超时/shutdown
闭合 → 安全、隐私、兼容性与迁移风险 → 测试与证据 → 文档、计划与 Git 规范同步。

发现实现与文档不一致时，先判断是实现缺陷还是已批准的设计变化：前者修复实现，后者按
决策流程同步所有受影响文档。
