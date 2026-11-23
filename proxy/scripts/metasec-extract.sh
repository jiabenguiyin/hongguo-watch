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

# --- license.b64 (aid=8662 MSSDK license; same string HgSign/hongguo-gui hardcodes) ---
# Provenance: SecApi / MS init config string in multi-DEX (classes*.dex), prefix "10CS/".
# Official 7.4.5.32: classes26.dex. Optional override: METASEC_LICENSE env.
if [[ ! -s "$DEST/license.b64" ]]; then
  TMPDEX=$(mktemp -d)
  trap 'rm -rf "$TMPDEX"' EXIT
  unzip -o -q "$APK" 'classes*.dex' -d "$TMPDEX" || true
  python3 - "$TMPDEX" "$DEST/license.b64" <<'PY' || true
import re, sys
from pathlib import Path
src, out = Path(sys.argv[1]), Path(sys.argv[2])
best = b""
for dex in sorted(src.glob("classes*.dex")):
    data = dex.read_bytes()
    for m in re.finditer(rb"10CS/[A-Za-z0-9+/=]{80,}", data):
        s = m.group()
        if len(s) > len(best):
            best = s
if not best:
    sys.exit(1)
out.write_text(best.decode("ascii") + "\n")
print(f"license.b64 ← DEX ({len(best)} chars)")
PY
fi

# --- ms_16777218.bin (optional) ---
# HgSign/hongguo-gui: MS.b(16777218) returns package Signature.toByteArray() (cert DER).
# Best: harvest from a working device / prior MetaSec backup.
# Fallback: APK signing cert DER from META-INF/*.RSA (PKCS#7). Engine tolerates missing file.
if [[ ! -s "$DEST/refso/data/ms_16777218.bin" ]]; then
  TMPMETA=$(mktemp -d)
  unzip -o -q "$APK" 'META-INF/*' -d "$TMPMETA" || true
  RSA=$(ls "$TMPMETA"/META-INF/*.RSA "$TMPMETA"/META-INF/*.DSA "$TMPMETA"/META-INF/*.EC 2>/dev/null | head -1 || true)
  if [[ -n "${RSA:-}" ]] && command -v openssl >/dev/null 2>&1; then
    if openssl pkcs7 -in "$RSA" -inform DER -print_certs 2>/dev/null \
      | openssl x509 -outform DER -out "$DEST/refso/data/ms_16777218.bin" 2>/dev/null; then
      echo "ms_16777218.bin ← APK signing cert DER ($(wc -c <"$DEST/refso/data/ms_16777218.bin") bytes) [optional fallback]"
    fi
  fi
  rm -rf "$TMPMETA"
fi

LIC_OK=0; [[ -s "$DEST/license.b64" ]] && LIC_OK=1
CERT_OK=0; [[ -s "$DEST/refso/data/ms_16777218.bin" ]] && CERT_OK=1

cat <<EOF
OK: $DEST/lib/arm64-v8a/libmetasec_ml.so ($(wc -c <"$DEST/lib/arm64-v8a/libmetasec_ml.so") bytes)
    license.b64: $([ "$LIC_OK" = 1 ] && echo present || echo MISSING — set METASEC_LICENSE or re-run with full DEX APK)
    ms_16777218.bin: $([ "$CERT_OK" = 1 ] && echo present || echo absent — optional; device harvest preferred)

HgSign / hongguo-gui mapping:
  license  → cfg[3] in MS.init (aid 8662), string starting with 10CS/
  cert     → MS.b(16777218) / PackageManager signatures; optional in unidbg port

Start:
  export METASEC_HOME=$DEST
  export METASEC_BACKEND=unicorn2
  PORT=8787 BIND=0.0.0.0 ./bin/proxy
EOF
