#!/bin/sh
# Simplified gradle wrapper bootstrap
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
else
  echo "Gradle wrapper jar not found. Please open project in Android Studio first to generate wrapper."
  exit 1
fi
