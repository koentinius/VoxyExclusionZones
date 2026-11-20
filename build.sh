#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
GRADLE_VERSION=8.14.3
BOOTSTRAP_DIR="$PWD/.gradle-bootstrap"
GRADLE_HOME="$BOOTSTRAP_DIR/gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$BOOTSTRAP_DIR"
  ZIP="$BOOTSTRAP_DIR/gradle-$GRADLE_VERSION-bin.zip"
  echo "Downloading Gradle $GRADLE_VERSION from the official Gradle distribution service..."
  curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
  unzip -q -o "$ZIP" -d "$BOOTSTRAP_DIR"
  rm "$ZIP"
fi
exec "$GRADLE_HOME/bin/gradle" --no-daemon build "$@"
