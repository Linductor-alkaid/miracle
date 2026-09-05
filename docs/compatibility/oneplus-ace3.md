# OnePlus Ace 3 真机证据

> 状态：Active
> 更新日期：2026-09-05
> 适用范围：唯一真机门禁设备（DEC-003）的验证记录

## 设备基线（实测）

| 项 | 值 |
| --- | --- |
| 型号 | PJE110（OnePlus Ace 3） |
| 系统 | Android 16（API 36），ColorOS V16.0.0 |
| ABI | `arm64-v8a,armeabi-v7a,armeabi`（主 ABI arm64-v8a） |
| 序列号 | `a4dfdcbf` |

## 验证记录

2026-09-05（P0 真机冒烟，commit `45775e9` 对应的 APK，`versionCode 1 / 0.1.0`）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk   # Success
adb shell am start -n dev.linductor.miracle/.MainActivity
adb logcat -s miracle/bridge
```

- logcat：
  `self test: {"ok":true,"stage":"complete","detail":"baseline completed",
  "init_ms":0,"wait_ms":1,"result_code":"Applied","task_terminal":false,
  "final_state":"Stopped","mira_version":"0.1.0"}`
- UI dump 确认渲染：`自检通过 / mira 版本 0.1.0 / Stopped / 悬浮球占位`。
- `am force-stop` 后无 FATAL/ANR。
- 环境备注：设备以 udev 规则（`51-android-oneplus.rules`，vendor `22d9`）+ USB 授权接入；
  API 36 高于 compileSdk 35/targetSdk 35 的构建基线，向前兼容正常。
- 证据等级：**Runtime verified(device)**——mira 0.1.0 Android arm64 闭包经本仓库
  `libmiracle_host.so` 在真机上完成 RuntimeBaseline 初始化与干净关闭。

## ColorOS 已确认事项

- USB 调试授权流程正常（弹窗授权 + 始终允许）。
- 后台管理对前台服务/无障碍的回收行为待 P1/P2 链路接入后验证（构建打包设计 §7）。
