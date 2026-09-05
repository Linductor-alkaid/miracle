# P0：骨架与消费验证

> 状态：Completed
> 负责人：Miracle Maintainers
> 所属计划：[Miracle 实施总计划](miracle-implementation-plan.md)
> 前置：文档基线（DEC-001~003 已 Accepted）
> 建议发布点：无（内部基线，不打 tag；tag 自 v0.1.0-alpha 起）
> 更新日期：2026-09-05

## 目标

建立可构建、可安装、可自检的最小工程：Gradle + NDK 单 APK 骨架，mira 安装包消费链路
（lock + 脚本 + CMake `find_package`），JNI bridge 完成 mira `RuntimeBaseline` 的初始化与
干净关闭，空壳 Compose GUI 呈现自检结果，CI 在 GitHub Actions 上全绿。

## 范围与非目标

范围：工程脚手架、版本基线钉死、mira arm64 安装与消费、native 自检桥、空壳 GUI、CI、
模拟器安装启动证据。

非目标：截屏/输入能力（P1/P2）、悬浮球交互（P3，仅留 UI 占位文案）、模型调用（P3）、
Host ABI 实现（P1 起）、持久状态（P4）。

说明：`libmiracle_host.so` 的 mira 闭包按里程碑递增链接——P0 仅 `Mira::core`
（`RuntimeBaseline` 所在）；P1/P2 增 `Mira::android_adapter`（届时必须提供 host ABI 符号实
现）；P3 增 transport/state_store。DEC-003 描述的是 v1 终态构成，与本递增不冲突。

## 设计与决策依据

- [总体架构设计](../design/system_architecture_design.md) §2/§4（模块与生命周期）。
- [构建打包设计](../design/build_packaging_design.md) §2/§3（版本基线与消费链路）。
- [DEC-003](../decisions/DEC-003-build-device-baseline.md)（单 APK、arm64、安装前缀消费、
  OnePlus Ace 3 门禁）。
- mira 消费示例：`examples/minimal_consumer.cpp`（RuntimeBaseline 语义）。

## 工作项

- [x] `P0-01` 工程与版本基线钉死：Gradle wrapper、version catalog（AGP/Kotlin/Compose/
  AndroidX）、applicationId `dev.linductor.miracle`、SDK 35/NDK 26.3.11579264/cmake 3.31.6，
  版本只存在于 `gradle/libs.versions.toml` 与构建配置。（注：SDK 仓库无 `cmake;3.22.8`，
  实钉 `cmake;3.31.6`）
- [x] `P0-02` 空壳 GUI：单 Activity Compose 应用，自检页展示 runtime 自检结果与版本信息；
  悬浮球以占位说明呈现（交互 P3 交付）。
- [x] `P0-03` mira 安装链路：`tools/mira.lock` 与 `tools/install-mira.sh`（本地优先克隆、
  commit 校验失败即失败、PIC 全局开启、安装完整性校验）。
- [x] `P0-04` native 自检桥：`runtime_glue.cpp` 经 `RegisterNatives` 暴露
  `runtimeSelfTest()`；返回结构化 JSON；零线程创建（grep 门禁通过）；异常不穿越 JNI。
- [x] `P0-05` CI：GitHub Actions `ci.yml`（SDK 组件与 NDK 钉版本、mira lock 校验、
  assembleDebug + lintDebug + testDebugUnitTest、APK 产物上传）。
- [x] `P0-06` 运行验证：模拟器（API 35 google_apis x86_64，abilist 含 arm64-v8a 翻译）安装
  启动自检通过；真机 OnePlus Ace 3 补跑条件已登记（见验证记录）。
- [x] `P0-07` 文档与发布同步：验证记录、兼容性证据（mira 消费 + 模拟器运行）、CHANGELOG、
  README 快速开始。

## 风险与阻塞

| 风险 | 对策 |
| --- | --- |
| mira 安装包在 Android 消费端从未被验证（RISK-2026-01） | P0 即以最小闭包（仅 core）验证；问题登记 `MIR-` 台账 |
| 本机无 Android SDK/Gradle | 从官方源安装 commandline-tools + 组件；版本记入兼容性文档 |
| x86_64 模拟器无法运行为 arm64 构建的 .so | 辅助 x86_64 ABI 仅用于冒烟（手动命令，不入产品 abiFilters）；真机证据补跑 |
| 首次 CI 下载量（NDK/依赖）导致时长较长 | 可接受；后续按需缓存 |

## 测试与退出条件

- [x] `./gradlew assembleDebug` 成功，APK 含 `libmiracle_host.so`（arm64-v8a，1.76MB）。
- [x] `./gradlew lintDebug testDebugUnitTest` 通过（3/3 单测通过，0 失败）。
- [x] CI（GitHub Actions）在 master push 上全绿，含 lock 校验。（run `33964086721`
  success，6m18s，commit `27653c4`；首跑 `33964056639` 因 runner 缺 cmdline-tools 失败，
  修复后绿）
