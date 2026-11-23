# 本次同步来源

2026-11-23：**唯一官方对照**为用户百度 CDN 包 `com.phoenix.read` **7.4.5.32** /
versionCode **74532**（文件 MD5=`31c6285e…`；URL `md5hash=37a427b7…` 不匹配已记录）。
更早 7.2.x / 7.1.x / 7.3.x 逆向结论一律作废。端到端播放序列对齐：
`device_register` → 登记 Cookie → 签名头 → `GET video_tab/multi_video_model` →
`POST multi_video_model` → 取流。官方 MetaSec/`libmetasec_ml.so` **不可再分发**
（法律 + JNI/包签名门槛，见 `MetaSecPlayback`）；手表用 GPL PlayerSigner 近似。
身份/UA 对齐 **74532**；登记 Cookie 会附在模型请求上（不含账号 session）。

2026-11-23：官方播放「真签名」对齐
[jycao2/hongguoTV kotlin-v0.33.1-build.62](https://github.com/jycao2/hongguoTV/releases/tag/kotlin-v0.33.1-build.62)
（提交 `91de1b673d6120edb37dfc65f3b116de7d44e8d5`，GPL-3.0）。该发行 APK **不含** MSSDK /
`libhongguo_signer.so`。手表端移植其 `Argus.kt` 中的 `PlayerSigner`（fqapp argus/ladon），
并把取流改为优先 `GET video_tab` + `POST /novel/player/multi_video_model/v1`（无 `/player/`、
无 `dr_scene`、`version_code=74532`），用 PlayerSigner 替换会被静默丢弃的 v5 helios/medusa 占位头。
未纳入其 UI、冷却策略以外的业务逻辑，也未复制发行 APK 二进制。

2026-11-20：手表端的同档 CDN 备用与模型二次取源，参考
[N3urda/hongguoTV](https://github.com/N3urda/hongguoTV/blob/478f00683f086f1e5d135b3bcbb443e3cbc8b252/kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt#L1292)、
[jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama/blob/6449bb1e0329dbe567ffa2db93e73d654bf36aba/src/native/app-source.js)
和 [kuoluoss/zhenguojian](https://github.com/kuoluoss/zhenguojian/blob/0cdce6ad19f2e4496650b325c4de3bf8522bda1e/native/core/provider_hongguo_fplay.go)
的播放模型和回包结构。手表端保留同一清晰度的备用媒体地址。仅当本集模型没有内联清晰度列表时，
才在原线路时限内跟随一次模型明确给出的官方 HTTPS 备用地址，并核对返回的视频身份。
不构造 `fplay` 地址或参数，备用地址不跟随重定向。

2026-11-23：新增 360p 公开备用流，参考独立发行仓库
[waligoraamodio288-rgb/hongguo-desktop-releases](https://github.com/waligoraamodio288-rgb/hongguo-desktop-releases)
中 TV v0.5.12 APK 的可观察行为；该仓库不含客户端源码。本项目独立实现请求：
读取 `hongguoduanju.com` 的公开剧集页 JSON-LD，精确匹配剧集 ID、分集 ID 和集数，再对固定站点的
`/video/public/{itemId}/360p.mp4` 做 32 字节 Range/MP4 头校验。只在官方模型无可播源或可恢复取流
失败后尝试；没有从该 APK 复制代码、资源、签名或身份数据。此公开站点是独立第三方，片源可能缺失或变化。

2026-11-11：TV 功能与测试同步自 tsukiyo-zero/hongguoTV，提交 `49532eb2110dcbf66814ca250d43f222e7193895`（GPL-3.0）。保留原作者声明；本分支适配原包名、更新渠道及品牌。

# 原生版第三方声明

本地播放兼容修改参考 FanchangWang 的 GPL-3.0 项目贡献：
https://github.com/N3urda/hongguoTV/pull/22 。播放请求使用独立设备标识和无签名请求，复用本项目原有的响应大小限制、请求取消及媒体解析逻辑。

2026-10-21 账号测试功能参考 charlie901208/HGTV 的 `feat/phone-login` 分支（GPL-3.0-only）：
https://github.com/charlie901208/HGTV/tree/feat/phone-login 。`DeviceRegister.kt`、`PhoneLogin.kt` 由该分支移植并修改；账号读写的路径和字段参照其 `Account.kt`、`ContentRepository.kt`。本地实现补充有界响应读取、登录态加密存储、错误验证码停止重试与云端精确进度字段保留；账号界面和同步调度为本地实现。未合并该分支的广告、遥测、界面重构或其他功能。原作者著作权保留。

`core` 中内容请求签名、Spade URL / key 处理、MP4 CENC 处理是以下 GPL-3.0 源码的 Kotlin 移植：

- drpys, `spider/js/红果果[短].js`
- https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
- 本仓库对照源：`../server/vendor/hongguo.cjs`，取回于 2026-09-21。
- 本版改动：完整 Kotlin 实现；签名只选择已验证的 hash branch 0；加密媒体通过 Media3 DataSource 直接读取；增加样本边界检查、子样本处理和 AVC 原始编码恢复。

播放设备注册与 x-argus / x-ladon / Simon 签名、双模型选流是以下 GPL-3.0 源码的 Kotlin 移植（用于恢复被上游拒绝的固定设备播放路径）：

- fqapp, `rust/src/sign/{argus,simon,ladon,gorgon,protobuf}.rs`、`rust/src/device/{register,crypto}.rs`、`rust/src/endpoints/video.rs`
- https://github.com/ch6vip/fqapp
- 测试使用其仓库自带的独立参考实现黄金向量（`rust/testdata/vectors.json`、`device_vectors.json`）。

`kotlin-tv/` 原生应用源码和派生实现以 GPL-3.0-only 发布，许可证全文见 `LICENSE`。发布 APK 时同时提供本分支对应源码、Gradle 包装器和构建说明。APK 不包含 Node、React Native、JavaScript 引擎或该 JavaScript 源文件。`tools/signing-oracle.cjs` 仅为开发期生成对照测试数据的工具，不参与应用构建或运行。

其他依赖：

- ZXing core（本机生成手机输入与更新二维码）：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- Bouncy Castle SM3：https://github.com/bcgit/bc-java — MIT
- jsoup（仅解析官网 HTML，不执行网页脚本）：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0

应用图标和 TV banner 沿用本仓库素材。这里的应用不是内容平台官方客户端。


Mac/TV 共用评论签名的第三方许可：
# Mac 第三方声明

本集评论的协议字段参考 yangbin1322/hongguo-player 公开发行包中的接口线索（https://github.com/yangbin1322/hongguo-player/releases/tag/v1.2.11），以匿名请求核实数据来源和游标推进后独立实现；未纳入该客户端的代码、资源或二进制。

弹幕评论签名的通用 Argus / Simon 结构参考并改写自 tr4cex/TikTok-Encryption（MIT）：
https://github.com/tr4cex/TikTok-Encryption 。Gorgon、Ladon 基于本仓库已有 GPL core Signer 改写。红果请求字段为匿名读取验证的协议参数。未纳入许可证未确认的 guoapp 实现。

MIT License

Copyright (c) 2024 TikTok Encryption Project

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

EDUCATIONAL USE DISCLAIMER:
This software is intended for educational and research purposes only.
Users are responsible for ensuring their use complies with applicable laws
and terms of service. The authors disclaim any responsibility for misuse
of this software.

评论表情图使用 Microsoft Fluent Emoji（MIT），通过 GPL HGTV 参考项目取得；许可全文随 APK assets/comment_emoji/LICENSE.txt 分发。为通用替代表情，非红果官方贴图。

## 2026-11-11 单集播放协议修复

`PlayerSignature.kt` 基于本仓库 `CommentSignature.kt` 的 Argus/Simon/AES 和 Ladon 实现，按单集播放协议补齐设备、版本、请求体摘要和 Gorgon 参数。协议对照来源：qq294716498/HongguoTV-Phone-TV-NoAds，固定提交 `8eafa3908e9191c0a5ae186d5beff11e3a971f3c` 的 `native/core/provider_hongguo_player_sign.go`、`provider_hongguo_registered_media.go` 和 `provider_hongguo_app.go`：
https://github.com/qq294716498/HongguoTV-Phone-TV-NoAds/tree/8eafa3908e9191c0a5ae186d5beff11e3a971f3c/native/core 。保留原 MIT/GPL 声明与许可；未引入该项目的应用界面或账号数据。

`core/src/test/resources/player-signing.json` 使用该固定 Go 实现、固定时间和 nonce 独立生成，用于逐字节校验，不含真实设备身份、账号或媒体地址。正式播放优先使用 `/novel/player/video_model/v1/` 和完整 Gorgon/Argus/Ladon，旧批量签名仅保留为有界兼容回退；前述历史“无签名播放请求”说明不再描述当前主路径。
