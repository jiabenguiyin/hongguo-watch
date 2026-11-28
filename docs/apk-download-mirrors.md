# APK 更新下载镜像

国内访问 GitHub Releases 常不稳定。0.5.8 起应用内「检查更新 / 下载安装」支持 **内置镜像源**（设置页），节点取自 [hiwiki 镜像目录](https://git.hiwiki.org/zh-CN/mirrors)。

## 行为

| 模式 | 说明 |
| --- | --- |
| **自动镜像**（默认） | APK 下载用前缀代理（默认节点 `gh.meali.top`）；检查更新走独立 API 代理池 |
| **GitHub 官方** | 直连 `api.github.com` 与 Release Assets |

**区域：** 自动镜像下，若设备不像中国大陆（系统国家码非 `CN`，且时区也非 `Asia/Shanghai` 等大陆区），**APK 下载跳过 CN 镜像、只走 GitHub 官方**。检查更新 API 仍按其自身备用池，不受此限制。

大陆自动镜像下 **APK 下载** 失败时，按 2026-11-28（北京时间）**上海**测速排序依次尝试，最后回落官方 GitHub：

1. 选中节点（默认 `gh.meali.top`）
2. `jiashu.1win.eu.org`
3. `gh.monlor.com`
4. `gitproxy.127731.xyz`
5. `free.cn.eu.org`
6. `cdn.akaere.online`
7. `gh.inkchills.cn`
8. `gh.chjina.com`
9. GitHub 官方

已从 APK 主梯队移出（目录中仍可选）：旧首选 `ghproxy.imciel.com`；仅在日本 Dot 表现突出的 `xsadwsd.kdns.fr` / `ghfast.top`。用户取消下载会立即停止，不再试下一个地址。

**发布 API**（检查更新）与 APK 池完全独立：忽略设置里选中的 APK 节点，**绝不使用** `gh.meali.top` / `jiashu.1win.eu.org`（meali 对 api.github.com TLS 失败会返回约 2KB 垃圾包）。自动模式下依次尝试：

1. `ghproxy.imciel.com`
2. `gh-proxy.com`
3. `cdn.gh-proxy.com`
4. `ghfast.top`
5. `gh.ddlc.top`
6. `gh.monlor.com`
7. 官方 `api.github.com`

## 设置

- **内置镜像源** → 切换「GitHub 官方」或「自动镜像」
- **调整镜像** → 从 hiwiki `proxyNodeKeys` 内置列表中选择节点（列表靠前优先上海测速 TOP 主机）

## 浏览器手动下载

也可把 Release Assets 链接改写成前缀形式，例如：

```text
https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/hongguo-watch-0.5.8-arm64-v8a.apk
→
https://gh.meali.top/https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/hongguo-watch-0.5.8-arm64-v8a.apk
```

请只从本仓库 Releases 或可信前缀代理获取安装包；勿使用来源不明的第三方改包。
