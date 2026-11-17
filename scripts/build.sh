#!/usr/bin/env bash
# WPS_Box APK 构建脚本
# 依赖：Android SDK build-tools (aapt2/d8/zipalign/apksigner)、JDK 8+
# 用法：修改下方路径后执行
set -u

SDK="${ANDROID_SDK:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$HERE/build"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

# 1) 编译 Java
find "$HERE/src" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -bootclasspath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt" || exit 1

# 2) dex（必须显式列文件，传目录会报 Unsupported source file type）
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
"$BT/d8" --min-api 29 --output "$OUT/dex" $(cat "$OUT/classlist.txt") || exit 1

# 3) 资源
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip" || exit 1
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" \
    --manifest "$HERE/AndroidManifest.xml" \
    -A "$HERE/assets" "$OUT/res.zip" --min-sdk-version 29 || exit 1

# 4) 组装
cp "$OUT/base.apk" "$OUT/wpsbox-unsigned.apk"
cd "$OUT"
zip -q -j wpsbox-unsigned.apk dex/classes.dex
[ -d "$HERE/jni" ] && zip -q -r -0 wpsbox-unsigned.apk "$HERE/jni" -x '*.DS_Store'
[ -f "$HERE/assets/payload.tar.gz" ] && zip -q -0 wpsbox-unsigned.apk "$HERE/assets/payload.tar.gz"

# 5) 对齐 + 签名
"$BT/zipalign" -f -p 4 wpsbox-unsigned.apk wpsbox-aligned.apk || exit 1
"$BT/apksigner" sign --ks "$HERE/wpsbox.keystore" \
    --ks-pass pass:wpsbox123 --key-pass pass:wpsbox123 \
    --ks-key-alias wpsbox --min-sdk-version 29 \
    --out "$HERE/verify/wpsbox-1.0.apk" wpsbox-aligned.apk || exit 1
echo "OK: $HERE/verify/wpsbox-1.0.apk"
