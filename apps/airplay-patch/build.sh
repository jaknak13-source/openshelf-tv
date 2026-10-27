#!/usr/bin/env bash
# Rebuilds the AirPlay Receiver APK with the "return to previous app" patch.
# Usage: APKTOOL_JAR=/path/to/apktool.jar airplay-patch/build.sh <upstream app-release.apk>
# Needs JAVA_HOME (JDK 17), ANDROID_SDK_ROOT with build-tools (see ../env.sh), python3 and openssl.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
if [ $# -ne 1 ]; then
  echo "usage: APKTOOL_JAR=/path/to/apktool.jar $0 <upstream app-release.apk>" >&2
  echo "The upstream APK is the release build of github.com/jqssun/android-airplay-server (tested: v0.0.36)." >&2
  exit 2
fi
IN="$1"
[ -f "$IN" ] || { echo "error: upstream APK not found: $IN" >&2; exit 1; }

. "$HERE/../env.sh"
need_jdk
need_build_tools
need_cmd python3
need_cmd openssl
if [ -z "${APKTOOL_JAR:-}" ]; then
  env_fail "APKTOOL_JAR is not set. Download apktool.jar from https://apktool.org (tested with 3.0.3)
       and set APKTOOL_JAR to the path of the .jar file."
fi
[ -f "$APKTOOL_JAR" ] || env_fail "APKTOOL_JAR points to a missing file: $APKTOOL_JAR"
OUT="$HERE/build"
SIGN="$HERE/signing"

rm -rf "$HERE/decoded" "$OUT"
mkdir -p "$OUT"
java -jar "$APKTOOL_JAR" d -f -o "$HERE/decoded" "$IN" >/dev/null
python3 -I "$HERE/apply_patch.py" "$HERE/decoded"
java -jar "$APKTOOL_JAR" b "$HERE/decoded" -o "$OUT/unsigned.apk" --aapt "$AAPT2" >/dev/null
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$SIGN/airplay.p12" ]; then
  mkdir -p "$SIGN" && chmod 700 "$SIGN"
  ( umask 077; openssl rand -hex 24 > "$SIGN/.pass" )
  keytool -genkeypair -keystore "$SIGN/airplay.p12" -storetype PKCS12 \
    -storepass:file "$SIGN/.pass" -keypass:file "$SIGN/.pass" -alias airplay \
    -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=AirPlay Receiver (patched)" >/dev/null 2>&1
  chmod 600 "$SIGN/airplay.p12"
fi
"$APKSIGNER" sign --ks "$SIGN/airplay.p12" --ks-pass "file:$SIGN/.pass" --ks-key-alias airplay \
  --v2-signing-enabled true --v3-signing-enabled true --out "$OUT/airplay-patched.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/airplay-patched.apk"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk"
ls -la "$OUT/airplay-patched.apk"
