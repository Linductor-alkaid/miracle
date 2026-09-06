# mira `android-host-abi.md` 证据回填拟稿（独立上游变更）

> 状态：Draft（已以 mira issue [#13](https://github.com/Linductor-alkaid/mira/issues/13) 提交上游，待 mira 维护者审核落地；miracle 不代改上游）
> 日期：2026-09-05（2026-09-06 反馈上游）
> 依据：mira `16e419e0c5b3c634885d97aebe54bc0497b609c1` + miracle P1 实测

本文件是提交给 mira 的证据回档内容建议，供维护者审核后落入 mira 仓库
`docs/compatibility/android-host-abi.md` §2 证据表与 §3 补跑条件。

## 建议新增证据行（§2）

| 声明 | 等级 | 证据 |
| --- | --- | --- |
| 真实 Android Host（Kotlin/JNI）互操作：create/start/stop/destroy、capabilities/topology、capture_frame 全路径 | Runtime verified(emulator+device) | miracle 仓库 P1：OnePlus Ace 3（Android 16/API 36，arm64）与 API 35 x86_64 模拟器（ARM 翻译）上，`AndroidHostAdapter::observe` 连续两帧成功（RGBA8888，epoch 一致，无违规回调），宿主实现为 `libmiracle_host.so`（`find_package(Mira)` 消费 0.1.0 安装包） |
| lease 释放语义在真实宿主路径闭合 | Runtime verified | 同上：宿主侧 outstanding lease 归零、destroy 无悬挂、bridge 违规计数为 0（注：bridge `leases_released` 计数在 observe 尾部 release 路径未递增，见台账备注） |

## 建议补充的契约澄清（§1 或头文件注释）

1. **out 参数零初始化**：参考消费方（adapter create、bridge 调用点）对
   `MiraHostCapabilitiesV1` / `MiraHostTopologyV1` 零初始化后传入——宿主应将
   out 参数 `struct_size==0` 视为"填充 v1"而非参数错误。
2. **`out_operation` 可空**：`HostDispatcherBridge::capture_frame` 以 `nullptr`
   调用三个异步提交函数（以 correlation 匹配结果）——宿主必须接受空出参。
3. **`deadline_ns` 语义**：adapter 将 `OperationContext.deadline`
   （steady_clock 绝对时刻）直接传入——宿主应自行换算剩余时长。

## 建议登记的改进项

- `AndroidHostAdapter` 内置 `MemoryArtifactStore` 容量（8MB）不可配置/不可注入
  （miracle 台账 `MIR-20260905-004`，mira
  [#10](https://github.com/Linductor-alkaid/mira/issues/10)）：真机一帧 6.4MB 时
  第二步 observe 即耗尽。
- bridge `leases_released` 统计口径与 observe 尾部 `lease.release()` 路径可能
  脱节（同台账备注，mira
  [#12](https://github.com/Linductor-alkaid/mira/issues/12)）。

## 关联

- miracle 仓库：`docs/plans/p1-screen-capture.md` 验证记录、
  `docs/compatibility/{oneplus-ace3,emulator-api35-x86_64}.md`、
  `docs/upstream_feedback/ledger.md`。
