<div align="center">

# 红果终端

**hongguo-watch · 为小屏而生的安卓短剧客户端**

[![Release](https://img.shields.io/github/v/release/jiabenguiyin/hongguo-watch?label=Release&color=E85D4C)](https://github.com/jiabenguiyin/hongguo-watch/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/jiabenguiyin/hongguo-watch/total?label=Downloads)](https://github.com/jiabenguiyin/hongguo-watch/releases)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84)](#设备要求)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

适配安卓手表、小屏 Android 设备及低配设备的非官方短剧、漫剧客户端。  
Lightweight short-drama player for Android watches and small-screen devices.

**[下载最新版 APK](https://github.com/jiabenguiyin/hongguo-watch/releases/latest)** · [安装指南](docs/install.md) · [更新记录](https://github.com/jiabenguiyin/hongguo-watch/releases) · [问题反馈](https://github.com/jiabenguiyin/hongguo-watch/issues/new/choose)

</div>

---

## 功能亮点

| 小屏追剧 | 播放体验 |
| --- | --- |
| 推荐、榜单、分类、搜索 | Media3 / IJK + FFmpeg 双内核 |
| 分集选择、自动下一集 | 软解 / 硬解选择、解码自检 |
| 收藏、观看历史、断点续播 | 等比显示、横竖屏、防误触锁定 |
| 条件满足时自动接续下一季 | 0.75–2 倍速、进度、亮度与音量手势 |
| 圆屏与大圆角方屏适配 | 应用内检查更新、诊断反馈 |

## 下载与安装

打开 [最新正式发布页](https://github.com/jiabenguiyin/hongguo-watch/releases/latest)，在 Assets 中选择与你设备系统 ABI 对应的安装包。

国内直连 GitHub Release 可能失败或极慢。可给原始 Assets 链接加社区前缀后再下载，例如：

`https://ghproxy.net/` + `https://github.com/jiabenguiyin/hongguo-watch/releases/download/v…/hongguo-watch-….apk`

备用前缀：`https://ghfast.top/`。具体拼法与自建同步见 [安装指南 · 镜像](docs/install.md#下载与升级)。应用内更新会优先镜像、失败再回落 GitHub。

| 安装包 | 适用系统 |
| --- | --- |
| `arm64-v8a.apk` | 64 位 ARM Android |
| `armeabi-v7a.apk` | 32 位 ARM Android |

开启未知来源安装权限后安装 APK；两种架构只需选择其一。支持 ADB 安装与应用内检查更新。

0.4.4 起的同证书安装包通常可覆盖升级。遇到签名冲突先查看 [安装指南](docs/install.md)，卸载会清除本地数据。当前测试发行签名的私钥公开，请只从本仓库 Releases 下载；详见 [安全说明](SECURITY.md)。

## 设备要求

- Android 7.0（API 24）或以上，支持 ARM32 / ARM64。
- 面向可以侧载 APK 的安卓手表及小屏设备；非独立 Wear OS 专用应用。
- 自动内核：手表 / 脆弱 SoC 优先 IJK + FFmpeg，其他设备默认 Media3。
- 默认清晰度请求上限 480p；支持手动选择 720p / 1080p。低内存脆弱设备可能限制有效请求上限，实际画质取决于片源。
- 不保证全部机型、封闭式手表系统或视频编码均可兼容。

## 帮助与反馈

有声无画、卡顿、封面异常、安装失败？先查看 [常见问题](docs/install.md#常见问题)，再按 [兼容性测试指南](docs/compatibility.md) 提交设备与播放信息。

当前版本部分播放失败时会尝试向公共粘贴服务上传诊断。分享诊断前请检查是否包含私人信息，详情见 [诊断与隐私](docs/compatibility.md#诊断与隐私)。

## 开发

[![Android CI](https://github.com/jiabenguiyin/hongguo-watch/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/jiabenguiyin/hongguo-watch/actions/workflows/android-ci.yml)

Kotlin · ViewBinding · RecyclerView · Media3 · IJKPlayer / FFmpeg · OkHttp · Coil · Room · DataStore

```sh
git clone https://github.com/jiabenguiyin/hongguo-watch.git
cd hongguo-watch
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

环境：JDK 17、Android SDK 34、Gradle 8.7（Wrapper）。Windows 使用 `gradlew.bat`。

[参与开发](CONTRIBUTING.md) · [签名与发布](signing/README.md) · [CI 构建记录](https://github.com/jiabenguiyin/hongguo-watch/actions)

## 开源与声明

本项目为独立开源项目，与红果短剧官方无隶属或授权关系。内容版权及商标归相应权利人所有，请遵守内容授权与平台条款。上游接口可能变化，不保证片源永久可用。

不提供后台连播或保活；跨季连播仅在同标题、连续季号、唯一可播候选成立时执行，只预取相关季元数据而非视频流。

本仓采用 [GPL-3.0](LICENSE) 许可，第三方组件保留各自许可。来源及归因：[hongguoTV](third_party/hongguoTV-NOTICE.md)、[IJKPlayer](third_party/IJKPLAYER-NOTICE.md)、[其他上游说明](third_party/SIGNING_V5.md)。另参考 [hongguo-drama](https://github.com/jackotom/hongguo-drama) 和 [Media3](https://github.com/androidx/media)。
