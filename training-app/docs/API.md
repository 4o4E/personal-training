# 开放接口

基础路径为 `/v1`。Android 与 `training-assistant` 操作相同的业务文档、同步协议和共享 Kotlin 规则，不直接修改服务器数据库。

## 鉴权与版本

除 `GET /health` 外，所有请求使用 `Authorization: Bearer <唯一 token>`。没有账号、密码、注册或登录接口。公网连接应经过 HTTPS。

文档格式：

```json
{
  "kind": "plan",
  "id": "plan-cat-cow",
  "data": {
    "id": "plan-cat-cow",
    "name": "猫牛式活动",
    "items": [{
      "id": "item-cat-cow",
      "exerciseId": "stretch_cat-cow",
      "sets": [{"reps": 8, "side": "both", "restSeconds": 15}]
    }]
  },
  "revision": 0,
  "deleted": false
}
```

这是计划示例，不是完成记录。新资料建议使用独立 UUID。标识和素材文件名只接受英文、数字、点号、下划线及连字符，长度最多 160，第一位是英文或数字。

- `kind`：`muscle`、`media`、`exercise`、`gym`、`equipment`、`program`、`plan`、`session`、`arrangement`、`activity`。
- `data.id` 必须与文档 `id` 相同；当前安排唯一使用 `arrangement/current`。
- `revision` 是服务器修订号，不是训练日期；不要根据它推断训练时间。
- 保存时提供读取该文档时的 `baseRevision`，新资料使用 `0`。不要为了覆盖冲突自动读取新修订号重试。
- 删除保留修订标记；正在被安排、计划、器械或历史素材使用的资料不能直接删除。

## 路由

| 请求 | 作用 | 返回 |
|---|---|---|
| `GET /health` | 不含个人资料的健康状态 | `{"status":"ok"}` |
| `GET /v1/documents?kind=plan&includeDeleted=false` | 查询一种或全部资料 | `Document[]` |
| `GET /v1/documents/{kind}/{id}` | 读取单份未删除资料 | `Document`，不存在时 404 |
| `PUT /v1/documents/{kind}/{id}` | 提交 `{"document": Document, "baseRevision": number}` | `SyncResponse`；版本冲突为 409 |
| `DELETE /v1/documents/{kind}/{id}?revision=number` | 带修订号删除 | `SyncResponse`；版本冲突为 409 |
| `POST /v1/sync` | 批量提交并拉取修订变化 | `SyncResponse`，逐份返回版本冲突 |
| `GET /v1/capabilities` | 查询互相配套的客户端与数据格式 | `HealthCapabilities` |
| `POST /v1/health/import` | 按单数据集分批幂等提交健康记录 | `HealthImportResult` |
| `GET /v1/health/status` | 读取已覆盖日期、修订和采集状态 | `HealthStatus` |
| `GET /v1/health/days?from=YYYY-MM-DD&to=YYYY-MM-DD` | 读取包含端点的有限范围日汇总 | `HealthDaysResponse` |
| `GET /v1/health/records?date=YYYY-MM-DD&dataset=heartRate&cursor=` | 读取指定日期/数据集的有界详情页 | `HealthRecordsPage` |
| `POST /v1/health/collection/request` | 请求或合并一次服务器采集 | `HealthCollectionRequestResult` |
| `POST /v1/health/collection/state` | 由采集器报告 started/submitted/succeeded/failed | `HealthCollectionState` |
| `GET /v1/history?from=YYYY-MM-DD&to=YYYY-MM-DD` | 按真实 UTC+8 训练日期查询，包含两端日期 | `HistorySummary` |
| `GET /v1/recommendations?type=strength&muscles=chest,triceps&gymId=ID` | 按部位和实际器械选择训练候选 | `Exercise[]` |
| `GET /v1/recommendations?type=stretch&exercises=ID1,ID2&gymId=ID` | 按所选动作的主要及辅助部位推荐拉伸 | `Exercise[]`，不会写入计划 |
| `GET /v1/smartworkout/exercises` | 查询网站来源动作轻量目录 | 来源标识、名称及已知分类/器械 |
| `GET /v1/smartworkout/exercises/{sourceId}/guide` | 不创建动作地查看网站教学 | 来源、线上媒体地址、说明、步骤、提示及常见错误；不存在时 404 |
| `POST /v1/smartworkout/exercises/{sourceId}/select` | 按当前训练或拉伸入口选择来源动作 | 提交 `{"type":"strength"}` 或 `{"type":"stretch"}`；返回确认后的动作 `Document` |
| `GET /v1/exercise-guide/{exerciseId}` | 按现有或已选动作身份读取网站教学 | 同上，不存在时 404 |
| `GET /v1/programs/{id}/next` | 按已完成训练与当前手动选择计算下一项 | `SplitSlot` |
| `GET /v1/files/{name}` | 下载图解或器械图片 | 实际图像字节，缺失时 404 |
| `PUT /v1/files/{name}` | 上传独立图像 | 204；同名同内容可重复，同名不同内容为 409 |
| `GET /v1/export` | 导出完整资料和素材 | ZIP |
| `POST /v1/import?replace=false` | 导入 ZIP，不覆盖已有不同内容 | 已保存文档列表；不同内容为 409 |
| `POST /v1/import?replace=true` | 明确允许替换已有不同内容 | 已保存文档列表 |

