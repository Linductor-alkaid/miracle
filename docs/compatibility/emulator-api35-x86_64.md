# 模拟器运行证据：API 35 google_apis x86_64

> 状态：Active
> 更新日期：2026-09-05
> 适用范围：P0 自检冒烟的模拟器环境记录（非真机、非产品分发目标）

## 环境

| 项 | 值 |
| --- | --- |
| AVD | `miracle_p0`（pixel_6 profile） |
| 系统镜像 | `system-images;android-35;google_apis;x86_64` |
| ABI 支持 | `ro.product.cpu.abilist = x86_64,arm64-v8a`（镜像含 ARM 翻译层） |
| 启动参数 | `-no-window -no-audio -gpu swiftshader_indirect -no-boot-anim -no-snapshot` |
| 宿主 | Ubuntu 24.04 x86_64，KVM |

## 证据

- 2026-09-05（P0）：`adb install` 成功（arm64-v8a APK 经翻译层安装）；应用启动后
  `miracle/bridge` logcat 输出 `"ok":true ... "final_state":"Stopped"
  "mira_version":"0.1.0"`；UI 文本渲染确认；force-stop 无 ANR/FATAL。
  详见 [P0 验证记录](../plans/p0-skeleton-consumption.md)。

## 声明边界

- 模拟器 + ARM 翻译执行 arm64 库，**性能不具代表性**，不得用于任何时延/性能声明。
- 能力声明等级：Runtime verified(emulator)；真机 OnePlus Ace 3 证据独立登记
  （`oneplus-ace3.md`，待设备执行）。

## P1 记录（2026-09-05）

- 授权路径：通知权限 → 投影确认（Start）→ 应用选择器（滚动选 Miracle）。
- 环境自检：两帧 636×1414 RGBA8888（182.2ms/66.6ms），`"ok":true`，干净关闭。
- 性能（ARM 翻译）不具代表性，仅作回归证据。
