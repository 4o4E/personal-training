# 后端配置与部署

## 本机运行

从项目根目录构建并运行后端：

```powershell
.\gradlew.bat -PserverOnly :server:installDist
.\server\build\install\server\bin\server.bat
```

Linux：

```sh
./gradlew -PserverOnly :server:installDist
./server/build/install/server/bin/server
```

默认监听 `0.0.0.0:8080`，资料位于当前运行目录的 `data/`，与本地小米数据库独立。

| 环境变量 | 用途 |
|---|---|
| `TRAINING_DATA` | 训练数据库、上传图片和服务器 token 的持久目录，默认 `data` |
| `TRAINING_HOST` | 监听地址，默认 `0.0.0.0` |
| `PORT` | HTTP 端口，默认 `8080` |
| `TRAINING_TOKEN` | 可选的唯一 token；未提供时自动生成并保存在数据目录的 `token` 文件中；提供空值会启动失败 |

应用首次教程或「菜单 → 设置」中填写手机实际可访问的后端地址和 token。不要填服务器自己的 `localhost`；公网服务应由 HTTPS 反向代理提供。不要经由日志、聊天或公开制品传递 token。仅本机验证可将 `TRAINING_HOST` 设为 `127.0.0.1`。

## 容器部署

项目提供 `compose.yml` 与 `Collector.Dockerfile`。服务器持久化目录映射到容器 `/data`，宿主机示例目录为 `training-data/`；后端数据/token 和采集器状态都需持久保存。容器默认仅将后端绑定到服务器 `127.0.0.1:8080`，由 HTTPS 反向代理提供外部访问；家庭网络直连需按实际网络调整端口绑定。健康检查端点是 `/health`，根路径不是网页首页。

### 首次部署

1. 准备持久化目录，并设置后端 token；确保目录权限仅授予服务所需用户。
2. 如需导入既有小米资料，将私有 SQLite 一致性副本放入 `training-data/misports.db`，并按[采集器指南](xiaomi-collector-usage.md)配置小米凭据。不要复制正在写入的数据库，也不要把私有源数据或凭据放入公开制品。
3. 拉取并启动训练服务，再依次发布既有采集历史、执行一次采集并启动常驻服务：

```sh
docker compose pull training collector
docker compose up -d training
docker compose run --rm collector publish
docker compose run --rm collector run
docker compose up -d collector
```

训练服务启动后，以配套版本的采集器执行操作。小米凭据可放在 `training-data/collector/source/credentials.json`（字段 `user_id`、`pass_token`）或 `training-data/collector/source/cookie.txt`（完整 cookie）；严格限制文件权限。凭据可能轮换，不要写入 Compose、Git、日志或公开制品。

### 更新与回退

更新前先完整备份服务器持久目录并停止或协调写入，再使用同一版本的训练与采集器镜像：

```sh
docker compose pull training collector
docker compose up -d training collector
```

保留已有 `/data`，不要用本机数据库替换服务器采集状态。回退时同时恢复升级前备份及匹配的服务版本；详见[备份与迁移](backup.md)。手机更新应另行备份本机资料并覆盖安装同版 APK，不要卸载。

## 发布与状态边界

项目根目录的 GitHub Actions 根据版本构建并发布 APK、后端与采集器发行物及镜像。标签格式为 `vMAJOR.MINOR.PATCH`。构建或镜像发布不等于服务器已部署；应以目标环境实际配置与验收为准。当前源码中的容器/采集器操作说明不构成生产部署状态声明。
