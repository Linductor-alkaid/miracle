# Huawei ADA-AL00 真机证据（第二受支持设备）

> 状态：Active
> 更新日期：2026-09-05
> 适用范围：多设备矩阵扩展（总计划 `POST-04` 首台扩展设备）

## 设备基线（实测）

| 项 | 值 |
| --- | --- |
| 型号 | HUAWEI ADA-AL00（EmotionUI 14.2.0，固件 ADA-AL00 4.2.0.235） |
| 系统 | Android 12（API 31） |
| ABI | `arm64-v8a,armeabi-v7a,armeabi`（主 ABI arm64-v8a） |
| 序列号 | `26QUT24905007471` |

与 OnePlus Ace 3（API 36）互补，覆盖 `minSdk 26` 与 `compileSdk 35` 之间的中档区间；
API 31 分支验证了 POST_NOTIFICATIONS 运行时请求不触发（33+ 才请求）的兼容路径。

## 验证记录

2026-09-05（P0+P1，同一 debug APK，versionCode 1 / 0.1.0）：

- P0：安装 Success；启动自检 `"ok":true`，终态 `Stopped`，mira 0.1.0，无崩溃。
- P1（首次运行）：环境自检两帧 630×1428 RGBA8888（160.3/125.9ms），epoch=1，
  违规计数全 0，`shutdown: Completed`；UI 渲染"✅ 环境自检通过"+两帧详情；
  force-stop 干净退出（FATAL/ANR=0，进程终止）。
- P1（复验，交互授权路径）：系统对话框"是否允许 Miracle 录制/投射您的屏幕"→
  点"允许"→ 两帧 630×1428（205.8/97.6ms），同样全绿。
- 证据等级：Runtime verified(device)。

## 观察项

1. **首次运行时未观察到授权对话框阻塞**：tap 授权按钮后约 5 秒内完成授权→
   服务→两帧自检，未呈现 EMUI 投影对话框（复验时对话框正常弹出且需手动允许）。
   机制待查（疑似首次 consent 的系统级行为差异）；不影响功能结论，但与
   "用户显式同意后才采集"的同意模型相关，P2 前建议复现确认。
2. EMUI 授权对话框为单步"允许/取消"（区别于 ColorOS 的三步流程），自动化脚本
   需按厂商分别定位。
3. 华为 USB 接入：udev ACL（uaccess）自动授权，无需新增 udev 规则。
