# 下载页部署与搜索收录

本目录 index.html 为独立静态下载页源码，当前提交不代表网站已发布。没有固定虚构网址，下载链接指向真实 GitHub Releases。

部署到维护者实际控制的 HTTPS 域名后：
1. 将 index.html 作为入口，确认 HTTP 200、移动端可读、无需登录。
2. 设置实际 URL 的 canonical 和 og:url；生成使用同一真实域名的 robots.txt（允许抓取）与 sitemap.xml。
3. 将站点纳入 Google Search Console、Bing Webmaster Tools 等站长平台，验证所有权后提交 sitemap 与首页。若采用国内服务器，先确认备案和分发要求。
4. README、交流群与真实项目介绍链接到该下载页；国内 APK 镜像完成前不得宣称免 VPN 下载。
5. 保持一个稳定下载入口，更新真实功能、安装教程与版本信息。不进行关键词堆砌、虚假 Star 或机器人搜索点击。搜索收录与排名无法保证。
