# 短剧本地视频缓存

**0.4.27（T-70）**：离线改为**详情页用户发起**（多选分集 / 全季）；播放器不再边看边缓存、不再预取下一集。库·「离线」仍管理已下载文件；动态存储预算保留。

**0.4.24（T-65）**：缓存上限按本机 `free`/`total`/`usedCache` 动态计算（预留 headroom），库·离线与设置展示实际预算；不再使用固定 512MB 档。

```
headroom = max(128MB, total×5%)
usable   = max(0, free + usedCache − headroom)
budget   = min(usable, clamp(min(usable×40%, total×20%), min(256MB, usable), 8GB))
```

**0.4.23（T-59）**：弱网缓冲提示。**0.4.22（T-58）**：阶梯自动切流。**0.4.21（T-57）**：库·离线列表。**0.4.20（T-56）**：本地缓存 MVP。
