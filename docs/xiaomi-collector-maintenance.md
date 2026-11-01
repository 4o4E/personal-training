# 小米运动健康数据维护指南

## 日常维护

以下 PowerShell 操作从项目根目录执行。先构建本机 Kotlin JVM 采集器发行版：

```powershell
.\gradlew.bat :collector:installDist
```

默认私有数据根目录为 `data/xiaomi/`，其中 SQLite、原始响应与凭据均在私有目录内。需要更换目录时，运行命令前设置 `XIAOMI_ROOT`：

```powershell
$env:XIAOMI_ROOT = 'D:\private\xiaomi'
```

查看状态或执行默认同步：

```powershell
.\collector\build\install\collector\bin\collector.bat stats
.\collector\build\install\collector\bin\collector.bat sync
```

同步后检查各表行数、覆盖日期范围与 `sync_state` 的最后同步时间。若日期范围没有推进，查看采集器输出，再检查手机上的小米运动健康是否完成同步；云端只包含已经上传的数据。

服务器容器由项目根目录的 Compose 服务独立运行：

```sh
docker compose run --rm collector publish
docker compose run --rm collector run
docker compose run --rm collector rescan --from 2026-10-01 --to 2026-10-31
```

线上持续采集期间不要在个人电脑同时运行自动采集。维护前应确认目标服务与持久目录，并按部署方案安排停写和备份。

## 凭据维护

### passToken 轮换

每次调用 `serviceLogin` 都会返回新的 `passToken`。`sync` 成功后会把新值写入 `credentials.json`，不需要手动处理。

### 凭据优先级

采集器读取两个文件，选择修改时间较新的那个：

- `credentials.json`
- `cookie.txt`

`sync` 会更新 `credentials.json`；重新登录后手动更新 `cookie.txt` 时，采集器使用更新的凭据。

### token 过期

出现以下情况时，`sync` 会明确报错：

- 返回 `70016`
- 返回 401 或 403
- 登录响应缺少 `ssecurity` 或 `location`
- 提示 `passToken` 失效

处理步骤：

1. 在浏览器登录 `https://account.xiaomi.com/`。
2. 打开开发者工具的 Application → Cookies → `https://account.xiaomi.com`。
3. 复制 `userId` 和 `passToken`。
4. 覆盖 `cookie.txt`，格式为 `userId=...; passToken=...`。
5. 重新运行 `sync`。

不要把 token 发到聊天、日志或公开仓库。

### 密码登录报错

小米账号密码登录可能触发风控。优先使用手机号加短信验证码登录，或者先在小米手机、官网完成安全验证。连续输错密码会临时冻结账号。

## 原始数据维护

### 原始数据的用途

`data/xiaomi/data/raw/<时间戳>/` 保存：

- 每个 key 的每页云端响应。
- 合并后的 `all.json`。
- 运动记录原始响应。
- FDS 的 `record.bin`、`gps.bin` 和下载响应。
- 本次同步的 `meta.json`。

数据库只保存解析后的数据。原始文件用于：

- 云端字段变化时回溯。
- 重新导入，不需要再次请求云端。
- 核对解析结果。

`raw_captures` 表记录原始文件的相对路径、记录数和抓取时间。

### 空间增长

每次 `sync` 都会新建一个时间戳目录。步数、心率、睡眠的 JSON 体积不大，FDS 二进制是主要占用：

FDS 二进制通常是原始响应目录中较大的内容。长时间范围回填会产生多个分段目录。

定期从项目根目录检查：

```powershell
Get-ChildItem .\data\xiaomi\data\raw -Directory | Measure-Object
Get-ChildItem .\data\xiaomi\data -Recurse | Measure-Object Length -Sum
```

### 清理策略

数据库查询不依赖原始文件。可以删除旧目录，但删除后失去对应时间段的回溯能力。

建议：

- 至少保留最近一次完整历史回填目录。
- 删除前先备份数据库。
- 不要删除仍被 `raw_captures` 引用且需要回溯的目录。
- 人工审查后再清理，不假设有自动清理。

删除示例（从项目根目录执行）：

```powershell
# 先确认目录内容
Get-ChildItem .\data\xiaomi\data\raw

# 仅删除明确不需要的一个时间戳目录
Remove-Item -Recurse .\data\xiaomi\data\raw\20261018-170023
```

删除原始文件不会删除数据库中的结构化数据。

### 从原始数据重建数据库

如果数据库损坏或需要调整解析逻辑，先停止写入并备份整个 `data/xiaomi/`，再从项目根目录执行：

```powershell
Remove-Item .\data\xiaomi\data\misports.db
.\collector\build\install\collector\bin\collector.bat init

Get-ChildItem .\data\xiaomi\data\raw -Directory | ForEach-Object {
    .\collector\build\install\collector\bin\collector.bat import-raw $_.FullName
}
```