缺少有效 token 返回 401；不正确的业务输入返回 400，格式为 `{"message":"具体原因"}`。单份读写接口返回的 `SyncResponse.documents` 也可能包含其他修订变化；CLI 对单次修改只显示 `accepted` 和 `conflicts`，不会将这些额外变化当作本次修改结果。
## 业务数据

字段完整定义位于 `shared/src/main/kotlin/top/e404/training/shared/Domain.kt`。

### 部位、动作与素材

- `Muscle`：`id`、中文 `name`、肌肉示意图使用的 `mapKeys`。
- `Media`：`id`、`name`、不可变 `fileName`、`mimeType`、来源 `source` 和 `sourceUrl`。
- `Exercise`：名称、动作类型、主要/辅助部位、器械类别、步骤、素材引用、单双侧及计量方式。空步骤表示没有记录教学说明，不可据此推测动作形式。
- 动作与素材身份独立；替换图解使用新的不可变文件名，再更新素材引用。
- 网站来源动作在用户选择后才创建对应动作文档；可靠关联的已有动作保留原标识。来源素材直接访问其线上地址，不属于 `/v1/files` 的本地素材文件，也不进入训练备份。`source_unknown` 器械标签表示来源条件未知，不代表无需器械；自动筛选不将其视为健身房已具备。具体训练组的次数或时长由 `SetTarget` 表达，不改写已有训练快照。

### 健身房、器械、分化和计划

- `Gym` 保存健身房名称及备注；`Equipment` 保存所属健身房、类别、原始重量单位/含义和可选素材。器械配置不是当前现场可用的保证。
- `Program.slots` 是有序的用户分化项；分化项数量即 N，不固定推拉腿。
- `Plan` 保存动作、目标组、器械选择及备注。计划快照不会改写已开始训练。
- 负重读数保留原始单位与 `meaning`，不做全局换算；`load=null` 表示没有记录外部重量，不代表零重量。
- 下一项按真实完成组推进：至少一组真实结果才算练过，完全无组不推进。自由训练和无法唯一匹配分化项的记录不推进。

### 实际训练与独立活动

