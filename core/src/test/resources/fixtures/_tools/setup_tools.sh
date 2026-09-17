#!/usr/bin/env bash
# Re-create the independent verification toolchain next to this script (no system-wide install).
#   ./setup_tools.sh [TOOLS_DIR]      (default: $CLEAR_CONTENT_TOOLS or ~/.cache/clear-content-tools)
# Result:
#   perl  $DIR/exiftool-master/exiftool
#   $DIR/venv/bin/python  (c2pa-python, Pillow, pillow-heif)
#   $DIR/c2patool/c2patool/c2patool
#   $DIR/certs/es256_certs.pem + es256_private.key  (PUBLIC CAI *test* credentials, for sign_c2pa.py)
# ffmpeg/ffprobe are taken from PATH; if missing, a static build is fetched into $DIR/ffmpeg/.
set -euo pipefail
DIR="${1:-${CLEAR_CONTENT_TOOLS:-$HOME/.cache/clear-content-tools}}"
mkdir -p "$DIR" && cd "$DIR"

if [ ! -f exiftool-master/exiftool ]; then
  curl -sSL -o exiftool.tar.gz https://github.com/exiftool/exiftool/archive/refs/heads/master.tar.gz
  tar xzf exiftool.tar.gz && rm exiftool.tar.gz
fi

if [ ! -x venv/bin/python ]; then
  python3 -m venv venv
  venv/bin/pip install -q --upgrade pip
  venv/bin/pip install -q c2pa-python Pillow pillow-heif
fi

C2PATOOL_VER=${C2PATOOL_VER:-v0.27.22}
if [ ! -x c2patool/c2patool/c2patool ]; then
  mkdir -p c2patool
  curl -sSL "https://github.com/contentauth/c2pa-rs/releases/download/c2patool-${C2PATOOL_VER}/c2patool-${C2PATOOL_VER}-x86_64-unknown-linux-gnu.tar.gz" \
    | tar xz -C c2patool
fi

mkdir -p certs
for f in es256_certs.pem es256_private.key; do
  [ -f "certs/$f" ] || curl -sSL -o "certs/$f" "https://raw.githubusercontent.com/contentauth/c2pa-python/main/tests/fixtures/$f"
done

if ! command -v ffprobe >/dev/null 2>&1; then
  mkdir -p ffmpeg
  curl -sSL https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz | tar xJ -C ffmpeg --strip-components=1
  echo "export FFMPEG=$DIR/ffmpeg/ffmpeg FFPROBE=$DIR/ffmpeg/ffprobe"
fi

perl exiftool-master/exiftool -ver
venv/bin/python -c "import c2pa; print('c2pa-python', c2pa.sdk_version())"
c2patool/c2patool/c2patool --version
