# APK 更新下载镜像

国内访问 GitHub Releases 常不稳定。0.5.8 起应用内「检查更新 / 下载安装」支持 **内置镜像源**（设置页），节点取自 [hiwiki 镜像目录](https://git.hiwiki.org/zh-CN/mirrors)。

## 行为

| 模式 | 说明 |
| --- | --- |
| **自动镜像**（默认） | 前缀代理：`https://{节点}/` + 完整 GitHub / api.github.com URL。默认节点 `ghproxy.imciel.com` |
| **GitHub 官方** | 直连 `api.github.com` 与 Release Assets |

自动镜像失败时依次尝试目录内备用节点（如 `gh-proxy.com`、`cdn.gh-proxy.com`、`ghfast.top` 等），最后回落官方 GitHub。用户取消下载会立即停止，不再试下一个地址。

## 设置

- **内置镜像源** → 切换「GitHub 官方」或「自动镜像」
- **调整镜像** → 从 hiwiki `proxyNodeKeys` 内置列表中选择节点

## 浏览器手动下载

也可把 Release Assets 链接改写成前缀形式，例如：

```text
https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/hongguo-watch-0.5.8-arm64-v8a.apk
→
https://ghproxy.imciel.com/https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/hongguo-watch-0.5.8-arm64-v8a.apk
```

请只从本仓库 Releases 或可信前缀代理获取安装包；勿使用来源不明的第三方改包。
