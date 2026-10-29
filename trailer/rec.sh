#!/bin/bash
# Records one trailer clip from the emulator: rec.sh <name> <seconds> <inner|outer> -- <actions script run while recording>
source ~/tools/env.sh
IN=4619827259835644672; OUT=4619827551948147201
name=$1; secs=$2; disp=$([ "$3" = outer ] && echo $OUT || echo $IN); shift 4
adb shell screenrecord --display-id $disp --time-limit $secs --bit-rate 16000000 /sdcard/$name.mp4 &
rp=$!
sleep 0.6
bash -c "$*"
wait $rp
adb pull /sdcard/$name.mp4 "$(dirname "$0")/clips/$name.mp4" >/dev/null && adb shell rm /sdcard/$name.mp4
ffmpeg -v error -y -sseof -1 -i "$(dirname "$0")/clips/$name.mp4" -frames:v 1 -update 1 "$(dirname "$0")/clips/$name.png"
echo "$name done"
