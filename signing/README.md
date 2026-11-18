# 签名说明

## 统一证书（当前所有已发布 APK）

仓库提交了 **`signing/debug.keystore`**（别名 `androiddebugkey`，密码 **android/android**）。

| 构建任务 | 签名 |
|----------|------|
| `:app:assembleDebug`（**GitHub Releases 现行做法**） | 始终 `signing/debug.keystore` |
| `:app:assembleRelease`（未配置正式密钥时） | **同一把** `signing/debug.keystore` |
| `:app:assembleRelease`（已配置 `WATCH_RELEASE_*` / `keystore.properties`） | 正式 keystore |

这样 **Releases 上的包、本地 debug、无正式钥时的 release 包可互相覆盖安装**，不必反复卸载。

证书指纹（SHA-256）：

`33:B2:1E:34:D8:19:88:2B:C0:77:D9:0F:FF:82:5D:0A:59:F7:D7:C5:C2:FE:E4:2B:0F:DB:86:65:73:0D:17:60`

### 从旧包升级

若设备上仍是 **0.4.4 之前**用本机默认 `~/.android/debug.keystore`（或其他随机钥）签出的包，证书不同无法覆盖 → **卸载一次**后再装 **0.4.4+**；之后同仓库证书升级不再需要卸载。

这把 debug 证书 **不是** 正式发布密钥，可公开；**请勿另造假 production keystore**。真要上正式钥时再配下一节。

## 正式签名（可选，有 production 密钥时）

配置后 `assembleRelease` 改用正式钥；与当前侧载 debug 证书 **不同**，用户需卸载再装一次。密钥丢失 = 无法以同包名升级，请离线备份。

### 1. 环境变量（CI / Cloud Agent 推荐）

| 变量 | 含义 |
|------|------|
| `WATCH_RELEASE_STORE_FILE` | keystore 路径（相对仓库根或绝对路径） |
| `WATCH_RELEASE_STORE_PASSWORD` | store 密码 |
| `WATCH_RELEASE_KEY_ALIAS` | 别名 |
| `WATCH_RELEASE_KEY_PASSWORD` | key 密码 |

### 2. 本地文件（勿提交密钥）

```bash
cp signing/keystore.properties.example signing/keystore.properties
# 编辑路径与密码；keystore 本体放仓库外或 signing/*.jks（已 gitignore）
./gradlew :app:assembleRelease
```

`signing/keystore.properties` 与 `*.jks` / `*.keystore` 已在 `.gitignore`（例外：`signing/debug.keystore`）。

### 生成正式密钥（仅首次）

```bash
keytool -genkeypair -v \
  -keystore /path/to/hongguo-watch-release.jks \
  -alias hongguo_watch \
  -keyalg RSA -keysize 2048 -validity 10000
```

## 发布说明模板

发 GitHub Release 时复制 [`RELEASE_NOTES_TEMPLATE.md`](./RELEASE_NOTES_TEMPLATE.md)，填版本号并保留签名/卸载说明段落。
