# 红果终端安卓手表版下载、安装与常见问题

## 红果短剧手表版怎么下载？

从 [GitHub Releases 最新版](https://github.com/jiabenguiyin/hongguo-watch/releases/latest) 下载 APK。网页中的 Source code 压缩包不是安装包。红果终端为独立第三方客户端，不是红果短剧官方手机版。

| APK 后缀 | 适用系统 |
| --- | --- |
| `universal.apk` | 同时包含 64 位和 32 位 ARM 库，不确定手表支持哪种 ABI 时选这个 |
| `arm64-v8a.apk` | 系统报告支持 `arm64-v8a`，体积较小 |
| `armeabi-v7a.apk` | 系统报告支持 `armeabi-v7a`，体积较小 |

只需安装一个 APK。以 Android 系统报告的 ABI 为准。三个包都保留兼容软解所需的 IJK/FFmpeg。

三星 Galaxy Watch5（SM-R9150）无法安装时，可以先用通用包。使用 ADB 时，先按 [Wear OS 无线调试指南](https://developer.android.com/training/wearables/get-started/debug-wifi) 在手表上配对并连接，再确认目标是手表、查询 ABI 并安装。

```sh
adb devices -l
adb -s <手表设备编号> shell getprop ro.product.cpu.abilist
adb -s <手表设备编号> install -r <APK文件路径>
```

系统报告 `arm64-v8a` 可以用 ARM64，只有 `armeabi-v7a` 就用 ARM32。通用包仍安装失败时，请记录 `adb install` 返回的 `INSTALL_FAILED_…` 错误码。

有系统安装器的设备也可直接打开 APK，根据提示为对应的安装来源开启权限。

0.4.4 起已发布的同证书版本通常可覆盖升级。旧随机签名包或第三方不同签名包可能无法覆盖；**卸载会清除本地历史、收藏和设置**，请勿遇到安装问题就立即卸载。

当前发行包使用公开的 debug 私钥，不能仅凭证书确认来源。只从本仓库 Releases 下载，详见 [签名说明](../signing/README.md) 和 [安全说明](../SECURITY.md)。

## 常见问题

| 现象 | 建议 |
| --- | --- |
| GitHub 下载失败 | 当前发布渠道仍为 GitHub Releases；请勿从来源不明的第三方镜像下载 |
| 下载后无法安装 | 检查 Android 7.0+、APK 是否完整、系统 ABI 与安装权限 |
| 提示签名不一致 | 核实原包来源和签名；保留旧数据，确认风险后再决定是否卸载 |
| 有声音但没有画面 | 设置中选择兼容软解与软件解码，先试默认 480p；若仍失败，按兼容性指南反馈 |
| 播放卡顿或发热 | 降低画质、恢复正常倍速、对比硬解与软解；不同机型结果不同 |
| 设置 720p 后仍是低清晰度 | 实际档位依赖片源；脆弱 SoC 且应用内存等级不超过 192MB 时，当前代码会把有效请求上限限制为 480p |
| 圆屏或大圆角屏显示不全 | 检查圆屏适配设置；记录屏幕分辨率并反馈 |
| 退后台后播放停止 | 当前不提供后台连播或保活，重新进入后尝试历史续播 |
| 某部短剧无法加载 | 上游内容接口、可用编码或片源可能变化；不要仅凭单部内容失败判定整台设备不兼容 |

仍有问题请参阅 [实机兼容性与诊断](compatibility.md)，然后提交 [Issue](https://github.com/jiabenguiyin/hongguo-watch/issues/new/choose)。请勿直接公开未脱敏的日志。

## 诊断上传

诊断只在用户点击「设置 → 上传诊断日志」时发送。上传成功后可以复制链接。默认使用第三方粘贴服务，详情见 [诊断与隐私](compatibility.md#诊断与隐私)。
