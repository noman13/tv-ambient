#!/usr/bin/env bash
# The only build entry point (.agents/PROJECT.md "Build"). Everything runs in Docker.
#
#   scripts/build.sh                     release APK -> dist/tv-ambient-<version>.apk (debug-signed)
#   KEYSTORE_DIR=/path/to/keys scripts/build.sh   release APK signed with keys/keystore.properties
#   scripts/build.sh debug               debug APK -> dist/tv-ambient-<version>-debug.apk
#   scripts/build.sh test                Rust host tests (cargo test -p tvambient-core)
#   scripts/build.sh shell               a shell in the build image, repo at /src
#
# The first run builds the image (docker/Dockerfile: JDK 17, Android SDK 34, NDK r26d, Rust + cargo-ndk);
# Gradle and Cargo caches live in the named volumes tv-ambient-builder-gradle and tv-ambient-builder-cargo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${IMAGE:-tv-ambient-builder}"
GRADLE_VOLUME="${GRADLE_VOLUME:-tv-ambient-builder-gradle}"
CARGO_VOLUME="${CARGO_VOLUME:-tv-ambient-builder-cargo}"
WHAT="${1:-release}"

command -v docker >/dev/null || { echo "Docker is not installed or not on PATH." >&2; exit 1; }

SRC="$ROOT"
USER_ARGS=()
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) export MSYS_NO_PATHCONV=1; SRC="$(cd "$ROOT" && pwd -W)" ;;
  *) USER_ARGS=(--user "$(id -u):$(id -g)") ;;
esac

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Building the $IMAGE image once (several minutes)..."
  docker build --platform linux/amd64 -t "$IMAGE" "$SRC/docker"
fi

KEY_ARGS=()
GRADLE_PROPS=()
if [ "$WHAT" = release ] && [ -n "${KEYSTORE_DIR:-}" ]; then
  [ -f "$KEYSTORE_DIR/keystore.properties" ] || { echo "No keystore.properties in $KEYSTORE_DIR" >&2; exit 1; }
  KDIR="$(cd "$KEYSTORE_DIR" && (pwd -W 2>/dev/null || pwd))"
  KEY_ARGS=(-v "$KDIR:/keys:ro")
  GRADLE_PROPS=(-PkeystoreProperties=/keys/keystore.properties)
  echo "Signing with $KEYSTORE_DIR/keystore.properties"
elif [ "$WHAT" = release ]; then
  echo "No KEYSTORE_DIR: the release APK is signed with the build container's debug key."
fi

run() {
  docker run --rm --platform linux/amd64 ${USER_ARGS[@]+"${USER_ARGS[@]}"} ${KEY_ARGS[@]+"${KEY_ARGS[@]}"} \
    -v "$SRC:/src" -v "$GRADLE_VOLUME:/gradle" -v "$CARGO_VOLUME:/cargo" -w /src \
    -e ANDROID_USER_HOME=/gradle/.android -e CARGO_HOME=/cargo -e CARGO_TARGET_DIR=/src/target "$@"
}

VERSION="$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" "$ROOT/app/build.gradle")"
case "$WHAT" in
  test)
    run "$IMAGE" cargo test -p tvambient-core ;;
  shell)
    run -it "$IMAGE" bash ;;
  debug)
    run "$IMAGE" sh ./gradlew --no-daemon --console=plain assembleDebug
    mkdir -p "$ROOT/dist"
    cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$ROOT/dist/tv-ambient-$VERSION-debug.apk"
    echo "Built dist/tv-ambient-$VERSION-debug.apk" ;;
  release)
    run "$IMAGE" sh ./gradlew --no-daemon --console=plain ${GRADLE_PROPS[@]+"${GRADLE_PROPS[@]}"} assembleRelease
    mkdir -p "$ROOT/dist"
    cp "$ROOT/app/build/outputs/apk/release/app-release.apk" "$ROOT/dist/tv-ambient-$VERSION.apk"
    echo "Built dist/tv-ambient-$VERSION.apk" ;;
  *)
    echo "usage: scripts/build.sh [release|debug|test|shell]" >&2; exit 2 ;;
esac
