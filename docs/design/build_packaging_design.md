# Miracle 构建打包与分发设计

> 状态：Proposed
> 版本：1.0
> 更新日期：2026-09-05
> 上位文档：[总体架构设计](system_architecture_design.md)
> 关联决策：[DEC-003 构建与设备基线](../decisions/DEC-003-build-device-baseline.md)

## 1. 结论摘要

单一 Gradle 工程，产出**一个 APK**：`libmiracle_host.so`（JNI bridge 自研代码 + mira 全部
静态库闭包：core/android_adapter/net+mbedtls transport/state_store，含 vendored executor、
mbed TLS、SQLite）与 Kotlin/Compose 代码共同打包；arm64-v8a 单 ABI；debug 自签调试、
release 自有 keystore 签名；以侧载（GitHub Release 附件 / 直接传输 apk）分发，OnePlus
Ace 3 为唯一真机门禁设备。

## 2. 工程结构与版本基线

```
miracle/
├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml   # 版本唯一来源
├── app/
│   ├── build.gradle.kts          # externalNativeBuild 指向 src/main/cpp
│   └── src/main/
│       ├── kotlin/…              # ui / overlay / runtime / consent / host
│       ├── cpp/
│       │   ├── CMakeLists.txt    # find_package(Mira)；产出 libmiracle_host.so
│       │   ├── host_abi_impl.cpp # mira Host ABI v1 导出符号实现
│       │   └── runtime_glue.cpp  # Executor owner + mira 组装 + JNI 入口
│       └── AndroidManifest.xml
├── tools/
│   ├── mira.lock                 # 源仓库 / 精确 commit / mira 版本 / 安装预设
│   └── install-mira.sh           # 按 lock 构建+安装到 third_party/mira-install/
├── third_party/mira-install/     # gitignored；脚本+lock 可完全复现
└── docs/…
```

版本基线（P0-01 钉死进 version catalog，此处为约束值）：

| 项 | 值 | 依据 |
| --- | --- | --- |
| NDK | 26.3.11579264 | 与 mira CI/平台矩阵一致，不得漂移 |
| CMake（NDK 内置版本之外若需独立安装） | ≥ 3.25 | mira 消费与 toolchain 要求 |
| AGP / Kotlin / Compose BOM | 以 `libs.versions.toml` 钉死（P0-01 确认具体版本） | 唯一来源，不散落模块 |
| compileSdk / targetSdk | 36 / 35 | 当前平台基线；AGP 能力匹配为准 |
| minSdk | 26 | mira NDK 下限 24 之下界之上的实用值：Compose/FGS API 舒适域；无真实 <26 用户需求，降低测试矩阵。dispatchGesture 需 24 已满足 |
| ABI | arm64-v8a（`abiFilters`） | mira 仅有 arm64 Android 预设；OnePlus Ace 3 为 arm64 |
| STL | `c++_static` | 单一 native 库，无共享需求 |

## 3. mira 消费链路

```bash
# 一次性/升级时（由 CI 与开发者执行，脚本校验 lock 中 commit）
tools/install-mira.sh            # = git checkout <commit> && cmake --preset android-arm64-release
                                 #   && cmake --install build/… --prefix third_party/mira-install
```

`app/src/main/cpp/CMakeLists.txt`（骨架）：

```cmake
cmake_minimum_required(VERSION 3.25)
project(miracle_host CXX)
set(CMAKE_CXX_STANDARD 20)
find_package(Mira 0.1 CONFIG REQUIRED)   # CMAKE_PREFIX_PATH 由 Gradle 注入

add_library(miracle_host SHARED host_abi_impl.cpp runtime_glue.cpp)
target_link_libraries(miracle_host PRIVATE
    Mira::core Mira::android_adapter Mira::net_transport
    Mira::mbedtls_transport Mira::state_store)
```

