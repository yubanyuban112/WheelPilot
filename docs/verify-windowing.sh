#!/usr/bin/env bash
# 「轻量打开」技术单点验证：全屏 task 能否就地转成浮窗
#
# 这是整套方案的唯一不确定点（见 docs/light-open-feasibility.md 第 4.2 节）。
# 其余判据都是纯逻辑，只有这条命令链依赖具体 ROM 的实现。
#
# 用法：
#   ./docs/verify-windowing.sh com.android.chrome
#   ./docs/verify-windowing.sh com.tencent.mm com.tencent.mm.ui.LauncherUI
#   ADB_BIN=/path/to/adb ./docs/verify-windowing.sh <包名>
#
# 输出四种结论之一：
#   PASS-RELAUNCH       主路径有效，am start --windowingMode 5 能把全屏 task 就地改成浮窗
#   FALLBACK-RESIZE     relaunch 只改了几何没改模式，需要用 am task resize 兜底
#   FAIL                两种命令都没让 task 离开全屏，需要走无障碍点系统小窗按钮
#   MANUAL              输出里拿不到 windowingMode，需人工看 dumpsys 片段
set -u

ADB="${ADB_BIN:-adb}"
PKG="${1:-}"
ACT="${2:-}"

if [ -z "$PKG" ]; then
  echo "用法: $0 <包名> [Activity 全名]"
  exit 2
fi
if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "找不到 adb，可用 ADB_BIN=/绝对路径/adb 指定"
  exit 2
fi
if [ -z "$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')" ]; then
  echo "没有已授权的设备（adb devices 为空）"
  exit 2
fi

say() { printf '\n== %s\n' "$1"; }

# 1. 解析启动组件
say "1/5 解析启动组件"
if [ -z "$ACT" ]; then
  ACT=$("$ADB" shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tail -1 | tr -d '\r')
fi
echo "component=$ACT"
case "$ACT" in
  */*) : ;;
  *) echo "解析不到启动 Activity，请手动传第二个参数"; exit 2 ;;
esac

# 2. 以全屏启动，建立"全屏 task"这个初始条件
say "2/5 全屏启动（建立初始条件）"
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell am start -n "$ACT" >/dev/null 2>&1
sleep 2

# 3. 抓当前 windowingMode 与 task id
read_mode() {
  "$ADB" shell dumpsys activity activities 2>/dev/null \
    | tr -d '\r' \
    | awk -v pkg="$PKG" '
        /Task\{/            { inblk = ($0 ~ pkg); buf = "" }
        inblk               { buf = buf $0 "\n" }
        inblk && /windowingMode=/ { print buf; exit }
      '
}

read_task_id() {
  "$ADB" shell dumpsys activity activities 2>/dev/null | tr -d '\r' \
    | awk -v pkg="$PKG" '
        /Task\{/ && $0 ~ pkg {
          if (match($0, /#[0-9]+/)) { print substr($0, RSTART+1, RLENGTH-1); exit }
        }'
}

say "3/5 读取启动后的 windowingMode"
BEFORE=$(read_mode)
TASK_ID=$(read_task_id)
echo "taskId=${TASK_ID:-未找到}"
echo "$BEFORE" | grep -Ei 'windowingMode|bounds|Task\{' | head -5
case "$BEFORE" in
  *freeform*) echo "启动后已是 freeform，说明该应用默认就进浮窗，本次验证无效，换一个全屏应用" ; exit 0 ;;
esac

# 4. 主路径：带 windowingMode 重新 start，看能否就地改模式
say "4/5 主路径 relaunch windowingMode=5"
"$ADB" shell am start -n "$ACT" --windowingMode 5 2>&1 | tail -2
sleep 2
AFTER=$(read_mode)
echo "$AFTER" | grep -Ei 'windowingMode|bounds' | head -5

case "$AFTER" in
  *freeform*) echo; echo "结论: PASS-RELAUNCH —— 主路径有效，转窗引擎可直接用这条命令" ; exit 0 ;;
esac

# 5. 兜底：resizeable + resize 到屏幕 70%
say "5/5 兜底 am task resize"
if [ -z "${TASK_ID:-}" ]; then
  echo "拿不到 task id，跳过兜底"
  echo; echo "结论: MANUAL —— 请把上面的 dumpsys 片段贴回"
  exit 1
fi

SIZE=$("$ADB" shell wm size 2>/dev/null | tr -d '\r' | awk -F': ' '/Override|Physical/{print $2}' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
case "${W:-}" in ''|*[!0-9]*) W=1080; H=2400 ;; esac
BW=$((W * 70 / 100)); BH=$((H * 60 / 100))
L=$(( (W - BW) / 2 )); T=$(( (H - BH) / 2 ))
R=$(( L + BW )); B=$(( T + BH ))

"$ADB" shell am task resizeable "$TASK_ID" 2 >/dev/null 2>&1
"$ADB" shell am task resize "$TASK_ID" "$L,$T,$R,$B" 2>&1 | tail -2
"$ADB" shell am stack resize "$TASK_ID" "$L,$T,$R,$B" >/dev/null 2>&1
sleep 2

FINAL=$(read_mode)
echo "$FINAL" | grep -Ei 'windowingMode|bounds' | head -5
case "$FINAL" in
  *freeform*) echo; echo "结论: FALLBACK-RESIZE —— relaunch 无效但 resize 能转，引擎需带 resize 兜底" ;;
  *) echo; echo "结论: FAIL —— 两条命令均无效，该 ROM 需走无障碍点系统小窗按钮" ;;
esac
