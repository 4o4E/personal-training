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
9. 「设置 → 应用更新」检查最新正式版本，查看更新说明并下载。下载可以在离开设置后继续；完成后点击安装，通过安卓系统确认覆盖更新，保留原有资料。首次安装来源未授权时，允许「个人训练」安装应用，返回后再次点击安装。

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

正式签名 APK 与后端 ZIP 可从 [Release](https://github.com/4o4E/personal-training/releases/latest) 下载，无需编译。后端 ZIP 包含 `bin/`、依赖和动作素材，解压后使用 JDK 21 运行 `bin/server`（Linux）或 `bin/server.bat`（Windows）。

正式版本从 `v0.0.1` 开始，标签为 `vMAJOR.MINOR.PATCH`。本地正式构建设置 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 后执行：

```powershell
.\gradlew.bat -PreleaseVersion=0.0.1 :server:test :server:distZip :android:assembleRelease
```

内部版本号为 `MAJOR × 1000000 + MINOR × 1000 + PATCH + 1`，次版本与修订号不超过 999。签名必须使用同一份密钥才能覆盖安装；密钥在仓库之外备份，不提交到 Git，CI 使用三个对应的签名 Secrets（密钥文件为 `ANDROID_KEYSTORE_BASE64`）。

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

源码、GitHub Release 与 GHCR 镜像均公开，工作流位于根目录 `.github/workflows/publish-training-image.yml`。默认分支提交及拉取请求只检查应用与后端；推送 `vMAJOR.MINOR.PATCH` 标签才正式发布，普通提交不更新 Release 或镜像。

正式发布在同一次 Gradle 执行中完成后端行为回归、签名 APK 与后端 ZIP，共享模块只构建一次并复用 Gradle 缓存。APK、后端 ZIP 和 `update.json` 发布到同一个 Release；随后镜像任务下载该 Release 的后端 ZIP，仅封装 JRE 和原始 JAR，Docker 内不包含 JDK、源码或 Gradle，也不重复编译。镜像保持框架依赖、教学素材和业务代码分层，通过实际启动与鉴权检查后推送版本标签和 `latest`。

### 镜像访问

公开镜像为 `ghcr.io/4o4e/personal-training:latest`，指定版本使用如 `ghcr.io/4o4e/personal-training:v0.0.1`。APK、更新资料、后端 ZIP 及镜像均无需登录 GitHub 即可下载或拉取。

公开的是可运行镜像，个人训练数据保存在部署服务器的数据卷；训练接口仍使用自己的唯一 token。CI 每次发布后使用空的 Docker 登录配置验证匿名拉取。

### 部署与更新

1. 将 `training-app/compose.yml` 复制到服务器的固定部署目录，无需复制源码或填写镜像仓库参数。
2. 在部署目录执行以下命令，无需 `docker login`：

```sh
docker compose pull
docker compose up -d
```

以后更新也执行这两条命令。Compose 始终拉取 `ghcr.io/4o4e/personal-training:latest`，资料位于 `training-data` 命名卷，不需要服务器本地构建。服务以非 root 用户运行，默认仅发布到服务器 `127.0.0.1:8080`，由 HTTPS 反向代理提供手机可访问的地址；家庭网络直连时需按实际网络修改端口绑定。

不配置 `TRAINING_TOKEN` 时，服务首次启动会自动生成唯一 token 并保存到 `/data/token`。在服务器本地取得后填入手机「设置」，不将其放进镜像、发布参数或聊天；持久卷保留数据库、素材和 token，更新镜像不清空资料。

容器运行用户为 `10001:10001`。预置配置使用命名卷；如果自行改为 `./training-data:/data` 这样的宿主目录绑定，需要先确保该目录及已有数据库文件归该用户所有，否则后端不能创建或更新 SQLite 数据库。健康检查地址为 `/health`，根路径不是网页首页。

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

已有实际 GitHub Actions 运行通过容器构建、服务端回归、启动、鉴权、Compose 和未登录镜像拉取检查。源码仓库为公开的 [4o4E/personal-training](https://github.com/4o4E/personal-training)，正式 APK、后端及镜像按版本发布；服务器由用户手动部署，本机 ADB 联调连接不作为长期服务。

`v0.0.1` 的实际 [正式发布 CI](https://github.com/4o4E/personal-training/actions/runs/39733130505) 已通过 APK 和后端同版本构建、Release 发布、Release 运行包组装镜像、容器启动及鉴权、匿名镜像拉取；[普通提交 CI](https://github.com/4o4E/personal-training/actions/runs/39733077733) 只执行检查，镜像任务跳过。公开产物位于 [v0.0.1 Release](https://github.com/4o4E/personal-training/releases/tag/v0.0.1)。

真机从带更新入口的 `0.0.0`（内部版本 1）读取真实公开 Release，后台下载、重新打开设置、校验、系统授权、取消安装后再次安装，最终升级为 `0.0.1`（内部版本 2）。自建验证资料保留，再次检查显示已是最新版本；损坏的安装包被拒绝，重新下载后正常安装。正式版完成组后返回桌面，系统实际发布普通 `reminder` 通知。自建验证训练、计划、健身房及临时连接已删除，历史和待同步均恢复为空。

从该 Release 匿名下载的后端 ZIP 已在 Windows 解压并通过其 `bin/server.bat` 实际启动；健康路由为 200，无 token 请求为 401，有效 token 请求为 200。验证目录、端口转发和本机服务不作为生产部署。

## 动作素材

内置 416 项中文力量动作、23 项拉伸及实际图解。训练与拉伸使用插画或分帧图，不要求训练时访问第三方网站。

- [Hevy](https://hevy.com/)：公开动作目录、中文步骤与动作缩略图。
- [RepDB](https://exercise-dataset.com/)：拉伸插画和动作资料。
- [Workout Guide](https://github.com/bryllim/workout-guide)：小腿拉伸 SVG 分帧。
- [react-body-highlighter](https://github.com/giavinh79/react-body-highlighter)：正背面肌肉示意轮廓。

每个媒体文档保留实际来源及下载地址；动作标识不依赖图片文件。`tools/download-media.mjs` 可以准备目录中声明的实际素材，使用原生 `curl`，需要代理时显式传入 `--proxy`。`--prune` 仅清理 `catalog/media` 中未被目录引用的文件，不操作个人数据库或上传图片。
