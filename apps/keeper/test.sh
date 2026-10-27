#!/usr/bin/env bash
# Runs the plain-JVM tests for the Android-free core (no device needed):
#   1. CoreTests: plan rules, embedded list, key storage, run log, ADB client against an in-process fake adbd.
#   2. KeyEncodingCheck: our encoder/signature vs. the host's adb key pair (ADBKEY, default
#      ~/.android/adbkey and adbkey.pub), plus an OpenSSL RSA_verify(sha1) cross-check. Only public
#      material is written to build/test/key-check. Skipped when there is no adb key on this machine.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../env.sh"
need_jdk
OUT="$HERE/build/test"
ADBKEY="${ADBKEY:-$HOME/.android/adbkey}"

rm -rf "$OUT"
mkdir -p "$OUT/classes"
"$HERE/tools/gen_packages.sh" "$OUT/gen/tv/openshelf/keeper/core/PackageList.java"
find "$HERE/src/tv/openshelf/keeper/core" "$HERE/test" "$OUT/gen" -name '*.java' | native_paths | sed 's/.*/"&"/' > "$OUT/sources.txt"
javac --release 17 -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"

java -cp "$OUT/classes" tv.openshelf.keeper.core.CoreTests "$OUT/scratch"

if [ ! -f "$ADBKEY" ] || [ ! -f "$ADBKEY.pub" ]; then
  echo "SKIP KeyEncodingCheck: skipped (no adb key found at $ADBKEY and $ADBKEY.pub)"
  echo "ALL TESTS PASSED (KeyEncodingCheck skipped)"
  exit 0
fi
need_cmd openssl
java -cp "$OUT/classes" tv.openshelf.keeper.core.KeyEncodingCheck "$ADBKEY" "$ADBKEY.pub" "$OUT/key-check"
K="$OUT/key-check"
openssl pkeyutl -verify -pubin -inkey "$K/pub.pem" -pkeyopt digest:sha1 -in "$K/token.bin" -sigfile "$K/sig.bin"
# Negative control: the same signature must fail for a different token, or the check above proves nothing.
first=$(od -An -tu1 -N1 "$K/token.bin" | tr -d ' ')
printf "\\$(printf '%03o' $(( (first + 1) % 256 )))" | dd of="$K/token.bin" bs=1 count=1 conv=notrunc 2>/dev/null
if openssl pkeyutl -verify -pubin -inkey "$K/pub.pem" -pkeyopt digest:sha1 -in "$K/token.bin" -sigfile "$K/sig.bin" >/dev/null 2>&1; then
  echo "FAIL openssl accepted the signature for a modified token" >&2
  exit 1
fi
echo "PASS openssl rejects the signature for a modified token"
echo "ALL TESTS PASSED"
