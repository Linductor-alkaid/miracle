# DEC-003：构建打包与设备基线（单 APK、arm64、mira 安装前缀消费、OnePlus Ace 3 门禁）

> 状态：Accepted
> 日期：2026-09-05
> 负责人：Miracle Maintainers
> 冻结里程碑：P0
> 替代/被替代：无

## 背景与问题

需要把 Kotlin 应用、JNI bridge 与 mira C++ 闭包打包为统一 APK 分发安装；真机资源为一台
OnePlus Ace 3。mira 只有 android-arm64 预设且以安装包形式消费（DEC-011）。

## 决策

1. 单 Gradle 工程、单 APK：`libmiracle_host.so`（bridge 自研 + mira 静态闭包：core/
   android_adapter/net+mbedtls transport/state_store）+ Kotlin/Compose；`abiFilters`
   限 arm64-v8a；STL 用 `c++_static`。
2. mira 消费走安装前缀：`tools/mira.lock`（精确 commit）+ `tools/install-mira.sh` 安装到
   gitignored 的 `third_party/mira-install/`；CMake `find_package(Mira)`，Gradle 注入
   `CMAKE_PREFIX_PATH`；NDK 钉 26.3.11579264 与 mira CI 一致。
3. SDK 基线：minSdk 26、targetSdk 35、compileSdk 36；具体 AGP/Kotlin/Compose 版本由
   P0-01 在 version catalog 钉死。
4. 分发：侧载（GitHub Release 附件/直传 APK），release 用自有 keystore 签名（不入库），
   v1 不开 R8 压缩；OnePlus Ace 3 为唯一真机门禁设备，证据等级区分真机/仅构建。
5. 模拟器因 mira 无 android-x86_64 预设不作为能力证据，登记台账并列为 POST 项。

## 备选方案

- mira 以源码子模块进 `third_party/` 直接构建：违反"只经安装包公共 API 消费"边界
  （mira DEC-011），且升级治理更弱。不采用。
- AAR+prefab 封装 mira：对单 app 消费无收益，增加封装维护。不采用（未来多模块/对外分发
  时重评）。
- minSdk 24（贴 mira 下限）：无真实低版本用户，白增测试矩阵。不采用。
- Play 渠道分发：政策申报与无障碍用途审核周期长，demo 阶段侧载足够。不采用（POST）。

## 影响与风险

- 单 ABI 限制潜在设备范围；v1 无分发面诉求，风险接受。
- keystore 丢失导致无法覆盖安装；发布清单含备份提醒（构建打包设计 §5/§8）。
- ColorOS 后台管理回收无障碍服务；列入 P0/P2 真机验证项与引导文案。

## 验证方式

P0 验收即包含：脚本消费链路可复现（lock 校验失败即构建失败）、真机安装启动加载
`libmiracle_host.so` 完成 mira baseline 初始化/关闭；P1/P2 真机证据回填兼容性文档。

## 关联文档和工作项

[构建打包设计](../design/build_packaging_design.md)；[架构设计](../design/system_architecture_design.md)；
总计划 `P0-01`；台账 `MIR-20260905-003`（x86_64 预设）、`POST-02`。
