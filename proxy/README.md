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

### Dist (Java 17 bytecode)

```bash
./gradlew :proxy:proxyDeployTar
# → proxy/build/distributions/hongguo-playback-proxy.tar.gz  (top-level proxy/)
# → also :proxy:installDist → proxy/build/install/proxy/{bin,lib}
```

On a host with **OpenJDK 17+**:

```bash
tar -xzf hongguo-playback-proxy.tar.gz
cd proxy
PORT=8787 BIND=0.0.0.0 HONGGUO_PLAYBACK_PROXY_TOKEN=secret ./bin/proxy
```

Store copy (agent builds): `/cursor/stores/self/media/hongguo-playback-proxy.tar.gz`.

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
| PlayerSigner (default) | — | GPL Argus/Ladon reused from watch upstream. **May empty-200**. |
| MetaSec / unidbg | `METASEC_UNIDBG_URL` | Scaffold only — see `MetaSecBridge.kt` TODO. Private host holds `.so`. |

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
