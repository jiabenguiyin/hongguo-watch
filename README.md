# 红果短剧手表版 · hongguo-watch

[![Latest release](https://img.shields.io/github/v/release/jiabenguiyin/hongguo-watch?label=Release)](https://github.com/jiabenguiyin/hongguo-watch/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/jiabenguiyin/hongguo-watch/total?label=Downloads)](https://github.com/jiabenguiyin/hongguo-watch/releases)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84)](#设备要求)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

**为安卓手表、小屏安卓设备及低配机设计的非官方短剧、漫剧客户端。**

Lightweight short-drama client for Android watches and small-screen devices, with IJK/FFmpeg software decoding and Media3 playback.

[下载最新版 APK](https://github.com/jiabenguiyin/hongguo-watch/releases/latest) · [更新记录](https://github.com/jiabenguiyin/hongguo-watch/releases) · [问题反馈](https://github.com/jiabenguiyin/hongguo-watch/issues/new/choose)

> 本项目为独立开源项目，与红果短剧官方无隶属或授权关系。请遵守内容权利人的授权及平台条款。

## 下载与安装

从 [GitHub Releases 最新版本](https://github.com/jiabenguiyin/hongguo-watch/releases/latest) 下载对应 ABI 的 APK。两个安装包不需要同时安装。

| 安装包后缀 | 适用设备 |
| --- | --- |
| `arm64-v8a.apk` | 64 位 Android 系统（CD12 Max 已有实机播放反馈） |
| `armeabi-v7a.apk` | 32 位 ARM Android 系统 |

开启手表的未知来源安装权限，将 APK 传到设备安装，或使用 ADB：

```sh
adb shell getprop ro.product.cpu.abilist
adb install -r <APK 文件路径>
```

如显示签名冲突，请先确认是否为本仓库旧版本或不同签名的安装包。0.4.4 前的随机 debug 签名包可能需要卸载后再安装；**卸载会清除本地历史、收藏与设置**。0.4.4 起已发布同证书安装包通常可覆盖升级。也可在应用设置中检查更新。

**安全提示：** 当前测试发行包使用公开的 debug 签名私钥，证书相同不能证明 APK 来自维护者。只从本仓库官方 Releases 下载，不要安装第三方修改包。正式签名迁移见 [签名说明](signing/README.md)。

## 功能

| 模块 | 功能 |
| --- | --- |
| 内容发现 | 首页推荐、榜单、分类、搜索、滚动分页 |
| 追剧 | 分集选择、收藏、观看历史、断点续播 |
| 播放 | 自动下一集、条件满足时自动接续下一季 |
| 播放控制 | 亮度、音量、横向进度手势；0.75–2 倍速；防误触锁定 |
| 小屏适配 | 圆屏安全区域、大圆角方屏安全边距、小屏触控布局 |
| 横竖屏 | 旋转切换、等比例 FIT，避免拉伸变形 |
| 系统兼容 | Media3 / IJK 双内核、硬软解选择、内置解码自检 |
| 设置 | 仅 Wi-Fi、清晰度上限、清理历史与缓存、检查更新、诊断 |

## 设备要求

- Android 7.0（API 24）或以上；ARM32 / ARM64。
- 针对可侧载 APK 的安卓手表及小屏设备，非独立 Wear OS 专用应用；不支持所有封闭式手表系统。
- 自动播放内核：手表 / 脆弱 SoC 优先 IJK + FFmpeg；其他设备默认 Media3。
- 默认清晰度上限 480p，可手动选择 720p / 1080p；脆弱 SoC 且应用内存等级较低时会进一步限制有效清晰度。实际档位取决于可用片源及选流策略。
- 不是所有 HEVC/bytevc1 媒体或设备都保证兼容。

### 已知实机记录

| 设备 | 系统 | 屏幕 | 测试情况 |
| --- | --- | --- | --- |
| CD12 Max（CD_Watch_A / SPRD） | Android 9 | 408 × 502 | IJK 软件解码路径已有用户实机出画反馈；其他功能与机型仍需测试 |

欢迎提交更多机型的验证结果，见 [兼容性反馈指南](docs/compatibility.md)。

## 播放异常怎么办？

1. 更新到 [最新版本](https://github.com/jiabenguiyin/hongguo-watch/releases/latest)。
2. 设置中选择「兼容软解」，解码模式选择软件解码，并先尝试默认 480p。
3. 运行设置中的解码自检，分别检查本地 H.264 / HEVC 测试片。
4. 如仍有声无画、卡顿或失败，记录机型、系统版本、内核、画质与首帧情况，按 [问题模板](https://github.com/jiabenguiyin/hongguo-watch/issues/new/choose) 反馈。

诊断可能包含设备信息与播放日志；当前版本在部分播放失败时也会尝试上传到公共粘贴服务。详细情况见 [诊断与隐私说明](docs/compatibility.md#诊断与隐私)。不要在公开 Issue 中贴 Cookie、令牌、播放地址或其他个人信息。

## 开发与构建

| 组件 | 技术 |
| --- | --- |
| UI | Kotlin、原生 View / ViewBinding、RecyclerView |
| 播放 | Media3 ExoPlayer、IJKPlayer / FFmpeg |
| 网络与图片 | OkHttp、Coil |
| 本地存储 | Room、DataStore |
| 构建 | Gradle 8.7、AGP 8.5.2、JDK 17、Android SDK 34 |

```sh
git clone https://github.com/jiabenguiyin/hongguo-watch.git
cd hongguo-watch
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 使用 `gradlew.bat`。本地需安装 SDK platform 34、build-tools 34，并配置 Android SDK 路径（如 `ANDROID_HOME` 或 `local.properties`）。

Debug 产物位于 `app/build/outputs/apk/debug/`，Release 产物位于 `app/build/outputs/apk/release/`。当前 Release 发布流程见 [签名说明](signing/README.md)，开发约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 实现边界与开源归因

- 不提供后台连播或保活；切回表盘可能被系统回收。
- 跨季连播仅在同标题、连续季号、唯一可播候选成立时自动进行；只预取相关季元数据，不预取下一季媒体。若无法可靠确定下一季则停止，不跨过待上线季。
- 内容接口可能变化；本项目不提供内容授权，不保证片源永久可用。
- 第三方来源见 [hongguoTV 归因](third_party/hongguoTV-NOTICE.md)、[IJKPlayer 归因](third_party/IJKPLAYER-NOTICE.md) 与 [其他上游说明](third_party/SIGNING_V5.md)。参考项目：[hongguoTV](https://github.com/N3urda/hongguoTV)、[hongguo-drama](https://github.com/jackotom/hongguo-drama)、[Media3](https://github.com/androidx/media)。

本项目以 [GPL-3.0](LICENSE) 发布；第三方组件保留各自许可。内容版权及商标归相应权利人所有。

---

**hongguo-watch** · Android watch / 安卓手表 / 短剧手表版 / 漫剧 / 小屏播放器
