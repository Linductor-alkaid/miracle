# Miracle

Android 端 AI Agent 产品：以 [mira](../mira) 为 LLM Agent 核心（安装包公共 API 消费），
提供主 GUI 与悬浮球两种前端；在用户已被告知并授权的前提下，向 mira 注入屏幕采集与
触控注入能力，由 mira 的 `Observe → Reason → Plan → Act → Verify` 闭环完成用户任务。

本仓库即 mira [DEC-011](../mira/docs/decisions/DEC-011-demo-first-external-validation.md)
定义的独立 demo/验证载体：只经 `find_package(Mira)` 消费 mira，不 fork 源码；能力缺口
回流为 mira issue（见[上游反馈台账](docs/upstream_feedback/ledger.md)）。

## 文档索引

- [可行性与方案分析](docs/feasibility-and-solution.md)
- 设计：[总体架构](docs/design/system_architecture_design.md) ·
  [Agent 工具集](docs/design/agent_tool_set_design.md) ·
  [前端](docs/design/frontend_design.md) ·
  [构建打包与分发](docs/design/build_packaging_design.md)
- 决策：[DEC-001 前端形态](docs/decisions/DEC-001-frontend-compose.md) ·
  [DEC-002 工具集路线](docs/decisions/DEC-002-agent-tool-set-route.md) ·
  [DEC-003 构建与设备基线](docs/decisions/DEC-003-build-device-baseline.md)
- 计划：[实施总计划](docs/plans/miracle-implementation-plan.md)
- 协作：[AGENTS.md](AGENTS.md) · [项目管理与工程规范](docs/project/project-standards.md)
- [上游反馈台账](docs/upstream_feedback/ledger.md)

## 快速开始（P0 交付后生效）

```bash
tools/install-mira.sh        # 按 tools/mira.lock 构建并安装 mira（arm64）
./gradlew assembleDebug      # 产出 app/build/outputs/apk/debug/*.apk
adb install -r <apk>         # OnePlus Ace 3（API 34+）
```
