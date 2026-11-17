# 红果短剧手表版（hongguo-watch）

面向安卓手表 / 华强北低配机的极简短剧客户端：**Kotlin + View + Media3**，`minSdk 24`。

非官方应用。默认片源对照开源仓 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV)（`Signer` / `DeviceRegister` / `MobilePlayback` / `MediaCrypto`）与 [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama) 的 **App 族**通路（`*.fqnovel.com`），**不**默认走官网 `hongguoduanju.com` 搜索/SSR。许可：**GPL-3.0**。

## MVP 功能

- 首页：演示片源（可关）+ App 搜索列表（封面 / 标题）
- 详情：App `multi_video_detail/preload` 分集
- 播放：Media3 ExoPlayer（硬解优先，无 FFmpeg）；加密档走开源 `RemoteVideo`/`MediaCrypto`；上一集 / 下一集；失败重试
- 历史：Room 落盘剧 + 集 + 进度，点进续播
- 设置：仅 Wi‑Fi、清晰度上限（**默认 480p**，可选手动 **720p / 1080p**）、演示源开关、已弃用官网源开关、清历史 / 清缓存

## 片源说明

| 路径 | 状态 |
|------|------|
| App 族搜索 `reading/bookapi/search/tab/v` + 开源 Signer | **默认** |
| App 分集 `novel/player/multi_video_detail/preload/v1` | **默认** |
| App 播放模型 `novel/player/multi_video_model/player/v1`（v5，对照 hongguoTV `MobilePlayback`） | **默认** |
| 演示流（Big Buck Bunny 等） | 设置可开关，用于验证播放器 |
| 官网 `hongguoduanju.com` 搜索 / SSR | **已弃用**；设置内默认关闭，不推荐 |
| 本地 Node 桥 | **不**作为手表默认路径 |

上游归因与协议说明见 [`third_party/hongguoTV-NOTICE.md`](third_party/hongguoTV-NOTICE.md)、[`third_party/SIGNING_V5.md`](third_party/SIGNING_V5.md)。

## 清晰度

开源 App 播放模型可解析多档清晰度（含 **360 / 480 / 720 / 1080**，标签还可识别到 1440 / 2160）。手表**默认上限 480p**；设置可手动提到 **720p / 1080p**（参考多数手表用户常用档）。**高配发热 / 卡顿风险自负**，低配机请保持 480p。

## 打 APK

环境：JDK 17+、Android SDK（platform 34、build-tools 34）。

```bash
# 可选：写入 SDK 路径
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleRelease
```

产物（按 ABI 分包，非 universal）：

- `app/build/outputs/apk/release/app-armeabi-v7a-release.apk`
- `app/build/outputs/apk/release/app-arm64-v8a-release.apk`

未设置 `WATCH_RELEASE_STORE_*` 环境变量时，Release 使用 **debug 密钥**签名（方便侧载验证）。正式分发请自备 keystore。

Debug：

```bash
./gradlew :app:assembleDebug
```

侧载：手表开启「未知来源」后 `adb install -r <apk>`。大量低价表优先装 **armeabi-v7a**。

## 风险与合规

1. **非官方 / 未授权**：接口与字段随时变更；可能违反平台条款，风险自负。
2. **版权**：短剧内容版权归权利人；本仓库只提供客户端壳与开源仓已公开协议的对照移植。
3. **GPL-3.0**：跟进 Signer / MediaCrypto 等核心代码并分发 → 本仓保持 GPL，须提供对应源码。
4. **高清**：手动 720p/1080p 可能导致手表发热或卡顿，风险自负。
5. **低配机限制**：不做后台连播 / 拉活；切表盘可能被杀，进度依赖本地即时写入。
6. **网络安全**：默认仅 HTTPS；部分 ROM 证书异常需自行诊断，勿默认关闭校验。
7. **不做**：不写破解鉴权 / 盗链 / DRM 绕过教程；只移植开源仓已公开的签名与请求逻辑。

## 技术栈

- minSdk 24 / targetSdk 34
- ViewBinding + RecyclerView（不用 Compose / Flutter）
- Media3 ExoPlayer + HLS 依赖（便于后续流类型）
- OkHttp、Coil、Room、DataStore、BouncyCastle（SM3）
- ABI split：`armeabi-v7a`、`arm64-v8a`

## 参考

- 方案与接口手册见本 Project 文档（`watch-hongguo-plan.md` / `third-party-api.md` / `source-repo-choice.md`）
- Media3：[androidx/media](https://github.com/androidx/media)
