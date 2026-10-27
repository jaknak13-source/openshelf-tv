#!/usr/bin/env bash
# Builds build/openshelf-home.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs JAVA_HOME (JDK 17) and ANDROID_SDK_ROOT; see ../env.sh.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../env.sh"
need_jdk
need_build_tools
need_android_jar
need_cmd zip
need_cmd openssl
OUT="$HERE/build"
SIGN="$HERE/signing"
APK="$OUT/openshelf-home.apk"

free_kb=$(df -Pk "$HERE" | awk 'NR==2 {print $4}')
if [ "$free_kb" -lt $((2 * 1024 * 1024)) ]; then
  echo "less than 2 GB free on disk; not building" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$AAPT2" compile --dir "$HERE/res" -o "$OUT/res.zip"
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" \
  --java "$OUT/gen" -o "$OUT/unaligned.apk" "$OUT/res.zip"

# javac argfiles need quoting because the checkout path may contain spaces.
find "$HERE/src" "$OUT/gen" -name '*.java' | native_paths | sed 's/.*/"&"/' > "$OUT/sources.txt"
# core-lambda-stubs.jar supplies LambdaMetafactory, which android.jar lacks; d8 desugars the lambdas.
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR:$LAMBDA_STUBS" \
  -Xlint:-options -Xlint:unchecked -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --release --min-api 28 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"
(cd "$OUT/dex" && zip -q "$OUT/unaligned.apk" classes.dex)

"$ZIPALIGN" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

if [ ! -f "$SIGN/home.p12" ]; then
  mkdir -p "$SIGN"
  chmod 700 "$SIGN"
  (umask 077 && openssl rand -base64 24 | tr -d '\n' > "$SIGN/.pass")
  chmod 600 "$SIGN/.pass"
  keytool -genkeypair -keystore "$SIGN/home.p12" -storetype PKCS12 -storepass:file "$SIGN/.pass" \
    -alias home -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OpenShelf" >/dev/null
  chmod 600 "$SIGN/home.p12"
  echo "created signing key in $SIGN"
fi
"$APKSIGNER" sign --v2-signing-enabled true --ks "$SIGN/home.p12" --ks-pass "file:$SIGN/.pass" --out "$APK" "$OUT/aligned.apk"
rm -f "$APK.idsig" "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "built $APK ($(wc -c < "$APK" | tr -d ' ') bytes)"
