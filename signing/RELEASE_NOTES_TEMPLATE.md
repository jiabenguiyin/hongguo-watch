# GitHub Release notes 模板（v0.5.1 风格）

发版时把下方 Markdown 贴到 Release 描述，替换 `X.Y.Z` / versionCode / 变更列表。
**标题必须是：** `红果终端 vX.Y.Z`（不要用英文技术副标题）。

```markdown
## 更新内容
- （中文短句，用户可见变更）
- （不要写长篇英文技术说明）

## 安装包
- **ARM64：** `hongguo-watch-X.Y.Z-arm64-v8a.apk`
- **ARM32：** `hongguo-watch-X.Y.Z-armeabi-v7a.apk`

Android 7.0+ · versionCode N · 可使用同签名的旧版本覆盖安装
```

说明：构建用 `./gradlew :app:assembleDebug`（仓库 `signing/debug.keystore`）。不确定架构时另附 universal 包，可不在正文列出。参考历史 Release：`v0.5.1`。
