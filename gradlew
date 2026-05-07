#!/bin/sh
# Gradle wrapper script

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
JAVA_OPTS=""
GRADLE_OPTS=""

exec "$JAVA_HOME/bin/java" $JAVA_OPTS \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
