# 开始使用与本地构建

## 安装应用

在 [GitHub Releases](https://github.com/4o4E/personal-training/releases) 查看是否有可用 APK；如有，下载后在 Android 设备上安装。Android 8.0 及以上。覆盖升级会保留应用资料；升级前应另行备份，不要卸载或清空应用数据。若没有可用发行版，请参考下方本地构建步骤。

首次使用可按应用教程配置后端及通知权限。后端不是离线训练的前置条件；连接后端后可同步资料、访问服务端素材及健康数据。

## 本地构建 APK

从项目根目录执行。需要 JDK 21、Android SDK 36 和 Build Tools 36.0.0，并设置 `JAVA_HOME` 与 `ANDROID_HOME`。正式签名构建还需准备仓库外的签名密钥：

```powershell
$env:ANDROID_KEYSTORE_PATH = 'D:\private\release.keystore'
$env:ANDROID_KEYSTORE_PASSWORD = '...'
$env:ANDROID_KEY_ALIAS = '...'
.\gradlew.bat :android:assembleRelease
```

APK 输出在 `android\build\outputs\apk\release\android-release.apk`。不要将密钥或密码提交到仓库。覆盖安装必须使用与已安装版本相同的签名证书。

正式版本使用 `vMAJOR.MINOR.PATCH` 标签。指定发布版本构建时，例如：

```powershell
.\gradlew.bat -PreleaseVersion=1.2.3 :android:assembleRelease
```

内部版本号为 `MAJOR × 1000000 + MINOR × 1000 + PATCH + 1`，次版本与修订号不超过 999。CI 签名使用 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD` 与 `ANDROID_KEY_ALIAS` 对应的 Secrets。

## 服务器发行物

服务器端构建可跳过 Android 工程，不需要 Android SDK：

```sh
./gradlew -PserverOnly :server:distZip :collector:distZip
```

Windows 可用 `.\gradlew.bat` 替代 `./gradlew` 并运行相同任务。后端与采集器分别由 `:server:distZip` 和 `:collector:distZip` 生成；采集器发行包名为 `personal-training-collector-<version>.zip`。正式版本构建示例：

```powershell
.\gradlew.bat -PreleaseVersion=1.2.3 :server:distZip :collector:distZip :android:assembleRelease
```

详细服务器运行和持久化要求见[部署指南](deployment.md)。
