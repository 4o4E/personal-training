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

- `kind`：`muscle`、`media`、`exercise`、`gym`、`equipment`、`program`、`plan`、`session`、`arrangement`。
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
| `GET /v1/history?from=YYYY-MM-DD&to=YYYY-MM-DD` | 按真实 UTC+8 训练日期查询，包含两端日期 | `HistorySummary` |
| `GET /v1/recommendations?type=strength&muscles=chest,triceps&gymId=ID` | 按部位和实际器械选择训练候选 | `Exercise[]` |
| `GET /v1/recommendations?type=stretch&exercises=ID1,ID2&gymId=ID` | 按所选动作的主要及辅助部位推荐拉伸 | `Exercise[]`，不会写入计划 |
| `GET /v1/programs/{id}/next` | 按已完成训练与当前手动选择计算下一项 | `SplitSlot` |
| `GET /v1/files/{name}` | 下载图解或器械图片 | 实际图像字节，缺失时 404 |
| `PUT /v1/files/{name}` | 上传独立图像 | 204；同名同内容可重复，同名不同内容为 409 |
| `GET /v1/export` | 导出完整资料和素材 | ZIP |
| `POST /v1/import?replace=false` | 导入 ZIP，不覆盖已有不同内容 | 已保存文档列表；不同内容为 409 |
| `POST /v1/import?replace=true` | 明确允许替换已有不同内容 | 已保存文档列表 |

缺少有效 token 返回 401；不正确的业务输入返回 400，格式为 `{"message":"具体原因"}`。单份读写接口返回的 `SyncResponse.documents` 也可能包含其他修订变化；CLI 对单次修改只显示 `accepted` 和 `conflicts`，不会将这些额外变化当作本次修改结果。

## 业务数据

字段完整定义位于 `shared/src/main/kotlin/top/e404/training/shared/Domain.kt`；以下说明业务含义。

### 部位、动作与素材

- `Muscle`：`id`、中文 `name`、肌肉示意图使用的 `mapKeys`。
- `Media`：`id`、`name`、不可变 `fileName`、`mimeType`、来源 `source` 和 `sourceUrl`。支持 JPEG、PNG、WebP、GIF、SVG。
- `Exercise`：`id`、`name`、`type`（`strength` / `stretch`）、`muscles`、`secondaryMuscles`、`equipmentTags`、中文步骤 `instructions`、`mediaIds`、`unilateral`、`bodyweight`、`measurement`（`reps` / `seconds`）、`defaultSeconds`、`notes`、`loadMode`（`external` / `assistance`）。
- 动作与素材身份独立。替换图解时先上传新文件名，再修改 `Media.fileName` 或动作的素材引用，不创建重复动作。
- 器械类别名称保存在 `catalog/equipment-tags.json`。动作要求的多个类别可以由同一健身房内不同器械共同提供，例如哑铃加训练凳。第一类别是选择具体器械时使用的主要类别。

### 健身房和器械

- `Gym`：`id`、`name`、`notes`。
- `Equipment`：`id`、`name`、`gymId`、`tags`、原始 `unit`、`meaning`、可选 `mediaId`、`instructions`、`notes`。
`unit`：`kg` / `lb`；`meaning`：`total`（总重量）、`per_hand`（单只重量）、`per_side`（单侧配重）、`unknown`（重量含义未记录）。器械配置是录入参考，各组仍独立保留原始读数。
- 只提供已经记录的器械条件，不把动作目录里存在某器械解释为健身房已经拥有该器械。

### 分化方案、具体计划和组目标

- `Program`：`id`、`name`、有序 `slots`。
- `SplitSlot`：`id`、`name`、目标 `muscles`。`slots` 的数量就是 N，推拉腿不是固定枚举。
- `Plan`：`id`、`name`、可选 `programId` / `slotId`、可选 `gymId`、可选本地日期 `date`、有序 `items`、`notes`。
- `PlanItem`：`id`、`exerciseId`、可选实际 `equipmentId`、有序 `sets`、`notes`。
- `SetTarget`：可选 `reps` / `repsMax` 或 `seconds`、可选 `load`、`side`、`restSeconds`。
- `Load`：`value`、`unit`、`meaning`、`mode`。`mode=assistance` 是助力，不是外部负重。
- `side`：`both`（双侧或不分侧）、`left`、`right`。单侧拉伸可按左右各一组表示。
- `load=null` 表示没有记录外部读数，不等同于零重量；同一训练中 `20 kg` 与 `60 lb` 原样保留，不进行全局显示换算。
- 器械类别和位置可能变化，计划仍可保存和恢复；开始训练前需要确认该计划实际可执行。
- 无日期且绑定分化项的计划可循环执行；指定日期和未绑定分化的计划不自动循环。未来计划编辑不会改写已有 Session。
- 下一项训练优先按已安排日期及完成情况选择；同一分化项存在多份无日期计划时，由用户明确选用。
- 当前方案、各方案的选用计划及下次手动指定保存为 `arrangement/current`：`programId` 可空，`selectedPlans` 按 `programId → slotId → planId` 嵌套，`nextOverrides` 按 `programId` 保存 `{id, slotId}`。
- `nextOverrides.id` 是本次手动选择的稳定标识。开始训练时复制到 Session；完成该指定训练只消费同一个 ID，取消不消费，也不会清除训练开始后新设的选择。
- 新建 arrangement 后必须保证方案、分化项和计划引用均有效。更新或删除被引用方案/计划时同步调整安排；无效引用不能自动换成别的计划。

