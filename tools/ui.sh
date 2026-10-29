#!/bin/bash
# Helpers for driving the app on a device/emulator via adb.
#   ui.sh tap "Text"        – tap the first on-screen node whose text/desc matches
#   ui.sh shot name         – save screenshot to screenshots/name.png
source ~/tools/env.sh
OUT="$(dirname "$0")/../screenshots"
mkdir -p "$OUT"
case "$1" in
  tap)
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    b=$(adb shell cat /sdcard/ui.xml | python3 -c "
import sys,re,html
x=sys.stdin.read(); t=sys.argv[1]
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    tx=html.unescape(re.search(r'text=\"([^\"]*)\"',n).group(1)); d=html.unescape(re.search(r'content-desc=\"([^\"]*)\"',n).group(1))
    if tx==t or d==t:
        b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n).groups(); b=list(map(int,b))
        print((b[0]+b[2])//2,(b[1]+b[3])//2); break
" "$2")
    [ -z "$b" ] && { echo "not found: $2"; exit 1; }
    adb shell input tap $b ;;
  shot)
    # Pick whichever display is currently on (inner when unfolded, outer when folded).
    ids=$(adb shell dumpsys SurfaceFlinger --display-id | grep -o "^Display [0-9]*" | awk '{print $2}')
    for id in $ids; do
      adb exec-out screencap -d $id -p > "$OUT/.tmp.png"
      if python3 -c "import sys,struct;d=open(sys.argv[1],'rb').read(24);w,h=struct.unpack('>II',d[16:24]);sys.exit(0 if (w>1500)==(sys.argv[2]=='inner') else 1)" "$OUT/.tmp.png" "${3:-inner}"; then
        mv "$OUT/.tmp.png" "$OUT/$2.png"; echo "$OUT/$2.png"; break
      fi
    done ;;
  open)
    adb shell am start -S -n com.dez.sports/.MainActivity ${2:+--es route "$2"} >/dev/null ;;
esac
