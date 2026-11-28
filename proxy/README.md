# 播放签名代理（设置 → 播放代理）

手表只请求 **流地址**（`GET /v1/stream`）。本进程负责
`device_register` → 签名 → `video_tab` / `multi_video_model`，返回 CDN HTTPS URL。
**不**代理视频字节——手表仍直连 CDN。

对应红果终端：**设置 → 播放代理**（无单独「API 代理」项）。空地址 = 关闭，走本机签名。

## 一键部署（Docker）

```bash
# 仓库根目录
./gradlew :proxy:installDist
cp proxy/.env.example proxy/.env   # 改 HONGGUO_PLAYBACK_PROXY_TOKEN
docker compose -f proxy/docker-compose.yml up -d --build
curl -fsS http://127.0.0.1:8787/health
```

手表填写：`http://<VPS或局域网IP>:8787` + 与 `.env` 相同的令牌。

## 本机直接跑

```bash
./gradlew :proxy:run
# 或
PORT=8787 BIND=0.0.0.0 HONGGUO_PLAYBACK_PROXY_TOKEN=devsecret ./gradlew :proxy:run
```

## 发布包（tar，OpenJDK 17+）

```bash
./gradlew :proxy:proxyDeployTar
# → proxy/build/distributions/hongguo-playback-proxy.tar.gz
```

```bash
install -d /opt/hongguo-playback-proxy && cd /opt/hongguo-playback-proxy
tar -xzf /path/to/hongguo-playback-proxy.tar.gz
cp /path/to/proxy/.env.example /opt/hongguo-playback-proxy/env   # 改令牌与上游
# 可选 MetaSec：
# bash proxy/scripts/metasec-extract.sh /path/to/phoenix.read.apk /opt/hongguo-playback-proxy/metasec
export $(grep -v '^#' /opt/hongguo-playback-proxy/env | xargs)
cd proxy && ./bin/proxy
# 或安装 proxy/systemd/hongguo-playback-proxy.service
```

冒烟：

```bash
BASE=http://127.0.0.1:8787 TOKEN=devsecret bash proxy/scripts/smoke-test.sh
# 有集 ID 时：EPISODE_ID=… 一并测 /v1/stream
```

## 环境变量

见 [`proxy/.env.example`](.env.example)。常用项：

| 变量 | 说明 |
| --- | --- |
| `PORT` / `BIND` | 监听（默认 `8787` / `127.0.0.1`；Docker/`0.0.0.0`） |
| `HONGGUO_PLAYBACK_PROXY_TOKEN` | Bearer 令牌；空 = 不鉴权 |
| `PROXY_REGISTER_HOST` | 设备注册上游 |
| `PROXY_PRELOAD_HOST` | preload 主机 |
| `PROXY_MODEL_HOSTS` | 逗号分隔 model 主机梯队 |
| `PROXY_VIDEO_TAB_HOST` | video_tab 主机 |
| `PROXY_STREAM_DEADLINE_SEC` | 单次取流截止（秒） |
| `METASEC_HOME` | 可选 MetaSec 资产根；无私有 `.so` 时用 Medusa V5 |

## 协议（与手表一致）

```
GET /health
→ {"ok":true,"signer":"…","upstream":{…}}

GET /v1/stream?episode_id={id}&quality=auto
Authorization: Bearer <令牌>   # 若代理配置了令牌

200 {
  "episode_id": "...",
  "expires_at": 1234567890,
  "renditions": [{ "quality": "480p", "codec": "h264", "url": "https://…" }]
}
```

错误：`401` 令牌、`429` 上游限流、`502` 上游空体。

## 签名器

| 模式 | 环境 | 说明 |
| --- | --- | --- |
| Medusa V5（默认） | — | 与手表同路径（F21=312） |
| MetaSec / unidbg | `METASEC_HOME` | 私有主机持有 `.so`；勿入库 |

## 安全

- 公网务必设令牌；勿匿名开放签名。
- 日志勿落完整 Cookie / Argus。
- MetaSec 衍生资产仅私有自用，**不可**随 GPL 手表再分发。

更多运维笔记见项目 Context：`docs/proxy-deploy.md`、`docs/metasec-proxy-deploy.md`。
