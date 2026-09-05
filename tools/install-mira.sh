#!/usr/bin/env bash
# 按 tools/mira.lock 钉死的 commit 构建 mira 并安装到本地前缀。
# 规范依据：docs/project/project-standards.md §9.1、DEC-003。
# 用法：ANDROID_HOME=<sdk> tools/install-mira.sh [--force]
#   --force 删除已有源码工作目录与安装前缀后重建。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/tools/mira.lock"

fail() { echo "install-mira: $*" >&2; exit 1; }

command -v git >/dev/null || fail "git required"
command -v python3 >/dev/null || fail "python3 required"
command -v cmake >/dev/null || fail "cmake required（需 >= 3.25，支持 presets v6）"
command -v ninja >/dev/null || fail "ninja required"

eval "$(python3 - "$LOCK" <<'PY'
import json, sys
lock = json.load(open(sys.argv[1]))
def shell(v):
    return "'" + str(v).replace("'", "'\\''") + "'"
fields = {
    "MIRA_COMMIT": lock["commit"],
    "MIRA_REMOTE": lock["remote_url"],
    "MIRA_LOCAL": lock.get("local_path", ""),
    "MIRA_SRC": lock["source_workdir"],
    "MIRA_PREFIX": lock["install_prefix"],
    "MIRA_PRESET": lock["cmake_preset"],
    "MIRA_NDK": lock["ndk_version"],
    "MIRA_CMAKE_MIN": lock["cmake_minimum"],
}
print("\n".join(f"{k}={shell(v)}" for k, v in fields.items()))
PY
)"

SRC="$ROOT/$MIRA_SRC"
PREFIX="$ROOT/$MIRA_PREFIX"

if [ "${1:-}" = "--force" ]; then
  rm -rf "$SRC" "$PREFIX"
fi

# --- NDK 解析（优先 ANDROID_HOME 布局，其次环境变量） ---
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/$MIRA_NDK" ]; then
  export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$MIRA_NDK"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk/$MIRA_NDK" ]; then
  export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$MIRA_NDK"
fi
[ -n "${ANDROID_NDK_HOME:-}" ] && [ -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ] \
  || fail "NDK $MIRA_NDK 未找到（设置 ANDROID_HOME 或 ANDROID_NDK_HOME）"

# --- 源码就位：本地仓库优先克隆（快且离线），否则远程；随后精确 checkout ---
if [ ! -d "$SRC/.git" ]; then
  mkdir -p "$(dirname "$SRC")"
  if [ -n "$MIRA_LOCAL" ] && [ -d "$MIRA_LOCAL/.git" ]; then
    echo "install-mira: cloning pinned mira from local $MIRA_LOCAL"
    git clone -q "$MIRA_LOCAL" "$SRC"
  else
    echo "install-mira: cloning pinned mira from $MIRA_REMOTE"
    git clone -q "$MIRA_REMOTE" "$SRC"
  fi
fi

if ! git -C "$SRC" cat-file -e "$MIRA_COMMIT^{commit}" 2>/dev/null; then
  git -C "$SRC" fetch -q origin || fail "无法获取 commit $MIRA_COMMIT"
fi
git -C "$SRC" checkout -q --detach "$MIRA_COMMIT"
ACTUAL="$(git -C "$SRC" rev-parse HEAD)"
[ "$ACTUAL" = "$MIRA_COMMIT" ] || fail "checkout 校验失败：$ACTUAL != $MIRA_COMMIT"
git -C "$SRC" submodule update --init --recursive

# --- 配置 / 构建 / 安装（preset 需在源码目录内解析；构建树随 --force 一并重建） ---
# POSITION_INDEPENDENT_CODE=ON：消费方 libmiracle_host.so 是共享库，mira 静态库
# （core/executor 等，未显式设置 PIC 的目标遵循该全局变量）必须以 -fPIC 编译。
( cd "$SRC" && cmake --preset "$MIRA_PRESET" -DCMAKE_POSITION_INDEPENDENT_CODE=ON )
( cd "$SRC" && cmake --build --preset "$MIRA_PRESET" )
cmake --install "$SRC/build/$MIRA_PRESET" --prefix "$PREFIX"

# --- 安装完整性校验 ---
[ -f "$PREFIX/lib/cmake/Mira/MiraConfig.cmake" ] \
  || fail "安装前缀缺少 MiraConfig.cmake（$PREFIX）"

echo "install-mira: OK -> $PREFIX (mira $(python3 -c 'import json;print(json.load(open("'"$LOCK"'"))["version"])') @ ${MIRA_COMMIT:0:12})"