### 实际训练

`Session` 保存 `id`、`name`、动作教学 `exercises`、`status`、`results`、`notes`、实际执行安排 `items`、被替代的 `skippedItems` 和开始时明确选择的可空 `splitSlotName`。实时训练要求 `snapshot: Plan` 与真实 `startedAt`；只有日期的历史训练允许 `snapshot`、`startedAt`、`endedAt` 和组 `completedAt` 为 `null`，并提供不可变 `historicalSource: {sourceId, localDate}`。历史训练不能标记为进行中，也不能推断精确时间。

- epoch 毫秒表示已知精确时间；日期筛选与实时训练日期统一 UTC+8，历史来源日期直接使用 `historicalSource.localDate`。
- `status`：`active` / `completed` / `cancelled`。没有实际完成组的训练不能标记完成。
- `SetResult`：`itemId`、非负 `setIndex`、实际 `reps` / `seconds`、实际 `load`、`side`、可空 `completedAt` 和有序 `segments`。
- `SetSegment` 保留分段实际 `reps` 与 `load`；分段次数之和必须等于整组次数，分段重量不可再压成一个组重量；一条 `SetResult` 仍只计一个实际组。
- `Load.meaning` 支持 `total`、`per_hand`、`per_side`、`unknown`；未知含义保留原始数值与单位，界面仅显示数字和原始单位，不猜测重量含义；助力模式仍显示助力语义。
- 完成部分组后仍可明确结束为完成，以实际量保存并推进分化；取消也保存已经练过的组，但不推进分化。
- `snapshot`、开始时间和已有动作教学不可改写。实时训练已有完成组的条目和目标不能替换。替换剩余动作时保留原条目与真实结果，将未执行部分标记跳过，再插入新执行条目。
- 编辑未来计划不会修改已经开始的训练。

`HistorySummary` 包含实际 `sessions`、`strengthSets`、`stretchSets` 和 `strengthSetsByMuscle`。统计包含进行中及取消训练中的真实完成组；分化轮换只考虑 `status=completed`。复合动作涉及多个主要部位，部位计数之和不一定等于总力量组数，不应据此重复统计总量。

## 同步

请求格式：

```json
{"cursor":0,"mutations":[]}
```

每个修改为 `{"document": Document, "baseRevision": number}`。响应字段：

- `cursor`：本次读取到的服务器修订位置。
- `documents`：该游标之后的最新文档及删除标记。
- `accepted`：已保存或重复确认的提交，包含服务器修订号。
- `conflicts`：`{"local": Document, "remote": Document}`，本地修改和当前服务器内容都保留。

相同内容重复提交直接确认已有修订，不增加实际记录或重复记账。版本不一致且内容不同的文档不覆盖服务器；同一批内不冲突且符合业务关联的文档仍可保存。新增和更新按关联先后处理，删除按相反顺序处理。

手机在 Room 中先提交实际组及计时状态，再交给网络同步。断网不阻止训练。重新联网后上传待同步资料；有冲突时在设置中查看两份具体内容并明确选择。素材下载中断后会重新检查缺失文件，不把已经确认的元数据等同于图像已经下载成功。

连接到另一套后端时使用完整备份迁移：先导出，再将 ZIP 导入目标后端，之后配置手机地址和 token。不要把更改地址当作清空服务器或自动复制另一套服务器的操作。

## 完整备份

ZIP 内含：

- `data.json`：新导出为 `{"formatVersion":3,"documents":[...],"exportedAt":epoch毫秒}`；读取端兼容版本 1、2 和 3。

版本 3 保留 nullable 历史时间与计划、历史来源的 `sourceId` 和 `localDate`、`splitSlotName`、组内多段重量和未知重量含义。版本 1、2 读取时会移除已废弃的 `historicalSource.originalText`，其余原生字段及用户备注保持。0.0.3 旧库存、手机缓存与同步文档也会在 Session 解码边界定向投影移除此字段，避免要求清缓存或改写数据库；不会自动转换 `notes`。版本 3 导入和新文档写入包含该字段会被拒绝。导出会投影为版本 3，并仅移除旧库存中的该废弃字段，不改写数据库或用户备注。版本 1、2 的既有精确时间、计划与结果按原义恢复；导出不含凭据。不同内容必须明确允许替换；素材先完整准备，再提交数据库，失败不会覆盖已有可见图片。

CLI 支持 `list/get/put/delete/sync/history/recommend/next/export/import/upload/download`。`TRAINING_URL` 指定后端；`TRAINING_TOKEN` 或 `TRAINING_TOKEN_FILE` 提供凭据。凭据不要放进命令参数、JSON、运动记录或临时文件。退出码 3 表示冲突，其他非零表示操作失败。