- 每个业务日期只有一个 `Session`，`id=day-YYYY-MM-DD`，使用UTC+8日期。根字段记录真实组、反馈、快照和状态；来源原事实以 `sourceRecords` 保存，不复制重复组。
- `startedAt`、`endedAt`、组完成时间可以为空，未知精度不补造。状态为 `active`、`completed` 或 `cancelled`；没有完成组不能标记完成。
- `ActivityRecord` 使用独立 `kind=activity`，字段含 `id`、`name`、`localDate`、可空 `durationSeconds`、`distanceMeters`、`notes`、`sourceId`、`startedAt`、`endedAt`。已知结束不得早于开始；起始时间已知时日期必须匹配其UTC+8日期。来源时长与起止间隔分别保留。
- 活动不属于 `Session`，不进入训练组统计或分化轮换。用户活动及日历记录只包含用户明确手动录入或明确导入的活动；自动采集的小米摘要不创建或更新`ActivityRecord`，只在健康数据详情读取。既有自动投影仅在`id`以`xiaomi-`开头且`sourceId`以`misports:activities:`开头时识别为隐藏条目；不得只按`sourceId`前缀过滤，例如明确导入徒步`activity-misports-20261017-walking`及手动UUID必须保留。此规则不删除或修改活动及健康原始资料，不改变schema和格式版本。
- `GET /v1/history` 的日期筛选同时作用于 Session 与可见的用户活动，但训练统计只依据实际 Session 组计算。

## 健康数据 API v1

除 `GET /health` 外，以上所有路径均使用 `Authorization: Bearer <唯一token>`。健康数据使用专用SQLite表，不进入 `documents`、`/v1/sync` 或普通历史文档流。时间戳全部为 epoch 毫秒，`localDate` 为 UTC+8 日期；健康源秒时间只在采集边界乘以 1000。缺失字段使用 `null` 或省略，绝不将缺失写成 0。自然来源键相同且内容完全相同的重试返回 `unchanged`，不增加日期修订。

`GET /v1/capabilities` 返回 `{"appVersion":"0.0.6","formatVersion":6,"backupFormatVersion":6,"healthApiVersion":1}`。客户端必须在写入前核对三个格式版本；不匹配时停止写入并要求升级配套端。

健康导入请求形如 `{"dataset":"steps","records":[{"sourceId":"...", "localDate":"2026-10-27","measuredAt":179...,"payload":{"steps":123,"distanceMeters":45.0,"caloriesKcal":2.1,"deviceId":null,"source":"cloud"}}]}`。每批只能包含一个 `HealthDataset`，来源标识在该批内唯一；服务端严格解码对应的共享 `@Serializable` payload，未知字段拒绝。`HealthImportResult` 为 `accepted`、`unchanged`、`revisionByDate`。

共享payload：

- `steps`: `steps: Long`、`distanceMeters: Double?`（m）、`caloriesKcal: Double?`（计步热量，kcal）、`deviceId: String?`、`source: String?`。
- `calories`: `caloriesKcal: Double`（独立活动热量，kcal）、`deviceId: String?`、`source: String?`。不可与计步热量合并。
- `heartRate`：`bpm: Int`、`sampleType: "active"|"passive"`、`deviceId: String?`、`source: String?`。小米运动真实心率明细使用 `sampleType="active"`、`source="xiaomi_workout"`，`measuredAt`为该样本的实际epoch毫秒，BPM必须是实际大于零的读数；稳定 `sourceId` 格式为 `workout:<activity.source_id>:<offset_s>`，其中offset来自该活动的运动采样相对位置。日常采样仍按其原有类型和来源保存，不用于拼成运动心率曲线。运动明细经现有 `/v1/health/import` 导入，可由既有分页与离线缓存读取；重复来源及相同内容保持幂等，不需要数据库结构变更。
- `restingHeartRate`: `bpm: Int`、`source: String?`。
- `sleep`: `startAt/endAt: Long`、`durationMinutes: Int`、`asleepMinutes/awakeMinutes: Int?`、`awakeCount: Int?`、`avgHeartRate/minHeartRate/maxHeartRate: Double?`、`deepMinutes/lightMinutes/remMinutes: Int?`、`score: Double?`、`isNap: Boolean`、`algorithmVersion: Int?`、`stages: [{startAt: Long,endAt: Long,stage: String}]`。来源睡眠分钟数与端点时间分别保留；`asleepMinutes`、`awakeMinutes` 在来源为空时保持空，按起床日期归档。
- `body`: `weightKg: Double`、`bmi/bodyFatPct/muscleMassKg/muscleRatePct/skeletalMuscleMassKg/bodyMoistureMassKg/moistureRatePct/boneMassKg/proteinMassKg/proteinRatePct/fatMassKg/fatFreeBodyKg/visceralFat/basalMetabolismKcal/bodyAge/bodyScore/whr/standardWeightKg: Double?`、`extra: JsonObject?`、`deviceId: String?`。体脂等率值保留源数值，不再猜测比例换算。
- `activity`: `sportKey: String`、`sportCategory: String?`、`sportTypeCode: Int?`、`startAt/endAt: Long`、`durationSeconds: Int?`、`mainPosture: Int?` 原样保留源编码不解释成文本，其余摘要值依 `ActivityPayload` 定义。距离是米、时长是秒、速度是米/秒、步幅是厘米、配速依字段分别为秒/公里或秒/100米；来源运动时长不由端点差重算。

