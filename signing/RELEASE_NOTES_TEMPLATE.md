# GitHub Release notes 模板

发版时把下方 Markdown 贴到 Release 描述，替换 `x.y.z` / versionCode / 变更列表。

```markdown
## 红果终端 vX.Y.Z

面向安卓手表 / 低配机的极简短剧客户端（Kotlin + View + Media3 / IJK）。

### 本版更新
- （列出用户可见变更）

### 安装注意
- **minSdk 24**（Android 7.0+）
- versionName **X.Y.Z** / versionCode **N**
- 按系统 ABI 选择一个安装包：
  - 不确定系统架构时用 ARM 通用包 `hongguo-watch-X.Y.Z-universal.apk`
  - 支持 arm64-v8a 的 Android 系统可用 `hongguo-watch-X.Y.Z-arm64-v8a.apk`
  - 支持 armeabi-v7a 的 Android 系统可用 `hongguo-watch-X.Y.Z-armeabi-v7a.apk`
- Wear OS 手表可通过 [无线 ADB](https://developer.android.com/training/wearables/get-started/debug-wifi) 连接后运行 `adb install -r <apk>`

### 签名（统一证书）
- 构建：优先 `./gradlew :app:assembleDebug`（现行 Releases）；若跑 `assembleRelease` 且未配置 `WATCH_RELEASE_*`，**同一把**仓库 `signing/debug.keystore`
- SHA-256：`33:B2:1E:34:D8:19:88:2B:C0:77:D9:0F:FF:82:5D:0A:59:F7:D7:C5:C2:FE:E4:2B:0F:DB:86:65:73:0D:17:60`
- **0.4.4 之前**的随机 debug 包：可能需**卸载后**再装本版（卸载会清除历史、收藏与设置）；**0.4.4+** 同证书可直接覆盖升级
- 现行测试签名私钥公开，签名相同不代表来源可信；仅从本仓库 Releases 下载。

### 清晰度
- 默认上限 **480p**
- 设置中可手动开启 **720p / 1080p**（手表发热 / 卡顿风险自负）

### 风险提示
- **非官方**客户端；片源接口与字段可能随时变更，使用风险自负
- 短剧内容版权归权利人；请遵守当地法律法规与平台条款
- 本仓库许可为 GPL-3.0
```