Gradle 侧（`app/build.gradle.kts`）：

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            arguments += "-DCMAKE_PREFIX_PATH=${rootDir}/third_party/mira-install"
        } }
    }
    externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt" } }
}
```

要点：

1. `install-mira.sh` 在 lock commit 不匹配时立即失败（对齐工程规范 §10.7）。
2. 构建依赖检查：configure 前校验 `third_party/mira-install/lib/cmake/Mira` 存在，缺失时
   给出一句话修复指引（运行安装脚本）。
3. 符号可见性：`host_abi_impl` 的导出符号与 mira adapter 在同一 .so 内链接期解析，保持
   默认可见性；release 开 R8 时仅需 Java/Kotlin 侧 keep 规则（v1 不开压缩，见 §5）。
4. JNI 入口集中在 `runtime_glue.cpp` 的 `RegisterNatives`（显式注册，避免符号剥离歧义）；
   异常不穿越 JNI，全部转结果码。

## 4. 应用标识与清单要点

| 项 | 值/约定 |
| --- | --- |
| applicationId | `dev.linductor.miracle`（P0 定稿，此后不变） |
| 前台服务 | `AgentForegroundService`；`foregroundServiceType="specialUse|mediaProjection"`（API 34+ 声明，`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明 agent 用途） |
| 无障碍声明 | `AccessibilityServiceInfo`：`canPerformGestures`、`canRetrieveWindowContent`（UiTreeProvider 验证用）、`isAccessibilityTool` 如实声明用途 |
| 权限 | `INTERNET`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW` |
| 组件导出 | 仅 launcher Activity 导出；服务与无障碍服务 `exported=false` + 权限保护 |

## 5. 构建类型与签名

| 构建类型 | 用途 | 签名 | 优化 |
| --- | --- | --- | --- |
| debug | 日常开发/真机联调 | debug keystore 自动 | 不压缩、可调试（JNI 也需 `debugSymbolLevel`） |
| release | 分发 | 自有 keystore（`keystore.properties` + `*.jks` 均 gitignored，经环境注入口令） | v1 不开 R8 压缩（诊断优先）；`nativeSymbolUploadEnabled` 或保留 symbols 目录供 native 崩溃解析 |

keystore 生成（一次性，妥善备份，丢失即无法覆盖安装同签名应用）：

```bash
keytool -genkeypair -v -keystore miracle-release.jks -alias miracle \
  -keyalg RSA -keysize 4096 -validity 10000
```

## 6. 版本、产物与分发流程

- 版本：`versionName` = `0.MINOR.PATCH`（与 Git tag 对应）；`versionCode` 单调递增整数
  （每次发布 +1），保证升级路径。
- 产物命名：`miracle-<versionName>-arm64-<git-short-commit>.apk`，由发布脚本复制自
  `assembleRelease` 输出并附 SHA-256。
- 发布流程（对齐工程规范 §10.5）：tag → CHANGELOG（含 mira commit 变化与已知限制）→
  构建 Release APK → `apksigner verify` → 上传 GitHub Release 附件（或点对点传输）→
  兼容性文档登记本次验证设备与固件。
- 安装升级：`adb install -r` 或系统包安装器（同签名覆盖安装，数据保留；mira 持久状态在
  `filesDir` 随数据保留）。
- CI（GitHub Actions）：host 构建（assembleDebug + lint + unit test + native configure 校
  锁）每 PR 必跑；真机 instrumented 测试为手动门禁，记录补跑条件。

## 7. 真机基线：OnePlus Ace 3

| 项 | 值 |
| --- | --- |
| SoC / 内存 | Snapdragon 8 Gen 2；12/16GB RAM |
| 屏幕 | 6.78" 2780×1264 LTPO AMOLED，120Hz |
| 系统 | 出厂 Android 14（ColorOS 14，API 34），可升级 ColorOS 15+（API 35+）；以实测 `Build.VERSION.SDK_INT` 登记为准 |
| ABI | arm64-v8a |

**ColorOS/OPlus 侧已知课题与对策（P0/P2 验证项）**：

1. 无障碍服务易被后台管理回收 → 引导页明确指引：电池"不受限制"+ 允许自启动；运行期检测
   服务断连并提示（epoch 递增→任务转 Failed 的路径已有）。
2. 悬浮窗权限位于"特殊应用权限"；USB/侧载安装需开启"安装未知应用"（按应用授权）。
3. 媒体投影每次会话弹系统授权（ColorOS 样式可能差异）→ 引导文案不假设系统对话框文案。
4. 120Hz 高刷下手势时长参数以真机实测校准（P2 输入矩阵项）。

**设备策略**：v1 只声明 OnePlus Ace 3（API 34+，实际以登记为准）为受支持真机；模拟器
路径已随 mira android-x86_64 预设解锁（`MIR-20260905-003` 关闭），消费侧双 ABI 构建
与 instrumented 冒烟为 POST 项，解锁前不作为能力证据。

## 8. 发布物清单（每次发布核对）

- [ ] APK 签名验证通过（`apksigner verify --print-certs`）
- [ ] `tools/mira.lock` commit 与 CHANGELOG 一致
- [ ] 权限与数据披露（截图出设备、触控模拟）在 Release 说明中重申
- [ ] OnePlus Ace 3 冒烟：安装→引导→授权→一次截屏→一次 tap→takeover→干净退出
- [ ] 已知限制列表（FLAG_SECURE、无 UI 树、单 ABI 等）无过时表述
