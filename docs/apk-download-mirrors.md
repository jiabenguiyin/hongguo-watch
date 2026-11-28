# APK 更新下载镜像

国内访问 GitHub Releases 常不稳定。0.5.8 起应用内「检查更新 / 下载安装」支持 **内置镜像源**（设置页），节点取自 [hiwiki 镜像目录](https://git.hiwiki.org/zh-CN/mirrors)。

## 行为

| 模式 | 说明 |
| --- | --- |
| **自动镜像**（默认） | 前缀代理：`https://{节点}/` + 完整 GitHub / api.github.com URL。默认节点 `ghproxy.imciel.com` |
| **GitHub 官方** | 直连 `api.github.com` 与 Release Assets |

自动镜像下 **APK 下载** 失败时，按 2026-11-28（北京时间）上海 + Dot 云电脑测速，依次尝试双站高通过率备用节点，最后回落官方 GitHub：

1. 选中节点（默认 `ghproxy.imciel.com`）
2. `jiashu.1win.eu.org`
3. `xsadwsd.kdns.fr`
4. `gitproxy.127731.xyz`
5. `ghfast.top`
6. `cdn.akaere.online`
7. `gh.chjina.com`
8. GitHub 官方

已从 APK 下载回退中降级 / 移出：`gh-proxy.com`、`cdn.gh-proxy.com`、`gh.monlor.com`（Dot 常 0 通过）、`gh.ddlc.top`（上海弱）。用户取消下载会立即停止，不再试下一个地址。

**发布 API**（检查更新）未随该表测试，仍用原备用序，不与 APK 下载列表共用。

## 设置

- **内置镜像源** → 切换「GitHub 官方」或「自动镜像」
- **调整镜像** → 从 hiwiki `proxyNodeKeys` 内置列表中选择节点（列表靠前优先双站高通过率主机）

## 浏览器手动下载

也可把 Release Assets 链接改写成前缀形式，例如：

```text
https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/hongguo-watch-0.5.8-arm64-v8a.apk
→
https://ghproxy.imciel.com/https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/hongguo-watch-0.5.8-arm64-v8a.apk
```

请只从本仓库 Releases 或可信前缀代理获取安装包；勿使用来源不明的第三方改包。
