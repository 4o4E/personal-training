# 个人训练

Android 原生训练应用、自有 Ktor 后端、SQLite 存储和项目级训练操作 skill。Android 8.0 及以上；不提供 iOS 或浏览器界面，后续浏览器继续遵守只读数据边界。

启动器图标以 `branding/app-icon.svg` 为图形来源；正式版应用名称为“个人训练”，验证版为“个人训练·验证”。

## 手机操作

1. 首次进入按教程配置后端或稍后连接；已有资料的用户可继续使用。升级前保留的本机选择在离线时继续可用；首次成功读取云端后，云端已有安排优先，云端尚无安排时再迁移旧本机选择。离线期间若同一安排又被云端修改，两份内容保留并需在设置中明确选择冲突。
2. 在「调整计划」配置整轮及当前方案，比较三、四、五分化候选，也可在「全部计划与日期安排」维护自定义 N 分化、独立计划与指定日期安排。每个分化训练项明确选择要使用的计划；确认后先保存到本机安排，联网后同步云端。已安排的指定日期计划可在开始页作为下一训练执行；独立安排可从全部计划列表单独开始，循环模板须先选入当前安排。
3. 候选可选力量动作数量与组数，预览动作顺序、部位、器械和目标；可添加拉伸并指定侧别、次数或时长及顺序。确认前可返回修改或取消，不会提前保存。
4. 「开始训练」优先继续正在进行的训练；否则只显示当前已安排的下一训练项（包括仍在本机、待同步的安排）。没有可执行安排时前往「调整计划」，训练页不提供多方案选择或计划编排。完成指定的下一部位后按该项继续轮换，取消不消耗指定安排。
5. 修改当前训练时，优先从「调整计划 → 调整本次剩余内容」进入；训练页也保留快捷入口。替换或加入的仅是未完成动作和拉伸，已完成组及实际读数保留。未来计划编辑只影响之后的训练。
6. 每组记录实际读数后保存并自动休息，原始 kg / lb 与负重含义保持不变；修正已完成组不重启计时。导航离开再返回不重置读数或休息截止时间。
7. 「运动记录」以日历显示至少完成一组的日期；点日期进入独立每日记录，返回时保留查看的月份。云端已同步及本机待上传记录在日历和详情中使用相同日期与实际结果。
8. 从左上角菜单进入独立「设置」页面，系统或页面返回均回到原位置。设置集中管理外观、后端同步与冲突、通知权限、导入导出及更新；休息中切换设置不会改变计时。
9. 健身房和器械可从菜单选填；动作教学可在计划预览、动作选择与训练中查看。

候选保留可对应的最近实际读数，不强制转换 kg / lb。未填写负重不等于零重量；更换具体器械后需要确认该台器械的实际显示。拉伸不套用力量动作的统一组数。

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
.\gradlew.bat -PreleaseVersion=0.0.3 :server:test :server:distZip :android:assembleRelease
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

在首次教程或「左上角菜单 → 设置」填写手机实际能够访问的后端地址及 token，点击保存并同步。不要填写服务器自己的 `localhost`；调试中的 ADB 端口转发不等同于长期可用的网络部署。公网使用 HTTPS 反向代理，不通过日志或聊天发送 token。

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

手机「左上角菜单 → 设置」可以导出和导入 ZIP；接口和 CLI 使用相同格式。导出使用备份格式 v2，并可读取 v1/v2。ZIP 包含版本化 `data.json` 与实际图片，覆盖动作、器械、分化、计划、云端训练安排及实际记录；不包含后端 token、手机授权或连接配置，也不包含教程与外观等设备偏好。

默认合并不替换已有不同内容，需要替换时必须明确选择。迁移到另一套后端时先导出完整资料，再导入目标后端，最后修改手机连接；不要将计划要求当作实际完成量。

从 `0.0.2` 升级时，先备份现有资料；客户端与配套后端都更新至 `0.0.3` 后，才能导入包含新历史格式的数据。`0.0.2` 客户端不能读取新历史格式，导入后不要再用 `0.0.2` 客户端打开该资料。

## 提醒条件

休息与拉伸计时由前台服务按持久化截止时间持续运行；倒计时期间保持 CPU 唤醒但不点亮屏幕。休息到时发送一次普通通知，页面及通知改为从原截止时间正向累计的额外休息秒数；拉伸到时显示计时结束，不转为额外休息。结束休息、完成下一组或结束训练时，计时通知会清除或由新计时替换。

