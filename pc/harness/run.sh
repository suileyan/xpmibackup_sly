#!/usr/bin/env bash
# 端到端逻辑通路验证：应用真实源码(WebdavFileHelp/ConfigHelp/LogHelp) 在桌面 JVM 直接打 Go 服务端
# 前置：pc/dist/mibackpc.exe 已在 18321 端口运行（见 pc/README.md）
set -e
PC_WIN="$(cygpath -m "$(cd "$(dirname "$0")/.." && pwd)")"
APP_SRC="$PC_WIN/../src/app/src/main/java/com/suileyan"
OUT="$PC_WIN/harness/build"
ANDROID_JAR="D:/Dev/Runing/android-sdk/platforms/android-37.0/android.jar"
LAYOUTLIB="D:/Dev/Runing/android-sdk/platforms/android-17/data/layoutlib.jar"
GRADLE_MODULES="D:/Dev/caches/gradle/caches/modules-2/files-2.1"
OKHTTP=$(find "$(cygpath -u "$GRADLE_MODULES")/com.squareup.okhttp3/okhttp" -name "okhttp-4.12.0.jar" | head -1)
OKIO=$(find "$(cygpath -u "$GRADLE_MODULES")/com.squareup.okio/okio-jvm" -name "okio-jvm-3.6.0.jar" | head -1)
KOTLIN=$(find "$(cygpath -u "$GRADLE_MODULES")/org.jetbrains.kotlin/kotlin-stdlib" -name "kotlin-stdlib-2.0.21.jar" | head -1)
OKHTTP_W=$(cygpath -m "$OKHTTP"); OKIO_W=$(cygpath -m "$OKIO"); KOTLIN_W=$(cygpath -m "$KOTLIN")

rm -rf "$OUT"
mkdir -p "$OUT"
javac -encoding UTF-8 \
  -cp "$ANDROID_JAR;$OKHTTP_W;$OKIO_W;$KOTLIN_W" \
  -d "$OUT" \
  "$PC_WIN/harness/android/util/Log.java" \
  "$APP_SRC/cloud/ProgressCallback.java" \
  "$APP_SRC/cloud/RemoteEntry.java" \
  "$APP_SRC/comm/AtomicFile.java" \
  "$APP_SRC/comm/LogHelp.java" \
  "$APP_SRC/comm/ConfigHelp.java" \
  "$APP_SRC/comm/WebdavFileHelp.java" \
  "$PC_WIN/harness/Harness.java"
echo "编译通过"
java -Dfile.encoding=UTF-8 -cp "$OUT;$LAYOUTLIB;$ANDROID_JAR;$OKHTTP_W;$OKIO_W;$KOTLIN_W" Harness "${1:-http://127.0.0.1:18321/dav/}" "${2:-miback}" "${3:?需要密码参数}"
