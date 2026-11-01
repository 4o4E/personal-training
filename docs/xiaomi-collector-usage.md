# 小米运动健康数据使用指南

本项目以 Kotlin JVM Gradle 模块 `:collector` 采集小米运动健康数据，保存原始响应并解析后写入本地 SQLite。数据可用于查询日常活动、睡眠、心率、体成分和运动明细；不生成训练计划，也不包含 MCP 服务。

## 目录与命令

- Kotlin 采集器模块：`collector/`；从项目根构建安装分发包。
- 数据库 schema：`collector/schema.sql`。
- 本机数据默认根目录：项目根的 `data/xiaomi/`；需要更换位置时设置 `XIAOMI_ROOT`。
- 私有 SQLite 数据库：`data/xiaomi/data/misports.db`；WAL/SHM 文件与数据库位于同一目录。
- 私有原始响应：`data/xiaomi/data/raw/`。
- 私有凭据文件：`data/xiaomi/credentials.json` 和 `data/xiaomi/cookie.txt`。
- [维护指南](xiaomi-collector-maintenance.md)：备份、升级与故障排查。

从项目根目录执行以下 PowerShell 命令。首次或更新后先构建本机发行版：

```powershell
.\gradlew.bat :collector:installDist
```

本机 CLI 位于 `collector\build\install\collector\bin\collector.bat`。默认从项目根目录下的 `data/xiaomi/` 读取和写入私有数据；需要使用其他私有目录时，在运行命令前设置：

```powershell
$env:XIAOMI_ROOT = 'D:\private\xiaomi'
```

不要把 `XIAOMI_ROOT` 指向仓库外任何含凭据的共享或公开目录。PowerShell 本机命令如下：

```powershell
.\collector\build\install\collector\bin\collector.bat --help
.\collector\build\install\collector\bin\collector.bat stats
```


`sync` 的选项以 `--help` 输出为准。服务器容器命令从包含 `compose.yml` 的项目根目录执行，见本指南“服务器采集”。

## 凭据

凭据文件为 `credentials.json` 和 `cookie.txt`。采集器选择修改时间较新的可用凭据：

### cookie.txt

从已经登录小米账号的浏览器导出，内容是一行 cookie：

```text
userId=1234567890; passToken=V1:......
```

必须同时包含 `userId` 和 `passToken`。可以从浏览器开发者工具的 Application → Cookies → `https://account.xiaomi.com` 复制。

### credentials.json

登录凭据由采集器维护：

```json
{
  "user_id": "1234567890",
  "pass_token": "V1:......"
}
```

登录小米云端时会轮换 `passToken`，采集器将更新后的值写回 `credentials.json`。除非 token 已失效且确认了新值，否则不要手动修改。

`cookie.txt`、`credentials.json` 和 `data/` 都已加入 `.gitignore`。不要提交，也不要发给别人。

## 初始化与本机采集

从项目根目录初始化数据库：

```powershell
.\collector\build\install\collector\bin\collector.bat init
```

默认数据库位于 `data/xiaomi/data/misports.db`。已有原始抓取目录可重复导入；将示例中的 `<时间戳>` 替换为实际原始目录名：

```powershell
.\collector\build\install\collector\bin\collector.bat import-raw '.\data\xiaomi\data\raw\<时间戳>'
```

`import-raw` 的原始目录包含 `meta.json`、`fitness/<key>/all.json`、`sport_records/all.json`，以及可用的 `fds/<编号>/meta.json`、`record.bin` 和 `gps.bin`。保留原始响应与 FDS 二进制；重复导入使用稳定主键更新，不应产生重复行。

手动同步及查看可用参数：

```powershell
.\collector\build\install\collector\bin\collector.bat sync
.\collector\build\install\collector\bin\collector.bat --help
```

同步时间范围、重扫选项以当前 `--help` 输出为准。原始响应保存在 `data/xiaomi/data/raw/<时间戳>/`，数据库中的 `raw_captures` 记录相对路径、记录数和抓取时间。解析失败时保留原始响应供排查和重导，不要先删除原始目录。

若更换数据目录，在上述命令前设置 `XIAOMI_ROOT`；未设置时使用项目根的 `data/xiaomi/`。数据库、原始响应和凭据应一并纳入私有备份。

## 查看状态

```powershell
.\collector\build\install\collector\bin\collector.bat stats
```

状态信息包括各表行数、步数/心率/睡眠/体成分/运动日期范围、各数据集最后同步时间与覆盖范围，以及运动 FDS 样本和轨迹点数。

`sync_state` 里的时间是 Unix 秒，可以在 SQL 里转换成本地时间：

```sql
SELECT dataset, data_key,
       datetime(last_synced_at, 'unixepoch', '+8 hours') AS last_sync_local,
       datetime(coverage_start, 'unixepoch', '+8 hours') AS coverage_start_local,
       datetime(coverage_end, 'unixepoch', '+8 hours') AS coverage_end_local
FROM sync_state;
```

## 服务器采集

