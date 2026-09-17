#!/usr/bin/env bash
# Runs the core test-suite and produces a signed release APK in dist/.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${JAVA_HOME:-}" ]]; then
  for jdk in /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-amd64; do
    [[ -d "$jdk" ]] && export JAVA_HOME="$jdk" && break
  done
fi
if [[ ! -f keystore.properties ]]; then
  echo "keystore.properties not found: the APK will be signed with the debug key" >&2
fi

./gradlew --console=plain :core:test :app:assembleRelease

version=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -1)
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/ClearContent-${version}.apk"
sha256sum "dist/ClearContent-${version}.apk"
