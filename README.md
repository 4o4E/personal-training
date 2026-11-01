# 个人训练

一款面向个人使用的 Android 原生训练应用，配套可自托管的 Ktor 后端与 SQLite 存储。支持离线记录训练，并在连接后同步；不提供 iOS 或浏览器界面。Android 8.0 及以上。

## 核心功能

- 配置自定义分化、训练计划、健身房和器械，按组记录实际训练与拉伸。
- 离线训练与后端同步，冲突由用户明确选择；支持训练资料、用户上传图片及健康资料备份恢复。
- 查看健康数据和小米运动健康采集结果；采集服务独立运行。
- 中文动作教学、主要/辅助部位文字、已有动作图片及来源视频的手机离线缓存与训练历史；没有可用图片或视频时仍显示文字说明。

## 安装

在 [GitHub Releases](https://github.com/4o4E/personal-training/releases) 查看是否有可用 APK；如有，下载后在 Android 设备上安装。覆盖升级前请保留手机资料备份；不要卸载应用来升级。

## 最简后端配置

后端可选：应用离线记录不要求服务在线。需要跨设备同步时，在自有服务器构建并运行后端：

```sh
./gradlew -PserverOnly :server:installDist
./server/build/install/server/bin/server
```

默认监听 `0.0.0.0:8080`，资料保存在运行目录下的 `data/`。首次启动未设置 `TRAINING_TOKEN` 时会自动生成 token 并写入 `data/token`。在应用首次设置或「设置」中填写手机可访问的后端地址和 token；公网部署应通过 HTTPS 反向代理，不要将 token 暴露在日志或公开位置。

## 文档

- [开始使用与本地构建](docs/getting-started.md)：环境、APK 构建与安装。
- [后端配置与部署](docs/deployment.md)：环境变量、容器、首次部署、更新与持久化。
- [应用使用指南](docs/app-guide.md)：计划、训练、健康数据及提醒操作。
- [备份与迁移](docs/backup.md)：手机与后端备份范围及升级注意事项。
- [动作素材](docs/media.md)：内置、缓存及网站来源素材。
- [开放接口](docs/API.md)：HTTP API 与数据格式。
- [小米采集器使用指南](docs/xiaomi-collector-usage.md) · [维护指南](docs/xiaomi-collector-maintenance.md)：本机及服务器采集操作。
- [项目训练助手技能](.agents/skills/training-assistant)：命令行操作与助手说明。

项目开发变更见 [CHANGELOG](CHANGELOG.md)。