从包含 `compose.yml` 的项目根目录执行容器命令。先发布源数据库中已有历史，再进行云端采集：

```sh
docker compose run --rm collector publish
docker compose run --rm collector run
```

`publish` 用于向后端提交已有资料；`run` 执行服务器采集。历史重扫可使用：

```sh
docker compose run --rm collector rescan --from 2026-10-01 --to 2026-10-31
```

具体参数以当前容器发行说明为准。服务器持续采集后不要在个人电脑同时运行自动采集。服务器状态与后端资料保存在持久化 `/data`；备份和回退约束见[维护指南](xiaomi-collector-maintenance.md)。

## 数据覆盖范围

实际覆盖日期随账号、设备同步情况和采集成功状态变化，不以本指南中的固定日期推断数据完整性。使用采集器 `stats` 命令检查当前数据库的日期范围和各数据集同步状态；云端只包含已上传的数据。

是否存在某类记录取决于账号、设备与服务端返回内容；未观察到记录不等同于数值为零，也不应据此补造数据。
## 数据口径

### 时间

- `ts` 是 Unix 秒，UTC。
- `date_local` 是 UTC+8 的日期，按天分组时直接用它。
- 睡眠的 `date_local` 使用起床日期。

### 步数和热量

`step_minutes` 和 `calorie_minutes` 是两套独立数据，不能相加：

- `step_minutes`：分钟级步数、距离、计步相关热量。
- `calorie_minutes`：分钟级活动热量，数值通常高于计步热量。

手机和手环会对同一分钟重复上报。导入时按分钟去重：保留步数更大的记录；步数相同时保留距离更大的记录。`step_minutes` 每分钟只有一行。

### 心率

- `heart_rate_samples` 是全天心率采样，可能不均匀；以实际记录中的时间和 `sample_type` 判断覆盖范围。
- `sample_type` 为 `passive` 或 `active`。日常采样不能替代运动期间的连续样本。
- 运动期间的秒级心率在 `activity_samples` 里。没有开始运动模式的力量训练不会产生秒级心率，短时心率峰值可能被日常采样漏掉，不能用于判断每组训练的强度。

### 睡眠

- `sleep_sessions` 存每次睡眠或午睡的摘要；按实际来源字段判断午睡标记和得分是否可用。
- `sleep_stages` 存分期，`stage` 取值 `deep`、`light`、`rem`、`awake`、`unknown`。

### 运动

- `activities` 是运动摘要，只在手动开始运动模式时产生。
- `activity_samples` 是秒级样本：心率、步频、距离、卡路里、步幅。
- `activity_track_points` 是 GPS 点：经纬度、精度、速度、海拔、HDOP。
- `distance_m` 已从原始 0.1 米单位换算成米。
- `activity_samples.calories_kcal` 是每秒增量，求和等于运动总卡路里。
- 跑步和步行的 `avg_pace_s_per_km` 在云端没有 `avg_pace` 时按 `duration_s / (distance_m / 1000)` 计算。
- 游泳的 `avg_pace_s_per_100m` 按 `duration_s / (distance_m / 100)` 计算，和跑步的每公里配速分开存储。
- 日常步行不会生成 `activities`，只进入 `step_minutes`。

### 单位

| 字段 | 单位 |
|---|---|
| `distance_m` | 米 |
| `duration_s` | 秒 |
| `avg_pace_s_per_km` | 秒/公里，跑步和步行 |
| `avg_pace_s_per_100m` | 秒/100 米，游泳 |
| `avg_cadence` | 步/分钟 |
| `avg_stride_cm` | 厘米 |
| `stroke_count` | 划水次数 |
| `avg_swolf`、`best_swolf` | SWOLF 值 |
| `turn_count` | 转身次数 |
| `calories_kcal` | 千卡 |
| `weight_kg` | 公斤 |
| `body_fat_pct` | 百分比 |
| `bpm` | 次/分钟 |

## 常用 SQL

### 每日活动

```sql
SELECT date_local,
       steps,
       distance_m,
       step_calories_kcal,
       total_calories_kcal
FROM v_daily_activity
ORDER BY date_local DESC
LIMIT 30;
```

### 按月汇总步数

```sql
SELECT substr(date_local, 1, 7) AS month,
       SUM(steps) AS steps,
       ROUND(SUM(distance_m) / 1000.0, 1) AS distance_km
FROM v_daily_activity
GROUP BY month
ORDER BY month;
```

### 每日睡眠

```sql
SELECT date_local,
       sessions,
       duration_min,
       deep_min,
       light_min,
       rem_min,
       awake_min,
       avg_hr
FROM v_daily_sleep
ORDER BY date_local DESC
LIMIT 30;
```

### 运动摘要

```sql
SELECT date_local,
       sport_key,
       distance_m,
       duration_s,
       avg_hr,
       max_hr,
       avg_cadence,
       steps
FROM activities
ORDER BY start_ts DESC;
```

### 跑步配速

