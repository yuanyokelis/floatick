#!/bin/sh
# Minimal Gradle wrapper launcher. Generated wrapper jar lives next to this script.
set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

case "$(uname)" in
  CYGWIN*|MINGW*|MSYS*)
    if command -v cygpath >/dev/null 2>&1; then
      CLASSPATH=$(cygpath -w "$CLASSPATH")
      APP_HOME=$(cygpath -w "$APP_HOME")
    fi
    ;;
esac

if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
fi

exec "$JAVACMD" -Xmx64m -Xms64m \
  -Dorg.gradle.appname=gradlew \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
