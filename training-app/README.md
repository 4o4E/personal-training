# 个人训练

Android 原生训练应用、自有 Ktor 后端、SQLite 存储和项目级训练操作 skill。Android 8.0 及以上；不提供 iOS 或浏览器界面，后续浏览器继续遵守只读数据边界。

## 手机操作

1. 在「健身房」记录实际场地和器械，选择器械类别、原始 kg / lb 及读数含义，可以添加实拍图片和使用说明。
2. 在「计划」配置 N 分化：每项填写名称和目标部位，使用增加、移除和调整顺序配置 N，不限制为推拉腿。
3. 创建具体计划，选择分化项、健身房及可选日期；按部位和实际器械选择动作，查看中文步骤、图解和肌肉示意，设置各组要求。
4. 点击「查看对应拉伸并选择加入」，只会查看候选；选择后自行设置左右侧、次数或时长及加入位置，不会自动增加固定收尾部分。
5. 在「训练」预览后续计划并开始。第一次需要通知时由系统请求授权；仍可在「设置」查看通知、定时唤醒及后台相关入口。
6. 每组按实际情况修改次数或读数，点击「完成一组」即保存并开始休息。计时固定显示在训练列表上方；切换应用和息屏后，到时唤醒应用并发送普通通知，不依赖网络或界面持续运行。
7. 结束时选择完成或取消。未完成的目标组不计入历史，取消中已完成的组仍保留；只有明确完成才推进分化顺序。休息几天不会跳过部位。
8. 「记录」查看实际组、原始重量单位、侧别、开始时计划和训练反馈。目标值与实际结果分开，过去的训练不会因修改未来计划而改变。

默认新力量动作 4 组，可以按实际情况调整；拉伸按动作自身的时长、次数和左右侧要求配置。更换具体器械不会把旧读数自动换算到另一种单位，需要确认该台器械的实际显示。

## 编译与安装

使用 JDK 21、Android SDK 36 和 Build Tools 36.0.0，设置好 `JAVA_HOME` 与 `ANDROID_HOME`。所有命令在本目录执行。

Windows 原生命令：

```powershell
.\gradlew.bat :server:test :server:installDist :android:assembleDebug
adb install -r android\build\outputs\apk\debug\android-debug.apk
```

Linux 后端构建不需要 Android SDK：

```sh
./gradlew -PserverOnly :server:test :server:installDist
```

应用、共享类型和后端分别位于 `android/`、`shared/`、`server/`。Gradle 包装器随项目提供，不需要全局安装 Gradle。

## 运行后端

Windows：

```powershell
.\gradlew.bat -PserverOnly :server:installDist
.\server\build\install\server\bin\server.bat
```

Linux：

```sh
./server/build/install/server/bin/server
```

默认监听 `0.0.0.0:8080`，资料位于当前运行目录的 `data/`，与已有运动记录及小米数据库完全独立。

| 环境变量 | 用途 |
|---|---|
| `TRAINING_DATA` | 自有训练数据库、上传图片和服务器 token 的持久目录，默认 `data` |
| `TRAINING_HOST` | 监听地址，默认 `0.0.0.0` |
| `PORT` | HTTP 端口，默认 `8080` |
| `TRAINING_TOKEN` | 可选的唯一 token；不提供时自动生成并保存在 `data/token`，提供空值则启动失败 |

在手机「设置」填写手机实际能够访问的后端地址及 token，点击保存并同步。不要填写服务器自己的 `localhost`；调试中的 ADB 端口转发不等同于长期可用的网络部署。公网使用 HTTPS 反向代理，不通过日志或聊天发送 token。

仅本机验证时可以把 `TRAINING_HOST` 设为 `127.0.0.1`。离线训练不要求后端运行；手机保存的待同步记录会在连接恢复后上传。后端只能读取已经上传的数据。

## CI 镜像发布与手动部署

源码保存在私有 GitHub 仓库，GHCR 镜像包公开。工作流位于根目录 `.github/workflows/publish-training-image.yml`，默认分支有后端变更时自动运行，也可以在 Actions 手动触发。镜像地址根据实际仓库生成并转为小写，发布 Linux amd64 的 `latest`。

构建时执行现有服务端行为回归；容器启动和唯一 token 鉴权通过后才推送。Gradle 依赖使用构建层缓存，框架依赖、教学素材和业务代码分层，业务更新不要求重新下载全部教学素材。发布使用工作流自己的 `GITHUB_TOKEN` 和 `packages: write`，不需要另存发布 PAT。

### 首次公开设置

GitHub 新建容器包默认是 Private。第一次推送生成包后，在 GitHub 的 Packages 中打开该包，进入 **Package settings → Change visibility → Public** 并确认，源码仓库继续保持 Private。包的可见性与源码仓库独立，公开镜像可以匿名下载；参见 [GHCR 官方说明](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)。

完成公开设置后，重新运行工作流。最后一步会使用空的 Docker 登录配置实际拉取镜像；没有公开时此检查失败，不会把需要登录的包视为已经满足匿名部署要求。

### 部署与更新

1. 将 `training-app/compose.yml` 复制到服务器的固定部署目录，无需复制源码。
2. 从发布任务的运行摘要复制 `GHCR_REPOSITORY` 内容，保存为部署目录中的 `.env`。该值由实际仓库生成，不包含 token。
3. 公开镜像无需 `docker login`，在部署目录执行：

```sh
docker compose pull
docker compose up -d
```

以后更新也执行这两条命令。Compose 始终拉取 `ghcr.io/${GHCR_REPOSITORY}:latest`，资料位于 `training-data` 命名卷，不需要服务器本地构建。服务以非 root 用户运行，默认仅发布到服务器 `127.0.0.1:8080`，由 HTTPS 反向代理提供手机可访问的地址；家庭网络直连时需按实际网络修改端口绑定。

