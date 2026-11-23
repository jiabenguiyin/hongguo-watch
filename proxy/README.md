# Playback signing proxy

Watch clients request **stream URLs only** (`GET /v1/stream`). This process owns
`device_register` → sign → `video_tab` / `multi_video_model` and returns CDN HTTPS URLs.
Media bytes are **not** proxied — the watch plays CDN URLs directly.

## Run locally

```bash
# from repo root
./gradlew :proxy:run

# optional
PORT=8787 BIND=0.0.0.0 HONGGUO_PLAYBACK_PROXY_TOKEN=devsecret ./gradlew :proxy:run
```

### Dist (Java 17 bytecode + unidbg)

```bash
./gradlew :proxy:proxyDeployTar
# → ~41 MiB tar with unidbg jars (no proprietary .so)
```

On a host with **OpenJDK 17+** and private MetaSec assets under `METASEC_HOME`
(default `/opt/hongguo-playback-proxy/metasec`):

```bash
tar -xzf hongguo-playback-proxy.tar.gz
bash proxy/scripts/metasec-extract.sh /path/to/phoenix.read.apk /opt/hongguo-playback-proxy/metasec
# auto: libmetasec_ml.so, license.b64 (DEX 10CS/…), optional ms_16777218.bin (APK cert DER)
# or: export METASEC_LICENSE='10CS/…'  — cert may be omitted
export METASEC_HOME=/opt/hongguo-playback-proxy/metasec
export METASEC_BACKEND=unicorn2
cd proxy && PORT=8787 BIND=0.0.0.0 HONGGUO_PLAYBACK_PROXY_TOKEN=secret ./bin/proxy
```

Store copy: `/cursor/stores/self/media/hongguo-playback-proxy.tar.gz`.
Deploy notes: `/cursor/stores/self/docs/metasec-proxy-deploy.md`.
Private samples (host-only, not git): `/cursor/stores/self/internal/metasec-host-assets/`.

Smoke:

```bash
curl -sS 'http://127.0.0.1:8787/health'
curl -sS -H "Authorization: Bearer devsecret" \
  'http://127.0.0.1:8787/v1/stream?episode_id=YOUR_EPISODE_ID'
```

Watch: Settings → **播放代理** → `http://<host>:8787` + token. Empty URL = off (default).

## Signer

| Mode | Env | Notes |
| --- | --- | --- |
| Medusa V5 (default) | — | TV 0.16.14 Kotlin path (F21=312). MetaSec optional. |
| MetaSec / unidbg | `METASEC_HOME` (+ optional `METASEC_UNIDBG_URL`) | Local unidbg engine; private host holds `.so` + `license.b64`. |

## Deploy (SSH)

Operators deploy this folder to a private host over SSH. The Cursor coordinator
holds the deploy private key at `/home/ubuntu/.ssh/hongguo_proxy_ed25519`
(comment `hongguo-watch-proxy@cursor`). **Do not** commit private keys or ask
users for passwords. When host/`user@host` details are available, deploy from
the coordinator (or share that key path with a worker that already has it).

Suggested remote layout: clone/sync repo → `./gradlew :proxy:run` (or install a
systemd unit wrapping the same). Open only the proxy port; keep MetaSec assets
on that host alone.

## API

See store plan: `/cursor/stores/self/docs/playback-proxy-plan.md`.