额外休息阶段释放 CPU 唤醒锁；熄屏不逐秒刷新通知，亮屏后从原截止时间恢复显示。计时依赖本机状态，不要求连接后端；到时不会自动记为完成一组。应用被强行停止或手机重启后的自动恢复不属于当前计时支持范围。

通知授权影响系统通知能否显示；精确定时权限用于系统唤醒能力。通知提示音、振动和勿扰均遵循系统及通知渠道设置，不使用闹钟铃声或全屏提醒。Android 13 的一加 9R 使用普通通知；Android API 36 及以上可在系统允许时请求 Live Updates，是否实际提升以系统状态为准。支持设备收起状态的持续计时展示仍待实机验证，普通通知不代表该能力已通过验收。

## 实际验证

使用 Windows 原生命令、JDK 21 和 Android 13 的 LE2101 真机完成验证；以下数字来自独立软件验证资料，不是个人训练记录。

- 安卓 APK 与后端运行程序已成功构建，APK 已实际安装并打开；真实目录与 SQLite 行为回归共 12 项通过。
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

`0.0.2` 使用独立应用标识完成整轮候选、四动作三组、单日三动作两组、同一训练的原始 kg/lb、取消及完成轮换、再次使用原计划、自定义二分化和指定日期只安排一次的真机操作。单日修改前后，其他训练项及进行中内容保持一致；不同方案即使复用训练项标识，也分别记住选用计划。

真机实际维护可选健身房器械：缺少训练凳时不能进入所选哑铃方案的确认预览，补充器械并同步后恢复可用。已有连接和进行中内容覆盖安装后保留，全新安装的教程明确跳过后不强制重复，菜单可重看。跟随系统及手动浅深外观、日期弹窗、教学图解和普通通知均已实际查看或触发。

独立软件验证资料包含 10 条训练记录、8 个实际操作完成的力量组。暂停后端仍能记录，恢复后同步成功；手机导出与合并导入、CLI 导出及独立后端恢复均执行成功。928 份业务内容与 442 份素材逐项一致，原始 kg/lb 和开始时内容保持，备份不包含连接 token 或本机交互偏好。

`v0.0.2` 的实际 [正式发布 CI](https://github.com/4o4E/personal-training/actions/runs/39769037369) 通过签名 APK 与后端同版本构建、Release 发布、复用 Release 后端包构建镜像、容器启动及鉴权、匿名镜像拉取；[普通提交 CI](https://github.com/4o4E/personal-training/actions/runs/39769036916) 仅执行检查。已下载公开 APK，核对应用标识、`0.0.2`／内部版本 `3`、更新清单 SHA-256，以及与 `0.0.1` 相同的签名证书。公开后端 ZIP 通过 Windows 启动脚本运行，健康请求为 200、未鉴权请求为 401、有效 token 请求为 200。发布时公开最新版本入口指向 [v0.0.2](https://github.com/4o4E/personal-training/releases/tag/v0.0.2)，镜像版本标签与 `latest` 摘要一致。

当前计时实现已在一加9R的独立验证应用完成休息计时验收：前台及桌面出现顶部普通提醒，熄屏到时同样发布通知，五次休息通知发出时间比手机记录的截止时间晚38–50毫秒。页面与通知在零点后显示正向额外休息，亮屏恢复、切换设置和再次返回不重复提醒；提前结束、完成下一组和结束训练会清除或替换旧计时。拉伸到时仅显示结束，完成组数不变。倒计时期间持有CPU唤醒锁，到时释放；熄屏的额外休息停止逐秒刷新。支持设备的原生收起状态仍待独立验收，尚无该项实际可见证据。

## 动作素材

内置 416 项中文力量动作、23 项拉伸及实际图解。训练与拉伸使用插画或分帧图，不要求训练时访问第三方网站。

- [Hevy](https://hevy.com/)：公开动作目录、中文步骤与动作缩略图。
- [RepDB](https://exercise-dataset.com/)：拉伸插画和动作资料。
- [Workout Guide](https://github.com/bryllim/workout-guide)：小腿拉伸 SVG 分帧。
- [react-body-highlighter](https://github.com/giavinh79/react-body-highlighter)：正背面肌肉示意轮廓。

每个媒体文档保留实际来源及下载地址；动作标识不依赖图片文件。`tools/download-media.mjs` 可以准备目录中声明的实际素材，使用原生 `curl`，需要代理时显式传入 `--proxy`。`--prune` 仅清理 `catalog/media` 中未被目录引用的文件，不操作个人数据库或上传图片。
