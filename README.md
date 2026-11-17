# 红果短剧手表版（hongguo-watch）

面向安卓手表 / 华强北低配机的极简短剧客户端：**Kotlin + View + Media3**，`minSdk 24`。

非官方应用。片源对照开源仓 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV)（`Signer` / `DeviceRegister` / `MobilePlayback` / `MediaCrypto` / `RelatedWorks`）与 [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama) 的 **App 族**通路（`*.fqnovel.com`）。许可：**GPL-3.0**。

## 功能

- 首页：演示片源（可关）+ App 搜索列表（封面 / 标题）
- 详情：App `multi_video_detail/preload` 分集；**收藏**；下方**相关季 / 同系列**
- 播放：Media3 ExoPlayer（硬解优先）；加密档走开源 `RemoteVideo`/`MediaCrypto`
  - **锁定**防误触；**左侧竖直亮度 / 右侧竖直音量 / 横向进度**
  - 暂停/播放、上/下集为**图标按钮**
  - 本季末集结束 → **无缝连播同系列下一季第 1 集**（见下）
- **历史续播**：Room 落盘剧 + 集 + 进度百分比；近片尾续播按规则归零；暂停/退出即时写入
- **收藏夹** Tab
- 设置：仅 Wi‑Fi、清晰度上限（**默认 480p**，可选手动 **720p / 1080p**）、演示源开关、清历史 / 清缓存

## 跨季连播能力边界

对照 hongguoTV `RelatedWorks` / `ContentRepository.related` 与公开系列文档：

| 能力 | 本仓行为 |
|------|----------|
| 相关列表 | `GET .../reading/bookapi/plan/v:version/`，`scene=10`，`from=detail_page_more_related`；解析 `video_data` 的 `series_id` / `season_index` / `content_type`(1004 可播 / 1007 待上线) |
| 下一季挑选 | 同标题 stem + **连续**季号 + **唯一**可播候选；待上线 / 禁播 / 非唯一 → **不自动跳** |
| 预取时机 | 播到本季**最后一集**且进度 ≥ 半程：只拉相关**元数据**，不预取下一季媒体流 |
| 结束跳转 | `Player.STATE_ENDED`：非末集 → 下一集；末集 → `detail(下一季)` → 第 1 集 0 秒；失败停住并提示 |
| 降级 | plan 失败时可用标题搜索作相关列表展示；自动连播仍要求唯一命中下一季，否则不强猜 |
| 不做 | 不跳过待上线季；不拉官网源；不后台连播 / 拉活 |

更细的开源对照见 Project 文档 `docs/season-handoff-notes.md`。

## UI（手表小屏）

- **播放**：沉浸式；顶栏返回+标题+锁；底栏进度 + 清晰度 / 上集 / 播放 / 下集 / 选集（图标）；收起后细进度；锁定后仅解锁钮
- **首页 / 搜索 / 历史 / 收藏**：四 Tab；历史显示「第 N 集 · 进度%」
- **详情**：收藏钮；续播带进度；选集网格；下方相关季列表
- **设置**：清晰度三档大芯片；演示源；清理操作

## 片源说明

| 路径 | 状态 |
|------|------|
| App 族搜索 `reading/bookapi/search/tab/v` + 开源 Signer | **默认** |
| App 分集 `novel/player/multi_video_detail/preload/v1` | **默认** |
| App 播放模型 `novel/player/multi_video_model/player/v1`（对照 hongguoTV `MobilePlayback`） | **默认** |
| App 相关作品 `reading/bookapi/plan/v:version/`（`detail_page_more_related`） | **默认** |
| 演示流（Big Buck Bunny + 演示第二季） | 设置可开关，用于验证播放器与跨季 |
| 本地 Node 桥 / 官网源入口 | **不**使用 |

上游归因见 [`third_party/hongguoTV-NOTICE.md`](third_party/hongguoTV-NOTICE.md)、[`third_party/SIGNING_V5.md`](third_party/SIGNING_V5.md)。

## 清晰度

开源 App 播放模型可解析多档清晰度。手表**默认上限 480p**；设置可手动提到 **720p / 1080p**。**高配发热 / 卡顿风险自负**，低配机请保持 480p。

## 打 APK

环境：JDK 17+、Android SDK（platform 34、build-tools 34）。

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease
```

产物（按 ABI 分包）：

- `app/build/outputs/apk/release/app-armeabi-v7a-release.apk`
- `app/build/outputs/apk/release/app-arm64-v8a-release.apk`

未设置 `WATCH_RELEASE_STORE_*` 时 Release 使用 **debug 密钥**签名。正式分发请自备 keystore。

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
6. **跨季**：依赖上游 plan 相关作品字段；无可靠下一季时停在本集结束，不盲跳。
7. **网络安全**：默认仅 HTTPS。
8. **不做**：不写破解鉴权 / 盗链 / DRM 绕过教程；只移植开源仓已公开的签名与请求逻辑。

## 技术栈

- minSdk 24 / targetSdk 34
- ViewBinding + RecyclerView（不用 Compose / Flutter）
- Media3 ExoPlayer + HLS 依赖
- OkHttp、Coil、Room、DataStore、BouncyCastle（SM3）
- ABI split：`armeabi-v7a`、`arm64-v8a`

## 参考

- 方案与接口手册见本 Project 文档（`watch-hongguo-plan.md` / `third-party-api.md` / `season-handoff-notes.md`）
- Media3：[androidx/media](https://github.com/androidx/media)
