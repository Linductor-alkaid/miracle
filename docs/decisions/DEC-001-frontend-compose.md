# DEC-001：前端形态采用 Jetpack Compose（类 React 声明式，原生实现）

> 状态：Accepted
> 日期：2026-09-05
> 负责人：Miracle Maintainers
> 冻结里程碑：P0
> 替代/被替代：无

## 背景与问题

产品需要主 GUI 与悬浮球两种前端，用户明确期望"类似 React 的搭建形式"。候选：Jetpack
Compose、React Native、Flutter、WebView+React。本产品 UI 面小但与系统层（前台服务、无障
碍、媒体投影、悬浮窗、JNI 门面）耦合极深。

## 决策

主 GUI 与悬浮面板统一采用 Kotlin + Jetpack Compose（Material 3，单 Activity，UDF，
ViewModel+StateFlow 作为 store）；悬浮球常驻态采用轻量原生自绘 View + 原生触摸处理，
点击展开的悬浮面板用 ComposeView overlay（装配 ViewTreeLifecycleOwner）。React 心智模型
经概念映射表（见[前端设计 §2](../design/frontend_design.md)）迁移。

## 备选方案

- React Native：范式完全一致，但所有系统能力仍需 Native Module 双份实现，JS↔native 桥
  叠加在 JNI 之上，链路成本翻倍；overlay 支持弱。不采用。
- Flutter：声明式但非 React 生态；Platform Channel 同样全覆盖；Dart FFI 到 mira 可行但
  服务层仍原生。不采用。
- WebView + React：悬浮窗/权限流/服务绑定基本不可用。不采用。

## 影响与风险

- Compose 即官方长期方向，工具链与 AGP 原生集成，无跨运行时桥。
- 悬浮球采用原生 View 与"统一 Compose"存在一处例外：为常驻开销与手势稳定性，已在
  [前端设计 §5](../design/frontend_design.md) 说明理由并限定范围。
- 团队 React 经验可迁移，但需接受 Compose 重组/跳过机制的学习曲线。

## 验证方式

P0 交付可导航的空壳 GUI 与悬浮球占位；P3 以真实页面与面板交互通过 instrumented 测试。

## 关联文档和工作项

[前端设计](../design/frontend_design.md)；总计划 `P0-02`、`P3-02`。
