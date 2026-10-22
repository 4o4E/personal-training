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

- `kind`：`muscle`、`media`、`exercise`、`gym`、`equipment`、`program`、`plan`、`session`。
- `data.id` 必须与文档 `id` 相同。
- `revision` 是服务器修订号，不是训练日期；不要根据它推断训练时间。
- 保存时提供读取该文档时的 `baseRevision`，新资料使用 `0`。不要为了覆盖冲突自动读取新修订号重试。
- 删除保留修订标记；正在被计划、器械或历史素材使用的资料不能直接删除。

## 路由

| 请求 | 作用 | 返回 |
|---|---|---|
| `GET /health` | 不含个人资料的健康状态 | `{"status":"ok"}` |
| `GET /v1/documents?kind=plan&includeDeleted=false` | 查询一种或全部资料 | `Document[]` |
| `GET /v1/documents/{kind}/{id}` | 读取单份未删除资料 | `Document`，不存在时 404 |
| `PUT /v1/documents/{kind}/{id}` | 提交 `{"document": Document, "baseRevision": number}` | `SyncResponse`；版本冲突为 409 |
| `DELETE /v1/documents/{kind}/{id}?revision=number` | 带修订号删除 | `SyncResponse`；版本冲突为 409 |
| `POST /v1/sync` | 批量提交并拉取修订变化 | `SyncResponse`，逐份返回版本冲突 |
| `GET /v1/history?from=YYYY-MM-DD&to=YYYY-MM-DD` | 按真实训练开始日期查询，包含两端日期 | `HistorySummary` |
| `GET /v1/recommendations?type=strength&muscles=chest,triceps&gymId=ID` | 按部位和实际器械选择训练候选 | `Exercise[]` |
| `GET /v1/recommendations?type=stretch&exercises=ID1,ID2&gymId=ID` | 按所选动作的主要及辅助部位推荐拉伸 | `Exercise[]`，不会写入计划 |
| `GET /v1/programs/{id}/next` | 按已完成训练计算下一项 | `SplitSlot` |
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
- `unit`：`kg` / `lb`；`meaning`：`total`（总重量）、`per_hand`（单只重量）、`per_side`（单侧配重）。器械配置是录入参考，各组仍独立保留原始读数。
- 只提供已经记录的器械条件，不把动作目录里存在某器械解释为健身房已经拥有该器械。

### 分化方案、具体计划和组目标

- `Program`：`id`、`name`、有序 `slots`。
- `SplitSlot`：`id`、`name`、目标 `muscles`。`slots` 的数量就是 N，推拉腿不是固定枚举。
- `Plan`：`id`、`name`、可选 `programId` / `slotId`、可选 `gymId`、可选本地日期 `date`、有序 `items`、`notes`。
- `PlanItem`：`id`、`exerciseId`、可选实际 `equipmentId`、有序 `sets`、`notes`。
- `SetTarget`：可选 `reps` / `repsMax` 或 `seconds`、可选 `load`、`side`、`restSeconds`。
- `Load`：`value`、`unit`、`meaning`、`mode`。`mode=assistance` 是助力，不是外部负重。
- `side`：`both`（双侧或不分侧）、`left`、`right`。单侧拉伸可按左右各一组表示。
- `load=null` 是没有记录外部读数，不等同于零重量。`20 kg` 和 `60 lb` 在同一训练中原样保留，不进行全局显示换算。
- 器械的当前类别和位置可能变化，计划仍可保存和恢复；开始训练前需要确认该计划实际可执行。
- 无日期且绑定分化项的计划可按完成顺序重复执行，每次执行创建独立的 `Session`；指定日期和未绑定分化的计划不自动循环。编辑未来计划不改写已有 `Session.snapshot`。
- 当前方案及每个方案中选用的循环计划是手机本机偏好，不增加业务字段或接口。一个训练项有多份无日期计划时，由用户明确选择；修改已选计划的同一标识可以更新未来内容。

### 实际训练

`Session` 保存 `id`、`name`、开始时的完整 `snapshot: Plan`、动作教学内容 `exercises`、真实 `startedAt`、可选 `endedAt`、必填 `status`、`results`、`notes`、实际执行安排 `items` 和已被替代的 `skippedItems`。

- 时间为 epoch 毫秒；日期查看与筛选统一 UTC+8。
- `status`：`active` / `completed` / `cancelled`。没有实际完成组的训练不能标记完成。
- `SetResult`：`itemId`、从零开始的 `setIndex`、实际 `reps` / `seconds`、实际 `load`、`side`、`completedAt`。
- 只有 `results` 才是已完成组。未执行的 `sets` 不计入历史。实际次数和重量可以与目标不同。
- 完成部分组后仍可明确结束为完成，以实际量保存并推进分化；取消也保存已经练过的组，但不推进分化。
- `snapshot`、开始时间和已有动作教学不可改写。已有完成组的条目和目标不能替换。替换剩余动作时保留原条目与其真实结果，将未执行部分标记跳过，再插入新的执行条目。
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

- `data.json`：`{"formatVersion":1,"documents":[...],"exportedAt":epoch毫秒}`。
- `media/`：所有未删除素材引用的实际图像文件。

格式只接收该版本的数据包，不直接解释其他应用的 CSV。导入保留逻辑标识、原始单位、助力含义、侧别、实际结果和开始时计划；缺少的实际结果不能由计划值补齐。不同内容必须明确允许替换；素材先完整准备，再提交数据库，失败不会覆盖已有可见图片。已有相同文件不会复制成另一份素材。连接 token、后端地址、手机系统权限、本地提醒及当前方案、循环计划选择、教程、外观偏好不进入数据包。

CLI 支持 `list/get/put/delete/sync/history/recommend/next/export/import/upload/download`。`TRAINING_URL` 指定后端；`TRAINING_TOKEN` 或 `TRAINING_TOKEN_FILE` 提供凭据。凭据不要放进命令参数、JSON、运动记录或临时文件。退出码 3 表示冲突，其他非零表示操作失败。
