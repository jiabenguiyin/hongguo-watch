# APK 下载镜像（国内可达）

GitHub Releases 资源在国内常需 VPN。红果终端采用轻量方案：**应用内优先镜像、失败回落 GitHub**，并在 README / 安装指南中提供可复制的镜像拼法。不绑定付费 CDN，不引入密钥。

## 结论（选型）

| 方案 | 结论 |
| --- | --- |
| 1. 应用内更新：镜像优先 + GitHub 回落 | **采用**。默认社区前缀代理；构建时可改 |
| 2. README / install 文档镜像链接 | **采用**。人工下载与应用内互补 |
| 3. 同步 Release APK 到自有可达主机 | **可选**。仅当你已有对象存储 / 静态站；不代开付费账号 |
| jsDelivr `cdn.jsdelivr.net/gh/...` | **不适合** Release Assets（面向仓库源文件，不是 `releases/download`） |
| 旧域名 `ghproxy.com` / `mirror.ghproxy.com` | **勿写死**。社区反馈不稳定或不可达；默认改用仍常见的前缀站 |

## 应用内行为（0.4.10+）

1. 「检查更新」仍读 `api.github.com`（与下载 CDN 路径不同）。
2. 选定 ABI 对应 APK 后，`UpdateApkInstaller` 按 `UpdateChecker.candidateDownloadUrls` 依次尝试：
   - 构建时注入的镜像基址（`BuildConfig.APK_MIRROR_BASES`）
   - 最后回落 GitHub `browser_download_url`
3. 单个镜像失败（超时 / 非 2xx）自动试下一个；用户取消立即停止。

## 配置镜像基址

环境变量 **`HG_APK_MIRROR_BASES`**（逗号分隔）在编译时写入 `BuildConfig.APK_MIRROR_BASES`。

未设置时默认：

```text
https://ghproxy.net/,https://ghfast.top/
```

清空镜像（仅 GitHub）：

```sh
export HG_APK_MIRROR_BASES=
./gradlew :app:assembleDebug
```

### 条目约定

| 写法 | 含义 | 示例结果 |
| --- | --- | --- |
| `https://ghproxy.net/` | 前缀代理：`base + 完整 GitHub URL` | `https://ghproxy.net/https://github.com/.../x.apk` |
| `file:https://cdn.example.com/apk/` | 按文件名：`base + apkName` | `https://cdn.example.com/apk/hongguo-watch-….apk` |
| `…/{url}` | 占位符替换完整 URL | 自定义反代路径 |
| `…/{file}` | 占位符替换 APK 文件名 | 自定义对象路径 |

自建示例（你已有静态托管，并把各 Release APK 同步到同一目录）：

```sh
export HG_APK_MIRROR_BASES='file:https://cdn.example.com/hongguo-watch/,https://ghproxy.net/'
./gradlew :app:assembleRelease
```

同步可用 Release 工作流里多一步 `curl`/`rclone` 上传 Assets；本仓库不内置付费 CDN 账号。

## 浏览器手动下载

1. 打开 [最新 Release](https://github.com/jiabenguiyin/hongguo-watch/releases/latest)，复制 Assets 中 `.apk` 的链接。
2. 在链接前加上前缀，例如：

```text
https://ghproxy.net/https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk
```

3. 核对文件大小与发布页一致后再安装。只信任本仓库 Releases 产物与统一签名说明（见 `signing/README.md`、`SECURITY.md`）。

社区镜像会轮换、限速或失效；失效时换前缀、直连 GitHub，或改用自建 `file:` 基址重新打包。

## 相关代码

- `app/src/main/java/com/hongguo/watch/data/UpdateChecker.kt` — 镜像解析与候选 URL
- `app/src/main/java/com/hongguo/watch/data/UpdateApkInstaller.kt` — 多地址依次下载
- `app/build.gradle.kts` — `HG_APK_MIRROR_BASES` / `APK_MIRROR_BASES`
