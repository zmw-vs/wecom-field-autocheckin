#!/bin/sh
# 精简版 Gradle 启动脚本（POSIX / Git-Bash 兼容）
APP_HOME=$(cd "$(dirname "$0")" >/dev/null && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

exec "$JAVACMD" -Xmx128m -Xms64m \
    "-Dorg.gradle.appname=$(basename "$0")" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