- [x] 模拟器安装启动：自检页显示 baseline 各步骤成功与耗时（init 4ms / wait 6ms /
  Applied / Stopped），UI 文本渲染确认；force-stop 无 ANR/native 崩溃日志。
- [x] bridge 源码无 `std::thread`/`std::async`/`pthread_create`（grep 门禁通过）。
- [x] OnePlus Ace 3 真机启动证据补跑条件已登记（见验证记录 2026-09-05）。
- [x] 文档同步完成（验证记录 + 兼容性证据 + CHANGELOG + README）。

## 验证记录

（按日期追加）

2026-09-05：计划建立，状态 `In Progress`。环境探测：本机 Java 21、`/dev/kvm` 可用、
无 Android SDK/Gradle（需安装）；mira 子模块已初始化；gh 已认证（Linductor-alkaid）。

2026-09-05（P0-01~P0-04 本地验证，commit 见 git 历史）：

- 环境：Ubuntu 24.04 x86_64；Oracle JDK 21.0.12（`~/jdk`，系统 Java 为 JRE 不含 javac）；
  Gradle 8.10.2 wrapper；AGP 8.7.3/Kotlin 2.1.0/Compose BOM 2024.12.01；
  SDK platform 35 + build-tools 35.0.0 + NDK 26.3.11579264 + cmake 3.31.6。
- mira 安装：`tools/install-mira.sh` 成功（mira 0.1.0 @ `16e419e0c5b3`，本地克隆 → 精确
  checkout → submodule update → preset `android-arm64-release` +
  `CMAKE_POSITION_INDEPENDENT_CODE=ON` → install 前缀校验通过）。
  集成中发现并处理两项（均为配置层，未改 mira 源码）：
  1. NDK 工具链 `CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY` 导致 `find_package(Mira)` 失败
     → app CMakeLists 将安装前缀并入 `CMAKE_FIND_ROOT_PATH`；
  2. mira 静态库默认非 PIC，链接 `.so` 失败 → 安装脚本全局开启 PIC
     （mira 未显式设置 PIC 的目标遵循该变量；mbedtls 目标上游已自设 PIC）。
- 构建：`./gradlew assembleDebug lintDebug testDebugUnitTest` BUILD SUCCESSFUL；
  APK `app-debug.apk` 11.2MB，含 `lib/arm64-v8a/libmiracle_host.so`；
  单测 `SmokeResultTest` 3/3 通过。
- 运行（模拟器）：AVD `miracle_p0`（system-images;android-35;google_apis;x86_64，
  abilist=`x86_64,arm64-v8a`，即镜像含 arm64 翻译）；`adb install` Success；
  `am start` 后 logcat：
  `miracle/bridge: self test: {"ok":true,"stage":"complete","detail":"baseline completed",
  "init_ms":4,"wait_ms":6,"result_code":"Applied","task_terminal":false,
  "final_state":"Stopped","mira_version":"0.1.0"}`；
  UI dump 确认"自检通过 / mira 版本 0.1.0 / Stopped / 悬浮球占位"渲染；
  `am force-stop` 后无 ANR/FATAL。证据等级：**Runtime verified（模拟器 + ARM 翻译执行
  arm64 库）**；截图存 `/tmp/android-setup/miracle-p0-ui.png`（不入库）。
- 门禁：`grep -E "std::thread|std::jthread|std::async|pthread_create" app/src/main/cpp/*`
  无命中。
- 限制与补跑条件（工程规范 §4）：
  - **OnePlus Ace 3 真机启动证据未执行**（设备在用户处）。补跑条件：手机开启开发者选项
    与 USB 调试后，`adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell
    am start -n dev.linductor.miracle/.MainActivity`，确认自检页"自检通过"且 logcat 出现
    `"ok":true`。负责人：Miracle Maintainers / 用户配合。真机通过后本条升级为 Runtime
    verified(device)，并回填 `docs/compatibility/oneplus-ace3.md`。
  - 模拟器证据基于 ARM 翻译执行 arm64 库（性能不代表性），不表述为真机性能。

2026-09-05（CI 验证与里程碑收尾，commit `27653c4`+）：

- CI：首跑 run `33964056639` 失败（ubuntu-latest 新镜像无 PATH 上的 `sdkmanager`，
  `command not found`）；修复为绝对路径调用 + 缺失时从官方源安装 cmdline-tools 后，
  run `33964086721` **success**（6m18s）：SDK 组件安装 → `install-mira.sh`（远程克隆
  `Linductor-alkaid/mira` @ 钉死 commit，含 submodule 拉取）→ assembleDebug +
  lintDebug + testDebugUnitTest 全绿 → APK 产物上传。
- 由此，mira 安装链路在"本地克隆"与"CI 远程克隆"两条路径均有 Build verified 证据。
- 里程碑退出条件全部满足（真机项按工程规范 §4 以补跑条件形式闭合）；状态置
  `Completed`。真机 OnePlus Ace 3 冒烟仍待设备执行，属登记的补跑项，不阻塞 P0。
