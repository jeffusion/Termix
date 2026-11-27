# Termix 应用更新

正式包从 GitHub Releases 读取最新稳定版。进入前台时最多每 24 小时自动检查一次，也可以在「设置 → 关于Termix」手动检查。下载由用户发起。校验通过后，用户可授权 Termix 请求安装并在 Android 系统界面确认。覆盖安装可能中断终端会话。

## 正式签名

GitHub 的 `release` environment 需要配置两个 secrets 和一个 variable。

| 配置 | 内容 |
| --- | --- |
| `KEYSTORE` | 项目正式签名密钥库的 Base64 |
| `PROP` | 包含 `keyAlias`、`keyPassword`、`storePassword` 的签名配置文件的 Base64 |
| `RELEASE_CERT_SHA256` | 正式证书的 SHA-256 指纹，64 位十六进制字符 |

正式构建和 APK 装箱会验证签名配置、实际证书、包名、版本和更新开关。用测试签名的日常 CI 构建不启用更新器。应安全备份正式密钥库，后续版本要沿用同一正式签名。

本地构建正式配置测试包时，将 `SIGNING_PROPERTIES_FILE` 和 `KEYSTORE_FILE` 指向仓库外的临时签名材料。使用 Android SDK Build Tools 36.0.0，将测试证书的指纹放入环境变量 `TERMIX_RELEASE_CERT_SHA256`。确认本地副本的版本后执行以下命令。

```sh
TERMIX_OFFICIAL_RELEASE=true ./gradlew :app:assembleRelease
bash scripts/prepare-release-apk.sh "$version"
```

## 从公开测试密钥迁移

旧版 v1.3.0 和 v1.4.0 使用了仓库内的公开测试密钥。两类旧版用户使用同一个迁移包。GitHub 稳定版的普通包命名为 `termix-v<版本>.apk`，已迁移用户可以从 Termix 的应用更新入口安装后续版本。

- **Android 9 及以上**。迁移包使用正式证书和轮换证明。结束终端会话，覆盖安装迁移包并检查 Linux 环境和设置。普通包发布后，打开「设置 → 关于Termix」检查。
- **Android 8.0 和 8.1**。等迁移包和普通包都可下载后再开始。迁移包在这两个系统上使用旧证书，覆盖安装后会打开导出入口。确认加密备份和恢复密钥都在手边，再卸载旧包、安装普通正式包并恢复。详见 [Android 8 数据迁移](android-8-data-migration.md)。

### 先准备迁移预发布

迁移预发布的 Git 标签使用 `migration-v1.4.0`，在 GitHub 标记为预发布。唯一的 APK 资产是 `termix-migration-v1.4.0.apk`。APK 内的 `versionName` 为 `1.4.0`，`versionCode` 为 `15`。GitHub 的稳定版更新入口会跳过这个 Release。

迁移改动合入 `main` 后，发布工作流会等待该提交的 `main` 推送 CI 通过，再用正式签名构建并发布迁移预发布，同时附上音频源码归档。迁移阶段只执行这一条发布路径。确认迁移预发布可下载后，再通过后续 PR 将工作流的 `TERMIX_RELEASE_PHASE` 切换为 `stable`。

如果发布失败，重跑原来的 Semantic Release 运行。运行会优先复用其中已验签的 APK，再继续上传和核对草稿资产。

本地验证时，按上面的正式签名步骤构建 `1.4.0 / 15`，用它作为迁移包的输入。设置 `KEYSTORE_FILE`、`TERMIX_RELEASE_KEY_ALIAS`、`TERMIX_RELEASE_STORE_PASSWORD`、`TERMIX_RELEASE_KEY_PASSWORD`、`TERMIX_RELEASE_CERT_SHA256` 和轮换证明的输出路径 `lineage`。用 Build Tools 36.0.0 生成证明和迁移包。旧证书只保留 `installed-data` 能力。

```sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" rotate --out "$lineage" \
  --old-signer --ks app/testkey.keystore --ks-key-alias testkey \
  --ks-pass pass:testkey --key-pass pass:testkey \
  --set-installed-data true --set-shared-uid false --set-permission false \
  --set-rollback false --set-auth false \
  --new-signer --ks "$KEYSTORE_FILE" --ks-key-alias "$TERMIX_RELEASE_KEY_ALIAS" \
  --ks-pass env:TERMIX_RELEASE_STORE_PASSWORD --key-pass env:TERMIX_RELEASE_KEY_PASSWORD

bash scripts/prepare-migration-apk.sh \
  app/termix-v1.4.0.apk "$lineage" termix-migration-v1.4.0.apk
```

迁移包在 Android 8.0/8.1 使用旧证书的 v2 签名，在 Android 9 及以上使用正式证书的 v3 签名。装箱脚本会检查各系统版本实际选择的证书。

### 再准备普通稳定版

普通包的 `versionName` 必须高于 `1.4.0`，`versionCode` 必须高于 `15`。代码中已为迁移预发布保留 `15`，现有自动发布流程会把下一稳定版递增到 `16`，正式版本名由 semantic-release 决定。稳定 Release 继续发布普通包和音频源码归档。首个普通包的发布说明应链接到迁移预发布，方便之后才迁移的旧用户。
