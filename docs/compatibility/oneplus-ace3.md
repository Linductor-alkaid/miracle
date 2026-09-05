# OnePlus Ace 3 真机证据

> 状态：Active（等待首次执行）
> 更新日期：2026-09-05
> 适用范围：唯一真机门禁设备（DEC-003）的验证记录

## 设备基线

| 项 | 值 |
| --- | --- |
| SoC / ABI | Snapdragon 8 Gen 2；arm64-v8a |
| 屏幕 | 6.78" 2780×1264 LTPO AMOLED 120Hz |
| 系统 | 出厂 Android 14（ColorOS 14）；当前固件以实测 `adb shell getprop ro.build.version.sdk` 登记为准 |

## 验证记录

（待补；首次执行后按工程规范 §7 填写日期、commit、固件版本、命令与结果）

首次执行清单（P0 冒烟）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.linductor.miracle/.MainActivity
adb logcat -s miracle/bridge   # 期望 "ok":true ... "final_state":"Stopped"
```

补跑条件与负责人见 [P0 验证记录](../plans/p0-skeleton-consumption.md)。
