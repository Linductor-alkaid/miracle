# 变更日志

本文件记录版本级变化（工程规范 §10.5）。格式遵循 Keep a Changelog；版本号与 Git tag 对应。

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
