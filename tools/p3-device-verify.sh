#!/usr/bin/env bash
# P3 真机取证驱动（docs/plans/p3-loop-mvp.md 补跑条件；工程规范 §7 验证证据）。
#
# 依据 P2 真机经验（docs/compatibility/oneplus-ace3.md）：
#  - uiautomator 对 Compose 语义树陈旧 → 证据以 logcat JSON（miracle/loop、
#    miracle/verify）+ 截图为准；
#  - adb install -r 会清除无障碍开关 → 每次安装后重启用；
#  - ColorOS 投影授权对话框无法脚本化（三步、dump 惊扰）→ 需人工完成一次，
#    脚本以 logcat "host bound" 判定后继续；
#  - 通知/悬浮窗可经 adb 预授权（pm grant / appops）。
#
# 用法：tools/p3-device-verify.sh [serial] [scenario...]
#   scenario ∈ setup|complete|max_steps|cancel|r3|connectivity|takeover
#   默认：setup complete max_steps cancel r3 takeover（connectivity 需先在
#   设置页配置真实 VLM 端点与密钥）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG="dev.linductor.miracle"
# 首参为序列号仅当它不是场景名（免序列号调用时自动忽略）
SCENARIO_NAMES='setup|complete|max_steps|cancel|r3|connectivity|takeover'
SERIAL=""
if [ "$#" -gt 0 ] && ! printf '%s' "$1" | grep -qxE "$SCENARIO_NAMES"; then
    SERIAL="$1"
fi
OUT_DIR="$ROOT/build/p3-device-evidence"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

[ -x "$ADB" ] || { echo "p3-verify: adb 不存在（$ADB）" >&2; exit 1; }
[ -f "$APK" ] || { echo "p3-verify: 先执行 assembleDebug（缺 $APK）" >&2; exit 1; }

adb_cmd() {
    if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi
}

wait_for_device() {
    echo "== 等待设备接入 =="
    adb_cmd wait-for-device
    adb_cmd shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
    echo "设备：$(adb_cmd shell getprop ro.product.model | tr -d '\r')"
}

logcat_since() {
    # 输出自本次启动以来的证据行（ miracle/loop | miracle/verify | miracle/bridge ）
    adb_cmd logcat -d -v time -s miracle/loop:V miracle/verify:V miracle/bridge:V miracle/service:V
}

wait_logcat_match() {
    # $1: 模式；$2: 超时秒。命中输出 0，超时输出 1。
    local pattern="$1" timeout="${2:-90}" waited=0
    while [ "$waited" -lt "$timeout" ]; do
        if logcat_since | grep -q "$pattern"; then
            return 0
        fi
        sleep 3
        waited=$((waited + 3))
    done
    return 1
}

scenario_setup() {
    echo "== 安装与授权 =="
    adb_cmd install -r "$APK"
    # 通知权限（API 33+ 可 adb 授予）
    adb_cmd shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    # 悬浮窗（appops；ColorOS 16 可能拒绝 shell 设置——降级为人工引导，不阻断）
    adb_cmd shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || \
        echo "!! appops 被系统拒绝：悬浮球需在设置页手动授权（仅影响球体交互取证）" 
    # 无障碍（install -r 会清除，重启服务）
    adb_cmd shell settings put secure enabled_accessibility_services \
        "$PKG/.host.MiracleAccessibilityService"
    adb_cmd shell settings put secure accessibility_enabled 1
    sleep 2
    echo "-- 无障碍状态：$(adb_cmd shell settings get secure enabled_accessibility_services | tr -d '\r')"
    echo "== 启动应用并等待投影授权 =="
    adb_cmd logcat -c
    adb_cmd shell am force-stop "$PKG" || true
    sleep 1
    adb_cmd shell am start -n "$PKG/.MainActivity"
    echo "!! 请在设备上完成投影授权（若此前已授权且服务已绑定则自动继续）…"
    if wait_logcat_match "host bound" 120; then
        echo "-- 投影会话已绑定（host bound）"
    else
        echo "!! 120s 内未见 host bound：请在设备上点击“授权屏幕采集并自检”并完成系统对话框" >&2
        wait_logcat_match "host bound" 180 || { echo "!! 投影未绑定，中止" >&2; exit 2; }
    fi
}

run_scenario() {
    local scenario="$1"
    echo "== 场景：$scenario =="
    adb_cmd logcat -c
    case "$scenario" in
    connectivity)
        adb_cmd shell am start -n "$PKG/.MainActivity" \
            --es "$PKG.extra.AUTO_SCENARIO" connectivity
        ;;
    takeover)
        # 语义链路经 auto 场景驱动（admission 失效→取消→RELEASE_ALL→确认失效）；
        # 悬浮球长按手势为独立人工取证项（若已授权悬浮窗）。
        adb_cmd shell am start -n "$PKG/.MainActivity" \
            --es "$PKG.extra.AUTO_SCENARIO" takeover
        ;;
    *)
        adb_cmd shell am start -n "$PKG/.MainActivity" \
            --es "$PKG.extra.AUTO_SCENARIO" "$scenario"
        ;;
    esac

    if [ "$scenario" = "r3" ]; then
        echo "!! 场景 r3：出现确认对话框后请人工点击“允许一次”（或“拒绝”做负向取证）"
    fi

    local waited=0
    while [ "$waited" -lt 90 ]; do
        if adb_cmd logcat -d -v time -s miracle/verify:V | grep -q "dryrun scenario="; then
            break
        fi
        if [ "$scenario" = "connectivity" ] && \
            adb_cmd logcat -d -v time -s miracle/verify:V | grep -q "connectivity "; then
            break
        fi
        sleep 3
        waited=$((waited + 3))
    done

    mkdir -p "$OUT_DIR"
    logcat_since > "$OUT_DIR/$scenario.logcat.txt"
    adb_cmd exec-out screencap -p > "$OUT_DIR/$scenario.png"
    echo "-- 证据：$OUT_DIR/$scenario.{logcat.txt,png}"
    echo "-- 结果行："
    grep -E "dryrun scenario=|connectivity " "$OUT_DIR/$scenario.logcat.txt" | tail -2 || true
    grep -E '"kind":"(result|confirmation_request|confirmation_settled)"' \
        "$OUT_DIR/$scenario.logcat.txt" | tail -6 || true
}

mkdir -p "$OUT_DIR"
wait_for_device
if [ -n "$SERIAL" ]; then
    shift
fi
SCENARIOS=("$@")
if [ ${#SCENARIOS[@]} -eq 0 ] || [ "${SCENARIOS[0]}" = "setup" ]; then
    scenario_setup
    [ ${#SCENARIOS[@]} -le 1 ] && SCENARIOS=(complete max_steps cancel r3 takeover)
else
    # 免安装直接跑场景：仍需投影已绑定
    wait_logcat_match "host bound" 10 || {
        echo "!! 投影未绑定：先运行 setup（tools/p3-device-verify.sh setup）" >&2
        exit 2
    }
fi

for scenario in "${SCENARIOS[@]}"; do
    [ "$scenario" = "setup" ] && continue
    run_scenario "$scenario"
done

echo "== 完成：证据目录 $OUT_DIR（汇总进 docs/plans/p3-loop-mvp.md 验证记录）=="
