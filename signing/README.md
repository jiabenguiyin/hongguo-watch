# 签名说明

## Debug / 测试侧载（固定证书）

仓库提交了 `signing/debug.keystore`（别名 `androiddebugkey`，密码 **android/android**）。  
`assembleDebug` 始终用这把钥匙，这样 **GitHub Releases 上的 debug APK 可以互相覆盖安装**，不必每次卸载。

若本机曾装过「另一台机器默认 `~/.android/debug.keystore`」签出的旧包，证书不同会提示无法覆盖 → **卸载一次**后再装 0.4.4+ 即可；之后同签名升级不再需要卸载。

这把 debug 证书 **不是** 正式发布密钥，可公开；请勿把它当成 production keystore。

## 正式签名（release）

**GitHub Releases 正式包** 必须使用固定 release 密钥，否则日后换钥会导致无法覆盖安装。

## 配置方式（二选一）

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

`signing/keystore.properties` 与 `*.jks` / `*.keystore` 已在 `.gitignore`。

## 生成密钥（仅首次）

```bash
keytool -genkeypair -v \
  -keystore /path/to/hongguo-watch-release.jks \
  -alias hongguo_watch \
  -keyalg RSA -keysize 2048 -validity 10000
```

## 注意

- 未配置时 **`assembleRelease` 不会静默用 debug 证书**，避免假 release。
- 若用户已安装 **debug 签名** 的 0.4.x，首次换成正式证书需卸载再装（证书不同无法覆盖）。
- 密钥丢失 = 无法以同包名升级；请离线备份。