```sql
SELECT date_local,
       distance_m,
       duration_s,
       ROUND(avg_pace_s_per_km / 60.0, 2) AS pace_min_per_km,
       avg_hr,
       steps
FROM activities
WHERE sport_key = 'outdoor_running'
ORDER BY start_ts;
```

### 游泳摘要

```sql
SELECT date_local,
       distance_m,
       duration_s,
       ROUND(avg_pace_s_per_100m / 60.0, 2) AS pace_min_per_100m,
       stroke_count,
       avg_swolf,
       best_swolf,
       turn_count
FROM activities
WHERE sport_key = 'pool_swimming'
ORDER BY start_ts;
```

### 每公里分段配速

```sql
WITH samples AS (
  SELECT ts,
         distance_m,
         SUM(distance_m) OVER (ORDER BY ts) AS cumulative_m
  FROM activity_samples
  WHERE activity_id = 1
)
SELECT CAST((cumulative_m - distance_m) / 1000 AS INTEGER) + 1 AS km,
       MIN(ts) AS start_ts,
       MAX(ts) AS end_ts,
       ROUND(SUM(distance_m), 1) AS segment_m,
       ROUND(
         (MAX(ts) - MIN(ts)) / NULLIF(SUM(distance_m) / 1000.0, 0),
         1
       ) AS pace_s_per_km
FROM samples
GROUP BY km
ORDER BY km;
```

### 运动心率曲线

```sql
SELECT ts, hr, cadence, distance_m, calories_kcal
FROM activity_samples
WHERE activity_id = 1
ORDER BY ts;
```

### GPS 轨迹

```sql
SELECT seq, ts, lat, lon, speed_mps, accuracy_m, altitude_m
FROM activity_track_points
WHERE activity_id = 1
ORDER BY seq;
```

### 体成分

```sql
SELECT date_local,
       weight_kg,
       body_fat_pct,
       muscle_mass_kg,
       skeletal_muscle_mass_kg,
       visceral_fat,
       basal_metabolism_kcal,
       body_score
FROM body_measurements
ORDER BY ts DESC;
```

### 最近 4 周和前 4 周步数对比

```sql
WITH periods AS (
  SELECT 'recent' AS period,
         date('now', '-27 days') AS start_date
  UNION ALL
  SELECT 'previous',
         date('now', '-55 days')
)
SELECT p.period,
       SUM(a.steps) AS steps,
       ROUND(SUM(a.distance_m) / 1000.0, 1) AS distance_km
FROM periods p
JOIN v_daily_activity a
  ON a.date_local >= p.start_date
 AND a.date_local < date(p.start_date, '+28 days')
GROUP BY p.period;
```

## 数据表

| 表 | 内容 |
|---|---|
| `step_minutes` | 分钟级步数、距离、计步热量 |
| `calorie_minutes` | 分钟级活动热量 |
| `heart_rate_samples` | 全天心率采样 |
| `resting_heart_rate` | 每日静息心率 |
| `sleep_sessions` | 睡眠摘要 |
| `sleep_stages` | 睡眠分期 |
| `body_measurements` | 体脂秤测量 |
| `activities` | 运动摘要 |
| `activity_samples` | 运动秒级样本 |
| `activity_track_points` | 运动 GPS 轨迹 |
| `sync_state` | 每个数据集的同步状态 |
| `raw_captures` | 原始文件索引 |

`raw_captures` 的 `path` 是相对项目根目录的路径。原始 JSON 和二进制不进入数据库，数据库只保存解析后的结构化数据。

## 常见问题

### 为什么步数和 App 显示的不一样

App 会合并手机和手环的数据。原始云端记录包含两个来源的分钟切片，直接求和会重复计算。数据库按分钟去重，保留步数更大的记录；步数相同时保留距离更大的记录。

### 为什么运动记录和日常步数对不上

只有手动开始运动模式才会生成运动记录。日常走路、通勤通常进入分钟级步数数据，不一定形成 `activities` 运动摘要；具体覆盖取决于设备和来源。

运动摘要与明细是否存在取决于来源运动类型及采集器解析支持情况。查看 `fds_status` 和已保存的原始响应；需要重试解析时保留原始目录并使用 `collector.bat import-raw <目录>`。

健康数据可能缺少某些测量类别或字段；以当前采集器支持范围和实际来源响应为准，不将缺失解释为零。

睡眠得分等字段可能不由云端提供；如字段为空，不能据此推算得分。

### sync 报 70016 或登录失败

`passToken` 可能已过期。重新登录小米账号，更新私有 `cookie.txt`，再按当前采集器帮助运行 `sync`。不要在聊天或日志中粘贴凭据。

### 原始数据占空间

`data/xiaomi/data/raw/` 保存每次同步的原始响应。数据库查询不依赖它，但它是字段变化时的兜底；保留与清理注意事项见[维护指南](xiaomi-collector-maintenance.md)。

## 安全

- `credentials.json` 和 `cookie.txt` 等同账号凭证。
- `data/` 包含个人健康数据。
- 两者都已加入 `.gitignore`。
- 不要把数据库、原始数据或凭据提交到公开仓库。