`import-raw` 可重复执行，主键冲突会更新；重建数据库前确保原始目录和凭据均有私有备份。

## 数据库维护

### WAL 模式

数据库启用了 WAL。正常运行时可能看到：

```text
data/xiaomi/data/misports.db
data/xiaomi/data/misports.db-wal
data/xiaomi/data/misports.db-shm
```

备份或移动数据库前先合并 WAL：

```sql
PRAGMA wal_checkpoint(TRUNCATE);
```

### 完整性检查

```sql
PRAGMA integrity_check;
```

正常结果是 `ok`。如果返回错误，停止写入，先备份当前私有数据，再从 `data/xiaomi/data/raw/` 重建。

### 备份

不要直接复制正在写入的 SQLite 文件。使用 SQLite 在线备份接口；例如已安装 `sqlite3` CLI 时，从项目根目录运行：

```powershell
New-Item -ItemType Directory -Force .\backups
sqlite3 .\data\xiaomi\data\misports.db ".backup '.\backups\misports-YYYY-MM-DD.db'"
```

同时备份整个 `data/xiaomi/data/raw/`，否则数据库中的 `raw_captures` 路径会指向不存在的文件；凭据也必须作为私有资料单独保护。

### 恢复

停止所有采集进程后，恢复数据库及原始数据到私有目录：

```powershell
Copy-Item .\backups\misports-YYYY-MM-DD.db .\data\xiaomi\data\misports.db -Force
```

恢复后启动采集器并检查：

```powershell
.\collector\build\install\collector\bin\collector.bat stats
```

### 空间回收

删除大量原始数据或行之后，SQLite 文件不会立即缩小。执行：

```sql
VACUUM;
```

`VACUUM` 需要足够的临时空间，执行期间不要同时运行 `sync`。

### 统计信息

大量导入后可以执行：

```sql
ANALYZE;
```

帮助查询优化器选择索引。

### 表结构变更

当前没有自动迁移框架。修改表结构时按以下顺序：

1. 备份 `data/xiaomi/data/misports.db` 和 `data/xiaomi/data/raw/`。
2. 修改 [`collector/schema.sql`](../collector/schema.sql)。
3. 同步更新采集器的数据库读写实现，必要时增加兼容迁移。
4. 用 `import-raw` 重放原始数据，确认新字段。
5. 运行 `PRAGMA integrity_check` 和 `stats`。

不要直接删除列。SQLite 删除列需要重建表，先确认没有查询依赖。

## 采集故障排查

### 登录失败

检查顺序：

1. `cookie.txt` 是否同时包含 `userId` 和 `passToken`。
2. `credentials.json` 是否是最近一次成功同步写入的。
3. 两个文件是否存在（不要只看文件名，确认内容不为空）。
4. 浏览器是否仍然登录同一个小米账号。
5. 是否触发了短信验证或安全验证。

### 返回 429 或 5xx

遇到超时、网络错误、429 或 5xx 时，查看本次采集输出及已保存的原始响应。连续失败时：

- 降低同步频率。
- 缩短时间范围。
- 等待一段时间再试。

### 分页游标异常

若分页停滞或重复，先缩小同步范围并检查本次输出；不要手工放宽分页上限。

### 运动明细或轨迹缺失

以下情况可能导致没有 `activity_samples`：

- `report_version` 小于等于 0。
- `proto_type` 不在当前支持列表里。
- 记录缺少 `fds_sid` 或 `report_time`。
- 云端没有返回 `record` 文件。

摘要仍可写入 `activities`，FDS 的 `record.bin`、`gps.bin` 等原始响应应保留；用 `fds_status` 区分 `parsed`、`raw_only`、`parse_error` 和 `missing`。

检查运动类型、设备或手机定位权限、私有原始目录中的 `fds/<编号>/gps.bin`，以及云端是否返回 GPS 文件。若后续解析器支持原先未解析的类型，可用 `collector.bat import-raw <目录>` 重放已保存原始响应，不必重新请求摘要。

### 时间戳异常

数据库中的 `ts` 统一是 Unix 秒 UTC，`date_local` 是 UTC+8。不要直接把 `ts` 当成本地时间展示。

如果从外部导入 GPX，需要注意小米 App 导出的 GPX 可能把北京时间标成 `Z`。当前项目的 FDS 路径不使用 GPX，直接使用云端二进制时间戳，不涉及这个偏移。

### 数据库被锁定

确认没有同时运行的 `sync`、`import-raw` 或数据库浏览器写入。WAL 模式下读取不会阻塞写入，但长时间写事务会持有锁。