`HealthDaySummary`含`localDate`、`revision`及可空`steps`、`distanceMeters`、`stepCaloriesKcal`、`calorieMinutesKcal`、`heartRateSamples`、`restingHeartRateBpm`、`sleepSessions`、`bodyMeasurements`、`activities`。每个字段无观测时为空；其中`activities`是只读健康运动摘要，不代表用户活动`ActivityRecord`，不自动投影进用户运动列表或日历。`HealthDaysResponse`为`days`和`revisionByDate`。日汇总只在请求范围内查询。

详情页固定最多 500 条，按 `measuredAt,sourceId` 升序。`HealthRecordsPage` 包含 `date`、`dataset`、该日期 `revision`、`records` 和可空 `nextCursor`；把游标原样传给下一页。页间 revision 改变必须重新读取该日期/数据集，不能标记混合版本完整。

`POST /v1/health/collection/request`不需要请求体；已有待执行、运行中或提交中的请求会合并并返回`requested:false`。`HealthStatus`的`coveredDates`和`revisionByDate`表示服务端已有记录的日期，`collection`为`HealthCollectionState`：`requested`、`stage`、`phase`、`requestedAt`、`startedAt`、`submittedAt`、`lastSuccessAt`、`failedAt`、`error`（除`stage`外可空）。阶段为`idle/requested/running/submitted/published/succeeded/failed`。状态事件请求为`{"event":"started|submitted|published|succeeded|failed","phase":null,"error":null}`；`published`只表示发布已有资料，不推进`lastSuccessAt`，但更新`submittedAt`并清除本次错误；只有`succeeded`（完整采集轮次成功）推进`lastSuccessAt`。`published`为终态，可再次请求采集；`failed`不更新`lastSuccessAt`。健康运动摘要是只读健康事实；采集及`/v1/health/import`不得自动创建或更新用户`ActivityRecord`。

所有健康DTO位于 `shared/src/main/kotlin/top/e404/training/shared/HealthDomain.kt`，可直接复用 `HealthRecord.decodePayload<T>()`；不要在客户端另建同义模型。
## 同步

请求格式：

```json
{"cursor":0,"mutations":[]}
```

每个修改为 `{"document": Document, "baseRevision": number, "restoreSession": boolean}`。`restoreSession` 可省略，默认 `false`。仅在用户明确选择恢复完整备份中的一份非删除每日 `Session` 时设为 `true`；它允许恢复该日完整的canonical内容（包括撤销备份后追加的来源、动作、目标、完成状态及反馈）。改变内容仍必须匹配 `baseRevision`；相同内容的重试按幂等提交确认，不增加修订。此标记不能用于计划、其他资料或删除标记。普通编辑、同步和历史来源归并都必须保持默认 `false`。

响应字段：

- `cursor`：本次读取到的服务器修订位置。
- `documents`：该游标之后的最新文档及删除标记。
- `accepted`：已保存或重复确认的提交，包含服务器修订号。
- `conflicts`：`{"local": Document, "remote": Document}`，本地修改和当前服务器内容都保留。

相同内容的提交直接确认已有修订，不增加实际记录或重复记账（包括显式恢复请求的相同内容重试）。内容不同且版本不一致的文档不覆盖服务器；变更内容的显式恢复也遵守此冲突规则。同一批内不冲突且符合业务关联的文档仍可保存。新增和更新按关联先后处理，删除按相反顺序处理。

