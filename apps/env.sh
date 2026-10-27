# Toolchain lookup shared by the build and test scripts. Source it, then call the need_* functions
# for what the script uses. Each one exits with a message that says what to install or set.
#
#   JAVA_HOME           JDK 17 or newer. On macOS, if unset, /usr/libexec/java_home -v 17 is tried.
#   ANDROID_SDK_ROOT    Android SDK (ANDROID_HOME is used if ANDROID_SDK_ROOT is unset), with
#                       platforms/android-28 and build-tools/$BUILD_TOOLS_VERSION (default 34.0.0).
#
# Sets: JAVA_HOME, PATH (JDK bin first), SDK, BT, ANDROID_JAR, AAPT2, D8, ZIPALIGN, APKSIGNER, LAMBDA_STUBS.

BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-34.0.0}"

env_fail() {
  echo "error: $*" >&2
  exit 1
}

need_jdk() {
  if [ -z "${JAVA_HOME:-}" ] && [ "$(uname -s)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
  if [ -z "${JAVA_HOME:-}" ]; then
    env_fail "JAVA_HOME is not set. Install a JDK 17 (for example Eclipse Temurin 17, 'brew install openjdk@17'
       or 'apt install openjdk-17-jdk') and set JAVA_HOME to its home folder (the one that contains bin/javac)."
  fi
  if [ ! -f "$JAVA_HOME/bin/javac" ] && [ ! -f "$JAVA_HOME/bin/javac.exe" ]; then
    env_fail "JAVA_HOME=$JAVA_HOME has no bin/javac. Point JAVA_HOME at a full JDK 17, not a JRE."
  fi
  export JAVA_HOME
  PATH="$JAVA_HOME/bin:$PATH"
  export PATH
  local version major
  version="$(javac -version 2>&1)"
  major="$(printf '%s\n' "$version" | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
  if [ -z "$major" ] || [ "$major" -lt 17 ]; then
    env_fail "JAVA_HOME=$JAVA_HOME is '$version'; JDK 17 or newer is needed."
  fi
}

# Prints the path of a build-tools program; on Windows the SDK ships them as .exe or .bat.
bt_tool() {
  local f
  for f in "$BT/$1" "$BT/$1.exe" "$BT/$1.bat"; do
    if [ -f "$f" ]; then
      printf '%s\n' "$f"
      return 0
    fi
  done
  env_fail "build-tools $BUILD_TOOLS_VERSION in $SDK has no '$1'.
       Reinstall it: sdkmanager \"build-tools;$BUILD_TOOLS_VERSION\""
}

find_sdk() {
  SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [ -z "$SDK" ]; then
    env_fail "ANDROID_SDK_ROOT (or ANDROID_HOME) is not set. Install the Android SDK command-line tools,
       run: sdkmanager \"platforms;android-28\" \"build-tools;$BUILD_TOOLS_VERSION\"
       and set ANDROID_SDK_ROOT to the SDK folder."
  fi
  [ -d "$SDK" ] || env_fail "Android SDK folder not found: $SDK"
}

need_build_tools() {
  find_sdk
  BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
  if [ ! -d "$BT" ]; then
    env_fail "build-tools $BUILD_TOOLS_VERSION not found in $SDK.
       Install it: sdkmanager \"build-tools;$BUILD_TOOLS_VERSION\" (or set BUILD_TOOLS_VERSION to one you have)."
  fi
  AAPT2="$(bt_tool aapt2)"
  D8="$(bt_tool d8)"
  ZIPALIGN="$(bt_tool zipalign)"
  APKSIGNER="$(bt_tool apksigner)"
  LAMBDA_STUBS="$BT/core-lambda-stubs.jar"
  [ -f "$LAMBDA_STUBS" ] || env_fail "$LAMBDA_STUBS is missing. Reinstall: sdkmanager \"build-tools;$BUILD_TOOLS_VERSION\""
}

need_android_jar() {
  find_sdk
  ANDROID_JAR="$SDK/platforms/android-28/android.jar"
  [ -f "$ANDROID_JAR" ] || env_fail "Android 9 platform not found ($ANDROID_JAR).
       Install it: sdkmanager \"platforms;android-28\""
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || env_fail "'$1' is not on PATH. Install it with your package manager."
}

# Git Bash on Windows: javac reads paths from argfiles itself, so they must be Windows paths.
native_paths() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m -f -
  else
    cat
  fi
}
