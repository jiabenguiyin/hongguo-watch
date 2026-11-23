#!/usr/bin/env bash
# Extract MetaSec native assets from a private com.phoenix.read APK onto the proxy host.
# NEVER commit the extracted .so / license / cert into the public git tree.
set -euo pipefail

APK="${1:-}"
DEST="${2:-${METASEC_HOME:-/opt/hongguo-playback-proxy/metasec}}"

if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "Usage: $0 /path/to/com.phoenix.read.apk [METASEC_HOME]" >&2
  exit 2
fi

mkdir -p "$DEST/lib/arm64-v8a" "$DEST/refso/data" "$DEST/msdata"
echo "Extracting arm64-v8a natives → $DEST/lib/arm64-v8a"
unzip -o -j -q "$APK" 'lib/arm64-v8a/libmetasec_ml.so' 'lib/arm64-v8a/libc++_shared.so' -d "$DEST/lib/arm64-v8a" || {
  echo "WARN: unzip specific sos failed; trying all arm64-v8a/*" >&2
  unzip -o -j -q "$APK" 'lib/arm64-v8a/*' -d "$DEST/lib/arm64-v8a"
}

# stub apk path (mssdk may open it; empty file is OK for hongguo-gui compatibility)
: > "$DEST/base.apk"

if [[ ! -s "$DEST/lib/arm64-v8a/libmetasec_ml.so" ]]; then
  echo "ERROR: libmetasec_ml.so missing after extract" >&2
  exit 1
fi

cat <<EOF
OK: $DEST/lib/arm64-v8a/libmetasec_ml.so ($(wc -c <"$DEST/lib/arm64-v8a/libmetasec_ml.so") bytes)

Still required (private — do not git-commit):
  1) $DEST/license.b64
     MSSDK license string for aid=8662 (from SecApi / MS init config in the APK DEX).
     Or export METASEC_LICENSE='…' when starting the proxy.
  2) $DEST/refso/data/ms_16777218.bin
     Package-signature blob returned by MS.b(16777218). Copy from a prior working
     hongguo MetaSec extract if you have one.

Start:
  export METASEC_HOME=$DEST
  export METASEC_BACKEND=unicorn2
  PORT=8787 BIND=0.0.0.0 ./bin/proxy
EOF