手机在 Room 中先提交实际组及计时状态，再交给网络同步。日期弹窗的统一修改先验证全部输入，并在一个本地事务中保存变更，再复用相同同步接口；云端仍按文档返回接收或修订冲突，不承诺跨文档原子提交。断网不阻止训练及本机编辑。重新联网后上传待同步资料；有冲突时从运动记录右上角同步图标打开详情，查看两份具体内容并明确选择。同步结果仅短暂提示，详细原因、重试和采集入口按需打开；不改变服务端协议。素材下载中断后会重新检查缺失文件，不把已经确认的元数据等同于图像已经下载成功。

连接到另一套后端时使用完整备份迁移：先导出，再将 ZIP 导入目标后端，之后配置手机地址和 token。不要把更改地址当作清空服务器或自动复制另一套服务器的操作。

## 完整备份

ZIP 内含：

`data.json` 新导出为 `{"formatVersion":6,"documents":[...],"exportedAt":epoch毫秒,"healthManifest":{...}}`，读取端兼容版本1至6。版本6必须具有健康清单；`healthManifest` 字段为 `scope`（`complete` 或 `cache`）、`coveredDates: String[]`、`datasets: HealthDataset[]`、`recordCount: Long`。健康数据不塞入 `data.json`，独立写入 `health.jsonl`，每行一个 `{"dataset":"steps","record":{"sourceId":"...","localDate":"YYYY-MM-DD","measuredAt":epoch毫秒,"payload":{...}}}`，流式读写避免把历史采样全装进内存。

服务器 `/v1/export` 始终导出 `scope=complete` 和全量健康资料；服务器 `/v1/import` 只接受完整范围，缓存包必须拒绝，不得用部分资料覆盖服务器。`replace=false` 对健康自然键已有的不同内容返回409并保留冲突；`replace=true` 才允许覆盖。内容相同重复导入幂等，不增加记录或日期修订。手机离线备份可设为 `scope=cache`，清单只声明本机已下载日期/数据集，不能称作完整健康备份；含完整健康资料的恢复需在线使用同一后端导入接口。

版本5和6使用每日canonical Session 格式，非canonical活跃Session拒绝读取。旧v1—v4读取时保留来源原payload，只清理废弃字段；导入阶段显式归并为稳定 `day-YYYY-MM-DD` 文档，按来源稳定标识去重实际组，动作ID确定性重映射；旧来源以删除标记退出同步。合并保留真实组、分段、原始单位、已知时间、分类、计划快照与反馈，不补造未知时间或目标。重复导入相同旧来源不增加组或覆盖根级反馈。`replace=true` 将完整canonical每日训练作为显式整日恢复；普通数据写入和旧版本历史归并仍受来源、动作、目标及已记录完成组的不可改写规则约束。canonical singleton从备份读取、导出及重启归一时保持原payload，不再投影改写。

CLI 支持 `list/get/put/delete/sync/history/recommend/next/export/import/upload/download`。健康只读/请求命令为 `capabilities`、`health-status`、`health-days --from YYYY-MM-DD --to YYYY-MM-DD`、`health-records --date YYYY-MM-DD --dataset <steps|calories|heartRate|restingHeartRate|sleep|body|activity> [--cursor CURSOR]`、`health-collect`（请求或合并服务端采集任务，不采集手机本地数据）。可用文档种类包括 `activity`，例如 `python scripts/training.py list activity` 与 `python scripts/training.py get activity <活动ID>`；使用 `put activity <活动ID> --file <资料JSON> --base-revision 0` 新建活动，已有资料先 `get` 并提交读取到的修订号。`TRAINING_URL` 指定后端；`TRAINING_TOKEN` 或 `TRAINING_TOKEN_FILE` 提供凭据。凭据不要放进命令参数、JSON、运动记录或临时文件。退出码 3 表示冲突，其他非零表示操作失败。
