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

## P1 记录（2026-09-05）

- 授权路径：ColorOS 三步（确认 → 应用选择器 → 选择 Miracle 自身）；uiautomator
  dump 会惊扰系统对话框，脚本化验证需用固定坐标盲点（详见 P1 计划验证记录）。
- 环境自检：两帧 640×1406 RGBA8888（155.8ms/106.8ms），epoch=1，
  `"ok":true`，shutdown Completed；UI 渲染含两帧预览；force-stop 干净退出。
- 帧预算：受 `MIR-20260905-004` 约束，宿主降采样至 0.9M px。

## P2 记录（2026-09-06）

- 输入链路（无障碍 dispatchGesture）：探针与 adapter 会话双轨全绿（详见
  `docs/plans/p2-input-dispatch.md` 验证记录）；证据等级 Runtime verified(device)。
- **ColorOS 拦截无障碍源 HOME**：`performGlobalAction(GLOBAL_ACTION_HOME)` 返回
  true 但前台不变（55 次 0.4s 间隔采样恒为本应用）；宿主实现追加
  CATEGORY_HOME intent 兜底——本应用前台会话可达 launcher
  （com.android.launcher），后台自动化场景受后台启动限制（P3 悬浮窗
  SYSTEM_ALERT_WINDOW 后覆盖）。back 无此拦截。
- **坐标空间**：Compose `boundsInWindow` 与屏幕原点存在约 77px 偏差（480dpi、
  edge-to-edge enforced 下实测）；宿主经 `LocalView.getLocationOnScreen` 锚定
  修正后落点精确。无障碍手势坐标空间与视觉空间一致。
- **无障碍服务生命周期**：`adb install -r` 与 `am force-stop` 会清除
  `enabled_accessibility_services`（系统行为，脚本化验证需每次重启用）；服务
  绑定存在偶发 teardown/重连窗口（~1.2s），能力位随之抖动（epoch 递增路径）。
- **uiautomator 对 Compose 返回陈旧语义树**：dump 文本与实时 UI 不一致
  （位置与文字均可能陈旧）；本机取证改用截图像素读取 + logcat JSON。
- 旋转：`user_rotation` 强制旋转下 epoch 正常递增（投影绑定会话）；旋转瞬间
  在途手势被平台拒收（dispatchGesture 拒绝或系统取消），宿主如实回执。

## P1 整屏模式问题定位与复验（2026-09-06）

- 用户报告：整屏授权后录屏指示出现、两帧已输出，自检停留"正在执行环境自检…"。
- 定位（代码走查）：UI 状态机搁浅——自检启动守卫进程级一次性而结果状态按
  ViewModel 实例私有，进程内第二次授权（"再次自检"）无完成方；两帧为上一轮
  （单应用投影）残留，"整屏"仅为第二次尝试的伴随选择，非因果。连带发现并修复：
  再次授权被前台服务静默丢弃（现拆旧建新重建投影会话）、整屏帧翻动下的持帧
  关闭竞争（拷贝并入 frameLock 临界区）。
- 复验（修复后，Runtime verified(device)）：单应用首跑 bind→结果 ~450ms；同进程
  "再次自检"换整屏通过且 **epoch=2**（会话重建）、帧预览为完整主屏幕；进程重启后
  整屏授权 bind→结果 ~1.0s；FATAL/ANR=0，force-stop 干净退出，重启 Idle 无搁浅。
- ColorOS 授权对话框：单应用三步（确认→选择器→选应用）；**整屏四步**（确认→
  选择器→整个屏幕→"屏幕共享"再次确认）。整屏流程的授权等待由第四步对话框的
  点击节奏决定；授权期间 UI 呈 Requesting（"等待投影授权…"）。
- 取证注记：本机 logcat 缓冲区分钟级滚动，取证须实时落盘（后台 `adb logcat -v
  time -s ...` 重定向）；截图读取链路在本会话出现过期缓存复用，时间线判定以
  logcat 为准，截图以 md5 区分新鲜度。
