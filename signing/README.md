# 正式签名（release）

当前 **debug / 测试侧载** 仍用 Android 默认 debug 证书。  
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