不配置 `TRAINING_TOKEN` 时，服务首次启动会自动生成唯一 token 并保存到 `/data/token`。在服务器本地取得后填入手机「设置」，不将其放进镜像、发布参数或聊天；持久卷保留数据库、素材和 token，更新镜像不清空资料。

## 助手操作 skill

从本目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\install-skill.ps1
```

只安装到当前工作区 `.omp/skills/training-assistant`，不访问全局 skill。工程工作流程技能继续使用工作区已经安装的 `4o4E/skills#feat/openspec-decisions` 和官方 OpenSpec 集成。

操作工具只需要 Python 3 标准库。为助手进程配置 `TRAINING_URL`，以及 `TRAINING_TOKEN` 或 `TRAINING_TOKEN_FILE`；服务器本地可以让 `TRAINING_TOKEN_FILE` 指向持久目录中的 `token` 文件，无需将其内容写入脚本。

```powershell
python skills\training-assistant\scripts\training.py list gym
python skills\training-assistant\scripts\training.py list program
python skills\training-assistant\scripts\training.py history --from 2026-10-01 --to 2026-10-31
python skills\training-assistant\scripts\training.py export training-backup.zip
```

安排计划、查询部位及器械候选、原始单位、同步版本、冲突处理和导入导出格式见 [开放接口](docs/API.md) 与 [操作技能](skills/training-assistant/SKILL.md)。助手不需要点击手机或修改数据库。

## 备份与迁移

手机「设置」可以导出和导入 ZIP；接口和 CLI 使用相同格式。ZIP 包含版本化 `data.json` 与实际图片，覆盖动作、器械、分化、计划及实际记录，不包含后端 token、手机授权或连接配置。

默认合并不替换已有不同内容，需要替换时必须明确选择。迁移到另一套后端时先导出完整资料，再导入目标后端，最后修改手机连接；不要将计划要求当作实际完成量。

## 提醒条件

休息和定时拉伸使用 `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, …)`。完成一组时先将结果与截止时间写入 Room，再由系统到时唤醒应用并发送普通通知。网络同步使用 WorkManager，不承担精确提醒。

需要通知渠道可用及系统允许定时唤醒。应用提供实际授权状态和设置入口。通知使用普通通知提示音和振动，遵循手机的通知、音量及勿扰设置，不播放闹钟铃声、不弹出全屏提醒。验证范围为日常训练、切换应用和息屏；不处理强制停止、关机等极端情况。

## 实际验证

使用 Windows 原生命令、JDK 21 和 Android 13 的 LE2101 真机完成验证；以下数字来自独立软件验证资料，不是个人训练记录。

- 安卓 APK 与后端运行程序已成功构建，APK 已实际安装并打开；真实 SQLite 行为回归共 8 项通过。
- 通过项目内操作 CLI 写入日期计划，手机同步后开始训练，再通过同一接口读取实际完成结果，完成计划—训练—休息—同步—助手读取的操作路径。
- 同一次训练原样保存 `20 kg · 单只` 与 `60 lb · 总重量 · 助力`；验证训练实际完成 3 组、有效目标 11 组，未执行的目标没有计入实际记录。
- 手机在后端停止期间仍保存完成组，恢复后端后同步取得一致结果。计划并发编辑会显示手机与后端两份内容，实际选择保留手机内容后同步成功。
- 真机检查了中文步骤、实际动作插画、正背面肌肉示意及多个器械匹配候选；仅查看拉伸不自动添加，指定插入位置后保留左右侧计时与动态次数。
- 通过界面创建两分化及健身房器械，验证未指定日期的计划按轮换顺序显示、切换页面保留方案选择、取消不推进、完成后推进；替换剩余动作不改写已经完成的组和开始时内容。
- 完成一组后返回桌面并息屏，系统实际发布休息结束普通通知；单侧拉伸计时结束也发布普通通知，类别为 `reminder`，音频用途为 `USAGE_NOTIFICATION`。
- 通过系统图片选择器保存器械图片，并经接口取得实际 JPEG 内容。手机 ZIP 导出、合并导入及独立后端恢复均已执行；含器械图片的数据包有 919 份业务文档、443 份媒体文件，恢复后逐份比较图像内容一致，解压后的业务资料不包含连接凭据。

工作流已通过 actionlint 检查，Compose 已用原生 Windows 工具实际解析，并验证缺少镜像仓库名时明确报错。源码仓库为私有的 [4o4E/personal-training](https://github.com/4o4E/personal-training)。当前电脑没有 Docker 服务；容器构建、GHCR 发布及公开匿名拉取由该仓库工作流执行，本机 ADB 联调连接不作为长期服务。

## 动作素材

内置 416 项中文力量动作、23 项拉伸及实际图解。训练与拉伸使用插画或分帧图，不要求训练时访问第三方网站。

- [Hevy](https://hevy.com/)：公开动作目录、中文步骤与动作缩略图。
- [RepDB](https://exercise-dataset.com/)：拉伸插画和动作资料。
- [Workout Guide](https://github.com/bryllim/workout-guide)：小腿拉伸 SVG 分帧。
- [react-body-highlighter](https://github.com/giavinh79/react-body-highlighter)：正背面肌肉示意轮廓。

每个媒体文档保留实际来源及下载地址；动作标识不依赖图片文件。`tools/download-media.mjs` 可以准备目录中声明的实际素材，使用原生 `curl`，需要代理时显式传入 `--proxy`。`--prune` 仅清理 `catalog/media` 中未被目录引用的文件，不操作个人数据库或上传图片。
