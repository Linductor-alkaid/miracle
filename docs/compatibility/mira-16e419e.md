# mira 消费兼容性证据：0.1.0 @ 16e419e

> 状态：Active
> 更新日期：2026-09-05
> 适用范围：`tools/mira.lock` 钉死版本在本仓库的构建与运行消费证据

## 消费方式

- 安装链路：`tools/install-mira.sh`（本地克隆优先，精确 checkout `16e419e0c5b3c634885d97aebe54bc0497b609c1`，preset `android-arm64-release`，安装前缀 `third_party/mira-install/`）。
- Gradle/CMake：`app/build.gradle.kts` 注入 `CMAKE_PREFIX_PATH`，`app/src/main/cpp/CMakeLists.txt` `find_package(Mira 0.1 CONFIG REQUIRED)`。
- P0 链接闭包：仅 `Mira::core`（`RuntimeBaseline`）；adapter/transport/state_store 按里程碑递增（P0 计划"范围与非目标"）。

## 证据记录

| 日期 | 环境 | 命令/结果 | 等级 |
| --- | --- | --- | --- |
| 2026-09-05 | Ubuntu 24.04 x86_64；系统 CMake 3.28.3、ninja（~/.local/bin）、NDK 26.3.11579264（`~/Android/Sdk`） | `tools/install-mira.sh` 成功：configure/build/install 全绿（含 `CMAKE_POSITION_INDEPENDENT_CODE=ON`，供 `.so` 链接），`MiraConfig.cmake` 落位 | Build verified（本仓库消费路径） |
| 2026-09-05 | 同上 + Gradle 8.10.2/AGP 8.7.3 | `./gradlew assembleDebug` 成功；`find_package(Mira)` 消费成功（NDK find-root 限制经 app CMakeLists 并入前缀解决） | Build verified（安装包 → APK 链路） |
| 2026-09-05 | 模拟器 API 35 google_apis x86_64（ARM 翻译） | 应用内 RuntimeBaseline 自检 `"ok":true`、终态 `Stopped`（详见 P0 验证记录） | Runtime verified(emulator) |

## 集成备注（配置层处理，未修改 mira 源码）

1. NDK 工具链将包搜索限制在 sysroot（`CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY`）；
   消费方需把 mira 安装前缀并入 `CMAKE_FIND_ROOT_PATH`（见
   `app/src/main/cpp/CMakeLists.txt` 注释）。
2. mira 静态库默认非 PIC；链入共享库需在 configure 时全局开启
   `CMAKE_POSITION_INDEPENDENT_CODE=ON`（见 `tools/install-mira.sh`）。
   该事实可作为 mira 安装包"共享库消费"场景的上游改进参考（暂不构成阻塞，暂不登记
   `MIR-` 台账；若未来上游修改 PIC 默认值则同步此脚本）。

## 已知限制

- mira Android arm64 在本仓库消费前无任何真机运行证据；运行级证据以 P0 计划验证记录为准，不将构建通过表述为运行支持。
- `cmake;3.22.8` 不存在于 SDK 仓库；Android 构建使用 `cmake;3.31.6`（记录于 `gradle/libs.versions.toml` 与 CI）。
