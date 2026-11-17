# 红果短剧手表版（hongguo-watch）

面向安卓手表 / 华强北低配机的极简短剧客户端：**Kotlin + View + Media3**，`minSdk 24`。

非官方应用。片源对照开源仓 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) 与 [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama) 的**官网路径**（`hongguoduanju.com` 搜索 / 详情 / 播放页）。许可：**GPL-3.0**。

## MVP 功能

- 首页：演示片源 + 官网搜索列表（封面 / 标题）
- 详情：分集网格选集
- 播放：Media3 ExoPlayer（硬解优先，无 FFmpeg）；上一集 / 下一集；失败重试
- 历史：Room 落盘剧 + 集 + 进度，点进续播
- 设置：仅 Wi‑Fi、清晰度上限（≤480p）、清历史 / 清缓存

## 片源说明

| 路径 | 状态 |
|------|------|
| 官网搜索 `GET /api/search/result` | MVP 已接 |
| 官网详情 / 播放页 SSR（`vid_list`、`VideoObject.contentUrl`） | MVP 已接 |
| 官网公开预览档 `/video/public/{vid}/360p.mp4` | 可播，多为短预览 |
| App 族 `*.fqnovel.com` + 开源 Signer | **未移植**（下一阶段对照 hongguoTV `Signer` / `PlaybackModels`） |
| 演示流（Big Buck Bunny 等） | 始终可用，用于验证播放器 |

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
2. **版权**：短剧内容版权归权利人；本仓库只提供客户端壳与公开协议对照实现。
3. **预览 ≠ 正片**：官网当前常见 `protectedPlayback`，公开 MP4 多为预览；完整正片需评估跟进开源仓 App 签名路径及其 GPL 义务。
4. **低配机限制**：不做后台连播 / 拉活；切表盘可能被杀，进度依赖本地即时写入。
5. **网络安全**：默认仅 HTTPS；部分 ROM 证书异常需自行诊断，勿默认关闭校验。

## 技术栈

- minSdk 24 / targetSdk 34
- ViewBinding + RecyclerView（不用 Compose / Flutter）
- Media3 ExoPlayer + HLS 依赖（便于后续流类型）
- OkHttp、Coil（小图缓存上限）、Room、DataStore
- ABI split：`armeabi-v7a`、`arm64-v8a`

## 参考

- 方案与接口手册见本 Project 文档（`watch-hongguo-plan.md` / `third-party-api.md`）
- Media3：[androidx/media](https://github.com/androidx/media)