## 数据质量检查

### 分钟重复

正常情况下 `step_minutes` 每分钟只有一行：

```sql
SELECT minute_ts, COUNT(*) AS n
FROM step_minutes
GROUP BY minute_ts
HAVING n > 1;
```

`calorie_minutes` 同样检查。

### 日期缺口

```sql
WITH RECURSIVE dates(d) AS (
  SELECT MIN(date_local) FROM step_minutes
  UNION ALL
  SELECT date(d, '+1 day')
  FROM dates
  WHERE d < (SELECT MAX(date_local) FROM step_minutes)
)
SELECT d
FROM dates
WHERE d NOT IN (SELECT date_local FROM step_minutes)
ORDER BY d;
```

有心率但没有步数的日期，通常说明手环没有同步或没有佩戴。不要自动补零。

### 运动明细与摘要一致性

```sql
SELECT a.id,
       a.distance_m AS summary_distance,
       ROUND(SUM(s.distance_m), 1) AS sample_distance,
       a.calories_kcal AS summary_calories,
       ROUND(SUM(s.calories_kcal), 1) AS sample_calories,
       a.avg_hr AS summary_avg_hr,
       ROUND(AVG(CASE WHEN s.hr > 0 THEN s.hr END), 1) AS sample_avg_hr
FROM activities a
JOIN activity_samples s ON s.activity_id = a.id
GROUP BY a.id;
```

距离和卡路里应该接近。GPS 累计距离通常比摘要略高，属于漂移。

### 睡眠分期

`deep_min + light_min + rem_min + awake_min` 不一定完全等于 `duration_min`。云端字段本身可能有取整和统计口径差异，不要强行修正。以 `sleep_sessions` 的汇总字段为准，`sleep_stages` 用于查看时间线。

### 心率覆盖

```sql
SELECT date_local, COUNT(*) AS samples
FROM heart_rate_samples
GROUP BY date_local
ORDER BY date_local DESC
LIMIT 30;
```

某天明显偏少时，检查手环佩戴和同步状态。

## 服务器采集操作

从包含 `compose.yml` 的项目根目录手动调用容器：

```sh
docker compose run --rm collector publish
docker compose run --rm collector run
docker compose run --rm collector rescan --from 2026-10-01 --to 2026-10-31
```

`publish` 用于首推已有历史，`run` 执行采集，`rescan` 用于指定历史重扫；重扫参数以当前发行说明为准。不要同时在个人电脑运行自动采集。服务器持久状态及后端资料仍使用 `/data`；执行更新或恢复前先备份并停止写入服务。

## 升级

在项目根目录构建本机发行版并检查可用命令：

```powershell
.\gradlew.bat :collector:installDist
.\collector\build\install\collector\bin\collector.bat --help
.\collector\build\install\collector\bin\collector.bat stats
.\collector\build\install\collector\bin\collector.bat sync
```

升级后先用当前 `--help` 中的小范围同步选项确认认证、分页和解析正常，再执行大范围回填。升级前备份整个 `data/xiaomi/` 私有目录；升级后数据库及原始数据必须保持在原有数据根目录。

## 已知数据边界

- 睡眠得分没有采集。
- 现有资料中没有血氧、压力、异常心跳数据。
- 游泳 FDS 原始文件已保存，但 `proto_type=9` 的现有记录尚未解析，`activity_samples` 为空。
- 运动记录只在手动开始运动模式时产生；日常步行只进入分钟级步数，不进入 `activities`。
- 日常心率多为 `passive` 采样，白天间隔可达 5–10 分钟一条，短时心率峰值可能缺失，不能用于力量训练的单组强度分析。
- 午睡记录的 `is_nap` 目前没有从云端写入 1，`v_daily_sleep` 会把午睡和夜间睡眠相加；分析恢复时取当天最长的一次作为主睡眠。
- GPS 海拔取决于设备和 FDS 版本，可能为空。
- 原始数据不会自动清理；表结构没有自动迁移任务。

## 迁移

换机器时只迁移本机私有资料和项目源码；不需要迁移旧运行环境：

1. 安全复制完整 `data/xiaomi/`，保留其中的 SQLite、原始响应和凭据。
2. 在项目根目录安装 JDK 21 后运行 `.\gradlew.bat :collector:installDist`。
3. 默认数据根为 `data/xiaomi/`；数据目录不同则设置 `XIAOMI_ROOT`。
4. 执行 `collector\build\install\collector\bin\collector.bat stats` 和 `--help` 核对数据路径及当前命令。

不要只复制 `misports.db` 而丢掉 `data/xiaomi/data/raw/`。数据库查询仍然可用，但原始响应的回溯与重导能力会失效。
