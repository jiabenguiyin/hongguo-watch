# APK 下载镜像（国内可达）

GitHub Releases 资源在国内常需 VPN。红果终端采用轻量方案：**优先把下载链接里的 `github.com` 改成 `githubfast.com`，失败再回落官方 GitHub**；并在 README / 安装指南中提供同样拼法。不绑定付费 CDN，不引入密钥。

## 结论（选型）

| 方案 | 结论 |
| --- | --- |
| 1. 应用内：`github.com` → `githubfast.com` + GitHub 回落 | **采用（默认）**。主机名插入 `fast`，路径不变 |
| 2. README / install 文档镜像链接 | **采用**。人工下载与应用内一致 |
| 3. 同步 Release APK 到自有可达主机 | **可选**。仅当你已有对象存储 / 静态站；不代开付费账号 |
| 前缀代理（ghproxy.net 等） | **可选追加**，经 `HG_APK_MIRROR_BASES`；不再作为默认首选 |
| jsDelivr `cdn.jsdelivr.net/gh/...` | **不适合** Release Assets |

## 应用内行为（0.4.10+）

1. 「检查更新」仍读 `api.github.com`（与下载 CDN 路径不同）。
2. 选定 ABI 对应 APK 后，`UpdateApkInstaller` 按 `UpdateChecker.candidateDownloadUrls` 依次尝试：
   - **`github.com` → `githubfast.com`**（主机名改写）
   - 可选：构建时注入的额外镜像（`BuildConfig.APK_MIRROR_BASES`）
   - 最后回落 GitHub `browser_download_url`
3. 单个地址失败（超时 / 非 2xx）自动试下一个；用户取消立即停止。

示例：

```text
https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk
→
https://githubfast.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk
```

## 额外镜像基址（可选）

环境变量 **`HG_APK_MIRROR_BASES`**（逗号分隔）写入 `BuildConfig.APK_MIRROR_BASES`，排在 githubfast 之后、官方 GitHub 之前。默认空。

### 条目约定

| 写法 | 含义 | 示例结果 |
| --- | --- | --- |
| `https://ghproxy.net/` | 前缀代理：`base + 完整 GitHub URL` | `https://ghproxy.net/https://github.com/.../x.apk` |
| `file:https://cdn.example.com/apk/` | 按文件名：`base + apkName` | `https://cdn.example.com/apk/hongguo-watch-….apk` |
| `…/{url}` | 占位符替换完整 URL | 自定义反代路径 |
| `…/{file}` | 占位符替换 APK 文件名 | 自定义对象路径 |

自建示例：

```sh
export HG_APK_MIRROR_BASES='file:https://cdn.example.com/hongguo-watch/'
./gradlew :app:assembleRelease
```

同步可用 Release 工作流里多一步 `curl`/`rclone` 上传 Assets；本仓库不内置付费 CDN 账号。

## 浏览器手动下载

1. 打开 [最新 Release](https://github.com/jiabenguiyin/hongguo-watch/releases/latest)，复制 Assets 中 `.apk` 的链接。
2. 把链接里的 `github.com` 改成 `githubfast.com`（在 `github` 后插入 `fast`），例如：

```text
https://githubfast.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk
```

3. 核对文件大小与发布页一致后再安装。只信任本仓库 Releases 产物与统一签名说明（见 `signing/README.md`、`SECURITY.md`）。

镜像站点可能变更或失效；失效时直连 GitHub，或配置自建 `file:` 基址后重新打包。

## 相关代码

- `app/src/main/java/com/hongguo/watch/data/UpdateChecker.kt` — `rewriteGithubFast` 与候选 URL
- `app/src/main/java/com/hongguo/watch/data/UpdateApkInstaller.kt` — 多地址依次下载
- `app/build.gradle.kts` — 可选 `HG_APK_MIRROR_BASES` / `APK_MIRROR_BASES`
